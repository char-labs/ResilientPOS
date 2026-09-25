# PR spec: 실패하거나 끊긴 이벤트 발행 기록을 metric으로 센다

## 목적
커밋 뒤 리스너(`payment`의 `OrderPaymentRequestedListener`·`PaymentAttemptCreatedListener`, `order`의 `PaymentApprovedListener`, `fulfillment`의 `OrderPaidListener`)가 실패하면 `event_publication`에 `FAILED`로, 종료나 프로세스 사망으로 끊기면 `PUBLISHED`·`PROCESSING`으로 남는다. 그것을 세거나 알리는 장치가 없어서 사람이 DB를 직접 보기 전에는 드러나지 않는다. 특히 **결제는 승인됐는데 주문이 `CHECKOUT_REQUESTED`에 머무는** 경우가 이렇게 숨는다.

기대 결과: 리스너별로 실패한 기록과 끊긴 기록의 수가 Micrometer gauge로 나가서, 수집기가 붙으면 `> 0` 알림 하나로 잡힌다. 재전송은 하지 않는다.

## 범위
소유 모듈은 `platform`이다. 업무 모듈의 코드는 바꾸지 않는다.

**gauge `event.publication.incomplete`** (Prometheus 이름 `event_publication_incomplete`). 태그는 둘이다.
- `status`: `failed`는 리스너가 던져 `FAILED`인 기록이다. `stale`은 `FAILED`도 완료도 아닌데(`PUBLISHED`·`PROCESSING`·`RESUBMITTED`) 기준 시간이 지난 기록이다. 경과 시간은 발행 시각부터, 재전송된 기록은 마지막 재전송 시각부터 잰다(`coalesce(last_resubmission_date, publication_date)`). 오래된 이벤트를 방금 다시 보냈다면 아직 처리 중일 수 있어서다. 막 발행돼 처리 중인 기록은 세지 않는다. `status`가 비어 있는 행(v1 스키마 시절의 것)은 `stale`로 센다.
- `listener`: `listener_id`(`패키지.클래스.메서드(이벤트타입)`)에서 뽑은 클래스 단순 이름이다. 중첩 클래스는 `바깥$안`으로 저장되어 안쪽 이름만 쓴다. 같은 단순 이름의 리스너가 둘이면 합산한다. `@ApplicationModuleListener(id = ...)`처럼 id를 직접 주는 리스너는 이 형식이 아니어서 이름이 어긋난다. 지금은 그런 리스너가 없다. 지금 4개라 카디널리티가 낮다. `orderId` 같은 식별자는 태그로 쓰지 않는다.

**세는 방법.** `event_publication`을 `completion_date is null` 조건으로 한 번 조회해 `(status, listener)`별로 센다. 미완료 행만 읽으므로 기존 `completion_date` 인덱스를 쓰고, 완료 행이 쌓여도 비용이 늘지 않는다. Modulith의 `EventPublicationRepository.countByStatus`는 `events-core`가 runtime 범위라 main에서 컴파일되지 않고, 조건도 맞지 않아 쓰지 않는다. 조회만 하며 상태를 바꾸지 않는다.

**갱신.** 리스너 목록이 데이터에서 나오므로 `MultiGauge`를 1분마다 `@Scheduled`로 다시 등록한다(overwrite). 기록이 사라진 조합의 시계열은 없어진다. 갱신은 `@Synchronized`로 직렬화한다. 두 갱신이 겹치면 먼저 조회한 쪽이 나중에 등록해 새 값을 덮을 수 있어서다. overwrite는 매 주기 행을 지웠다가 다시 등록하므로 그 짧은 틈에 scrape가 들어오면 한 번 빠질 수 있다. 1분 주기의 `> 0` 알림에는 영향이 없어 받아들인다. `PlatformConfiguration`에 `@EnableScheduling`을 더한다. Boot가 `TaskScheduler`를 등록하게 되므로 **커밋 뒤 리스너가 계속 Boot의 task executor(`task-N` 스레드)에서 도는지** 인수 조건으로 확인한다.

**조회 상한.** 공유 `JdbcTemplate`을 건드리지 않고 이 조회 전용 `JdbcTemplate`에 쿼리 타임아웃 10초를 건다(JPA 쿼리 상한과 같은 값). 트랜잭션 밖이라 트랜잭션 상한이 걸리지 않고, 없으면 스케줄러 스레드 하나가 소켓 상한 30초까지 묶인다.

**기준 시간.** 5분이다. 리스너는 짧은 DB 쓰기이고 트랜잭션 상한이 30초, provider는 지연 없는 Fake다. 여기에 큐 대기의 여유를 둔다. `@Value`의 기본값으로 두고 `application.yaml`에는 적지 않는다. 실제 provider가 붙으면 환경 변수로 바꾼다. 시각은 주입된 `Clock`으로 계산한다.

**여러 인스턴스.** 모든 인스턴스가 같은 DB를 세므로 같은 값을 낸다. 알림은 `sum`이 아니라 `max`로 모은다. 이 점을 wiki에 적는다.

**병렬 브랜치와의 충돌 회피.** 아직 push되지 않은 `feature/prometheus-structured-logging`이 `build.gradle.kts`(`micrometer-registry-prometheus`), `application.yaml`의 끝(`management:` 블록), 새 `docs/wiki/observability.md`, `docs/wiki/README.md`, `README.md`를 바꾼다. 이 PR은 그 파일들을 건드리지 않는다. gauge는 actuator가 이미 끌어오는 `micrometer-core`만 쓴다. 테스트는 `/actuator/prometheus`가 아니라 `MeterRegistry` 빈을 직접 본다. 그 PR이 머지되면 공통 태그 `application`이 이 gauge에도 붙고 `/actuator/prometheus`로 나간다.

계약: 새 metric 하나가 생긴다. HTTP API, 이벤트, 스키마는 바뀌지 않는다. `listener` 태그 값이 리스너 클래스 이름이라 리스너 이름을 바꾸면 알림 규칙도 함께 바꾼다(리스너 이름은 이미 저장 형식이다, 도메인 모델 §4.3).

복구: 해당 없음. 감지만 한다. 조회가 실패하면 그 주기의 갱신을 건너뛰고 WARN 로그를 남긴다. 이전 값이 그대로 남는다. 그래서 조회가 계속 실패하는 동안 새 실패는 드러나지 않는다(WARN 로그로만 보인다). 마지막 성공 시각 gauge는 알림 규칙을 정하는 쪽이 필요하면 더한다.

**`failed` 알림은 스스로 꺼지지 않는다.** 재전송이 없으므로 `FAILED` 행은 사람이 조치하기 전까지 남는다. 조치(업무 상태를 바로잡거나 `FailedEventPublications.resubmit`)를 끝내면 그 행에 `completion_date`를 채우거나 지워야 시계열이 사라진다. 배포하면 이미 쌓여 있던 미완료 행 때문에 알림이 바로 켜질 수 있으니, 배포 전에 `select status, listener_id, count(*) from event_publication where completion_date is null group by 1, 2`로 세어 둔다.

관측성: 이 PR의 산출물이 곧 관측성이다. 알림을 받은 뒤 어느 주문인지는 `serialized_event`에서 찾는다. 찾는 조건은 기존 spec들의 것(`listener_id like '%PaymentApprovedListener%' and status = 'FAILED'` 등)을 그대로 쓴다.

## 제외
- 재전송·재발행(`FailedEventPublications.resubmit`)과 그 운영 진입점. Kafka를 들이는 설계가 맡는다. 진입점을 열면 인증과 다중 인스턴스 중복 전송을 함께 정해야 한다.
- Modulith staleness 모니터(`spring.modulith.events.staleness.*`). 오래된 미완료를 `FAILED`로 바꾸는 장치라 감지가 아니라 상태 변경이다.
- 헬스 인디케이터. 업무 실패 한 건으로 인스턴스가 `DOWN`이 되면 안 된다.
- 알림 규칙, 대시보드, 수집기 설정. 수집기를 붙이는 쪽이 정한다.
- `/actuator/prometheus` 노출과 공통 태그. `feature/prometheus-structured-logging`이 한다.
- `docs/wiki/observability.md`에 이 metric을 적는 일. 그 파일을 만드는 PR이 머지된 뒤 후속으로 한 줄 더한다.
- 완료 행 정리(보존 정책).

## 관련 문서
없음

## 변경 경로
- src/main/kotlin/com/resilientpos/platform/EventPublicationMetrics.kt
- src/main/kotlin/com/resilientpos/platform/PlatformConfiguration.kt
- src/test/kotlin/com/resilientpos/platform/EventPublicationMetricsTest.kt
- docs/wiki/domain-model.md
- docs/spec/2026-09-25-failed-publication-metric.md

## 인수 조건
1. `event_publication`에 리스너 A의 `FAILED` 기록, 리스너 A의 6분 전 `PUBLISHED` 기록, 리스너 B의 6분 전 `PROCESSING` 기록, 리스너 A의 1분 전 `PUBLISHED` 기록, 리스너 A의 `COMPLETED` 기록이 있을 때 갱신하면 gauge가 `(failed, A)=1`, `(stale, A)=1`, `(stale, B)=1`이고 다른 조합은 없다.
2. `FAILED` 기록은 발행 시각과 무관하게 `failed`로 센다.
3. 기록이 완료되거나(`completion_date`가 채워지거나) 지워진 뒤 갱신하면 그 조합의 시계열이 없어진다.
4. `listener` 태그 값은 `listener_id`의 클래스 단순 이름이다(예: `...internal.application.OrderPaidListener.on(com.resilientpos.contract.OrderPaid)`는 `OrderPaidListener`, 중첩 클래스 `Outer$Inner`는 `Inner`).
5. `@EnableScheduling`을 켠 뒤에도 커밋 뒤 리스너는 `task-` 스레드에서 실행된다. 테스트 전용 `@ApplicationModuleListener`로 확인한다. 프로덕션 리스너와 같은 애너테이션이라 같은 실행기를 탄다.
6. 발행 뒤 30분, 마지막 재전송 뒤 1분인 `RESUBMITTED` 기록은 세지 않고, 재전송 뒤 6분인 기록은 `stale`로 센다.
7. 리스너가 실제로 예외를 던져 Modulith가 남긴 기록을 `failed`로 센다(SQL로 넣은 행이 아니라 실제 실패 행의 모양을 확인한다).
8. 기존 테스트가 모두 통과한다.

## 검증
- 인수 1~4: `./gradlew test --tests 'com.resilientpos.platform.EventPublicationMetricsTest'`. Docker가 필요하다. 행은 테스트가 SQL로 직접 넣고, 갱신은 스케줄을 기다리지 않고 메서드를 직접 부른다.
- 인수 5~7: 같은 명령. 테스트 전용 리스너 둘(스레드 이름을 남기는 것, 던지는 것)을 중첩 `@TestConfiguration`으로 둔다.
- 인수 8: `./gradlew test`.
- `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전 `--worktree` 없이 한 번 더.

## Wiki 영향
- [도메인 모델](../wiki/domain-model.md) §4.3에 한 줄: 실패·끊긴 발행 기록은 `event_publication_incomplete{status, listener}`로 센다, 여러 인스턴스는 `max`로 모은다, 리스너 이름을 바꾸면 알림 규칙도 바꾼다.
- 볼트의 현재 상태 문서(저장소 밖)에서 "미완료 기록을 세거나 알리는 장치도 아직 없다"를 고친다. 재전송이 없다는 빈틈은 그대로 둔다.
- 요구사항 변화: 없다. 시스템 설계 7절의 관측성 요구 중 실패 이벤트 감지의 첫 단계다.

## 분할 계획
main 코드 60줄 안팎, 테스트 100줄 안팎, 문서 10줄 안팎이다. 분할하지 않는다.

## TDD 검증 기록
업무 동작이 아니라 인프라 metric이다. 그래도 세는 규칙(인수 1~4)은 분기가 있어서 통합 테스트를 먼저 쓰고 RED를 관측한다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 1~4: `리스너별로 실패한 기록과 기준 시간이 지난 미완료 기록을 센다`, `기록이 완료되거나 사라지면 그 조합의 시계열도 없어진다`, `listener 태그는 listener_id의 클래스 단순 이름이다` | `./gradlew test --tests 'com.resilientpos.platform.EventPublicationMetricsTest'`. 빈 동작의 stub에 대해 3개 실패: 기대값 `{failed=1.0, stale=1.0}`인데 실제 값이 비어 있었고, 이름은 기대값 `OrderPaidListener`인데 빈 문자열이었다 | 같은 명령. 통과 | `./gradlew test`. 223개 중 실패 0 |
| 인수 5: `스케줄링을 켜도 커밋 뒤 리스너는 task executor에서 돈다` | RED 없음. `@EnableScheduling` 전 기준선으로 먼저 통과시킨 characterization이다 | `@EnableScheduling` 뒤 같은 명령. 통과(스레드 이름 `task-` 접두어) | `./gradlew test`. 223개 중 실패 0 |
| 인수 6: `재전송된 기록은 마지막 재전송 시각부터 잰다` | 리뷰 반영으로 조건을 바꾸기 전에 쓴 테스트가 아니어서 RED를 관측하지 않았다. 바꾸기 전 조건(`publication_date` 기준)이면 두 행이 모두 `stale`이라 기대값 1과 어긋난다 | 같은 명령. 통과 | `./gradlew test`. 223개 중 실패 0 |
| 인수 7: `리스너가 던지면 Modulith가 남긴 기록을 failed로 센다` | 같은 명령. 실패: 기대값 `{failed=1.0}`인데 비어 있었다. 실제 `listener_id`가 `...EventPublicationMetricsTest$FailingProbeListener.on(...)`이라 이름이 `EventPublicationMetricsTest$FailingProbeListener`로 뽑혔다. 중첩 클래스의 `$`를 처리하지 않던 결함이다 | `listenerName`이 `$` 뒤만 쓰게 고친 뒤 같은 명령. 통과. 이름 테스트에 `Outer$Inner` 경우를 더했다 | `./gradlew test`. 223개 중 실패 0 |

## 결과
- 인수 1~8을 충족했다. `./gradlew test`는 223개(기존 217, 신규 6) 중 실패 0이다.
- 구현: `platform.EventPublicationMetrics`(MultiGauge, 1분 `@Scheduled` 갱신, `@Synchronized`, 전용 `JdbcTemplate`의 쿼리 타임아웃 10초), `PlatformConfiguration`의 `@EnableScheduling`, 통합 테스트다. 새 의존성과 설정 파일 변경은 없다.
- 개발 중 확인한 것: 테스트가 가짜 이벤트 타입으로 넣은 미완료 행이 남으면 컨텍스트 종료 때 Modulith가 그 행을 복원하려다 `Unable to locate named class`로 경고를 낸다. 같은 DB를 쓰는 다른 컨텍스트에도 번지므로 테스트마다 이 클래스가 남긴 행(가짜 타입과 테스트 이벤트 타입)을 지운다. 정리 뒤 남은 행이 0인 것을 임시 확인 코드로 봤다(커밋하지 않았다).
- 병렬 브랜치 충돌: `git merge-tree --write-tree HEAD feature/prometheus-structured-logging`이 충돌 없이 트리를 만들었다. 겹치는 파일이 없다.
- `tdd-driver`에 위임하지 않고 주 에이전트가 직접 수행했다. 업무 동작이 아니라 `platform` 인프라다.

### multi-review (2026-09-25)
Claude 85점, Codex 68점(Codex CLI 직접 실행, read-only라 테스트는 돌리지 않았다), Gemini 미실행(CLI 없음). 첫 Codex 시도는 리뷰어 에이전트가 지원되지 않는 모델을 지정해 CLI가 실패했고, 그때 에이전트가 대신 쓴 리뷰는 Codex 결과로 치지 않았다. 반영한 것:
- 스케줄 갱신과 테스트 갱신의 경합(Claude): `refresh()`를 `@Synchronized`로 직렬화했다.
- 약속만 하고 빠진 merge-tree 결과(Claude): 위에 적었다.
- `failed` 알림이 꺼지지 않는 문제와 배포 전 기존 행(Claude): 범위의 복구 절에 조치 뒤 행 처리와 배포 전 조회를 적었다.
- 완료 경로 미검증(Claude): 인수 3 테스트가 완료와 삭제를 모두 본다.
- 인수 5 문구와 구현 불일치(Claude): 문구를 테스트 전용 리스너로 고쳤다.
- 쿼리 타임아웃 부재(Claude): 전용 `JdbcTemplate`에 10초를 걸었다.
- `RESUBMITTED`의 경과 기준(Claude): `coalesce(last_resubmission_date, publication_date)`로 바꾸고 인수 6을 더했다.
- 실제 실패 행 미검증(Claude): 인수 7을 더했고, 이 테스트가 중첩 클래스 이름 결함을 찾았다.
- 테스트 리스너 등록이 암묵적(Codex, Critical로 판정): 두 테스트가 통과하므로 등록은 되고 있었다. 읽는 사람이 같은 의문을 갖지 않게 `@Import`로 명시했다.
- 실제 이벤트 행 정리 누락(Codex): 정리 대상에 테스트 이벤트 타입을 더했다.
- wiki의 재전송 기준 누락(Codex): 도메인 모델 §4.3을 고쳤다.

반영하지 않은 것과 이유:
- overwrite가 매 주기 지웠다 다시 등록하는 틈(Claude 참고): 1분 주기의 `> 0` 알림에 영향이 없다. 범위의 갱신 절에 받아들인다고 적었다.
- 조회 실패가 계속될 때 이전 값이 남는 문제(Claude 참고): WARN 로그로 보인다. 마지막 성공 시각 gauge는 알림 규칙을 정하는 쪽이 필요하면 더한다.
- `@ApplicationModuleListener(id = ...)` 형식(Claude 참고): 쓰는 리스너가 없다. 범위에 한계로 적었다.
- 테스트가 컨텍스트를 하나 더 띄우는 비용(Claude 참고): 테스트 리스너가 필요해서 받아들인다.
