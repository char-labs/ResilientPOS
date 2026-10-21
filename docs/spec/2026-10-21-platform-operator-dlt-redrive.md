# PR spec: 플랫폼 운영자를 사내 IdP 토큰으로 인증하고 DLT 재투입 API를 연다

## 목적
DLT 재투입은 지금 Kafka CLI 절차뿐이다([도메인 모델 §4.3](../wiki/domain-model.md#43-이벤트-전달과-저장-형식)). 누가 왜 넣었는지, 어떤 메시지를 이미 넣었는지 남지 않는다. DLT는 보존 기간이 없어 메시지가 지워지지 않으므로, 재투입 여부를 로그와 업무 상태로 짐작해야 한다. 여러 매장에 걸친 이 작업은 점주가 할 수 없다([operator-authentication](2026-10-06-operator-authentication.md) 결정 1). 그런데 그 일을 할 플랫폼 운영자 주체가 없다(이슈 #91).

기대 결과:
- 플랫폼 운영자(우리 조직 직원)는 사내 IdP가 발급한 Bearer 토큰으로 `/admin/**`를 부른다. 점주 세션·단말 토큰과 섞이지 않는다.
- 운영자는 DLT의 메시지를 목록으로 보고, 한 건을 사유와 함께 원 토픽에 다시 넣는다. 지금 코드가 읽지 못하는 메시지는 넣기 전에 거절된다.
- 재투입은 행위자·사유·넣은 자리와 함께 DB에 남는다. 같은 메시지를 다시 요청하면 다시 보내지 않고 처음 결과를 돌려준다.

## 범위
소유 모듈은 `platform` 하나다. `/admin` Security 체인, DLT 목록·재투입 API, 재투입 기록 표가 모두 여기에 있다. endpoint는 그 데이터를 소유한 모듈에 둔다는 지금 규칙을 따른다(점주의 단말 API는 `terminal`, 대조 해소는 `reconciliation`). 토픽·DLT 구성(`KafkaConfiguration`)과 발행 기록 재전송(`EventPublicationResubmitter`)이 이미 `platform`에 있고, 재투입은 업무 규칙 없이 바이트를 옮기는 인프라 작업이다. 나중에 생길 운영자 기능(예: 여러 매장 대조 해소)은 그 데이터를 소유한 모듈에 `/admin` endpoint로 둔다. 참고 문서의 근거는 시스템 설계의 "운영 API는 별도 role/scope 및 audit를 요구한다", PRD PR-05와 청사진의 "operator action에는 principal … 을 남긴다"다.

### 결정
**1. 플랫폼 운영자는 사내 IdP가 발급한 Bearer JWT로 인증한다.** 앱은 resource server로 토큰을 검증하기만 한다(`spring-boot-starter-oauth2-resource-server`).
- 점주를 앱 자체 계정으로 둔 근거는 "고객 계정에 IdP를 두면 IdP 운영과 매장 소속 동기화가 따라온다"였다. 운영자는 직원이라 조직이 이미 운영하는 IdP가 있고, 매장 소속이 없어 동기화할 것도 없다. 권한은 IdP의 그룹·역할 하나다.
- 계정 생성·비활성화(퇴사)·MFA는 IdP 정책이 맡는다. 그래서 운영자 표·비밀번호·로그인·실패 카운터·세션·CSRF를 만들지 않는다. MFA(#93)는 점주만 남는다.
- 실제 IdP(Google Workspace·Okta·Entra ID 등)는 배포 PR이 고른다. 이 PR은 표준 OIDC issuer와 audience만 가정한다.
- 버린 것: 점주와 같은 앱 자체 계정+세션은 같은 `__Host-SESSION` 쿠키를 쓴다. 그러면 두 체인 모두에 역할 검사가 필요하고(지금 점주 체인은 `authenticated`만 본다), 최고 권한 계정이 MFA 없이 남는다. 앱 자체 계정+개인 API 토큰은 표 두 개와 발급·회전·첫 토큰 부트스트랩이 따라오고 MFA가 없다.

**2. `/admin/**`는 전용 Security 체인 하나가 맡는다.** `platform.AdminSecurityFilterChain`은 점주 체인과 같은 컴포넌트 모양(`SecurityFilterChain` 위임)이다.
- `securityMatcher("/admin/**")`, `oauth2ResourceServer { jwt { } }`, 세션 정책은 stateless다. 쿠키를 읽지도 쓰지도 않으므로 CSRF를 끈다. 점주 체인과 단말 인터셉터는 바꾸지 않는다. 점주 세션 쿠키로 `/admin`을 부르면 Bearer가 없어 401이고, 운영자 토큰으로 `/owner`나 `/v1`을 부르면 그쪽 인증이 토큰을 모르므로 401이다.
- 권한은 `resilientpos-operator` 하나다. 모든 `/admin/**`에 이 권한이 필요하다. 권한을 어느 claim에서 읽을지는 IdP마다 달라서 Boot 속성(`authorities-claim-name`, `authority-prefix: ""`)으로 배포가 정한다. 코드에 IdP별 분기를 두지 않는다.
- issuer(`issuer-uri`)와 audience(`audiences`)는 둘 다 필수이고 기본값이 없다. 둘 중 하나라도 비면 체인이 생성을 거부해 앱이 뜨지 않는다. Boot에 맡기지 않는 이유는 Boot가 검사 없이 넘어가는 경로가 있어서다. issuer 없이 `jwk-set-uri`나 `public-key-location`만 주면 decoder가 만들어지지만 `iss`를 검사하지 않고, `audiences`가 비면 `aud`를 검사하지 않는다. 기동 실패로 두는 이유는 둘이다. 체인이 없으면 `/admin/**`가 어떤 체인에도 맞지 않아 인증 없이 Controller에 닿는다. audience를 검사하지 않으면 같은 IdP가 다른 앱용으로 발급한 토큰도 받는다(Spring Security는 기본으로 `aud`를 검사하지 않는다).
- 행위자는 토큰의 `sub`다(OIDC Core가 255자 이하로 정한다). 이메일·이름은 남기지 않는다. IdP가 `sub`로 사람을 찾는다.
- 오류는 점주 체인처럼 `handlerExceptionResolver`로 넘겨 `ApiControllerAdvice`가 envelope와 로그를 맡는다. 토큰이 없거나 틀리면(서명·만료·issuer·audience) `401 UNAUTHENTICATED`이고 `WWW-Authenticate: Bearer`를 붙인다(단말 401과 같다). 권한이 없으면 `403 ACCESS_DENIED`다. 토큰 값은 로그와 예외 메시지에 남기지 않는다.
- IdP에 닿지 못하면(discovery·JWK 조회 실패) `503 SERVICE_UNAVAILABLE` envelope다. Boot의 issuer 기반 decoder는 첫 요청 때 discovery를 하는 `SupplierJwtDecoder`라 기동이 IdP 가용성에 묶이지 않는다. 대신 그 초기화 실패(`JwtDecoderInitializationException`)는 `AuthenticationException`이 아닌 `RuntimeException`이라, 그대로 두면 Security 필터 밖으로 나가 Tomcat HTML 500이 된다. 점주의 세션 저장소 장애(`OwnerSessionStoreFilter`)와 같은 모양의 `/admin/*` 서블릿 필터(`AdminIdentityProviderFilter`)를 Security 필터 앞에 두고 503 envelope로 바꾼다. 첫 검증의 decoder 초기화(issuer discovery와 JWK Set 첫 조회) 실패가 `JwtDecoderInitializationException`이고, 초기화 뒤 JWK Set 재조회(캐시에 없는 kid, 키 회전) 실패는 `AuthenticationServiceException`으로 와서 resource server의 실패 처리기가 다시 던진다. 필터는 둘 다 잡는다. 503 예외는 브로커 장애에도 쓰는 `DependencyUnavailableException`이다. 대가로 Nimbus가 서명 처리 중 내는 `JOSEException` 같은 토큰 처리 오류도 401이 아니라 503이 된다. 어느 쪽이든 거절이다.
- `/admin` 경로의 네트워크 접근 제한(ingress allowlist·VPN)은 배포 확인 항목이다. 앱은 IP로 거르지 않는다.

**3. DLT 목록: `GET /admin/v1/dead-letters?topic=&partition=&fromOffset=&limit=`.**
- `topic`은 설정된 DLT 토픽(`resilientpos.kafka.topics`의 각 이름 + `.DLT`)만 받는다. 원 토픽, 내부 토픽(`__consumer_offsets` 등), 모르는 이름은 `400 INVALID_DEAD_LETTER_POSITION`이다. 운영자 API로 임의 토픽을 읽지 못하게 한다. 없는 partition, 음수 offset, `limit`이 1~50 밖이어도 같은 400이다. `limit` 기본값은 50이다.
- `fromOffset`부터 `limit`건 또는 그 partition의 끝까지 읽는다. 끝 이상이면 빈 목록이다. 다음 쪽은 마지막 `offset + 1`로 부른다.
- 읽기는 `ConsumerFactory`의 consumer를 assign·seek로 쓰고 group 없이 offset을 커밋하지 않는다. 그래서 어떤 consumer group의 위치도 바뀌지 않는다. 끝 offset(`endOffsets`)을 먼저 읽어 그 범위만 poll한다. `KafkaTemplate.receive`는 쓰지 않는다. 레코드마다 따로 poll해서, 없는 offset마다 poll 시간 초과를 끝까지 기다리기 때문이다(spring-kafka 4.1.1 소스).
- 항목: `partition`, `offset`, `timestamp`, `key`, `value`(UTF-8 문자열), `originalTopic`·`originalPartition`·`originalOffset`(헤더 `kafka_dlt-original-*`), `exception`(리스너가 던진 예외 클래스. `kafka_dlt-exception-cause-fqcn`이고 없으면 `kafka_dlt-exception-fqcn`이다. spring-kafka는 `ListenerExecutionFailedException`만 벗기므로 `JpaSystemException` 안의 SQL 예외 같은 더 깊은 원인은 실리지 않는다), `exceptionMessage`(`kafka_dlt-exception-message`, "포장 예외 메시지; 리스너 예외 메시지" 모양이다. 예: `Listener failed; 이미 처리된 주문입니다`), `schemaVersion`(값 JSON의 필드, 없거나 JSON이 아니면 null), `redrive`(재투입 기록이 있으면 넣은 `partition`·`offset`·`operator`·`redrivenAt`, 없으면 null). 스택 트레이스 헤더는 싣지 않는다. 레코드마다 수 KB라 50건이면 응답이 커진다.
- 값에는 매장·단말·주문 ID와 금액이 들어 있다. 고객 개인정보는 이벤트에 없다. 운영자는 이미 CLI로 같은 값을 본다.

**4. DLT 재투입: `POST /admin/v1/dead-letters/{topic}/{partition}/{offset}/redrive`, 본문 `{reason}`.**
- 순서는 확인 → 읽기 검사 → 전송 → 기록이다.
  1. 그 좌표의 재투입 기록이 있으면 다시 보내지 않고 처음 기록을 `200`으로 돌려준다. 사유가 달라도 처음 기록을 바꾸지 않는다.
  2. DLT에서 그 레코드를 읽는다. 위치가 틀리면 3의 400이고, 그 offset에 레코드가 없으면 `404 DEAD_LETTER_NOT_FOUND`다.
  3. 원 토픽(헤더 `kafka_dlt-original-topic`, DLT 이름에서 `.DLT`를 뺀 것과 같아야 한다)의 이벤트 타입으로 값을 읽어 본다. 실패하면 `422 DEAD_LETTER_NOT_READABLE`(메시지에 원인 예외 클래스와 도메인 코드)이고 전송도 기록도 하지 않는다.
  4. 같은 key와 같은 값 바이트를 원 토픽에 보내고 ack를 기다린다(상한 10초). 트랜잭션 밖이다. 다시 직렬화하지 않는다. 파티션은 정하지 않는다. producer가 key로 고르므로 파티션 수가 같으면 원래 자리로 간다. DLT의 헤더는 옮기지 않는다. consumer는 타입 헤더를 쓰지 않고, 옮기면 다시 실패했을 때 DLT 헤더가 겹친다.
  5. `Tx.masterTx`에서 기록을 넣는다. unique 위반(같은 좌표를 동시에 요청해 둘 다 보낸 경우)이면 이긴 쪽 기록을 읽어 `200`으로 돌려준다.
- 응답은 `dltTopic`, `dltPartition`, `dltOffset`, `topic`(원 토픽), `partition`, `offset`(넣은 자리), `operator`, `redrivenAt`이다.
- 사유는 필수이고 앞뒤 공백을 지운 뒤 1~500자다. NUL 문자(U+0000)는 PostgreSQL varchar에 들어가지 못해 커밋에서 500이 되므로 거절한다. 틀리면 `400 INVALID_REDRIVE_REASON`이다(대조 case `note`와 같은 규칙).
- 같은 좌표를 동시에 두 번 요청하면 둘 다 보낼 수 있다. 기록은 unique가 하나로 묶는다. 전송은 됐는데 기록 커밋이 실패해도(DB 장애) 다시 요청하면 한 번 더 보낸다. 두 경우 모두 consumer가 멱등이라 업무 결과는 하나다. 기록을 먼저 넣고 전송 뒤 커밋하는 방식(선점)은 버렸다. 넣은 자리 컬럼을 nullable로 두어야 하고, Kafka ack를 기다리는 동안 DB 커넥션과 행 잠금을 쥐며, 커밋 실패 때 중복 전송은 그대로 남는다.
- 브로커에 닿지 못하면(끝 offset 조회·poll·전송 시간 초과) `503 SERVICE_UNAVAILABLE`이고 기록하지 않는다. 시간 초과 뒤에 전송이 실제로 성공했을 수 있다. 다시 요청하면 중복 전송이고 위와 같이 consumer가 견딘다.
- 원인을 고치지 않고 넣은 메시지는 다시 DLT로 간다. 새 DLT 레코드의 `kafka_dlt-original-offset`이 재투입 기록의 넣은 `offset`과 같아서 목록에서 이어 볼 수 있다. 새 레코드는 새 좌표라 다시 재투입할 수 있다.

**5. 읽기 검사는 consumer와 같은 변환이다.** event-contract 8절의 두 경우(아는 것보다 높은 `schemaVersion`, 모르는 enum 값)를 API가 막는다.
- 토픽에서 타입을 찾는 표는 기동할 때 클래스패스의 `@Externalized` 타입을 훑어 만든다(대상 문자열의 `::` 앞이 토픽). `platform`이 업무 모듈 타입을 컴파일 의존하지 않는다. 의존하면 `payment → platform`과 순환한다(`KafkaConfiguration` KDoc과 같은 이유).
- 변환은 Boot의 `JsonMapper` 빈으로 한다. Kafka 변환기(`ByteArrayJacksonJsonMessageConverter`)와 같은 빈이다([이벤트 계약](../wiki/event-contract.md) 2절). 버전 가드는 이벤트 `init`의 `requireKnownSchemaVersion`이라 생성자 호출로 함께 걸린다.
- 한계: 검사는 요청을 받은 pod의 코드로 한다. 롤링 배포 중이면 아직 옛 pod인 consumer가 읽지 못해 다시 DLT로 갈 수 있다. "그 버전을 아는 코드가 모든 pod에 배포된 뒤 넣는다"는 운영자가 확인한다.
- 이 표가 비어 있거나 설정된 토픽 하나라도 타입을 못 찾으면 기동이 실패한다. 토픽 목록과 `@Externalized`가 어긋난 것이기 때문이다.

**6. 재투입 기록은 `t_dead_letter_redrive` 표다.** 감사의 정본이자 멱등 키다.
- 컬럼: `id`(UUID v7), `dlt_topic`, `dlt_partition`, `dlt_offset`, `target_partition`, `target_offset`, `operator_subject`, `reason`, `BaseEntity` 공통 컬럼. 재투입 시각은 `created_at`이다. 원 토픽은 `dlt_topic`에서 나오므로 두지 않는다.
- unique `ux_dead_letter_redrive_source(dlt_topic, dlt_partition, dlt_offset)`가 "한 DLT 레코드의 기록은 하나"의 유일한 방어선이다. 목록의 기록 조회(같은 토픽·partition의 offset 범위)도 이 인덱스를 탄다.
- 기록은 바꾸거나 지우지 않는다. 엔티티에 변경 메서드가 없다.

**7. 운영자 재결제 입구는 점주의 것으로 정하고, 이 PR에서는 열지 않는다.** 재결제는 한 매장의 고객 결제를 provider에 다시 요청하는 일이라 매장 범위 주체의 몫이다. 플랫폼 운영자가 매장을 넘나들며 고객 결제를 일으킬 업무 이유가 없다. 점주 세션과 `StoreManagement.requireOwned`가 이미 있고, `PaymentManagement.retry`도 `storeId`를 받는다. 관리 화면 같은 요구가 생기면 점주 경로(`/owner/v1/stores/{storeId}/payment-attempts/{paymentId}/retry`)로 연다(제외). [payment-status-query](2026-10-06-payment-status-query.md)와 [operator-authentication](2026-10-06-operator-authentication.md)의 제외 절이 남긴 결정이다.

**8. 감사와 관측.**
- 처음 재투입한 뒤 INFO 한 줄을 남긴다: `audit action=DEAD_LETTER_REDRIVEN operator=… dltTopic=… dltPartition=… dltOffset=… topic=… partition=… offset=…`. 사유 원문은 남기지 않는다. 자유 입력이고 DB 기록이 정본이다(대조 case 해소와 같은 기준). 같은 좌표의 재요청에는 남지 않아 줄 수가 곧 재투입 건수다.
- counter `event.kafka.dead-letter.redriven{topic}`(Prometheus `event_kafka_dead_letter_redriven_total`)을 처음 재투입할 때만 올린다. `topic`은 원 토픽이다. 기동 때 토픽별 0으로 등록한다(DLT counter와 같은 이유).
- 400·401·403·404·422는 advice의 기존 로그(WARN, 404는 INFO), 503은 ERROR다. 목록 조회는 앱 로그를 남기지 않는다. 다만 요청마다 consumer를 만들므로 Kafka 클라이언트가 INFO(`ConsumerConfig values` 블록, partition 배정·seek, 종료)를 남긴다. 운영자가 드물게 부르는 조회라 로그 레벨을 바꾸지 않는다. 바꾸면 기동 때 실제 설정값을 확인하는 `ConsumerConfig values` 로그도 함께 사라진다.
- 한계: consumer를 닫을 때(`close`) fetch session 정리로 최대 30초를 기다릴 수 있다. 레코드를 받은 뒤 브로커가 끊기면 응답이 5초 상한보다 그만큼 늦는다(코드의 `ponytail:` 표식). 브로커 호출의 503은 Kafka `TimeoutException`만이고, 권한·인증 같은 다른 `KafkaException`은 500이다.

### 계약 요약
- **새 HTTP endpoint**: `GET /admin/v1/dead-letters`, `POST /admin/v1/dead-letters/{topic}/{partition}/{offset}/redrive`.
- **새 오류 code**: `INVALID_DEAD_LETTER_POSITION`(400), `DEAD_LETTER_NOT_FOUND`(404), `DEAD_LETTER_NOT_READABLE`(422), `INVALID_REDRIVE_REASON`(400). 401·403·503은 기존 `ErrorType`이다.
- **새 설정**: `spring.security.oauth2.resourceserver.jwt.issuer-uri`·`audiences`(필수, 환경 변수 `OPERATOR_ISSUER_URI`·`OPERATOR_AUDIENCE`), `authorities-claim-name`(환경 변수 `OPERATOR_AUTHORITIES_CLAIM`, 기본 `roles`), `authority-prefix: ""`.
- **새 의존성**: `spring-boot-starter-oauth2-resource-server`(Boot BOM 버전).
- **스키마**: 새 표 하나(스키마 변경 검토).
- **바뀌지 않는 것**: 이벤트 타입·토픽·consumer group·DLT 구성, 점주 체인, 단말 인증, 공개 Facade.

### 불변식
- `/admin/**`는 issuer·audience·서명·만료가 맞고 `resilientpos-operator` 권한이 있는 토큰 없이 부를 수 없다. IdP 설정이 없으면 앱이 뜨지 않는다.
- 설정된 DLT 토픽만 읽고, 원 토픽에만 넣는다.
- 한 DLT 레코드의 재투입 기록은 하나다. 기록이 있으면 넣은 메시지가 브로커에 ack된 것이다.
- 지금 코드가 읽지 못하는 메시지는 API로 넣지 않는다.

### 복구
- 재투입 응답을 잃으면 같은 요청을 다시 보낸다. 기록이 있으면 같은 응답이고, 없으면(전송 전 실패) 그때 보낸다.
- 422는 아무것도 바꾸지 않는다. 그 버전·값을 아는 코드를 모든 pod에 배포한 뒤 다시 요청한다.
- 503은 기록하지 않는다. 브로커가 돌아온 뒤 다시 요청한다.
- 앱이 내려가 API를 쓸 수 없으면 [도메인 모델 §4.3](../wiki/domain-model.md#43-이벤트-전달과-저장-형식)의 CLI 절차를 쓴다. 이때는 기록이 남지 않으므로 사유를 이슈에 남긴다.

### 공식 문서와 소스 대조
- [Spring Security resource server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html): 최소 설정은 `iss`·`exp`·`nbf`만 검사하고 `aud`는 Boot의 `audiences` 속성으로 켠다. issuer 기반 discovery는 첫 요청 때 하고 "startup is not coupled to the authorization server's availability"이며 `SupplierJwtDecoder`가 맡는다. 기본 권한은 `scope`·`scp`에 `SCOPE_` 접두어이고 claim 이름과 접두어를 바꿀 수 있다. clock skew 기본값은 60초다.
- Boot 4.1.1 `spring-boot-security-oauth2-resource-server` jar: 설정 메타데이터에 `issuer-uri`·`audiences`·`authorities-claim-name`·`authority-prefix`·`public-key-location`이 있다. `JwtDecoderConfiguration`은 issuer만 있으면 `SupplierJwtDecoder`를 만들고, `audiences`가 비지 않았을 때만 `aud` 검사기를 더한다. `public-key-location` decoder는 issuer·jwk-set-uri가 없을 때만 만들어진다(`KeyValueCondition`, 구현 중 관측). 그래서 테스트는 운영과 같은 issuer decoder를 쓰고, `JwkSetUriJwtDecoderBuilderCustomizer`로 decoder의 HTTP 호출(discovery·JWK Set)만 메모리 응답으로 바꾼다. 서명·만료·issuer·audience 검사가 네트워크 없이 운영 구성 그대로 돈다.
- spring-security-oauth2-jose 7.1.1(Boot 4.1.1 BOM이 고른 버전): `SupplierJwtDecoder`는 초기화 실패를 `JwtDecoderInitializationException`(`RuntimeException` 하위, `JwtException` 아님)으로 감싼다. issuer decoder는 build 단계에서 JWK Set을 읽어 서명 알고리즘을 정하고, JWK 소스는 Nimbus 캐시(rate limit 꺼짐)라 캐시에 없는 kid가 오면 바로 다시 조회한다(구현 중 바이트코드 확인).
- spring-security-oauth2-resource-server·web 7.1.1: `JwtAuthenticationProvider`는 `BadJwtException`(401)이 아닌 `JwtException`을 `AuthenticationServiceException`으로 바꾼다. `BearerTokenAuthenticationFilter`의 기본 실패 처리기 `AuthenticationEntryPointFailureHandler`는 그 예외를 다시 던지는 것이 기본값이고(`rethrowAuthenticationServiceException`), configurer는 entry point만 바꾼다. 그래서 결정 2의 503 필터가 두 예외를 잡는다. Security 필터의 순서 상수는 Boot 4.1.1 `SecurityFilterProperties.DEFAULT_FILTER_ORDER`(-100)다.
- [Spring Kafka 오류 처리](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html): `DeadLetterPublishingRecoverer`가 붙이는 헤더 목록(원 토픽·partition·offset·timestamp·consumer group, 예외 클래스·원인 클래스·메시지·스택). 문자열 값은 spring-kafka 4.1.1 `KafkaHeaders`(`kafka_dlt-original-topic`, `kafka_dlt-original-partition`, `kafka_dlt-original-offset`, `kafka_dlt-exception-cause-fqcn`, `kafka_dlt-exception-fqcn`, `kafka_dlt-exception-message`)로 확인했다. 이 문서는 DLT 재생을 다루지 않는다.
- [Spring Kafka `KafkaTemplate` receive](https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/template-receive.html): 작업마다 consumer를 만들고 닫으며, 여러 offset은 하나씩 가져온다. 4.1.1 소스에서 없는 offset이면 poll 시간 초과까지 기다린다. 그래서 목록은 consumer로 끝 offset까지만 읽는다.
- producer 내구성(`acks=all`, `enable.idempotence=true`)은 [kafka-event-redelivery](2026-09-26-kafka-event-redelivery.md) 공식 문서 점검의 기동 로그 값 그대로다. 재투입 전송도 같은 producer 설정을 쓴다. 구현에서 템플릿의 key·value 직렬화기를 기동 로그 `ProducerConfig values`로 확인하고 결과 절에 적는다.

## 제외
- 일괄 재투입. 운영자가 단건을 반복 호출한다. 결정 3·4.
- 여러 매장 대조 case 일괄 해소([payment-reconciliation-case](2026-10-20-payment-reconciliation-case.md) 제외 절). 그 기능이 생기면 `reconciliation` 모듈에 `/admin` endpoint로 둔다.
- 운영자 재결제 입구. 점주의 것으로 정했고 요구가 생기면 점주 경로로 연다. 결정 7.
- 운영 콘솔 화면, 로컬 개발용 토큰 발급기(mock IdP). compose의 앱은 issuer·audience 자리값으로 뜨고 `/admin`은 쓸 수 없다. 동작은 테스트가 검증한다.
- 실제 IdP 선택, 권한 claim 이름, `/admin` 네트워크 접근 제한. 배포 PR이 정한다.
- 운영자 권한 세분화(읽기 전용과 재투입 분리), `/admin` rate limit(#87과 함께).
- DLT 메시지의 폐기 표시, 미처리 DLT 건수 metric. 재투입 기록과 DLT 끝 offset으로 셀 수 있지만 지금 읽는 코드가 없다.
- 재투입 기록의 보존 기간.

## 관련 문서
없음. 이 PR이 따르는 장기 스펙 문서(DESIGN·concerns·flows)가 없다. 앞 PR은 [operator-authentication](2026-10-06-operator-authentication.md), [kafka-event-redelivery](2026-09-26-kafka-event-redelivery.md), [event-contract-compatibility](2026-10-20-event-contract-compatibility.md)다.

## 변경 경로
- build.gradle.kts
- docker/docker-compose.yaml
- src/main/kotlin/com/resilientpos/platform/**
- src/main/java/com/resilientpos/platform/**
- src/main/resources/application.yaml
- src/main/resources/db/migration/V019__dead_letter_redrive.sql
- src/test/kotlin/com/resilientpos/**
- src/test/resources/**
- docs/wiki/domain-model.md
- docs/wiki/event-contract.md
- docs/wiki/observability.md
- docs/wiki/architecture.md
- docs/wiki/entity-map.md
- docs/spec/2026-10-21-platform-operator-dlt-redrive.md

## 인수 조건
1. issuer·audience·서명·만료가 맞고 `resilientpos-operator` 권한이 있는 토큰으로 두 `/admin` endpoint를 부를 수 있다. 토큰이 없거나 서명·만료·issuer·audience 중 하나가 틀리면 `401 UNAUTHENTICATED`이고 `WWW-Authenticate: Bearer`가 붙는다. 권한이 없으면 `403 ACCESS_DENIED`다. 응답에 `Set-Cookie`가 없다.
2. 점주 세션 쿠키로 `/admin`을 부르면 401, 운영자 토큰으로 `/owner/v1/stores`와 `/v1/orders`를 부르면 401, 단말 토큰으로 `/admin`을 부르면 401이다. `/owner/**`·`/v1/**`·`/actuator/health`의 기존 동작은 바뀌지 않는다.
3. issuer나 audience 설정이 없으면 애플리케이션 컨텍스트가 뜨지 않는다.
4. IdP에 닿지 못하면 `/admin` 요청은 envelope의 `503 SERVICE_UNAVAILABLE`이고 HTML이 아니다.
5. 목록은 설정된 DLT 토픽·partition의 `fromOffset`부터 `limit`건까지 결정 3의 항목으로 돌려준다. 재투입한 레코드는 `redrive`가 채워진다. 끝 이상이면 빈 목록이다. 설정에 없는 토픽(원 토픽·`__consumer_offsets` 포함), 없는 partition, 음수 offset, 1~50 밖의 `limit`은 `400 INVALID_DEAD_LETTER_POSITION`이다. 목록을 읽어도 어떤 consumer group의 커밋된 offset도 바뀌지 않는다.
6. DLT로 간 `order.paid` 메시지를 재투입하면 같은 key·값이 `order.paid`에 들어가 fulfillment가 생기고, 응답에 넣은 자리와 `operator`(토큰 `sub`)가 실리며, 기록 표에 행 하나와 사유가 남고, `event_kafka_dead_letter_redriven_total{topic="order.paid"}`가 1 오르며, 감사 INFO 한 줄이 남고 그 줄에 사유가 없다.
7. 같은 좌표를 다시 재투입하면 같은 응답이 `200`이고, 원 토픽에 메시지가 늘지 않으며, counter와 감사 줄도 늘지 않는다.
8. `schemaVersion`이 아는 것보다 큰 메시지와 모르는 enum 값이 든 메시지의 재투입은 `422 DEAD_LETTER_NOT_READABLE`이고, 원 토픽에 메시지가 늘지 않으며, 기록도 남지 않는다.
9. 없는 offset은 `404 DEAD_LETTER_NOT_FOUND`, 빈 사유·500자 초과·NUL 문자가 든 사유는 `400 INVALID_REDRIVE_REASON`이다. 둘 다 전송도 기록도 하지 않는다.
10. 기록 저장이 unique에 걸리면(같은 좌표 경합) 이긴 쪽 기록을 `200`으로 돌려준다.
11. 브로커에 닿지 못하면 목록과 재투입은 `503 SERVICE_UNAVAILABLE`이고 기록이 남지 않는다.
12. V019 뒤 `t_dead_letter_redrive`가 있고 `(dlt_topic, dlt_partition, dlt_offset)`이 unique다.
13. REST Docs 스니펫이 두 endpoint의 요청·응답 필드와 400·401·403·404·422 예를 문서화한다.
14. `ModuleStructureTest`·`DependencyDirectionTest`·`TransactionDeclarationTest`를 포함한 기존 테스트가 모두 통과한다.

### Checkpoint와 검증 명령 (선택)
| Checkpoint | 검증 명령 | 의존 |
|---|---|---|
| `/admin` 체인과 IdP 설정 (1~4) | `./gradlew test --tests 'com.resilientpos.platform.Admin*'` | 없음 |
| DLT 목록 (5·11) | `./gradlew test --tests 'com.resilientpos.platform.deadletter.*'` | `/admin` 체인 |
| 재투입·기록·검사 (6~10·12) | `./gradlew test --tests 'com.resilientpos.platform.deadletter.*' --tests 'com.resilientpos.SchemaMigrationTest'` | DLT 목록 |
| REST Docs·회귀 (13·14) | `./gradlew test` | 전부 |

## 스키마 변경 검토
`V019__dead_letter_redrive.sql`이 새 표 하나와 unique 제약 하나를 더한다.

```sql
create table t_dead_letter_redrive (
    id uuid primary key,
    dlt_topic varchar(249) not null,
    dlt_partition integer not null,
    dlt_offset bigint not null,
    target_partition integer not null,
    target_offset bigint not null,
    operator_subject varchar(255) not null,
    reason varchar(500) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz,
    constraint ux_dead_letter_redrive_source unique (dlt_topic, dlt_partition, dlt_offset)
);
```

- 질문 1(도출): 재투입 여부·행위자·사유·넣은 자리는 어디에도 없다. Kafka는 DLT 레코드를 읽거나 다시 보낸 사실을 남기지 않고, 로그는 보존 기간이 있으며 정본이 아니다. 원 토픽(`dlt_topic`에서 `.DLT`를 뺀 값)과 재투입 시각(`created_at`)은 도출할 수 있어 컬럼을 두지 않는다.
- 질문 2(지금 읽는 코드): 재투입은 좌표로 기존 기록을 찾아 멱등 응답을 만들고, 목록은 범위로 찾아 `redrive`를 채운다. `target_partition`·`target_offset`은 응답과 목록이 읽는다. 다시 DLT로 간 메시지(`kafka_dlt-original-offset`)를 이 재투입에 잇는 유일한 값이다. `operator_subject`·`reason`은 감사 정본이다(참고 문서의 operator action principal 요구).
- 질문 3(나중에 채우기): 재투입 시점에만 아는 값이라 나중에 채울 수 없다.
- 질문 4(마지막 방어선): unique `(dlt_topic, dlt_partition, dlt_offset)`가 "한 DLT 레코드의 기록은 하나"와 멱등 응답의 유일한 방어선이다. Kafka offset은 정본이 될 수 없다는 규칙과 맞다. Kafka는 좌표만 주고 기록의 유일성은 PostgreSQL이 지킨다. 기록을 지우는 명령이 없고 멱등 키라 soft delete된 행에도 걸리게 둔다(partial이 아니다).
- 질문 5(정본): 재투입 기록의 정본은 이 표 하나다. 감사 INFO 로그는 사유가 없는 관측용 사본이고 커밋 뒤 남긴다. 로그가 빠져도 표가 맞다.
- 질문 6(check-then-act): 확인(기록 조회) 뒤 전송·기록이다. 경합은 unique가 막고, 진 쪽은 위반을 잡아 이긴 쪽 기록을 돌려준다. 전송은 트랜잭션 밖이라 경합한 두 요청이 둘 다 보낼 수 있고, consumer 멱등이 그 중복을 견딘다(결정 4). 기다리는 수단(row lock·`Lock`)은 두지 않는다. 낙관적 잠금도 필요 없다. 행을 고치는 경로가 없다.
- 질문 7(락·크기): 새 표 생성이라 다른 표를 잠그지 않는다. 기존 행이 없어 위반 행 검사가 필요 없고, 잡을 락이 없어 `lock_timeout`을 두지 않는다.
- 질문 8(이전 버전 호환): 이전 버전 애플리케이션은 이 표를 모르고 쓰지 않는다. `validate`는 매핑된 엔티티만 보므로 이전 버전이 새 스키마에서 뜬다.
- 질문 9(이벤트·캐시): 이벤트 payload나 캐시 DTO에 실리지 않는다. 재투입은 DLT의 값 바이트를 그대로 옮기므로 이벤트 형식을 바꾸지 않는다.
- `dlt_topic varchar(249)`는 Kafka 토픽 이름의 최대 길이이고 `operator_subject varchar(255)`는 OIDC Core가 정한 `sub`의 최대 길이다. 사유 500자는 대조 case `note`와 같다.
- 대조 기록: 미작성. PR 제출 전에 구현자가 아닌 리뷰어가 마이그레이션·엔티티·코드와 대조해 채운다.

## 검증
- 인수 1~14: `./gradlew test`. Docker가 필요하다(PostgreSQL·Redis·Kafka 컨테이너).
- 인수 1·2: 테스트 JVM마다 만드는 RSA 키로 서명한 JWT를 보낸다. decoder는 운영과 같은 issuer decoder이고 discovery·JWK 응답만 테스트 설정(`OperatorTokens.IdpStub`)이 준다. 권한 claim은 `roles`. 키 파일을 커밋하지 않는다.
- 인수 3: 설정을 빼고 컨텍스트를 띄워 실패를 단언한다.
- 인수 4: issuer를 닿지 않는 주소로 둔 컨텍스트에서 형식이 맞는 토큰으로 부른다.
- 인수 6~8: 기존 Kafka 통합 테스트처럼 DLT에 레코드를 직접 넣거나(헤더 포함) 실패를 주입해 보낸 뒤 API를 부른다.
- 정상: 목록 → 재투입 → 업무 결과 → 같은 좌표 재요청이 같은 응답.
- 실패: 토큰 없음·틀린 audience·권한 없음, 설정에 없는 토픽, 없는 offset, 읽지 못하는 메시지, 빈 사유, 브로커 없음.
- 동작을 지워 테스트가 빨개지는지 본다: audience 검사, 토픽 허용 목록, 읽기 검사, 기존 기록 확인.
- `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전 `--worktree` 없이 한 번 더.

## Wiki 영향
- [도메인 모델](../wiki/domain-model.md): 용어집 `operator` 행(주체가 생겼다, 사내 IdP 토큰, `/admin`, 권한 하나). §4.3 "DLT 재투입" 항목을 API 절차로 바꾸고 CLI는 앱을 쓸 수 없을 때의 대안으로 남긴다(기록이 남지 않는다). §4.7 사례에 `t_dead_letter_redrive`.
- [이벤트 계약](../wiki/event-contract.md) 8절: API가 읽기 검사로 막는 것(높은 `schemaVersion`, 모르는 enum)과 운영자가 여전히 확인할 것(모든 pod 배포)을 적는다.
- [관측성](../wiki/observability.md): 운영자 감사(`DEAD_LETTER_REDRIVEN`)와 재투입 counter, 노출 범위에 `/admin`(Bearer, 네트워크 제한은 배포), IdP 장애 503. #76(관측 마무리)과 겹치는 문서라 항목 추가만 하고, #76의 runbook이 이 절차를 링크한다.
- [아키텍처](../wiki/architecture.md): 인증 수단이 셋(단말 인터셉터, 점주 세션 체인, 운영자 토큰 체인)이 된 것과 `platform`의 책임에 `/admin` 체인·DLT 재투입을 더한다.
- [엔티티 맵](../wiki/entity-map.md): `t_dead_letter_redrive`.
- 볼트 문서(저장소 밖): 현재 상태(운영자 주체, 재투입 API, 의존성), Modulith·Kafka 키워드(갱신 규칙대로 DLT 재투입 항목).
- 요구사항 변화: 플랫폼 운영자 주체와 재투입 감사가 생긴다. 원문의 operator action audit 요구를 DLT 재투입에 대해 채운다. 업무 정합성 요구는 그대로다.

## 분할 계획
한 PR, 한 컨텍스트(플랫폼 운영자 인증과 그 위의 DLT 재투입)다. 운영자 재결제 입구와 여러 매장 대조 해소는 결정만 하거나 제외했다.

경로 겹침(2026-10-21, origin/main `c7810af`):
- 열린 PR #94(Modulith 2.1.2 대기 draft)와 `build.gradle.kts`(의존성 줄 추가), `docs/wiki/domain-model.md`(§4.3의 다른 항목)가 겹친다. 먼저 머지된 쪽에 맞춰 rebase한다.
- 진행 중인 #76(관측 마무리, 아직 push 전)과 `docs/wiki/observability.md`가 겹칠 수 있다. 이 PR은 항목 추가만 하고 DLT 재투입 절차는 이 PR이 맡는다(합의).
- 구현 중(2026-10-21) 열린 PR #102(inbound rate limit, draft)와 `ApiControllerAdvice.kt`·`PlatformConfiguration.kt`·`application.yaml`·`docs/wiki/architecture.md`·`docs/wiki/observability.md`가 겹친다. 둘 다 항목 추가라 충돌은 줄 단위다. #102는 Controller 슬라이스 테스트들도 고치므로, 먼저 머지되면 이 PR의 `DeadLetterControllerTest`·`DeadLetterRedrive` 슬라이스도 같은 준비가 필요한지 rebase 때 본다.
- 공용 헬퍼: `/admin`의 503 필터는 `OwnerSessionStoreFilter`와 모양이 같지만 잡는 예외와 경로가 달라 합치지 않는다. main과 열린 PR에 운영자 인증·DLT 읽기 코드는 없다.

## TDD 검증 기록
`/admin` 체인과 Controller·재투입 동작은 TDD로 한다. 개발 전 테스트 의도이고 실행 뒤 채운다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 1·2: `AdminSecurityIntegrationTest` 5개(`issuer audience 서명 만료가 맞고 운영자 권한이 있는 토큰은 체인을 통과해 없는 경로의 404 envelope가 되고 Set-Cookie가 없다`, `토큰이 없거나 서명 만료 issuer audience 중 하나가 틀리면 401 UNAUTHENTICATED와 WWW-Authenticate Bearer이고 Set-Cookie가 없다`(토큰 없음·같은 kid 다른 키·만료·다른 issuer·다른 audience), `운영자 권한이 없는 토큰은 403 ACCESS_DENIED이고 Set-Cookie가 없다`, `점주 세션 쿠키나 단말 토큰으로 admin을 부르면 401이다`, `운영자 토큰으로 owner와 v1을 부르면 401이다`) | 의존성·설정·테스트만 두고 체인은 컴포넌트가 아닌 상수 선언으로 둔 상태에서 `./gradlew test --tests 'com.resilientpos.platform.Admin*'` — 8개 중 5개 실패(403 기대 404, 토큰 없음 401 기대 404, 점주 쿠키 401 기대 404, 아래 기동 실패 2개). 유효 토큰의 404와 owner·v1의 401은 체인 없이도 통과한 characterization이다 | 체인 구현 뒤 같은 명령 8개 통과. 테스트 `audiences`에 다른 값을 더하면 다른 audience 사례가 401 대신 404로 실패해 aud 검사기가 401을 만든다는 것을 확인하고 되돌렸다 | `./gradlew test --tests 'com.resilientpos.platform.*' --tests 'com.resilientpos.owner.*' --tests 'com.resilientpos.terminal.*'` 230개 통과, `./gradlew test` 721개 통과 |
| 인수 3: `AdminSecurityConfigurationTest` 4개(`issuer와 audience가 있으면 운영자 체인이 만들어진다`(대조군), `issuer가 비면 체인이 거부해 컨텍스트가 뜨지 않는다`, `issuer 없이 jwk-set-uri만 있으면 issuer 검사가 빠지므로 컨텍스트가 뜨지 않는다`, `audience가 비면 체인이 거부해 컨텍스트가 뜨지 않는다`) | 위와 같은 명령. issuer·audience 누락 2개는 `issuer 없이 컨텍스트가 떴다`·`audience 없이 컨텍스트가 떴다`로 실패. jwk-set-uri 사례는 첫 GREEN 뒤 리뷰에서 더했고, Boot에 맡긴 issuer 필수(JwtDecoder 없음)로는 4개 중 이것만 `issuer 없이 컨텍스트가 떴다`로 실패했다 | 체인에 issuer `check`를 더한 뒤 `./gradlew test --tests 'com.resilientpos.platform.Admin*'` 9개 통과 | 아래 다음 동작의 회귀에 포함 |
| 인수 4: `AdminIdentityProviderFilterTest` 5개(두 예외를 `DependencyUnavailableException`으로 resolver에 넘김, 응답이 커밋됐으면 다시 던짐, 무관한 예외는 통과, 등록은 admin 경로이고 Security 필터보다 앞), `AdminIdentityProviderOutageIntegrationTest`의 `issuer discovery에 실패하면 admin 요청은 HTML이 아니라 503 SERVICE_UNAVAILABLE envelope이고 ERROR 로그에 토큰이 없다`, `AdminJwkOutageIntegrationTest`의 `decoder 초기화 뒤 JWK Set 재조회가 실패하면 admin 요청은 503 SERVICE_UNAVAILABLE envelope이고 토큰은 출력에 없다` | 예외 클래스·통과만 하는 필터·경로 없는 등록만 둔 상태에서 `./gradlew test --tests 'com.resilientpos.platform.Admin*'` — 16개 중 5개 실패. 필터 단위 2개는 예외가 그대로 전파, 등록은 `expected [/admin/*] but was []`, 통합 2개는 `JwtDecoderInitializationException`(원인 `UnknownHostException: operator-idp.test`)과 `AuthenticationServiceException`(JWK 500)이 MockMvc 밖으로 샜다. 필터만 두고 advice handler가 없을 때는 통합 2개가 500 `INTERNAL_ERROR`로 실패했다 | 같은 명령 16개 통과 | `./gradlew test --tests 'com.resilientpos.platform.*'` 133개 통과, `./gradlew test` 729개 통과 |
| 인수 5·11(목록): `DeadLetterListIntegrationTest` 4개(`설정된 DLT의 fromOffset부터 limit건을 원 위치 예외 schemaVersion과 함께 돌려주고 없는 헤더는 null이며 스택 트레이스는 싣지 않는다`, `fromOffset이 partition 끝 이상이면 빈 목록이다`, `설정에 없는 토픽 없는 partition 음수 offset 1에서 50 밖의 limit은 400 INVALID_DEAD_LETTER_POSITION이다`, `목록을 읽어도 consumer group이 늘지 않고 어떤 group도 DLT에 offset을 커밋하지 않는다`), `DeadLetterReaderTest`의 `브로커에 닿지 못해 Kafka 시간 초과가 나면 DependencyUnavailableException이고 consumer를 닫는다`. 첫 레코드는 실제 `DeadLetterPublishingRecoverer.accept`로 넣어 헤더 형식을 실물과 대조했다 | 매핑 없는 Controller·빈 목록 reader·DTO만 둔 상태에서 `./gradlew test --tests 'com.resilientpos.platform.deadletter.*'` — 12개 중 10개 실패(슬라이스·통합은 `Status expected 200·400·503 but was 404`, 단위는 예외 대신 빈 목록) | 같은 명령 12개 통과. 토픽 허용 목록을 지우면 원 토픽 400 사례가 200으로, reader가 group으로 `commitSync`하게 바꾸면 offset 테스트가 `{dlt-reader-mutation=[order.paid.DLT-0]}`로 실패하는 것을 보고 되돌렸다 | `./gradlew test --tests 'com.resilientpos.platform.*'` 145개 통과, `./gradlew test` 741개 통과 |
| 인수 6·7·10: 재투입·기록·멱등·counter·감사 | 미실행 | 미실행 | 미실행 |
| 인수 8·9: 읽기 검사 422, 없는 offset 404, 사유 400 | 미실행 | 미실행 | 미실행 |
| 인수 13(목록): `DeadLetterControllerTest` 7개(200과 항목·없는 헤더 null, 생략 시 0과 50, 400 `INVALID_DEAD_LETTER_POSITION`, 필수 파라미터 없으면 400이고 reader 미호출, 503, 401과 `WWW-Authenticate: Bearer`, 403). 스니펫 `dead-letters-list`·`-list-invalid-position`·`-list-unavailable`·`dead-letters-unauthenticated`·`dead-letters-access-denied` | 위 RED에 포함. 401·403은 Controller 없이도 통과한 characterization(운영자 체인) | 위 GREEN에 포함. `build/generated-snippets`에 다섯 디렉터리 확인 | 위와 같다 |
| 인수 13(재투입) | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
