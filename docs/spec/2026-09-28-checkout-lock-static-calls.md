# PR spec: checkout 잠금의 트랜잭션 확인과 토큰 생성을 선언과 주입으로 바꾼다

## 목적
`RedisCheckoutLock.lockUntilTransactionEnds`는 메서드 안에서 정적 호출 두 개로 전제와 값을 얻는다.
`TransactionSynchronizationManager.isSynchronizationActive()`를 `check`해서 트랜잭션 밖 호출을 막고, `UUID.randomUUID()`로 잠금 토큰을 만든다.
트랜잭션 전제는 메서드 선언에서 보이지 않고, 토큰 생성은 저장소의 다른 식별자와 달리 주입된 `IdentifierGenerator`를 거치지 않는다.

기대 결과: 트랜잭션 전제는 `@Transactional(propagation = Propagation.MANDATORY)` 선언으로 드러나고, 토큰은 생성자로 주입한 `IdentifierGenerator.next()`로 만든다. 잠금 동작(획득·대기·fail-open·해제)은 바뀌지 않는다.

## 범위
소유 모듈은 `payment`다.

- `lockUntilTransactionEnds`의 `check(TransactionSynchronizationManager.isSynchronizationActive())`를 지우고 메서드에 `@Transactional(propagation = Propagation.MANDATORY)`를 붙인다.
  트랜잭션 밖에서 부르면 Spring이 `IllegalTransactionStateException`으로 거절한다. 유일한 호출자 `PaymentManagement.createAttempt`는 `CheckoutLock` 인터페이스로 주입받은 빈을 부르므로 프록시를 거친다. `PaymentManagement.authorize`가 `NEVER`로 반대 전제를 선언하는 것과 같은 방식이다.
- 잠금 토큰 `UUID.randomUUID()`를 생성자로 주입한 `foundation.IdentifierGenerator`의 `next()`로 바꾼다. 빈은 `PlatformConfiguration`의 UUID v7 구현이다. 토큰은 요청마다 달라야 할 뿐이라 v7이어도 해제 판정(토큰 일치)이 같다.
- `TransactionSynchronizationManager.registerSynchronization`은 그대로 둔다(검토한 대안 참고).

계약: `CheckoutLock` port와 Redis 키·TTL·대기 한도, 이벤트, 오류 code는 바뀌지 않는다. 트랜잭션 밖 호출의 예외 타입만 `IllegalStateException`에서 `IllegalTransactionStateException`으로 바뀐다. 두 경우 모두 프로그래밍 오류이고 이를 잡는 코드는 없다.

복구·관측성: 바뀌지 않는다.

## 제외
- `acquire` 대기 루프의 `System.nanoTime()`·`Thread.sleep`. 시간 주입은 이번 목표 밖이다.
- 다른 파일의 정적 호출. main 코드를 점검한 결과 이런 정적 호출은 `RedisCheckoutLock`에만 있고, `Clock`·`IdentifierGenerator`는 이미 주입 중이다.
- `CheckoutLock` 인터페이스 문서의 "트랜잭션 안에서만 부른다" 서술. 그대로 참이다.

## 검토한 대안
- **`registerSynchronization`을 감싸는 타입을 새로 선언해 주입**: 사용자가 래퍼 타입을 새로 두지 말라고 결정했다. 호출이 한 곳뿐이라 얻는 것도 없다.
- **`@TransactionalEventListener`로 해제**: 잠금마다 이벤트를 발행하면 Spring Modulith가 `EVENT_PUBLICATION`에 잠금마다 행을 남긴다. 정합성과 무관한 기록이 쌓이므로 기각했다.
- **`check`를 남기고 `MANDATORY`만 추가**: 같은 전제를 두 번 검사한다. 호출이 프록시를 거치므로 선언 하나로 충분하다.

## 관련 문서
[payment-advisory-lock](2026-09-26-payment-advisory-lock.md)이 이 잠금을 들였다.

## 변경 경로
- src/main/kotlin/com/resilientpos/payment/internal/adapter/lock/RedisCheckoutLock.kt
- src/test/kotlin/com/resilientpos/payment/internal/adapter/lock/RedisCheckoutLockTest.kt
- src/test/kotlin/com/resilientpos/payment/internal/application/PaymentAttemptCreationLockIntegrationTest.kt
- docs/spec/2026-09-28-checkout-lock-static-calls.md

## 인수 조건
1. 트랜잭션 밖에서 `CheckoutLock` 빈의 `lockUntilTransactionEnds`를 부르면 `IllegalTransactionStateException`이 난다.
2. `RedisCheckoutLockTest`의 기존 검증(Redis 오류면 잠그지 않고 돌아온다)이 생성자 인자만 맞춘 채 통과한다.
3. `PaymentAttemptCreationLockIntegrationTest`의 기존 검증(늦은 요청이 앞 요청의 시도를 돌려받는다)과 나머지 테스트가 깨지지 않는다.
4. `RedisCheckoutLock.kt`에 `isSynchronizationActive`와 `UUID.randomUUID` 호출이 없다.

## 검증
- `./gradlew test --tests 'com.resilientpos.payment.*'`로 좁혀 돌린 뒤 `./gradlew test` 전체를 돌린다. Docker가 필요하다.
- 실패 시나리오: `MANDATORY`가 빠지면 인수 조건 1의 테스트가 예외 없이 끝나 실패한다.
- `python3 scripts/check_pr.py --base 5eece85 --worktree`, 제출 전 `--worktree` 없이 한 번 더.

## Wiki 영향
없다. [도메인 모델](../wiki/domain-model.md)은 `CheckoutLock`을 조회 전에 거는 fail-open 잠금으로만 설명하고, 트랜잭션 전제를 검사하는 방식이나 토큰 생성은 적지 않는다. 요구사항 변화도 없다. 잠금의 동작과 계약이 그대로이고 구현 내부의 의존 방식만 바뀐다.

## 분할 계획
4개 파일, 추가와 삭제 합계 120줄 안쪽이다. 경고선에 이르지 않는다.

## TDD 검증 기록
업무 동작은 바뀌지 않는 리팩터링이다. 트랜잭션 밖 호출 거절은 새 통합 테스트로 RED→GREEN을 확인한다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 트랜잭션 밖에서 잠그면 거절한다 | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
