# PR spec: 브로커로 보내지 못한 발행 기록을 주기적으로 다시 보내고 완료 행을 정리한다

## 목적
브로커 장애로 `FAILED`가 됐거나 전송 중 끊긴 발행 기록(`event_publication`)을 사람이 `resubmit`해야 한다. 완료 행도 지우는 작업이 없어 계속 쌓인다. [kafka-event-redelivery](2026-09-26-kafka-event-redelivery.md)가 PR ③으로 미룬 알려진 빈틈이다.

기대 결과는 두 가지다. 브로커가 돌아오면 밀린 기록이 사람 손 없이 나간다. 완료 뒤 보존 기간이 지난 행은 저절로 지워진다.

## 범위
소유 모듈은 `platform`이다. `EventPublicationMetrics` 옆에 재전송·정리 작업 `EventPublicationResubmitter` 하나를 둔다. 업무 모듈의 Command·Query·Event와 HTTP API는 바뀌지 않는다. 스키마는 `event_publication`에 `status` 인덱스 하나를 더한다(아래 스키마 변경 검토).

**주기 작업.** `@EnableScheduling`을 켜고 `@Scheduled` 작업 하나가 아래 세 단계를 차례로 한다. 주기는 `resilientpos.event-publication.resubmit-interval`(기본 `PT1M`)이고 첫 실행도 한 주기 뒤다. 통합 테스트는 이 값을 하루로 늘린다. 여러 테스트가 DB를 함께 쓰면서 `FAILED` 행과 완료 행을 넣기 때문에, 작업이 스스로 돌면 결과가 실행 순서에 따라 바뀐다. 작업이 던지면 Spring 스케줄러가 로그를 남기고 다음 주기를 그대로 돈다.

**1. 끊긴 기록 표시.** `PUBLISHED`·`PROCESSING`·`RESUBMITTED`인 채 마지막 시도(`coalesce(last_resubmission_date, publication_date)`)부터 `resilientpos.event-publication.stale-after`(기본 5분)가 지난 미완료 기록을 `FAILED`로 바꾼다. 이어서 완료됐는데 `RESUBMITTED`로 남은 행(아래 재전송의 fail-open 틈에서 생긴다)을 `COMPLETED`로 돌린다. 둘 다 `JdbcTemplate`의 조건부 UPDATE 한 문장씩이고 쿼리 상한은 10초다. 트랜잭션 밖이라 상한이 없으면 잠금에 걸린 UPDATE가 소켓 상한(30초)까지 공용 스케줄러 스레드를 붙잡는다. 끊긴 기록의 기준은 metric의 `stale`과 같다. 여러 pod가 겹쳐 돌아도 같은 행을 같은 값으로 바꾼다. `stale-after`는 producer 전송 상한(`max.block.ms` + `delivery.timeout.ms`, 기본 약 3분)보다 길어야 한다.

Modulith staleness 모니터(`spring.modulith.events.staleness.*`)는 쓰지 않는다. 2.1.1 소스를 대조했더니 두 가지가 맞지 않았다.
- `RESUBMITTED`도 최초 `publication_date`로 잰다. 5분 넘게 묵은 이벤트는 다시 보낸 직후 다음 모니터 주기에 `FAILED`로 되돌아가 앞 전송이 끝나기 전에 또 나간다.
- 상태로 행을 고른 뒤 ID로만 UPDATE한다(`status != FAILED`). 그 사이 externalizer가 완료한 행을 `FAILED`로 덮으면 완료된 이벤트가 다시 나간다.

**2. 재전송.** `FailedEventPublications.resubmit(ResubmissionOptions)`으로 `FAILED` 기록을 오래된 것부터 다시 보낸다.
- `batchSize` 500, `maxInFlight` 500, `minAge` 0이다. `maxInFlight`는 DB의 `RESUBMITTED` 행 수로 센다(모든 pod 합계). 500건을 넘는 실패는 다음 주기에 이어서 나간다.
- `Lock.executeWithLockNoTx(상수 키) { }`로 pod 사이를 줄 세운다. 이 진입점은 이 PR에서 `Lock`에 더한다. 트랜잭션을 열지 않고 잠그기만 하며, 바깥 트랜잭션 안에서 부르면 잠그기 전에 거절한다. 합류하면 바깥이 커밋하기 전에 락이 풀리기 때문이다. `executeWithLock`은 전과 같다(바깥 트랜잭션 허용, `REQUIRES_NEW`). 재전송은 트랜잭션 밖에서 돈다. 그래서 Modulith 저장소(클래스 수준 `@Transactional`)의 호출마다 따로 커밋된다. 재전송 표시(`markResubmitted`, `status != RESUBMITTED`인 행만 바꾸는 조건부 UPDATE)가 한 건씩 곧바로 커밋되고, 다음 pod는 표시된 행을 건너뛴다. 기존 `executeWithLock`(`REQUIRES_NEW`)을 쓰면 표시한 500행의 잠금을 블록이 끝날 때까지 쥔다. 그동안 비동기 externalizer가 완료 표시를 하려고 커넥션을 쥔 채 그 잠금을 기다린다(executor 8, Hikari 10).
- `Lock`이 fail-open이라 Redis 장애나 대기 초과 때는 잠금 없이 돈다. 그때 행 단위 조건부 UPDATE는 같은 행을 두 pod가 동시에 전송 중으로 두는 것을 막는다. 방금 완료된 행의 재전송은 막지 못한다. 한 pod가 읽어 둔 행을 다른 pod가 먼저 완료시키면 `status != RESUBMITTED` 조건을 통과해 다시 나간다. 이 중복 전송은 멱등 consumer가 견딘다. 그 행은 `completion_date`가 찬 채 `RESUBMITTED`로 남는데, Modulith의 완료 표시가 `completion_date`가 빈 행만 바꾸기 때문이다. 1단계가 이 행을 `COMPLETED`로 돌린다.
- 재전송 횟수에 상한을 두지 않는다. Modulith는 `FAILED` 행을 읽은 **뒤에** `withFilter`로 거른다. 그래서 상한에 걸린 행이 읽기 자리를 계속 차지하고, 새 실패까지 막는다.
- 읽어 온 `DefaultJpaEventPublication` 엔티티는 저장소 호출의 트랜잭션이 끝나면 분리된다. Modulith 엔티티에는 변경 메서드가 없다. 표시·삭제는 모두 JPQL 벌크 문장이라 dirty checking이 쓸 것이 없다.

**3. 정리.** `CompletedEventPublications.deletePublicationsOlderThan(보존 기간)`으로 완료 뒤 보존 기간이 지난 행을 지운다. 보존 기간은 `resilientpos.event-publication.retention`(기본 `P7D`)이다. 멱등 DELETE라 락 밖에서 따로 커밋한다. 정리가 실패해도 재전송에 번지지 않는다. 앞 단계가 던지면 뒤 단계는 그 주기에 건너뛰고 다음 주기에 다시 한다. kafka-event-redelivery spec이 `purge`라고 적은 것의 실제 API가 이것이다.

**설정 검증.** 보존 기간이 0 이하면 기동에서 막는다. 0이면 완료 행이 곧바로 지워진다. `stale-after`는 `EventPublicationMetrics`가 이미 양수만 받는다.

**복구와 관측성.**
- 브로커 장애: 매분 최대 500건씩 다시 보낸다. 브로커가 돌아오면 밀린 기록이 오래된 것부터 나간다.
- pod 종료: 끊긴 기록은 마지막 시도부터 5분 뒤 `FAILED`가 되고 다음 주기에 다시 나간다.
- 새 metric은 없다. 이제 `failed` gauge에는 자동 재전송을 기다리는 행도 포함된다. `stale`은 5분을 넘기면 곧 `failed`로 옮겨 가므로 잠깐만 보인다. 사람이 볼 조건은 `failed`가 한 시간 내내 0이 아닌 경우다(`max(...) > 0`에 `for: 1h`). observability wiki에 적는다.

## 제외
- PostgreSQL advisory lock, ShedLock 같은 새 조정 수단. 조정은 #68의 `Lock` 하나로 한다.
- 재전송 횟수 상한과 영영 나가지 못하는 기록의 격리. Modulith API로는 읽는 쿼리를 바꿀 수 없다. 격리하려면 재전송을 직접 구현하거나 격리 테이블을 두어야 한다. 한계 절과 domain-model §4.3의 탐지 SQL로 대신한다.
- 작업 생존 metric(마지막 성공 시각, 주기당 선택·전송 건수, 락 fail-open 횟수)과 알림 규칙. 수집기가 아직 없다. 알림 규칙을 만드는 PR이 함께 정한다.
- 첫 정리 때의 대량 DELETE 분할과 JDBC 전환. 배포된 환경이 없어 쌓인 완료 행이 로컬 compose 볼륨뿐이다. 한 번의 DELETE가 쿼리 상한(10초)이나 트랜잭션 상한(30초)을 넘으면 정리를 `JdbcTemplate`으로 나눠 지운다. 재전송은 externalizer 리스너를 다시 불러야 해서 JDBC로 옮길 수 없다. 전환 시점은 domain-model §4.3에 적었다.
- 재전송 간격을 늘리는 backoff. 매분 같은 간격이다.
- DLT 재투입.

## 한계
- **영영 나가지 못하는 기록.** 리스너가 사라졌거나(리스너를 바꾸는 배포의 조건을 어김) 이벤트 타입을 역직렬화할 수 없는 기록이 여기에 해당한다. 이런 기록은 매 주기 다시 시도되고, Modulith가 행 단위로 예외를 잡아 로그를 남긴다. 이런 기록이 500건을 넘게 쌓이면 가장 오래된 행이라 읽기를 모두 차지해 새 실패가 나가지 못한다. domain-model §4.3에 탐지 SQL과 조치(원인을 되살리거나, 버리기로 한 기록만 완료 처리)를 적었다.
- 락 TTL은 10초다. 재전송 블록이 10초를 넘으면 락이 먼저 풀리고 다른 pod가 들어올 수 있다. 그때도 행 단위 조건부 UPDATE가 같은 행의 동시 전송을 막는다.
- 모든 pod가 매분 세 단계를 돈다. 두 번째 이후 pod는 락을 기다린 뒤 표시된 행을 건너뛰므로 대부분 읽기만 한다. 끊긴 기록 표시와 정리는 겹쳐도 결과가 같다.
- 발행 뒤 5분 넘게 externalizer 대기열에 머문 이벤트는 `FAILED`가 되어 한 번 더 나갈 수 있다. 브로커 장애로 전송이 `max.block.ms` 동안 막혀 대기열이 밀린 경우다. consumer가 멱등이라 정합성 문제는 아니다.

## 관련 문서
- [kafka-event-redelivery](2026-09-26-kafka-event-redelivery.md)(PR ③ 계획)
- [shared-distributed-lock](2026-09-28-shared-distributed-lock.md)(`Lock`)

## 변경 경로
- src/main/kotlin/com/resilientpos/platform/**
- src/main/resources/application.yaml
- src/main/resources/db/migration/**
- src/test/kotlin/com/resilientpos/**
- docs/spec/2026-09-29-event-publication-resubmit.md
- docs/wiki/domain-model.md
- docs/wiki/observability.md

## 인수 조건
1. 브로커 전송이 `FAILED`인 발행 기록을 작업 한 번이 다시 보낸다. 그 이벤트가 토픽에 도착하고 기록은 완료된다.
2. 재전송이 읽어 온 발행 기록 엔티티를 flush가 고쳐 쓰지 않는다(발행 기록 엔티티의 Hibernate UPDATE 0건).
3. 완료 뒤 보존 기간이 지난 기록은 지워진다. 보존 기간 안의 완료 기록과 미완료 기록은 남는다.
4. 작업은 `Lock` 블록 안에서 재전송한다. 같은 키를 다른 쪽이 쥐고 있으면 기다린다.
5. 작업이 공용 스케줄러에 올라간다.
6. 끊긴 기록은 마지막 시도부터 `stale-after`가 지나야 `FAILED`가 된다. 오래전에 발행됐어도 방금 다시 보낸 기록은 그대로다. 완료됐는데 `RESUBMITTED`로 남은 행은 `FAILED`가 아니라 `COMPLETED`가 된다.
7. 보존 기간이 0 이하면 기동이 실패한다.
8. `Lock.executeWithLockNoTx` 블록은 트랜잭션 없이 돌고, 블록 안의 쓰기는 블록이 끝나기 전에 커밋된다. 바깥 트랜잭션 안에서 부르면 잠그기 전에 거절한다.
9. `event_publication_status_idx`가 유효한(`indisvalid`) 인덱스로 만들어진다.
10. `./gradlew test` 전체와 `python3 scripts/check_pr.py --base origin/main --worktree`가 통과한다.

### Checkpoint와 검증 명령
| Checkpoint | 검증 명령 | 의존 |
|---|---|---|
| lock-no-tx (8) | `./gradlew test --tests 'com.resilientpos.platform.LockIntegrationTest'` | 없음 |
| resubmitter (1~7) | `./gradlew test --tests 'com.resilientpos.platform.EventPublicationResubmitterIntegrationTest'` | lock-no-tx |
| 인덱스 (9) | `./gradlew test --tests 'com.resilientpos.SchemaMigrationTest'` | 없음 |
| 전체 (10) | `./gradlew test` · `python3 scripts/check_pr.py --base origin/main --worktree` | 모두 |

## 스키마 변경 검토
`event_publication`에 `create index concurrently event_publication_status_idx on event_publication (status)`를 더한다(`V009`).

**실측.** PostgreSQL 18 컨테이너(`shared_buffers` 512MB)에 V004 스키마를 만들고, 7일에 고르게 퍼진 완료 행과 미완료 행(`FAILED`, `RESUBMITTED` 500, 막 발행된 `PUBLISHED`·`PROCESSING` 1,200, 완료된 재전송 행 50)을 넣었다. 작업·Modulith·metric이 매분 pod마다 부르는 쿼리는 5번씩 돌렸다. 쓰기는 pgbench(8 연결, 15초 × 3)로 한 이벤트의 생애(INSERT → PROCESSING → 완료, `serialized_event`로 찾음)를 돌렸다. 값은 중앙값이다.

| 쿼리 (완료 1,000만 건, 힙 5.2GB) | 인덱스 없음 | `(status)` | partial `(status, publication_date)` 미완료만 |
|---|---|---|---|
| 완료된 재전송 행 정리 UPDATE | 887ms Seq Scan | 1.7ms | 0.8ms |
| `countByStatus(RESUBMITTED)` 리터럴 | 413ms Parallel Seq | 0.09ms | 0.1ms |
| `countByStatus` 파라미터 generic plan | 493ms Parallel Seq | 31ms Parallel Index | 737ms Parallel Seq |
| `findFailedPublications` 500건 | 645ms Parallel Seq | 4.3ms | 4.0ms |
| 끊긴 기록 UPDATE | 10ms | 7.8ms | 29ms |
| 인덱스 크기 | - | 67MB(중복 제거) | 0.2MB |
| 쓰기 TPS | 5,916 | 5,313 | 6,406 |

- 브로커 장애로 `FAILED`가 20만 건 밀린 경우(완료 1,000만 건): 인덱스가 없으면 끊긴 기록 UPDATE 101ms, `findFailedPublications` 658ms, 정리 UPDATE 1.1초다. `(status)`면 14ms, 30ms, 1ms다.
- 완료 100만 건에서도 인덱스가 없으면 세 쿼리가 각각 0.2~1.8초였다.
- 쓰기 TPS는 같은 구성 안에서도 실행마다 2천~1만 1천으로 흔들려 세 구성의 차이를 가를 수 없었다. 인덱스의 쓰기 비용은 측정 잡음보다 작다.
- 인덱스 없음이 치르는 비용: pod마다 매분 5GB 힙을 Parallel Seq Scan으로 세 번 훑는다. 두 번은 Modulith 자신의 쿼리라 이 작업이 없어도 재전송(`resubmit`)을 부르는 순간 생긴다.
- partial 인덱스를 고르지 않은 이유: Modulith의 `countByStatus`는 JPQL 파라미터라 JDBC 서버 준비 문장이 generic plan으로 바뀌면 partial 조건을 증명하지 못해 Seq Scan으로 떨어진다. 전체 인덱스는 generic plan에서도 쓰인다.
- 측정 스크립트는 저장소에 두지 않는다. 적재 SQL, 쿼리, pgbench 스크립트의 요지는 이 절에 적었다.

질문별 답(도메인 모델 §4.7):
1. 새 값이 아니라 기존 컬럼의 인덱스다. 도출·비교할 대체 수단은 없다. `completion_date` 인덱스는 `status`로 거르는 세 쿼리를 좁히지 못한다. 완료된 재전송 행 정리는 `completion_date is not null`이라 완료 행 대부분에 맞는다. `countByStatus`는 `completion_date`를 조건에 두지 않는다. `findFailedPublications`는 `status is null` 가지에만 두어 `status = FAILED` 가지를 좁히지 못한다.
2. 지금 코드가 읽는다. 재전송 작업의 완료된 재전송 행 정리, Modulith의 `countByStatus`·`findFailedPublications`다.
3. 인덱스라 기존 행은 빌드가 채운다.
4. 불변식의 방어선이 아니다. 성능 인덱스다.
5. 정본은 `status` 컬럼 하나다. 인덱스는 사본이 아니다.
6. `status`를 읽고 쓰는 경합은 조건부 UPDATE가 막는다(`markResubmitted`의 `status != RESUBMITTED`, 끊긴 기록 표시의 `completion_date is null`). 인덱스는 그 조건을 바꾸지 않는다.
7. `CREATE INDEX CONCURRENTLY`라 `SHARE UPDATE EXCLUSIVE`를 잡아 빌드 동안 읽기와 쓰기를 막지 않는다. 트랜잭션 밖에서 돌아야 해서 이 문장만 담은 마이그레이션으로 두고, 처음 넣는 CONCURRENTLY 마이그레이션이라 `spring.flyway.postgresql.transactional-lock=false`를 함께 설정한다. 실패하면 INVALID 인덱스와 `flyway_schema_history`의 `success = false` 행이 남는다. 다시 배포하기 전에 `drop index concurrently if exists event_publication_status_idx`로 인덱스를 지우고, 실패 행을 정리(`flyway repair` 또는 행 삭제)한다. 실패 행이 남아 있으면 다음 migrate가 멈춘다. 마이그레이션에 `if not exists`를 쓰지 않는다. INVALID 인덱스가 남아 있어도 건너뛰기 때문이다. `SchemaMigrationTest`가 인덱스의 `indisvalid`를 확인한다. `CONCURRENTLY`는 진행 중인 쓰기 트랜잭션이 끝나기를 기다린다. 그래서 떠 있는 이전 버전 pod의 긴 트랜잭션이 기동을 늦출 수 있고, 상한은 트랜잭션 상한 30초다. 트랜잭션 밖이라 `SET LOCAL lock_timeout`은 효과가 없다. `transactional-lock` 키는 Boot 4.1.1 설정 메타데이터(`spring-boot-flyway`)에 있는 것을 확인했다. V001~V008에는 `CONCURRENTLY` 문장이 없다. 제약이 아니라 위반 행 검사는 없다. 배포된 환경이 없어 지금 대상 행은 로컬 compose 볼륨뿐이다.
8. 인덱스 추가라 이전 버전의 기동과 쓰기에 영향이 없다.
9. payload나 캐시 DTO에 실리지 않는다.
- 대조 기록: code-reviewer 에이전트(읽기 전용), 2026-09-29, 불일치 ① 질문 1 근거(`findFailedPublications`는 `status is null` 가지에 `completion_date` 조건이 있다, 결론은 유지) ② 질문 7 재실행 절차(`IF EXISTS` 누락, `flyway_schema_history` 실패 행 정리 누락) ③ V009 주석·결과 절의 "0.4~1.1초"가 완료 1,000만 건 실측(0.41~0.89초)과 다르다. 셋 다 문구를 고쳐 반영했고, §4.7에 실패 행 정리와 대기 조건을 더했다.

## 검증
- 정상: `FAILED` 기록이 작업 한 번 뒤 토픽에 나가고 완료된다. 끊긴 기록만 `FAILED`가 된다. 오래된 완료 행이 지워진다.
- 실패: 잘못된 설정은 기동을 막는다. 완료 기록과 방금 다시 보낸 기록은 `FAILED`가 되지 않는다.
- 통합 테스트는 Testcontainers의 PostgreSQL·Redis·Kafka를 쓴다(Docker는 OrbStack).

## Wiki 영향
- [domain-model](../wiki/domain-model.md) §4.3
  - "아직 사람이 `resubmit`한다" 문장을 이 작업의 세 단계로 바꾼다. 영영 나가지 못하는 기록의 탐지 SQL과 조치, JDBC 전환 시점을 적는다.
  - gauge 항목의 "재전송이 없어 스스로 꺼지지 않으니 손으로 완료 처리한다"는 조치를 지운다. 그대로 두면 곧 다시 나갈 메시지를 버리게 된다.
  - 남은 결정 목록에서 staleness monitor를 뺀다.
  - `Lock` 항목에 `executeWithLockNoTx`의 용도와 바깥 트랜잭션 거절을 적는다.
  - Modulith 버전을 올리는 절차에 재전송 작업이 기대는 동작의 대조 항목을 더한다.
- [observability](../wiki/observability.md): `failed` gauge에 자동 재전송 대기 행이 포함된다는 점, `stale`이 잠깐만 보인다는 점, 사람이 볼 조건을 적는다.
- current-state는 볼트 파일이라 저장소 커밋 없이 볼트에서 고친다. 대상은 "브로커로 보내지 못한 기록을 다시 보내는 절차가 아직 없다" 문단이다.

## 분할 계획
개발 컨텍스트는 하나다. 발행 기록의 재전송과 정리다. `Lock`의 새 진입점은 이 작업만 쓰므로 함께 둔다. 작업 시작 시점(origin/main e6e2c4a, #68 머지 뒤)에 열린 PR이 없어 변경 경로가 겹치지 않는다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 1, `브로커 전송이 실패한 기록을 다시 보내 토픽에 도착하고 완료된다` | `./gradlew test --tests 'com.resilientpos.platform.EventPublicationResubmitterIntegrationTest'`. 본문이 빈 `resubmitAndPurge`로 실행. 되돌린 `FAILED` 행이 20초 안에 완료되지 않아 `ConditionTimeoutException` | 같은 명령. `FailedEventPublications.resubmit` 뒤 통과 | `./gradlew test` 통과 |
| 인수 2, `읽어 온 발행 기록 엔티티를 flush로 고쳐 쓰지 않는다` | characterization. Modulith 엔티티에 변경 메서드가 없어 처음부터 통과한다. 발행 기록 엔티티를 실제로 읽었는지(`loadCount` 증가)도 확인해 빈 검사가 되지 않게 했다 | 같은 명령 통과 | `./gradlew test` 통과 |
| 인수 3, `보존 기간이 지난 완료 기록만 지운다` | 같은 명령. 8일 전 완료 행이 남아 `AssertionFailedError` | 같은 명령. `deletePublicationsOlderThan(retention)` 뒤 통과 | `./gradlew test` 통과 |
| 인수 4, `다른 쪽이 락을 쥐고 있으면 풀릴 때까지 기다린다` | 같은 명령. 락과 무관하게 500ms 안에 끝나 `AssertionFailedError`(TimeoutException을 기대) | 같은 명령. 재전송을 `Lock` 블록으로 감싼 뒤 통과 | `./gradlew test` 통과 |
| 인수 5, `작업이 공용 스케줄러에 올라간다` | 같은 명령. `@Scheduled`가 없어 `AssertionFailedError` | 같은 명령. `@Scheduled`와 `@EnableScheduling` 뒤 통과 | `./gradlew test` 통과 |
| 인수 6, `끊긴 기록은 마지막 시도부터 5분이 지나야 실패로 바뀌고 완료된 재전송 행은 완료로 돌린다` | 같은 명령. 본문이 빈 `markStalePublicationsFailed`로 실행. 5분 지난 행이 그대로라 `AssertionFailedError`. 완료된 재전송 행을 더한 뒤에도 그 행이 `RESUBMITTED`로 남아 다시 RED | 같은 명령. 조건부 UPDATE 두 문장 뒤 통과. 기준을 `publication_date`로 바꾼 변이, `completion_date is null`을 뺀 변이, 완료로 돌리는 문장을 뺀 변이가 모두 실패하는 것을 확인하고 되돌렸다 | `./gradlew test` 통과 |
| 인수 7, `보존 기간이 0 이하면 만들지 않는다` | 같은 명령. 검증이 없어 `AssertionFailedError`(IllegalArgumentException을 기대) | 같은 명령. `init`의 `require` 뒤 통과 | `./gradlew test` 통과 |
| 인수 8, `LockIntegrationTest`의 `트랜잭션 없는 블록은 줄만 세우고 쓰기는 각자 바로 커밋한다`·`트랜잭션 없는 블록을 트랜잭션 안에서 부르면 잠그기 전에 거절한다` | `./gradlew test --tests 'com.resilientpos.platform.LockIntegrationTest'`. 새 진입점이 `executeWithLock`을 그대로 부르게 두고 실행해 트랜잭션이 열려 있어 `AssertionFailedError`. 거절 테스트는 `check`가 없어 `AssertionFailedError`(IllegalStateException을 기대) | 같은 명령. 잠금·해제를 private `locked`로 빼고 두 진입점이 각각 부르게 한 뒤, `executeWithLockNoTx` 첫 줄에 `check`를 두어 통과. 블록 안의 읽기는 `REQUIRES_NEW` 템플릿(다른 커넥션)이라, 새 진입점이 트랜잭션을 여는 변이에서 0을 읽어 실패하는 것을 확인했다 | `./gradlew test --tests 'com.resilientpos.platform.Lock*'`, `./gradlew test` 통과 |

## 결과
- `./gradlew test` 전체 통과(286개), `python3 scripts/check_pr.py --base origin/main --worktree` PASS.
- platform에 `EventPublicationResubmitter`를 더했다. 1분마다 끊긴 기록을 `FAILED`로 바꾸고, `Lock.executeWithLockNoTx` 안에서 `FAILED` 기록을 다시 보내고(한 주기 500건), 락 밖에서 완료 뒤 7일이 지난 기록을 지운다.
- `Lock`에 `executeWithLockNoTx`를 더했다. `executeWithLock`은 그것에 `Tx.newTx`를 씌운 것이다.
- `PlatformConfiguration`에 `@EnableScheduling`을 명시했다. 지금까지는 Moments 자동 구성이 켜 주던 것이다.
- `application.yaml`에 `resilientpos.event-publication`의 `stale-after`·`retention`·`resubmit-interval`과 `spring.flyway.postgresql.transactional-lock: false`를 더했다.
- `V009`로 `event_publication (status)` 인덱스를 `CONCURRENTLY` 더했다. 실측 근거는 스키마 변경 검토 절이다.
- 합의 뒤 바꾼 것
  - **재전송 횟수 상한(60회)을 뺐다.** `withFilter`가 읽은 뒤에 걸러서, 상한에 걸린 행이 읽기 자리를 차지해 새 실패까지 막았다.
  - **Modulith staleness 모니터 대신 조건부 UPDATE를 쓴다.** multi-review(Claude·Codex)가 두 결함을 짚었고 소스로 확인했다. 재전송 행을 최초 발행 시각으로 잰다는 것, 완료된 행을 `FAILED`로 덮는다는 것이다.
  - **재전송을 트랜잭션 없는 `Lock` 블록으로, 정리를 락 밖으로 옮겼다.** 표시한 행의 잠금을 블록이 끝날 때까지 쥐어 externalizer가 커넥션을 쥔 채 기다리는 문제와, 정리 실패가 재전송 표시까지 롤백하는 문제를 없앴다.
  - 작업 예외를 직접 잡지 않는다. Spring 스케줄러가 반복 작업의 예외를 로그로 남기고 주기를 이어 가므로 인수 5를 "공용 스케줄러에 올라간다"로 바꿨다.
  - 주기를 설정값으로 열었다. 통합 테스트가 작업을 멈출 방법이 필요했다.
  - 첫 전송도 `completion_attempts`를 1로 센다. 재전송 테스트는 절댓값 대신 한 번 늘었는지를 본다.
- multi-review에서 함께 고친 것
  - 테스트
    - 미완료 가짜 행을 방금 발행한 것으로 넣는다.
    - Hibernate 통계를 발행 기록 엔티티로 좁혔다.
    - 락 테스트는 락을 한 번만 푼다.
    - staleness 테스트는 설정값이 아니라 상태 전이를 본다.
  - 문서
    - domain-model gauge 항목의 수동 완료 처리 조치를 지웠다.
    - Redis 용도 주석에 재전송 줄 세우기를 더했다.
    - observability에 사람이 볼 조건을 식으로 적었다.
- 2차 multi-review(Claude·Codex)에서 고친 것
  - 락이 fail-open일 때 완료된 행이 `RESUBMITTED`로 남아 `maxInFlight` 칸을 차지하는 문제. Modulith의 완료 표시(`completion_date is null`)와 `countByStatus`(status만 봄)를 소스로 확인하고 1단계에 완료로 돌리는 문장을 더했다.
  - `executeWithLockNoTx`가 바깥 트랜잭션을 막지 않던 문제. `check`로 거절한다. `executeWithLock`과 공유하던 경로를 private `locked`로 나눠 `executeWithLock`의 바깥 트랜잭션 허용은 그대로 두었다.
  - 1단계 UPDATE에 쿼리 상한 10초를 걸었다(전용 `JdbcTemplate`).
  - 테스트: 트랜잭션 없는 블록 테스트가 같은 트랜잭션에 합류해 읽던 것을 `REQUIRES_NEW`로 바꿨다. 락 대기 테스트는 단언이 실패해도 락을 푼다. 완료됐는데 `PROCESSING`인 가짜 행을 실제로 생기는 모양(`RESUBMITTED` + 완료 시각)으로 바꿨다.
  - 문서: observability의 알림식을 `for: 1h`로 바꿨다(`min_over_time`은 사라진 시계열을 0으로 세지 않는다). `Lock` 객체 KDoc을 진입점별로 나눴다. `stale-after`의 하한, 단계가 실패할 때의 건너뜀, Modulith 버전을 올릴 때 대조할 항목을 적었다.
  - 반영하지 않은 것: 테스트 시각을 주입한 `Clock`으로 맞추는 제안(앱의 `Clock`도 `systemUTC()`이고 경계 여유가 1분이다).
  - 인덱스는 실측으로 정했다. 완료 1,000만 건에서 `status`로 거르는 세 쿼리가 인덱스 없이 0.4~0.9초였고, partial 인덱스는 generic plan에서 쓰이지 않아 `(status)` 전체 인덱스를 더했다.
- multi-review에서 미룬 것: 영영 나가지 못하는 기록의 격리, 작업 생존 metric(제외 절).
- wiki: domain-model §4.3, observability, current-state(볼트)를 고쳤다.
