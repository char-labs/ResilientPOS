# PR spec: 실패하거나 끊긴 이벤트 발행 기록을 metric으로 센다

## 목적
커밋 뒤 리스너(`payment`의 `OrderPaymentRequestedListener`·`PaymentAttemptCreatedListener`, `order`의 `PaymentApprovedListener`, `fulfillment`의 `OrderPaidListener`)가 실패하면 `event_publication`에 `FAILED`로, 종료나 프로세스 사망으로 끊기면 `PUBLISHED`·`PROCESSING`으로 남는다. 그것을 세거나 알리는 장치가 없어서 사람이 DB를 직접 보기 전에는 드러나지 않는다. 특히 **결제는 승인됐는데 주문이 `CHECKOUT_REQUESTED`에 머무는** 경우가 이렇게 숨는다.

기대 결과: 리스너별로 실패한 기록과 끊긴 기록의 수가 Micrometer gauge로 나가서, 수집기가 붙으면 `> 0` 알림 하나로 잡힌다. 재전송은 하지 않는다.

## 범위
소유 모듈은 `platform`이다. 업무 모듈의 코드는 바꾸지 않는다.

**gauge `event.publication.incomplete`** (Prometheus 이름 `event_publication_incomplete`). 태그는 셋이다.
- `status`: `failed`는 리스너가 던져 `FAILED`인 기록이다. `status`가 비어 있는 미완료 행도 `failed`로 센다(지금 저장소에는 그런 행이 없다). Modulith의 실패 조회(`FailedEventPublications`)가 `status = FAILED or (status is null and completion_date is null)`로 찾기 때문이다(`JpaEventPublicationRepository`의 쿼리 문자열로 확인). `stale`은 `PUBLISHED`·`PROCESSING`·`RESUBMITTED`인 채 기준 시간이 지난 기록이다. 그 밖의 상태값은 세지 않는다. Modulith가 새 미완료 상태를 더하면 그 행은 조용히 빠지므로 Modulith 버전을 올리는 PR은 `EventPublication.Status`도 diff한다(도메인 모델 §4.3 절차). 경과 시간은 발행 시각부터, 재전송된 기록은 마지막 재전송 시각부터 잰다(`coalesce(last_resubmission_date, publication_date)`). 오래된 이벤트를 방금 다시 보냈다면 아직 처리 중일 수 있어서다. 막 발행돼 처리 중인 기록은 세지 않는다.
- `module`: `listener_id`의 `com.resilientpos.` 다음 세그먼트다(예: `fulfillment`). 메서드와 클래스를 뗀 패키지에서 찾으므로 이 접두어가 아니거나(저장소 밖 리스너, id를 직접 준 리스너) 기본 패키지 바로 아래 클래스면 `unknown`이다. 모듈마다 같은 이름의 리스너가 생길 수 있어서 둔다. `PaymentApproved`는 `order`와 앞으로의 `reconciliation`이 함께 받는다(도메인 모델 §2). 카디널리티는 모듈 수 이하다.
- `listener`: `listener_id`(`패키지.클래스.메서드(이벤트타입)`)에서 뽑은 클래스 단순 이름이다. 중첩 클래스는 `바깥$안`으로 저장되어 안쪽 이름만 쓴다. 같은 모듈에서 같은 단순 이름의 리스너가 둘이면 합산한다. `@ApplicationModuleListener(id = ...)`처럼 id를 직접 주는 리스너는 이 형식이 아니어서 이름이 어긋난다. 추상 기반 클래스에 선언한 메서드를 상속한 리스너는 선언한 클래스의 이름과 모듈로 찍힌다. 지금은 둘 다 없다. `orderId` 같은 식별자는 태그로 쓰지 않는다.

**세는 방법.** `event_publication`을 `completion_date is null` 조건으로 한 번 조회해 `(status, module, listener)`별로 센다. 미완료 행만 읽으므로 기존 `completion_date` 인덱스를 쓰고, 완료 행이 쌓여도 비용이 늘지 않는다. 비용은 미완료 행 수에 비례한다. PostgreSQL 18에 `V004`를 적용하고 완료 행 100만 개를 넣은 뒤 `EXPLAIN (ANALYZE, BUFFERS)`로 쟀다. 미완료 1천 개면 인덱스 스캔으로 0.27ms(버퍼 57), 10만 개면 22.7ms(버퍼 5400)였다. 5차에서 상태 조건을 좁힌 뒤 다시 쟀다. 같은 인덱스 스캔이고 스캔 노드 시간은 1천 개 0.2~0.3ms(버퍼 60), 10만 개 13~30ms(버퍼 약 5400, 3회)였다. 미완료 행은 조치되지 않은 실패라 10만 개면 알림이 이미 오래 켜져 있는 상태다. 그때도 1분에 한 번 많아야 30ms라 새 인덱스를 두지 않는다. 이 비용은 인스턴스마다 들어서 인스턴스가 N개면 분당 N번이다. 인스턴스 10개에 미완료 10만 개라도 분당 0.3초 남짓이다. Modulith의 `EventPublicationRepository.countByStatus`는 `events-core`가 runtime 범위라 main에서 컴파일되지 않고, 조건도 맞지 않아 쓰지 않는다. 조회만 하며 상태를 바꾸지 않는다.

**갱신.** 리스너 목록이 데이터에서 나오므로 `MultiGauge`의 row 목록을 1분마다 다시 등록한다. row는 값을 고정하지 않고 스냅샷 맵을 읽는 함수로 두고 overwrite를 끈다(`register(rows, false)`). 이미 있는 조합은 지웠다 다시 등록되지 않아 scrape 사이에 시계열이 빠지지 않고, 목록에 없는 조합만 지워진다. overwrite를 켜면 매 주기 지웠다 다시 등록하는 틈에 scrape가 들어와 시계열이 한 번 빠지고, `for:`를 건 알림은 그때 pending이 초기화된다. 갱신은 `@Synchronized`로 직렬화한다. 두 갱신(주기 갱신과 테스트의 직접 호출)이 겹치면 먼저 조회한 쪽이 나중에 등록해 새 값을 덮을 수 있어서다.

**스케줄링.** 갱신은 Spring의 공용 스케줄러(`@Scheduled`)가 아니라 이 컴포넌트가 가진 전용 스레드 하나(`ScheduledExecutorService`, 스레드 이름 `event-publication-metrics`)에서 1분 간격으로 돈다. 공용 스케줄러는 main에서 이미 켜져 있고(Modulith Moments의 자동 구성 `MomentsAutoConfiguration`이 `@EnableScheduling`을 단다) 스레드가 하나라, 거기 올리면 느린 조회가 매시간·자정의 `HourHasPassed`·`DayHasPassed` 발행까지 붙잡는다. 또 `spring.modulith.moments.enabled=false`로 Moments를 끄면 공용 스케줄러도 함께 꺼진다. 전용 스레드는 둘 다와 무관하다(인수 8·12). 그래서 이 PR은 `@EnableScheduling`을 더하지 않고 커밋 뒤 리스너의 실행기에도 영향을 주지 않는다. 스레드는 컨텍스트(인스턴스)마다 하나이고, 테스트에서는 캐시된 컨텍스트 수만큼 뜬다. `refresh()`는 `RuntimeException`을 삼키지만 `Error`가 새면 `ScheduledExecutorService`가 이후 실행을 멈추므로 주기 작업은 한 번 더 감싼다. 그래서 `Error`(`OutOfMemoryError` 포함)도 로그를 남기고 주기를 유지한다. 주기가 조용히 멈추는 쪽보다 낫다고 봤다.

**시작과 종료.** `SmartLifecycle`(기본 phase: 가장 늦게 시작하고 가장 먼저 멈춘다)이 맡는다. 모든 싱글턴(Flyway 마이그레이션 포함)이 준비된 뒤 첫 갱신이 돈다. 컨텍스트가 닫힐 때는 빈을 파괴하기 전에 멈춤 표시를 세우고 스레드를 끊은 뒤 3초까지 끝나기를 기다린다. 커넥션 대기는 인터럽트로 바로 끝나므로 기다리는 것은 쿼리 중일 때뿐이다. 3초를 넘기면 기다리지 않고 넘어가며, 그 옛 조회는 닫히는 풀에 닿을 수 있지만 멈춰 있는 동안 끝나면 결과와 오류는 버린다. 대기가 짧은 이유는 버리는 결과를 위해 종료를 늦출 까닭이 없어서다. `stop()`은 동기 호출이라 lifecycle processor가 끊지 않지만, 그래도 3초는 phase 종료 상한(프레임워크 기본 10초, Boot `spring.lifecycle.timeout-per-shutdown-phase` 기본 30초)보다 짧다. 이 멈춤은 가장 먼저 도는 phase라 웹 서버의 graceful shutdown보다 먼저 최대 3초를 쓴다. 배포의 종료 유예 시간(예: Kubernetes `terminationGracePeriodSeconds`)은 graceful shutdown 시간에 이 값을 더해 잡는다.

**동시성.** 락은 둘이다. `refresh()`의 모니터(`@Synchronized`)는 갱신을 한 줄로 세운다. 그래서 다시 시작한 뒤의 첫 갱신은 옛 갱신이 끝난 뒤에 적용되고, 옛 결과가 새 결과를 덮지 않는다. 다만 멈춘 뒤 다시 시작하기 전에 옛 갱신이 끝나지 못했다면, 다시 시작한 뒤 옛 결과(또는 옛 오류의 WARN과 개수 내리기)가 한 번 적용되고 모니터 뒤에서 기다리던 새 갱신이 곧 덮는다. 재시작은 테스트나 devtools에서만 일어나서 받아들인다. 짧은 상태 락은 `start()`의 생성·예약·대입, `stop()`의 상태 변경(`scheduler`, 멈춤 표시), 결과 적용(개수, 신선도, 실패 때 개수 내리기)을 묶는다. 그래서 멈춤 확인과 적용 사이에 `stop()`이 끼어들 수 없고, `start()`와 `stop()`이 겹쳐도 멈춘 executor에 예약하지 않는다. 락 안에서는 조회도 로그도 하지 않아 `stop()`이 오래 막히지 않는다. `stop()`은 `refresh()`의 모니터를 잡지 않는다. 잡으면 드라이버에 막힌 조회가 종료를 붙잡는다. 드라이버가 끝내 돌아오지 않으면 다시 시작한 뒤의 새 갱신은 모니터 뒤에서 기다리고 신선도가 멈춰 알림이 난다(재시작은 테스트나 devtools에서만 일어난다).

**신선도 gauge `event.publication.incomplete.refreshed`** (Prometheus 이름 `event_publication_incomplete_refreshed_seconds`). 마지막으로 갱신에 성공한 시각(epoch 초)이다. 초기값이 0이라 첫 조회부터 실패해도 드러난다. 조회가 실패하면 그 인스턴스는 개수 시계열을 모두 내리고 이 값을 멈춘다. 낡은 개수를 남기면 `max`에 섞여 이미 조치된 알림을 붙잡아 두기 때문이다. 그래서 개수는 모든 인스턴스가 같으니 `max`로 모으고, 신선도는 인스턴스별로(또는 `min`으로) 보고 이 값이 오래되면(예: 3분) 따로 울린다. `max`로 모으면 건강한 인스턴스가 조회가 막힌 인스턴스를 가린다. 모든 인스턴스의 조회가 막히면 개수가 사라져 `failed` 알림이 resolved로 바뀌고, 신선도 알림은 `for:`가 지난 뒤에야 울린다. 그 사이 몇 분은 "해결됨"만 보인다. 규칙을 정하는 쪽은 `keep_firing_for`로 신선도 알림이 울릴 때까지 `failed` 알림을 유지할 수 있다. 기동 직후 첫 갱신 전의 scrape는 0을 읽으므로 신선도 알림에는 `for:`(예: 3분)를 둔다.

**조회 상한.** 공유 `JdbcTemplate`을 건드리지 않고 이 조회 전용 `JdbcTemplate`에 쿼리 타임아웃 10초를 건다(JPA 쿼리 상한과 같은 값). 트랜잭션 밖이라 트랜잭션 상한이 걸리지 않고, 없으면 갱신 스레드가 소켓 상한 30초까지 묶인다. 커넥션 대기는 Hikari `connection-timeout`(3초)이 따로 막는다. 둘 다 전용 스레드만 묶고 다른 작업을 막지 않는다.

**기준 시간.** 5분이다. 리스너는 짧은 DB 쓰기이고 트랜잭션 상한이 30초, provider는 지연 없는 Fake다. 여기에 큐 대기의 여유를 둔다. `@Value`의 기본값으로 두고 `application.yaml`에는 적지 않는다. 실제 provider가 붙으면 환경 변수 `RESILIENTPOS_EVENT_PUBLICATION_STALE_AFTER`(ISO-8601 기간, 예: `PT10M`)로 바꾼다. 0이나 음수는 막 발행된 기록까지 모두 `stale`로 만들어 알림이 쏟아지므로 기동에서 거절한다. 설정 오류라 `DomainException`이 아니라 `require`로 막는다. 시각은 주입된 `Clock`으로 계산한다.

**병렬 브랜치.** 개발을 시작할 때 `feature/prometheus-structured-logging`이 아직 main 밖이어서 그 브랜치가 바꾸는 파일(`build.gradle.kts`, `application.yaml`, `docs/wiki/observability.md` 등)을 건드리지 않았다. 그 브랜치가 #56으로 머지된 뒤 rebase했고, 공통 태그 `application`이 이 gauge에도 붙어 `/actuator/prometheus`로 나간다. 그래서 `observability.md`에 이 metric을 적는 일을 이 PR로 당겼다. 지금도 `application.yaml`은 바꾸지 않는다.

계약: 새 metric 둘이 생긴다. HTTP API, 이벤트, 스키마는 바뀌지 않는다. `module`·`listener` 태그 값이 리스너의 패키지·클래스 이름이라 리스너를 옮기거나 이름을 바꾸면 알림 규칙도 함께 바꾼다(리스너 이름은 이미 저장 형식이다, 도메인 모델 §4.3).

복구: 해당 없음. 감지만 한다. 조회나 행 매핑이 실패하면(`RuntimeException` 전부) 그 주기의 갱신을 건너뛰고 WARN 로그를 남긴다. 그 인스턴스는 개수 시계열을 내리고 신선도 gauge를 멈춘다. 다음 조회가 성공하면 둘 다 돌아온다.

**`failed`와 끊긴 `stale` 알림은 스스로 꺼지지 않는다.** 재전송이 없고 재기동 때 재발행도 꺼져 있어서(`republish-outstanding-events-on-restart: false`) 그 행은 사람이 조치하기 전까지 남는다. 저절로 풀리는 것은 실행기 큐에 밀려 늦은 `stale`뿐이다. 조치(업무 상태를 바로잡거나 `FailedEventPublications.resubmit`)를 끝내면 그 행의 `completion_date`와 `status = 'COMPLETED'`를 함께 채우거나 행을 지워야 시계열이 사라진다. `completion_date`만 채우면 metric에서는 빠지지만 `status`로 찾는 경로가 그 행을 다시 집을 수 있다. 배포하면 이미 쌓여 있던 행 때문에 알림이 바로 켜질 수 있으니, 배포 전에 미완료 행을 metric과 같은 조건으로 `status`·`listener_id`별로 세어 둔다. `completion_date`가 비어 있고, `status`가 `FAILED`이거나 비어 있거나, `PUBLISHED`·`PROCESSING`·`RESUBMITTED`인 채 마지막 재전송 또는 발행이 5분보다 이전인 행이다. 조건 정본은 `EventPublicationMetrics.refresh()`의 SQL이다.

관측성: 이 PR의 산출물이 곧 관측성이다. 알림을 받은 뒤 어느 주문인지는 `serialized_event`에서 찾는다. 찾는 조건은 기존 spec들의 것(`listener_id like '%PaymentApprovedListener%' and status = 'FAILED'` 등)에 `status is null`을 더한다(`failed` 태그가 그 행도 세기 때문이다).

## 제외
- 재전송·재발행(`FailedEventPublications.resubmit`)과 그 운영 진입점. Kafka를 들이는 설계가 맡는다. 진입점을 열면 인증과 다중 인스턴스 중복 전송을 함께 정해야 한다.
- Modulith staleness 모니터(`spring.modulith.events.staleness.*`). 오래된 미완료를 `FAILED`로 바꾸는 장치라 감지가 아니라 상태 변경이다.
- 헬스 인디케이터. 업무 실패 한 건으로 인스턴스가 `DOWN`이 되면 안 된다.
- 알림 규칙, 대시보드, 수집기 설정. 수집기를 붙이는 쪽이 정한다.
- Moments를 끄는 일. 이 PR 전부터 켜져 있었고 받는 쪽이 없어 해가 없다. 끄려면 `application.yaml`을 바꾸는 별도 결정이다.
- 완료 행 정리(보존 정책).

## 관련 문서
없음. 이 PR이 따르는 장기 스펙 문서(DESIGN·concerns·flows)가 없다. 바꾼 wiki는 Wiki 영향 절에 적었다.

## 변경 경로
- src/main/kotlin/com/resilientpos/platform/EventPublicationMetrics.kt
- src/test/kotlin/com/resilientpos/platform/EventPublicationMetricsTest.kt
- src/test/kotlin/com/resilientpos/platform/EventPublicationMetricsSchedulingTest.kt
- src/test/kotlin/com/resilientpos/platform/ObservabilityConfigurationTest.kt
- docs/wiki/domain-model.md
- docs/wiki/observability.md
- docs/spec/2026-09-25-failed-publication-metric.md

## 인수 조건
1. `event_publication`에 리스너 A의 `FAILED` 기록, 리스너 A의 6분 전 `PUBLISHED` 기록, 리스너 B의 6분 전 `PROCESSING` 기록, 리스너 A의 1분 전 `PUBLISHED` 기록, 리스너 A의 `COMPLETED` 기록, 리스너 C의 `status`가 비어 있는 기록과 6분 전 모르는 상태값 기록이 있을 때 갱신하면 gauge가 `(failed, A)=1`, `(stale, A)=1`, `(stale, B)=1`, `(failed, C)=1`이고 이 리스너들의 다른 조합은 없다.
2. `FAILED` 기록은 발행 시각과 무관하게 `failed`로 센다.
3. 기록이 완료되거나(`completion_date`가 채워지거나) 지워진 뒤 갱신하면 그 조합의 시계열이 없어진다.
4. `module`은 `listener_id`의 `com.resilientpos.` 다음 세그먼트이고 `listener`는 클래스 단순 이름이다(예: `...fulfillment.internal.application.OrderPaidListener.on(...)`는 `fulfillment`와 `OrderPaidListener`, 중첩 클래스 `Outer$Inner`는 `Inner`). 접두어가 다르거나 기본 패키지 바로 아래 클래스면 `module`은 `unknown`이다.
5. 발행 뒤 30분, 마지막 재전송 뒤 1분인 `RESUBMITTED` 기록은 세지 않고, 재전송 뒤 6분인 기록은 `stale`로 센다.
6. 리스너가 실제로 예외를 던져 Modulith가 남긴 기록을 `failed`로 센다(SQL로 넣은 행이 아니라 실제 실패 행의 모양을 확인한다). 테스트 리스너가 `com.resilientpos.platform` 아래라 `module`은 `platform`이다.
7. 값만 바뀐 조합(1 → 2)은 다시 등록되지 않고 같은 gauge가 새 값을 읽는다.
8. 갱신은 Spring 공용 스케줄러에 올라가지 않는다. 전용 스레드가 스스로 도는지는 인수 12가 본다.
9. 조회가 실패하면 그 인스턴스의 개수 시계열이 없어지고 `event.publication.incomplete.refreshed`는 마지막 성공 시각을 그대로 낸다. 성공하면 그 시각이 주입된 `Clock`의 현재 초다. 실패 뒤 다음 조회가 성공하면 개수와 갱신 시각이 돌아온다.
10. 기준 시간이 0이나 음수면 인스턴스를 만들 수 없다.
11. `/actuator/prometheus`에 `event_publication_incomplete_refreshed_seconds`가 `application="ResilientPOS"` 태그와 함께 나온다.
12. `spring.modulith.moments.enabled=false`로 Moments를 꺼도(3차에 확인한 대로 공용 스케줄러도 함께 꺼진다), 아무도 `refresh()`를 부르지 않는 컨텍스트에서 첫 갱신이 스스로 돌아 신선도가 채워진다.
13. 멈춘(`stop()`) 뒤의 `refresh()`는 조회하지 않고, 다시 `start()`하면 다시 센다.
14. 드라이버가 인터럽트에 반응하지 않아 `stop()`이 기다리다 넘어간 옛 갱신이 멈춰 있는 동안 끝나면 결과(개수, 신선도)를 적용하지 않는다. 멈춤 확인과 적용은 `stop()`과 같은 상태 락 안이다.
15. 기존 테스트가 모두 통과한다.

## 검증
- 인수 1~10: `./gradlew test --tests 'com.resilientpos.platform.EventPublicationMetricsTest'`. Docker가 필요하다. 행은 테스트가 SQL로 직접 넣고, 갱신은 스케줄을 기다리지 않고 메서드를 직접 부른다. 인수 6은 던지는 테스트 전용 리스너를 중첩 `@TestConfiguration`으로 두고 `@Import`로 명시한다. 인수 9·10은 빈이 아닌 인스턴스를 직접 만든다.
- 인수 11: `./gradlew test --tests 'com.resilientpos.platform.ObservabilityConfigurationTest'`.
- 인수 12: `./gradlew test --tests 'com.resilientpos.platform.EventPublicationMetricsSchedulingTest'`. Moments를 끈 컨텍스트를 하나 더 띄운다. 1분 간격 자체는 상수(`INTERVAL`)라 따로 검증하지 않는다.
- 인수 13·14: 인수 1~10과 같은 명령. 빈이 아닌 인스턴스를 `start()`·`stop()`한다. 인수 14는 첫 커넥션 요청을 인터럽트에도 풀리지 않게 붙잡는 `DataSource`와 짧은 종료 대기로 옛 작업을 만든다. 다시 시작한 뒤 옛 작업이 끝나는 경우의 순서는 `refresh()`의 모니터가 정하므로 따로 만들지 않는다. 확인과 적용 사이에 `stop()`이 끼어드는 경우는 같은 락이라 생길 수 없어 테스트하지 않는다. 예외 처리의 멈춤 확인은 멈춘 뒤의 로그 소음만 막는 것이라 테스트하지 않는다. `start()`와 `stop()`의 병렬 호출은 테스트하지 않는다. 10차에서 50번 겹쳐 부르는 테스트를 써 봤지만 예약을 락 밖으로 되돌린 코드에서도 통과해 결함을 잡지 못했다. 원자성은 생성·예약·대입이 한 락 블록이라는 구조로 보장하고, Spring은 lifecycle 메서드를 한 스레드에서 부른다.
- 인수 15: `./gradlew test`.
- `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전 `--worktree` 없이 한 번 더.

## Wiki 영향
- [도메인 모델](../wiki/domain-model.md) §4.3에 한 줄: metric 이름과 세 태그, 기준 시간, `max`와 인스턴스별 신선도, 스스로 꺼지지 않는다는 것과 조치 방법.
- [관측성](../wiki/observability.md) "지금 있는 것"에 한 줄: Prometheus 이름, 기본 기준 시간과 설정 키, 모으는 방법, 자세한 규칙은 도메인 모델로 링크.
- 볼트의 현재 상태 문서(저장소 밖)에서 "미완료 기록을 세거나 알리는 장치도 아직 없다"를 고친다. 재전송이 없다는 빈틈은 그대로 둔다.
- 요구사항 변화: 없다. 시스템 설계 7절의 관측성 요구 중 실패 이벤트 감지의 첫 단계다.

## 분할 계획
`git diff --numstat origin/main` 기준 main 코드 약 200줄, 테스트 약 390줄, 문서 약 270줄(spec이 대부분), 7개 파일, 합계 약 860줄이다. 더 늘면 리뷰 이력 절을 줄이거나 테스트 장치를 나누는 쪽을 먼저 본다. [개발 규칙](../wiki/development-workflow.md)의 경고 한도(900줄) 안이라 나누지 않는다.

## TDD 검증 기록
업무 동작이 아니라 인프라 metric이다. 그래도 세는 규칙에 분기가 있어서 통합 테스트를 먼저 쓰고 RED를 관측한다. 리뷰 반영으로 뒤에 더한 테스트는 구현을 잠시 틀리게 바꿔 RED를 관측하고 되돌렸다(커밋하지 않았다).

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 1~4: `리스너별로 실패한 기록과 기준 시간이 지난 미완료 기록을 센다`, `기록이 완료되거나 사라지면 그 조합의 시계열도 없어진다`, `module과 listener 태그는 listener_id에서 뽑는다` | `./gradlew test --tests 'com.resilientpos.platform.EventPublicationMetricsTest'`. 빈 동작의 stub에 대해 3개 실패: 기대값 `{failed=1.0, stale=1.0}`인데 실제 값이 비어 있었고, 이름은 기대값 `OrderPaidListener`인데 빈 문자열이었다. 5차에서 더한 경우는 조건과 `moduleName`을 잠시 이전으로 되돌려 관측했다: status가 빈 행이 `{failed=1.0}` 대신 `{stale=1.0}`, 기본 패키지 바로 아래 클래스의 모듈이 `unknown` 대신 `Probe` | 같은 명령. 통과 | `./gradlew test`. 231개 중 실패 0 |
| 인수 5: `재전송된 기록은 마지막 재전송 시각부터 잰다` | 같은 명령. 조건을 잠시 이전(발행 시각만 비교)으로 되돌리면 실패: 기대값 `{stale=1.0}`인데 `{stale=2.0}` | 같은 명령. 통과 | `./gradlew test`. 231개 중 실패 0 |
| 인수 6: `리스너가 던지면 Modulith가 남긴 기록을 failed로 센다` | 같은 명령. 실패: 기대값 `{failed=1.0}`인데 비어 있었다. 실제 `listener_id`가 `...EventPublicationMetricsTest$FailingProbeListener.on(...)`이라 이름이 `EventPublicationMetricsTest$FailingProbeListener`로 뽑혔다. 중첩 클래스의 `$`를 처리하지 않던 결함이다 | `listenerName`이 `$` 뒤만 쓰게 고친 뒤 같은 명령. 통과 | `./gradlew test`. 231개 중 실패 0 |
| 인수 7: `값만 바뀐 조합은 같은 gauge가 새 값을 읽는다` | 같은 명령. 등록을 잠시 이전 방식(`overwrite=true`)으로 되돌리면 실패: `같은 조합은 다시 등록하지 않는다` | 스냅샷을 읽는 row와 `overwrite=false`로 같은 명령. 통과 | `./gradlew test`. 231개 중 실패 0 |
| 인수 8: `갱신은 공용 스케줄러에 올라가지 않는다` | 같은 명령. `refresh`에 `@Scheduled`를 다시 달면 실패: `공용 스케줄러에 올라가 있다: [...EventPublicationMetrics.refresh, ...Moments.everyHour, ...]`. (이전 판정의 이력: `ScheduledMethodRunnable`로 메서드를 꺼내던 첫 판정은 Spring이 runnable을 감싸 빈 목록으로 실패했다. 7차에서 JVM 전체를 보던 스레드 단언은 뺐다) | 같은 명령. 통과 | `./gradlew test`. 231개 중 실패 0 |
| 인수 9: `조회가 실패하면 개수를 내리고 마지막 갱신 시각을 멈췄다가 성공하면 되돌린다` | 같은 명령. 갱신 시각을 조회 전에 찍도록 잠시 바꾸면 실패: 실패 전 시각 대신 1분 뒤 시각이 나왔다. 4차에서 개수를 내리도록 바꾼 뒤, 내리는 두 줄을 잠시 지우면 실패: `개수가 남아 있다` | 같은 명령. 통과 | `./gradlew test`. 231개 중 실패 0 |
| 인수 10: `기준 시간이 0 이하면 기동을 막는다`(0과 음수) | 같은 명령. 검사를 잠시 주석 처리하면 실패: 예외가 나지 않았다 | 같은 명령. 통과 | `./gradlew test`. 231개 중 실패 0 |
| 인수 11: `prometheus 엔드포인트가 ...` 테스트에 검사 하나(4줄) | RED 없음. 기존 테스트에 노출 이름을 고정하는 검사를 더했다 | `./gradlew test --tests 'com.resilientpos.platform.ObservabilityConfigurationTest'`. 통과 | `./gradlew test`. 231개 중 실패 0 |
| 인수 12: `Moments를 꺼도 갱신은 스스로 돈다` | 같은 명령. 전용 스레드 시작을 잠시 막으면 실패: `10초 안에 첫 갱신이 돌지 않았다` | `./gradlew test --tests 'com.resilientpos.platform.EventPublicationMetricsSchedulingTest'`. 통과 | `./gradlew test`. 231개 중 실패 0 |
| 인수 13: `멈춘 뒤의 갱신은 조회하지 않고 다시 시작하면 센다` | 같은 명령. `refresh()`의 멈춤 확인을 잠시 지우면 실패: `멈춘 뒤에 조회했다`. `start()`에서 멈춤 표시를 되돌리는 줄을 잠시 지우면 실패: `10초 안에 충족되지 않았다: 다시 시작한 뒤 센다` | 같은 명령. 통과 | `./gradlew test`. 231개 중 실패 0 |
| 인수 14: `제때 멈추지 않은 옛 갱신의 결과는 버린다` | 같은 명령. 8차(세대 번호)에서는 조회 뒤의 세대 확인을, 9차(상태 락)에서는 적용 구간의 멈춤 확인을 잠시 지우면 실패: `옛 갱신의 결과가 적용됐다` | 같은 명령. 통과 | `./gradlew test`. 231개 중 실패 0 |
| 인수 15: 기존 테스트 | 해당 없음. 회귀 확인이다 | `./gradlew test`. 231개 중 실패 0 | 같은 명령. 같은 결과 |

## 결과
- 인수 1~15를 충족했다. `./gradlew test`는 231개(main 219, 신규 12) 중 실패 0이다. `ObservabilityConfigurationTest`에는 검사 하나(4줄)만 더해 테스트 수는 같다.
- 구현: `platform.EventPublicationMetrics`(스냅샷을 읽는 MultiGauge row와 `overwrite=false`, `module`·`listener` 태그, 신선도 gauge, `SmartLifecycle`로 시작·종료하고 상태 락으로 멈춘 뒤의 결과를 버리는 전용 스레드의 1분 갱신, `@Synchronized`, 전용 `JdbcTemplate`의 쿼리 타임아웃 10초, 기준 시간 검증), 통합 테스트 두 클래스다. `PlatformConfiguration`은 결국 바꾸지 않았다(6차). 새 의존성과 설정 파일 변경은 없다.
- 개발 중 확인한 것: 테스트가 가짜 이벤트 타입으로 넣은 미완료 행이 남으면 컨텍스트 종료 때 Modulith가 그 행을 복원하려다 `Unable to locate named class`로 경고를 낸다. 같은 DB를 쓰는 다른 컨텍스트에도 번지므로 테스트마다 이 클래스가 남긴 행(가짜 타입과 테스트 이벤트 타입)을 지운다. 정리 뒤 남은 행이 0인 것을 임시 확인 코드로 봤다(커밋하지 않았다).
- 병렬 브랜치: #56 머지 전에는 `git merge-tree`로 충돌이 없음을 확인했고, 머지 뒤에는 충돌 없이 rebase했다.
- `tdd-driver`에 위임하지 않고 주 에이전트가 직접 수행했다. 업무 동작이 아니라 `platform` 인프라다.

### multi-review 1차 (2026-09-25)
이력 절의 인수 번호는 그 차수 당시의 번호다. 지금 번호가 다르면 괄호로 적는다.

Claude 85점, Codex 68점(Codex CLI 직접 실행, read-only라 테스트는 돌리지 않았다), Gemini 미실행(CLI 없음). 첫 Codex 시도는 리뷰어 에이전트가 지원되지 않는 모델을 지정해 CLI가 실패했고, 그때 에이전트가 대신 쓴 리뷰는 Codex 결과로 치지 않았다. 반영한 것:
- 스케줄 갱신과 테스트 갱신의 경합(Claude): `refresh()`를 `@Synchronized`로 직렬화했다.
- 약속만 하고 빠진 merge-tree 결과(Claude): 위에 적었다.
- `failed` 알림이 꺼지지 않는 문제와 배포 전 기존 행(Claude): 범위의 복구 절에 조치 뒤 행 처리와 배포 전 조회를 적었다.
- 완료 경로 미검증(Claude): 인수 3 테스트가 완료와 삭제를 모두 본다.
- 인수 5 문구와 구현 불일치(Claude): 문구를 테스트 전용 리스너로 고쳤다. (3차에서 그 인수(리스너 실행기 스레드)를 지움)
- 쿼리 타임아웃 부재(Claude): 전용 `JdbcTemplate`에 10초를 걸었다.
- `RESUBMITTED`의 경과 기준(Claude): `coalesce(last_resubmission_date, publication_date)`로 바꾸고 인수 6(현재 5)을 더했다.
- 실제 실패 행 미검증(Claude): 인수 7(현재 6)을 더했고, 이 테스트가 중첩 클래스 이름 결함을 찾았다.
- 테스트 리스너 등록이 암묵적(Codex, Critical로 판정): 두 테스트가 통과하므로 등록은 되고 있었다. 읽는 사람이 같은 의문을 갖지 않게 `@Import`로 명시했다.
- 실제 이벤트 행 정리 누락(Codex): 정리 대상에 테스트 이벤트 타입을 더했다.
- wiki의 재전송 기준 누락(Codex): 도메인 모델 §4.3을 고쳤다.

반영하지 않은 것과 이유:
- overwrite가 매 주기 지웠다 다시 등록하는 틈(Claude 참고): 1분 주기의 `> 0` 알림에 영향이 없다. 범위의 갱신 절에 받아들인다고 적었다. (2차에서 뒤집음)
- 조회 실패가 계속될 때 이전 값이 남는 문제(Claude 참고): WARN 로그로 보인다. 마지막 성공 시각 gauge는 알림 규칙을 정하는 쪽이 필요하면 더한다. (2차에서 뒤집음)
- `@ApplicationModuleListener(id = ...)` 형식(Claude 참고): 쓰는 리스너가 없다. 범위에 한계로 적었다.
- 테스트가 컨텍스트를 하나 더 띄우는 비용(Claude 참고): 테스트 리스너가 필요해서 받아들인다.
- Codex 1차의 지적은 위 셋이 전부다. 반영하지 않은 Codex 지적은 없다.

### multi-review 2차 (2026-09-25, rebase 전 커밋 05f4f1e 기준)
Claude 89점, Codex 82점(CLI 직접 실행), Gemini 미실행. 둘 다 Critical은 없었다. 1차에서 받아들인 두 가지(overwrite의 틈, 조회 실패 중 남는 값)를 두 리뷰어가 모두 다시 짚어 받아들이지 않고 고쳤다. 반영한 것:
- overwrite 틈(Claude·Codex): "영향이 없다"는 근거가 틀렸다. `for:`를 건 알림은 시계열이 한 번 빠지면 pending이 초기화된다. 스냅샷을 읽는 row와 `overwrite=false`로 틈을 없앴고 인수 8(현재 7)로 고정했다.
- 조회 실패 중 남는 값(Codex): 신선도 gauge `event.publication.incomplete.refreshed`를 더했다.
- 미완료 행이 쌓일 때의 비용(Codex): 완료 100만 개 기준 미완료 1천 개 0.27ms, 10만 개 22.7ms를 실측해 범위에 적었다. 인덱스는 더하지 않는다.
- `stale`도 스스로 꺼지지 않는다(Claude): 복구 문단과 wiki를 넓혔다.
- 수동 조치에서 `status`도 바꾼다(Claude), 배포 전 조회를 metric과 같은 조건으로(Claude), 환경 변수 이름과 0·음수 주의(Claude): 복구 문단과 기준 시간 문단에 적었다.
- `status`가 비어 있는 행의 문구(Claude): 이 저장소는 처음부터 v2 스키마라 그런 행이 없다. 문장을 지웠다. (5차에서 Modulith 실패 조회와 맞추려 다시 셈)
- 인수 6(현재 5)의 RED 미관측(Claude): 조건을 잠시 되돌려 관측하고 표에 적었다.
- 스케줄 등록 미검증(Claude): 인수 9(현재 8)를 더했다. 이때 `@EnableScheduling`이 Modulith Moments도 켠다고 적었는데 틀렸다. 3차 절에서 바로잡는다.
- 반복 실행 때 `probeThread`가 남는 문제(Claude·Codex): 테스트 시작 때 비운다. (3차에서 그 테스트째 지움)

### multi-review 3차 (2026-09-25, rebase 전 커밋 6442983 기준)
Claude 88점, Codex 86점(CLI 직접 실행, Micrometer 소스로 `overwrite=false`와 함수형 row의 동작을 확인했다), Gemini 미실행. 둘 다 Critical은 없었다. 그 사이 병렬 브랜치가 #56으로 main에 머지돼 이 브랜치를 rebase했다.

**2차에 적은 사실 하나가 틀렸다.** "`@EnableScheduling`이 Modulith Moments도 켠다"고 적었지만, Moments의 자동 구성(`MomentsAutoConfiguration`) 자체가 `@EnableScheduling`을 달고 있다. main에서 이미 스케줄링이 켜져 있었고 `TaskScheduler`도 Moments도 있었다. 이 PR의 `@EnableScheduling`은 동작을 바꾸지 않는다. Moments는 `spring.modulith.moments.enabled=false`로 끌 수 있다(설정 메타데이터에는 없고 조건 애너테이션에만 있다). Codex의 Moments 지적에 답하려고 `@EnableScheduling`을 빼고 전용 스레드로 바꿨다가, Moments 테스트가 그래도 실패해 이 사실을 알았다. 전용 스레드는 근거가 사라져 되돌렸다. (6차에서 공용 스케줄러 점유를 근거로 다시 도입)

반영한 것:
- Moments 부작용(Codex): 위 사실로 해소했다. 범위의 스케줄링 문단을 고쳤다.
- 조회 실패 경로 미검증(Claude·Codex): 인수 9. 빈이 아닌 인스턴스를 전환할 수 있는 `DataSource`와 움직이는 `Clock`으로 만들어 실패 뒤 이전 값과 갱신 시각이 그대로인지 본다. (4차에서 개수는 내리도록 바꿈) 갱신 시각을 실패 전에 찍도록 잠시 바꾸면 실패한다.
- 결과 절 숫자와 분할 계획(Claude·Codex): 고쳤다.
- 인스턴스별 신선도(Claude): 범위와 wiki에 적었다.
- `listener` 태그 충돌(Claude): `module` 태그를 더했다.
- 신선도 gauge 단위(Claude): `baseUnit("seconds")`로 Prometheus 이름이 `event_publication_incomplete_refreshed_seconds`다. `/actuator/prometheus` 테스트로 고정했다.
- Moments 끄는 방법 미확인(Claude): 위에 적었다.
- 스케줄 판정이 `toString`에만 기댐(Claude): `FixedDelayTask`와 1분 간격도 함께 본다. (6차에서 지움. 공용 스케줄러를 쓰지 않게 됐다)
- 0·음수 기준 시간(Claude): 기동에서 거절한다(인수 10).
- 1차 목록의 뒤집힌 항목 표시(Claude): 표시했다.

반영하지 않은 것과 이유:
- 스냅샷을 먼저 바꾸고 row를 나중에 등록하는 사이의 scrape(Claude 참고): 사라질 조합이 한 번 0으로, 새 조합이 한 번 빠질 수 있다. `> 0` 알림에는 해가 없고 코드 주석에 적었다.

### multi-review 4차 (2026-09-25, 커밋 57da79f 기준)
Claude 89점, Codex 94점(CLI 직접 실행), Gemini 미실행. 둘 다 Critical은 없었다. 반영한 것:
- Moments를 끄면 갱신이 멈춘다고 읽히는 설명(Codex): 코드 동작은 반대(명시했으니 계속 돈다)였고 문장이 모호했다. `PlatformConfiguration` KDoc과 범위의 스케줄링 문단을 고치고, Moments를 끈 컨텍스트에서 갱신이 스케줄되는지 인수 12로 고정했다. (6차에서 전용 스레드로 바꿔 `@EnableScheduling`을 뺌)
- 신선도가 깨진 인스턴스의 낡은 개수가 `max` 알림을 붙잡는 문제(Claude): 조회가 실패하면 그 인스턴스는 개수 시계열을 내린다. 3차의 "이전 값을 그대로 낸다"를 뒤집었다. 모든 인스턴스가 막혀 개수가 사라지는 경우는 신선도 알림이 맡는다.
- 이력 절 인수 번호(Claude): 머리에 당시 번호라고 적고 지금 번호를 괄호로 달았다.
- 기동 직후 신선도 0(Claude): 신선도 알림에 `for:`를 둔다고 적었다.
- 음수 기준 시간 테스트(Claude·Codex): 인수 10에 더했다.
- 통합 경로의 `module` 값(Claude): 인수 6이 실제 행의 `module`이 `platform`인지 본다.
- `moduleName`이 접두어를 확인하지 않음(Claude): 괄호 앞만 보고 `com.resilientpos.`가 아니면 `unknown`이다.
- 문서의 "5분"이 기본값이라는 표시(Claude), "한 줄" 표현(Claude), 관련 문서 절(Codex): 고쳤다. 관련 문서는 템플릿대로 장기 스펙 문서의 자리라 "없음"을 두고 이유를 적었다.
- 테스트 인스턴스의 약한 참조(Claude 참고): 신선도 gauge에 `strongReference(true)`를 줬다. MultiGauge row는 이미 강한 참조다.

반영하지 않은 것과 이유:
- 인수 1의 "다른 조합은 없다"가 고유 리스너 범위에서만 확인되는 점(Claude 참고): DB를 나눠 쓰는 테스트의 의도된 절충이고 클래스 KDoc에 적혀 있다.

### multi-review 5차 (2026-09-25, 커밋 5d9630e 기준)
Claude 90점, Codex 91점(CLI 직접 실행), Gemini 미실행. 둘 다 Critical은 없었다. 반영한 것:
- `status`가 비어 있는 미완료 행(Codex): Modulith의 실패 조회가 이 행을 실패로 보는 것을 jar의 쿼리 문자열로 확인하고 `failed`로 센다. `stale`은 `PUBLISHED`·`PROCESSING`·`RESUBMITTED`로 좁혀 모르는 상태값은 세지 않는다. 인수 1에 두 경우를 더했다. 조건이 바뀌어 조회 비용을 다시 쟀다.
- 도메인 모델의 "기본 5분"과 `min` 표기(Claude): 4차에 고쳤다고 적었지만 도메인 모델에는 빠져 있었다. 고쳤다.
- 분할 계획 숫자와 파일 수(Claude·Codex): diff 기준으로 다시 적었다.
- 인수 13(현재 15)의 표 행(Codex): 더했다.
- 복구 경로 미검증과 쓰이지 않던 insert(Claude): 인수 9가 다음 조회 성공 뒤 두 행을 세고 갱신 시각이 돌아오는지 본다.
- `DataAccessException`이 아닌 예외에서 낡은 개수가 남는 문제(Claude): `RuntimeException`을 잡는다.
- 모든 인스턴스가 막힐 때 `failed` 알림이 resolved로 나가는 구간(Claude): 범위의 신선도 문단에 `keep_firing_for`와 함께 적었다.
- 테스트 이름(Claude): `module과 listener 태그는 listener_id에서 뽑는다`로 바꿨다.
- 기본 패키지 바로 아래 클래스의 `module`(Claude 참고): 패키지에서 찾도록 고쳐 `unknown`이다.
- Moments가 꺼졌다는 단언의 전제(Claude 참고): 기본 컨텍스트의 스케줄 테스트가 Moments가 스케줄된다는 것을 함께 본다. (6차에서 지움. 인수 12가 전용 스레드로 바뀌어 전제가 필요 없어졌다)

### multi-review 6차 (2026-09-25, 커밋 5301b46 기준)
Claude 91점, Codex 90점(CLI 직접 실행), Gemini 미실행. 둘 다 Critical은 없었다. 반영한 것:
- 공용 스케줄러 점유(Codex, Important): 공용 스케줄러는 스레드가 하나라 느린 조회가 Moments의 시간 이벤트 발행을 붙잡는다. Claude도 커넥션 대기까지 더하면 10초보다 길다고 짚었다. 갱신을 전용 스레드로 옮기고 `@EnableScheduling`을 뺐다. 3차에 같은 방식을 시도했다가 틀린 근거(Moments 부작용) 때문에 되돌렸는데, 이번 근거는 실제 점유다. 인수 8·12를 새 구조로 바꾸고 각각 RED를 관측했다.
- 배포 전 조회와 주문 찾기 조건이 5차 조건과 다름(Claude): 둘 다 고쳤다. 조건 정본은 코드의 SQL이라고 적었다.
- 모르는 상태값을 세지 않는 것의 조용한 누락(Claude): 세지 않는 쪽을 유지하고(5차 Codex의 요구), 도메인 모델 §4.3의 Modulith 버전업 절차에 `EventPublication.Status`도 diff한다는 단계를 더했다.
- 비용 문단의 "23ms"(Claude), 인스턴스 수에 따른 비용(Codex 참고): 재측정 범위와 인스턴스당 비용으로 고쳤다.
- 2차 이력의 뒤집힘 표시(Claude), 상속한 리스너 메서드의 한계(Claude 참고), 행마다 만드는 집합(Claude 참고): 고쳤다.
- 쿼리 상한이 커넥션 대기까지 묶는다는 과장(Claude): 커넥션 대기는 Hikari 3초가 막는다고 적었다. 전용 스레드라 둘 다 다른 작업을 막지 않는다.

반영하지 않은 것과 이유:
- 환경 변수 이름의 바인딩 테스트(Claude 참고): Boot의 `SystemEnvironmentPropertySource`가 점과 하이픈을 밑줄로 바꿔 찾는 문서화된 동작이라 테스트를 더하지 않는다.
- 1분 간격 자체의 테스트: 상수(`INTERVAL`)라 따로 검증하지 않는다.

### multi-review 7차 (2026-09-25, 커밋 452f910 기준)
Claude 91점, Codex 91점(CLI 직접 실행), Gemini 미실행. 둘 다 Critical은 없었다. 두 리뷰가 전용 스레드의 생명주기를 양쪽 끝에서 짚었다. 반영한 것:
- 시작 시점(Claude): `@PostConstruct`는 빈 초기화 도중이라 Flyway보다 먼저 쿼리가 나갈 수 있었다. `SmartLifecycle.start()`로 옮겨 모든 싱글턴이 준비된 뒤 시작한다.
- 종료 시점(Codex): `shutdownNow()`는 기다리지 않고, 모니터를 기다리던 갱신은 인터럽트로 깨지 않아 닫히는 `DataSource`에 닿을 수 있었다. `SmartLifecycle.stop()`이 멈춤 표시를 세우고 끊은 뒤 15초까지 기다리며(8차에서 11초로 줄임), 멈춘 뒤의 `refresh()`는 조회하지 않는다. 인수 13으로 고정하고 RED를 관측했다.
- JVM 전체를 보는 스레드 단언(Claude): 뺐다. 전용 스레드가 도는지는 인수 12가 자기 컨텍스트의 신선도로 본다. 스레드 수(컨텍스트마다 하나)를 범위에 적었다.
- 6차에서 무효가 된 이력 두 줄(Claude): 표시했다. Moments를 끈 테스트의 KDoc과 인수 12에는 3차에 확인한 사실이라는 출처를 달았다.
- §4.3 4단계의 조치(Claude 참고): 새 미완료 상태를 SQL 목록에 더한다고 적었다.
- 로그 문구(Claude 참고): "멈출 뻔했다"를 "실패했다"로 바꿨다.
- 볼트의 현재 상태 문서 테스트 수(Codex 참고): 230개로 고쳤다(#56의 관측성 테스트 2개도 빠져 있어 함께 더했다). 저장소 밖 파일이라 이 PR의 diff에는 없다.

반영하지 않은 것과 이유:
- `runCatching`이 `OutOfMemoryError`까지 삼키고 주기를 계속 도는 점(Claude 참고): 주기가 조용히 멈추는 쪽보다 낫다고 보고 그대로 둔다. Claude도 방어할 만하다고 봤다.

### multi-review 8차 (2026-09-25, 커밋 21085c2 기준)
Claude 92점, Codex 87점(CLI 직접 실행), Gemini 미실행. 둘 다 Critical은 없었다. 반영한 것:
- 제때 멈추지 않은 옛 갱신이 멈춘 뒤나 다시 시작한 뒤에 결과를 적용하는 문제(Codex, Important 두 건): (9차에서 세대 번호를 빼고 상태 락으로 바꿈) 세대 번호를 두어 조회 전과 적용 직전, 예외 처리에서 확인한다. 옛 결과와 오류를 버리고 개수·신선도를 건드리지 않는다. 인수 14로 고정하고 RED를 관측했다. 문서는 "닫히는 DataSource에 닿지 않는다"를 "닿을 수 있지만 결과와 오류는 버린다"로 낮췄다.
- 재시작 경로 회귀 테스트(Claude): 인수 13이 다시 시작하면 다시 세는지 본다. `start()`에서 멈춤 표시를 되돌리는 줄을 지우면 실패한다.
- 종료 중 인터럽트가 남기는 잘못된 WARN(Claude): 예외 처리에서도 세대를 먼저 확인해 멈춘 뒤의 오류는 로그도 개수 변경도 없이 버린다. 주석의 "커넥션 대기(3초) 안에 끝난다"를 "인터럽트로 바로 끝난다"로 고쳤다.
- 동기 `stop()`이 종료 시간을 쓰는 점(Claude): 대기를 11초로 줄이고 배포의 종료 유예 시간에 더하라고 적었다. (9차에서 3초로 줄임)
- 두 번째 `start()`의 executor 누수(Claude), `scheduler`의 가시성(Claude), phase 명시(Codex 참고): 막고, `@Volatile`을 달고, 기본 phase의 뜻을 적어 명시했다.
- 테스트의 `start()`를 try/finally로 감쌈(Claude), spec 끝 공백(Claude·Codex): 고쳤다.

반영하지 않은 것과 이유:
- 다시 시작한 뒤 옛 작업이 늦게 끝나는 경로의 별도 테스트(Codex): 멈춘 뒤 끝나는 경로(인수 14)와 같은 세대 확인이다(9차에서 세대 번호를 뺌. 지금은 모니터가 순서를 정한다). 모니터 순서를 만들기 위한 테스트 장치가 더 커져 받아들이지 않는다.
- 도메인 모델 §4.3에 기동·종료 순서 한 구(Claude 참고): 운영자 문서에는 "전용 스레드에서 1분마다"로 충분하고 순서는 spec에 있다.

### multi-review 9차 (2026-09-25, 커밋 f2e9ddd 기준)
Claude 93점, Codex 84점(CLI 직접 실행), Gemini 미실행. 둘 다 Critical은 없었다. 두 리뷰가 같은 곳을 반대 방향에서 짚었다. Claude는 세대 번호가 막는다던 "다시 시작한 뒤 옛 결과"를 실제로는 `refresh()`의 모니터가 막고 있어 세대 번호가 테스트로 구분되지 않는다고 했다. Codex는 `stop()`이 적용 구간과 같은 락을 잡지 않아 확인 직후에 멈춰도 결과가 적용된다고 했다. 반영한 것:
- 세대 번호를 빼고 짧은 상태 락 하나로 `start()`·`stop()`의 상태 변경과 결과 적용을 묶었다(Claude·Codex). 확인과 적용 사이에 멈춤이 끼어들 수 없고, 적용 구간에 I/O가 없어 `stop()`이 오래 막히지 않는다. `start()`의 확인과 대입도 같은 락이다. 재시작 뒤의 순서는 모니터가 정한다는 것을 범위의 동시성 문단에 적었다. 인수 14의 RED를 새 구조에서 다시 관측했다.
- `stop()`이 모니터를 잡지 않아야 한다는 불변식(Claude): 코드 주석과 범위에 적었다.
- 종료 대기(Codex·Claude): 멈춘 뒤의 결과와 오류는 버리므로 3초로 줄였다. Codex가 짚은 phase 종료 상한과의 충돌은 확인해 보니 기본 설정에서는 생기지 않는다(`stop()`이 동기라 끊기지 않고, Boot 기본 상한은 30초다). 그래도 짧게 해서 여유를 뒀다.
- 종료 유예 안내가 spec에만 있음(Claude): 관측성 문서에 적었다.
- 긴 스케줄링 문단(Claude): 스케줄링, 시작과 종료, 동시성 세 문단으로 나눴다.
- 테스트 대기의 상한(Claude): 첫 커넥션 요청을 10초까지만 기다린다.
- 분할 계획과 볼트 문서의 숫자(Claude): 다시 셌다.

반영하지 않은 것과 이유:
- 확인 직후 `stop()`을 끼워 넣는 결정적 테스트(Codex): 같은 락 안이라 그 순서가 생길 수 없다. 만들려면 코드에 테스트 전용 멈춤 지점을 넣어야 해서 받아들이지 않는다.
- 컨텍스트를 닫을 때 `DataSource`가 metric 스레드보다 늦게 닫히는지 보는 통합 테스트(Codex): phase 순서는 Spring의 계약이고, 순서가 어긋나도 멈춘 뒤의 결과와 오류는 버린다.
- 예외 처리의 멈춤 확인 테스트(Claude): 로그 소음만 막는 확인이라 테스트하지 않는다고 검증 절에 적었다.

### multi-review 10차 (2026-09-25, 커밋 8fb0594 기준)
Claude 94점, Codex 88점(CLI 직접 실행), Gemini 미실행. 둘 다 Critical은 없었고 락 순서에 교착이 없음을 확인했다. 반영한 것:
- `start()`의 예약이 상태 락 밖(Codex, Important. Claude도 짚음): 생성·예약·대입을 한 락 블록으로 옮겼다. 병렬 호출 테스트는 결함을 잡지 못해 넣지 않았다(검증 절).
- 재시작 경로에서 "버린다"는 과장(Claude, Important): 옛 결과가 한 번 적용되고 곧 덮이는 창을 KDoc 두 곳, 인수 14, 동시성 문단에 정확히 적고 받아들였다.
- 락 안의 로그(Claude): 로그를 락 밖으로 뺐다. 주석도 "조회도 로그도 하지 않는다"로 고쳤다.
- `Error` 처리의 뜻(Codex 참고): 로그를 남기고 주기를 유지한다고 적었다.
- phase 상한 주석(Claude 참고), 인수 14 테스트의 의도 주석(Claude 참고), 8차 이력 표시(Claude 참고), 분할 계획 문장 중복(Claude 참고): 고쳤다.
