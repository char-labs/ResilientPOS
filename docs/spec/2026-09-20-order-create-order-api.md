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
- [현재 상태](../wiki/current-state.md): 첫 공개 endpoint, REST Docs 도입, 테스트 수를 반영한다. "REST Docs는 첫 Controller PR에서 추가한다"는 문장을 고친다.
- [변경 기록](../wiki/changes.md): 이 spec 링크와 결정 근거를 남긴다.
- [AGENTS.md](../../AGENTS.md): 기술 스택 요약에 REST Docs를 더한다.

## 분할 계획
예상 규모: 구현 약 80줄, 테스트 약 230줄, 문서 약 110줄로 총 420줄·10파일 내외. 경고 한도(600줄·20파일) 안이다.
초과하면 http-integration Checkpoint(인수 조건 7~8)를 후속 PR로 옮긴다. 후속 순서는 `order-idempotency-conflict` → 빈 상품 코드 규칙 → `RequestCheckout`이다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 개발 전에 테스트 의도를 적고 실행 후 관측 결과를 기록 | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
