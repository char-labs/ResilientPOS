# PR spec: Redis 분산 락을 platform 공용 블록으로 옮기고 주문 생성에 쓴다

## 목적
Redis 분산 락이 `payment` 안의 `CheckoutLock`/`RedisCheckoutLock`에만 있다. 주문 생성도 같은 락이 필요하다. 같은 `(terminalId, clientOrderId)` 요청 두 개가 동시에 오면 둘 다 기존 주문을 찾지 못하고, unique `(terminal_id, client_order_id)`가 한쪽을 거절한다. 그러면 진 쪽은 기존 결과 대신 오류를 받는다([order-idempotency-conflict](2026-09-20-order-idempotency-conflict.md) 제외 절의 알려진 빈틈). 잠글 행이 아직 없어서 행 잠금으로는 풀 수 없다. 이 락을 `order`에 한 벌 더 복사하면 두 구현이 따로 바뀐다.

지금의 호출 모양에도 문제가 있다. `lockUntilTransactionEnds`는 부른 줄부터 트랜잭션이 끝날 때까지 잠근다. 그래서 락이 어디까지 잡히는지 코드에 드러나지 않고, 바깥 트랜잭션에 합류하면 범위가 더 넓어진다.

기대 결과:
- 분산 락은 platform의 `Lock.executeWithLock(key) { }` 블록 하나로만 건다.
- 블록이 곧 락 범위다. 블록 안의 일은 새 트랜잭션(`REQUIRES_NEW`)에서 돌고, 커밋한 뒤에 락이 풀린다.
- 같은 키로 동시에 온 주문 생성 요청은 앞 요청의 커밋을 기다렸다가 기존 주문을 받는다.
- 결제 시도 생성의 동작은 그대로다.

## 범위
소유 모듈은 `platform`, `order`, `payment`다.

**platform: 공용 락**
- port `DistributedLock`을 둔다. `tryAcquire(key): String?`는 잠금을 얻으면 토큰을, 못 얻으면 null을 돌려준다. `release(key, token)`은 토큰이 자기 것일 때만 지운다.
- Redis 구현 `RedisDistributedLock`은 `RedisCheckoutLock`의 획득·해제를 그대로 옮긴다. `SET key token NX PX 10s`로 얻고, 50ms 간격으로 최대 3초까지 다시 시도하고, Lua 스크립트로 토큰을 비교해 지운다. 토큰은 `IdentifierGenerator`로 만든다.
- **fail-open을 유지한다.** Redis 오류나 3초 초과면 경고 로그(키와 이유)를 남기고 null을 돌려준다. 이때 블록은 잠금 없이 실행된다. 해제 실패도 경고만 남기고, 그 키는 TTL이 치운다. 정합성의 정본은 DB이고 이 락은 README가 허용한 "짧은 중복 요청 완화"다.
- `Tx`에 `newTx { }`를 더한다. `TxRunner.runNewTx`가 `@Transactional(propagation = REQUIRES_NEW)`로 연다.
- 전역 `object Lock`을 둔다. `executeWithLock(key, action)`은 `tryAcquire` → `Tx.newTx(action)` → `finally`에서 `release` 순서로 돈다.
  - `REQUIRES_NEW`를 쓰는 이유: 바깥 트랜잭션이 있어도 블록의 커밋이 해제보다 먼저 일어나야 한다. 뒤에 온 요청이 커밋되지 않은 상태를 읽으면 안 된다(컬리 `AopForTransaction` 패턴).
  - 러너는 `Tx`처럼 `PlatformConfiguration`이 기동할 때 넣는다. 테스트에서는 `TxRebindingTestExecutionListener`가 `Lock`도 그 컨텍스트의 구현으로 다시 묶는다.
- `TransactionSynchronization`을 쓰던 해제 방식은 없앤다.

**payment**
- `CheckoutLock`과 `RedisCheckoutLock`을 지운다. `PaymentManagement`는 락을 주입받지 않는다.
- `createAttempt`의 `Tx.masterTx { checkoutLock.lockUntilTransactionEnds(...) ... }`를 `Lock.executeWithLock(checkoutLockKey(orderId, checkoutNo)) { ... }`로 바꾼다.
- 키 함수와 접두어 상수는 `PaymentManagement`의 companion에 둔다. 키 값 `payment:checkout-lock:{orderId}:{checkoutNo}`는 바꾸지 않는다. 롤링 배포 중에도 이전 버전 pod와 새 버전 pod가 서로를 막아야 하기 때문이다.

**order**
- `OrderCommandFacade.create`의 `Tx.masterTx { }`를 `Lock.executeWithLock(createLockKey(terminalId, clientOrderId)) { }`로 바꾼다. line 개수 검사는 지금처럼 블록 앞에서 먼저 한다.
- 키 `order:create-lock:{terminalId}:{clientOrderId}`의 함수와 접두어 상수는 `OrderCommandFacade`의 companion에 둔다.
- 락을 기다리는 동안에는 트랜잭션을 열지 않아 커넥션을 쥐지 않는다.

**한 경로에서 락 하나만 기다린다는 규칙**
- 한 use case는 기다리는 수단을 하나만 쓴다. 잠글 행이 이미 있으면 행 잠금(`findByIdForUpdate`)을 쓴다. 행이 아직 없거나 DB 밖의 일을 감싸야 하면 분산 락을 쓴다.
- 둘을 함께 쓰면 두 시스템에 걸친 교착이 생길 수 있다. 한쪽이 Redis 락을 쥐고 행을 기다리는 동안 다른 쪽이 행을 쥐고 Redis 락을 기다리면, 둘 다 3초를 채우고 실패한다.
- 이 규칙을 `Lock` KDoc과 domain-model 질문 6에 적는다. 행 잠금을 쓰는 `requestCheckout`, `markPaid`, `applyOutcome`, `retry`는 그대로 둔다.

**계약**: 이벤트, `listener_id`, 오류 code, 스키마, HTTP 응답 모양은 바뀌지 않는다. 바뀌는 동작은 하나다. 동시에 온 같은 키의 주문 생성이 오류 대신 201과 기존 주문을 받는다.

**실패와 복구**: 잠금이 통하지 않으면(fail-open, TTL 만료) 지금과 같이 DB unique가 막는다. 그때 진 요청은 커밋에서 실패하고 트랜잭션이 롤백되어 행이 남지 않는다.

**관측성**: 잠금 없이 진행할 때와 해제에 실패할 때 키와 이유를 경고 로그로 남긴다. metric은 더하지 않는다.

## 제외
- **AOP 기반 `@DistributedLock`.** 이유는 네 가지다.
  - 트랜잭션 aspect와의 실행 순서를 `@Order`로 명시해야 한다.
  - 인자에서 오는 키 부분이 검사되지 않는 SpEL 문자열로 남는다.
  - 잠그는 위치를 고를 수 없다. 주문 생성의 line 개수 검사가 락 뒤로 밀린다.
  - 같은 클래스 안에서 부르면 프록시를 거치지 않아 락이 조용히 빠진다.

  사용처가 서너 곳 넘게 늘고, 모두 메서드 진입 시점에 인자만으로 키를 만들 수 있으면 다시 검토한다.
- **공용 확장 함수(`withLock`)와 키 조립 멤버(`lock(prefix, vararg parts)`).** 블록 형태는 해제 시점을 숨기고, vararg는 타입 검사를 잃는다. 키는 모듈 companion의 타입 있는 함수로 만든다. 공용 key object도 두지 않는다. 그러면 platform이 업무 모듈 이름을 알게 된다.
- 참고 코드에 있던 `timeoutMillis`·`timeoutException` 파라미터와 중간 계층(`LockAdvice`). 두 사용처가 모두 3초 fail-open이다.
- pub/sub 대기와 Redisson 도입. 대기가 드물어 스핀으로 충분하다. 대기 빈도가 metric에 보이면 바꾼다.
- `authorize`의 provider 호출을 분산 락으로 감싸는 일. 이중 청구의 방어선은 provider의 `PaymentId` 멱등성이다.
- 3초 대기 상수(행 잠금 힌트 두 곳, 분산 락)를 한곳으로 모으는 일.
- **`REQUIRES_NEW`의 한계**: 지금은 두 사용처(Kafka 리스너, HTTP 컨트롤러) 모두 바깥 트랜잭션이 없어 해당하지 않는다. §4.6 sync intake가 주문 생성을 한 커밋으로 묶어 부를 때 그 PR에서 정한다.
  - 바깥이 롤백돼도 블록의 커밋은 남는다.
  - 바깥 트랜잭션이 있으면 커넥션을 두 개 쓴다.
  - 바깥이 잠근 행을 블록이 기다리면 대기 한도까지 스스로를 기다린다.

## 관련 문서
없음

## 변경 경로
- src/main/kotlin/com/resilientpos/platform/**
- src/main/kotlin/com/resilientpos/order/**
- src/main/kotlin/com/resilientpos/payment/**
- src/main/resources/application.yaml
- src/test/kotlin/com/resilientpos/**
- docs/spec/2026-09-28-shared-distributed-lock.md
- docs/wiki/domain-model.md
- README.md

## 인수 조건
1. 같은 키로 `Lock.executeWithLock`이 겹치면, 뒤 블록은 앞 블록이 커밋한 뒤에 실행되고 앞 블록이 쓴 행을 본다.
2. 바깥 트랜잭션 안에서 불러도, 블록이 쓴 행은 블록이 끝날 때 커밋된다. 바깥 트랜잭션이 롤백돼도 그 행은 남는다.
3. Redis가 오류를 내면 `tryAcquire`가 던지지 않고 null을 돌려주고, 블록은 잠금 없이 실행된다. 해제는 토큰이 자기 것일 때만 지운다.
4. 주문 생성: 같은 `(terminalId, clientOrderId)`의 락을 앞 요청이 쥐고 있는 동안 온 `create`는 기다린다. 앞 요청이 커밋하면 같은 `orderId`를 받고, `t_order` 행은 하나다.
5. 결제 시도 생성: 같은 checkout의 `createAttempt`가 락에서 기다렸다가 앞 시도를 돌려받는다. 락이 통하지 않으면 `ux_payment_attempt_first_authorization`이 두 번째 시도를 막는다. 두 번째 시도는 Facade가 아니라 repository로 직접 넣는다. Facade는 이제 늘 자기 트랜잭션에서 커밋하므로 커밋을 붙잡을 수 없기 때문이다.
6. `CheckoutLock`·`RedisCheckoutLock`을 참조하는 코드가 없다.
7. `./gradlew test` 전체와 `python3 scripts/check_pr.py --base origin/main --worktree`가 통과한다.

### Checkpoint와 검증 명령
| Checkpoint | 검증 명령 | 의존 |
|---|---|---|
| lock-block (1~3) | `./gradlew test --tests 'com.resilientpos.platform.*Lock*'` | 없음 |
| order-create-lock (4) | `./gradlew test --tests 'com.resilientpos.order.internal.application.*'` | lock-block |
| payment-lock (5~6) | `./gradlew test --tests 'com.resilientpos.payment.*'` | lock-block |
| 전체 (7) | `./gradlew test` · `python3 scripts/check_pr.py --base origin/main --worktree` | 모두 |

## 검증
- 정상: 겹친 주문 생성과 결제 시도 생성의 늦은 요청이 예외 없이 앞 결과를 받는다.
- 실패: Redis 오류나 대기 초과에서는 잠금 없이 진행하고, DB unique가 중복을 막는다.
- Docker(OrbStack)가 필요한 통합 테스트는 Testcontainers의 PostgreSQL·Redis·Kafka를 쓴다.

## Wiki 영향
- [domain-model](../wiki/domain-model.md)
  - 질문 6에 "한 경로에서 락 하나만 기다린다"는 규칙을 더한다.
  - §4.x의 `CheckoutLock` 설명을 `Lock.executeWithLock`으로 바꾼다.
  - 주문 생성 경합의 방어 수단을 적는다.
- README: "Redis는 결제 시도 생성에만" 문구를 공용 분산 락(결제 시도 생성·주문 생성)으로 고친다.
- current-state는 볼트 파일이라 저장소 커밋 없이 볼트에서 고친다. 결제 시도 생성의 락 설명과 주문 생성의 알려진 빈틈이 대상이다.

## 분할 계획
개발 컨텍스트는 하나다. 분산 락을 공용으로 옮기고 두 번째 사용처에 적용하는 일이다. 작업 시작 시점(origin/main 5114d6e, #67 머지 뒤)에 열린 PR이 없어 변경 경로가 겹치지 않는다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 1, 같은 키 블록의 직렬화와 커밋 뒤 해제 | 미실행 | 미실행 | 미실행 |
| 인수 2, 바깥 트랜잭션과 분리된 커밋 | 미실행 | 미실행 | 미실행 |
| 인수 3, Redis 오류 fail-open과 토큰 확인 해제 | 미실행 | 미실행 | 미실행 |
| 인수 4, 겹친 주문 생성이 기존 주문을 받음 | 미실행 | 미실행 | 미실행 |
| 인수 5, 겹친 결제 시도 생성과 DB 방어선 | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
