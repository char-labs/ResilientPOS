# 이벤트 계약과 호환 규칙

외부로 나가는 이벤트의 JSON 모양은 저장 형식이다. Kafka 토픽과 DLT(`원토픽.DLT`), 발행 기록(`event_publication.serialized_event`)에 그 모양 그대로 남는다. DLT는 보존 기간이 없어서 옛 형식 메시지가 지워지지 않는다. 그 메시지를 재투입하거나 발행 기록을 배포 뒤에 다시 보내면, 그사이 바뀐 지금 코드가 옛 형식을 읽는다.

이 페이지는 세 가지를 정한다. 어떤 변경이 안전한지, 안전하지 않은 변경은 어떻게 하는지, 그것을 지키는 테스트가 무엇을 보는지다. 결정 근거와 버린 선택지는 [event-contract-compatibility](../spec/2026-10-20-event-contract-compatibility.md)에 있다. 토픽 이름·consumer group·key·`event_type`·`listener_id`가 저장 형식이라는 규칙은 [도메인 모델 §4.3](domain-model.md#43-이벤트-전달과-저장-형식)이 정본이다.

## 1. 대상

`@Externalized`가 붙은 이벤트 넷이다. 모듈 안 이벤트(`PaymentEvent.Declined`·`Unknown`, `order`의 모듈 안 이벤트)는 받는 모듈 안 리스너가 없어 어디에도 저장되지 않으므로 대상이 아니다. 외부로 내보내는 순간 대상이 된다(7절).

| 이벤트 | 토픽 | fixture |
|---|---|---|
| `contract.OrderPaymentRequested` | `order.payment-requested` | `src/test/resources/event-contract/order.payment-requested/` |
| `payment.PaymentEvent.AttemptCreated` | `payment.attempt-created` | `src/test/resources/event-contract/payment.attempt-created/` |
| `contract.PaymentApproved` | `payment.approved` | `src/test/resources/event-contract/payment.approved/` |
| `contract.OrderPaid` | `order.paid` | `src/test/resources/event-contract/order.paid/` |

## 2. 무엇이 저장되나

- Kafka 값과 `serialized_event`는 같은 JSON이다. 둘 다 Boot의 `JsonMapper` 빈으로 쓴다. Modulith Kafka 모듈의 메시지 변환기(`ByteArrayJacksonJsonMessageConverter`)와 발행 기록의 `JacksonEventSerializer`가 같은 빈을 받는다.
- consumer는 `@KafkaListener` 메서드의 파라미터 타입으로 읽는다. 보낼 때 클래스 이름 타입 헤더(`__TypeId__`)가 붙지만 읽을 때는 쓰지 않는다(`TypePrecedence.INFERRED`). 그래서 헤더 없이 다시 넣은 메시지도 같은 타입으로 읽힌다.
- 지금 형식은 아래와 같다. 예시는 `src/test/resources/event-contract/order.paid/1.json`이다.
  - ID(value class 포함)는 UUID 문자열이다.
  - 시각(`Instant`)은 ISO-8601 문자열이고 소수 초를 그대로 둔다(`2026-10-20T01:02:03.456789Z`).
  - 금액(`Money`)은 `{"amount": 숫자, "currency": "KRW"}`다.
  - enum은 이름 문자열, `schemaVersion`은 정수다.

## 3. 지금 consumer가 JSON을 읽는 방식

Boot 4.1.1은 Jackson 3 기본값을 그대로 쓴다(`spring.jackson.use-jackson2-defaults`를 두지 않는다). 이 저장소의 `JsonMapper`로 확인한 동작이다.

| JSON의 상태 | 결과 |
|---|---|
| 클래스에 없는 필드가 있다 | 무시한다 |
| 클래스의 필드가 없다, 기본값이 있다 | 기본값으로 채운다 |
| 클래스의 필드가 없다, nullable이다 | `null`로 채운다 |
| 클래스의 필드가 없다, 기본값 없는 non-null이다 | 실패한다(`Missing required creator property`) |
| 클래스가 모르는 enum 값이다 | 실패한다(`InvalidFormatException`) |

여기서 가장 위험한 경우가 나온다. **필드 이름을 바꾸면 옛 이름은 "모르는 필드"로 버려지고, 새 이름은 "없는 필드"라 기본값이나 `null`이 된다. 실패하지 않고 값만 잃는다.** 실패한 메시지는 DLT에 남아 되살릴 수 있지만, 조용히 처리된 메시지는 흔적이 없다.

Jackson이나 Boot를 올려 이 동작이 바뀌면 계약 테스트(6절)가 실패한다.

## 4. 변경 규칙

하위 호환은 옛 메시지를 지금 코드가 읽는 방향이다(DLT 재투입, 배포 뒤 발행 기록 재전송). 상위 호환은 새 메시지를 옛 코드가 읽는 방향이다(롤링 배포 중의 옛 pod, 롤백).

| 변경 | 하위 호환 | 상위 호환 | 규칙 |
|---|---|---|---|
| 기본값이 있거나 nullable인 필드 추가 | 된다 | 된다(모르는 필드 무시) | 허용. 다음 번호 fixture를 더한다 |
| 기본값 없는 non-null 필드 추가 | 안 된다 | 된다 | 금지. 기본값·nullable로 더하거나 `schemaVersion`을 올린다 |
| 필드 삭제 | 된다(무시) | 안 된다(옛 pod가 non-null 필드를 못 채운다) | 금지. 계약 테스트가 막는다. 쓰지 않는 필드로 남긴다 |
| 필드 이름 변경 | 값을 잃거나 실패 | 안 된다 | 금지. 계약 테스트가 막는다. 새 필드를 더하고 옛 필드도 계속 채운다 |
| JSON 표현이 바뀌는 타입 변경 | 안 된다 | 안 된다 | `schemaVersion`을 올리고 변환을 둔다(5절) |
| enum 값 추가 | 된다 | 안 된다(옛 pod 바인딩 실패) | 허용. 롤링 배포 중 옛 pod가 DLT로 보낸 메시지는 배포 뒤 재투입한다 |
| enum 값 제거·이름 변경 | 안 된다 | 된다 | `schemaVersion`을 올리고 변환을 둔다 |
| 이벤트 클래스 이동·이름 변경 | Kafka는 상관없다. 발행 기록은 안 된다(`event_type`) | 같다 | 미완료 발행 기록이 0건일 때만(도메인 모델 §4.3) |
| 토픽 이름 변경 | 안 된다 | 안 된다 | 금지 |

- JSON 표현이 같은 타입 변경(예: `Int`를 `Long`으로)은 형식이 바뀌지 않아 계약 테스트가 통과한다. 옛 pod가 범위를 넘는 값을 받는 경우는 사람이 본다.
- enum 값을 더할 때 옛 pod의 실패는 가드보다 먼저 바인딩 단계에서 난다. 그래서 DLT metric의 `reason`이 `error`로 찍힌다.

## 5. `schemaVersion`과 가드

- 대상 이벤트는 마지막 생성자 인자로 `val schemaVersion: Int = SCHEMA_VERSION`을 갖고, `companion object`에 `const val SCHEMA_VERSION`을 둔다. 지금은 모두 1이다.
- **버전은 호환되지 않는 변경에만 올린다.** 필드 추가 같은 호환 변경에 올리면, 롤링 배포 중 옛 pod가 읽을 수 있는 메시지까지 아래 가드가 DLT로 보낸다. 그래서 fixture 번호(형식이 바뀔 때마다 는다)와 `schemaVersion`(변환이 필요할 때만 는다)은 따로 움직인다.
- **가드**: 이벤트의 `init`이 `contract.requireKnownSchemaVersion`을 부른다. 받은 버전이 `SCHEMA_VERSION`보다 크면 `DomainException(UNSUPPORTED_EVENT_SCHEMA_VERSION, …, UNPROCESSABLE)`을 던진다. Jackson이 생성자를 부르므로 두 경로에 모두 걸린다.
  - Kafka consumer: 재시도 없이 DLT로 가고 `event_kafka_dead_letter_total{reason="UNSUPPORTED_EVENT_SCHEMA_VERSION"}`이 는다. 이 reason이 보이면 롤링 배포 중 옛 pod가 새 버전 메시지를 받은 것이다. 배포가 끝난 뒤 그대로 재투입한다.
  - 발행 기록 재전송: 옛 pod가 새 버전 기록을 옛 클래스 모양으로 다시 써서 새 필드를 잃은 채 보내는 일을 막는다. 대가로 그 pod의 재전송 실행은 그 행에서 끊긴다(spring-modulith#1904, 도메인 모델 §4.3). 그 행은 새 pod가 보낸다.

### 버전을 올리는 절차

1. 이벤트 클래스를 새 모양으로 바꾸고 `SCHEMA_VERSION`을 올린다.
2. 옛 버전 JSON을 새 모양으로 바꾸는 변환(upcast)을 그 이벤트의 역직렬화 지점(Jackson 생성자나 deserializer)에 둔다. 두 경로가 같은 `JsonMapper`라 한 곳에 두면 둘 다 걸린다. 변환 결과의 `schemaVersion`은 현재 값이다. 구체 방식은 첫 변환을 만드는 PR이 정하고 이 절을 고친다.
3. 새 형식을 다음 번호 fixture로 더한다. 옛 fixture는 그대로 둔다. 계약 테스트의 값 보존 검사는 지금 버전 fixture만 보므로, 옛 버전 fixture를 읽은 결과의 기대값 테스트를 같은 PR에서 더한다.
4. 롤링 배포 동안 옛 pod는 새 버전 메시지를 DLT로 보내고 재전송이 끊긴다. 배포 뒤 DLT의 그 메시지를 재투입한다.

## 6. golden fixture와 계약 테스트

- **위치**: `src/test/resources/event-contract/토픽/번호.json`. 번호는 1부터 형식이 바뀔 때마다 하나씩 는다.
- **내용**: `src/test/kotlin/com/resilientpos/contract/EventContracts.kt`의 고정 인스턴스를 지금 코드로 직렬화한 JSON이다. 비교는 JSON 트리로 하므로 들여쓰기와 필드 순서는 상관없다.
- **갱신 규칙**
  - 머지된 fixture는 고치지 않는다. DLT에 그 모양 그대로 남아 있을 수 있다.
  - 형식이 바뀌면 다음 번호 파일을 더하고 `EventContracts`의 고정 인스턴스를 새 형식에 맞춘다. 새 필드에는 기본값이 아닌 값을 넣어 형식에 드러나게 한다.
  - 새 파일 내용은 쓰기 검사의 실패 메시지에 나오는 지금 JSON이다. 손으로 쓰지 않는다.
- **`EventContractCompatibilityTest`가 보는 것**
  1. 클래스패스의 `@Externalized` 타입 집합이 `EventContracts` 목록과 같다. 토픽이 `@Externalized`의 대상과 같고, 마지막 fixture의 `schemaVersion`이 `SCHEMA_VERSION`이다.
  2. 읽기: 모든 fixture를 Kafka 변환기와 `EventSerializer`가 같은 값으로 읽는다. 지금 버전 fixture는 읽었다가 다시 쓴 JSON에 fixture의 모든 필드가 같은 값으로 남는다. 삭제·이름 변경·표현이 바뀌는 타입 변경이 여기서 걸린다.
  3. 쓰기: 고정 인스턴스를 두 경로로 직렬화한 JSON이 마지막 fixture와 같다. 필드 추가나 라이브러리 업그레이드로 형식이 바뀌면 여기서 걸린다.
  4. 모르는 필드를 더한 마지막 fixture도 두 경로가 고정 인스턴스로 읽는다(상위 호환의 전제).
  5. `schemaVersion`이 아는 것보다 큰 JSON은 두 경로 모두 `UNSUPPORTED_EVENT_SCHEMA_VERSION`으로 거절한다. 그런 메시지를 `order.paid`에 넣으면 DLT로 가고 reason으로 세며 뒤 메시지는 처리된다.
- 실행: `./gradlew test --tests 'com.resilientpos.contract.*'`

## 7. 새 이벤트를 외부로 내보낼 때

1. 이벤트에 `@Externalized(TOPIC + "::#{#this.orderId}")`, `TOPIC`, `SCHEMA_VERSION = 1`, 마지막 인자 `val schemaVersion: Int = SCHEMA_VERSION`, `init`의 `requireKnownSchemaVersion`을 둔다.
2. `application.yaml`의 `resilientpos.kafka.topics`에 토픽을 넣는다(도메인 모델 §4.3).
3. `EventContracts`에 한 줄을 더하고 `event-contract/토픽/1.json`을 만든다.
4. 도메인 모델 §4.3의 이벤트 표와 Modulith·Kafka 키워드(`docs/wiki/modulith-kafka-keywords.md`, 볼트)를 고친다.

빠뜨리면 아래가 실패한다.
- `EventContracts`나 fixture가 없거나, fixture에 `schemaVersion`이 없으면 계약 테스트 1번이 실패한다.
- 가드가 없으면 계약 테스트 5번이 실패한다.
- consumer가 듣는 토픽이 목록에 없으면 `KafkaEventDeliveryIntegrationTest`가 실패한다.

## 8. DLT 재투입 전에 볼 것

- 메시지의 `schemaVersion`이 지금 배포된 `SCHEMA_VERSION` 이하인가. 높으면 그 버전을 아는 코드가 모든 pod에 배포된 뒤 넣는다.
- `reason="UNSUPPORTED_EVENT_SCHEMA_VERSION"`으로 간 메시지는 위 조건만 맞으면 그대로 넣는다.
- 그 밖의 이유로 간 메시지와 재투입 명령은 [도메인 모델 §4.3](domain-model.md#43-이벤트-전달과-저장-형식)의 절차를 따른다.

## 9. 하지 않은 것

- 원문 시스템 설계의 integration envelope(`eventType`·`producer`·`correlationId`·`data`), AsyncAPI, Schema Registry. 소비자가 모두 같은 앱이고 메시지 형식은 Jackson JSON이다(Modulith·Kafka 키워드 8.20). 앱 밖 consumer가 생길 때 다시 본다.
- 새 fixture를 이전 릴리스 코드로 읽는 상위 호환 자동 검사. 배포 기준 버전이 아직 없다.
- 모르는 enum 값을 `null`로 읽는 설정(`READ_UNKNOWN_ENUM_VALUES_AS_NULL`). 결제 종류를 모르는 승인을 주문이 처리하게 된다. DLT에서 재투입으로 복구한다.
