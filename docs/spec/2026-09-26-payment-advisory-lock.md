# PR spec: 같은 checkout의 결제 시도 생성을 advisory lock으로 직렬화한다

## 목적
`PaymentManagement.createAttempt`는 그 checkout의 승인 시도를 결말과 무관하게 읽고, 없을 때만 새 시도를 넣는다. 같은 `OrderPaymentRequested`가 동시에 두 번 오면 두 트랜잭션이 모두 "없음"을 읽는다. 앞쪽이 커밋되고 그 시도가 거절(`DECLINED`)된 뒤에 뒤쪽이 커밋하면 partial unique `ux_payment_attempt_inflight_authorization`이 `DECLINED`를 술어에서 빼고 보므로 뒤쪽의 삽입이 통과한다. 사용자가 재결제를 요청하지 않았는데 한 checkout에 승인 시도가 둘 생기고 provider가 두 번 불린다. 이중 승인 위험이다(`PaymentManagement.kt`의 `ponytail:` 표식).

지금은 재발행이 꺼져 있어 동시 배달이 없다. Kafka 도입 설계(브랜치 `feature/kafka-event-redelivery`의 `docs/spec/2026-09-26-kafka-event-redelivery.md`, 아직 main 밖)에서 consumer가 at-least-once로 전달하면 리밸런스 순간에 같은 사실이 동시에 올 수 있다. 이 PR은 Kafka와 무관하게 main 위에서 그 창을 먼저 닫는다.

기대 결과: 같은 `(orderId, checkoutNo)`의 생성 요청이 겹치면 뒤쪽이 앞쪽의 커밋을 기다린 뒤 그 시도를 돌려받는다. 새 시도는 하나다.

## 범위
소유 모듈은 `payment`다.

**업무 트랜잭션 안에서 조회 전에 `pg_advisory_xact_lock`을 건다.** `createAttempt`가 `findAuthorization`보다 먼저 port의 새 메서드 `PaymentAttemptRepository.lockCheckout(orderId, checkoutNo)`를 부른다. 잠금은 트랜잭션 수준이라 커밋·롤백과 함께 풀린다. 뒤쪽은 앞쪽의 커밋 뒤에 조회하므로 앞 시도를 보고 그것을 돌려준다.

**Redis·Redisson을 쓰지 않는다.** [AGENTS.md](../../AGENTS.md)가 정합성을 Redis로 대신하지 않는다고 정했고, 잠금이 업무 커밋과 함께 풀려야 한다. 분산 잠금은 커밋과 해제 사이에 창이 생기고 TTL 만료에서 다시 열린다. 정본 DB 안의 잠금이면 둘 다 없다.

**키는 두 int 형식 `pg_advisory_xact_lock(int4, int4)`이다.** 첫 키는 `orderId`의 `UUID.hashCode()`(상위·하위 64비트를 XOR한 뒤 32비트로 접은 값), 둘째 키는 `checkoutNo` 그대로다.
- 두 int 형식을 고른 이유: `checkoutNo`는 이미 int라 손실 없이 들어가고, 해시가 필요한 것은 `orderId` 하나뿐이다. PostgreSQL 문서상 두 int 키 공간과 bigint 키 공간은 서로 겹치지 않는다.
- 해시를 Kotlin에서 계산하는 이유: `UUID.hashCode()`는 Java 명세로 고정된 결정적 함수다. PostgreSQL의 `hashtext`는 문서화되지 않은 내부 함수라 계약이 없다.
- 충돌: 서로 다른 주문의 `orderId` 해시와 `checkoutNo`가 모두 같으면 같은 잠금을 쓴다. 결과는 정합성 오류가 아니라 불필요한 대기다(한쪽이 다른 쪽의 짧은 트랜잭션이 끝날 때까지 기다린다). UUID v7의 하위 62비트가 무작위라 해시가 고르게 퍼지고, 동시에 잠금을 쥔 checkout이 k개일 때 새 요청이 그중 하나와 부딪칠 확률은 대략 k / 2^32다. 초당 수백 건이어도 무시할 수 있다.
- 이 저장소에 다른 advisory lock 사용자는 없다. 두 int 키 공간을 쓰는 사용자가 생기면 첫 키가 겹칠 수 있으므로 그 PR이 네임스페이스를 정한다.

**잠금 대기는 3초로 제한한다.** 잠금 직전에 `set_config('lock_timeout', '3000', true)`(트랜잭션 한정)를 건다. `findByIdForUpdate`의 3초와 같은 이유다(무기한 대기는 커넥션을 쥔 채 쌓여 풀을 말린다). 같은 트랜잭션의 이후 삽입에도 적용되지만 그 삽입이 기다릴 상대는 이제 재결제 경로뿐이고 같은 한도가 맞다. 넘기면 `PessimisticLockingFailureException` 계열로 실패하고 아무것도 남지 않는다.

**재결제(`retryAuthorization`)는 이 잠금을 걸지 않는다.** 재결제는 이미 있는 거절 시도를 전제로 하므로, 겹친 `createAttempt`는 그 시도를 보고 새 시도를 만들지 않는다. 재결제끼리는 대상 행 잠금이 직렬화한다.

partial unique는 마지막 방어선으로 남는다. 코드의 `ponytail:` 표식을 지우고 `createAttempt`·`findAuthorization`·리스너의 설명을 잠금 기준으로 고친다.

계약: 스키마, 이벤트, `listener_id`, 오류 code는 바뀌지 않는다. `PaymentAttemptRepository`에 메서드가 하나 는다(모듈 내부 port).

복구: 잠금 대기 초과로 실패하면 리스너가 던지고 발행 기록이 미완료로 남는다. 지금 경로와 같다.

관측성: 새 로그·metric은 없다. 겹친 요청의 뒤쪽은 기존 리스너 로그에 앞 시도의 `paymentId`로 찍힌다.

## 제외
- Kafka consumer와 재배달(별도 PR).
- `fulfillment`의 같은 창(`FulfillmentCommandFacade`의 `ponytail:`)을 `on conflict`로 바꾸는 것. Kafka 재시도가 해소하기로 합의했다.
- 스키마 변경. 잠금은 행을 만들지 않는다.
- 잠금 대기 초과 전용 테스트. 설정 한 줄이고 동작은 PostgreSQL이 보장한다.

## 관련 문서
[도메인 모델](../wiki/domain-model.md) §4.3. 앞 PR은 [payment-attempt-from-order-event](2026-09-22-payment-attempt-from-order-event.md), [payment-gateway-authorization](2026-09-23-payment-gateway-authorization.md), [payment-retry-after-decline](2026-09-26-payment-retry-after-decline.md)다.

## 변경 경로
- src/main/kotlin/com/resilientpos/payment/**
- src/test/kotlin/com/resilientpos/payment/**
- docs/wiki/domain-model.md
- docs/spec/2026-09-26-payment-advisory-lock.md

## 인수 조건
1. 같은 `(orderId, checkoutNo)`의 `createAttempt`가 겹치고 앞쪽 시도가 거절까지 가도 승인 시도는 하나이고, 뒤쪽은 앞쪽과 같은 `paymentId`를 돌려받는다.
2. 기존 테스트가 깨지지 않는다.

## 검증
- 경합 재현 통합 테스트: 한 트랜잭션(B)이 `createAttempt`를 마치고 커밋하지 않은 채로, 다른 스레드(A)가 같은 checkout으로 `createAttempt`를 부른다. 금액 끝자리가 1이라 `FakePaymentGateway`가 거절한다. 잠금이 없으면 A가 커밋되고 거절된 뒤 B가 커밋되어 시도가 둘이 된다(RED). 잠금이 있으면 A가 advisory lock에서 기다리고(`pg_locks`로 관측) B 커밋 뒤 B의 시도를 돌려받는다. 테스트는 A의 완료 또는 대기를 확인한 뒤 B를 커밋하므로 순서가 결정적이다.
- `./gradlew test` — 전부 통과. Docker가 필요하다.
- `python3 scripts/check_pr.py --base origin/main --worktree` — PASS.

## Wiki 영향
- [도메인 모델](../wiki/domain-model.md) §4.3: `payment`의 재배달 멱등 문장에 "동시에 온 같은 사실은 `(orderId, checkoutNo)` advisory lock이 직렬화한다"를 더한다.
- 현재 상태 문서는 볼트에서 갱신한다.
- 요구사항 변화: 없다. 같은 사실은 시도 하나라는 기존 요구를 동시 배달에서도 지킨다.

## 분할 계획
예상 6개 파일 / 약 250줄(spec 포함). 한도 안이다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 겹친 생성이 거절까지 가도 시도는 하나다 (인수 조건 1) `PaymentAttemptCreationConcurrencyIntegrationTest` | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성.
