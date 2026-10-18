# PR spec: checkout 요청 endpoint POST /v1/orders/{orderId}/checkout

## 목적
`OrderCommandFacade.requestCheckout`은 있지만(#32) 단말이 부를 HTTP endpoint가 없다. endpoint와 REST Docs를 더하고, 같은 주문에 요청이 겹쳤을 때 나는 경합 예외를 단말이 재시도해도 되는 응답으로 바꾼다. `RequestCheckout` 세 PR의 마지막이다.

## 범위
- 소유 모듈: `order`(endpoint와 응답 DTO), `platform`(경합 예외의 공통 응답).
- endpoint: `POST /v1/orders/{orderId}/checkout`. 요청 본문이 없다. `OrderController`가 경로의 `orderId`를 `RequestCheckoutCommand`로 옮겨 Facade를 부른다.
- 성공 응답: 200과 `data{orderId, status, checkoutNo, amount, currency}`. `amount`는 주문 통화 기준 decimal 문자열이고(`POST /v1/orders`의 `unitPrice`와 같은 표기) `currency`는 ISO 4217 코드다. 첫 요청과 재요청이 같은 200과 같은 본문이다.
- 오류 응답:
  - 없는 주문은 `404 ORDER_NOT_FOUND`다. `DomainErrorKind.NOT_FOUND`의 기존 매핑을 그대로 쓴다.
  - `orderId`가 UUID 형식이 아니면 `400 BAD_REQUEST`이고 Facade를 부르지 않는다.
  - 경합 예외는 `409 CONCURRENT_REQUEST`다. Spring의 `ConcurrencyFailureException` 계열(잠금 대기 초과의 `PessimisticLockingFailureException`, 버전 충돌의 `OptimisticLockingFailureException`)을 `ApiControllerAdvice`의 handler 하나가 받는다. `ErrorType`에 `CONCURRENT_REQUEST`를 더한다. 메시지는 고정 문구이고 예외 메시지는 로그에만 남는다. 로그 수준은 WARN이다.
- 관측성: 경합 응답은 기존 `failBody` 경로로 `errorCode=CONCURRENT_REQUEST`와 예외를 남긴다. 5xx가 아니라서 서버 오류율 지표에 섞이지 않는다.
- 실패·재시도·복구: `409 CONCURRENT_REQUEST`를 받은 단말은 같은 요청을 다시 보낸다. `RequestCheckout`은 상태로 멱등을 판정하므로 그때는 기존 결과를 받는다.
- REST Docs: 성공, `ORDER_NOT_FOUND`, `CONCURRENT_REQUEST`, 형식이 틀린 `orderId`를 문서화한다. `POST /v1/orders`의 `data.status` 설명을 "주문의 현재 상태"로 고친다(#32에서 checkout을 요청한 주문의 생성 재시도가 `CHECKOUT_REQUESTED`를 받는 것을 계약으로 삼았다).
- 인가가 없다는 점을 현재 상태(`docs/wiki/current-state.md`, 볼트)의 알려진 빈틈에 적는다.

### 합의한 결정 (2026-09-21 grill)
- 경로는 주문에 대한 행위를 하위 경로로 둔 `POST /v1/orders/{orderId}/checkout`이고 성공은 첫 요청과 재요청 모두 200이다. 회차를 조회하는 endpoint가 없고 주문의 상태를 바꾸는 요청이라 201보다 200이 맞다. 멱등 재시도가 "또 만들었다"로 읽히지 않는다.
- 경합 예외는 `409 CONCURRENT_REQUEST`다. 원인이 단말의 중복·동시 요청이라 4xx가 맞다. `503`과 `Retry-After`는 단말의 단순 중복 요청까지 5xx로 집계되고 프록시가 인스턴스 이상으로 해석할 수 있어 버렸다. 500으로 두는 안은 단말이 재시도해도 되는 오류와 서버 오류를 구분할 수 없어 버렸다.

### 범위 조정 (2026-09-21, 제출 전 multi-review 반영)
응답 DTO의 프로퍼티 이름과 응답에 나가는 상수 `CONCURRENT_REQUEST`가 있고 `platform` advice가 모든 endpoint에 적용되므로 PR 템플릿 기준 🔴이다. multi-review(Claude, Codex 실행. Gemini는 CLI가 없어 미실행)를 돌렸고 둘 다 막는 결함은 없다고 판정했다.
- 공통 문구가 재시도를 과하게 약속했다. `CONCURRENT_REQUEST`의 고정 문구에서 "다시 요청해 주세요"를 빼고 "동시 처리 충돌로 요청을 완료하지 못했습니다."로 좁힌다. `ConcurrencyFailureException`은 DB 경합을 뜻할 뿐이고 그 endpoint가 멱등인지는 보장하지 않는다. 같은 요청을 다시 보내면 된다는 안내는 checkout의 REST Docs에만 둔다. checkout에서도 "기존 결과를 받는다"는 앞 요청이 커밋한 경우의 이야기다. 앞 요청이 롤백했으면 재시도가 최초 전이를 수행한다.
- 409에는 재시도하면 안 되는 `IDEMPOTENCY_CONFLICT`와 재시도해도 되는 `CONCURRENT_REQUEST`가 함께 있다. 단말은 상태 코드가 아니라 `errorCode`로 분기해야 한다는 점을 wiki에 적는다.
- 이 계열에는 deadlock 패자와 직렬화 실패도 들어 있다. 트랜잭션이 롤백된 상태라 재시도는 안전하지만 단말의 중복 요청이 아니라 서버 코드의 잠금 순서 문제를 뜻할 수 있다. WARN 로그의 `errorCode=CONCURRENT_REQUEST`에서 따로 봐야 한다는 점을 wiki에 적는다.
- 커밋 시점의 버전 충돌이 이 handler에 도달하는지를 두 리뷰어가 물었다. 임시 통합 테스트로 확인했다(커밋하지 않았다). **도달하지 않는다.** `BaseEntity`의 `@UpdateTimestamp(source = DB)` 때문에 Hibernate 7.4.5가 `UPDATE ... RETURNING`을 쓰고, 버전이 맞지 않아 0행이 갱신되면 "The database returned no natively generated values"라는 `HibernateException`을 던진다. Spring은 이것을 `JpaSystemException`으로 바꾸므로 HTTP에서는 `500 INTERNAL_ERROR`다. 커밋은 거절되어 정합성은 지켜진다. #20부터 있던 `BaseEntity`의 성질이고 이 PR의 endpoint는 행 잠금을 써서 이 경로에 닿지 않는다. 고치는 일은 `BaseEntity`의 시각 생성 방식을 다루는 별도 PR로 둔다. 인수 조건 5의 `OptimisticLockingFailureException` 매핑은 조회 시점 충돌(#32에서 확인한 "conflicting version" 경로)에 해당한다.
- 인가 빈틈의 기록을 강화한다. 전이를 되돌리는 endpoint가 없어서 잘못 촉발된 checkout은 복구할 수 없다. 단말 인증과 주문의 매장·단말 소유권 검사가 들어오기 전에는 이 API를 외부에 노출하지 않는다. 인증만 넣고 소유권 검사를 하지 않으면 빈틈은 남는다.
- `CONCURRENT_REQUEST`의 재시도가 안전한 것은 지금 `OrderPaymentRequested`의 리스너가 없기 때문이기도 하다. Facade는 커밋 전에 이벤트를 발행한다. Event Publication Registry나 리스너를 넣는 PR이 발행과 트랜잭션의 묶임을 확인한다.
- 통합 테스트에서 주문 생성 재시도 시나리오를 별도 테스트로 떼고, 400 테스트에 `data`가 null인지와 고정 문구 확인을 더한다.

## 제외
- 인가(요청한 단말이 주문의 단말·매장과 같은지 확인). 단말 인증이 아직 없다. `orderId`만 알면 다른 매장의 주문을 전이시킬 수 있다는 점을 알려진 빈틈으로 적는다.
- 커밋 시점 버전 충돌이 `JpaSystemException`(500)으로 나오는 것의 수정. 위 범위 조정에 적은 `BaseEntity`의 성질이고 별도 PR이다.
- unique 제약 위반(`DataIntegrityViolationException`)의 매핑. 경합 예외 계열이 아니고 어떤 제약인지에 따라 뜻이 달라서 일반 매핑하지 않는다. `POST /v1/orders`의 같은 키 동시 요청은 계속 500이다.
- `Retry-After` 헤더와 재시도 간격 안내. 단말의 재시도 정책이 정해지면 다룬다.
- Asciidoctor HTML 빌드. 스니펫까지만 만든다.
- Event Publication Registry, `payment` 모듈. 다음 작업이다.

## 관련 문서
없음.

## 변경 경로
- src/main/kotlin/com/resilientpos/order/internal/adapter/web/**
- src/main/kotlin/com/resilientpos/platform/ApiControllerAdvice.kt
- src/main/kotlin/com/resilientpos/platform/ErrorType.kt
- src/test/kotlin/com/resilientpos/order/internal/adapter/web/**
- src/test/kotlin/com/resilientpos/platform/ApiAdviceTest.kt
- docs/spec/2026-09-21-order-request-checkout-api.md
- docs/wiki/current-state.md
- docs/wiki/changes.md

## 인수 조건
1. `POST /v1/orders/{orderId}/checkout`은 경로의 `orderId`를 `RequestCheckoutCommand`로 옮겨 Facade를 부르고 200과 `data.orderId`·`data.status`·`data.checkoutNo`·`data.amount`(decimal 문자열)·`data.currency`를 돌려준다.
2. Facade가 `ORDER_NOT_FOUND`(`NOT_FOUND`)를 던지면 404이고 `error.errorCode`가 `ORDER_NOT_FOUND`다.
3. Facade가 `PessimisticLockingFailureException` 계열을 던지면 409이고 `error.errorCode`가 `CONCURRENT_REQUEST`이며 `error.message`에 예외 메시지가 나가지 않는다.
4. `orderId`가 UUID 형식이 아니면 `400 BAD_REQUEST`이고 Facade를 부르지 않는다.
5. advice: `OptimisticLockingFailureException` 계열도 `409 CONCURRENT_REQUEST`이고, 처리하지 못한 다른 예외는 계속 `500 INTERNAL_ERROR`다.
6. 실제 Facade와 PostgreSQL까지 연결한 통합 테스트에서 주문을 만든 뒤 checkout을 요청하면 200과 `CHECKOUT_REQUESTED`, 주문 합계 금액을 받고 `t_order.status`가 `CHECKOUT_REQUESTED`다. 같은 요청을 다시 보내면 같은 본문이고 `t_order_checkout`의 행이 하나다. 별도 테스트에서, checkout을 요청한 주문에 같은 본문으로 `POST /v1/orders`를 다시 보내면 201과 `data.status`가 `CHECKOUT_REQUESTED`다.
7. 통합 테스트에서 없는 주문의 checkout 요청은 `404 ORDER_NOT_FOUND`다.
8. 1~4의 요청·응답으로 REST Docs 스니펫이 `build/generated-snippets`에 생긴다.
9. 기존 테스트 72개가 그대로 통과한다.

## 검증
- 웹 슬라이스: `./gradlew test --tests 'com.resilientpos.order.internal.adapter.web.*'`
- advice: `./gradlew test --tests 'com.resilientpos.platform.ApiAdviceTest'`
- 전체 회귀: `./gradlew test` (Docker 필요)
- 하네스: `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전 `--worktree` 없이 한 번 더.
- 정상 시나리오: 인수 조건 1·6. 실패 시나리오: 2·3·4·5·7.

## Wiki 영향
- 현재 상태(`docs/wiki/current-state.md`, 볼트): checkout endpoint와 응답 모양, `409 CONCURRENT_REQUEST` 매핑과 `ErrorType` 항목 수, 알려진 빈틈(인가 없음, unique 위반은 계속 500), 테스트 수를 반영한다. "checkout을 요청하는 endpoint는 아직 없다"와 "이 예외를 HTTP 응답으로 바꾸는 매핑은 아직 없다"를 지운다.
- 변경 기록(`docs/wiki/changes.md`, 2026-09-21 삭제): 이 spec 링크와 결정을 적는다.
- [도메인 모델](../wiki/domain-model.md): 무영향. 용어·aggregate 경계·스키마가 바뀌지 않는다.

## 분할 계획
예상 규모는 구현 약 50줄, 테스트 약 250줄, 문서 약 150줄로 총 450줄·10파일 내외다. 경고 한도(600줄·20파일) 안이다. 초과하면 advice의 `OptimisticLockingFailureException` 테스트(인수 조건 5)를 줄이지 않고 wiki 서술을 줄인다. 이 PR로 `RequestCheckout`이 끝난다. 다음 작업은 Event Publication Registry 도입과 `payment` 모듈이다.

## TDD 검증 기록

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 조건 5: `잠금 대기 초과와 버전 충돌은 메시지를 노출하지 않는 409 CONCURRENT_REQUEST다`(`ApiAdviceTest`) | `./gradlew test --tests 'com.resilientpos.order.internal.adapter.web.*' --tests 'com.resilientpos.platform.ApiAdviceTest'`. handler가 없는 상태에서 실패: 409를 기대했는데 500이었다 | 같은 명령. 통과 | `./gradlew test`. 81개 중 실패 0 |
| 인수 조건 1: `경로의 orderId를 RequestCheckoutCommand로 옮기고 200과 확정 금액을 돌려준다`, `소수 자리가 있는 통화의 금액은 통화 자릿수 그대로 나간다` | 같은 명령. endpoint가 없는 상태에서 둘 다 실패: 200을 기대했는데 404였다 | 같은 명령. 처음에는 성공 응답의 `error` 필드를 문서화하지 않아 REST Docs가 `SnippetException`으로 실패했다. 기존 `orders-create`처럼 `error`(null)를 문서 필드에 더한 뒤 통과 | `./gradlew test`. 81개 중 실패 0 |
| 인수 조건 2: `Facade가 ORDER_NOT_FOUND를 던지면 404이고 도메인 오류 코드를 담는다` | 같은 명령. 실패: `ORDER_NOT_FOUND`를 기대했는데 endpoint가 없어 advice의 `NOT_FOUND`였다 | 같은 명령. 통과 | `./gradlew test`. 81개 중 실패 0 |
| 인수 조건 3: `Facade가 잠금 대기 초과를 던지면 409 CONCURRENT_REQUEST이고 예외 메시지를 담지 않는다` | 같은 명령. 실패: 409를 기대했는데 404였다 | 같은 명령. 통과. 범위 조정으로 고정 문구를 바꾼 뒤 기대값도 함께 바꿨다 | `./gradlew test`. 81개 중 실패 0 |
| 인수 조건 4: `orderId가 UUID 형식이 아니면 400 BAD_REQUEST이고 Facade를 부르지 않는다` | 같은 명령. 실패: 400을 기대했는데 404였다 | 같은 명령. 통과. 범위 조정으로 더한 `data` null과 고정 문구 확인은 RED 없이 통과했다 | `./gradlew test`. 81개 중 실패 0 |
| 인수 조건 6: `주문을 만든 뒤 checkout을 요청하면 200과 확정 금액을 받고 재요청도 같은 본문이다`, `checkout을 요청한 주문의 생성 재시도는 201과 CHECKOUT_REQUESTED다` | 같은 명령. 한 테스트였을 때 실패: 200을 기대했는데 404였다. 범위 조정으로 둘로 나눈 뒤에는 RED를 다시 관측하지 않았다 | 같은 명령. 통과 | `./gradlew test`. 81개 중 실패 0 |
| 인수 조건 7: `없는 주문의 checkout 요청은 404 ORDER_NOT_FOUND다` | 같은 명령. 실패: `ORDER_NOT_FOUND`를 기대했는데 `NOT_FOUND`였다 | 같은 명령. 통과 | `./gradlew test`. 81개 중 실패 0 |

## 결과
- 인수 조건 1~9를 충족했다. `./gradlew test`는 81개(기존 72, 신규 9) 중 실패 0이다. REST Docs 스니펫 `orders-checkout`, `orders-checkout-not-found`, `orders-checkout-concurrent-request`, `orders-checkout-bad-request`가 `build/generated-snippets`에 생겼다.
- 구현: `OrderController.requestCheckout`, `RequestCheckoutResponse`, `ErrorType.CONCURRENT_REQUEST`, `ApiControllerAdvice`의 `ConcurrencyFailureException` handler다. 경로 변수를 문서화하려면 URL 템플릿이 필요해서 checkout의 웹 슬라이스 테스트는 Kotlin DSL 대신 REST Docs의 request builder를 쓴다.
- 이것으로 `RequestCheckout`(도메인 #31, 영속화·Facade #32, 이 PR)이 끝났다.
- multi-review: Claude 88점, Codex 90점, Gemini 미실행(CLI 없음). 둘 다 막는 결함은 없고 합의한 전제를 뒤집을 사유도 없다고 판정했다. 반영한 것은 위 범위 조정에 적었다. 반영하지 않은 것과 이유는 다음과 같다. advice 테스트의 두 경우를 한 테스트의 반복으로 둔 것은 같은 파일의 기존 테스트(`도메인 규칙 위반의 kind가 HTTP 상태를 정한다`)와 같은 방식이라 그대로 뒀다. 테스트 클래스 이름 `OrderCheckoutControllerTest`는 대상이 `OrderController`지만 문서화 대상 endpoint를 드러내려고 그대로 뒀다. 응답 DTO가 도메인 enum `OrderStatus`를 그대로 내보내는 것은 `CreateOrderResponse`와 같은 기존 방식이다. 요청 본문에 `terminalId`를 받아 주문의 것과 대조하자는 안은 위조할 수 있는 값이라 보안 통제가 아니고 가짜 안전감을 줘서 받지 않았다.
- 범위 밖에서 확인한 사실: 커밋 시점의 버전 충돌이 `JpaSystemException`(500)으로 나온다. 위 범위 조정과 현재 상태(`docs/wiki/current-state.md`, 볼트)의 알려진 빈틈에 적었고 별도 PR로 고친다.
- `tdd-driver`에 위임하지 않고 주 에이전트가 TDD 절차를 직접 수행했다.
