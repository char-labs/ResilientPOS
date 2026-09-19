# PR spec: foundation 도메인 예외 DomainException

## 목적
도메인 규칙 위반을 JDK 범용 예외와 구분되는 전용 타입으로 던진다. 지금은 `Order`·`OrderLine`·`Money`가 `require(...)`로 `IllegalArgumentException`을 던진다. 이 예외는 JDK와 라이브러리도 던지므로, 다음 PR의 web advice가 이것을 400으로 바꾸면 버그에서 나온 예외까지 400이 되고 내부 메시지가 응답에 노출된다. 전용 타입이 있으면 advice는 그 타입만 클라이언트 오류로 바꾸고 나머지는 500으로 둔다.

## 범위
- `foundation`에 `DomainException(code: String, message: String) : RuntimeException(message)`을 추가한다. 프레임워크 의존성이 없다.
  - `code`는 API 오류 응답과 로그 `errorCode`에 그대로 쓰는 안정적인 식별자다(`EMPTY_ORDER` 등). 기존 메시지 상수의 값을 그대로 쓴다.
  - `message`는 사람이 읽는 한국어 설명이다. 규칙을 던지는 자리에 같이 적는다. advice가 코드→문구 표를 따로 가지면 규칙과 문구가 다른 파일로 갈라지기 때문이다.
- `Order.create`, `OrderLine` 생성, `Money`의 `require(...)`와 `throw IllegalArgumentException(...)`을 `DomainException`으로 바꾼다. 검증 조건과 코드 값은 바꾸지 않는다.
- 소유 모듈: `foundation`(타입), `order`(사용처). 계약: `DomainException`의 public API와 기존 코드 값 5개(`EMPTY_ORDER`, `CURRENCY_MISMATCH`, `INVALID_QUANTITY`, `NEGATIVE_UNIT_PRICE`, `INVALID_MONEY_SCALE`). 불변식: 도메인 규칙 위반은 `DomainException`으로만 나간다.
- 복구·관측성: 예외 타입 교체라 재시도·복구 경로가 생기지 않는다. `code`를 로그 속성 `errorCode`로 남기는 일은 advice가 생기는 다음 PR이 맡는다.

## 제외
- web advice, 오류 응답 envelope `{code, message}`, HTTP 상태 매핑. 다음 PR `order-create-order-api`.
- 코드별 하위 예외 타입이나 sealed 계층, 오류 코드 enum. 코드가 문자열 상수 5개인 동안 만들지 않는다.
- 메시지 다국어(`MessageSource`). 단말이 `code`로 문구를 고르는 요구가 생기면 다룬다.
- 같은 `(terminalId, clientOrderId)`에 다른 payload가 오는 경우의 `IDEMPOTENCY_CONFLICT`. 후속 PR `order-idempotency-conflict`.

## 관련 문서
없음. 선행 PR은 [order-create-order-persistence](2026-09-16-order-create-order-persistence.md), 예외가 web 응답으로 이어지는 근거는 [테스트 전략](../wiki/testing-strategy.md)의 Controller 절이다.

## 변경 경로
- src/main/kotlin/com/resilientpos/foundation/DomainException.kt
- src/main/kotlin/com/resilientpos/foundation/Money.kt
- src/main/kotlin/com/resilientpos/order/internal/domain/Order.kt
- src/main/kotlin/com/resilientpos/order/internal/domain/OrderLine.kt
- src/test/kotlin/com/resilientpos/foundation/MoneyTest.kt
- src/test/kotlin/com/resilientpos/order/internal/domain/OrderTest.kt
- src/test/kotlin/com/resilientpos/order/internal/application/OrderCommandFacadeIntegrationTest.kt
- docs/spec/2026-09-19-foundation-domain-exception.md
- docs/wiki/current-state.md
- docs/wiki/changes.md

## 인수 조건
1. line이 없는 주문 생성은 `DomainException`으로 거절되고 `code`가 `EMPTY_ORDER`다.
2. line 통화가 주문 통화와 다르면 `code`가 `CURRENCY_MISMATCH`인 `DomainException`으로 거절된다.
3. 수량이 1 미만이면 `INVALID_QUANTITY`, 단가가 음수면 `NEGATIVE_UNIT_PRICE`인 `DomainException`으로 거절된다.
4. `Money`의 scale 초과 입력은 `INVALID_MONEY_SCALE`, 통화가 다른 연산은 `CURRENCY_MISMATCH`인 `DomainException`으로 거절된다.
5. 모든 `DomainException`의 `message`는 비어 있지 않고 `code`와 다른 문구다.
6. `OrderCommandFacade.create`가 불변식 위반 command를 받으면 `DomainException`이 호출자까지 전파되고 주문이 저장되지 않는다.
7. `src/main/kotlin`에 도메인 규칙용 `require(`와 `IllegalArgumentException`이 남지 않는다.
8. `DependencyDirectionTest`와 `ModuleStructureTest`가 그대로 통과한다.
9. `python3 scripts/check_pr.py --base origin/main`이 통과한다.

## 검증
```sh
./gradlew test
grep -rn "require(\|IllegalArgumentException" src/main/kotlin
python3 scripts/check_pr.py --base origin/main --worktree
```
- 정상: 기존 정상 경로 테스트가 수정 없이 통과한다.
- 실패: 인수 조건 1~6의 거절 사례가 `DomainException`과 지정한 `code`로 실패하는지 확인한다. `grep`은 결과가 없어야 한다.

## Wiki 영향
- [현재 상태](../wiki/current-state.md): `foundation` 항목에 `DomainException`을 추가하고 테스트 수를 갱신한다.
- [변경 기록](../wiki/changes.md): 이 spec 링크와 결정 근거를 남긴다. 제품 요구사항은 바뀌지 않는다.

## 분할 계획
예상 규모: 구현 약 40줄, 테스트 수정 약 50줄, 문서 약 90줄로 총 180줄·10파일 내외. 경고 한도(600줄·20파일) 안이다.
후속 순서는 `order-create-order-api`(REST Controller, 오류 envelope, REST Docs) → `order-idempotency-conflict` → `RequestCheckout`이다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 개발 전에 테스트 의도를 적고 실행 후 관측 결과를 기록 | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
