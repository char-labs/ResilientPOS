# PR spec: 빈 상품 코드·상품명을 가진 주문 line 거절

## 목적
`POST /v1/orders`는 `productCode`나 `productName`이 빈 문자열이거나 공백뿐인 line을 그대로 받아 저장한다. [현재 상태](../wiki/current-state.md)가 알려진 빈틈으로 적어 둔 항목이다. 상품을 식별할 수 없는 line은 영수증·주방 ticket·정산 어디에서도 쓸 수 없으므로 `OrderLine`이 생성 시점에 거절한다.

## 범위
- 소유 모듈: `order`. 규칙은 `internal/domain`의 `OrderLine`이 소유한다. 데이터가 가진 규칙은 데이터 가까이에 둔다는 기존 방식(`INVALID_QUANTITY`, `NEGATIVE_UNIT_PRICE`)을 따른다.
- 계약: `CreateOrderCommand`와 `CreateOrderResult`의 모양은 바뀌지 않는다. 거절 코드 두 개가 늘어난다.
  - `BLANK_PRODUCT_CODE`: `productCode`가 빈 문자열이거나 공백 문자뿐이다.
  - `BLANK_PRODUCT_NAME`: `productName`이 빈 문자열이거나 공백 문자뿐이다.
  - 둘 다 `DomainErrorKind.INVALID`(기본값)이고 HTTP에서는 기존 매핑대로 400이다.
- 판정은 Kotlin `isBlank()`다. 값을 다듬지 않는다. 앞뒤 공백이 있는 `" SKU-1 "`은 그대로 받아 그대로 저장한다. trim을 넣으면 `verifySamePayload`의 같은 payload 판정이 함께 바뀌므로 이 PR에서 다루지 않는다.
- 불변식: 저장된 `OrderLine`의 `productCode`와 `productName`은 blank가 아니다. 거절된 명령은 `t_order`와 `t_order_line`에 행을 남기지 않는다(기존 트랜잭션 경계가 보장하고 HTTP 통합 테스트로 확인한다).
- 실패·재시도·복구: 거절은 결정적이라 같은 요청을 다시 보내도 같은 400이다. 저장이 없으므로 복구 경로가 없다.
- 관측성: 새 로그 속성은 없다. `ApiControllerAdvice`가 `DomainException`의 `code`를 기존 방식으로 남긴다.
- REST Docs: `lines[].productCode`와 `lines[].productName` 필드 설명에 거절 코드를 적는다.

## 제외
- 길이 상한(`product_code varchar(64)`, `product_name varchar(255)`) 검증. 지금은 초과하면 DB 오류로 500이 된다. 별개의 규칙이고 상한 값을 도메인 상수로 둘지부터 정해야 하므로 후속 PR로 둔다.
- 앞뒤 공백 trim과 대소문자 정규화. 위 범위에 적은 이유다.
- 상품 코드의 형식(허용 문자)과 상품 마스터 대조. `t_product`는 두지 않고 line이 판매 시점 값을 기록한다는 기존 결정을 유지한다.
- DB `check` 제약. 마이그레이션을 바꾸지 않는다. 이 규칙의 방어선은 도메인 생성자이고 `OrderLine`을 거치지 않고 line을 쓰는 경로가 없다.
- `RequestCheckout`. 다음 PR이다.

## 관련 문서
없음.

## 변경 경로
- src/main/kotlin/com/resilientpos/order/internal/domain/OrderLine.kt
- src/test/kotlin/com/resilientpos/order/internal/domain/OrderTest.kt
- src/test/kotlin/com/resilientpos/order/internal/adapter/web/OrderApiIntegrationTest.kt
- src/test/kotlin/com/resilientpos/order/internal/adapter/web/OrderControllerTest.kt
- docs/spec/2026-09-21-order-line-blank-product.md
- docs/wiki/current-state.md
- docs/wiki/changes.md

## 인수 조건
1. `Order.create`에 `productCode`가 빈 문자열인 line과 공백뿐인 line을 각각 주면 `DomainException`의 `code`가 `BLANK_PRODUCT_CODE`, `kind`가 `INVALID`다. 메시지는 비어 있지 않고 코드와 다르다.
2. `Order.create`에 `productName`이 빈 문자열인 line과 공백뿐인 line을 각각 주면 `code`가 `BLANK_PRODUCT_NAME`, `kind`가 `INVALID`다. 메시지 조건은 1과 같다.
3. 앞뒤에 공백이 있지만 blank가 아닌 값은 거절하지 않고 다듬지 않은 채로 line에 담는다.
4. `POST /v1/orders`에 `productCode`가 빈 문자열인 line을 보내면 400이고 `error.errorCode`가 `BLANK_PRODUCT_CODE`이며 `t_order`에 행이 생기지 않는다.
5. 기존 테스트 55개가 그대로 통과한다.

## 검증
- 도메인: `./gradlew test --tests 'com.resilientpos.order.internal.domain.OrderTest'`
- HTTP 통합: `./gradlew test --tests 'com.resilientpos.order.internal.adapter.web.OrderApiIntegrationTest'` (Docker 필요)
- 전체 회귀: `./gradlew test`
- 하네스: `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전 `--worktree` 없이 한 번 더.
- 정상 시나리오: 기존 생성·재시도 테스트가 그대로 통과한다. 실패 시나리오: 인수 조건 1, 2, 4.

## Wiki 영향
- [현재 상태](../wiki/current-state.md): 알려진 빈틈에서 "빈 `productCode`·`productName`을 받는 것"을 지우고 거절 코드 두 개와 테스트 수를 반영한다. 길이 상한 초과가 500이 되는 것을 알려진 빈틈에 더한다.
- [변경 기록](../wiki/changes.md): 이 spec 링크와 요구사항 영향을 적는다.
- [도메인 모델](../wiki/domain-model.md): 무영향. 용어와 aggregate 경계가 바뀌지 않는다.

## 분할 계획
예상 규모는 구현 약 10줄, 테스트 약 60줄, 문서 약 90줄로 총 160줄·7파일 내외다. 경고 한도(600줄·20파일) 안이다. 초과하면 HTTP 통합 테스트(인수 조건 4)를 후속 PR로 옮긴다. 후속 순서는 `RequestCheckout`이고 길이 상한 검증은 그와 독립이라 어느 쪽이 먼저여도 된다.

## TDD 검증 기록

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 조건 1: `빈 productCode를 BLANK_PRODUCT_CODE로 거절한다` | `./gradlew test --tests 'com.resilientpos.order.internal.domain.OrderTest'`. 실패: `DomainException`을 기대했는데 `Order.create`가 `Transition`을 돌려주며 끝났다(`productCode=''`). 코드 상수만 먼저 선언해 컴파일은 통과한 상태였다 | 같은 명령. 10개 중 실패 0 | `./gradlew test`. 59개 중 실패 0 |
| 인수 조건 2: `빈 productName을 BLANK_PRODUCT_NAME으로 거절한다` | 같은 명령. 실패 이유도 같다(`productName=''`) | 같은 명령. 10개 중 실패 0 | `./gradlew test`. 59개 중 실패 0 |
| 인수 조건 3: `앞뒤 공백이 있는 상품 코드와 상품명은 다듬지 않고 받는다` | RED 없음. 기존 동작을 고정하는 characterization 테스트라 구현 전에 이미 통과했다 | 같은 명령. 통과 | `./gradlew test`. 59개 중 실패 0 |
| 인수 조건 4: `productCode가 빈 line은 400 BLANK_PRODUCT_CODE이고 t_order에 행이 생기지 않는다` | RED 미실행. 도메인 구현 뒤에 추가한 HTTP 연결 확인이라 실패를 먼저 관측하지 않았다. 도메인 예외가 400으로 바뀌는 매핑은 기존 `EMPTY_ORDER` 통합 테스트가 이미 검증한다 | `./gradlew test`. `OrderApiIntegrationTest` 4개 중 실패 0 | `./gradlew test`. 59개 중 실패 0 |

## 결과
- 인수 조건 1~5를 충족했다. `./gradlew test`는 59개(기존 55, 신규 4) 중 실패 0이다.
- 구현은 `OrderLine`의 `init`에 `isBlank()` 검사 두 개와 코드 상수 두 개다. `OrderLine`은 `Order.restore` 경로에서도 만들어지므로 blank 값이 이미 저장된 행이 있으면 조회가 실패한다. 배포된 데이터가 없어서 도달하지 않는 경로이고, 기존 `INVALID_QUANTITY`·`NEGATIVE_UNIT_PRICE`도 같은 성질이다.
- REST Docs는 `lines[].productCode`·`lines[].productName` 설명에 거절 코드를 적었다. 새 스니펫은 만들지 않았다. Controller 슬라이스는 Facade를 대역으로 두므로 도메인 규칙의 응답은 `EMPTY_ORDER` 문서와 모양이 같다.
- 제외에 적은 길이 상한 초과는 임시 테스트로 확인했다. 65자 `productCode`의 응답은 `500 INTERNAL_ERROR`였다. 임시 테스트는 커밋하지 않았고 [현재 상태](../wiki/current-state.md)의 알려진 빈틈에 적었다.
- `tdd-driver`에 위임하지 않고 주 에이전트가 TDD 절차를 직접 수행했다. 변경이 구현 8줄이라 위임 비용이 더 컸다.
