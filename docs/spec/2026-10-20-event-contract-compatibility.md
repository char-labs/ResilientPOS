# PR spec: 외부 전달 이벤트의 계약 호환성 테스트와 변경 규칙

## 목적
모듈 사이 이벤트에는 스키마 버전 필드도, 호환성을 검사하는 테스트도 없다. DLT(`원토픽.DLT`)는 보존 기간이 없어서 옛 형식 메시지가 지워지지 않는다. 그 메시지를 나중에 다시 넣을 때 그사이 바뀐 consumer가 읽지 못하거나, 더 나쁘게는 실패하지 않고 값을 잃은 채 처리할 수 있다. 발행 기록(`event_publication.serialized_event`)을 배포 뒤에 다시 보낼 때도 같은 일이 생긴다. 이 PR은 저장된 형식을 고정하는 계약 테스트, 변경 종류별 호환 규칙, 버전 필드와 그 가드를 만든다. 플랫폼 운영자의 DLT 재투입 API(#91)가 이 위에 선다.

이슈 #78의 세 항목(golden fixture 계약 테스트, 하위 호환 규칙 wiki, 버전 전략)을 모두 다룬다.

## 범위
소유 모듈은 `contract`다. `payment`는 `PaymentEvent.AttemptCreated`에 같은 필드를 더하는 것만 바꾸고 동작은 그대로다. consumer 넷과 `platform`은 바꾸지 않는다.

**대상은 외부로 나가는 이벤트 넷이다.** `@Externalized`가 붙은 `contract.OrderPaymentRequested`·`contract.PaymentApproved`·`contract.OrderPaid`와 `payment.PaymentEvent.AttemptCreated`다. 저장되는 형식은 이 넷뿐이다. 모듈 안 리스너가 없어서 `PaymentEvent.Declined`·`Unknown`과 `order`의 모듈 안 이벤트는 발행 기록도 Kafka 메시지도 남기지 않는다(`@ApplicationModuleListener`·`@TransactionalEventListener`를 main 코드에서 찾아 없음을 확인했다). `AttemptCreated`는 `contract`에 없지만 `payment.attempt-created.DLT`와 발행 기록이 있어 #91의 재투입 대상이라 넣는다.

**fixture 하나가 두 저장 형식을 함께 대표한다.** Kafka 값과 `serialized_event`는 같은 Boot `JsonMapper`가 쓴다. Modulith Kafka 모듈은 `RecordMessageConverter`가 없으면 `ByteArrayJacksonJsonMessageConverter(mapper.getIfUnique(...))`를 등록하고 값 직렬화기를 `ByteArraySerializer`·`ByteArrayDeserializer`로 둔다(`KafkaJacksonConfiguration`, `kafka-json.properties`). 발행 기록의 `JacksonEventSerializer`도 같은 `JsonMapper` 빈을 받는다(`JacksonEventSerializationConfiguration`). 둘 다 `spring-modulith-events-kafka`·`-jackson` 2.1.1 소스로 확인했다. 테스트는 두 경로를 모두 실제 빈으로 읽고 써서, 둘이 갈라지면 실패한다.

**호환 방향.** 하위 호환(옛 메시지를 지금 코드가 읽는다. DLT 재투입, 배포 뒤 재전송)은 테스트로 고정한다. 상위 호환(새 메시지를 옛 코드가 읽는다. 롤링 배포, 롤백)은 규칙으로 적고, 그 전제인 "지금 consumer는 모르는 필드를 무시한다"만 테스트로 고정한다.

**Jackson의 실제 동작(소스로 확인).** Boot 4.1.1은 `spring.jackson.use-jackson2-defaults`가 꺼져 있으면 Jackson 3 기본값을 그대로 쓴다(`JacksonAutoConfiguration`). 이 저장소는 그 설정을 두지 않는다. jackson-databind 3.1.7 기본값은 아래와 같다.
- `FAIL_ON_UNKNOWN_PROPERTIES`는 꺼짐이다. 모르는 필드는 무시된다. 그래서 필드 삭제는 읽기가 성공한다.
- `READ_UNKNOWN_ENUM_VALUES_AS_NULL`은 꺼짐이다. 모르는 enum 값은 바인딩이 실패한다.
- Kotlin 모듈은 JSON에 없는 생성자 인자가 기본값이 있거나 nullable이면 그 값으로 채우고, 둘 다 아니면 실패한다(테스트로 확인한다).
- 이름을 바꾼 필드에 기본값이나 nullable이 있으면 옛 메시지는 실패하지 않고 옛 값이 조용히 기본값으로 바뀐다. 이 경우를 막는 것이 이 PR의 핵심이다.

**변경 규칙.** 정본은 새 wiki 페이지 `docs/wiki/event-contract.md`다.

| 변경 | 하위 호환(옛 메시지 → 지금 코드) | 상위 호환(새 메시지 → 옛 pod) | 규칙 |
|---|---|---|---|
| 기본값이 있거나 nullable인 필드 추가 | 됨 | 됨(모르는 필드 무시) | 허용. 새 fixture를 더한다 |
| 기본값 없는 non-null 필드 추가 | 안 됨 | 됨 | 금지. 기본값이나 nullable로 더하거나 `schemaVersion`을 올린다 |
| 필드 삭제 | 됨(무시) | 안 됨(옛 필드가 non-null이면 실패, nullable·기본값이면 옛 pod가 값을 잃음) | 금지. 테스트가 막는다. 쓰지 않는 필드로 남긴다 |
| 필드 이름 변경 | 조용한 값 손실 또는 실패 | 안 됨(삭제와 같음) | 금지. 테스트가 막는다. 새 필드를 더하고 옛 필드를 계속 채운다 |
| nullable을 non-null로 좁히기 | 안 됨(옛 `null` 메시지가 실패) | 됨 | 금지 |
| `init` 불변식 강화 | 옛 메시지가 실패할 수 있음 | 됨 | 옛 fixture로 확인하고, 실패하면 `schemaVersion`을 올린다 |
| 타입 변경(JSON 표현이 바뀌는 것) | 안 됨 | 안 됨 | `schemaVersion`을 올리고 변환을 둔다 |
| enum 값 추가 | 됨 | 안 됨(옛 pod 바인딩 실패 → DLT) | 허용. 롤링 배포 중 옛 pod가 DLT로 보낸 메시지는 배포 뒤 재투입한다 |
| enum 값 제거·이름 변경 | 안 됨 | 됨 | `schemaVersion`을 올리고 변환을 둔다 |
| 클래스 이동·이름 변경 | Kafka는 무관(파라미터 타입으로 읽음), 발행 기록은 안 됨(`event_type`) | 같음 | 미완료 발행 기록이 0건일 때만(기존 domain-model §4.3 조건) |
| 토픽 이름 변경 | 안 됨 | 안 됨 | 금지(저장 형식) |

**빠진 필드는 "모름"이다(multi-review 반영, 2026-10-20 합의).** 버전을 올리지 않는 필드 추가에는 가드가 걸리지 않는다. 그래서 새 필드가 빠진 메시지가 두 곳에서 온다. 하나는 필드를 더하기 전에 저장된 옛 메시지(DLT, 발행 기록)다. 다른 하나는 롤링 배포 중 옛 pod가 다시 보낸 메시지다. 옛 pod의 재전송은 발행 기록을 옛 클래스로 읽어 다시 직렬화하므로 새 필드를 버린다(Codex·Claude 리뷰 지적). 두 메시지는 모양이 같으므로 consumer가 그 모양을 옳게 처리하게 한다.
- 새 필드가 빠졌을 때의 값(기본값·`null`)은 "모름"이다. 실제 업무 값을 기본값으로 두지 않는다. `kind`를 나중에 기본값 `AUTHORIZATION`으로 더했다면 옛 취소 승인을 결제 승인으로 읽었을 것이다.
- consumer는 "모름"이면 필드를 더하기 전처럼 동작하거나 자기 데이터에서 값을 찾는다.
- 필드를 더하는 PR은 이전 번호 fixture(그 필드가 없는 메시지)로 consumer 테스트를 더한다.
- 빠지면 옳게 처리할 수 없는 필드면 `schemaVersion`을 올린다. 조용히 틀리는 대신 DLT로 가고, 배포 뒤 재투입한다.
- 이 규칙을 지키는지는 리뷰가 본다. 계약 테스트는 역직렬화까지만 증명한다.
- 버린 대안은 둘이다.
  - 형식이 바뀔 때마다 버전을 올린다: 옛 DLT 메시지는 어차피 필드가 없어 consumer 처리는 여전히 필요하다. 그런데도 필드를 더하는 배포마다 옛 pod가 새 메시지를 DLT로 보낸다.
  - 재전송이 저장된 JSON을 그대로 보낸다: Modulith externalizer나 `EventSerializer`를 갈아끼우는 `platform` 변경이라 별도 PR이다.

**`schemaVersion`.** 넷 모두 마지막 생성자 인자로 `val schemaVersion: Int = SCHEMA_VERSION`을 더하고, 이벤트마다 `companion object`에 `const val SCHEMA_VERSION = 1`을 둔다. 원문 PRD의 이벤트 예시(`val schemaVersion: Int = 1`)와 같은 모양이고, 원문 시스템 설계 §3.3의 "event version은 topic 이름보다 envelope `schemaVersion`과 호환성 규칙을 우선한다"를 따른다. `foundation.DomainEvent`에는 넣지 않는다. 저장되지 않는 이벤트까지 떠안게 되기 때문이다. 버전은 **호환되지 않는 변경일 때만** 올린다. 필드 추가 같은 호환 변경에 올리면, 롤링 배포 중 옛 pod가 읽을 수 있는 메시지까지 아래 가드가 DLT로 보낸다. 그래서 fixture 번호(형식이 바뀔 때마다 는다)와 `schemaVersion`(변환이 필요할 때만 는다)은 따로 움직인다.

**가드.** 이벤트의 `init`이 받은 버전이 `SCHEMA_VERSION`보다 크면 `DomainException(UNSUPPORTED_EVENT_SCHEMA_VERSION, …, UNPROCESSABLE)`을 던진다. Jackson이 생성자를 부르므로 Kafka 변환기와 `EventSerializer` 두 경로에 모두 걸린다.
- Kafka: 변환 실패는 재시도 허용 목록 밖이라 바로 DLT로 간다. `KafkaConfiguration`이 원인 체인에서 `DomainException`을 찾아 `event_kafka_dead_letter_total{reason="UNSUPPORTED_EVENT_SCHEMA_VERSION"}`으로 센다. 그래서 운영자는 "새 버전을 배포한 뒤 재투입할 메시지"를 다른 실패와 구분한다.
- 발행 기록 재전송: `schemaVersion`을 올린 변경에서, 옛 pod가 새 버전 기록을 옛 클래스 모양으로 다시 써서 새 필드를 잃은 채 Kafka로 보내는 일을 막는다. 버전을 올리지 않는 필드 추가는 막지 못한다(위 "모름" 규칙).
- 재전송의 대가: 롤링 배포 동안 옛 pod의 재전송 실행이 그 행에서 끊긴다(spring-modulith#1904, domain-model §4.3).
  - 옛 pod가 재전송 표시를 먼저 커밋하므로 그 행은 `RESUBMITTED`로 남는다. 5분(`stale-after`) 뒤 `FAILED`로 돌아와야 새 pod가 보낸다.
  - 그 실행에서 뒤에 올 행과 정리 단계는 다음 주기로 밀린다.
  - 롤백하면 모든 pod가 옛 버전이라 새 버전 기록은 나가지 못하고 5분마다 실행을 끊는다. 이런 행을 찾는 SQL을 wiki에 적는다.
- 검사 헬퍼와 오류 code 상수는 `contract`에 하나 둔다. 쓰는 곳이 넷이고 `payment`는 이미 `contract`를 의존한다. main과 열린 PR에 같은 헬퍼가 없음을 확인했다.
- 변환(upcast)은 만들지 않는다. 지금 모든 이벤트가 버전 1이라 바꿀 대상이 없다. 호환되지 않는 변경을 하는 PR이 버전을 올리고, 그 이벤트의 Jackson 생성자(JSON 트리를 받아 옛 모양을 새 모양으로 바꾸는 위임 생성자)에 변환을 둔다. 두 경로가 같은 `JsonMapper`를 쓰니 한 곳에 두면 둘 다 걸린다. 변환은 `schemaVersion`을 현재 값으로 채우고, `schemaVersion`이 없는 JSON은 1로 본다. 기본값이 현재 상수라 그대로 두면 최신 버전으로 읽힌다. 롤링 배포 중 DLT를 피하는 두 단계 배포도 있다. 먼저 새 버전을 읽기만 하고 옛 버전을 쓰는 배포를 하고, 다음 배포에서 새 버전을 쓴다. 이 방식에는 "읽는 최대 버전"과 "쓰는 버전" 상수가 따로 필요하다. 어느 쪽으로 할지는 첫 변환 PR이 정한다. 이 절차를 wiki에 적는다.

**golden fixture.**
- 위치는 `src/test/resources/event-contract/토픽/번호.json`이다. 토픽 이름은 저장 형식이고 이벤트마다 하나이며, DLT도 같은 이름에서 나온다. 번호는 1부터 형식이 바뀔 때마다 늘린다.
- 이벤트마다 fixture 1은 이 PR에서 고정 인스턴스를 직렬화한 결과다. 머지된 fixture는 고치지 않고, 형식이 바뀌면 새 번호의 파일을 더한다.
- 읽기 검사: 이벤트마다 모든 fixture를 Kafka 변환기(`RecordMessageConverter.toMessage`, 파라미터 타입 추론 경로)와 `EventSerializer.deserialize`로 읽는다. 두 결과가 같아야 한다. 지금 `schemaVersion`과 같은 버전의 fixture는 읽었다가 다시 쓴 JSON에 fixture의 모든 필드가 같은 값으로 남아야 한다. 삭제·이름 변경·표현이 바뀌는 타입 변경을 이 조건이 막는다. 옛 버전 fixture의 기대값은 변환을 더하는 PR이 정한다.
- 쓰기 검사: 고정 인스턴스를 두 경로(`EventSerializer.serialize`, `RecordMessageConverter.fromMessage`)로 직렬화한 JSON이 마지막 fixture와 같아야 한다. 필드 변경이나 Jackson·Boot 업그레이드로 형식이 바뀌면 PR에서 드러난다.
- 상위 호환 전제: 마지막 fixture에 모르는 필드를 하나 더한 메시지도 두 경로가 읽고 고정 인스턴스와 같다.
- 새 이벤트 강제: 클래스패스에서 `com.resilientpos` 아래 `@Externalized` 타입을 모두 찾아 계약 테스트의 목록과 같은지 본다. 목록의 이벤트마다 fixture가 하나 이상 있고 마지막 fixture의 `schemaVersion`이 `SCHEMA_VERSION`과 같아야 한다. #96처럼 이벤트를 더하는 PR은 fixture와 목록 한 줄을 더하지 않으면 실패한다.
- fixture 번호는 1부터 빈틈없이 이어져야 한다. 테스트가 검사한다. 머지된 fixture를 고치지 않는다는 규칙은 git 이력이 있어야 알 수 있어 테스트로 강제할 수 없다. 그래서 리뷰가 `git diff --name-status origin/main...HEAD -- src/test/resources/event-contract/`에 `A`만 있는지 본다.
- Kafka 변환기로 읽을 때 레코드에 다른 클래스 이름의 `__TypeId__` 헤더를 단다. 읽기가 헤더가 아니라 파라미터 타입을 쓴다(`INFERRED`)는 것을 고정한다. 클래스 이동이 Kafka와 상관없다는 규칙과 헤더를 보존하는 재투입(#91)의 근거다.
- **Jackson 동작 고정(multi-review 반영).** 규칙 표가 기대는 동작을 두 경로에서 테스트로 고정한다. 빠진 nullable은 `null`, 빠진 `schemaVersion`은 1이다. 빠진 non-null(객체 `orderId`, 원시 `checkoutNo`)과 모르는 enum 값은 실패한다.
- **머지된 enum 이름과 null 값(multi-review 반영).** fixture는 이벤트마다 한 값만 담아서 `CANCELLATION`이나 `providerReference = null`을 덮지 않는다. 그래서 머지된 enum 이름을 리터럴 목록(`AUTHORIZATION`, `CANCELLATION`)으로 둔다. 그 이름과 null 값으로 바꾼 JSON을 두 경로가 읽고 같은 이름으로 돌려주는지 본다. 이름을 지우거나 바꾸면 실패하고, 추가는 목록에 더한다.
- 고정 인스턴스는 Fixture Monkey로 만들지 않는다. golden fixture는 모든 값이 고정이어야 한다.

**계약.** 새 공개 상수는 이벤트 넷의 `SCHEMA_VERSION`과 오류 code `UNSUPPORTED_EVENT_SCHEMA_VERSION`이다. 새 필드는 이벤트 넷의 `schemaVersion`이다. 새 enum 값, 새 토픽, 새 consumer group, 새 설정 키, 스키마 변경은 없다. `event_publication`의 `event_type`·`listener_id`도 그대로다. 배포 환경이 없어 `schemaVersion`이 없는 메시지는 아직 저장된 적이 없다. 그래도 필드에 기본값이 있어서, 그런 메시지는 버전 1로 읽힌다.

**실패·복구·관측성.** 가드가 거절한 메시지는 기존 DLT 경로를 그대로 탄다. 새 metric은 없고 기존 counter의 `reason`에 새 값이 하나 생긴다. 재투입은 domain-model §4.3의 절차와 같다.

**Kafka·Modulith 공식 문서 대조.**
- Spring Kafka의 Serialization, Deserialization, and Message Conversion 문서를 확인했다. "When you use a `@KafkaListener`, the parameter type is provided to the message converter to assist with the conversion"이고, 기본 `TypePrecedence`는 `INFERRED`(헤더가 아니라 파라미터 타입)다.
- spring-kafka 4.1.1 `JacksonJsonMessageConverter` 소스도 확인했다. 보낼 때 `typeMapper.fromClass`가 클래스 이름 타입 헤더를 붙이지만, 읽을 때는 `INFERRED`라 파라미터 타입을 쓴다. 그래서 헤더가 빠지는 console producer 재투입도 같은 타입으로 읽힌다(domain-model §4.3, 2026-09-26 확인).
- Spring Modulith 이벤트 문서는 발행 기록이 Boot의 매퍼로 Jackson 직렬화를 한다는 것까지만 말하고, Kafka 직렬화와 스키마 진화는 다루지 않는다. 그 부분은 위 소스 확인이 근거다.
- 기동 로그(`./gradlew test -i`, kafka-clients 4.2.2)의 `ProducerConfig values`는 `value.serializer = class org.apache.kafka.common.serialization.ByteArraySerializer`였다. 앱 consumer의 `ConsumerConfig values`는 `value.deserializer = class org.apache.kafka.common.serialization.ByteArrayDeserializer`였다. 같은 로그의 `StringDeserializer`는 테스트 도구 `KafkaTestSupport`의 읽기용 consumer다.

## 제외
- 변환(upcast) 코드와 그 구조. 버전 2가 생기는 PR이 만든다.
- 원문의 integration envelope(`eventType`·`producer`·`correlationId`·`causationId`·`data`)와 AsyncAPI·Schema Registry. 소비자가 모두 같은 앱이고 메시지 형식은 Jackson JSON이라는 기존 결정(Modulith·Kafka 키워드 8.20)을 바꾸지 않는다. 앱 밖 consumer가 생길 때 다시 본다.
- 모듈 안 이벤트(`Declined`·`Unknown`, `order` 모듈 안 이벤트)의 fixture와 버전 필드. 저장되지 않는다. 외부로 내보내는 PR에서 위 강제 테스트가 fixture를 요구한다.
- 상위 호환을 기계로 검사하는 것(새 fixture를 이전 릴리스 코드로 읽기). 배포 기준 버전이 아직 없다.
- DLT 재투입 API와 플랫폼 운영자 인증. #91이다.
- enum 값 추가 때 옛 pod가 DLT로 보내는 일을 막는 것(`READ_UNKNOWN_ENUM_VALUES_AS_NULL` 같은 설정). null로 읽으면 결제 종류를 모르는 승인을 주문이 처리하게 된다. 재투입으로 복구한다.
- `FAIL_ON_UNKNOWN_PROPERTIES`를 켜는 것. 이름 변경을 큰 실패로 바꾸지만, 필드 추가의 상위 호환(롤링 배포 중 옛 pod)을 잃는다.
- 머지된 fixture 수정을 기계로 막는 것(`check_pr.py` 검사). 하네스 변경이라 이 컨텍스트 밖이다. 위 리뷰 명령으로 본다.
- 가드와 발행 기록 재전송을 함께 도는 통합 테스트(`FAILED` 행과 `EventPublicationResubmitter`). 가드는 그 행을 역직렬화할 수 없게 만들 뿐이다. 그때의 재전송 동작(#1904)은 domain-model §4.3이 Modulith 소스로 확인해 두었고, 재전송 테스트 파일은 열린 #94가 고친다.
- 소수 통화 끝자리 0의 형식 고정. JSON 트리 비교에서 소수는 실수로 읽혀 `45.10`과 `45.1`이 같다. 금액 왕복은 `OrderEventPublicationIntegrationTest`의 `끝자리가 0인 소수 통화 금액도 자릿수를 잃지 않고 복원된다`가 맡는다.
- 새 reason counter를 미리 등록하는 것. `platform`이 `contract`를 참조하면 순환한다. 첫 DLT를 `increase()` 알림이 놓칠 수 있으므로 배포 뒤 DLT를 직접 확인한다고 wiki에 적는다.
- 이벤트별 테스트 보고(`@TestFactory`)와 배열 원소 단위의 값 보존 비교. 지금은 실패 메시지에 fixture 이름이 나오고, 배열을 가진 이벤트가 없다. 배열이 생기는 PR이 비교를 원소 단위로 바꾼다.

## 관련 문서
- [contract-module-for-cross-module-events](2026-09-22-contract-module-for-cross-module-events.md)(제외 절의 "이벤트 저장 형식의 버전 관리")
- [event-publication-registry](2026-09-21-event-publication-registry.md)(저장 형식 규칙의 출처)
- [kafka-event-redelivery](2026-09-26-kafka-event-redelivery.md)(DLT·재투입)
- [operator-authentication](2026-10-06-operator-authentication.md)(#91의 근거)

## 변경 경로
- src/main/kotlin/com/resilientpos/contract/**
- src/main/kotlin/com/resilientpos/payment/PaymentEvent.kt
- src/test/kotlin/com/resilientpos/contract/**
- src/test/resources/event-contract/**
- docs/spec/2026-10-20-event-contract-compatibility.md
- docs/wiki/event-contract.md
- docs/wiki/domain-model.md
- docs/wiki/README.md

## 인수 조건
1. 이벤트 넷이 `schemaVersion`(기본값 `SCHEMA_VERSION` = 1)을 가진다. 넷을 직렬화한 JSON에 `"schemaVersion":1`이 있다.
2. `schemaVersion`이 `SCHEMA_VERSION`보다 큰 JSON은 Kafka 변환기와 `EventSerializer` 모두에서 실패하고, 원인 체인에 code `UNSUPPORTED_EVENT_SCHEMA_VERSION`의 `DomainException`이 있다. 같은 버전은 읽힌다(1보다 작은 버전은 없다).
3. 그런 메시지를 `order.paid`에 넣으면 consumer가 재시도 없이 `order.paid.DLT`로 보내고, `event.kafka.dead-letter{topic="order.paid", reason="UNSUPPORTED_EVENT_SCHEMA_VERSION"}`이 1 늘며, 뒤 메시지는 계속 처리된다.
4. 이벤트마다 `src/test/resources/event-contract/토픽/1.json`이 있고, 읽기·쓰기 검사와 모르는 필드 검사가 통과한다.
5. fixture 1의 필드 하나를 지우거나 이름을 바꾼 사본으로 읽기 검사를 돌리면 실패한다(구현 중 임시로 확인하고 결과 절에 남긴다. 커밋하지 않는다).
6. 이벤트 필드를 하나 더하면 쓰기 검사가 실패한다(구현 중 임시로 확인).
7. 클래스패스의 `@Externalized` 타입 집합이 계약 목록과 다르면 강제 테스트가 실패한다. 지금은 넷으로 같다.
8. `docs/wiki/event-contract.md`에 변경 규칙 표, 빠진 필드("모름") 규칙과 필드를 더할 때의 체크리스트, `schemaVersion`을 올리는 절차와 롤백 영향, fixture 갱신 규칙, 새 이벤트 체크리스트가 있다. domain-model §4.3의 저장 형식 문단과 §4.7 질문 9가 이 페이지를 가리키고, wiki 인덱스에 한 줄이 있다.
9. Modulith·Kafka 키워드(볼트)가 그 문서의 갱신 규칙대로 갱신된다.
10. `./gradlew test` 전체와 `python3 scripts/check_pr.py --base origin/main`(`--worktree` 유무 둘 다)가 통과한다.
11. Jackson 동작 고정 테스트와 머지된 enum 이름·null 값 테스트가 두 경로에서 통과한다. fixture 번호에 빈틈이 있으면 테스트가 실패한다. 다른 클래스 이름의 `__TypeId__` 헤더가 있어도 Kafka 변환기가 파라미터 타입으로 읽는다.

### Checkpoint와 검증 명령
| Checkpoint | 검증 명령 | 의존 |
|---|---|---|
| 가드 (1~3) | `./gradlew test --tests 'com.resilientpos.contract.*'` | 없음 |
| fixture (4~7, 11) | `./gradlew test --tests 'com.resilientpos.contract.*'` | 가드 |
| 회귀 (10) | `./gradlew test` | 가드, fixture |

## 검증
- 정상: 넷의 fixture 1을 두 경로가 읽고 고정 인스턴스와 같다. 지금 직렬화가 fixture 1과 같다. 모르는 필드가 더해져도 읽는다.
- 실패: 높은 버전 → 두 경로 모두 code로 거절, Kafka에서는 DLT와 reason counter. 필드 삭제·이름 변경 사본 → 읽기 검사 실패. 필드 추가 → 쓰기 검사 실패. fixture 없는 `@Externalized` 타입 → 강제 테스트 실패.
- 기존 테스트: 이벤트를 만드는 테스트는 기본값 덕분에 바뀌지 않는다. 발행 기록의 JSON 문자열을 직접 비교하는 테스트가 있으면 `schemaVersion`을 반영한다.
- `./gradlew test`, `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전 `--worktree` 없이 한 번 더.

## Wiki 영향
- 새 [이벤트 계약](../wiki/event-contract.md): 변경 규칙의 정본. 규칙 표, Jackson 3 기본값 근거, `schemaVersion`과 가드, 버전을 올리는 절차(변환 위치), fixture 위치·갱신 규칙, 새 이벤트를 더할 때의 체크리스트(토픽 목록, fixture, 계약 목록, `SCHEMA_VERSION`), DLT 재투입 전 확인할 것.
- [도메인 모델](../wiki/domain-model.md) §4.3: "이벤트 클래스의 모양이 저장 형식이다" 문단의 "하위 호환인 변경은 기본값이 있는 필드 추가뿐이다"가 실제보다 좁고, 이름 변경의 조용한 값 손실을 말하지 않는다. 요약과 링크로 바꾼다. 새 이벤트를 더하는 문장에 fixture를 덧붙인다. §4.7 질문 9에 링크를 단다.
- [wiki 인덱스](../wiki/README.md): 이벤트 계약 페이지 진입점 한 줄.
- Modulith·Kafka 키워드(`docs/wiki/modulith-kafka-keywords.md`, 볼트, 저장소 diff 없음): 키워드(`schemaVersion`과 가드, golden fixture, 모르는 필드·enum의 Jackson 3 기본값, 타입 헤더와 `INFERRED`)와 8절 결정(버전 전략, 가드 위치, 삭제·이름 변경 금지)을 갱신 규칙 형식으로 더하고 출처에 이 spec을 단다.
- 요구사항 영향: 원문 시스템 설계 §3.3(호환 규칙, `schemaVersion` 우선)과 개발 가이드의 `schemaVersion` 요구를 외부 전달 이벤트에 한해 충족한다. envelope·correlation은 제외 절대로 남는다.

## 분할 계획
- 이 PR은 "외부 전달 이벤트의 저장 형식 호환" 컨텍스트 하나다. DLT 재투입 API(#91)는 이 결과를 쓰는 다른 컨텍스트라 뒤에 온다.
- 겹침 확인(2026-10-20, origin/main 9c34aaf). 열린 PR은 #94(`refactor/modulith-2-1-2-resubmit`) 하나다.
  - #94는 `docs/wiki/domain-model.md` §4.3의 Modulith 버전 올리기 절차 5번과 재전송 문단을 고친다. 이 PR이 고칠 저장 형식 문단과는 다른 줄이라(사이에 바뀌지 않는 줄이 있다) 겹치지만 충돌은 작다. 먼저 머지된 쪽에 맞춰 rebase한다.
  - 진행 중인 #96(`feature/payment-reconciliation-case`)과 #79(`fix/contention-error-mapping`)는 아직 push되지 않았다. #96이 `contract`에 이벤트를 더하면 강제 테스트가 fixture를 요구한다. 먼저 머지된 쪽에 맞춰 rebase하고, #96이 먼저면 그 이벤트의 fixture와 `schemaVersion`을 이 PR에서 더한다.
  - push·PR 생성·머지 직전에 같은 확인을 다시 하고 결과를 여기에 덧붙인다.
  - #94는 이 PR이 기대는 재전송 서술(재전송 루프가 역직렬화 실패에 실행 전체를 끊는다, #1904)이 있는 문단도 고친다. #94가 먼저 머지되면 rebase할 때 그 서술이 그대로인지 다시 대조한다.

## TDD 검증 기록
`contract`의 이벤트 불변식(가드)과 consumer 경로의 동작이라 TDD로 한다. Controller·도메인 aggregate 변경은 없다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| `아는 버전보다 높은 schemaVersion은 Kafka 변환기와 발행 기록 복원 모두 도메인 코드로 거절한다` | `./gradlew test --tests 'com.resilientpos.contract.*'`: 필드와 상수만 선언하고 가드가 없어 `OrderPaymentRequested: 높은 버전을 읽었다. Expected an exception to be thrown, but was completed successfully`(`schemaVersion=2`로 읽힘) | 같은 명령, 가드(`requireKnownSchemaVersion`)를 넣고 통과 | 헬퍼 추출 뒤 같은 명령 6개 통과 |
| `아는 버전보다 높은 메시지는 DLT로 가고 도메인 코드로 세며 뒤 메시지는 처리된다` | TDD 순서 예외: 가드를 구현한 뒤에 쓴 테스트라 RED를 먼저 보지 못했다. 사후에 가드 조건을 꺼서 실패를 확인했다(mutation 검증): `20초 안에 충족되지 않았다: DLT에 들어간다`(높은 버전이 그대로 처리됨) | 가드를 되돌리고 통과 | 같은 명령 6개 통과 |
| `Externalized 이벤트마다 계약과 fixture가 있고 마지막 fixture가 지금 버전이다` | fixture 없이 실행: `order.payment-requested: fixture가 없다`. 클래스패스 스캔 집합은 목록 넷과 같아 그 단언은 통과 | fixture 1 넷을 더하고 통과 | 같은 명령 6개 통과 |
| `쌓인 fixture를 두 경로가 같게 읽고 지금 버전 fixture의 값을 잃지 않는다` | fixture가 없을 때는 검사할 것이 없어 통과(위 강제 검사가 fixture를 요구한다). fixture를 더한 뒤 임시 사본으로 확인: 이름 변경(`providerReference`를 `providerRef`로) `payment.approved/1.json: providerRef 이 사라졌다`, 삭제(fixture에 `legacyField` 추가) `order.paid/1.json: legacyField 이 사라졌다` | fixture 원본으로 통과 | 같은 명령 6개 통과 |
| `지금 직렬화가 두 경로 모두 마지막 fixture와 같다` | fixture 없이 `fixture가 없다`. fixture를 더한 뒤 임시로 `OrderPaid`에 `val note: String? = null`을 더해 확인: `order.paid/1.json와 형식이 다르다 … "note":null` | 필드를 되돌리고 통과 | 같은 명령 6개 통과 |
| `모르는 필드가 더해진 메시지도 두 경로가 읽는다` | fixture 없이 `fixture가 없다` | fixture를 더하고 통과(Jackson 3 기본값이라 구현 변경 없음, characterization) | 같은 명령 6개 통과 |
| `빠진 필드와 모르는 enum 값을 규칙 표대로 읽거나 거절한다` (multi-review 반영) | 미실행 | 미실행 | 미실행 |
| `머지된 enum 이름과 null 값을 두 경로가 읽는다` (multi-review 반영) | 미실행 | 미실행 | 미실행 |

## 결과
- **구현**
  - `contract.requireKnownSchemaVersion`과 `UNSUPPORTED_EVENT_SCHEMA_VERSION`을 더했다(`EventSchemaVersion.kt`).
  - 이벤트 넷에 `schemaVersion`, `SCHEMA_VERSION = 1`, `init` 가드를 더했다.
  - 계약 테스트 `EventContractCompatibilityTest`와 고정 인스턴스 목록 `EventContracts`를 만들고, fixture 1 넷을 지금 `EventSerializer` 출력으로 만들었다. 손으로 쓰지 않으려고 커밋하지 않는 임시 테스트로 파일을 썼다.
- **Kotlin 모듈 동작 실측**(앱 `JsonMapper`, 커밋하지 않은 임시 테스트, 2026-10-20)
  - 빠진 nullable 인자는 `null`, 빠진 기본값 인자는 기본값으로 채워졌다.
  - 빠진 non-null 인자는 `MismatchedInputException: Missing required creator property`로 실패했다.
  - 모르는 enum 값 `REFUND`는 `InvalidFormatException`으로 실패했다.
  - 모르는 필드는 무시됐다.
  - 범위 절의 서술과 같다.
- **가드의 원인 체인.** Kafka 변환기와 `EventSerializer` 모두에서 던진 예외의 원인 체인에 `DomainException`이 있었다. `KafkaConfiguration`이 그 code를 DLT reason으로 셌다(인수 조건 3 테스트).
- **인수 조건 3의 "재시도 없이".** 테스트가 보는 것은 DLT 도착, reason counter, 뒤 메시지 처리다. 재시도하지 않는 것은 `KafkaConfiguration`의 허용 목록(`defaultFalse`)에 변환 실패와 `DomainException`이 없어서다. 같은 분류는 기존 `KafkaRetryIntegrationTest`의 `역직렬화할 수 없는 값은 consumer를 멈추지 않고 DLT로 간다`가 고정한다.
- **구현 중 정한 것.** fixture는 `classpath*:` 패턴으로 찾는다. `classpath:`는 디렉터리가 없으면 `FileNotFoundException`으로 실패한다. 새 이벤트를 더하는 PR이 보는 메시지가 "fixture가 없다"가 되도록 바꿨다.
- **검증**
  - `./gradlew test --tests 'com.resilientpos.contract.*'`: 6개 통과.
  - `./gradlew test` 전체: 97개 클래스 596개 테스트, 실패 0(2분 37초).
  - `python3 scripts/check_pr.py --base origin/main --worktree`: PASS.
- **Wiki.** [이벤트 계약](../wiki/event-contract.md)을 새로 썼다. [도메인 모델](../wiki/domain-model.md) §4.3의 토픽 목록 문장과 저장 형식 문단, §4.7 질문 9, [wiki 인덱스](../wiki/README.md)를 고쳤다. Modulith·Kafka 키워드(볼트)에는 다음을 더했다.
  - 2절: `저장 형식` 항목의 날짜 기록, `Jackson 3의 역직렬화 기본값`, `schemaVersion과 버전 가드`, `golden fixture`.
  - 3절과 6절: 날짜 기록.
  - 7절: 쌍 둘.
  - 8절: 8.20 날짜 기록, 8.29~8.32.
  - 출처.
