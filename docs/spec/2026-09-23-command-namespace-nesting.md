# PR spec: 커맨드를 모듈 이름공간 아래 use case별로 중첩한다

## 목적
모듈의 쓰기 커맨드를 `OrderCommand.Create`처럼 모듈 이름공간 하나 아래에서 찾게 한다. 지금은 `CreateOrderCommand`, `CreateOrderLine`, `CreateOrderResult`처럼 use case 이름을 접두어로 반복하는 최상위 타입이 모듈마다 흩어져 있다. payment는 `AuthorizePaymentCommand`가 `CreatePaymentAttemptCommand.kt`에 같이 들어 있어 파일 이름과 내용도 어긋난다.

기대 결과: 호출부는 `OrderCommand.Create`, `OrderCommand.Create.Line`, `OrderCommand.Create.Result`로 쓴다. 커맨드에 딸린 입력 조각과 결과가 그 커맨드 안에 있어 짝이 한눈에 보인다.

## 범위
**모양.** 바깥은 필드 없는 `object`이고 use case는 그 안의 `data class`다. 입력 조각과 결과는 use case `data class` 안에 중첩한다. Kotlin의 중첩 클래스는 static이라 바깥 인스턴스를 참조하지 않고, 안팎의 `equals`·`copy`가 서로 영향을 주지 않는다.

| 이전 | 이후 |
|---|---|
| `order.CreateOrderCommand` | `order.OrderCommand.Create` |
| `order.CreateOrderLine` | `order.OrderCommand.Create.Line` |
| `order.CreateOrderResult` | `order.OrderCommand.Create.Result` |
| `order.RequestCheckoutCommand` | `order.OrderCommand.RequestCheckout` |
| `order.RequestCheckoutResult` | `order.OrderCommand.RequestCheckout.Result` |
| `payment.CreatePaymentAttemptCommand` | `payment.PaymentCommand.CreateAttempt` |
| `payment.CreatePaymentAttemptResult` | `payment.PaymentCommand.CreateAttempt.Result` |
| `payment.AuthorizePaymentCommand` | `payment.PaymentCommand.Authorize` |
| `payment.AuthorizePaymentResult` | `payment.PaymentCommand.Authorize.Result` |

**바깥을 `sealed interface`로 두지 않는다.** Facade는 커맨드마다 메서드가 따로 있고 `when (command)`로 분기하는 곳이 없다. sealed 계층은 그런 분기가 생길 때 의미가 있다. sync intake가 커맨드 타입으로 분기하게 되면 그때 `sealed interface`로 바꾼다. 중첩 경로가 같아 호출부는 바뀌지 않는다.

**로직은 커맨드 밖에 그대로 둔다.** 요청을 커맨드로 바꾸는 일은 `CreateOrderRequest.toCommand()`, 결과를 응답으로 바꾸는 일은 `*Response.from(result)`가 계속 맡는다. 커맨드는 모듈 공개 API이고 web DTO는 `internal` adapter라서, 커맨드에 `from(request)`를 두면 공개 API가 내부 adapter를 의존하게 된다. 검증은 계속 aggregate가 소유한다.

계약: 필드 이름·타입·순서와 Facade 메서드 이름은 바뀌지 않는다. HTTP 요청·응답, 이벤트, 저장 형식은 건드리지 않는다. 타입 이름만 바뀐다. data class의 `toString`은 단순 이름을 쓰므로 출력의 타입 이름이 `Create(...)`, `Result(...)`처럼 짧아진다. 커맨드와 결과를 통째로 찍는 운영 로그는 없다.

복구·관측성: 해당 없음. 동작이 바뀌지 않는다.

## 제외
- 조회 쪽 명명(`*Query`/`*Criteria`/`*View`). 조회 타입이 아직 하나도 없다. 첫 조회 PR에서 같은 모양을 따를지 정한다.
- 이벤트를 sealed 인터페이스 안으로 옮기는 일(`PaymentEvent.Declined` 등). `when` 분기가 생길 때 따로 한다.
- 커맨드에 팩토리·검증 로직을 더하는 일.
- 이미 머지된 PR spec 안의 옛 타입 이름. 그 PR 시점의 기록이다.

## 관련 문서
없음

## 변경 경로
- src/main/kotlin/com/resilientpos/order/**
- src/main/kotlin/com/resilientpos/payment/**
- src/test/kotlin/com/resilientpos/order/**
- src/test/kotlin/com/resilientpos/payment/**
- README.md
- docs/wiki/domain-model.md
- docs/spec/2026-09-23-command-namespace-nesting.md

## 인수 조건
1. `src`에 옛 타입 이름 9개가 남지 않는다.
2. order의 커맨드 타입은 `order/OrderCommand.kt` 한 파일에, payment의 커맨드 타입은 `payment/PaymentCommand.kt` 한 파일에 있다.
3. Fixture Monkey가 중첩 타입 `OrderCommand.Create`를 만들어 기존 fixture가 그대로 동작한다.
4. `ModuleStructureTest`를 포함한 기존 테스트가 모두 통과한다. 중첩 타입도 모듈 루트 패키지에 있어 공개 API로 판정된다.
5. README의 CQRS 규약과 도메인 모델의 명명 예시가 새 모양을 쓴다.

## 검증
- `LC_ALL=C grep -rnE '(CreateOrderCommand|CreateOrderResult|RequestCheckoutCommand|RequestCheckoutResult|CreatePaymentAttemptCommand|CreatePaymentAttemptResult|AuthorizePaymentCommand|AuthorizePaymentResult|CreateOrderLine([^R]|$))' src README.md docs/wiki` — 결과 없음. `LC_ALL=C`가 없으면 한글이 단어 문자라 `CreateOrderCommand로`처럼 조사가 붙은 이름을 놓치고, `CreateOrderLine` 뒤를 막는 것은 web DTO `CreateOrderLineRequest`를 빼기 위해서다.
- `./gradlew test` — 전체 통과. Docker가 필요하다.
- `python3 scripts/check_pr.py --base 4c5556e --worktree` — PASS.

## Wiki 영향
- [도메인 모델](../wiki/domain-model.md) — §1의 명령/결과 명명 규약·Facade 예시·공개 API 행, §4.4의 공개 타입 목록, §5의 레이어 경계 표를 이름공간 모양으로 바꾼다.
- README의 CQRS 규약 — 쓰기 줄을 새 모양으로 바꾸고, 읽기 줄에는 모양을 첫 조회 PR에서 정한다고 적는다.
- 요구사항 변화: 없다. 타입 이름과 위치만 바뀐다.

## 분할 계획
약 30개 파일 / 300줄 안팎이다. 대부분 한 줄짜리 이름 교체라 한도 안이고 나누지 않는다.

## TDD 검증 기록
동작이 바뀌지 않는 이름 변경 리팩터링이라 새 테스트를 쓰지 않는다. 기존 테스트 전체로 회귀를 본다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 기존 테스트 전체 | 해당 없음 — 동작 변경 없음 | 해당 없음 | `./gradlew test` — 199개 중 실패 0 |

## 결과
`./gradlew test` 199개가 전부 통과한다. `src`에 옛 타입 이름 9개가 남지 않는다.

- **이름만 바꿨다.** 필드, Facade 메서드 이름, HTTP·이벤트·저장 형식은 그대로다. `ModuleStructureTest`가 통과해 중첩 타입도 모듈 루트의 공개 API로 판정된다.
- **Fixture Monkey는 중첩 타입을 그대로 만든다.** `giveMeKotlinBuilder`에 `OrderCommand.Create`를 넘기고 `OrderCommand.Create::storeId` 같은 프로퍼티 참조로 값을 고정한다.
- **테스트 이름 두 개는 점 없이 풀어 적었다.** JVM 메서드 이름에 `.`을 쓸 수 없어 `OrderCommand.Create로`처럼 옮길 수 없다. `본문을 생성 커맨드로 옮기고`, `orderId를 결제 요청 커맨드로 옮기고`로 바꿨다.
- **테스트 헬퍼 `OrderFixtures.createOrderCommand`는 이름을 두었다.** 타입이 아니라 함수 이름이고 "주문 생성 커맨드를 만든다"는 뜻이 그대로 맞다.
- **import는 이름이 바뀐 줄만 고쳤다.** 원래 정렬되지 않았던 다른 import 줄은 건드리지 않았다.
- multi-review: Claude 87점, Codex 점수 미표기(P2 1건), Gemini 미실행(CLI 없음). 두 리뷰가 함께 짚은 것은 domain-model §5 레이어 표와 §1 공개 API 행이 옛 접미사 규약을 쓰던 점이고 반영했다. Claude의 보완 제안(`LC_ALL=C` 검증, `toString` 변화, sealed 전환 시점, 중첩 타입 import 금지, README 읽기 줄)도 반영했다.
- 규모: `check_pr.py` 기준 35개 파일 / 550줄(spec 포함).
