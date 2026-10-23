# 이벤트 계약과 호환 규칙

외부로 나가는 이벤트의 JSON 모양은 저장 형식이다. Kafka 토픽과 DLT(`원토픽.DLT`), 발행 기록(`event_publication.serialized_event`)에 그 모양 그대로 남는다. DLT는 보존 기간이 없어서 옛 형식 메시지가 지워지지 않는다. 그 메시지를 재투입하거나 발행 기록을 배포 뒤에 다시 보내면, 그사이 바뀐 지금 코드가 옛 형식을 읽는다.

이 페이지는 세 가지를 정한다. 어떤 변경이 안전한지, 안전하지 않은 변경은 어떻게 하는지, 그것을 지키는 테스트가 무엇을 보는지다. 결정 근거와 버린 선택지는 [event-contract-compatibility](../spec/2026-10-20-event-contract-compatibility.md)에 있다. 토픽 이름·consumer group·key·`event_type`·`listener_id`가 저장 형식이라는 규칙은 [도메인 모델 §4.3](domain-model.md#43-이벤트-전달과-저장-형식)이 정본이다.

## 1. 대상

`@Externalized`가 붙은 이벤트 다섯이다. 모듈 안 이벤트(`PaymentEvent.Declined`·`Unknown`, `order`의 모듈 안 이벤트)는 받는 모듈 안 리스너가 없어 어디에도 저장되지 않으므로 대상이 아니다. 외부로 내보내는 순간 대상이 된다(7절).

| 이벤트 | 토픽 | fixture |
|---|---|---|
| `contract.OrderPaymentRequested` | `order.payment-requested` | `src/test/resources/event-contract/order.payment-requested/` |
| `payment.PaymentEvent.AttemptCreated` | `payment.attempt-created` | `src/test/resources/event-contract/payment.attempt-created/` |
| `contract.PaymentApproved` | `payment.approved` | `src/test/resources/event-contract/payment.approved/` |
| `contract.OrderPaid` | `order.paid` | `src/test/resources/event-contract/order.paid/` |
| `contract.PaymentReconciliationRequested` | `payment.reconciliation-requested` | `src/test/resources/event-contract/payment.reconciliation-requested/` |

## 2. 무엇이 저장되나

- Kafka 값과 `serialized_event`는 같은 JSON이다. 둘 다 Boot의 `JsonMapper` 빈으로 쓴다. Modulith Kafka 모듈의 메시지 변환기(`ByteArrayJacksonJsonMessageConverter`)와 발행 기록의 `JacksonEventSerializer`가 같은 빈을 받는다.
- consumer는 `@KafkaListener` 메서드의 파라미터 타입으로 읽는다. 보낼 때 클래스 이름 타입 헤더(`__TypeId__`)가 붙지만 읽을 때는 쓰지 않는다(`TypePrecedence.INFERRED`). 그래서 헤더 없이 다시 넣은 메시지도 같은 타입으로 읽힌다.
- 지금 형식은 아래와 같다. 예시는 `src/test/resources/event-contract/order.paid/1.json`이다.
  - ID(value class 포함)는 UUID 문자열이다.
  - 시각(`Instant`)은 ISO-8601 문자열이고 소수 초를 그대로 둔다(`2026-10-20T01:02:03.456789Z`).
  - 금액(`Money`)은 `{"amount": 숫자, "currency": "KRW"}`다.
  - enum은 이름 문자열, `schemaVersion`은 정수다.

## 3. 지금 consumer가 JSON을 읽는 방식

Boot 4.1.2는 Jackson 3 기본값을 그대로 쓴다(`spring.jackson.use-jackson2-defaults`를 두지 않는다). 이 저장소의 `JsonMapper`로 확인한 동작이다.

| JSON의 상태 | 결과 |
|---|---|
| 클래스에 없는 필드가 있다 | 무시한다 |
| 클래스의 필드가 없다, 기본값이 있다 | 기본값으로 채운다 |
| 클래스의 필드가 없다, nullable이다 | `null`로 채운다 |
| 클래스의 필드가 없다, 기본값 없는 non-null이다 | 실패한다(`Missing required creator property`) |
| 클래스가 모르는 enum 값이다 | 실패한다(`InvalidFormatException`) |

여기서 가장 위험한 경우가 나온다. **필드 이름을 바꾸면 옛 이름은 "모르는 필드"로 버려지고, 새 이름은 "없는 필드"라 기본값이나 `null`이 된다. 실패하지 않고 값만 잃는다.** 실패한 메시지는 DLT에 남아 되살릴 수 있지만, 조용히 처리된 메시지는 흔적이 없다.

Jackson이나 Boot를 올려 이 동작이 바뀌면 계약 테스트(6절)의 `빠진 필드와 모르는 enum 값을 규칙 표대로 읽거나 거절한다`와 `모르는 필드가 더해진 메시지도 두 경로가 읽는다`가 실패한다.

## 4. 변경 규칙

하위 호환은 옛 메시지를 지금 코드가 읽는 방향이다(DLT 재투입, 배포 뒤 발행 기록 재전송). 상위 호환은 새 메시지를 옛 코드가 읽는 방향이다(롤링 배포 중의 옛 pod, 롤백).

| 변경 | 하위 호환 | 상위 호환 | 규칙 |
|---|---|---|---|
| 기본값이 있거나 nullable인 필드 추가 | 된다 | 된다(모르는 필드 무시). 단 옛 pod의 재전송은 새 필드를 버린다(4.1) | 허용. 빠진 값은 "모름"이어야 한다(4.1). 다음 번호 fixture를 더한다 |
| 기본값 없는 non-null 필드 추가 | 안 된다 | 된다 | 금지. 기본값·nullable로 더하거나 `schemaVersion`을 올린다 |
| 필드 삭제 | 된다(무시) | 안 된다(옛 필드가 non-null이면 실패, nullable·기본값이면 옛 pod가 값을 잃는다) | 금지. 계약 테스트가 막는다. 쓰지 않는 필드로 남긴다 |
| 필드 이름 변경 | 값을 잃거나 실패 | 안 된다(삭제와 같다) | 금지. 계약 테스트가 막는다. 새 필드를 더하고 옛 필드도 계속 채운다 |
| nullable을 non-null로 좁히기 | 안 된다(옛 `null` 메시지가 실패) | 된다 | 금지. 계약 테스트가 `providerReference = null`로 막는다 |
| `init` 불변식 강화 | 옛 메시지가 실패할 수 있다 | 된다 | 옛 fixture로 확인한다. 실패하면 `schemaVersion`을 올린다 |
| JSON 표현이 바뀌는 타입 변경 | 안 된다 | 안 된다 | `schemaVersion`을 올리고 변환을 둔다(5절) |
| enum 값 추가 | 된다 | 안 된다(옛 pod 바인딩 실패) | 허용. 롤링 배포 중 옛 pod가 DLT로 보낸 메시지는 배포 뒤 재투입한다 |
| enum 값 제거·이름 변경 | 안 된다 | 된다 | `schemaVersion`을 올리고 변환을 둔다. 계약 테스트가 머지된 이름 목록으로 막는다 |
| 이벤트 클래스 이동·이름 변경 | Kafka는 상관없다. 발행 기록은 안 된다(`event_type`) | 같다 | 미완료 발행 기록이 0건일 때만(도메인 모델 §4.3) |
| 토픽 이름 변경 | 안 된다 | 안 된다 | 금지 |

- JSON 표현이 같은 타입 변경(예: `Int`를 `Long`으로)은 형식이 바뀌지 않아 계약 테스트가 통과한다. 옛 pod가 범위를 넘는 값을 받는 경우는 사람이 본다.
- enum 값을 더할 때 옛 pod의 실패는 가드보다 먼저 바인딩 단계에서 난다. 그래서 DLT metric의 `reason`이 `error`로 찍힌다. 새 값은 계약 테스트의 머지된 이름 목록에 더한다.

### 4.1 빠진 필드는 "모름"이다

버전을 올리지 않는 필드 추가에는 가드가 걸리지 않는다. 그래서 새 필드가 빠진 메시지가 두 곳에서 온다.
- 필드를 더하기 전에 저장된 옛 메시지(DLT, 발행 기록).
- 롤링 배포 중 옛 pod가 다시 보낸 메시지. 옛 pod의 재전송은 발행 기록을 옛 클래스로 읽어 다시 직렬화하므로 새 필드를 버린다.

두 메시지는 모양이 같다. 그래서 막는 대신 consumer가 그 모양을 옳게 처리하게 한다.
- 새 필드가 빠졌을 때의 값(기본값·`null`)은 "모름"이다. 실제 업무 값을 기본값으로 두지 않는다. 예를 들어 `PaymentApproved.kind`를 나중에 기본값 `AUTHORIZATION`으로 더했다면, 옛 취소 승인을 결제 승인으로 읽어 주문을 결제 완료로 넘겼을 것이다.
- consumer는 "모름"이면 필드를 더하기 전처럼 동작하거나, 자기 데이터에서 값을 찾는다.
- 빠지면 옳게 처리할 수 없는 필드면 `schemaVersion`을 올린다(5절). 조용히 틀리는 대신 DLT로 가고, 배포 뒤 재투입한다.
- 계약 테스트는 역직렬화까지만 증명한다. 이 규칙을 지키는지는 리뷰가 본다.

**필드를 더할 때 체크리스트**
1. 기본값이나 nullable로 더하고, 빠진 값이 "모름"인지 확인한다.
2. consumer가 "모름"을 처리하는 테스트를 이전 번호 fixture(그 필드가 없는 메시지)로 더한다.
3. `EventContracts`의 고정 인스턴스에 기본값이 아닌 값을 넣고, 다음 번호 fixture를 더한다(6절).
4. 빠지면 옳게 처리할 수 없으면 이 체크리스트 대신 5절의 버전 올리는 절차를 따른다.

## 5. `schemaVersion`과 가드

- 대상 이벤트는 마지막 생성자 인자로 `val schemaVersion: Int = SCHEMA_VERSION`을 갖고, `companion object`에 `const val SCHEMA_VERSION`을 둔다. 지금은 모두 1이다.
- **버전은 호환되지 않는 변경에만 올린다.** 필드 추가 같은 호환 변경에 올리면, 롤링 배포 중 옛 pod가 읽을 수 있는 메시지까지 아래 가드가 DLT로 보낸다. 그래서 fixture 번호(형식이 바뀔 때마다 는다)와 `schemaVersion`(변환이 필요할 때만 는다)은 따로 움직인다.
- **가드**: 이벤트의 `init`이 `contract.requireKnownSchemaVersion`을 부른다. 받은 버전이 `SCHEMA_VERSION`보다 크면 `DomainException(UNSUPPORTED_EVENT_SCHEMA_VERSION, …, UNPROCESSABLE)`을 던진다. Jackson이 생성자를 부르므로 두 경로에 모두 걸린다.
  - Kafka consumer: 재시도 없이 DLT로 가고 `event_kafka_dead_letter_total{reason="UNSUPPORTED_EVENT_SCHEMA_VERSION"}`이 는다. 이 reason이 보이면 롤링 배포 중 옛 pod가 새 버전 메시지를 받은 것이다. 배포가 끝난 뒤 그대로 재투입한다. 이 reason의 counter는 처음 DLT로 보낼 때 생긴다. 알림 `KafkaDeadLetter`는 이 counter가 아니라 재투입도 폐기도 하지 않은 DLT 메시지 수로 울리므로 처음 생긴 reason도 놓치지 않는다([runbook](runbook.md#kafkadeadletter)).
  - 발행 기록 재전송: 버전을 올린 변경에서, 옛 pod가 새 버전 기록을 옛 클래스 모양으로 다시 써서 새 필드를 잃은 채 보내는 일을 막는다. 버전을 올리지 않는 필드 추가는 막지 못한다(4.1).
- **재전송의 대가**: 옛 pod가 새 버전 기록을 만나면 그 pod의 재전송 실행이 그 행에서 끊긴다(spring-modulith#1904, 도메인 모델 §4.3).
  - 옛 pod가 재전송 표시를 먼저 커밋하므로 그 행은 `RESUBMITTED`로 남는다. 5분(`stale-after`) 뒤 `FAILED`로 돌아와야 새 pod가 보낸다.
  - 그 실행에서 뒤에 올 행과 정리 단계는 다음 주기로 밀린다.
  - **롤백하면** 모든 pod가 옛 버전이다. 새 버전 기록은 나가지 못하고 5분마다 실행을 끊는다. 이런 기록이 몇 건만 쌓여도 다른 `FAILED` 기록의 재전송까지 사실상 멈춘다. 롤백했으면 8절의 SQL로 찾아 다시 배포한 뒤 보낸다.

### 버전을 올리는 절차

1. 이벤트 클래스를 새 모양으로 바꾸고 `SCHEMA_VERSION`을 올린다.
2. 옛 버전 JSON을 새 모양으로 바꾸는 변환(upcast)을 그 이벤트의 역직렬화 지점(Jackson 생성자나 deserializer)에 둔다. 두 경로가 같은 `JsonMapper`라 한 곳에 두면 둘 다 걸린다. 변환 결과의 `schemaVersion`은 현재 값이다. `schemaVersion`이 없는 JSON은 1로 본다. 기본값이 현재 상수라 그대로 두면 최신 버전으로 읽힌다. 구체 방식은 첫 변환을 만드는 PR이 정하고 이 절을 고친다.
3. 새 형식을 다음 번호 fixture로 더한다. 옛 fixture는 그대로 둔다. 계약 테스트의 값 보존 검사는 지금 버전 fixture만 보므로, 옛 버전 fixture를 읽은 결과의 기대값 테스트를 같은 PR에서 더한다.
4. 롤링 배포 동안 옛 pod는 새 버전 메시지를 DLT로 보내고 재전송이 끊긴다. 배포 뒤 DLT의 그 메시지를 재투입한다.

이 DLT를 피하는 두 단계 배포도 있다. 먼저 새 버전을 읽을 수만 있고 쓰기는 옛 버전으로 하는 배포를 한다. 다음 배포에서 새 버전을 쓴다. 스키마의 expand 뒤 contract와 같은 순서다. 결제 요청처럼 DLT로 가면 업무가 멈추는 이벤트라면 이쪽이 낫다. 다만 지금은 상수 하나(`SCHEMA_VERSION`)가 "읽는 최대 버전"과 "쓰는 버전"을 겸해서 이 방식을 표현할 수 없다. 첫 변환 PR이 두 상수로 나눌지 정한다.

## 6. golden fixture와 계약 테스트

- **위치**: `src/test/resources/event-contract/토픽/번호.json`. 번호는 1부터 형식이 바뀔 때마다 하나씩 는다.
- **내용**: `src/test/kotlin/com/resilientpos/contract/EventContracts.kt`의 고정 인스턴스를 지금 코드로 직렬화한 JSON이다. 비교는 JSON 트리로 하므로 들여쓰기와 필드 순서는 상관없다.
- **갱신 규칙**
  - 머지된 fixture는 고치지 않는다. DLT에 그 모양 그대로 남아 있을 수 있다. 테스트로는 막을 수 없어서 리뷰가 `git diff --name-status origin/main...HEAD -- src/test/resources/event-contract/`에 `A`(추가)만 있는지 본다.
  - 번호는 1부터 빈틈없이 잇는다. 테스트가 검사한다.
  - 형식이 바뀌면 다음 번호 파일을 더하고 `EventContracts`의 고정 인스턴스를 새 형식에 맞춘다. 새 필드에는 기본값이 아닌 값을 넣어 형식에 드러나게 한다.
  - 새 파일 내용은 쓰기 검사의 실패 메시지에 나오는 지금 JSON이다. 손으로 쓰지 않는다.
  - 라이브러리를 올려 표현만 바뀌면(예: 시각의 소수 자리) 지금 버전 옛 fixture의 값 보존 검사가 실패한다. 옛 fixture는 고칠 수 없으므로 옛 표현을 고정한다(필드의 `@JsonFormat`이나 매퍼 설정). 그럴 수 없으면 두 표현을 모두 읽는지 확인하고 판단을 그 PR spec에 남긴다.
- **`EventContractCompatibilityTest`가 보는 것**
  1. 클래스패스의 `@Externalized` 타입 집합이 `EventContracts` 목록과 같다. 토픽이 `@Externalized`의 대상과 같고, 마지막 fixture의 `schemaVersion`이 `SCHEMA_VERSION`이다.
  2. 읽기: 모든 fixture를 Kafka 변환기와 `EventSerializer`가 같은 값으로 읽는다. 지금 버전 fixture는 읽었다가 다시 쓴 JSON에 fixture의 모든 필드가 같은 값으로 남는다. 삭제·이름 변경·표현이 바뀌는 타입 변경이 여기서 걸린다.
  3. 쓰기: 고정 인스턴스를 두 경로로 직렬화한 JSON이 마지막 fixture와 같다. 필드 추가나 라이브러리 업그레이드로 형식이 바뀌면 여기서 걸린다.
  4. 모르는 필드를 더한 마지막 fixture도 두 경로가 고정 인스턴스로 읽는다(상위 호환의 전제).
  5. 3절의 Jackson 동작: 빠진 nullable은 `null`, 빠진 `schemaVersion`은 1이다. 빠진 non-null(객체·원시 타입)과 모르는 enum 값은 실패한다.
  6. 머지된 enum 이름(`AUTHORIZATION`, `CANCELLATION`)과 `null` 값으로 바꾼 JSON을 두 경로가 읽고 같은 값으로 다시 쓴다. enum 이름을 지우거나 바꾸면, 또는 nullable을 non-null로 좁히면 여기서 걸린다.
  7. `schemaVersion`이 아는 것보다 큰 JSON은 두 경로 모두 `UNSUPPORTED_EVENT_SCHEMA_VERSION`으로 거절한다. 그런 메시지를 `order.paid`에 넣으면 DLT로 가고 reason으로 세며 뒤 메시지는 처리된다.
- Kafka 경로로 읽을 때는 레코드에 다른 클래스 이름의 `__TypeId__` 헤더를 단다. 읽기가 헤더가 아니라 파라미터 타입을 쓴다는 것(2절)을 함께 고정한다.
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

재투입 API(`POST /admin/v1/dead-letters/{topic}/{partition}/{offset}/redrive`)는 넣기 전에 값을 원 토픽의 이벤트 타입으로, consumer와 같은 `JsonMapper`로 읽어 본다. 아래 첫째·셋째 경우(높은 `schemaVersion`, 모르는 enum 값)는 `422 DEAD_LETTER_NOT_READABLE`로 거절되고 아무것도 보내지 않는다. 다만 요청을 받은 pod의 코드로만 확인하므로, 롤링 배포 중이면 "모든 pod에 배포됐는가"는 운영자가 본다. 목록(`GET /admin/v1/dead-letters`)의 `schemaVersion`과 `exception`·`exceptionMessage`로 미리 가를 수 있다([platform-operator-dlt-redrive](../spec/2026-10-21-platform-operator-dlt-redrive.md)).

- 메시지의 `schemaVersion`이 지금 배포된 `SCHEMA_VERSION` 이하인가. 높으면 그 버전을 아는 코드가 모든 pod에 배포된 뒤 넣는다.
- `reason="UNSUPPORTED_EVENT_SCHEMA_VERSION"`으로 간 메시지는 위 조건만 맞으면 그대로 넣는다.
- enum 값을 더한 배포 중 옛 pod가 보낸 메시지는 `reason="error"`라 다른 실패와 섞인다. 로그 `DLT로 보냄`의 스택 트레이스에 `not one of the values accepted for Enum class`가 있으면 그 경우다. 새 값을 아는 코드가 배포된 뒤 넣는다.
- 아는 것보다 높은 버전이라 나가지 못한 발행 기록(롤백 뒤)은 아래로 찾는다.
  ```sql
  select id, event_type, status, publication_date from event_publication
  where completion_date is null and (serialized_event::jsonb ->> 'schemaVersion')::int > 1
  order by publication_date;
  ```
  `> 1`은 지금 배포된 `SCHEMA_VERSION`으로 바꿔 쓴다.
- 그 밖의 이유로 간 메시지와 재투입 절차는 [도메인 모델 §4.3](domain-model.md#43-이벤트-전달과-저장-형식)을 따른다.
- 넣지 않기로 한 메시지(지금 버전으로 다시 넣어도 같은 이유로 실패하는 업무 불일치 등)는 재투입하지 않고 폐기를 표시한다(`POST /admin/v1/dead-letters/{topic}/{partition}/{offset}/dismiss`). 읽기 검사 없이 기록만 남고, 그 메시지가 미처리 DLT 수와 알림에서 빠진다.

## 9. 하지 않은 것

- 원문 시스템 설계의 integration envelope(`eventType`·`producer`·`correlationId`·`data`), AsyncAPI, Schema Registry. 소비자가 모두 같은 앱이고 메시지 형식은 Jackson JSON이다(Modulith·Kafka 키워드 8.20). 앱 밖 consumer가 생길 때 다시 본다.
- 새 fixture를 이전 릴리스 코드로 읽는 상위 호환 자동 검사. 배포 기준 버전이 아직 없다.
- 모르는 enum 값을 `null`로 읽는 설정(`READ_UNKNOWN_ENUM_VALUES_AS_NULL`). 결제 종류를 모르는 승인을 주문이 처리하게 된다. DLT에서 재투입으로 복구한다.
- 모르는 필드에 실패하는 설정(`FAIL_ON_UNKNOWN_PROPERTIES`). 이름 변경을 큰 실패로 바꾸지만, 롤링 배포 중 옛 pod가 필드를 더한 새 메시지를 읽지 못한다.
- 재전송이 저장된 JSON을 다시 직렬화하지 않고 그대로 보내게 하는 것. 4.1의 손실이 사라지지만 Modulith externalizer나 `EventSerializer`를 갈아끼우는 `platform` 변경이다.
