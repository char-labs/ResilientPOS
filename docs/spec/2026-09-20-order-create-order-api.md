# PR spec: 주문 생성 REST API POST /v1/orders

## 목적
단말이 HTTP로 주문을 만들 수 있게 한다. `OrderCommandFacade.create`와 응답 envelope·오류 변환은 이미 있지만 공개 endpoint가 없어서 밖에서 부를 길이 없다. 이 PR이 끝나면 `POST /v1/orders`가 첫 공개 API가 되고, 요청·응답·오류 계약이 REST Docs 스니펫으로 남는다.

## 범위
- `order/internal/adapter/web`에 `OrderController`와 요청·응답 DTO를 둔다. Controller는 요청을 `CreateOrderCommand`로 바꿔 Facade를 부르고 결과를 응답 DTO로 바꾸는 일만 한다([청사진 §1.1](../references/05_Domain_Development_Blueprint.md)).
- 요청 본문: `storeId`·`terminalId`·`clientOrderId`(UUID), `currency`(ISO 4217 코드), `lines[]`(`productCode`, `productName`, `unitPrice`, `quantity`). `unitPrice`는 decimal string이다([개발 가이드 §3.2](../references/03_Development_Guide.md)). 통화는 주문 단위로 한 번만 받고 line 단가는 그 통화를 따른다.
  - `terminal` 인증이 없어서 `storeId`·`terminalId`를 본문으로 받는다. 인증이 생기면 본문 값과 credential의 단말이 같은지 검증하는 쪽으로 확장하고 본문 모양은 유지한다.
- 성공 응답: 201과 `data{orderId, status}`. `ApiResponseBodyAdvice`가 envelope로 감싼다. 값 클래스(`OrderId`)를 그대로 직렬화하지 않고 응답 DTO에서 UUID로 푼다.
- 재시도: 같은 `(terminalId, clientOrderId)`로 다시 요청해도 201과 같은 본문이다. 응답을 잃은 단말이 원래 받았어야 할 응답을 그대로 받는다([개발 가이드 §6.1](../references/03_Development_Guide.md) "same idempotency key returns original outcome"). `CreateOrderResult`는 고치지 않는다.
- 요청 검증: Bean Validation을 쓰지 않는다. DTO 필드를 `UUID`·`Currency`·`BigDecimal`·`Int`로 선언해 형식 오류(UUID·통화 코드·숫자 형식, 필드 누락, JSON 문법)는 Jackson 역직렬화가 거절하고 #22 advice가 `400 BAD_REQUEST`로 바꾼다. 업무 규칙은 도메인이 `DomainException`으로 거절하고 #23 handler가 `kind`에 맞는 상태로 바꾼다. 같은 규칙을 DTO와 도메인 두 곳에 두지 않는다.
- Spring REST Docs를 도입한다(test 의존성, MockMvc). 스니펫은 `build/generated-snippets`에 만든다. 성공, 도메인 거절, 형식 오류 세 가지를 문서화한다.
- 소유 모듈: `order`. 계약: `POST /v1/orders`의 요청·응답 JSON과 상태 코드. 불변식: Controller는 규칙을 판단하지 않는다. 복구: 재시도는 Facade의 기존 결과 반환에 맡긴다. 관측성: 거절은 advice가 `errorCode` 로그를 남기고, 요청 수·오류율·지연은 Actuator의 `http.server.requests`가 맡는다.

## 제외
- 같은 `(terminalId, clientOrderId)`에 다른 payload가 오는 경우. 지금은 기존 결과와 201을 돌려준다. payload hash 비교와 `409 IDEMPOTENCY_CONFLICT`(`DomainErrorKind.CONFLICT`)는 후속 PR `order-idempotency-conflict`.
- 빈 `productCode`·`productName` 거절. 도메인 규칙이므로 `OrderLine`에 `DomainException`으로 추가한다. 후속 PR.
- `Location` 헤더와 `GET /v1/orders/{id}`. 조회 API가 없어서 가리킬 곳이 없다.
- 단말 인증·인가, `schemaVersion`, correlation ID, 구조화 로그 속성(MDC). 로그 포맷과 수집 설정이 아직 없다.
- Asciidoctor HTML 빌드와 문서 배포. 스니펫 생성까지만 한다. 문서를 내보낼 곳이 생기면 추가한다.
- Bean Validation(`spring-boot-starter-validation`). 필드 단위 오류 목록이 필요해지면 다시 검토한다.

## 관련 문서
없음. 선행 PR은 [api-response-advice](2026-09-19-api-response-advice.md)와 [foundation-domain-exception](2026-09-19-foundation-domain-exception.md)이다. endpoint 근거는 [시스템 설계 §3.1](../references/02_System_Design.md), Controller 테스트 기준은 [테스트 전략](../wiki/testing-strategy.md)이다.

## 변경 경로
- build.gradle.kts
- src/main/kotlin/com/resilientpos/order/internal/adapter/web/**
- src/test/kotlin/com/resilientpos/order/internal/adapter/web/**
- docs/spec/2026-09-20-order-create-order-api.md
- docs/wiki/current-state.md
- docs/wiki/changes.md
- AGENTS.md

## 인수 조건
웹 슬라이스(1~6)는 `@WebMvcTest`와 Facade test double로 검증하고 Docker가 필요 없다. 통합(7~8)은 실제 Facade와 PostgreSQL을 쓴다.
1. 유효한 요청은 201이고 `result`가 `SUCCESS`, `data.orderId`가 Facade가 돌려준 UUID, `data.status`가 `OPEN`이다.
2. Controller는 본문을 `CreateOrderCommand`로 빠짐없이 옮긴다. `unitPrice` `"4500"`과 `currency` `"KRW"`는 `Money.of("4500", KRW)`가 된다.
3. Facade가 `DomainException(EMPTY_ORDER)`을 던지면 400이고 `error.errorCode`가 `EMPTY_ORDER`다.
4. `unitPrice`가 통화 자릿수를 넘으면(`"1000.5"`, KRW) Facade를 부르기 전에 400 `INVALID_MONEY_SCALE`이다.
5. UUID 형식이 아닌 `terminalId`, 없는 통화 코드, 빠진 필수 필드, 깨진 JSON은 각각 400 `BAD_REQUEST`이고 Facade를 부르지 않는다.
6. 성공(1), 도메인 거절(3), 형식 오류(5 중 하나)의 요청·응답 필드가 `relaxed` 없이 문서화되고 `build/generated-snippets/orders-create*`에 스니펫이 생긴다.
7. 같은 본문을 두 번 보내면 두 번 다 201이고 `data.orderId`가 같으며 `t_order`에 행이 하나다.
8. line이 빈 요청은 400 `EMPTY_ORDER`이고 `t_order`에 행이 생기지 않는다.
9. `ModuleStructureTest`와 `DependencyDirectionTest`가 그대로 통과한다. Controller는 `order.internal` 밖으로 새지 않는다.
10. `python3 scripts/check_pr.py --base origin/main`이 통과한다.

### Checkpoint와 검증 명령
| Checkpoint | 검증 명령 | 의존 |
|---|---|---|
| web-slice | `./gradlew test --tests 'com.resilientpos.order.internal.adapter.web.OrderControllerTest'` | 없음 |
| http-integration | `./gradlew test --tests 'com.resilientpos.order.internal.adapter.web.OrderApiIntegrationTest'` | web-slice |

## 검증
```sh
./gradlew test
ls build/generated-snippets
python3 scripts/check_pr.py --base origin/main --worktree
```
- 정상: 201 응답과 재시도 시 같은 `orderId`.
- 실패: 도메인 거절과 형식 오류가 각각 지정한 `errorCode`의 400으로 나가고 저장되지 않는지 확인한다.

## Wiki 영향
- 현재 상태(`docs/wiki/current-state.md`, 볼트): 첫 공개 endpoint, REST Docs 도입, 테스트 수를 반영한다. "REST Docs는 첫 Controller PR에서 추가한다"는 문장을 고친다.
- 변경 기록(`docs/wiki/changes.md`, 2026-09-21 삭제): 이 spec 링크와 결정 근거를 남긴다.
- [AGENTS.md](../../AGENTS.md): 기술 스택 요약에 REST Docs를 더한다.

## 분할 계획
예상 규모: 구현 약 80줄, 테스트 약 230줄, 문서 약 110줄로 총 420줄·10파일 내외. 경고 한도(600줄·20파일) 안이다.
초과하면 http-integration Checkpoint(인수 조건 7~8)를 후속 PR로 옮긴다. 후속 순서는 `order-idempotency-conflict` → 빈 상품 코드 규칙 → `RequestCheckout`이다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 1~5, `OrderControllerTest`의 8개 요청·매핑·오류 테스트 | `./gradlew test --tests 'com.resilientpos.order.internal.adapter.web.OrderControllerTest'`. 매핑 없는 골격 `OrderController`(요청 처리 없음)로 실행해 8개 모두 실패 관측: 성공·커맨드매핑·EMPTY_ORDER·형식 오류(4건)는 expected 201 또는 400 but was 404(Not Found), 커맨드 캡처 테스트는 stub 미설정으로 NullPointerException | 같은 명령. `CreateOrderRequest`·`CreateOrderLineRequest`·`CreateOrderResponse`·`OrderController.create` 구현 후 8개 전부 통과 | `./gradlew test --tests 'com.resilientpos.order.internal.adapter.web.OrderControllerTest'` 8개 통과 유지 |
| 인수 6, REST Docs 3종(`orders-create`, `orders-create-domain-rejected`, `orders-create-bad-request`) | 같은 클래스에 `document(...)` 3개 테스트 추가하되 `@AutoConfigureRestDocs` 미적용 상태로 실행 → 3개 모두 IllegalStateException: REST Docs configuration not found. Did you forget to apply a MockMvcRestDocumentationConfigurer | `build.gradle.kts`에 `spring-boot-starter-restdocs`·`spring-restdocs-mockmvc` test 의존성 추가, 테스트 클래스에 `@AutoConfigureRestDocs`(`org.springframework.boot.restdocs.test.autoconfigure`) 적용 후 재실행 → 2개는 FieldTypesDoNotMatchException(lines[].unitPrice를 NUMBER로 기술했으나 실제 값은 String) 실패, `orders-create-domain-rejected`만 통과. `lines[].unitPrice` 기술을 STRING으로 고친 뒤 11개(웹 슬라이스 8 + Docs 3) 전부 통과, `build/generated-snippets`에 3개 디렉터리 생성 확인 | `./gradlew test --tests 'com.resilientpos.order.internal.adapter.web.OrderControllerTest'` 11개 통과 유지, `ls build/generated-snippets`로 orders-create·orders-create-domain-rejected·orders-create-bad-request 3개 확인 |
| 인수 7~8, `OrderApiIntegrationTest`의 재시도·EMPTY_ORDER 통합 2개 | 웹 슬라이스 구현이 이미 완료된 뒤 처음 작성해 실행했고 Docker 기반 PostgreSQL(Testcontainers)로 2개 모두 즉시 통과. 별도 실패를 관측하지 못해 characterization으로 기록(실제 Facade·DB 연결은 `OrderCommandFacadeIntegrationTest`가 이미 검증한 로직을 HTTP 경유로 재확인하는 성격) | `./gradlew test --tests 'com.resilientpos.order.internal.adapter.web.OrderApiIntegrationTest'` 2개 통과(Docker 필요, 이번 실행 환경에 Docker 있어 실제 실행함) | 동일 명령 2개 통과 유지 |
| 인수 9, `ModuleStructureTest`·`DependencyDirectionTest` 회귀 | 신규 코드 추가 전부터 이미 통과하던 기존 테스트라 RED 없이 characterization | `./gradlew test --rerun` 전체 53개 통과, 그중 `ModuleStructureTest` 2개·`DependencyDirectionTest` 2개 포함 | `./gradlew test --rerun` 53개 전부 통과, 실패 0 |
| 리팩터링: 동작 검증과 문서화를 같은 테스트로 합치고 요청 JSON·필드 기술을 함수로 뽑음(`OrderControllerTest` 11개 → 7개, 시나리오는 그대로) | 해당 없음(동작을 바꾸지 않는 테스트 정리) | 해당 없음 | `./gradlew clean test` → BUILD SUCCESSFUL, 49개 전부 통과(기존 40 + `OrderControllerTest` 7 + `OrderApiIntegrationTest` 2), `ls build/generated-snippets`로 3개 디렉터리 확인 |

## 결과
`order/internal/adapter/web`에 `OrderController`, `CreateOrderRequest`·`CreateOrderLineRequest`, `CreateOrderResponse`를 추가했다. Controller는 `request.toCommand()`로 Facade를 부르고 `CreateOrderResponse.from(result)`를 201로 돌려주는 한 줄이다. envelope와 오류 변환은 #22·#23의 advice가 맡아서 Controller에 try-catch나 검증 코드가 없다.

REST Docs 좌표는 Gradle 의존성 해석과 jar 내용으로 확인했다. `spring-boot-starter-restdocs`(4.1.1)만으로는 `spring-restdocs-core`만 들어와서 `spring-restdocs-mockmvc`(4.0.1, BOM 관리)를 같이 선언했다. `@AutoConfigureRestDocs`의 패키지는 `org.springframework.boot.restdocs.test.autoconfigure`다. 출력 경로는 설정 없이 `build/generated-snippets`다.

개발 중 확인한 Jackson·JDK 동작:
- `unitPrice`는 DTO에서 `BigDecimal`이지만 JSON 문자열 `"4500"`을 그대로 받는다. 그래서 REST Docs 필드 타입은 `STRING`이다.
- `"XXX"`는 ISO 4217이 "통화 없음"으로 정의한 실제 코드라서 `Currency`가 거절하지 않는다. 없는 통화 사례는 `"ZZZ"`로 검증한다.
- 빠진 필드 사례는 `storeId`로 검증했다. 빠진 `quantity`가 0으로 채워져 도메인의 `INVALID_QUANTITY`로 나가는지는 확인하지 않았다. 어느 쪽이든 400이다.
- Mockito의 `any()`·`ArgumentCaptor`는 Kotlin non-null 파라미터에서 NPE가 난다. `CreateOrderCommand`가 data class라서 기대 값과의 동등성으로 stub·verify했고 `mockito-kotlin`은 추가하지 않았다.

인수 조건 7~8의 통합 테스트는 웹 슬라이스 구현이 끝난 뒤에 써서 처음부터 통과했다. RED를 보지 못했으므로 characterization으로 기록했다. 첫 구현에서 `OrderControllerTest`가 360줄이었고 PR 검사가 600줄 경고를 냈다. 같은 요청의 동작 검증과 문서화가 따로 있었고 JSON 본문과 필드 기술이 반복됐기 때문이다. 시나리오를 그대로 두고 합쳐서 190줄로 줄였다.

`./gradlew clean test`는 49개 테스트가 모두 통과했다(실패 0, Docker·Testcontainers PostgreSQL 사용). `build/generated-snippets`에 `orders-create`, `orders-create-domain-rejected`, `orders-create-bad-request`가 생긴다.
