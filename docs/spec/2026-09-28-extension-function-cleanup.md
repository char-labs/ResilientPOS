# PR spec: 반복되는 호출을 확장 함수·멤버·공용 헬퍼로 줄인다

## 목적
같은 모양의 호출이 여러 곳에 복사돼 있다. 테스트 대기 루프가 9벌, 주문 생성 뒤 checkout 요청 헬퍼가 4벌 있고, `JdbcTemplate` 조회는 약 20곳에서 `X::class.java`를 넘긴다. 운영 코드도 금액을 0과 비교할 때마다 `Money.zero(currency)`를 만들고, `findById(id).orElse(null)`을 직접 푼다. 한 곳을 고치면 나머지가 어긋나고 읽는 사람이 같은 코드를 여러 번 확인해야 한다.

기대 결과: 두 곳 이상 반복되는 호출만 골라 한 곳으로 모은다. 업무 동작과 테스트가 검증하는 내용은 바뀌지 않는다.

## 범위
소유 모듈은 `foundation`, `order`, `payment`와 테스트 전반이다. 배치 원칙은 셋이다. 우리 값 객체는 멤버로, 외부 라이브러리 타입은 확장 함수로, 수신 객체가 필요 없는 것은 최상위 함수로 둔다. 이미 설치된 라이브러리 확장이 있으면 그것을 쓴다.

운영 코드
- `Money`에 `isZero`·`isNegative` 멤버를 두고 0과 비교하던 도메인 코드 3곳(`OrderLine` 단가 음수 확인, `PaymentAttempt.create`의 음수 확인, `approveIfFree`의 0원 확인)을 바꾼다. 값 객체라 확장이 아니라 멤버다. scale과 무관하게 부호로 판정한다.
- `OrderPersistenceAdapter`의 `findById(id).orElse(null)` 2곳을 spring-data에 있는 `findByIdOrNull`로 바꾼다.

테스트
- 대기 루프 `awaitUntil` 9벌을 테스트 공용 최상위 함수 하나(`Await.kt`)로 모은다. 발행 기록을 덧붙이던 변형은 실패 진단 인자로 흡수한다. 같은 루프를 인라인으로 쓴 2곳도 옮긴다.
- `JdbcTemplate` 조회를 spring-jdbc의 reified 확장(`queryForObject`·`queryForList`의 타입 인자 형태)으로 바꾸고, 여러 파일에 반복되는 주문 상태·checkout 행 수 조회는 테스트 확장 함수 `orderStatus`·`checkoutRowCount`로 둔다.
- 주문 생성 뒤 checkout을 요청하는 헬퍼 4벌과 준비 단계로만 쓰인 인접한 두 줄을 테스트 확장 `OrderCommandFacade.checkout`으로, 반복되는 line 생성을 `OrderFixtures.line`으로 모은다. checkout 요청 자체를 검증하는 주문 모듈 테스트의 호출은 그대로 둔다.
- `Order.restore`로 필드 일부만 바꾼 사본을 만드는 4곳을 테스트 확장 `Order.restored`로 모은다. 운영 코드에 두면 불변식을 우회하는 길이 되므로 테스트에만 둔다.
- 직접 만든 `DomainException` 단언(`assertFailsWithDomain`, `runCatching` 뒤 `assertIs`)을 kotlin.test의 `assertFailsWith`로 바꾼다.
- `ApplicationEvents`의 타입별 조회와 `MeterRegistry`의 발행 기록 metric 조회를 테스트 확장 함수로 둔다. 조회 방식이 다른 `MeterRegistry.get` 한 곳은 그대로 둔다.

계약: 운영 동작, 이벤트, 오류 code, 스키마는 바뀌지 않는다.

## 제외
- 도메인의 `if`·`throw`를 한 줄 함수로 바꾸는 것. 코드 전체의 스타일을 바꾸는 일이다.
- executor 정리를 `use`로 바꾸는 것. `close()`가 무기한 기다려 10초 대기 상한이 사라진다.
- Hibernate 문장 수 측정 헬퍼와 커밋 뒤 발행 헬퍼. 반복이 적어 얻는 것이 작다.
- 고정 식별자 생성기와 `PaymentCommand.CreateAttempt` fixture의 중복. 확장 함수가 아니라 fixture 정리라 따로 한다.

## 검토한 대안
- **`Money`의 0 비교를 확장 함수로 두기**: 우리 값 객체라 멤버가 자연스럽고, 확장이면 다른 파일에서 import해야 한다.
- **대기 루프를 `ApplicationEvents`나 `JdbcTemplate`의 확장으로 두기**: 기다리는 조건이 제각각이라 수신 객체가 없다. 최상위 함수가 맞다.
- **`Order` 사본 생성을 운영 코드의 `copy` 멤버로 두기**: 전이를 거치지 않고 상태를 바꾸는 길이 운영 코드에 생긴다.

## 관련 문서
[checkout-lock-static-calls](2026-09-28-checkout-lock-static-calls.md)에서 나눈 후속 PR이다. 그 PR 브랜치(`refactor/checkout-lock-static-calls`)를 base로 쌓는다.

## 변경 경로
- src/main/kotlin/com/resilientpos/foundation/Money.kt
- src/main/kotlin/com/resilientpos/order/internal/domain/OrderLine.kt
- src/main/kotlin/com/resilientpos/payment/internal/domain/PaymentAttempt.kt
- src/main/kotlin/com/resilientpos/order/internal/adapter/persistence/command/OrderPersistenceAdapter.kt
- src/test/kotlin/com/resilientpos/**
- docs/spec/2026-09-28-extension-function-cleanup.md

## 인수 조건
1. `Money.isZero`·`isNegative`는 scale과 무관하게 부호로 판정한다(`0.00`은 0, `-1`은 음수, `0`은 음수가 아니다).
2. main 코드에 `Money.zero(...)`와의 비교와 `findById(...).orElse(null)`이 없다.
3. 테스트에 `private fun awaitUntil`, `assertFailsWithDomain`, `runCatching` 뒤 `DomainException` 단언, `JdbcTemplate` 조회의 `X::class.java` 인자가 없다.
4. 기존 테스트가 검증 내용을 바꾸지 않고 모두 통과한다.

## 검증
- `./gradlew test` 전체를 돌린다. Docker가 필요하다.
- 인수 조건 2·3은 `grep`으로 확인한다.
- `python3 scripts/check_pr.py --base refactor/checkout-lock-static-calls --worktree`, 제출 전 `--worktree` 없이 한 번 더.

## Wiki 영향
없다. wiki는 테스트 헬퍼 이름이나 `Money`의 비교 방식을 적지 않는다. 요구사항 변화도 없다. 반복 호출을 모으는 정리이고 동작이 같다.

## 분할 계획
35개 파일, 추가와 삭제 합계 620줄 안쪽이다. 경고선(900줄)에 이르지 않는다. 앞 PR이 squash merge되면 `git rebase --onto origin/main` 으로 이 PR의 커밋만 main 위로 옮긴다. 앞 PR의 최종 내용 위에 쌓았으므로 충돌하지 않는다.

## TDD 검증 기록
운영 동작은 바뀌지 않는 정리다. 새 멤버 `Money.isZero`·`isNegative`만 테스트를 먼저 쓴다. 테스트 정리는 기존 테스트가 그대로 통과하는지로 확인한다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 0인지와 음수인지를 scale과 무관하게 판정한다 | `./gradlew compileTestKotlin`, `isZero`가 없어 테스트 컴파일 실패 | `./gradlew test --tests 'com.resilientpos.foundation.MoneyTest'`, 멤버를 두고 통과 | `./gradlew test`, 253개 통과 |

## 결과
개발 전 spec을 독립 커밋으로 작성했다. 구현 후 아래를 확인했다.

- 테스트 대기 루프 9벌과 인라인 2곳을 `awaitUntil` 하나로 모았다. `JdbcTemplate` 조회는 reified 확장과 `orderStatus`·`checkoutRowCount` 확장, 주문 준비는 `OrderCommandFacade.checkout`·`OrderFixtures.line`, 사본은 `Order.restored`, 단언은 `assertFailsWith`, 이벤트·metric 조회는 확장 함수로 바꿨다.
- 운영 코드는 `Money.isZero`·`isNegative`(0 비교 3곳)와 `findByIdOrNull`(2곳)이다.
- 인수 조건 2·3을 `grep`으로 확인했다. main의 `Money.zero`는 합계 fold의 시작값 1곳만 남았다(비교가 아니다).
- `./gradlew test`: 253개 통과, 실패 0.
