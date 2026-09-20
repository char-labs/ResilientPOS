# PR spec: foundation 도메인 예외 DomainException

## 목적
도메인 규칙 위반을 JDK 범용 예외와 구분되는 전용 타입으로 던진다. 지금은 `Order`·`OrderLine`·`Money`가 `require(...)`로 `IllegalArgumentException`을 던진다. 이 예외는 JDK와 라이브러리도 던지므로, 다음 PR의 web advice가 이것을 400으로 바꾸면 버그에서 나온 예외까지 400이 되고 내부 메시지가 응답에 노출된다. 전용 타입이 있으면 advice는 그 타입만 클라이언트 오류로 바꾸고 나머지는 500으로 둔다.

## 범위
- `foundation`에 `DomainException(code: String, message: String) : RuntimeException(message)`을 추가한다. `message`는 non-null로 다시 선언해 호출자가 `!!` 없이 쓴다. 프레임워크 의존성이 없다.
  - `code`는 API 오류 응답과 로그 `errorCode`에 그대로 쓰는 안정적인 식별자다(`EMPTY_ORDER` 등). 기존 메시지 상수의 값을 그대로 쓴다.
  - `message`는 사람이 읽는 한국어 설명이다. 규칙을 던지는 자리에 같이 적는다. advice가 코드→문구 표를 따로 가지면 규칙과 문구가 다른 파일로 갈라지기 때문이다.
- `Order.create`, `OrderLine` 생성, `Money`의 `require(...)`와 `throw IllegalArgumentException(...)`을 `DomainException`으로 바꾼다. 검증 조건과 코드 값은 바꾸지 않는다.
- `platform.ApiControllerAdvice`에 `DomainException` handler를 추가한다. 상태는 400이고 본문은 `ApiResponse.fail(ErrorResponse(errorCode = code, message = message))`다. `DomainException`의 `message`는 응답에 내보내려고 쓴 문구라서 그대로 나간다. 그 밖의 예외는 [api-response-advice](2026-09-19-api-response-advice.md)가 정한 대로 `ErrorType`의 고정 문구만 나간다. 로그는 `WARN`으로 `errorCode`와 함께 남긴다(아래 범위 조정 참고).
- 소유 모듈: `foundation`(타입), `order`(사용처), `platform`(HTTP 매핑). 계약: `DomainException`의 public API와 기존 코드 값 5개(`EMPTY_ORDER`, `CURRENCY_MISMATCH`, `INVALID_QUANTITY`, `NEGATIVE_UNIT_PRICE`, `INVALID_MONEY_SCALE`). 불변식: 도메인 규칙 위반은 `DomainException`으로만 나간다.
- 복구·관측성: 예외 타입 교체라 재시도·복구 경로가 생기지 않는다. advice가 도메인 거절마다 `errorCode={code}` 로그를 `WARN`으로 남긴다. 구조화 로그 속성은 Controller PR에서 붙인다.

## 제외
- REST Controller, 요청 DTO, validation, REST Docs. 다음 PR `order-create-order-api`. 응답 envelope와 공통 오류는 #22 [api-response-advice](2026-09-19-api-response-advice.md)가 이미 정했다.
- 코드별 HTTP 상태 구분(404·409 등). 지금 코드 5개는 모두 입력 거절이라 400 하나로 충분하다. 상태가 갈리는 첫 코드(`IDEMPOTENCY_CONFLICT`)가 생길 때 다룬다.
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
- src/main/kotlin/com/resilientpos/platform/ApiControllerAdvice.kt
- src/test/kotlin/com/resilientpos/platform/ApiAdviceTest.kt
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
10. Controller에서 `DomainException`이 나오면 응답은 400이고 `result`가 `FAIL`, `error.errorCode`가 예외의 `code`, `error.message`가 예외의 `message`다.
11. `DomainException`이 아닌 예외는 #22와 같이 500 `INTERNAL_ERROR`이고 예외 메시지가 응답에 나가지 않는다(기존 `ApiAdviceTest` 회귀).

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
예상 규모: 구현 약 50줄, 테스트 수정 약 70줄, 문서 약 110줄로 총 230줄·12파일 내외. 경고 한도(600줄·20파일) 안이다.
후속 순서는 `order-create-order-api`(REST Controller, 오류 envelope, REST Docs) → `order-idempotency-conflict` → `RequestCheckout`이다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| `Money.of`/연산 거절이 `DomainException`을 던진다 — `MoneyTest.통화 자릿수를 넘는 소수 입력을 거절한다`, `통화가 다른 금액은 더하지 않는다`, `통화가 다른 금액은 비교하지 않는다` | `./gradlew test --tests 'com.resilientpos.foundation.MoneyTest'` → `AssertionFailedError: Expected an exception of class com.resilientpos.foundation.DomainException to be thrown, but was java.lang.IllegalArgumentException: CURRENCY_MISMATCH`(및 `INVALID_MONEY_SCALE`) | 같은 명령 → BUILD SUCCESSFUL, 7 tests 통과 | `./gradlew test` → BUILD SUCCESSFUL |
| `Order.create` 거절이 `DomainException`을 던진다 — `OrderTest.빈 line을 EMPTY_ORDER로 거절한다`, `0 이하 수량과 음수 단가를 거절한다`, `주문 통화와 다른 단가를 CURRENCY_MISMATCH로 거절한다` | `./gradlew test --tests 'com.resilientpos.order.internal.domain.OrderTest'` → `AssertionFailedError: Expected an exception of class com.resilientpos.foundation.DomainException to be thrown, but was java.lang.IllegalArgumentException: EMPTY_ORDER`(및 `INVALID_QUANTITY`/`NEGATIVE_UNIT_PRICE`/`CURRENCY_MISMATCH`) | 같은 명령 → BUILD SUCCESSFUL, 6 tests 통과 | `./gradlew test` → BUILD SUCCESSFUL |
| `OrderCommandFacade.create`가 불변식 위반 command에 `DomainException`을 전파하고 저장하지 않는다 — `OrderCommandFacadeIntegrationTest.도메인이 거절한 명령은 행을 남기지 않는다` | 별도 RED 미실행: `MoneyTest`/`OrderTest`에서 이미 `IllegalArgumentException → DomainException` 전환을 RED로 확인했고, 이 테스트는 같은 예외 타입 교체를 상위에서 재검증하는 회귀 성격이라 production 교체 전 별도 실행은 생략 | `./gradlew test` (Docker 실행 중, Testcontainers Postgres 사용) → BUILD SUCCESSFUL | `./gradlew test` → BUILD SUCCESSFUL, 32 tests 전체 통과, 0 failed |
| 도메인 규칙 위반이 400과 예외의 code·message로 나간다 — `ApiAdviceTest.도메인 규칙 위반은 예외의 code와 message를 담은 400이다` | `./gradlew test --tests 'com.resilientpos.platform.ApiAdviceTest'` → `AssertionError: Status expected:[400] but was:[500]`(7 tests, 1 failed) | 같은 명령 → BUILD SUCCESSFUL, 7 tests 통과 | `./gradlew test` → BUILD SUCCESSFUL, 39 tests 전체 통과, 0 failed |
| `grep -rn "require(\|IllegalArgumentException" src/main/kotlin` | 대상 아님(테스트 아님) | 실행 결과 없음(빈 출력) 확인 | 동일 |

## 결과
`DomainException(code, message)`을 `foundation`에 추가하고 `Money`·`Order`·`OrderLine`의 `require(...)`와 `IllegalArgumentException`을 모두 `DomainException`으로 교체했다. `code`는 기존 상수 값을 그대로 쓰고 `message`에는 규칙별 한국어 문장을 붙였다. 테스트는 기대 예외 타입을 `DomainException`으로 바꾸고 코드 값 비교 대상을 `failure.message`에서 `failure.code`로 옮겼다. 인수 조건 5는 코드 5개의 거절 사례마다 `message`가 비어 있지 않고 `code`와 다르다는 assertion으로 검증한다.

`Money.of`가 `ArithmeticException`을 감싸던 자리는 cause를 넘기지 않는다. `INVALID_MONEY_SCALE` 코드가 원인을 이미 말해 주므로 `DomainException`에 cause 파라미터를 두지 않았다.

main rebase 뒤 `ApiControllerAdvice`에 `DomainException` handler를 추가했다(아래 범위 조정). `./gradlew test`는 39개 테스트가 모두 통과했다(실패 0, Docker·Testcontainers PostgreSQL 사용. #22의 advice 테스트 6개와 이 PR의 1개 포함). `DependencyDirectionTest`와 `ModuleStructureTest`도 같은 실행에서 통과했다. `grep -rn "require(\|IllegalArgumentException" src/main/kotlin`은 결과가 없다. [현재 상태](../wiki/current-state.md)의 테스트 수를 39개(API advice 7)로 고쳤다.

### 범위 조정 - main rebase와 DomainException handler 추가 (개발 후)
개발 도중 main에 #22 [api-response-advice](2026-09-19-api-response-advice.md)가 머지되어 이 브랜치를 main 위로 rebase했다. 충돌은 `docs/wiki/changes.md` 한 곳이었고 두 항목을 모두 남겼다. spec 단독 첫 커밋은 그대로 첫 커밋이다.

#22가 응답 계약을 먼저 정했으므로 이 spec이 전제하던 후속 계약을 고친다.
- 오류 응답은 자체 envelope `{code, message}`이 아니라 `ApiResponse`의 `error{errorCode, message}`다.
- 형식 오류 코드는 `INVALID_REQUEST`가 아니라 `ErrorType.BAD_REQUEST`다.
- 전역 advice를 새로 만들지 않고 `platform.ApiControllerAdvice`에 handler를 더한다.

#22의 spec은 `DomainException` handler를 `order-create-order-api`에서 추가한다고 적었다. 그때는 두 타입이 서로 다른 브랜치에 있었기 때문이다. rebase 뒤에는 한 브랜치에 같이 있고, handler가 없으면 이 PR의 목적(도메인 거절만 클라이언트 오류로 구분)이 코드로 확인되지 않는다. handler와 테스트는 약 25줄이라 이 PR에 넣고, Controller PR은 HTTP 매핑과 문서화에만 집중하게 한다.
