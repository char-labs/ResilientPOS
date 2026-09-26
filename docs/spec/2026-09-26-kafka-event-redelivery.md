# PR spec: 커밋 뒤 이벤트를 Kafka로 보내고 실패한 소비를 재시도와 DLT로 다시 전달한다

## 목적
커밋 뒤 리스너 4개(`payment`의 `OrderPaymentRequestedListener`·`PaymentAttemptCreatedListener`, `order`의 `PaymentApprovedListener`, `fulfillment`의 `OrderPaidListener`)가 실패하면 `event_publication`에 `FAILED`로 남고 다시 보내는 절차가 없다. 재기동 시 재발행은 꺼져 있고(인스턴스가 여럿이면 중복 재발행된다) 재전송 주기도 없다. #50·#53·#55·#57이 "재전달은 Kafka를 들이는 별도 설계가 맡는다"며 미뤄 왔다. 이 PR이 그 설계의 첫 조각이다.

기대 결과: 네 이벤트가 Kafka 토픽으로 나가고, 네 리스너는 그 토픽의 consumer가 된다. 일시 장애로 실패한 소비는 같은 자리에서 몇 번 다시 시도되고, 끝내 실패하거나 다시 해도 같은 결과인 메시지는 DLT로 가서 metric과 로그로 드러난다. 사람이 DB를 보고 `resubmit`을 부르지 않아도 일시 장애는 스스로 복구된다.

## 범위

**Kafka를 지금 들이는 이유.** [시스템 설계 §4.2](../references/02_System_Design.md)는 "독립 배포 consumer가 실제로 생긴 event만 externalize한다"고 하고 [도메인 모델](../wiki/domain-model.md) §4.6도 "외부 consumer가 생기면 relay한다"고 조건을 걸었다. 지금 consumer는 모두 같은 앱 안이다. 재전달만이 목적이면 `FailedEventPublications.resubmit`을 주기적으로 부르는 것으로 Kafka 없이 풀린다. 그래도 사용자가 Kafka를 골랐다. 여러 pod(k8s·k3s)로 돌리는 것을 전제하고, 재전달·순서·DLT를 브로커의 표준 수단으로 맡기며, 앱 밖 consumer(KDS·영수증·CRM)가 붙을 때 같은 토픽을 그대로 쓰려는 것이다. 원문과 다르게 가는 결정이라 도메인 모델 §8에 적는다.

**relay만으로는 재전달이 되지 않는다.** `@Externalized`만 붙이고 리스너를 두면 Modulith는 "브로커로 보내기"를 리스너 하나로 추적할 뿐이고 내부 리스너의 `FAILED`는 그대로 남는다. 그래서 네 리스너를 모두 Kafka consumer로 바꾼다. 소비 실패는 Kafka의 재시도·DLT가 맡고 `event_publication`에는 브로커 전송 기록만 남는다.

**발행.** 네 이벤트 타입에 `@Externalized`를 단다. 소유 모듈이 접두어인 이벤트별 토픽 하나씩이다.

| 이벤트 | 토픽 | consumer(모듈) | consumer group |
|---|---|---|---|
| `contract.OrderPaymentRequested` | `order.payment-requested` | `OrderPaymentRequestedListener`(payment) | `payment.order-payment-requested` |
| `payment.PaymentEvent.AttemptCreated` | `payment.attempt-created` | `PaymentAttemptCreatedListener`(payment) | `payment.attempt-created` |
| `contract.PaymentApproved` | `payment.approved` | `PaymentApprovedListener`(order) | `order.payment-approved` |
| `contract.OrderPaid` | `order.paid` | `OrderPaidListener`(fulfillment) | `fulfillment.order-paid` |

- 키는 `orderId`다. 네 이벤트가 모두 든다. 흐름이 인과적이라(다음 이벤트는 앞 consumer의 커밋 뒤에 발행된다) 토픽 사이의 순서는 필요 없고, 한 토픽 안에서 같은 주문이 한 파티션에 차례로 서면 된다. 같은 checkout의 재요청·재전달이 한 consumer에서 순서대로 처리되므로 동시 배달은 리밸런스 순간으로 좁혀진다.
- 값은 지금 `serialized_event`와 같은 Jackson JSON이다. 도메인 모델 §4.3의 "하위 호환은 기본값이 있는 필드 추가뿐" 규칙이 Kafka 메시지에도 그대로 걸린다. consumer는 토픽마다 대상 타입을 고정하고 타입 헤더를 믿지 않는다. 헤더를 믿으면 이벤트 클래스를 옮기는 순간 이미 토픽에 있는 메시지를 읽지 못한다.
- `contract` 모듈이 `spring-modulith-events-api`의 애너테이션 하나를 의존하게 된다. 업무 모듈 타입이 아니라 방향 검사(`ModuleStructureTest`·`DependencyDirectionTest`)에 걸리지 않는다. 설정 빈 한 곳(`EventExternalizationConfiguration`)에 라우팅을 모으는 방법은 쓰지 않는다. 그 빈이 `platform`에 있으면 `platform → payment` 간선이 생겨 `payment → platform`과 순환한다.
- 토픽 이름·consumer group·키도 저장 형식이 된다. 바꾸면 이미 토픽에 있는 메시지나 커밋된 offset을 잃는다. 도메인 모델 §4.3에 적는다.

**토픽 생성.** 앱이 기동할 때 `NewTopic` 빈으로 토픽 4개와 DLT 4개(`원토픽.DLT`)를 만든다. compose 브로커는 자동 토픽 생성을 끈다. 이름이 틀리면 조용히 새 토픽이 생기지 않고 발행이 실패해 드러난다. 파티션 수는 `resilientpos.kafka.partitions`(기본 3)이고 compose·테스트는 1이다. 처리량 근거가 아직 없어 늘릴 수 있게만 둔다. 파티션을 늘리면 키의 배치가 바뀌어 그 순간 같은 주문의 순서가 깨질 수 있다. 늘리는 PR이 consumer를 비운 뒤 바꾼다. 복제 수는 `resilientpos.kafka.replicas`(기본 1)이고 운영 브로커가 생기는 배포 PR이 정한다.

**소비.** 네 리스너의 `@ApplicationModuleListener(propagation = NOT_SUPPORTED)`를 `@KafkaListener`로 바꾼다. 리스너가 트랜잭션이 아닌 것은 그대로다. 트랜잭션은 Facade가 나눠 연다(도메인 모델 §4.3). 리스너 본문(Facade 호출과 로그)은 바꾸지 않는다. offset은 리스너가 반환한 뒤 커밋한다. 커밋 전에 죽으면 같은 메시지가 다시 오고(at-least-once) consumer가 멱등이라 해가 없다.

멱등 근거는 기존 것을 그대로 쓴다.
- payment 시도 생성: 그 checkout의 시도를 결말과 무관하게 찾아 돌려주고, partial unique가 진행 중인 시도를 하나로 묶는다. 앞 시도가 거절된 직후의 동시 생성 창은 PR ①(`(orderId, checkoutNo)` `pg_advisory_xact_lock`)이 닫는다. 이 PR은 그 창을 키 직렬화로 리밸런스 순간까지 좁힌다.
- 승인 호출: `PaymentManagement.authorize`가 결말이 난 시도를 걸러 낸다.
- 주문 `PAID`: 같은 회차·금액의 승인이 다시 오면 아무것도 하지 않는다.
- fulfillment: 기존 것을 돌려주고 unique `(order_id)`가 막는다. 경합에서 진 쪽은 unique 위반으로 실패하지만 재시도가 기존 fulfillment를 읽고 끝난다. 그래서 `insert ... on conflict do nothing`으로 바꾸지 않고, `FulfillmentCommandFacade`의 `ponytail:` 표식을 "재시도가 해소한다"로 고친다.

**재시도와 DLT.** blocking 재시도다. `DefaultErrorHandler`가 실패한 메시지를 같은 자리에서 1초에서 시작해 2배씩 최대 5회(합계 약 31초) 다시 시도하고, 모두 실패하면 `DeadLetterPublishingRecoverer`가 `원토픽.DLT`의 같은 파티션으로 보내고 offset을 넘긴다. 그동안 같은 파티션의 뒤 메시지는 기다리므로 주문별 순서가 지켜진다. 합계 31초는 `max.poll.interval.ms` 기본값(5분)보다 짧다. 횟수와 시작 간격은 `resilientpos.kafka.retry.*`로 바꾼다. 실제 provider가 없어 이 숫자에 측정 근거는 없다.
- 다시 해도 같은 결과인 예외는 재시도하지 않고 바로 DLT로 보낸다. `DomainException`(예: `PAYMENT_APPROVAL_MISMATCH`)과 역직렬화 실패다. 역직렬화는 `ErrorHandlingDeserializer`로 감싸 consumer가 멈추지 않게 한다.
- 재시도 대상은 그 밖의 예외 전부다. DB 장애, 잠금 대기 초과(`PessimisticLockingFailureException`), unique 경합(`DataIntegrityViolationException`)이 여기 든다.
- DLT를 읽는 consumer와 재투입 운영 진입점은 두지 않는다. 원인을 고친 뒤 DLT 메시지를 원 토픽에 다시 넣는 절차(Kafka CLI)를 도메인 모델 §4.3에 적는다. consumer가 멱등이라 다시 넣어도 안전하다. 재투입할 때 key를 함께 옮겨야 파티션과 순서가 유지된다.
- `PAYMENT_APPROVAL_MISMATCH`는 지금까지 `FAILED` 발행 기록이 case를 대신했다. 이제 `payment.approved.DLT`의 메시지가 그 자리다. 찾는 조건이 SQL에서 DLT 조회로 바뀐다.

**발행 기록.** `event_publication`에는 이벤트마다 Modulith externalizer의 행 하나만 남는다. 브로커 전송이 실패하면(브로커 장애) 그 행이 `FAILED`로, 전송 도중 인스턴스가 내려가면 끊긴 채로 남는다. 이 행을 다시 보내는 주기 작업과 완료 행 정리는 PR ③이다. 그 전까지 브로커 장애로 남은 행은 지금처럼 사람이 `resubmit`한다. `republish-outstanding-events-on-restart`는 계속 끈다.

계약: HTTP API와 스키마는 바뀌지 않는다. 이벤트 타입과 필드도 그대로다. 새 저장 형식이 셋 생긴다. 토픽 이름, consumer group, 메시지 키(`orderId`)다. 앱이 Kafka 없이는 이벤트를 전달하지 못하므로 로컬 실행과 통합 테스트에 Kafka가 필요해진다. `#57` metric의 `listener`·`module` 태그 값이 네 리스너 이름에서 externalizer 하나로 바뀐다(실제 `listener_id`는 구현에서 확인해 적는다). 알림 규칙이 리스너 이름으로 걸려 있었다면 함께 바꾼다. 브로커 전송 실패는 이벤트와 무관하게 한꺼번에 나므로 한 series로 충분하고, 이벤트별 구분은 Kafka 쪽 metric이 맡는다.

복구:
- 소비 실패: 재시도가 일시 장애를 스스로 해소한다. DLT로 간 메시지는 원인을 고친 뒤 재투입한다.
- 종료 중: 커밋 뒤 리스너가 기본 task executor에서 버려지던 빈틈(종료 대기 0ms)이 consumer에서는 사라진다. offset을 커밋하지 않은 메시지는 다음 소유자가 다시 읽는다. externalizer는 여전히 그 executor에서 돌아 종료 중 전송이 끊기면 행이 남는다. PR ③이 다시 보낸다.
- 브로커 장애: 발행 기록이 `FAILED`로 남고 업무 트랜잭션은 영향이 없다. PR ③ 전까지 사람이 `resubmit`한다.
- 승인 뒤 저장 실패: provider가 승인했는데 결과를 저장하는 커밋이 실패하면 재시도가 `REQUESTED` 시도로 provider를 다시 부른다. 지금은 `FakePaymentGateway`라 해가 없다. **실제 provider를 붙이는 PR은 `paymentId`를 provider 멱등 키로 넘겨야 한다.** 알려진 빈틈으로 적는다.

**배포 조건.** 도메인 모델 §4.3대로 `listener_id`는 저장 형식이다. 리스너를 바꾸면 옛 `listener_id`로 남은 미완료 기록을 끝낼 주체가 없어진다. 배포 전에 다음이 0건이어야 한다. 남아 있으면 옛 버전으로 `resubmit`하거나 업무 상태를 바로잡은 뒤 `completion_date`와 `status = 'COMPLETED'`를 채운다. 배포된 환경은 아직 없어서 지금은 로컬 compose 볼륨만 해당한다.

```sql
select listener_id, status, count(*) from event_publication
where completion_date is null
  and listener_id similar to '%(OrderPaymentRequestedListener|PaymentAttemptCreatedListener|PaymentApprovedListener|OrderPaidListener)%'
group by listener_id, status;
```

관측성:
- counter `event.kafka.dead-letter{topic}`(Prometheus 이름 `event_kafka_dead_letter_total`). DLT로 보낸 메시지 수다. `topic`은 원 토픽이라 카디널리티는 토픽 수다.
- Boot가 바인딩하는 Kafka 클라이언트 metric(consumer lag, producer 오류)이 `/actuator/prometheus`로 나간다. 원문 7절의 consumer lag·DLQ count 요구가 여기서 채워진다.
- 재시도마다 WARN 한 줄(토픽·파티션·offset·key·시도 횟수·예외 이름), DLT로 보낼 때 ERROR 한 줄(같은 필드와 예외 메시지). key(`orderId`)는 로그 필드이고 metric 태그로 쓰지 않는다.

**인프라.** `spring-modulith-events-kafka`와 Boot의 Kafka starter를 더한다. compose에는 `apache/kafka` KRaft 단일 노드를 profile 없이 추가한다. 앱이 Kafka 없이 이벤트를 전달하지 못하므로 postgres와 같은 기본 서비스다. 테스트는 `testcontainers-kafka`의 컨테이너를 `PostgresIntegrationTest`처럼 JVM에 하나만 띄워 공유한다.

## 제외
- advisory lock으로 payment의 동시 생성 창을 닫는 일. PR ①이 main 위에서 따로 한다.
- 브로커 미전송 기록의 주기 resubmit(1분, advisory lock을 잡은 pod 하나, `FAILED`와 5분 지난 끊긴 기록, 최대 500건)과 완료 후 7일이 지난 행 정리. PR ③이 이 PR 머지 뒤 한다.
- non-blocking 재시도(`@RetryableTopic`). 아래 분할 계획의 후속 작업이다.
- DLT consumer, DLT 재투입 API. 재투입은 wiki 절차로 둔다. API를 열면 인증을 함께 정해야 한다.
- 옛 `listener_id` 기록을 옮기는 이관 코드. 배포 조건으로만 둔다.
- Kafka 트랜잭션(exactly-once). consumer가 DB에 쓰고 DB와 Kafka를 한 트랜잭션으로 묶을 수 없어 이득이 없다. 멱등 consumer로 대신한다.
- 스레드 풀·큐·거절 정책. 실제 provider가 붙는 PR이 정한다.
- Redis·Redisson. 정합성 수단을 Redis로 대신하지 않는다(AGENTS.md).

## 관련 문서
없음. 이 PR이 따르는 장기 스펙 문서(DESIGN·concerns·flows)가 없다. 바꾸는 wiki는 Wiki 영향 절에 적는다.

## 변경 경로
- build.gradle.kts
- src/main/resources/application.yaml
- docker/docker-compose.yaml
- src/main/kotlin/com/resilientpos/contract/**
- src/main/kotlin/com/resilientpos/payment/PaymentEvent.kt
- src/main/kotlin/com/resilientpos/payment/internal/application/**
- src/main/kotlin/com/resilientpos/order/internal/application/**
- src/main/kotlin/com/resilientpos/fulfillment/internal/application/**
- src/main/kotlin/com/resilientpos/platform/**
- src/test/kotlin/com/resilientpos/**
- src/test/resources/**
- docs/wiki/domain-model.md
- docs/wiki/observability.md
- docs/spec/2026-09-26-kafka-event-redelivery.md

## 인수 조건
1. 주문을 만들고 checkout을 요청하면 네 토픽을 거쳐 주문이 `PAID`가 되고 fulfillment가 하나 생긴다(테스트 픽스처의 끝자리 규칙으로 승인되는 금액).
2. 네 토픽의 메시지 key가 그 이벤트의 `orderId`다.
3. 기동하면 토픽 4개와 DLT 4개가 설정한 파티션 수로 생긴다.
4. `event_publication`에는 이벤트마다 externalizer의 행 하나만 남고, 전송이 끝나면 완료된다. 네 리스너 이름의 행은 생기지 않는다.
5. consumer가 일시 장애 예외를 던지다가 성공하면 메시지는 DLT로 가지 않고 업무 결과가 한 번만 반영된다.
6. consumer가 설정한 횟수만큼 모두 실패하면 메시지가 원 key와 함께 `원토픽.DLT`로 가고 `event.kafka.dead-letter{topic}`이 1 오르며, 같은 파티션의 다음 메시지가 처리된다.
7. `DomainException`을 던지면 재시도 없이 바로 DLT로 간다. 불일치 승인(`PAYMENT_APPROVAL_MISMATCH`)이 그 경우이고 주문은 `CHECKOUT_REQUESTED`에 남는다.
8. 역직렬화할 수 없는 값은 consumer를 멈추지 않고 바로 DLT로 간다.
9. 같은 이벤트를 같은 토픽에 두 번 넣어도 시도·`PAID` 전이·fulfillment는 하나다(네 consumer 각각).
10. consumer는 타입 헤더 없이 온 메시지도 토픽의 이벤트 타입으로 읽는다.
11. `/actuator/prometheus`에 Kafka consumer metric과 `event_kafka_dead_letter_total`이 `application="ResilientPOS"` 태그와 함께 나온다.
12. `docker compose -f docker/docker-compose.yaml --profile app up -d --build`로 띄운 앱에서 주문·checkout API를 부르면 주문이 `PAID`가 된다.
13. `ModuleStructureTest`·`DependencyDirectionTest`를 포함한 기존 테스트가 모두 통과한다.

## 검증
- 인수 1~11, 13: `./gradlew test`. Docker가 필요하다(PostgreSQL과 Kafka 컨테이너). 좁히려면 `./gradlew test --tests 'com.resilientpos.platform.*'`처럼 실행한다.
- 인수 5~8의 실패는 테스트 전용 설정으로 Facade나 리스너를 감싸 주입한다. 재시도 간격은 테스트에서 짧게 바꾼다.
- 인수 12: compose로 띄운 뒤 `curl`로 주문 생성·checkout을 부르고 조회해 `PAID`를 확인한다. 결과를 이 spec 결과 절에 적는다.
- `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전 `--worktree` 없이 한 번 더.

## Wiki 영향
- [도메인 모델](../wiki/domain-model.md) §4.3: 모듈 사이 전달이 Kafka라는 것, 토픽·key·consumer group 표, 토픽 이름·group·key가 저장 형식이라는 것, 재시도·DLT 규칙과 재투입 절차, `event_publication`에 externalizer 행만 남는다는 것, 리스너 이름으로 찾던 조건이 DLT 조회로 바뀐 것, 배포 조건 SQL. "외부를 부르는 리스너는 트랜잭션이 아니어야 한다" 문단은 `@KafkaListener`에 맞게 고친다.
- 도메인 모델 §4.6: "외부 consumer가 생기면 relay한다"를 "모듈 사이 후속 처리는 Kafka consumer다"로 고친다.
- 도메인 모델 §8: 원문 §4.2와 다르게 consumer가 생기기 전에 Kafka를 들인 결정과 이유.
- [관측성](../wiki/observability.md): "지금 있는 것"에 DLT counter와 Kafka 클라이언트 metric, 로컬에서 띄우기에 Kafka 서비스. "아직 없는 것"에서 옮길 것이 있으면 옮긴다.
- 볼트의 현재 상태 문서(저장소 밖): 재전달 빈틈을 "소비는 Kafka 재시도·DLT, 브로커 미전송은 PR ③"으로 고치고, 종료 중 리스너가 버려지던 빈틈을 externalizer로 좁히며, 승인 뒤 저장 실패의 provider 멱등 키 조건을 더한다.
- 요구사항 변화: 없다. 원문 4.2·7절이 요구하던 외부 전달 경계와 consumer lag·DLQ 관측을 앞당겨 채운다.

## 분할 계획
세 PR로 나눈다. 합치면 약 1,350~1,550줄로 실패 한도(1,400줄)에 걸리고 목표가 셋이다.
- PR ①(advisory lock): `(orderId, checkoutNo)` `pg_advisory_xact_lock`으로 payment의 동시 생성 창을 닫는다. Kafka와 무관하게 main 위에 선다. 새 worktree에서 이 PR과 나란히 진행한다. 약 150줄.
- PR ②(이 PR): 예상 main 코드 약 300줄, 테스트 약 450줄, 설정·compose 약 80줄, 문서 약 200줄, 합계 약 1,000줄이다. 경고 한도를 넘으면 문서 문장을 먼저 줄이고, 그래도 넘으면 인수 10(타입 헤더)과 11(metric 노출 확인)을 남기고 compose 검증(인수 12)을 PR ③으로 옮긴다.
- PR ③(주기 resubmit과 완료 행 정리): "미완료 기록은 브로커 전송뿐"이라는 전제가 이 PR 뒤에야 서므로 이 PR 머지 뒤 main에서 새 worktree로 시작한다. stacked PR이 아니다. 1분 주기, `pg_try_advisory_xact_lock(상수)`를 잡은 pod 하나, `FAILED`와 `stale-after`(5분)가 지난 끊긴 기록, 한 번에 최대 500건, 완료 후 7일(설정값)이 지난 행 삭제. 약 300줄.
- 후속 작업(non-blocking 재시도): 사용자는 여러 pod 환경에서 non-blocking 재시도를 기본으로 생각했고, 이번에는 blocking으로 시작하기로 했다. 판단 근거는 pod 수가 아니다. 파티션은 pod마다 나뉘어 배정되므로 blocking이 막는 범위는 실패한 메시지가 있는 파티션 하나다. DLT로 가기 전 그 파티션이 멈추는 시간(지금 약 31초)이 허용 범위를 넘는지, 또는 재시도 간격을 분 단위로 늘려야 하는 장애(provider 장애)가 생기는지가 기준이다. 그때 `@RetryableTopic`으로 바꾸고 같은 주문의 순서가 깨지는 것을 consumer가 견디는지 함께 본다.

## TDD 검증 기록
consumer 전환은 업무 흐름의 전달 경로를 바꾸므로 통합 테스트를 먼저 쓰고 RED를 관측한다. 리스너 본문과 Facade는 바뀌지 않는다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 1·2·4: checkout이 네 토픽을 거쳐 `PAID`와 fulfillment가 되고 key가 `orderId`다 | 미실행 | 미실행 | 미실행 |
| 인수 3: 토픽과 DLT가 설정한 파티션 수로 생긴다 | 미실행 | 미실행 | 미실행 |
| 인수 5: 일시 장애 뒤 성공하면 DLT로 가지 않는다 | 미실행 | 미실행 | 미실행 |
| 인수 6: 재시도를 모두 실패하면 DLT와 counter, 다음 메시지 처리 | 미실행 | 미실행 | 미실행 |
| 인수 7·8: `DomainException`과 역직렬화 실패는 바로 DLT | 미실행 | 미실행 | 미실행 |
| 인수 9: 같은 이벤트 두 번에도 결과는 하나 | 미실행 | 미실행 | 미실행 |
| 인수 10: 타입 헤더 없는 메시지를 읽는다 | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
