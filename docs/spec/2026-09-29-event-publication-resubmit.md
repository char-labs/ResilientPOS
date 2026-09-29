# PR spec: 브로커로 보내지 못한 발행 기록을 주기적으로 다시 보내고 완료 행을 정리한다

## 목적
브로커 장애로 `FAILED`가 됐거나 전송 중 끊긴 발행 기록(`event_publication`)을 사람이 `resubmit`해야 한다. 완료 행도 지우는 작업이 없어 계속 쌓인다. [kafka-event-redelivery](2026-09-26-kafka-event-redelivery.md)가 PR ③으로 미룬 알려진 빈틈이다.

기대 결과는 두 가지다. 한 시간 안의 브로커 장애는 사람 손 없이 복구된다. 완료 뒤 보존 기간이 지난 행은 저절로 지워진다.

## 범위
소유 모듈은 `platform`이다. `EventPublicationMetrics` 옆에 재전송·정리 작업 하나를 둔다. 업무 모듈의 Command·Query·Event, HTTP API, 스키마는 바뀌지 않는다.

**주기 작업.** `@EnableScheduling`을 켜고 `@Scheduled(fixedDelay = 1분)` 작업 하나가 재전송과 정리를 차례로 한다. staleness 모니터가 `SchedulingConfigurer`라서 스케줄링을 켜야 돈다. 공용 스케줄러 스레드는 하나다. 이 작업은 재전송할 기록을 표시하고 전송을 비동기 externalizer(`@ApplicationModuleListener`)에 넘기기만 하므로 금방 끝난다. `EventPublicationMetrics`는 전용 스레드라 영향이 없다.

**한 번에 한 pod.** 작업 본문을 `Lock.executeWithLock(상수 키) { }` 블록 안에서 돌린다. Modulith의 `markResubmitted`는 조건부 UPDATE(`status != RESUBMITTED`, 1행일 때만 전송)다. 그래서 두 pod가 동시에 돌아도 행 하나를 가져가는 쪽은 하나다. 하지만 한 pod가 재전송을 끝내 `COMPLETED`로 바꾼 행을, 그 전에 `FAILED`로 읽어 둔 다른 pod가 다시 가져가는 틈이 남는다. 락은 이 틈을 줄인다. 블록 안에서 Modulith 저장소(클래스 수준 `@Transactional`)의 표시가 블록 트랜잭션에 합류하므로, 표시가 커밋된 뒤에 락이 풀리고 다음 pod는 표시된 행을 건너뛴다. `Lock`이 fail-open이라 Redis 장애나 대기 초과 때는 잠금 없이 돈다. 그 경우는 행 단위 조건부 UPDATE와 멱등 consumer가 받친다. PostgreSQL advisory lock은 쓰지 않는다. 저장소에서 쓴 적이 없는 수단이고(#61에서 기각), 잠금 수단은 #68의 `Lock` 하나로 모은다.

**끊긴 기록.** Modulith staleness 모니터가 `PUBLISHED`·`PROCESSING`·`RESUBMITTED`인 채 기준 시간이 지난 기록을 `FAILED`로 바꾼다. 세 기준 모두 metric의 `resilientpos.event-publication.stale-after`(기본 5분)를 그대로 쓴다. 그래서 metric의 `stale` 기준과 같다. `resubmitted`도 켜는 이유가 있다. 재전송 중에 pod가 죽으면 행이 `RESUBMITTED`로 남아 `maxInFlight`를 계속 차지하기 때문이다. 이 모니터는 모든 pod에서 돈다. 같은 조건으로 같은 값을 쓰는 UPDATE라 겹쳐도 해가 없다.

**재전송.** `FailedEventPublications.resubmit(ResubmissionOptions)`로 한다.
- `batchSize` 500, `maxInFlight` 500, `minAge` 0이다. 브로커 장애 동안에는 매분 최대 500건이 다시 실패한다.
- `withFilter`로 `completionAttempts`가 상한 미만인 행만 다시 보낸다. 상한은 `resilientpos.event-publication.max-resubmissions`(기본 60, 1분 주기라 약 1시간)이다.
- 상한에 걸린 행은 `FAILED`로 남는다. 기존 `event.publication.incomplete{status=failed}` gauge에 보이고, 원인을 고친 뒤 사람이 `resubmit`한다.

**정리.** `CompletedEventPublications.deletePublicationsOlderThan(보존 기간)`으로 완료 뒤 보존 기간이 지난 행을 지운다. 보존 기간은 `resilientpos.event-publication.retention`(기본 `P7D`)이다. kafka-event-redelivery spec이 `purge`라고 적은 것의 실제 API가 이것이다. 미완료 행은 지우지 않는다.

**설정 검증.** 상한과 보존 기간이 0 이하면 기동에서 막는다. 0이면 재전송이 모두 걸러지거나 완료 행이 곧바로 지워진다.

**복구와 관측성.**
- 브로커 장애: 매분 다시 보낸다. 한 시간 넘게 이어지면 상한에 걸린 행이 `failed` gauge에 남고, 사람이 원인을 고친 뒤 `resubmit`한다.
- pod 종료: 끊긴 기록은 5분 뒤 staleness 모니터가 `FAILED`로 바꾸고, 다음 주기에 다시 나간다.
- 작업 한 번이 실패하면 로그를 남기고 다음 주기에 다시 한다. 스케줄러가 멈추지 않게 예외를 밖으로 던지지 않는다.
- 새 metric은 없다. 이제 `failed` gauge에는 자동 재전송을 기다리는 행도 포함된다. 사람이 볼 대상은 `failed`가 한 시간 넘게 줄지 않는 경우다. observability wiki에 적는다.

## 제외
- PostgreSQL advisory lock, ShedLock 같은 새 조정 수단.
- 재전송 metric(재전송 건수, 상한에 걸린 건수). 기존 `failed`·`stale` gauge로 감지한다. 알림 규칙과 수집기도 아직 없다.
- 첫 정리 때의 대량 DELETE 분할. 배포된 환경이 없어 쌓인 완료 행이 로컬 compose 볼륨뿐이다.
- 재전송 간격을 늘리는 backoff. 매분 같은 간격이다.
- DLT 재투입, 영구 실패 행을 따로 옮기는 격리 테이블.

## 한계
- 락 TTL은 10초다. 정리 DELETE가 길어져 블록이 10초를 넘으면 락이 먼저 풀리고 다른 pod가 들어올 수 있다. 그때도 행 단위 조건부 UPDATE가 같은 행의 동시 전송을 막는다.
- 블록 트랜잭션이 커밋될 때까지 표시한 행은 잠겨 있다. 그동안 비동기 externalizer의 완료 표시(`REQUIRES_NEW`)는 그 행을 기다린다. 블록은 이 표시를 기다리지 않으므로 교착은 없다. 기다림은 블록이 끝날 때 풀린다.
- 블록이 롤백되면 표시는 되돌아가도 이미 넘긴 전송은 나간다. 다음 주기에 한 번 더 나가고, consumer가 멱등으로 견딘다.

## 관련 문서
없음

## 변경 경로
- src/main/kotlin/com/resilientpos/platform/**
- src/main/resources/application.yaml
- src/test/kotlin/com/resilientpos/**
- src/test/resources/**
- docs/spec/2026-09-29-event-publication-resubmit.md
- docs/wiki/domain-model.md
- docs/wiki/observability.md

## 인수 조건
1. 브로커 전송이 `FAILED`인 발행 기록을 작업 한 번이 다시 보낸다. 그 이벤트가 토픽에 도착하고 기록은 완료된다.
2. `completionAttempts`가 상한 이상인 `FAILED` 기록은 다시 보내지 않고 `FAILED`로 남는다.
3. 완료 뒤 보존 기간이 지난 기록은 지워진다. 보존 기간 안의 완료 기록과 미완료 기록은 남는다.
4. 작업은 `Lock.executeWithLock` 블록 안에서 돈다. 같은 키를 다른 쪽이 쥐고 있으면 기다린다.
5. 작업 한 번에서 예외가 나도 던지지 않고 로그를 남긴다. 다음 주기는 그대로 돈다.
6. staleness 모니터의 `published`·`processing`·`resubmitted`가 `resilientpos.event-publication.stale-after`와 같은 값으로 켜진다.
7. 상한이나 보존 기간이 0 이하면 기동이 실패한다.
8. `./gradlew test` 전체와 `python3 scripts/check_pr.py --base origin/main --worktree`가 통과한다.

### Checkpoint와 검증 명령
| Checkpoint | 검증 명령 | 의존 |
|---|---|---|
| resubmit (1~2, 4~5) | `./gradlew test --tests 'com.resilientpos.platform.EventPublicationResubmit*'` | 없음 |
| purge (3) | 같은 명령 | 없음 |
| 설정 (6~7) | 같은 명령 | 없음 |
| 전체 (8) | `./gradlew test` · `python3 scripts/check_pr.py --base origin/main --worktree` | 모두 |

## 검증
- 정상: `FAILED` 기록이 작업 한 번 뒤 토픽에 나가고 완료된다. 오래된 완료 행이 지워진다.
- 실패: 상한에 걸린 기록은 남는다. 작업 예외는 삼키고 로그를 남긴다. 잘못된 설정은 기동을 막는다.
- 통합 테스트는 Testcontainers의 PostgreSQL·Redis·Kafka를 쓴다(Docker는 OrbStack).

## Wiki 영향
- [domain-model](../wiki/domain-model.md) §4.3: "아직 사람이 `resubmit`한다. 주기 재전송과 완료 행 정리는 후속 PR이다" 문장을 이 작업의 동작으로 바꾼다. 매분, `Lock` 한 블록, staleness 5분, 상한 60회, 보존 7일, 상한을 넘은 행의 수동 조치를 적는다.
- [observability](../wiki/observability.md): `failed` gauge에 자동 재전송 대기 행이 포함된다는 점과, 사람이 볼 조건(한 시간 넘게 줄지 않음)을 적는다.
- current-state는 볼트 파일이라 저장소 커밋 없이 볼트에서 고친다. 대상은 "브로커로 보내지 못한 기록을 다시 보내는 절차가 아직 없다" 문단이다.

## 분할 계획
개발 컨텍스트는 하나다. 발행 기록의 재전송과 정리다. 작업 시작 시점(origin/main e6e2c4a, #68 머지 뒤)에 열린 PR이 없어 변경 경로가 겹치지 않는다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 1, `FAILED` 기록을 다시 보내 토픽에 도착하고 완료된다 | 미실행 | 미실행 | 미실행 |
| 인수 2, 상한 이상인 기록은 다시 보내지 않는다 | 미실행 | 미실행 | 미실행 |
| 인수 3, 보존 기간이 지난 완료 기록만 지운다 | 미실행 | 미실행 | 미실행 |
| 인수 4, 같은 키를 쥐고 있으면 기다린다 | 미실행 | 미실행 | 미실행 |
| 인수 5, 작업 예외를 삼키고 로그를 남긴다 | 미실행 | 미실행 | 미실행 |
| 인수 6~7, staleness 설정 연결과 설정 검증 | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
