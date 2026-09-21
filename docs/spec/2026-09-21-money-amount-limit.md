# PR spec: Money가 저장 가능한 금액 범위를 불변식으로 지킨다

## 목적
금액에 상한이 없어서 저장할 수 없는 주문이 만들어지고, 그 주문은 복구할 수 없다. 실제 요청으로 재현했다. 단가 `999999999999999`(15자리)에 수량 2로 주문을 만들면 `201 CREATED`로 커밋되고, 이어서 checkout을 요청하면 합계가 16자리가 되어 `t_order_checkout.amount`(`numeric(19,4)`, 정수부 15자리) INSERT가 실패한다. 응답은 `500 INTERNAL_ERROR`이고 재시도해도 같다. 주문은 `OPEN`으로 남는데 line을 고치거나 주문을 취소하는 명령이 없어서 DB를 직접 고치는 것 말고는 복구 경로가 없다.

원인은 저장 가능한 범위의 정본이 마이그레이션의 컬럼 타입 한 곳에만 있고 도메인이 그것을 모르는 것이다. `BigDecimal`은 무한 정밀도라 `Money.times`가 16자리를 그대로 만들어 낸다.

## 범위
- 소유 모듈: `foundation`. `Money`가 저장 가능한 범위를 불변식으로 갖는다.
- 불변식: 금액의 정수부는 15자리를 넘지 않는다. `amount.precision()`에서 `amount.scale()`을 뺀 값이 15 이하인지로 검사한다. 이 식은 `BigDecimal`의 메타데이터만 읽으므로 값을 문자열로 펴지 않는다.
- 위반하면 `DomainException(INVALID_MONEY_AMOUNT, ..., INVALID)`이고 HTTP에서는 기존 매핑대로 400이다. 기존 `INVALID_MONEY_SCALE`과 구분한다. scale은 "소수 자릿수가 통화와 다르다", 새 코드는 "금액이 저장할 수 있는 범위를 넘는다"로 뜻이 다르고 단말의 대응도 다르다.
- 검사 위치는 `init`이다. 생성자, `copy()`, `of`, `plus`, `minus`, `times`가 모두 같은 검사를 거친다. `Money`를 만드는 경로에 예외가 없다.
- 효과: 단가가 범위를 넘으면 주문 생성이 400으로 거절된다. 단가가 범위 안이어도 합계가 넘으면 `Order.total`을 계산하는 시점에 400이 된다. 두 경우 모두 저장되지 않으므로 복구할 수 없는 주문이 생기지 않는다.
- 상한 15의 근거는 `t_order_line.unit_price`와 `t_order_checkout.amount`가 둘 다 `numeric(19,4)`라는 것이다. 정수부 19 − 4 = 15자리다. `foundation`이 저장 형식을 알게 되는 대가를 치른다. 상수와 근거를 `Money`에 주석으로 남겨서, 컬럼 타입을 바꾸는 PR이 이 상수도 함께 보게 한다.
- 정합성 불변식: 저장된 모든 `Money`는 `numeric(19,4)`에 들어간다. 마지막 방어선인 컬럼 타입은 그대로 두고 그 앞에 도메인 검사를 세운다.
- 실패·재시도·복구: 거절은 결정적이라 같은 요청을 다시 보내도 같은 400이다. 저장이 없으므로 복구 경로가 없다.
- 관측성: 새 로그 속성은 없다. `ApiControllerAdvice`가 `code`를 기존 방식으로 남긴다.

### 합의한 결정 (2026-09-21)
- 상한을 `Money`의 불변식으로 둔다. `Order`·`OrderLine`의 업무 규칙으로 두는 안은 같은 검사를 line 생성과 합계 계산 두 곳에 써야 하고, `payment`·`sync`가 `Money`를 쓸 때 같은 구멍이 다시 생겨서 버렸다. 컬럼 precision을 키우는 안은 도메인이 상한을 모르는 상태가 그대로라 더 큰 값에서 같은 고착이 생겨서 버렸다.

### 범위 조정 (2026-09-21, 개발 중 발견)
인수 조건 6의 전제가 틀렸다. `Money`에 상한을 넣는 것만으로는 고착이 닫히지 않는다.
- `OrderLine.amount`와 `Order.total`이 둘 다 lazy 계산(`get()`)이라 `Order.create`가 합계를 평가하지 않는다. 그래서 단가가 범위 안이고 합계만 넘는 주문은 여전히 `201`로 커밋된다. 확인한 결과 상한을 넣은 뒤에도 `201`이었다.
- 달라진 것은 checkout의 응답이다. 예전에는 DB가 거절해 `500 INTERNAL_ERROR`였고, 이제는 `Money`가 거절해 `400 INVALID_MONEY_AMOUNT`다. 진단 가능한 오류가 됐지만 **주문은 여전히 `OPEN`에 고착된다.**
- 그래서 `Order.create`가 생성 시점에 합계를 한 번 평가한다. 합계가 저장 가능한 범위인지는 주문 생성의 불변식이다. 이 평가가 없으면 "저장할 수 있는 주문만 만든다"가 성립하지 않는다.
- 인수 조건 6을 "주문 생성이 400으로 거절되고 행이 생기지 않는다"로 유지하되, 그 근거를 "합계 계산이 `Order.create` 안에서 일어나기 때문"에서 "`Order.create`가 합계를 명시적으로 평가하기 때문"으로 고친다.

## 제외
- 지수 표기 입력의 비용. `CreateOrderRequest`가 `BigDecimal.toPlainString()`으로 문자열을 만든 뒤 `Money.of`에 넘기므로, `1E+100000` 같은 입력은 이 PR 뒤에도 10만 자 문자열을 만든 다음 400으로 거절된다(지금은 500이다). 지수를 더 키웠을 때의 위험은 `Money.of(BigDecimal, Currency)` 오버로드를 만들어 DTO가 문자열을 거치지 않게 하는 별도 PR에서 닫는다. 그 PR이 요청 DTO를 바꾸므로 여기서 함께 하지 않는다.
- 소수 자릿수가 `-1`인 통화(`XAU`·`XDR`·`XXX`)를 `Money`가 받아들여 금액을 10 단위로 양자화하는 것. 통화 allow-list와 함께 다룰 별도 결함이다.
- `Money.of`가 `NumberFormatException`을 `DomainException`으로 감싸지 않는 것. 지금은 호출부가 `BigDecimal`을 넘겨 도달하지 않는다.
- `quantity`의 상한과 line 개수 상한. 금액 상한이 생기면 곱셈 결과가 걸리므로 고착은 닫힌다. 요청 크기 자체를 막는 것은 별도 주제다.
- 이미 저장된 데이터의 정리. 개별 금액은 컬럼 제약이 막아 왔지만 **합계는 막은 적이 없다.** 합계가 범위를 넘는 주문은 `OPEN`으로 커밋될 수 있었고, 그런 행이 남아 있는 DB에서는 이 변경 뒤에도 checkout이 `400 INVALID_MONEY_AMOUNT`를 돌려주며 주문은 `OPEN`에 남는다. 달라지는 것은 응답이 `500`에서 `400`으로 바뀌는 것뿐이다. `restore`는 합계를 평가하지 않으므로 조회와 주문 생성 재시도는 계속 동작한다. 배포된 환경이 없어서 지금 그런 행은 없다. 생기면 DB에서 직접 정리한다.

### 범위 조정 2 (2026-09-21, 제출 전 multi-review 반영)
새 오류 코드가 응답에 나가고 `foundation` 값 타입의 불변식이 바뀌므로 🔴이다. multi-review(Claude 89점, Codex 90점. Gemini는 CLI가 없어 미실행)를 돌렸고 둘 다 막는 결함은 없다고 판정했다. 상한 식에서 반례를 찾지 못했다는 것도 둘 다 같았다.
- **사실 오류를 고친다.** 두 리뷰어가 함께 짚었다. 제외 절의 "범위를 넘는 행이 저장된 적이 없다(컬럼 제약이 막아 왔다)"는 틀렸다. 컬럼 제약은 개별 금액만 막았고 합계는 막은 적이 없다. 위 제외 절을 고치고 [현재 상태](../wiki/current-state.md)의 알려진 빈틈에도 적는다.
- 상한 상수와 컬럼을 이어 붙이는 **왕복 테스트**를 더한다. 지금 테스트는 거절만 확인해서, 상한을 14로 잘못 적어도 단위 테스트만 고치면 통과한다. 정수부 15자리 주문이 실제로 저장되고 checkout까지 가는 것을 확인한다.
- `Order.create`의 `order.total` 한 줄에 이름을 준다(`requireStorableTotal`). 이 불변식은 지금 `create`에만 붙어 있어서, line을 바꾸는 명령이 생기면 그 경로에서도 불러야 한다는 계약이 이름과 KDoc으로 남는다.
- 주석의 "lazy 계산"은 부정확하다. `by lazy`가 아니라 접근할 때마다 계산하는 getter다. 문구를 고친다.
- `minus`와 여러 line의 합산 경계를 테스트로 더한다. 소수 자릿수가 4인 통화(`numeric(19,4)`의 한계와 정확히 같은 19자리)도 함께 고정한다.
- spec과 wiki가 "Jackson 역직렬화도 같은 검사를 거친다"를 사실로 적었는데 `Money`를 역직렬화하는 경로가 지금 없다. 열거에서 뺀다.

## 변경 경로
- src/main/kotlin/com/resilientpos/foundation/Money.kt
- src/main/kotlin/com/resilientpos/order/internal/domain/Order.kt
- src/test/kotlin/com/resilientpos/order/internal/domain/OrderTest.kt
- src/test/kotlin/com/resilientpos/foundation/MoneyTest.kt
- src/test/kotlin/com/resilientpos/order/internal/adapter/web/OrderApiIntegrationTest.kt
- docs/spec/2026-09-21-money-amount-limit.md
- docs/wiki/current-state.md
- docs/wiki/changes.md

## 인수 조건
1. 정수부가 15자리인 금액은 만들어진다. KRW `999999999999999`, USD `999999999999999.99`가 모두 통과한다.
2. 정수부가 16자리인 금액은 `DomainException`의 `code`가 `INVALID_MONEY_AMOUNT`, `kind`가 `INVALID`다. 메시지는 비어 있지 않고 코드와 다르다.
3. 검사는 `Money`를 만드는 모든 경로에서 걸린다. 생성자, `of`, `copy()`, `plus`, `minus`, `times`가 모두 거절한다.
4. 0과 음수는 자릿수만 보므로 영향을 받지 않는다. `Money.zero`, `-999999999999999`는 통과한다.
5. HTTP: 단가가 16자리인 주문 생성 요청은 `400 INVALID_MONEY_AMOUNT`이고 `t_order`에 행이 생기지 않는다.
6. HTTP: 단가 `999999999999999` 수량 2처럼 단가는 범위 안이고 합계만 넘는 요청도 주문 생성이 `400 INVALID_MONEY_AMOUNT`로 거절되고 `t_order`에 행이 생기지 않는다. `Order.create`가 생성 시점에 합계를 평가하기 때문이다. 재현했던 고착(`201` 뒤 checkout에서 `500`)이 더 이상 생기지 않는다.
7. 기존 테스트 96개가 그대로 통과한다.

## 검증
- 값 타입: `./gradlew test --tests 'com.resilientpos.foundation.MoneyTest'`
- HTTP 통합: `./gradlew test --tests 'com.resilientpos.order.internal.adapter.web.OrderApiIntegrationTest'` (Docker 필요)
- 전체 회귀: `./gradlew test`
- 하네스: `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전 `--worktree` 없이 한 번 더.
- 정상 시나리오: 인수 조건 1·4. 실패 시나리오: 2·3·5·6.

## Wiki 영향
- [현재 상태](../wiki/current-state.md): `Money`의 서술에 금액 범위 불변식과 근거를 더한다. 입력 거절 코드 수를 반영한다. 알려진 빈틈에 지수 표기 입력의 비용과 `-1` 자릿수 통화를 새로 적는다.
- [변경 기록](../wiki/changes.md): 이 spec 링크, 재현한 고착, 결정을 적는다.
- [도메인 모델](../wiki/domain-model.md): 무영향. 용어·aggregate 경계·스키마가 바뀌지 않는다.

## 분할 계획
예상 규모는 구현 약 15줄, 테스트 약 70줄, 문서 약 140줄로 총 225줄·6파일 내외다. 경고 한도(600줄·20파일) 안이다. 초과하면 HTTP 통합 테스트(인수 조건 5·6)를 줄인다. 이 PR과 같은 감사에서 나온 하네스 결함 다섯 개는 목표가 달라서 뒤따르는 별도 PR로 낸다.

## TDD 검증 기록

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 조건 1·2: `정수부가 15자리면 만들어지고 16자리면 거절한다` | 미실행 | 미실행 | 미실행 |
| 인수 조건 3: `연산 결과가 범위를 넘으면 거절한다` | 미실행 | 미실행 | 미실행 |
| 인수 조건 4: `0과 음수는 자릿수만 본다` | 미실행 | 미실행 | 미실행 |
| 인수 조건 5·6: `저장할 수 없는 금액의 주문은 400이고 행이 생기지 않는다` | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
