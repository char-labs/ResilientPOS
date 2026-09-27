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
- payment 시도 생성: 그 checkout의 시도를 결말과 무관하게 찾아 돌려주고, partial unique가 진행 중인 시도를 하나로 묶는다. 앞 시도가 거절된 직후의 동시 생성 창은 #61이 닫았다. 사실로 만든 첫 승인 시도를 `(order_id, checkout_no)`당 하나로 묶는 DB unique(`V008`)가 방어선이고, Redis 잠금은 늦은 요청이 실패하지 않고 앞 시도를 돌려받게 하는 보조다([payment-advisory-lock](2026-09-26-payment-advisory-lock.md)). 이 PR은 key 직렬화로 그 창을 리밸런스 순간까지 좁힌다.
- 승인 호출: `PaymentManagement.authorize`가 결말이 난 시도를 걸러 낸다.
- 주문 `PAID`: 같은 회차·금액의 승인이 다시 오면 아무것도 하지 않는다.
- fulfillment: 기존 것을 돌려주고 unique `(order_id)`가 막는다. 경합에서 진 쪽은 unique 위반으로 실패하지만 재시도가 기존 fulfillment를 읽고 끝난다. 그래서 `insert ... on conflict do nothing`으로 바꾸지 않고, `FulfillmentCommandFacade`의 `ponytail:` 표식을 "재시도가 해소한다"로 고친다.

**재시도와 DLT.** blocking 재시도다. `DefaultErrorHandler`가 실패한 메시지를 같은 자리에서 1초에서 시작해 2배씩 최대 5회(합계 약 31초) 다시 시도하고, 모두 실패하면 `DeadLetterPublishingRecoverer`가 `원토픽.DLT`로 보내고 offset을 넘긴다. 그동안 같은 파티션의 뒤 메시지는 기다린다. 합계 31초는 `max.poll.interval.ms` 기본값(5분)보다 짧다. 횟수와 시작 간격은 `resilientpos.kafka.retry.*`로 바꾼다. 실제 provider가 없어 이 숫자에 측정 근거는 없다.
- 다시 해도 같은 결과인 예외는 재시도하지 않고 바로 DLT로 보낸다. `DomainException`(예: `PAYMENT_APPROVAL_MISMATCH`)과 역직렬화·변환 실패다. 변환 실패는 Spring Kafka가 기본으로 재시도하지 않는 예외라 따로 등록하지 않는다(아래 범위 조정).
- 재시도 대상은 허용 목록이다(아래 범위 조정의 리뷰 반영). 잠금 대기 초과·쿼리 타임아웃(`TransientDataAccessException`)과 unique 경합(`DataIntegrityViolationException`)은 5회, DB에 닿지 못하는 실패(`CannotCreateTransactionException`·`DataAccessResourceFailureException`)는 횟수 없이 30초 간격까지 늘려 기다린다. 목록 밖은 바로 DLT다.
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
- counter `event.kafka.dead-letter{topic, reason}`(Prometheus 이름 `event_kafka_dead_letter_total`). DLT로 보낸 메시지 수다. `topic`은 원 토픽, `reason`은 `DomainException`의 코드(유한 집합)나 `error`다. 업무 불일치와 그 밖의 실패는 대응하는 사람이 다르다.
- Boot가 바인딩하는 Kafka 클라이언트 metric(consumer lag, producer 오류)이 `/actuator/prometheus`로 나간다. 원문 7절의 consumer lag·DLQ count 요구가 여기서 채워진다.
- 재시도마다 WARN 한 줄(토픽·파티션·offset·key·시도 횟수·예외 이름), DLT로 보낼 때 ERROR 한 줄(같은 필드와 예외 메시지). key(`orderId`)는 로그 필드이고 metric 태그로 쓰지 않는다.

**인프라.** `spring-modulith-events-kafka`와 Boot의 Kafka starter를 더한다. compose에는 `apache/kafka` KRaft 단일 노드를 profile 없이 추가한다. 앱이 Kafka 없이 이벤트를 전달하지 못하므로 postgres와 같은 기본 서비스다. 테스트는 `testcontainers-kafka`의 컨테이너를 `PostgresIntegrationTest`처럼 JVM에 하나만 띄워 공유한다.

### 범위 조정 (2026-09-26, 구현 중)
- **`ErrorHandlingDeserializer`를 두지 않는다.** Modulith Kafka 모듈이 값의 직렬화기를 `ByteArraySerializer`·`ByteArrayDeserializer`로 정하고 JSON 변환을 리스너 어댑터의 메시지 변환기(`ByteArrayJacksonJsonMessageConverter`)가 맡는다. 그래서 consumer가 받는 값은 늘 바이트이고 역직렬화 단계는 실패하지 않는다. 깨진 JSON은 변환 단계의 `ConversionException`이 되고, `DefaultErrorHandler`가 기본으로 재시도하지 않는 예외라 바로 DLT로 간다(인수 8). 변환기는 메서드 파라미터 타입으로 읽으므로 "타입 헤더를 믿지 않는다"도 설정 없이 성립한다(인수 10).
- **DLT counter를 기동할 때 토픽별 0으로 등록한다.** 한 번도 DLT로 간 적이 없으면 series가 없어 `increase()` 알림이 첫 증가를 놓친다.
- **로그의 예외 이름은 원인 예외다.** DLT로 보낼 때 받는 예외는 `ListenerExecutionFailedException`으로 감싸여 있어 그대로 찍으면 원인을 알 수 없었다(compose 확인 중 발견). 재시도 로그의 문구는 "소비 실패"다. 재시도하지 않는 예외도 한 번 찍히기 때문이다.
- **테스트 기반.** 통합 테스트가 Kafka 컨테이너를 PostgreSQL처럼 JVM에 하나 띄우고 자동 토픽 생성을 끈다. 캐시된 Spring 컨텍스트가 여럿 살아 있으면 같은 consumer group을 나눠 가져 다른 컨텍스트가 메시지를 처리하므로 `src/test/resources/spring.properties`로 컨텍스트 캐시를 하나로 제한한다. 재시도 간격은 테스트에서 20ms로 줄인다. 운영값이면 실패를 다루는 테스트가 약 31초 동안 파티션을 붙잡아 뒤 테스트가 시간을 넘긴다.
- **기존 테스트 일곱 개가 "처리가 끝났는가"를 옛 리스너의 발행 기록으로 판단하고 있었다.** consumer group의 커밋된 offset과 DLT로 바꿨다. 그중 어댑터가 던지는 경우는 처음에 재시도마다 provider를 다시 불러 호출이 6번이었다. 아래 리뷰 반영으로 재시도가 허용 목록이 되면서 호출 1번과 DLT를 기대값으로 고정했다.

### 범위 조정 (2026-09-26, 시니어 리뷰 반영)
리뷰 에이전트(결제·이벤트 인프라 관점)의 지적 12건을 spring-kafka 4.1.1 소스와 테스트 로그로 대조했다. 11번(프레임워크 로그 중복)은 실제 로그에 우리 로그만 있어 받지 않았다. 나머지는 사실로 확인했다(기본 DLT 이름 `-dlt`, 음수 파티션은 key로 분배, `KafkaAdmin`의 파티션 자동 증가, backoff가 consumer 스레드를 재움, `resetStateOnRecoveryFailure` 기본 true).
- **재시도는 허용 목록이다.** "모르는 예외는 재시도"면 버그나 어댑터의 계약 위반에도 provider를 다섯 번 더 부른다. 목록 밖은 바로 DLT로 간다.
- **DB에 닿지 못하는 실패는 횟수 없이 기다린다.** DB failover가 31초를 넘으면 그동안의 메시지가 차례로 DLT에 쌓여 사람이 되돌려야 했다. DB 없이는 어차피 아무것도 처리할 수 없으므로 30초 간격까지 늘려 기다린다. 간격 하나가 30초라 `max.poll.interval.ms`(5분)를 넘지 않는다.
- **consumer 스레드 수는 파티션 수와 같다**(`spring.kafka.listener.concurrency`). 재시도 대기가 스레드를 재우므로 스레드 하나가 파티션 셋을 맡으면 한 주문의 실패가 셋을 모두 멈춘다. 스레드는 리스너 넷 × 3 = 12개로 Hikari 풀(10)보다 많지만 동시에 커넥션을 쥐는 것은 처리 중인 스레드뿐이고, 요청 스레드가 이미 풀보다 많아 새 제약은 아니다. 대기 초과는 `CannotCreateTransactionException`이라 기다림 경로로 간다.
- **파티션 수를 환경 변수로 열지 않는다.** 값이 커지면 `KafkaAdmin`이 기동하면서 파티션을 늘려, rolling 배포 중에 순서가 깨질 수 있다. compose의 파티션 1 설정도 뺐다. 브로커 하나에 파티션 셋은 문제없다. 같은 이유로 relaxed binding이 대신하는 `RESILIENTPOS_KAFKA_*` placeholder를 모두 지웠다.
- **DLT는 보존 기간이 없다**(`retention.ms=-1`). 불일치 승인의 case 자리라 브로커 기본값 7일에 지워지면 안 된다. 원 토픽은 브로커 기본값을 따르고, 배포하는 쪽이 consumer 정지 허용 시간보다 길게 잡는다.
- **DLT로 보낼 때 파티션을 정하지 않는다**(`-1`, key로 분배). 원 토픽 파티션만 늘려도 DLT 전송이 막히지 않는다. 같은 주문은 key가 같아 DLT에서도 한 파티션에 모인다.
- **DLT counter에 `reason` 태그를 더했다.** 로그와 counter는 DLT 전송이 성공한 뒤에 남긴다. 전송이 실패하면 재시도가 처음부터 다시 돌고, 그동안 "DLT로 보냄"이 거짓으로 찍히지 않는다.
- **consumer가 듣는 토픽이 설정 목록에 있는지 테스트로 대조한다.** 새 이벤트를 더할 때 목록을 빠뜨리면 토픽이 생기지 않는데, 그 누락을 잡는 장치가 없었다.
- **문서의 순서 표현을 고쳤다.** 파티션 안의 순서는 전송 순서다. externalizer가 비동기라 같은 주문의 두 이벤트가 커밋 순서와 다르게 전송될 수 있고, 후속 PR의 `resubmit`은 오래된 이벤트를 나중에 보낸다. consumer는 순서에 기대지 않고 멱등으로 견딘다.
- **테스트의 `awaitUntil` 복사본을 `PostgresIntegrationTest` 하나로 모았다.** 이 PR이 고친 테스트 셋의 복사본도 지웠다. checkout 도우미의 복사본과 겹치는 시나리오는 그 테스트가 원래 있던 것이라 두었다.

### 범위 조정 (2026-09-27, 공식 문서 점검)
[Spring Modulith 이벤트 문서](https://docs.spring.io/spring-modulith/reference/events.html), [Spring Kafka 오류 처리 문서](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html)와 이 설정을 대조했다. Apache Kafka 설정 문서 페이지는 가져오지 못해서, kafka-clients 4.2.1이 테스트 기동 때 로그로 찍은 실제 값을 보았다.
- **맞게 쓰고 있는 것.** `@Externalized("토픽::#{#this.orderId}")`는 문서의 `$target::$key` 형식 그대로이고, Kafka에서는 target이 토픽, key가 메시지 key가 된다. DLT 목적지를 명시하는 것, 파티션을 음수로 두면 Kafka가 key로 고르는 것, 목록 밖 예외를 바로 recoverer로 보내는 것, 대기 한 번(최대 30초)이 `max.poll.interval.ms`(300초)보다 짧아 기본 `BackOffHandler`(스레드 대기)로 충분한 것 모두 문서와 같다. `DeadLetterPublishingRecoverer`는 원래 key를 유지하고 이전 예외 헤더를 지운다(`stripPreviousExceptionHeaders` 기본 true). producer는 `acks=-1`(all), `enable.idempotence=true`, `retries` 최대, `delivery.timeout.ms=120000`이고 consumer는 `enable.auto.commit=false`, `auto.offset.reset=earliest`, `max.poll.interval.ms=300000`이다. 설정하지 않아도 내구성 권장값이다.
- **전송 순서를 직렬화하지 않는다(`spring.modulith.events.externalization.serialize-externalization: false`, 기본값).** 문서가 "externalizer가 여러 스레드에서 돌아 특히 resubmit 때 순서가 뒤집힐 수 있고, 켜면 한 번에 하나씩 보낸다"고 적는다. consumer가 순서에 기대지 않고 멱등이므로 처리량을 내주지 않는다. 결정을 `application.yaml`에 적어 둔다.
- **PR ③은 Modulith의 공식 도구로 짠다.** 끊긴 기록은 직접 조회하지 않고 staleness monitor(`spring.modulith.events.staleness.published`·`processing`·`resubmitted`, 기본 0 = 꺼짐)가 `FAILED`로 바꾸게 한다. 재전송은 `FailedEventPublications.resubmit(ResubmissionOptions)`(`withBatchSize`·`withMaxInFlight`·`withMinAge`·`withFilter`)로, 정리는 `CompletedEventPublications.purge(Duration)`로 한다. 문서가 여러 인스턴스의 동시 resubmit은 외부 조정이 필요하다고 적으므로 advisory lock 결정은 그대로다. 대안인 outbox 모드(`spring.modulith.events.externalization.mode: outbox`, Namastack·JobRunr)는 의존성이 늘어 PR ③에서 비교만 한다.
- **`Error`는 오류 처리기를 거치지 않는다.** 문서가 `DefaultErrorHandler`는 `RuntimeException`만 다루고 `Error`는 consumer를 끝내 버린다고 경고한다. 리스너 본문이 `Error`를 직접 던지지 않고 Kotlin의 `check`·`require`·`error`는 `RuntimeException`이라 해당하지 않는다. `OutOfMemoryError` 같은 JVM 오류로 consumer가 멈추면 lag metric(`kafka_consumer_fetch_manager_records_lag_max`)이 오르므로 그것으로 알린다.
- **운영 브로커의 내구성 설정은 배포 PR이 정한다.** 지금 복제 수는 1이다. 운영은 복제 3과 `min.insync.replicas=2`를 `acks=all`과 함께 쓰는 것이 일반적인 권장이고, 그 PR이 `replicas`와 토픽 config를 함께 정한다.
- **consumer 리밸런스 프로토콜은 기본 `classic`에 둔다.** Kafka 4의 새 프로토콜(`group.protocol=consumer`, KIP-848)은 리밸런스 중 멈춤을 줄이지만 pod가 여럿이 되기 전에는 이득을 잴 수 없다. 여러 pod로 배포하는 PR이 본다.

### 범위 조정 (2026-09-27, #61 머지 뒤 rebase)
#61이 먼저 머지되어 이 브랜치를 origin/main `8c0b56d` 위로 rebase했다. spec 첫 커밋은 첫 자리를 지켰다.
- **충돌**: `docker/docker-compose.yaml`·`PostgresIntegrationTest.kt`는 양쪽이 서로 다른 인프라(Redis와 Kafka)를 더한 것이라 둘 다 살렸다. `docs/wiki/domain-model.md` §4.3 첫 줄은 #61이 결제 멱등 키 설명을 고친 것이고 이 PR은 그 아래에 문단을 더했을 뿐이라, 첫 줄은 #61의 것을 쓰고 Kafka 문단을 이어 붙였다. `application.yaml`·`build.gradle.kts`는 자동으로 합쳐졌다(`spring.data.redis`와 `spring.kafka`, Redis starter와 Kafka 의존성이 나란히 있다).
- **PR ①의 결과가 계획과 달랐다.** advisory lock 대신 DB unique와 Redis 보조 잠금으로 머지됐다. 이 spec에서 PR ①을 advisory lock으로 적은 곳을 고쳤다. Kafka consumer 쪽의 결론은 같다. 동시 배달이 와도 두 번째 첫 승인 시도는 DB unique가 막고, 막힌 쪽은 unique 위반(`DataIntegrityViolationException`)이라 재시도 허용 목록에 들어 다시 읽고 기존 시도를 돌려받는다.
- **검증**: rebase 뒤 `./gradlew test` 258개 중 실패 0(#61의 테스트 포함).

### 범위 조정 (2026-09-27, multi-review 반영)
PR 리뷰 포인트의 🔴 항목 때문에 multi-review를 돌렸다(Claude·Codex 실행, Gemini는 CLI가 없어 미실행). GitHub에는 리뷰가 없었다.
- **받았다: DB 연결이 끊긴 경우의 예외가 재시도 목록 밖이었다**(Claude 1, P1). `pg_terminate_backend`로 트랜잭션 도중 연결을 끊어 실측했다. 커밋 시점이면 `DataAccessResourceFailureException`(SQLState 57P01)이라 이미 기다림 경로였다. 하지만 쿼리 도중이면 원래 예외(57P01)가 롤백 실패에 덮여 `JpaSystemException` ← `org.hibernate.TransactionException` ← `SQLException("Connection is closed")`(SQLState 없음)으로 왔고 곧장 DLT로 갔다. failover 한 번에 처리 중이던 메시지가 DLT에 쌓이는 것이다. SQLState를 해석하지 않고 `JpaSystemException`·`TransactionSystemException`·`TransactionTimedOutException`을 경합 재시도에 넣었다. 다음 시도에서 DB가 아직 없으면 `CannotCreateTransactionException`이 나고 예외가 바뀌어 재시도 상태가 새로 시작되므로 기다림 경로로 넘어간다. 대가로 JPA 계열 버그성 예외도 5회 재시도된다.
- **받았다: 복제 수 기본값 1**(Claude 3). 설정이 없으면 복제 수를 지정하지 않아 브로커의 `default.replication.factor`를 따른다. `KafkaAdmin`은 복제 수를 바꾸지 않아 한 번 잘못 만들면 되돌리기 어렵다.
- **받았다: DLT 전송 실패가 조용하다**(Claude 2). 재시도 리스너의 `recoveryFailed`에서 ERROR 로그와 counter `event.kafka.dead-letter.failed{topic}`를 남긴다. 전송 실패 루프에서 목록 밖 예외라도 리스너가 다시 불리는 동작 자체는 그대로다(메시지를 버리지 않기 위해서다). 실제 provider를 붙이는 PR은 이 counter에 알림을 건다.
- **받았다: `max.poll.records` 기본값 500**(Claude 4). 50으로 줄였다. 레코드 처리가 느려지면 한 poll의 처리가 `max.poll.interval.ms`(300초)를 넘어 group에서 쫓겨난다.
- **받았다: 기동 때 브로커가 없으면 토픽 없이 뜬다**(Claude 9). `spring.kafka.admin.fail-fast: true`. compose에서 브로커를 내리고 앱만 다시 띄우면 `Application run failed`로 끝나는 것을 확인했다. 브로커 장애 때 전송 실패가 드러나는 시간은 PR ③(미전송 기록 회수)과 함께 본다. 실측으로는 `max.block.ms`(60초)가 아니라 `delivery.timeout.ms`(120초)가 정한다(결과 절).
- **남긴다**: `@Externalized` 발행 토픽과 설정 목록의 대조(Claude 8), 재시도 counter(Claude 11), Redis 잠금의 `InterruptedException` 처리(Claude 10, #61 범위), 번역 경로를 더 넓게 보는 분류 단위 테스트(Claude 5의 일부). 커밋 시점 unique 위반이 `DataIntegrityViolationException`으로 오는 것은 #61의 `PaymentAttemptCreationConcurrencyIntegrationTest`가 확인한다.
- **받지 않았다(Codex)**: 사실과 달랐다.
  - "import가 없어 컴파일되지 않는다": `FulfillmentCommand`는 같은 패키지다.
  - "3초·30초 대기가 session timeout(10초)을 넘겨 리밸런스된다": heartbeat는 별도 스레드가 보내고 session timeout은 45초(기동 로그)다. 처리 스레드의 대기가 걸리는 상한은 `max.poll.interval.ms`(300초)다.
  - "unique 위반은 재시도해도 성공하지 않는다": PostgreSQL에서 진 쪽의 INSERT는 이긴 쪽의 커밋을 기다렸다 실패하므로, 재시도하면 이미 커밋된 행을 읽고 `created=false`로 끝난다.
  - "`setBackOffFunction`의 null 의미가 불명확하다": 공식 문서가 null이면 기본 `BackOff`를 쓴다고 적는다.

## 제외
- payment의 동시 생성 창을 닫는 일. PR ①(#61, DB unique와 Redis 보조 잠금)이 main 위에서 따로 했다.
- 브로커 미전송 기록의 주기 resubmit(1분, advisory lock을 잡은 pod 하나, `FAILED`와 5분 지난 끊긴 기록, 최대 500건)과 완료 후 7일이 지난 행 정리. PR ③이 이 PR 머지 뒤 한다.
- non-blocking 재시도(`@RetryableTopic`). 아래 분할 계획의 후속 작업이다.
- DLT consumer, DLT 재투입 API. 재투입은 wiki 절차로 둔다. API를 열면 인증을 함께 정해야 한다.
- 옛 `listener_id` 기록을 옮기는 이관 코드. 배포 조건으로만 둔다.
- Kafka 트랜잭션(exactly-once). consumer가 DB에 쓰고 DB와 Kafka를 한 트랜잭션으로 묶을 수 없어 이득이 없다. 멱등 consumer로 대신한다.
- 스레드 풀·큐·거절 정책. 실제 provider가 붙는 PR이 정한다.
- Redis를 Kafka 경로의 정합성 수단으로 쓰는 일. 정합성은 DB unique가 맡는다(AGENTS.md). #61이 들인 Redis는 결제 시도 생성의 보조 잠금에만 쓰인다.

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
6. consumer가 설정한 횟수만큼 모두 실패하면 메시지가 원 key와 함께 `원토픽.DLT`로 가고 `event.kafka.dead-letter{topic, reason=error}`가 1 오르며, 같은 파티션의 다음 메시지가 처리된다. DB에 닿지 못하는 실패는 횟수를 넘겨도 DLT로 가지 않고 기다렸다가 처리된다. 허용 목록 밖의 예외는 한 번에 DLT로 간다.
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
- PR ①(#61, 머지됨): payment의 동시 생성 창을 닫는다. 계획은 `pg_advisory_xact_lock`이었지만 그 PR에서 DB unique(첫 승인 시도)와 Redis 보조 잠금으로 바뀌어 머지됐다. 이 PR은 #61 머지 뒤 origin/main 위로 rebase했다.
- PR ②(이 PR): 예상 main 코드 약 300줄, 테스트 약 450줄, 설정·compose 약 80줄, 문서 약 200줄, 합계 약 1,000줄이다. 경고 한도를 넘으면 문서 문장을 먼저 줄이고, 그래도 넘으면 인수 10(타입 헤더)과 11(metric 노출 확인)을 남기고 compose 검증(인수 12)을 PR ③으로 옮긴다. 실제는 `git diff --numstat origin/main` 기준 main 코드 약 185줄, 테스트 약 460줄, 설정·compose 약 65줄, 문서(spec·wiki) 약 250줄로 경고 한도(900줄)를 조금 넘는다. 발행 전환·consumer 전환·재시도가 서로 없으면 동작하지 않는 한 목표라 나누지 않았다. 실패 한도(1,400줄) 안이다.
- PR ③(주기 resubmit과 완료 행 정리): "미완료 기록은 브로커 전송뿐"이라는 전제가 이 PR 뒤에야 서므로 이 PR 머지 뒤 main에서 새 worktree로 시작한다. stacked PR이 아니다. 1분 주기, `pg_try_advisory_xact_lock(상수)`를 잡은 pod 하나, staleness monitor(5분)가 `FAILED`로 바꾼 끊긴 기록과 원래 `FAILED`를 `FailedEventPublications.resubmit(ResubmissionOptions)`로 한 번에 최대 500건, `CompletedEventPublications.purge`로 완료 후 7일(설정값)이 지난 행 삭제(위 공식 문서 점검). 약 300줄.
- 후속 작업(non-blocking 재시도): 사용자는 여러 pod 환경에서 non-blocking 재시도를 기본으로 생각했고, 이번에는 blocking으로 시작하기로 했다. 판단 근거는 pod 수가 아니다. 파티션은 pod마다 나뉘어 배정되고 consumer 스레드 수가 파티션 수와 같으므로 blocking이 막는 범위는 실패한 메시지가 있는 파티션 하나다. DLT로 가기 전 그 파티션이 멈추는 시간(지금 약 31초)이 허용 범위를 넘는지, 또는 재시도 간격을 분 단위로 늘려야 하는 장애(provider 장애)가 생기는지가 기준이다. 그때 `@RetryableTopic`으로 바꾸고 같은 주문의 순서가 깨지는 것을 consumer가 견디는지 함께 본다.

## TDD 검증 기록
consumer 전환은 업무 흐름의 전달 경로를 바꾸므로 통합 테스트를 먼저 쓰고 RED를 관측한다. 리스너 본문과 Facade는 바뀌지 않는다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 1·2: `checkout이 네 토픽을 거쳐 PAID와 fulfillment가 되고 key는 orderId다` | `./gradlew test --tests 'com.resilientpos.platform.KafkaEventDeliveryIntegrationTest'`. 실패: `order.payment-requested 에 key가 orderId인 메시지 하나` 기대 1, 실제 0 | `@Externalized`·`@KafkaListener`·토픽 생성 뒤 같은 명령. 통과 | `./gradlew test`. 251개 중 실패 0 |
| 인수 4: `발행 기록은 이벤트마다 externalizer 행 하나이고 전송이 끝나면 완료된다` | 같은 명령. 실패: 기대 `...EventExternalizerModuleListener.externalize(java.lang.Object)`, 실제 `...OrderPaymentRequestedListener.on(...)` | 같은 명령. 통과 | `./gradlew test`. 251개 중 실패 0 |
| 인수 3: `기동하면 토픽과 DLT가 설정한 파티션 수로 생긴다` | 같은 명령. 실패: `order.payment-requested 파티션 수` 기대 1, 실제 null | `KafkaConfiguration.eventTopics` 뒤 같은 명령. 통과 | `./gradlew test`. 251개 중 실패 0 |
| 인수 5: `일시 장애 뒤 성공하면 DLT로 가지 않고 한 번만 반영된다` | `./gradlew test --tests 'com.resilientpos.platform.KafkaRetryIntegrationTest'`. 오류 처리기 전에도 통과했다(Spring Kafka 기본값이 간격 없이 9회 재시도). characterization으로 남긴다. 처음 실행은 Mockito `any()`가 Kotlin non-null 검사에 걸린 설정 오류라 RED가 아니었고 매처 도우미로 고쳤다 | 같은 명령. 통과(호출 3회) | `./gradlew test`. 251개 중 실패 0 |
| 인수 6: `재시도를 모두 실패하면 DLT로 가고 다음 메시지가 처리된다` | 같은 명령. 실패: `20초 안에 충족되지 않았다: DLT에 원래 key로 들어간다` | `kafkaErrorHandler`(지수 backoff 5회, DLT 발행, counter) 뒤 같은 명령. 통과(호출 6회, counter +1) | `./gradlew test`. 251개 중 실패 0 |
| 인수 7: `DomainException은 재시도 없이 DLT로 가고 주문은 그대로다` | 같은 명령. 실패: `DLT에 들어간다` 시간 초과 | `addNotRetryableExceptions(DomainException)` 뒤 같은 명령. 통과(`markPaid` 1회) | `./gradlew test`. 251개 중 실패 0 |
| 인수 8: `역직렬화할 수 없는 값은 consumer를 멈추지 않고 DLT로 간다` | 같은 명령. 실패: `DLT에 들어간다` 시간 초과 | 같은 오류 처리기로 같은 명령. 통과 | `./gradlew test`. 251개 중 실패 0 |
| 인수 9: `같은 이벤트를 두 번 받아도 시도와 PAID 전이와 fulfillment는 하나다` | 전환 뒤 처음 실행부터 통과. 기존 멱등 근거(도메인 모델 §4.3)를 Kafka 경로로 확인한 characterization이다 | `KafkaEventDeliveryIntegrationTest`. 통과 | `./gradlew test`. 251개 중 실패 0 |
| 인수 10: `타입 헤더 없이 온 메시지도 토픽의 이벤트 타입으로 읽는다` | 처음 실행부터 통과. 변환기가 파라미터 타입으로 읽는 것을 고정한 characterization이다 | 같은 명령. 통과 | `./gradlew test`. 251개 중 실패 0 |
| 인수 6 리뷰 반영: `DB에 닿지 못하는 실패는 재시도 횟수를 넘겨도 기다렸다가 처리한다`, `재시도 허용 목록 밖의 예외는 재시도 없이 DLT로 간다` | 구현을 먼저 고쳐서, 뒤에 쓴 테스트는 구현을 잠시 되돌려(backoff 함수를 빼고 `defaultFalse`를 이전 분류로) 관측했다. 실패: `fulfillment가 생긴다` 시간 초과, 호출 기대 1 실제 6 | `./gradlew test --tests 'com.resilientpos.platform.KafkaRetryIntegrationTest'`. 통과 | `./gradlew test`. 254개 중 실패 0 |
| 인수 3 리뷰 반영: DLT 보존 기간, 듣는 토픽과 설정 목록 대조 | 보존 기간 설정을 잠시 빼면 실패: `order.payment-requested.DLT 보존 기간` 기대 -1 실제 604800000. 대조 테스트는 처음부터 통과(characterization) | `KafkaEventDeliveryIntegrationTest`. 통과 | `./gradlew test`. 254개 중 실패 0 |
| 인수 11: `prometheus 엔드포인트가 Kafka consumer metric과 토픽별 DLT 개수를 돌려준다` | `./gradlew test --tests 'com.resilientpos.platform.ObservabilityConfigurationTest'`. 실패: `order.payment-requested 의 event_kafka_dead_letter_total이 없다`(consumer metric은 이미 있었다) | 토픽별 0 등록 뒤 같은 명령. 통과 | `./gradlew test`. 251개 중 실패 0 |
| 인수 12: compose 실행 | 해당 없음(수동 확인) | 아래 결과 절 | 해당 없음 |
| 인수 13: 기존 테스트 | 전환 직후 `./gradlew test`에서 7개 실패(옛 리스너의 발행 기록을 기다리다 시간 초과, 어댑터 예외의 재시도가 파티션을 붙잡아 뒤 테스트 시간 초과) | offset·DLT 기준으로 고치고 테스트 재시도 간격을 줄인 뒤 `./gradlew test`. 251개 중 실패 0 | 같은 명령. 실패 0 |
| 리뷰 반영: `처리 도중 DB 연결이 끊겨 롤백까지 실패해도 DLT로 보내지 않고 다시 시도한다` | `./gradlew test --tests 'com.resilientpos.platform.KafkaRetryIntegrationTest'`. 실측한 예외 체인(`JpaSystemException` ← `TransactionException` ← `SQLException`)을 던지게 하자 실패: `fulfillment가 생긴다` 시간 초과(바로 DLT로 갔다) | 재시도 목록에 세 예외를 더한 뒤 같은 명령. 통과 | `./gradlew test`. 259개 중 실패 0 |

## 결과
- `./gradlew test`: 251개 중 실패 0(2026-09-26).
- 인수 12: `docker compose -f docker/docker-compose.yaml --profile app up -d --build` 뒤 `POST /v1/orders`(4500원 한 줄)와 `POST /v1/orders/{orderId}/checkout`을 부르자 3초 안에 주문이 `PAID`, fulfillment 1건, 발행 기록은 externalizer 행 넷이 모두 `COMPLETED`였다. 토픽 여덟 개(원 토픽 넷과 DLT 넷)가 생겼다.
- DLT 재투입 절차: 깨진 값을 key와 함께 `order.paid`에 넣어 DLT로 보낸 뒤, 도메인 모델 §4.3의 console consumer·producer 명령으로 다시 넣었다. 같은 key로 다시 DLT에 들어가 key가 보존되는 것을 확인했다.
- consumer lag: `/actuator/prometheus`에 `kafka_consumer_fetch_manager_records_lag_max`가 consumer group마다(`client_id`에 group 이름) 나오는 것을 임시 출력으로 확인하고 되돌렸다.
- 배포 조건 SQL은 배포된 환경이 없어 실행하지 않았다. 로컬 compose 볼륨에는 전환 전 측정 때 쌓인 행이 없었다(#60에서 `down -v`로 지웠다).
- multi-review 반영 뒤(2026-09-27): `./gradlew test` 259개 중 실패 0. compose에서 주문이 `PAID`까지 가고, DLT가 복제 수를 지정하지 않아 브로커 기본값(1)·`retention.ms=-1`로 만들어지며, 브로커를 내린 채 앱을 띄우면 기동이 실패(exit 1)하는 것을 확인했다.
- 발행 기록 실측(2026-09-27, compose): 승인되는 주문 3건은 이벤트 4종마다 externalizer 행 하나씩 12행이 모두 `COMPLETED`였고, 결과를 모르는 주문은 2행이었다. 브로커를 내린 채 checkout하면 요청은 200으로 바로 끝나고 행은 `PROCESSING`으로 남았다가 약 120초 뒤 `FAILED`가 됐다(`Expiring 1 record(s) … 120003 ms has passed since batch creation`). 토픽 metadata가 캐시돼 있으면 전송이 막히지 않고 버퍼에 들어가 `delivery.timeout.ms`까지 재시도하기 때문이다. 브로커를 다시 올려도 행은 `FAILED`로 남고 주문은 `CHECKOUT_REQUESTED`에 머물렀으며, `event_publication_incomplete{status="failed"}`가 1이 됐다. 자동 재전송은 PR ③이다.
