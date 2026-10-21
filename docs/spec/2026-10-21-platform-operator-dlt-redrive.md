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
- IdP HTTP 호출(discovery·JWK Set)은 connect 2초·read 3초 상한이다. Spring Security 기본값(각 30초)이면 IdP가 응답을 멈췄을 때 decoder 초기화 잠금 뒤에 다른 `/admin` 요청이 줄을 서서 요청 스레드를 수십 초씩 쥔다. `JwkSetUriJwtDecoderBuilderCustomizer`로 바꿔 Boot의 issuer·audience 검사기는 그대로 둔다.
- 503 필터는 응답 본문만 비워(`resetBuffer`) Security가 쓴 응답 헤더를 남긴다. 필터 순서는 `SecurityFilterProperties`(설정 `spring.security.filter.order`)에서 읽어 Security 필터 바로 앞에 둔다.
- `/admin` 경로의 네트워크 접근 제한(ingress allowlist·VPN)은 배포 확인 항목이다. 앱은 IP로 거르지 않는다. 캐시에 없는 kid의 토큰은 인증 없이도 JWK 재조회를 일으키므로(rate limit 꺼짐) 이 제한과 rate limit(#87·#102)이 그 경로를 막는다.
- IdP 배포 확인 항목: 권한 claim은 사용자에게 할당한 role·group이어야 하고 scope가 아니다(scope는 클라이언트가 요청하는 값이라 그 클라이언트를 쓰는 직원이면 누구나 운영자가 된다). audience는 이 API의 식별자이고 콘솔의 client_id가 아니다(그러면 ID 토큰이 Bearer로 통과한다). issuer는 https다. access token 수명을 짧게 둔다(퇴사 반영). Kafka ACL을 쓰는 운영이면 앱 principal에 `*.DLT` Read·Describe를 더한다.

**3. DLT 목록: `GET /admin/v1/dead-letters?topic=&partition=&fromOffset=&limit=`.**
- `topic`은 설정된 DLT 토픽(`resilientpos.kafka.topics`의 각 이름 + `.DLT`)만 받는다. 원 토픽, 내부 토픽(`__consumer_offsets` 등), 모르는 이름은 `400 INVALID_DEAD_LETTER_POSITION`이다. 운영자 API로 임의 토픽을 읽지 못하게 한다. 없는 partition, 음수 offset, `limit`이 1~50 밖이어도 같은 400이다. `limit` 기본값은 50이다.
- `fromOffset`부터 `limit`건 또는 그 partition의 끝까지 읽는다. 끝 이상이면 빈 목록이다. 다음 쪽은 마지막 `offset + 1`로 부른다.
- 읽기는 `ConsumerFactory`의 consumer를 assign·seek로 쓰고 group 없이 offset을 커밋하지 않는다. 그래서 어떤 consumer group의 위치도 바뀌지 않는다. 끝 offset(`endOffsets`)을 먼저 읽어 그 범위만 poll한다. `KafkaTemplate.receive`는 쓰지 않는다. 레코드마다 따로 poll해서, 없는 offset마다 poll 시간 초과를 끝까지 기다리기 때문이다(spring-kafka 4.1.1 소스).
- 항목: `partition`, `offset`, `timestamp`, `key`, `value`(UTF-8 문자열), `originalTopic`·`originalPartition`·`originalOffset`(헤더 `kafka_dlt-original-*`), `exception`(리스너가 던진 예외 클래스. `kafka_dlt-exception-cause-fqcn`이고 없으면 `kafka_dlt-exception-fqcn`이다. spring-kafka는 `ListenerExecutionFailedException`만 벗기므로 `JpaSystemException` 안의 SQL 예외 같은 더 깊은 원인은 실리지 않는다), `exceptionMessage`(`kafka_dlt-exception-message`, "포장 예외 메시지; 리스너 예외 메시지" 모양이다. 예: `Listener failed; 이미 처리된 주문입니다`), `schemaVersion`(값 JSON의 필드, 없거나 JSON이 아니면 null), `redrive`(재투입 기록이 있으면 넣은 `partition`·`offset`·`operator`·`redrivenAt`, 없으면 null). 스택 트레이스 헤더는 싣지 않는다. 레코드마다 수 KB라 50건이면 응답이 커진다.
- 값에는 매장·단말·주문 ID와 금액이 들어 있다. 고객 개인정보는 이벤트에 없다. 운영자는 이미 CLI로 같은 값을 본다.

**4. DLT 재투입: `POST /admin/v1/dead-letters/{topic}/{partition}/{offset}/redrive`, 본문 `{reason}`.**
- 순서는 읽기 → 확인 → 읽기 검사 → 전송 → 기록이다(아래 범위 조정에서 확인을 읽기 뒤로 옮겼다). 그 앞에 사유와 운영자(`sub`)를 검사한다(아래). 사유가 틀린 재요청은 기존 기록이 있어도 400이다.
  1. DLT에서 그 레코드를 읽는다. 위치가 틀리면 3의 400이고(DB보다 먼저 검사한다), 그 offset에 레코드가 없으면 `404 DEAD_LETTER_NOT_FOUND`다. 요청한 offset이 log 시작보다 앞이거나 빈 자리면 다른 레코드를 돌려주지 않고 404다.
  2. 그 레코드(좌표와 레코드 시각)의 재투입 기록이 있으면 다시 보내지 않고 처음 기록을 `200`으로 돌려준다. 사유가 달라도 처음 기록을 바꾸지 않는다. 브로커에 닿지 못하면 기록이 있어도 503이다.
  3. 원 토픽의 이벤트 타입으로 값을 읽어 본다. 원 토픽은 DLT 이름에서 `.DLT`를 뺀 것이다(`KafkaConfiguration` 목적지 함수의 역). 헤더 `kafka_dlt-original-topic`으로 정하지 않는다. 허용 목록이 DLT 이름을 이미 검증했고, 헤더가 없는 레코드(CLI로 넣은 것)도 같은 규칙으로 간다. 실패하면 `422 DEAD_LETTER_NOT_READABLE`이고 전송도 기록도 하지 않는다. 문구에는 원인 사슬의 도메인 코드(예: `UNSUPPORTED_EVENT_SCHEMA_VERSION`)나, 없으면 Jackson 예외의 단순 클래스 이름(예: `InvalidFormatException`) 하나만 싣는다. Jackson 메시지는 입력 조각을 담으므로 응답과 로그에 넣지 않는다. 값이 없거나(tombstone) JSON `null`이어도 422다("값이 없습니다").
  4. 같은 key와 같은 값 바이트를 원 토픽에 보내고 ack를 기다린다(상한 10초). 트랜잭션 밖이다. 다시 직렬화하지 않는다. 파티션은 정하지 않는다. producer가 key로 고르므로 파티션 수가 같으면 원래 자리로 간다. DLT의 헤더는 옮기지 않는다. consumer는 타입 헤더를 쓰지 않고, 옮기면 다시 실패했을 때 DLT 헤더가 겹친다.
  5. `Tx.masterTx`에서 기록을 넣는다. unique 위반(같은 레코드를 동시에 요청해 둘 다 보낸 경우)이면 이긴 쪽 기록을 읽어 `200`으로 돌려준다.
- 응답은 `dltTopic`, `dltPartition`, `dltOffset`, `topic`(원 토픽), `partition`, `offset`(넣은 자리), `operator`, `redrivenAt`이다.
- 사유는 필수이고 앞뒤 공백을 지운 뒤 1~500자다. NUL 문자(U+0000)는 PostgreSQL varchar에 들어가지 못해 커밋에서 500이 되므로 거절한다. 틀리면 `400 INVALID_REDRIVE_REASON`이다(대조 case `note`와 같은 규칙). 본문에 `reason`이 없으면 빈 사유로 보고 같은 400이다. 저장하는 값은 공백을 지운 값이다. 길이는 UTF-16 단위로 세어 varchar(코드 포인트)보다 엄격하다.
- 운영자는 검증된 토큰의 `sub`다. resource server는 `sub`를 검사하지 않으므로 보내기 전에 1~255자이고 제어 문자가 없는지 본다. 없거나 틀린 `sub`(IdP 설정 오류)는 보내지도 기록하지도 않고 500이다. 전송 뒤 커밋에서 실패해 재요청마다 다시 보내는 일과 감사 로그의 줄 위조를 막는다.
- 같은 레코드를 동시에 두 번 요청하면 둘 다 보낼 수 있다. 기록은 unique가 하나로 묶는다. 전송은 됐는데 기록 커밋이 실패해도(DB 장애) 다시 요청하면 한 번 더 보낸다. 두 경우 모두 consumer가 멱등이라 업무 결과는 하나다. 기록이 남지 않는 전송의 행위자는 WARN 감사 줄이 남긴다(결정 8).
- 재투입은 원 토픽의 모든 consumer group에 다시 전달된다. 지금은 토픽마다 consumer group이 하나라 실패한 group만 다시 받는다. group이 늘면 성공한 group도 다시 받으므로 그 group도 멱등이어야 한다. 재투입한 메시지는 파티션 끝에 붙어 같은 주문의 순서가 바뀌지만, consumer는 순서에 기대지 않는다([도메인 모델 §4.3](../wiki/domain-model.md#43-이벤트-전달과-저장-형식)). 기록을 먼저 넣고 전송 뒤 커밋하는 방식(선점)은 버렸다. 넣은 자리 컬럼을 nullable로 두어야 하고, Kafka ack를 기다리는 동안 DB 커넥션과 행 잠금을 쥐며, 커밋 실패 때 중복 전송은 그대로 남는다.
- 브로커에 닿지 못하면(끝 offset 조회·poll·전송 시간 초과) `503 SERVICE_UNAVAILABLE`이고 기록하지 않는다. 시간 초과 뒤에 전송이 실제로 성공했을 수 있다. 다시 요청하면 중복 전송이고 위와 같이 consumer가 견딘다. 503은 시간 초과(`java.util.concurrent.TimeoutException`이나 원인 사슬의 Kafka `TimeoutException`)만이고, 다른 Kafka 실패와 출처 unique가 아닌 제약 위반은 500이다.
- 한계: 전송 상한 10초는 `send()`가 돌아온 뒤부터다. 원 토픽 메타데이터가 캐시에 없는데 브로커가 내려가 있으면 `send()` 자체가 `max.block.ms`(60초)까지 막힌다. 대개 그 전에 DLT 읽기(5초)가 503으로 끝난다. 공용 producer 설정은 바꾸지 않는다.
- 원인을 고치지 않고 넣은 메시지는 다시 DLT로 간다. 새 DLT 레코드의 `kafka_dlt-original-offset`이 재투입 기록의 넣은 `offset`과 같아서 목록에서 이어 볼 수 있다. 새 레코드는 새 좌표라 다시 재투입할 수 있다.

**5. 읽기 검사는 consumer와 같은 변환이다.** event-contract 8절의 두 경우(아는 것보다 높은 `schemaVersion`, 모르는 enum 값)를 API가 막는다.
- 토픽에서 타입을 찾는 표는 기동할 때 클래스패스의 `@Externalized` 타입을 훑어 만든다(대상 문자열의 `::` 앞이 토픽). `platform`이 업무 모듈 타입을 컴파일 의존하지 않는다. 의존하면 `payment → platform`과 순환한다(`KafkaConfiguration` KDoc과 같은 이유).
- 변환은 Boot의 `JsonMapper` 빈으로 한다. Kafka 변환기(`ByteArrayJacksonJsonMessageConverter`)와 같은 빈이다([이벤트 계약](../wiki/event-contract.md) 2절). 버전 가드는 이벤트 `init`의 `requireKnownSchemaVersion`이라 생성자 호출로 함께 걸린다.
- 한계: 검사는 요청을 받은 pod의 코드로 한다. 롤링 배포 중이면 아직 옛 pod인 consumer가 읽지 못해 다시 DLT로 갈 수 있다. "그 버전을 아는 코드가 모든 pod에 배포된 뒤 넣는다"는 운영자가 확인한다.
- 설정된 토픽 하나라도 타입을 못 찾거나 한 토픽에 타입이 둘이면 기동이 실패한다(`토픽 … 으로 나가는 @Externalized 타입이 하나가 아니다`). 토픽 목록과 `@Externalized`가 어긋난 것이기 때문이다. 버전 가드의 `DomainException`은 Boot `JsonMapper`에서 Jackson 예외에 감싸여 원인 사슬로 온다(통합 테스트로 확인). 그래서 Jackson 예외만 잡고, 그 밖의 예외는 500이다.

**6. 재투입 기록은 `t_dead_letter_redrive` 표다.** 감사의 정본이자 멱등 키다.
- 컬럼: `id`(UUID v7), `dlt_topic`, `dlt_partition`, `dlt_offset`, `dlt_timestamp`(DLT 레코드의 timestamp), `target_partition`, `target_offset`, `operator_subject`, `reason`, `BaseEntity` 공통 컬럼. 재투입 시각은 `created_at`이다. 원 토픽은 `dlt_topic`에서 나오므로 두지 않는다.
- unique `ux_dead_letter_redrive_source(dlt_topic, dlt_partition, dlt_offset, dlt_timestamp)`가 "한 DLT 레코드의 기록은 하나"의 유일한 방어선이다. 좌표만으로는 DLT 토픽을 다시 만들면(클러스터 이전·DR·재생성) offset이 0부터 다시 시작해 옛 기록과 겹친다. 레코드 시각은 `DeadLetterPublishingRecoverer`가 timestamp를 정하지 않아 producer가 DLT로 보낼 때 찍은 값이다(spring-kafka 4.1.1 `createProducerRecord`). 목록의 기록 조회(같은 토픽·partition의 offset 범위)도 이 인덱스를 타고, 시각까지 맞는 기록만 `redrive`로 붙인다.
- 기록은 바꾸거나 지우지 않는다. 엔티티에 변경 메서드가 없다. SQL로도 soft delete하지 않는다. 조회는 `@SoftDelete`로 삭제 행을 거르는데 unique는 삭제 행에도 걸려, 삭제 행과 같은 레코드를 요청하면 보낸 뒤 500이 된다.

**7. 운영자 재결제 입구는 점주의 것으로 정하고, 이 PR에서는 열지 않는다.** 재결제는 한 매장의 고객 결제를 provider에 다시 요청하는 일이라 매장 범위 주체의 몫이다. 플랫폼 운영자가 매장을 넘나들며 새 결제 시도를 만들 업무 이유가 없다. DLT 재투입은 이와 다르다. `order.payment-requested`·`payment.attempt-created`를 재투입하면 provider 호출이 일어날 수 있지만, 끊긴 시도를 이어 가는 것이고 새 시도를 만들지 않는다. 결말 난 시도는 `PaymentManagement.authorize`가 거르고, 같은 key는 한 partition에서 차례로 처리된다. 실제 provider를 붙이는 PR은 `paymentId`를 provider 멱등 키로 넘긴다는 기존 전제([kafka-event-redelivery](2026-09-26-kafka-event-redelivery.md))를 그대로 따른다. 점주 세션과 `StoreManagement.requireOwned`가 이미 있고, `PaymentManagement.retry`도 `storeId`를 받는다. 관리 화면 같은 요구가 생기면 점주 경로(`/owner/v1/stores/{storeId}/payment-attempts/{paymentId}/retry`)로 연다(제외). [payment-status-query](2026-10-06-payment-status-query.md)와 [operator-authentication](2026-10-06-operator-authentication.md)의 제외 절이 남긴 결정이다.

**8. 감사와 관측.**
- 처음 재투입한 뒤 INFO 한 줄을 남긴다: `audit action=DEAD_LETTER_REDRIVEN operator=… dltTopic=… dltPartition=… dltOffset=… topic=… partition=… offset=…`. 사유 원문은 남기지 않는다. 자유 입력이고 DB 기록이 정본이다(대조 case 해소와 같은 기준). 같은 레코드의 재요청에는 남지 않아 줄 수가 곧 기록된 재투입 건수다.
- 전송했는데 기록이 남지 않는 세 경로는 WARN 한 줄 `audit action=DEAD_LETTER_REDRIVE_UNRECORDED operator=… dltTopic=… dltPartition=… dltOffset=… cause=…`을 남긴다. `cause`는 `DUPLICATE`(경합에 진 쪽, 넣은 자리 포함), `RECORD_FAILED`(전송 뒤 기록 커밋 실패, 넣은 자리 포함, 응답 500), `SEND_UNCONFIRMED`(ack 시간 초과, 실제 전송 여부 모름, 응답 503)다. 이 경로의 행위자 정본은 로그다. `SEND_UNCONFIRMED`는 503으로 바꾸는 전송 경로 전부에서 남는다. ack 시간 초과뿐 아니라 메타데이터 대기 시간 초과로 실제로는 보내지 않은 경우와 delivery timeout으로 실패한 future도 들어서 "실제 전송 여부 모름"이다. ack를 기다리다 인터럽트되면 보냈을 수 있지만 줄 없이 500이다(종료 중). DUPLICATE·RECORD_FAILED는 이긴 기록을 읽거나 예외를 다시 던지기 전에 남겨, 뒤 조회가 실패해도 행위자가 남는다.
- 목록을 읽으면 INFO 한 줄 `audit action=DEAD_LETTERS_LISTED operator=… topic=… partition=… fromOffset=… count=…`을 남긴다. 여러 매장의 주문 ID·금액을 읽는 운영 행위다.
- counter `event.kafka.dead-letter.redriven{topic}`(Prometheus `event_kafka_dead_letter_redriven_total`)을 처음 재투입할 때만 올린다. `topic`은 원 토픽이다. 기동 때 토픽별 0으로 등록한다(DLT counter와 같은 이유).
- 400·401·403·404·422는 advice의 기존 로그(WARN, 404는 INFO), 503은 ERROR다. 401은 entry point가 원인 예외의 클래스와 메시지(토큰 값은 담기지 않는다)를 INFO로 남겨, 빈 JWK Set이나 키 회전처럼 IdP 쪽 이상이 401로 보일 때 가를 수 있게 한다. 목록 조회는 위 감사 줄 말고는 앱 로그를 남기지 않는다. 다만 요청마다 consumer를 만들므로 Kafka 클라이언트가 INFO(`ConsumerConfig values` 블록, partition 배정·seek, 종료)를 남긴다. 운영자가 드물게 부르는 조회라 로그 레벨을 바꾸지 않는다. 바꾸면 기동 때 실제 설정값을 확인하는 `ConsumerConfig values` 로그도 함께 사라진다.
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
- 한 DLT 레코드(좌표와 레코드 시각)의 재투입 기록은 하나다. 기록이 있으면 넣은 메시지가 브로커에 ack된 것이다. 기록 없는 전송은 WARN 감사 줄이 남는다.
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
- producer 내구성(`acks=all`, `enable.idempotence=true`)은 [kafka-event-redelivery](2026-09-26-kafka-event-redelivery.md) 공식 문서 점검의 기동 로그 값 그대로다. 재투입 전송도 같은 producer를 쓴다. 테스트 기동 로그의 `ProducerConfig values`에서 `key.serializer = StringSerializer`, `value.serializer = ByteArraySerializer`, `acks = -1`, `enable.idempotence = true`, `max.block.ms = 60000`, `delivery.timeout.ms = 120000`을 확인했다. 값 직렬화기는 spring-modulith-events-kafka 2.1.1의 `kafka-json.properties`가 정한다(consumer도 `ByteArrayDeserializer`). 그래서 DLT 레코드의 key 문자열과 값 바이트를 그대로 보내면 다시 직렬화하지 않는다.
- Boot 4.1.1 `JwtDecoderConfiguration`(sources jar)은 issuer decoder(`supplyJwtDecoderByIssuerUri`)도 jwk-set-uri decoder와 같은 `buildJwkSetUriJwtDecoder`를 거쳐 `JwkSetUriJwtDecoderBuilderCustomizer`를 `orderedStream`으로 적용한다. 그래서 운영 상한(`platform.AdminIdentityProviderTimeouts`, `@Order(HIGHEST_PRECEDENCE)`)이 먼저, 테스트의 IdP 응답 customizer가 뒤에 붙는다. 쓰는 타입은 `org.springframework.boot.security.oauth2.server.resource.autoconfigure.JwkSetUriJwtDecoderBuilderCustomizer`이고 `…autoconfigure.servlet` 패키지의 같은 이름은 4.1.0부터 deprecated다. discovery는 builder의 `restOperations`를 쓰고 4xx가 아닌 첫 실패에서 멈춘다(read 상한 한 번, 약 3초). 503 필터 순서가 읽는 `SecurityFilterProperties` 빈은 servlet 전용 `SecurityFilterAutoConfiguration`이 등록한다.
- spring-security-oauth2-jose 7.1.1 `NimbusJwtDecoder.JwkSetUriJwtDecoderBuilder`의 기본 `RestOperations`는 `RestTemplateWithDefaultTimeouts`이고 connect·read 상한은 `JwtDecoderProviderConfigurationUtils`가 시스템 속성(`sun.net.client.defaultConnectTimeout`·`defaultReadTimeout`, 기본 30000ms)에서 읽는다(바이트코드 확인). spring-kafka 4.1.1 `DefaultKafkaConsumerFactory.createConsumer`는 덮어쓰는 `Properties`를 `entrySet`으로 복사해 문자열이 아닌 값도 반영한다.
- Hibernate 7.4.5 `CurrentTimestampGeneration`은 VM 시각을 컬럼 정밀도(6자리)로 자른다. 그래서 처음 응답의 `redrivenAt`과 재요청이 DB에서 읽은 값이 같다(통합 테스트가 `created_at`과 대조한다).

### 범위 조정 (2026-10-21, multi-review 반영)
Claude 85점, Codex 판정 "주의 병합"(HIGH 1·MEDIUM 1·LOW 2), spring-backend-specialist P0 없음·P1 2건, Gemini 미실행(CLI 없음). 스키마 대조(code-reviewer)는 답 9개 일치, 보강 3곳이었다. 아래를 이 PR에 넣고 위 결정 본문을 고쳤다.
- **기록 없는 전송의 감사 줄(세 리뷰 모두 지적, 사용자 결정).** 순서(전송 먼저, 기록 나중)는 그대로 두고 결정 8의 `DEAD_LETTER_REDRIVE_UNRECORDED` WARN을 더한다. 기록 선점 상태 머신(PENDING·SENT·FAILED, Codex 제안)은 상태 컬럼·갱신 경로·멈춘 PENDING 처리가 따라오고, 전송 뒤 갱신이 실패하면 실제 전송 여부는 여전히 모르므로 버렸다.
- **멱등 키에 레코드 시각(스페셜리스트 P1, 사용자 결정).** 결정 6. 확인을 DLT 읽기 뒤로 옮겼다. 대가로 같은 레코드의 재요청도 브로커를 읽어 브로커 장애 때는 200 대신 503이다. DLT 토픽 ID(Kafka topicId)는 요청마다 Admin 호출이 늘고 이전 도구가 ID를 보존하면 구분하지 못해 버렸다.
- **IdP HTTP 상한, 503 필터의 헤더와 순서(Claude·Codex·스페셜리스트).** 결정 2. Claude와 스페셜리스트는 "상한 없음"이라 했지만 바이트코드로는 각 30초였다(공식 문서와 소스 대조). 위험은 남아 상한을 줄였다.
- **`sub` 검사(세 리뷰 모두).** 결정 4.
- **목록 감사와 principal(Claude·스페셜리스트).** 결정 8. 처음 결정 8은 목록에 로그를 남기지 않는다고 했다. 목록도 운영 행위이고, principal을 받으면 체인이 빠졌을 때의 이중 방어가 된다.
- **그 밖(한 리뷰어).** 허용 목록 검사를 DB 조회보다 앞에 둔다. `InterruptedException`은 인터럽트 플래그를 되살려 던진다. 401 원인을 INFO로 남긴다(결정 8). 결정 7의 DLT 재투입 경계, 결정 4의 consumer group 전제, 결정 2의 IdP 배포 확인 항목, 스키마 검토 질문 2·4의 보강, README `bootRun` 안내의 자리값, wiki·KDoc의 낡은 문구.
- **더하는 테스트.** 요청 offset이 log 시작보다 앞이면 다른 레코드를 돌려주지 않는다(404), DLT 읽기의 deadline 초과는 503이고 consumer를 닫는다, 실제 DB에서 커밋 때 unique 위반을 번역해 이긴 기록을 돌려준다, 레코드 시각이 다른 같은 좌표는 새로 재투입한다, 세 누락 경로의 WARN 줄, `sub`가 없거나 256자이거나 제어 문자를 담으면 500이고 보내지 않는다, aud 없는 토큰과 서명 없는(`alg: none`) 토큰은 401, 응답하지 않는 IdP는 상한 안에 503, 503 응답에 Security 헤더가 남는다, 목록 감사 줄.
- **받지 않은 것.** `Properties`의 Boolean 값이 무시된다는 지적(공식 문서와 소스 대조의 `DefaultKafkaConsumerFactory`). 요청마다 consumer metric series가 늘어난다는 지적(client id가 `ResilientPOS-consumer`로 고정이라 label이 같다, 미실측). 재투입 endpoint의 503·401·403 REST Docs(401·403은 체인 수준이라 목록 스니펫과 같다). 권한 값의 설정화와 `typ: at+jwt` 검사(IdP마다 달라 배포 PR이 정한다). 체인 밖에 `@PreAuthorize`를 겹치는 심층 방어(다른 모듈에 `/admin` endpoint가 생길 때 본다). `/admin`에서 두 IdP 예외 말고 다른 예외(권한 claim 형식 오류 등)가 새면 envelope가 아닌 Boot 오류 응답이 되는 것(IdP 설정 오류에서만 생긴다, 한계).

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
- docs/wiki/runbook.md
- docker/prometheus/alerts.yaml
- docs/wiki/architecture.md
- docs/wiki/entity-map.md
- docs/wiki/README.md
- README.md
- docs/spec/2026-10-21-platform-operator-dlt-redrive.md

## 인수 조건
1. issuer·audience·서명·만료가 맞고 `resilientpos-operator` 권한이 있는 토큰으로 두 `/admin` endpoint를 부를 수 있다. 토큰이 없거나 서명·만료·issuer·audience 중 하나가 틀리면 `401 UNAUTHENTICATED`이고 `WWW-Authenticate: Bearer`가 붙는다. 권한이 없으면 `403 ACCESS_DENIED`다. 응답에 `Set-Cookie`가 없다.
2. 점주 세션 쿠키로 `/admin`을 부르면 401, 운영자 토큰으로 `/owner/v1/stores`와 `/v1/orders`를 부르면 401, 단말 토큰으로 `/admin`을 부르면 401이다. `/owner/**`·`/v1/**`·`/actuator/health`의 기존 동작은 바뀌지 않는다.
3. issuer나 audience 설정이 없으면 애플리케이션 컨텍스트가 뜨지 않는다.
4. IdP에 닿지 못하면 `/admin` 요청은 envelope의 `503 SERVICE_UNAVAILABLE`이고 HTML이 아니다.
5. 목록은 설정된 DLT 토픽·partition의 `fromOffset`부터 `limit`건까지 결정 3의 항목으로 돌려준다. 재투입한 레코드는 `redrive`가 채워진다. 끝 이상이면 빈 목록이다. 설정에 없는 토픽(원 토픽·`__consumer_offsets` 포함), 없는 partition, 음수 offset, 1~50 밖의 `limit`은 `400 INVALID_DEAD_LETTER_POSITION`이다. 목록을 읽어도 어떤 consumer group의 커밋된 offset도 바뀌지 않는다.
6. DLT로 간 `order.paid` 메시지를 재투입하면 같은 key·값이 `order.paid`에 들어가 fulfillment가 생기고, 응답에 넣은 자리와 `operator`(토큰 `sub`)가 실리며, 기록 표에 행 하나와 사유가 남고, `event_kafka_dead_letter_redriven_total{topic="order.paid"}`가 1 오르며, 감사 INFO 한 줄이 남고 그 줄에 사유가 없다.
7. 같은 레코드(좌표와 레코드 시각)를 다시 재투입하면 같은 응답이 `200`이고, 원 토픽에 메시지가 늘지 않으며, counter와 감사 줄도 늘지 않는다.
8. `schemaVersion`이 아는 것보다 큰 메시지와 모르는 enum 값이 든 메시지의 재투입은 `422 DEAD_LETTER_NOT_READABLE`이고, 원 토픽에 메시지가 늘지 않으며, 기록도 남지 않는다.
9. 없는 offset은 `404 DEAD_LETTER_NOT_FOUND`, 빈 사유·500자 초과·NUL 문자가 든 사유는 `400 INVALID_REDRIVE_REASON`이다. 둘 다 전송도 기록도 하지 않는다.
10. 기록 저장이 unique에 걸리면(같은 레코드 경합) 이긴 쪽 기록을 `200`으로 돌려주고 `cause=DUPLICATE` WARN 감사 줄을 남긴다. 기록 확인이 커밋 전 행을 놓친 실제 DB 경합에서도 같다.
11. 브로커에 닿지 못하면 목록과 재투입은 `503 SERVICE_UNAVAILABLE`이고 기록이 남지 않는다.
12. V019 뒤 `t_dead_letter_redrive`가 있고 `(dlt_topic, dlt_partition, dlt_offset, dlt_timestamp)`가 unique다.
13. REST Docs 스니펫이 두 endpoint의 요청·응답 필드와 400·401·403·404·422 예를 문서화한다.
14. `ModuleStructureTest`·`DependencyDirectionTest`·`TransactionDeclarationTest`를 포함한 기존 테스트가 모두 통과한다.
15. 같은 좌표라도 레코드 시각이 다른 옛 기록(토픽 재생성)은 목록의 `redrive`에 붙지 않고, 그 레코드를 재투입하면 새로 보내고 새 행을 남긴다.
16. 전송 뒤 기록이 출처 unique가 아닌 이유로 실패하면 그 예외로 끝나고 `cause=RECORD_FAILED` WARN(넣은 자리 포함)을, ack 시간 초과·Kafka 시간 초과로 503이면 `cause=SEND_UNCONFIRMED` WARN을 남긴다. 세 WARN 줄에 사유 원문이 없다. ack를 기다리다 인터럽트되면 인터럽트 표시를 되살려 던진다.
17. 토큰의 `sub`가 없거나 비었거나 256자 이상이거나 제어 문자를 담으면 목록과 재투입 모두 500이고 DLT를 읽지도 보내지도 않는다.
18. 목록을 읽으면 `audit action=DEAD_LETTERS_LISTED` INFO 한 줄(운영자·위치·건수)이 남는다. 요청 offset이 log 시작보다 앞이면 다른 레코드를 돌려주지 않는다(재투입은 404). DLT 읽기가 deadline을 넘기면 503이고 consumer를 닫는다.
19. 연결은 받고 응답하지 않는 IdP면 issuer discovery가 기본 30초가 아니라 read 상한(3초) 안에 실패하고, 그 실패는 인수 4의 503이다. IdP 장애의 503 응답에도 정상 `/admin` 응답과 같은 Security 헤더(`X-Content-Type-Options`, `X-Frame-Options`, `Cache-Control` 등)가 남는다. 503 필터는 설정된 Security 필터 순서 바로 앞이다. 401은 원인 예외와 메시지를 INFO로 남기고 그 줄에 토큰 조각이 없다. aud가 없는 토큰과 서명 없는(`alg: none`) 토큰은 401이다.

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
    dlt_timestamp timestamptz not null,
    target_partition integer not null,
    target_offset bigint not null,
    operator_subject varchar(255) not null,
    reason varchar(500) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz,
    constraint ux_dead_letter_redrive_source unique (dlt_topic, dlt_partition, dlt_offset, dlt_timestamp)
);
```

- 질문 1(도출): 재투입 여부·행위자·사유·넣은 자리는 어디에도 없다. Kafka는 DLT 레코드를 읽거나 다시 보낸 사실을 남기지 않고, 로그는 보존 기간이 있으며 정본이 아니다. 원 토픽(`dlt_topic`에서 `.DLT`를 뺀 값)과 재투입 시각(`created_at`)은 도출할 수 있어 컬럼을 두지 않는다.
- 질문 2(지금 읽는 코드): 재투입은 좌표와 레코드 시각으로 기존 기록을 찾아 멱등 응답을 만들고, 목록은 범위로 찾아 시각까지 맞는 기록을 `redrive`로 채운다. `target_partition`·`target_offset`은 응답과 목록이 읽는다. 다시 DLT로 간 메시지(`kafka_dlt-original-offset`)를 이 재투입에 잇는 유일한 값이다. `operator_subject`는 응답과 목록이 읽는다. `reason`은 읽는 코드가 없다. 사람이 DB에서 보는 감사 정본이고(참고 문서의 operator action 요구) 재투입 시점에만 알아 나중에 채울 수 없다(질문 3, `t_sync_operation.kind`와 같은 판단).
- 질문 3(나중에 채우기): 재투입 시점에만 아는 값이라 나중에 채울 수 없다.
- 질문 4(마지막 방어선): unique `(dlt_topic, dlt_partition, dlt_offset, dlt_timestamp)`가 "한 DLT 레코드의 기록은 하나"와 멱등 응답의 유일한 방어선이다. Kafka offset은 정본이 될 수 없다는 규칙과 맞다. Kafka는 좌표와 시각만 주고 기록의 유일성은 PostgreSQL이 지킨다. 좌표만이면 DLT 토픽을 다시 만들 때 offset이 재사용돼 새 메시지가 옛 기록에 가려진다(결정 6). 기록을 지우는 명령이 없고 멱등 키라 soft delete된 행에도 걸리게 둔다(partial이 아니다). 두 조회(단건·범위)는 `@SoftDelete`로 삭제 행을 보지 못한다. 그래서 이 표의 행은 SQL로도 soft delete하지 않는다(결정 6). 삭제 명령이 생기면 조회가 삭제 행도 보게 한다(`t_order`와 같은 조건).
- 질문 5(정본): 재투입 기록의 정본은 이 표 하나다. 감사 INFO 로그는 사유가 없는 관측용 사본이고 커밋 뒤 남긴다. 로그가 빠져도 표가 맞다.
- 질문 6(check-then-act): 확인(기록 조회) 뒤 전송·기록이다. 경합은 unique가 막고, 진 쪽은 위반을 잡아 이긴 쪽 기록을 돌려준다. 전송은 트랜잭션 밖이라 경합한 두 요청이 둘 다 보낼 수 있고, consumer 멱등이 그 중복을 견딘다(결정 4). 기다리는 수단(row lock·`Lock`)은 두지 않는다. 낙관적 잠금도 필요 없다. 행을 고치는 경로가 없다.
- 질문 7(락·크기): 새 표 생성이라 다른 표를 잠그지 않는다. 기존 행이 없어 위반 행 검사가 필요 없다. `CREATE TABLE`도 새 relation에 `ACCESS EXCLUSIVE`를 잡지만 기다릴 세션이 없어 `lock_timeout`을 두지 않는다.
- 질문 8(이전 버전 호환): 이전 버전 애플리케이션은 이 표를 모르고 쓰지 않는다. `validate`는 매핑된 엔티티만 보고, Flyway는 기본값(`ignoreMigrationPatterns=*:future`)으로 모르는 미래 버전 V019를 무시하므로 이전 버전이 새 스키마에서 뜬다. multi-review 전의 V019(`dlt_timestamp` 없음)를 이미 적용한 로컬 DB는 checksum 검증에서 막히므로 초기화한다(배포 환경은 머지 전이라 해당 없음).
- 질문 9(이벤트·캐시): 이벤트 payload나 캐시 DTO에 실리지 않는다. 재투입은 DLT의 값 바이트를 그대로 옮기므로 이벤트 형식을 바꾸지 않는다.
- `dlt_topic varchar(249)`는 Kafka 토픽 이름의 최대 길이이고 `operator_subject varchar(255)`는 OIDC Core가 정한 `sub`의 최대 길이다. `sub`는 보내기 전에 길이와 제어 문자를 검사한다(결정 4). 사유 500자는 대조 case `note`와 같다. `dlt_timestamp`는 Kafka 레코드 timestamp(epoch 밀리초)라 `timestamptz`(마이크로초)에 그대로 들어간다.
- 대조 기록: code-reviewer 에이전트(구현자 아님), 2026-10-21, multi-review 반영 뒤 V019·DeadLetterRedriveEntity·DeadLetterRedriver·DeadLetterRedriveJpaRepository·DeadLetterReader·DeadLetterController 재대조. 불일치: 없음

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
- [runbook](../wiki/runbook.md) `OwnerSessionStoreUnavailable`와 그 규칙 주석(`docker/prometheus/alerts.yaml`): #106이 "`/actuator` 밖의 503은 점주 세션 저장소 장애뿐"을 전제로 썼다. 이 PR의 운영자 경로 503(IdP·브로커 장애)도 같은 규칙에 잡히므로 그 문장을 고치고 ERROR 줄의 `uri`로 가르는 법을 더한다. 규칙 식과 summary는 바꾸지 않는다.
- [아키텍처](../wiki/architecture.md): 인증 수단이 셋(단말 인터셉터, 점주 세션 체인, 운영자 토큰 체인)이 된 것과 `platform`의 책임에 `/admin` 체인·DLT 재투입을 더한다.
- [엔티티 맵](../wiki/entity-map.md): `t_dead_letter_redrive`. [wiki 인덱스](../wiki/README.md)의 엔티티 수.
- [README](../../README.md) 아키텍처 절: 인증 문장에 `/admin/**`(사내 IdP Bearer 토큰).
- 볼트 문서(저장소 밖): 현재 상태(운영자 주체, 재투입 API, 의존성), Modulith·Kafka 키워드(갱신 규칙대로 DLT 재투입 항목).
- 요구사항 변화: 플랫폼 운영자 주체와 재투입 감사가 생긴다. 원문의 operator action audit 요구를 DLT 재투입에 대해 채운다. 업무 정합성 요구는 그대로다.

## 분할 계획
한 PR, 한 컨텍스트(플랫폼 운영자 인증과 그 위의 DLT 재투입)다. 운영자 재결제 입구와 여러 매장 대조 해소는 결정만 하거나 제외했다.

경로 겹침(2026-10-21, origin/main `c7810af`):
- 열린 PR #94(Modulith 2.1.2 대기 draft)와 `build.gradle.kts`(의존성 줄 추가), `docs/wiki/domain-model.md`(§4.3의 다른 항목)가 겹친다. 먼저 머지된 쪽에 맞춰 rebase한다.
- 진행 중인 #76(관측 마무리, 아직 push 전)과 `docs/wiki/observability.md`가 겹칠 수 있다. 이 PR은 항목 추가만 하고 DLT 재투입 절차는 이 PR이 맡는다(합의).
- 구현 중(2026-10-21) 열린 PR #102(inbound rate limit, draft)와 `ApiControllerAdvice.kt`·`PlatformConfiguration.kt`·`application.yaml`·`docs/wiki/architecture.md`·`docs/wiki/observability.md`가 겹친다. 둘 다 항목 추가라 충돌은 줄 단위다. #102는 Controller 슬라이스 테스트들도 고치므로, 먼저 머지되면 이 PR의 `DeadLetterControllerTest`·`DeadLetterRedrive` 슬라이스도 같은 준비가 필요한지 rebase 때 본다.
- 공용 헬퍼: `/admin`의 503 필터는 `OwnerSessionStoreFilter`와 모양이 같지만 잡는 예외와 경로가 달라 합치지 않는다. main과 열린 PR에 운영자 인증·DLT 읽기 코드는 없다.
- PR 생성 직전 재확인(2026-10-21, origin/main `c7810af` 그대로): #76이 PR #106(ready)으로 열렸다. 겹치는 경로는 `build.gradle.kts`·`docker/docker-compose.yaml`·`README.md`·`application.yaml`·`docs/wiki/README.md`·`domain-model.md`·`event-contract.md`·`observability.md`이고, `git merge-tree`로 합쳐 보면 실제 충돌은 `observability.md` 하나다. #102와는 `observability.md`·`ApiControllerAdvice.kt`·`PlatformConfiguration.kt`가 줄 단위로 충돌하고, #94와는 충돌 없이 합쳐진다. 모두 서로 항목을 더한 변경이라 먼저 머지된 쪽에 맞춰 rebase하고 전체 테스트를 다시 돌린다.
- #106 머지 뒤 rebase(2026-10-21, origin/main `4ff68c2`): 줄 단위 충돌은 `observability.md` 하나였고 main 본문에 이 PR의 운영자 항목만 더했다. 의미 충돌이 하나 있었다. #106의 `OwnerSessionStoreUnavailable` 규칙·runbook·관측성 문서가 "`/actuator` 밖의 503은 점주 세션 저장소 장애뿐"이라고 적었는데, 이 PR의 `/admin` 503(IdP·브로커 장애)도 그 규칙에 잡힌다. 규칙 식은 그대로 두고 문장과 runbook의 "먼저 볼 것"만 고쳤다(Wiki 영향). 필터 단계의 503은 `uri` 태그가 `UNKNOWN`일 수 있어 식으로 `/admin`을 빼지 않았다. 운영자 요청은 사람이 드물게 부르므로 이 규칙을 오래 울리는 일은 드물다.

## TDD 검증 기록
`/admin` 체인과 Controller·재투입 동작은 TDD로 한다. 개발 전 테스트 의도이고 실행 뒤 채운다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 1·2: `AdminSecurityIntegrationTest` 5개(`issuer audience 서명 만료가 맞고 운영자 권한이 있는 토큰은 체인을 통과해 없는 경로의 404 envelope가 되고 Set-Cookie가 없다`, `토큰이 없거나 서명 만료 issuer audience 중 하나가 틀리면 401 UNAUTHENTICATED와 WWW-Authenticate Bearer이고 Set-Cookie가 없다`(토큰 없음·같은 kid 다른 키·만료·다른 issuer·다른 audience), `운영자 권한이 없는 토큰은 403 ACCESS_DENIED이고 Set-Cookie가 없다`, `점주 세션 쿠키나 단말 토큰으로 admin을 부르면 401이다`, `운영자 토큰으로 owner와 v1을 부르면 401이다`) | 의존성·설정·테스트만 두고 체인은 컴포넌트가 아닌 상수 선언으로 둔 상태에서 `./gradlew test --tests 'com.resilientpos.platform.Admin*'` — 8개 중 5개 실패(403 기대 404, 토큰 없음 401 기대 404, 점주 쿠키 401 기대 404, 아래 기동 실패 2개). 유효 토큰의 404와 owner·v1의 401은 체인 없이도 통과한 characterization이다 | 체인 구현 뒤 같은 명령 8개 통과. 테스트 `audiences`에 다른 값을 더하면 다른 audience 사례가 401 대신 404로 실패해 aud 검사기가 401을 만든다는 것을 확인하고 되돌렸다 | `./gradlew test --tests 'com.resilientpos.platform.*' --tests 'com.resilientpos.owner.*' --tests 'com.resilientpos.terminal.*'` 230개 통과, `./gradlew test` 721개 통과 |
| 인수 3: `AdminSecurityConfigurationTest` 4개(`issuer와 audience가 있으면 운영자 체인이 만들어진다`(대조군), `issuer가 비면 체인이 거부해 컨텍스트가 뜨지 않는다`, `issuer 없이 jwk-set-uri만 있으면 issuer 검사가 빠지므로 컨텍스트가 뜨지 않는다`, `audience가 비면 체인이 거부해 컨텍스트가 뜨지 않는다`) | 위와 같은 명령. issuer·audience 누락 2개는 `issuer 없이 컨텍스트가 떴다`·`audience 없이 컨텍스트가 떴다`로 실패. jwk-set-uri 사례는 첫 GREEN 뒤 리뷰에서 더했고, Boot에 맡긴 issuer 필수(JwtDecoder 없음)로는 4개 중 이것만 `issuer 없이 컨텍스트가 떴다`로 실패했다 | 체인에 issuer `check`를 더한 뒤 `./gradlew test --tests 'com.resilientpos.platform.Admin*'` 9개 통과 | 아래 다음 동작의 회귀에 포함 |
| 인수 4: `AdminIdentityProviderFilterTest` 5개(두 예외를 `DependencyUnavailableException`으로 resolver에 넘김, 응답이 커밋됐으면 다시 던짐, 무관한 예외는 통과, 등록은 admin 경로이고 Security 필터보다 앞), `AdminIdentityProviderOutageIntegrationTest`의 `issuer discovery에 실패하면 admin 요청은 HTML이 아니라 503 SERVICE_UNAVAILABLE envelope이고 ERROR 로그에 토큰이 없다`, `AdminJwkOutageIntegrationTest`의 `decoder 초기화 뒤 JWK Set 재조회가 실패하면 admin 요청은 503 SERVICE_UNAVAILABLE envelope이고 토큰은 출력에 없다` | 예외 클래스·통과만 하는 필터·경로 없는 등록만 둔 상태에서 `./gradlew test --tests 'com.resilientpos.platform.Admin*'` — 16개 중 5개 실패. 필터 단위 2개는 예외가 그대로 전파, 등록은 `expected [/admin/*] but was []`, 통합 2개는 `JwtDecoderInitializationException`(원인 `UnknownHostException: operator-idp.test`)과 `AuthenticationServiceException`(JWK 500)이 MockMvc 밖으로 샜다. 필터만 두고 advice handler가 없을 때는 통합 2개가 500 `INTERNAL_ERROR`로 실패했다 | 같은 명령 16개 통과 | `./gradlew test --tests 'com.resilientpos.platform.*'` 133개 통과, `./gradlew test` 729개 통과 |
| 인수 5·11(목록): `DeadLetterListIntegrationTest` 4개(`설정된 DLT의 fromOffset부터 limit건을 원 위치 예외 schemaVersion과 함께 돌려주고 없는 헤더는 null이며 스택 트레이스는 싣지 않는다`, `fromOffset이 partition 끝 이상이면 빈 목록이다`, `설정에 없는 토픽 없는 partition 음수 offset 1에서 50 밖의 limit은 400 INVALID_DEAD_LETTER_POSITION이다`, `목록을 읽어도 consumer group이 늘지 않고 어떤 group도 DLT에 offset을 커밋하지 않는다`), `DeadLetterReaderTest`의 `브로커에 닿지 못해 Kafka 시간 초과가 나면 DependencyUnavailableException이고 consumer를 닫는다`. 첫 레코드는 실제 `DeadLetterPublishingRecoverer.accept`로 넣어 헤더 형식을 실물과 대조했다 | 매핑 없는 Controller·빈 목록 reader·DTO만 둔 상태에서 `./gradlew test --tests 'com.resilientpos.platform.deadletter.*'` — 12개 중 10개 실패(슬라이스·통합은 `Status expected 200·400·503 but was 404`, 단위는 예외 대신 빈 목록) | 같은 명령 12개 통과. 토픽 허용 목록을 지우면 원 토픽 400 사례가 200으로, reader가 group으로 `commitSync`하게 바꾸면 offset 테스트가 `{dlt-reader-mutation=[order.paid.DLT-0]}`로 실패하는 것을 보고 되돌렸다 | `./gradlew test --tests 'com.resilientpos.platform.*'` 145개 통과, `./gradlew test` 741개 통과 |
| 인수 12: `SchemaMigrationTest`의 `V019 뒤 DLT 재투입 기록은 DLT 좌표 하나에 한 행만 들어간다`, 기존 두 테스트에 019와 `t_dead_letter_redrive` | `./gradlew test --tests 'com.resilientpos.SchemaMigrationTest'` — 9개 중 3개 실패(unique 조회가 빈 목록, 적용 버전에 019 없음, 표 목록에 `t_dead_letter_redrive` 없음) | V019를 더한 뒤 같은 명령 9개 통과 | 아래 행의 회귀에 포함 |
| 인수 5(redrive 필드)·6·7·9·10·11(재투입): `DeadLetterRedriveIntegrationTest` 4개(`DLT로 간 order paid를 재투입하면 같은 key와 값이 order paid에 들어가 fulfillment가 생기고 기록 counter 감사 줄이 하나씩 남으며 감사 줄에 사유가 없다`, `같은 좌표를 다시 재투입하면 처음 기록을 200으로 돌려주고 원 토픽 메시지 counter 감사 줄 기록이 늘지 않는다`, `재투입한 레코드는 목록의 redrive가 채워지고 다른 레코드는 null이다`, `없는 offset은 404 DEAD_LETTER_NOT_FOUND이고 빈 사유 500자 초과 NUL 사유는 400 INVALID_REDRIVE_REASON이며 둘 다 보내지도 기록하지도 않는다`), `DeadLetterRedriverTest` 8개(counter 0 등록, 사유 500자 경계와 공백 제거 저장, 사유 400은 읽기·전송·기록 없음, 404, unique 위반이면 이긴 기록·counter와 감사 없음, 다른 제약 위반은 그대로, ack 10초 초과 503·기록 없음, Kafka 시간 초과만 503). DLT 메시지는 `OrderPaid`를 `JsonMapper`로 직렬화해 실제 `DeadLetterPublishingRecoverer.accept`로 넣었다 | 엔티티·repository·V019·DTO만 두고 Controller 매핑 없음, redriver는 가짜 응답, `readRecord`는 null인 상태에서 `./gradlew test --tests 'com.resilientpos.platform.deadletter.*' --tests 'com.resilientpos.SchemaMigrationTest'` — 36개 중 15개 실패(`Status expected 200 but was 404`, `errorCode expected DEAD_LETTER_NOT_FOUND but was NOT_FOUND`, 단위는 예외 미발생·결과 불일치, counter `expected 0.0 but was null`) | 같은 명령 36개 통과. 기존 기록 확인(1단계)을 지우면 인수 7만 `원 토픽에 다시 보냈다 expected 1 but was 2`로 실패했고, 그 실행에서 두 번째 요청이 실제 DB의 unique 경로로 같은 응답을 받아 인수 10의 통합 증거도 됐다. 되돌렸다 | `./gradlew test --tests 'com.resilientpos.platform.*'`와 SchemaMigration·구조 테스트 5종 184개 통과, `./gradlew test` 757개 통과 |
| 인수 8: `DeadLetterRedriveIntegrationTest`의 `schemaVersion이 아는 것보다 큰 메시지와 모르는 enum 값이 든 메시지의 재투입은 422 DEAD_LETTER_NOT_READABLE이고 원 토픽에 메시지가 늘지 않으며 기록도 남지 않는다`(`order.paid`에 `SCHEMA_VERSION + 1`, `payment.approved`의 `kind`를 `REFUND`로. 출력에 `REFUND`가 없다), `DeadLetterRedriverTest`의 `원 토픽의 이벤트 타입으로 읽지 못하면 DEAD_LETTER_NOT_READABLE이고 값 내용 없이 보내지도 기록하지도 않는다`(높은 버전·JSON 아님·null), `DeadLetterEventTypesTest` 2개(설정된 토픽마다 타입 하나, 타입 없는 토픽이면 토픽 이름과 함께 생성 실패) | 표는 `Object`만 돌려주는 껍데기, redriver는 생성자 인자와 상수만 있고 호출이 없는 상태에서 `./gradlew test --tests 'com.resilientpos.platform.deadletter.*'` — 32개 중 4개 실패(표 생성 실패 미발생, `expected OrderPaid but was Object`, 통합 `Status expected 422 but was 200`, 단위는 검사 없이 전송·persist까지 가 `lateinit property createdAt`) | 같은 명령 32개 통과. 읽기 검사 호출을 지우면 통합·단위 2개가 같은 이유로 실패하는 것을 보고 되돌렸다 | `./gradlew test --tests 'com.resilientpos.platform.*' --tests 'com.resilientpos.ModuleStructureTest' --tests 'com.resilientpos.DependencyDirectionTest'` 172개 통과, `./gradlew test` 762개 통과 |
| 범위 조정 인수 12·15(레코드 시각 키): `SchemaMigrationTest`의 `V019 뒤 DLT 재투입 기록은 DLT 레코드의 좌표와 레코드 시각 하나에 한 행만 들어간다`, `DeadLetterRedriveIntegrationTest`의 `같은 좌표라도 레코드 시각이 다른 옛 기록은 목록의 redrive에 붙지 않는다`·`같은 좌표라도 레코드 시각이 다른 옛 기록이 있으면 새로 보내고 새 행을 남긴다`(30일 전 시각의 행을 JDBC로 먼저 넣는다), `DeadLetterRedriverTest`의 `DLT 위치가 틀리면 기록을 조회하지 않고 그 400을 그대로 던진다` | `./gradlew test --tests 'com.resilientpos.SchemaMigrationTest'` — unique 열 `expected 4열 but was 3열`. 스키마 반영 뒤 `./gradlew test --tests 'com.resilientpos.platform.deadletter.*' --tests 'com.resilientpos.SchemaMigrationTest'` — 44개 중 3개 실패(재투입 응답의 운영자가 옛 기록의 것, 목록에 옛 기록이 붙음, 위치 오류에도 저장소 조회) | 같은 명령 47개 통과 | 아래 회귀 |
| 범위 조정 인수 17·18(운영자·목록 감사): `DeadLetterControllerTest`의 `토큰의 sub가 없거나 비었거나 256자이거나 제어 문자를 담으면 목록과 재투입 모두 500이고 reader와 redriver를 부르지 않는다`, `토큰의 sub가 255자면 운영자로 받는다`(경계, characterization), `목록을 읽으면 운영자와 위치 건수를 감사 INFO 한 줄로 남긴다` | 같은 명령 — `목록 sub 없음 expected 500 but was 200`, 감사 줄 없음 | 같은 명령 52개 통과 | 아래 회귀 |
| 범위 조정 인수 10·16(기록 없는 전송의 감사·인터럽트): `DeadLetterRedriverTest`의 DUPLICATE·RECORD_FAILED(다른 제약 위반, `CannotCreateTransactionException`)·SEND_UNCONFIRMED(ack 10초 초과, Kafka 시간 초과 두 경로, 권한 실패는 줄 없음) 사례와 `ack를 기다리다 인터럽트되면 인터럽트 표시를 되살려 그 예외를 그대로 던지고 기록하지 않는다`, `DeadLetterRedriveIntegrationTest`의 `기록 확인이 커밋 전인 같은 레코드의 기록을 못 보고 보냈으면 커밋의 unique 위반을 번역해 이긴 기록을 200으로 돌려주고 DUPLICATE WARN 줄을 남긴다`(이긴 쪽 행을 커밋 전에 붙든 트랜잭션과 `pg_blocking_pids`로 실제 경합을 만든다, 컨텍스트 추가 없음) | 같은 명령 — 52개 중 7개 실패(WARN 줄 `List is empty`, `인터럽트 표시가 지워진 채 던졌다`). 실제 DB 경합 테스트는 200·이긴 운영자·행 1개 단언은 이미 통과했고(커밋 때 번역은 기존 코드의 characterization) WARN 단언만 RED였다 | 같은 명령 52개 통과 | 아래 회귀 |
| 범위 조정 인수 18(읽기 가드·deadline): `DeadLetterReaderTest`의 `요청 offset이 log 시작보다 앞이면 log 시작의 레코드를 돌려주지 않고 null이다`, `poll이 계속 비어 position이 그대로면 5초 deadline에서 DependencyUnavailableException이고 consumer를 닫는다`(Kafka `MockConsumer`) | 이미 통과한 characterization. 가드(`takeIf { it.offset() == offset }`)를 지우면 첫 테스트가 `expected null but was ConsumerRecord(offset = 5)`로, 기록 확인을 지우면 인수 7이 `원 토픽에 다시 보냈다 expected 1 but was 2`로 실패하는 것을 보고 되돌렸다 | 같은 명령 52개 통과 | `./gradlew test --tests 'com.resilientpos.platform.*'`와 SchemaMigration·구조 테스트 5종 200개 통과 |
| 범위 조정 인수 19(IdP 상한): `AdminIdentityProviderTimeoutsTest`의 `연결은 받고 응답하지 않는 IdP면 issuer discovery가 기본 30초가 아니라 read 상한 안에 실패한다`(accept하지 않는 `ServerSocket`, 원인 `SocketTimeoutException`), `운영 상한이 먼저 적용되므로 뒤에 붙는 customizer가 decoder의 HTTP 호출을 바꿀 수 있다`(`ApplicationContextRunner`, 새 SpringBootTest 컨텍스트 없음) | customizer 본문을 비운 상태에서 `./gradlew test --tests 'com.resilientpos.platform.Admin*'` — `elapsed=PT30.006S`로 실패. 본문은 있고 `@Order`만 없을 때는 운영 클라이언트가 테스트 IdP 응답을 덮어 `UnknownHostException: operator-idp.test`로 실패 | 같은 명령 20개 통과(상한 테스트 3.03초) | `./gradlew test --tests 'com.resilientpos.platform.*' --tests 'com.resilientpos.owner.*' --tests 'com.resilientpos.ModuleStructureTest'` 227개 통과 |
| 범위 조정 인수 19(503 헤더·순서·401 로그·토큰 사례): `AdminJwkOutageIntegrationTest`의 `IdP 장애의 503 응답에도 정상 admin 응답과 같은 Security 헤더가 남는다`, `AdminIdentityProviderFilterTest`의 두 예외 사례(헤더는 두고 본문만 비움)와 `등록은 admin 아래 경로만 걸고 설정된 Spring Security 필터 순서 바로 앞이다`, `AdminSecurityIntegrationTest`의 `만료 토큰과 서명 틀린 토큰의 401은 원인 예외와 메시지를 INFO로 남기고 토큰 조각은 출력에 없다`와 401 사례 `aud 없음`·`서명 없음(alg none)` | 같은 명령 — `X-Content-Type-Options expected nosniff but was 빈 값`, 필터 단위 `expected nosniff but was null`(2개), 순서 `expected 9 but was -101`, 401 INFO 줄 없음(DEBUG). aud 없음·`alg: none`은 처음부터 401인 characterization | 같은 명령 20개 통과. 뒤에 401 메시지의 제어 문자를 지우는 한 줄을 더하고 `./gradlew test --tests 'com.resilientpos.platform.AdminSecurityIntegrationTest'` 6개 통과 | 위와 같다 |
| 인수 13(422): `DeadLetterControllerTest`의 `원 토픽의 이벤트 타입으로 읽지 못하는 메시지면 422 DEAD_LETTER_NOT_READABLE이다`, 스니펫 `dead-letters-redrive-not-readable` | 위 RED에서도 통과했다. advice가 이미 `UNPROCESSABLE`을 422로 바꾸므로 characterization이다 | 같은 명령 32개 통과, 스니펫 디렉터리 확인 | 위와 같다 |
| 인수 13(목록): `DeadLetterControllerTest` 7개(200과 항목·없는 헤더 null, 생략 시 0과 50, 400 `INVALID_DEAD_LETTER_POSITION`, 필수 파라미터 없으면 400이고 reader 미호출, 503, 401과 `WWW-Authenticate: Bearer`, 403). 스니펫 `dead-letters-list`·`-list-invalid-position`·`-list-unavailable`·`dead-letters-unauthenticated`·`dead-letters-access-denied` | 위 RED에 포함. 401·403은 Controller 없이도 통과한 characterization(운영자 체인) | 위 GREEN에 포함. `build/generated-snippets`에 다섯 디렉터리 확인 | 위와 같다 |
| 인수 13(재투입): `DeadLetterControllerTest`에 3개(`재투입은 200과 넣은 자리 운영자 시각을 돌려주고 토큰의 sub를 운영자로 넘긴다`, `사유가 규칙에 어긋나면 400 INVALID_REDRIVE_REASON이고 본문에 사유가 없으면 빈 사유로 넘긴다`, `그 offset에 레코드가 없으면 404 DEAD_LETTER_NOT_FOUND다`)와 목록 스니펫의 `redrive` 필드. 스니펫 `dead-letters-redrive`·`-redrive-invalid-reason`·`-redrive-not-found`. 422 예는 다음 행 | 위 RED에 포함. 목록 스니펫의 `redrive` 문서화는 mock reader의 DTO 모양만 보므로 RED에서도 통과했다 | 위 GREEN에 포함. 스니펫 디렉터리 확인 | 위와 같다 |

## 결과
- 인수 조건 1~19를 TDD로 구현했다. 테스트 이름과 RED·GREEN·회귀 명령은 TDD 검증 기록 표에 있다.
- `./gradlew test`: 121개 클래스, 777개 테스트, 실패·오류·건너뜀 0(2026-10-21 09:47–09:53 UTC, 리뷰 반영을 모두 넣은 `279541d` 위).
- `python3 scripts/check_pr.py --base origin/main --worktree`와 `--worktree` 없는 제출 전 검사가 통과했다.
- multi-review(Claude 85점, Codex 판정 "주의 병합", spring-backend-specialist P0 없음·P1 2건, Gemini 미실행)를 반영했다. 사용자가 고른 것은 두 가지다. 기록 없는 전송은 순서를 유지하고 WARN 감사 줄로 남기고, 멱등 키에 DLT 레코드 시각을 더한다. 반영한 것과 받지 않은 것은 범위 조정(multi-review 반영) 절에 있다.
- 리뷰어 주장 중 소스로 확인해 받지 않은 것이 둘이다. IdP HTTP 호출은 "상한 없음"이 아니라 각 30초였고(그래도 2·3초로 줄였다), `Properties`의 Boolean 값은 무시되지 않았다.
- 스키마 변경 검토는 구현자가 아닌 리뷰어가 두 번 대조했다(처음 보강 3곳, 반영 뒤 불일치 없음).
- 볼트 문서(저장소 밖)도 갱신했다. 현재 상태(운영자 주체·재투입 API·의존성)와 Modulith·Kafka 키워드(재투입 절과 8.11 갱신, 8.37 재투입 방식, 8.38 DLT 읽기 수단, 7절 DLT 좌표와 원 좌표)다.
- 실행하지 않은 것: compose의 앱에서 `/admin` 호출(로컬 IdP가 없다), connect 2초 상한(응답 없는 주소가 환경마다 달라 read 상한만 실측), 실제 IdP와 롤링 배포 중의 재투입.
