# PR spec: Money가 받아들이는 입력의 경계를 닫는다

## 목적
`Money`가 외부 입력을 받는 두 지점에서 세 가지 구멍을 닫는다. 지수 표기 금액이 CPU를 수백 ms 잡는 것, 소수 자릿수가 없는 통화가 금액을 조용히 반올림하는 것, 숫자가 아닌 문자열이 도메인 예외가 아닌 날것의 예외로 새는 것이다. 셋 다 `Money.of`의 검사 순서 하나에서 비롯된다.

## 범위
소유 모듈은 `foundation`이다. `Money`는 도메인 값이라 `order`·`payment`·`sync`가 모두 공유한다. 지금 세 곳 중 `order`만 있으므로 `order`의 요청 경로 하나만 함께 고친다.

**불변식을 지키는 순서를 바꾼다.** 지금 `Money.of`는 입력을 먼저 canonical scale로 맞추고(`setScale`) 그다음 불변식을 본다. 값을 펴는 일이 검사보다 앞에 있어서, 거절할 입력에도 펴는 비용을 먼저 치른다. 순서를 뒤집어 **값을 펴기 전에 메타데이터만 보고 판정**한다. `BigDecimal.precision()`과 `scale()`은 이미 가지고 있는 정수 두 개라 입력 크기와 무관하다.

**통화 자릿수를 한 곳에서 판정한다.** `Currency.getDefaultFractionDigits()`는 금액 개념이 없는 통화 코드(`XAU`, `XDR`, `XXX`)에 `-1`을 돌려준다. `Money`는 이 값을 canonical scale로 그대로 쓰므로, `-1`이면 금액이 10 단위로 양자화된다. 이런 통화로는 `Money`의 불변식("통화 표준 자릿수를 canonical scale로 유지한다")을 세울 수 없으므로 값을 만들지 않고 거절한다. 판정은 생성자 `init`과 `of`가 같은 함수를 쓴다.

**문자열 파싱 실패를 도메인 예외로 바꾼다.** `Money.of(String, Currency)`는 `foundation`의 공개 API다. 지금은 숫자가 아닌 문자열에 `NumberFormatException`이 그대로 올라가고, web advice는 `DomainException`만 클라이언트 오류로 바꾸므로 500이 된다.

**요청 DTO가 문자열을 거치지 않게 한다.** `CreateOrderLineRequest.unitPrice`는 이미 `BigDecimal`인데 `toPlainString()`으로 문자열을 만든 뒤 다시 파싱한다. `BigDecimal`을 받는 `of` 오버로드를 더해 왕복을 없앤다.

계약: 새 `DomainException` code 두 개가 응답 본문의 `code`로 나간다. 둘 다 `DomainErrorKind.INVALID`라서 400이다.

| code | 의미 |
|---|---|
| `UNSUPPORTED_CURRENCY` | 금액을 표현할 수 없는 통화다. 언제 보내도 거절된다. |
| `INVALID_MONEY_FORMAT` | 금액 문자열이 숫자가 아니다. |

복구: 셋 다 요청 자체가 잘못된 경우라 재시도로 풀리지 않는다. 클라이언트가 값을 고쳐 다시 보내는 것이 유일한 경로다. 관측성은 기존 advice의 `WARN` 로그를 그대로 쓴다.

## 제외
- 매장이 어떤 통화를 받을지 정하는 allow-list. 이 PR은 `Money`가 표현할 수 없는 통화만 거절하고, 표현할 수 있는 통화 중 무엇을 영업에 쓸지는 정하지 않는다.
- line 개수와 요청 본문 크기의 상한, 커넥션·쿼리·트랜잭션 타임아웃, 로그 속성. 같은 감사에서 나왔고 뒤따르는 PR에서 닫는다.
- `quantity`의 상한.
- 응답 본문에 어떤 금액 형식을 쓸지. 지금 `order`의 응답에는 금액이 없다.

## 관련 문서
없음

## 변경 경로
- src/main/kotlin/com/resilientpos/foundation/Money.kt
- src/main/kotlin/com/resilientpos/order/internal/adapter/web/CreateOrderRequest.kt
- src/test/kotlin/com/resilientpos/foundation/MoneyTest.kt
- src/test/kotlin/com/resilientpos/order/**
- docs/**

## 인수 조건
1. 단가 `1E+100000`으로 주문을 만들면 400 `INVALID_MONEY_AMOUNT`이고, `Money.of`가 값을 펴지 않는다. 같은 입력 1000회 반복이 1초 안에 끝난다.
2. `currency`가 `XAU`인 주문 요청은 400 `UNSUPPORTED_CURRENCY`다. `Money.of("100", XAU)`도 같은 예외다.
3. `Money.of("abc", KRW)`는 `INVALID_MONEY_FORMAT`을 가진 `DomainException`이다. `NumberFormatException`이 밖으로 나가지 않는다.
4. 정수부 15자리, 소수 자릿수가 통화와 같은 금액은 그대로 통과한다. 기존 테스트가 하나도 깨지지 않는다.
5. `Money(BigDecimal, XAU)`를 생성자로 직접 만들어도 거절된다. `of`만 막으면 `copy`가 우회한다.

## 검증
- `./gradlew test` — 전부 통과.
- `python3 scripts/check_pr.py --base origin/main --worktree` — PASS.
- 반복 비용은 테스트 안에서 잰다. 시간을 단언하는 테스트는 머신에 따라 흔들리므로, 1000회 반복에 넉넉한 상한 1초를 두고 실패하면 비용이 돌아온 것으로 읽는다.

## Wiki 영향
- [현재 상태](../wiki/current-state.md) — 알려진 빈틈에서 닫은 세 항목을 지운다.
- [변경 이력](../wiki/changes.md) — 항목 추가.
- 요구사항 변화: 입력 규칙이 두 개 늘었다. 표현할 수 없는 통화와 숫자가 아닌 금액 문자열을 거절한다. 요청·응답의 모양과 스키마는 바뀌지 않는다.

## 분할 계획
예상 2개 파일 / 약 60줄 + 테스트. 경고 한도(600줄·20파일) 안이다. 같은 감사에서 나온 운영 설정과 관측성은 목표가 달라 처음부터 나눈다.

## TDD 검증 기록

RED는 두 단계로 관측했다. 새 입구 `of(BigDecimal, Currency)`는 존재하지 않아 컴파일이 먼저 실패했으므로(`Argument type mismatch: actual type is 'BigDecimal', but 'String' was expected.` 6건), **현재 동작 그대로인 위임 한 줄**(`of(amount.toPlainString(), currency)`)을 먼저 넣어 동작 실패를 보이게 했다. 그 상태의 `./gradlew test --tests 'com.resilientpos.foundation.MoneyTest'`는 **120초 안에 끝나지 않았다.** 1000회 반복 × 375ms이므로 6분 넘게 걸리는 것이 맞고, 이것이 비용 결함의 RED다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 지수 표기 금액을 값을 펴지 않고 거절한다 | `./gradlew test --tests '…MoneyTest'` — 120초 초과로 종료하지 못함. 한 번 거절에 375ms | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| 지수 표기를 쓴 소수도 값을 펴지 않고 거절한다 | 같은 실행에 묶여 미도달 | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| 소수 자릿수가 없는 통화로는 금액을 만들지 않는다 | 같은 실행에 묶여 미도달. 위임 상태에서 `of("100", XAU)`는 `1.0E+2`를 돌려줌(사전 실측) | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| 소수 자릿수가 없는 통화는 생성자와 zero로도 우회할 수 없다 | 같은 실행에 묶여 미도달 | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| 숫자가 아닌 금액 문자열은 도메인 예외다 | 같은 실행에 묶여 미도달. 위임 상태에서 `of("abc", KRW)`는 `NumberFormatException`(사전 실측) | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| BigDecimal 입구가 문자열 입구와 같은 값을 만든다 | 컴파일 실패 | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| 표현할 수 없는 통화로 온 주문 요청은 400이다 | `./gradlew test --tests '…OrderControllerTest'` — 컴파일 실패 | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| 지수 표기 단가로 온 주문 요청은 400이다 | 같은 명령 — 컴파일 실패 | 같은 명령 — 통과 | `./gradlew test` — 통과 |

## 결과
`MoneyTest` 17개가 **0.064초**에 끝난다. RED에서 같은 스위트가 120초를 넘겨 끝나지 않던 것과 대비된다. 반복 2000회(1000회 × 테스트 2개)를 포함한 값이므로 거절 한 번의 비용은 μs 단위이고, 지수 크기와 무관하다.

전체 `./gradlew test`는 111개 통과다(기존 103 + 신규 8).

인수 조건 5번(생성자 우회)은 실제로 필요했다. `of`에만 통화 판정을 두면 `Money(BigDecimal("1.0E+2"), XAU)`와 `zero(XAU)`가 그대로 통과한다. 판정을 `fractionDigitsOf`로 빼 `init`이 쓰게 해서 닫았다.

`stripTrailingZeros()`를 쓴 이유를 남긴다. 판정을 `amount.scale()`로 직접 하면 `1000.0000`(KRW)처럼 뒤따르는 0만 있는 입력이 거절된다. 0을 떼면 유효 자릿수가 드러나고, 떼는 비용은 `precision()`에 비례해 지수 표기에서는 한 자리다. canonical 값도 원본이 아닌 뗀 값에서 만들어야 `setScale`이 큰 `BigInteger`를 만들지 않는다.

`stripTrailingZeros()`가 비싸지는 입력이 도달 가능한지 확인했다. 자릿수가 실제로 적힌 리터럴에서는 비싸다 — 100만 자리에 1.4초가 걸린다. 그러나 **Jackson이 숫자 길이를 1000자로 제한한다**(`StreamReadConstraints.maxNumberLength`, 리터럴과 문자열 양쪽 모두 `StreamConstraintsException`으로 거절). 1000자리에서 `stripTrailingZeros()`는 0ms였다. 이 상한을 올리는 변경은 이 비용을 되살리므로 함께 본다.

이전 구현과 판정이 갈리는 입력이 있는지 따로 대조했다. 경계값 33종(`999999999999999`, `1000000000000000`, `0E+10`, `1000.0000`, `0.00`, 음수, `1E+15`/`1E+16` 등) × 통화 자릿수 3종과 무작위 30만 건에서 **불일치 0건**이다. 새 순서가 기존 `MAX_INTEGER_DIGITS` 계약과 같은 집합을 거절한다.

이 대조가 처음에는 결함을 덮었다. 지수를 극단으로 키운 입력을 넣지 않았기 때문이다. **`precision() - scale()`을 `Int`로 계산하면 넘친다.** `1E+2147483647`은 `precision = 1`, `scale = -2147483647`이라 차가 `Int.MIN_VALUE`로 넘쳐 상한 검사를 통과한다. 13자짜리 입력이라 Jackson의 1000자 제한도 지나간다. 그 상태로 작성했던 첫 구현은 뒤이은 `setScale`에서 `ArithmeticException`이 도메인 밖으로 새어 **500**이 됐다. main은 `setScale`을 `try/catch`로 감싸고 있어 같은 입력에 400을 돌려주므로, 이대로 두면 회귀였다. 자릿수 계산을 `Long`으로 바꿔(`BigDecimal.integerDigits()`) 닫고 회귀 테스트를 남겼다.

그 결과 이전 구현과 갈리는 입력이 이 군(群) 하나 남는다. 지수가 `Int` 범위를 넘게 만드는 입력에서 **main은 `INVALID_MONEY_SCALE`, 이 PR은 `INVALID_MONEY_AMOUNT`**를 돌려준다. 둘 다 400이고, 바뀐 쪽이 의미에 맞는다 — scale이 문제가 아니라 금액이 저장 범위를 넘은 것이다. main의 코드는 삼킨 `ArithmeticException`을 scale 오류로 뭉뚱그리고 있었다.

두 가드를 지난 뒤 `setScale`이 실패할 수 있는지도 확인했다. 첫 가드가 통과한 값은 scale이 통화 자릿수 이하임이 보장되므로 남는 변환은 scale을 늘리는 쪽뿐이고, 이는 0을 붙이는 일이라 `UNNECESSARY`로도 실패하지 않는다. main에 있던 `try/catch`는 그래서 뺐다.

검토: 제출 전 multi-review를 실행했다(Codex 81점, Gemini는 CLI가 없어 미실행). 막는 결함은 없다는 판정이다. 반영한 지적 세 건을 적는다.

1. 시간을 단언하는 테스트가 느린 CI에서 흔들릴 수 있다. 상한을 1초에서 5초로 올렸다. 검사 순서가 되돌아가면 1000회가 6분이 되므로 상한을 키워도 회귀는 그대로 잡힌다.
2. `stripTrailingZeros()`의 비용이 Jackson의 숫자 길이 제한에 기대고 있는데 그 의존이 코드 어디에도 없다. 상수를 단언하는 대신 **요청 경로로 고정**했다. 1001자리 `unitPrice`를 보내면 본문을 읽는 단계에서 400으로 끊기는지 보는 web 슬라이스 테스트를 더했다. 제한을 올리는 변경은 이 테스트를 깨뜨린다.
3. `zero`가 `BigDecimal.ZERO`를 쓰도록 바뀌었는데 `of("0")`과 같은 값을 만드는지 검증이 없다. scale까지 비교하는 테스트를 더했다.

반영하지 않은 지적 한 건을 적는다. `setScale`을 `try/catch`로 감싸자는 제안은 받지 않았다. 두 가드를 지난 값은 scale을 늘리는 변환만 남아 실패할 수 없고, 일어날 수 없는 경로에 처리를 두면 다음 사람이 그 경로가 가능하다고 읽는다. 이유를 코드 주석에 남겼다.
