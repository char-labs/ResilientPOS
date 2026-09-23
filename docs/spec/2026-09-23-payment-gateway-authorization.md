# PR spec: provider 경계를 포트로 세우고 승인 결과를 시도에 적용한다

## 목적
결제 시도가 `REQUESTED`에서 멈춰 있다. 정본이 "외부 provider 경계(`PaymentGateway` 포트, `FakePaymentGateway`)와 provider 결과 적용 전이는 다음 PR이 만든다"로 예고한 자리다.

지금 `OrderPaymentRequestedListener`가 시도를 기록하고 끝난다. provider를 부르는 곳이 없어서 `APPROVED`가 되는 경로는 0원 주문 하나뿐이고, `DECLINED`와 `UNKNOWN`은 enum에만 있고 아무도 만들지 않는다. `PaymentAttemptEntity`의 KDoc도 "승인 결과를 반영하는 명령이 생기면 그 PR이 `apply`를 더한다"고 적어 두었다.

## 범위
**provider 호출은 `PaymentAttemptCreated`를 받는 새 리스너가 한다.** 시도 기록과 provider 호출이 각각 자기 트랜잭션과 자기 발행 기록을 갖는다. 호출이 실패하면 `event_publication`에 미완료로 남아, 재전달이 생기는 날 그 기록이 그대로 복구 단서가 된다. 기존 리스너에서 이어서 하면 이미 커밋된 `REQUESTED` 행만 남고 그 행을 다시 집어들 단서가 어디에도 없다.

`PaymentAttemptCreated`는 지금까지 소비자가 없어 발행돼도 기록되지 않았다. 이 리스너가 첫 소비자다. **그 순간부터 이 이벤트가 `event_publication`에 기록된다.**

**provider 호출은 트랜잭션 밖이다.** [도메인 모델](../wiki/domain-model.md) §4.6("provider 승인 호출은 트랜잭션 밖")과 §6("provider 호출·외부 전송은 상자 밖")이 정한 것이다. `authorize`에서 트랜잭션은 적용 하나뿐이다.

```
     시도를 읽는다 → 결말이 났으면 돌아선다
     provider를 부른다 (DB 커넥션을 쥐지 않는다)
tx   시도를 잠그고 다시 읽는다 → 그사이 결말이 났으면 돌아선다 → 결과를 적용하고 사실을 발행한다
```

Facade가 명시적으로 여는 트랜잭션은 적용 하나다. 앞의 조회는 Spring Data `findById`의 읽기 전용 트랜잭션에서 돌고 끝나면 자원을 반납한다 — 이 조회를 파생 쿼리로 바꾸면 그 트랜잭션이 사라져 EntityManager가 스레드에 남을 수 있어서, provider를 부르는 순간 스레드에 묶인 DB 자원이 없는지를 테스트가 본다. 적용 트랜잭션은 Boot가 등록한 `TransactionOperations` 빈으로 연다 — 같은 클래스의 `@Transactional` 메서드는 프록시를 거치지 않아 열리지 않는다.

**`authorize`는 트랜잭션 안에서 부를 수 없다(`@Transactional(propagation = NEVER)`).** 안에서 부르면 적용이 그 트랜잭션에 합류해, 잠금 조회가 먼저 읽어 둔 낡은 `REQUESTED`를 돌려받고 앞의 결말을 덮는다.

그러려면 리스너 자신이 트랜잭션이면 안 된다. `@ApplicationModuleListener`는 `@Async`·`@TransactionalEventListener`·`@Transactional(REQUIRES_NEW)`의 합성이라 리스너가 트랜잭션을 열고 Facade가 그 안에 합류한다. **두 리스너 모두 `@ApplicationModuleListener(propagation = NOT_SUPPORTED)`로 트랜잭션을 끈다.** `propagation`이 메타 애너테이션의 `@Transactional`로 이어지는 속성이라 표준 애너테이션을 그대로 쓴다. 발행 기록은 발행하는 쪽의 트랜잭션에서 쓰이고 완료 표시는 Modulith가 리스너 반환 뒤 자기 트랜잭션에서 하므로, 리스너가 트랜잭션이 아니어도 추적이 유지된다. 같은 관측 테스트를 기본값과 이 설정에 돌려 확인했다 — 승인하면 `COMPLETED`, 리스너가 던지면 `FAILED`로 같고, provider 호출 중 트랜잭션이 활성에서 비활성으로, 쥔 커넥션이 하나 줄었다. `listener_id`도 같다.

리스너가 트랜잭션이 아니면 Facade가 반환하는 순간이 곧 커밋 뒤다. 그래서 **반환 뒤에 찍는 로그가 그대로 참이 되고, 커밋 뒤로 로그를 미루던 `afterCommit` 헬퍼가 필요 없어져 지운다.** 경합에서 진 쪽은 이제 Facade 안의 커밋에서 예외를 받아 로그에 닿지 않는다.

**tx2는 행을 잠근다.** provider 호출을 밖으로 빼면 읽기와 적용 사이가 네트워크 왕복만큼 벌어진다. 그 사이에 같은 시도에 대한 다른 `authorize`가 끼어들면 둘 다 `REQUESTED`를 읽고 둘 다 적용해, 나중에 커밋한 쪽이 앞의 결과를 덮는다 — 승인이 거절로 조용히 바뀐다. `order`의 `findByIdForUpdate`와 같은 모양(`PESSIMISTIC_WRITE`, 대기 3초)으로 잠그고 다시 읽는다. 진 쪽은 앞 트랜잭션의 커밋을 기다렸다가 결말을 보고 돌아선다. 스키마를 바꾸지 않는다.

**잠금이 막는 것은 DB 안의 이중 적용이다. provider 쪽 이중 호출은 막지 못한다.** 두 호출이 모두 앞의 조회를 지나면 provider는 두 번 불린다. 그때 이중 청구를 막는 것은 같은 `PaymentId`에 같은 답을 주는 provider의 멱등성이고, 이 포트가 구현에 요구하는 계약이다. 조기 반환은 결말이 난 시도에 불필요한 호출을 하지 않게 할 뿐이다.

**같은 `OrderPaymentRequested`가 다시 와도 시도는 하나다 — 거절 뒤에도.** 시도를 만들 때 그 checkout의 승인 시도가 **결말과 무관하게** 이미 있으면 그것을 돌려준다. `OrderPaymentRequested`는 checkout 회차마다 한 번 나고 `(orderId, checkoutNo)`가 `t_order_checkout`의 자연 키이므로, 같은 쌍이 다시 오면 그것은 재시도가 아니라 같은 사실의 재배달이다. 진행 중인 시도만 찾으면 거절된 시도를 못 보고 새 `PaymentId`로 provider를 다시 부르는데, 멱등 키가 달라 provider도 막아 주지 못한다.

partial unique `ux_payment_attempt_inflight_authorization`과 `INFLIGHT_STATUSES`는 그대로 둔다. 거절 뒤 재결제는 여전히 DB가 허용해야 하는 일이고, 그것은 사용자가 명시적으로 요청하는 **별도 명령**이 할 일이다. 사실의 재배달과 재결제 의도를 같은 입구로 받지 않는다.

**포트는 `payment/internal/domain`에 둔다.** `PaymentAttemptRepository`와 같은 자리다. 둘 다 aggregate가 바깥에 말을 거는 통로이고, 구현은 `internal/adapter` 아래에 있다.

```
PaymentGateway.authorize(AuthorizationRequest): AuthorizationOutcome
AuthorizationOutcome = Approved(providerReference) | Declined(providerReference?) | Unknown
```

세 갈래가 `PaymentAttemptStatus`의 `APPROVED`·`DECLINED`·`UNKNOWN`과 일대일이다. provider가 말하는 상태 이름을 그대로 들이지 않는다 — 어댑터가 자기 어휘를 이 세 갈래로 접는다.

**idempotency 키는 `PaymentId`다.** 별도 컬럼을 만들지 않는다. 한 시도가 provider 요청 1회이므로 시도의 식별자가 곧 그 요청의 식별자다. 같은 키로 다시 부르면 provider가 같은 답을 주어야 한다는 기대를 어댑터 계약으로 적는다.

**결과 적용은 aggregate가 한다.** `PaymentAttempt.applyAuthorization(outcome, ids, now)`가 새 인스턴스와 이벤트를 돌려준다. `REQUESTED`가 아닌 시도에 결과를 적용하면 `PAYMENT_RESULT_ALREADY_APPLIED`로 거절한다. [도메인 모델](../wiki/domain-model.md) §2의 "provider 결과는 한 번만 적용한다"가 이 불변식이다.

| 결과 | 상태 | 내는 이벤트 | 이벤트가 사는 곳 |
|---|---|---|---|
| Approved | `APPROVED` | `PaymentApproved` | `contract` |
| Declined | `DECLINED` | `PaymentDeclined` | `payment` |
| Unknown | `UNKNOWN` | `PaymentUnknown` | `payment` |

**`PaymentDeclined`와 `PaymentUnknown`을 더한다.** `PaymentEvent`의 KDoc이 "provider 결과를 적용하는 전이가 생기는 PR이 `PaymentDeclined`·`PaymentUnknown`을 더한다"로 이 자리를 예고했고, `PaymentUnknown`은 [용어집](../wiki/domain-model.md) §3이 이미 정한 이름이다.

**셋 중 둘은 `payment`에 남는다.** `PaymentApproved`만 `order`와 `reconciliation`이 받아 경계를 넘고, 거절과 미확인은 받을 쪽이 아직 없다. 경계를 넘는 것만 `contract`가 소유한다는 §4.4가 이 비대칭의 이유다. 소비자가 생기면 그 PR이 옮기고, 그 이동은 저장 형식을 건드리므로 그때 배포된 행이 있는지부터 본다.

**Facade가 진입점이다.** `PaymentManagement.authorize(AuthorizePaymentCommand)`가 위 세 단계를 소유한다. `REQUESTED`가 아니면 provider를 부르지 않고 현재 상태를 돌려준다. 0원 주문은 이미 `APPROVED`로 만들어지므로 이 조기 반환이 그 시도를 provider에서 지켜 준다.

**Fake는 금액으로 결과를 정한다.** 설정도 스텁 교체도 없이 통합 테스트가 세 경로를 다 밟게 하려는 것이다. 금액의 최소 단위 끝자리를 본다.

| 끝자리 | 결과 |
|---|---|
| 1 | Declined |
| 2 | Unknown |
| 그 밖 | Approved |

승인 참조는 `paymentId`에서 결정적으로 만든다. 같은 입력이 항상 같은 결과라 재현된다.

계약: `event_publication`에 새 `listener_id`와 새 `event_type`(`PaymentAttemptCreated`)이 들어간다. `PaymentDeclined`·`PaymentUnknown`·`PaymentApproved`는 받는 쪽이 없어 발행돼도 기록되지 않는다. 배포된 환경이 없어 옛 이름으로 남은 행이 없다. 스키마는 건드리지 않는다 — `status`와 `provider_reference` 컬럼이 이미 있다.

복구: 경로가 셋이고 남는 흔적이 다르다.

| 무슨 일이 | 시도 | 발행 기록 | 다시 돌리면 |
|---|---|---|---|
| provider가 결과를 모른다고 답함 | `UNKNOWN` | `COMPLETED` | 조회·reconciliation을 기다린다. 포트 계약상 통신 실패는 이 경로다 |
| 어댑터가 예외를 던짐 | `REQUESTED` | `FAILED` | 같은 `PaymentId`로 다시 부른다 |
| provider는 답했는데 적용 트랜잭션이 실패함 | `REQUESTED`이거나 경쟁 호출이 커밋한 결말 | 대개 `FAILED` | 다시 읽고, `REQUESTED`면 같은 `PaymentId`로 다시 부른다 |
| 잠금 대기 3초 초과 | 잠금을 쥔 쪽이 커밋한 결말 | `FAILED` | 결말이 났으므로 할 일이 없다 — 이 `FAILED`는 거짓 경보다 |

커밋 응답을 잃으면 적용됐는지 알 수 없고, 실패 표시 자체가 DB 장애로 실패하면 기록은 미완료로 남는다. 어느 경우든 시도를 다시 읽는 것이 먼저다.

`REQUESTED`는 **"아직 부르지 않았다"와 "불렀는데 결과를 적지 못했다"를 구분하지 못한다.** 셋째 행에서는 돈이 이미 움직였을 수 있다. 그래도 다시 불러 안전한 근거는 조기 반환이 아니라 provider의 `PaymentId` 멱등성이다. 자동 재시도가 없어 사람이 `event_publication`을 봐야 드러난다. 이미 적힌 빈틈과 같은 건이다.

관측성: 승인·거절·미확인을 Facade가 반환한 뒤 로그로 남긴다. 리스너가 트랜잭션이 아니라 그 시점이 곧 커밋 뒤다. `paymentId`와 결과만 적고 provider 참조는 적지 않는다.

## 제외
- 취소·환불 시도. `PaymentAttemptKind`에 값은 있지만 되돌릴 승인이 이제야 생긴다.
- `order`가 `PaymentApproved`를 받아 주문을 전이시키는 것. `contract`로 옮겨 길은 열어 두었고 소비자는 그 PR이 만든다.
- `UNKNOWN`을 provider 조회로 수렴시키는 것. 조회 API가 포트에 없다. reconciliation이 생기는 PR이 정한다.
- 거절 사유를 저장하는 것. 컬럼이 없고, 만들면 스키마 변경이 된다. `PaymentDeclined`도 사유를 담지 않는다 — 받을 쪽이 없어 무엇이 필요한지 정할 근거가 없다.
- 리스너 스레드 풀과 종료 대기. Fake는 네트워크를 타지 않아 지연이 없고, 실제 provider 없이 코어 수·큐 상한·거절 정책을 정하면 근거 없는 숫자가 박힌다. 실제 provider가 붙는 PR이 정한다.
- 실제 PG 연동. 토스페이먼츠 같은 구현은 이 포트 뒤에 붙는다.
- provider 호출의 타임아웃 설정. Fake에 걸 대상이 없다.
- 거절 뒤 재결제 명령. DB는 허용하지만 그 입구가 아직 없다. 사용자가 명시적으로 요청하는 명령으로 만든다.
- 같은 사실이 **동시에** 두 번 올 때의 생성 경합. 둘 다 "없음"을 읽고 앞 시도가 거절된 뒤 뒤쪽이 새 시도를 넣을 수 있다 — partial unique가 `DECLINED`를 빼고 보기 때문이다. 지금은 재발행이 꺼져 동시 배달이 없다. 재발행·Kafka·`sync` 경로가 생기는 PR이 `(orderId, checkoutNo)`에 `pg_advisory_xact_lock`을 건다. 코드에 `ponytail:` 표식으로 남겼다.
- 어댑터가 던진 예외를 `UNKNOWN`으로 바꾸는 것. 바꾸면 `REQUESTED`의 모호함은 사라지지만 `UNKNOWN`이 진행 중 상태라 그 checkout이 reconciliation이 올 때까지 잠긴다. 실제 PG 어댑터를 붙이는 PR이 고른다.

## 관련 문서
정본은 [도메인 모델](../wiki/domain-model.md) §2·§3이다. 앞 PR은 [payment-attempt-from-order-event](2026-09-22-payment-attempt-from-order-event.md)다. 외부 연동의 원문은 [외부 연동 전략](../references/04_External_Integration_Strategy.md)이다.

## 변경 경로
- src/main/kotlin/com/resilientpos/payment/**
- src/test/kotlin/com/resilientpos/payment/**
- src/test/kotlin/com/resilientpos/ModuleStructureTest.kt
- docs/wiki/domain-model.md
- docs/spec/2026-09-23-payment-gateway-authorization.md

## 인수 조건
1. 승인이 나는 금액이면 시도가 `APPROVED`가 되고 provider 참조가 남는다.
2. 거절이 나는 금액이면 시도가 `DECLINED`가 되고 `PaymentDeclined`가 난다.
3. 미확인이 나는 금액이면 시도가 `UNKNOWN`이 된다.
4. 0원 주문은 provider를 부르지 않는다.
5. 이미 결말이 난 시도에 결과를 적용하면 `PAYMENT_RESULT_ALREADY_APPLIED`로 거절한다.
6. 주문 생성부터 checkout, 시도 기록, 승인까지 한 흐름이 통합 테스트로 돈다.
7. `PaymentDeclined`와 `PaymentUnknown`의 정규 이름이 고정된다.
8. provider를 부르는 동안 트랜잭션이 없다.
9. 같은 시도에 `authorize`가 겹쳐도 결과는 한 번만 적용되고 두 호출이 같은 결말을 돌려받는다.
10. 거절된 시도가 있는 checkout에 같은 요청이 다시 와도 새 시도를 만들지 않는다.
11. 없는 시도를 승인하려 하면 `PAYMENT_ATTEMPT_NOT_FOUND`로 거절한다.
12. 기존 테스트가 하나도 깨지지 않는다.

## 검증
- `./gradlew test` — 전부 통과. Docker가 필요하다.
- `python3 scripts/check_pr.py --base origin/main --worktree` — PASS.
- 인수 조건 4는 provider를 부른 횟수를 세어 확인한다. 상태만 보면 0원이 원래 `APPROVED`라 구분되지 않는다. 횟수를 읽기 전에 그 시도의 `PaymentAttemptCreated` 발행 기록이 완료될 때까지 기다린다 — 기다리지 않으면 리스너가 아직 돌지 않아서 0회인 것과 구분되지 않는다.
- 테스트가 동작을 실제로 지키는지는 동작을 지운 코드에서 **테스트가 실패하는 것**으로 확인한다. 조기 반환을 지우고, 잠금을 빼고, 결말 무관 조회를 진행 중 조회로 되돌려 각각 해당 테스트가 빨개지는지 본다.

## Wiki 영향
- [도메인 모델](../wiki/domain-model.md) — 결제 이벤트 셋의 소속이 갈리는 이유를 적는다.
- 요구사항 변화: 없다. 결제 흐름이 정본이 예고한 대로 이어진다. API도 스키마도 그대로다.

## 분할 계획
21개 파일 / 1374줄이다. 경고선을 넘었지만 실패 한도 안이고 목표가 하나라 나누지 않는다 — 포트·전이·Fake·리스너 중 어느 하나만 내면 승인 경로가 이어지지 않는다.

## TDD 검증 기록
동작을 먼저 비우거나 지운 코드에서 의도한 assertion 실패를 봤다.

| 동작 | RED — 명령과 실패 이유 | GREEN |
|---|---|---|
| 세 결말이 상태와 사실이 된다 | 전이를 `Transition(this, emptyList())`로 두고 `--tests '*PaymentAttemptAuthorizationTest'` — 6개 중 5개 실패. 불변성 테스트 하나는 no-op에서 저절로 맞는다 | 구현 뒤 6개 통과 |
| Fake가 금액으로 가른다 | Fake를 늘 `Unknown`으로 두고 `--tests '*FakePaymentGatewayTest'` — 4개 중 3개 실패. "끝자리 2면 미확인"은 저절로 맞는다 | 규칙 구현 뒤 4개 통과 |
| 거절 뒤 재배달이 새 시도를 만들지 않는다 | `--tests '*PaymentAttemptFromOrderEventIntegrationTest'` — 시도 `expected: <1> but was: <2>` | 결말 무관 조회로 바꾼 뒤 통과 |
| provider 호출 중 스레드에 DB 자원이 없다 | `--tests '*PaymentAuthorizationIntegrationTest'` — `expected: <false> but was: <true>` | 리스너 전파를 끄고 Facade가 적용만 트랜잭션으로 연 뒤 통과 |
| 트랜잭션 안에서 부르면 거절한다 | 같은 명령 — 트랜잭션 안에서 불러도 예외 없이 진행(`actual <null>`) | `NEVER` 뒤 통과 |
| 겹친 승인이 결과를 한 번만 적용한다 | `--tests '*PaymentAuthorizationConcurrencyIntegrationTest'` — 두 호출이 서로 다른 결말을 돌려받음 | 잠금 뒤 3회 연속 통과 |

통과 뒤에는 **동작을 하나씩 지워** 테스트가 다시 빨개지는지 봤다. 통과만 보면 테스트가 무엇을 지키는지 알 수 없다.

| 지운 동작 | 빨개진 테스트 |
|---|---|
| 결말이 난 시도에서 돌아서기 | Facade 단위 1개, 통합 2개(0원, 재요청) |
| 적용 트랜잭션의 잠금 | 경합 통합 — 3회 모두 |
| 결말 무관 조회(진행 중 조회로 되돌림) | 거절 뒤 재배달 통합 |
| 리스너의 `propagation = NOT_SUPPORTED` | 트랜잭션 없는 호출 통합 |

`없는 시도를 승인하려 하면 거절한다`와 `어댑터가 던지면 발행 기록은 실패로 남는다`는 동작이 처음부터 있어 RED가 없다. characterization이다. 잠금을 뺐을 때의 경합 테스트 실패는 확률적이다 — 래치는 두 호출이 provider에서 만나는 것까지만 보장하고, 적용 단계의 순서는 보장하지 않는다.

## 결과
199개 통과다(#51의 5개 포함).

- provider 호출은 트랜잭션 밖이다. 리스너는 `@ApplicationModuleListener(propagation = NOT_SUPPORTED)`이고, 이 규칙을 [도메인 모델](../wiki/domain-model.md) §4.3에 적었다.
- 적용은 잠그고 다시 읽는다. 잠금이 막는 것은 DB 안의 이중 적용이고, provider 쪽 이중 호출은 provider의 멱등성이 막는다. 경합 테스트가 둘 다 고정한다.
- `createAttempt`는 결말과 무관하게 기존 시도를 찾는다. 진행 중인 승인 행이 하나뿐이라는 것은 partial unique의 술어가 정하고, 스키마 테스트가 그 술어를 리터럴로 고정한다.
- 리스너가 트랜잭션이 아니므로 Facade가 반환한 뒤 찍는 로그가 곧 커밋 뒤의 사실이다.
- 적용 어댑터에서 행이 없으면 도메인 예외가 아니라 불변식 위반으로 던진다. 방금 잠근 행이라 클라이언트에 줄 답이 아니다.
