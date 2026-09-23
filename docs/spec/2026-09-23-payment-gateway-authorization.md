# PR spec: provider 경계를 포트로 세우고 승인 결과를 시도에 적용한다

## 목적
결제 시도가 `REQUESTED`에서 멈춰 있다. [정본](../wiki/current-state.md)이 "외부 provider 경계(`PaymentGateway` 포트, `FakePaymentGateway`)와 provider 결과 적용 전이는 다음 PR이 만든다"로 예고한 자리다.

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

앞의 조회는 트랜잭션으로 감싸지 않는다. 감쌌을 때와 안 감쌌을 때 provider를 부르는 순간 그 스레드에 EntityManager도 물리 커넥션도 남지 않아 같았다. 적용 트랜잭션은 Boot가 등록한 `TransactionOperations` 빈으로 연다 — 같은 클래스의 `@Transactional` 메서드는 프록시를 거치지 않아 열리지 않는다.

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
| provider는 답했는데 tx2 커밋이 실패함 | `REQUESTED` | `FAILED` | 같은 `PaymentId`로 다시 부른다 |

둘째·셋째 행의 `REQUESTED`는 **"아직 부르지 않았다"와 "불렀는데 결과를 적지 못했다"를 구분하지 못한다.** 셋째 행에서는 돈이 이미 움직였을 수 있다. 그래도 다시 불러 안전한 근거는 조기 반환이 아니라 provider의 `PaymentId` 멱등성이다. 자동 재시도가 없어 사람이 `event_publication`을 봐야 드러난다. 이미 적힌 빈틈과 같은 건이다.

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
- 어댑터가 던진 예외를 `UNKNOWN`으로 바꾸는 것. 바꾸면 `REQUESTED`의 모호함은 사라지지만 `UNKNOWN`이 진행 중 상태라 그 checkout이 reconciliation이 올 때까지 잠긴다. 실제 PG 어댑터를 붙이는 PR이 고른다.

## 관련 문서
정본은 [도메인 모델](../wiki/domain-model.md) §2·§3과 [현재 상태](../wiki/current-state.md)다. 앞 PR은 [payment-attempt-from-order-event](2026-09-22-payment-attempt-from-order-event.md)다. 외부 연동의 원문은 [외부 연동 전략](../references/04_External_Integration_Strategy.md)이다.

## 변경 경로
- src/main/kotlin/com/resilientpos/payment/**
- src/test/kotlin/com/resilientpos/payment/**
- src/test/kotlin/com/resilientpos/ModuleStructureTest.kt
- docs/wiki/current-state.md
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
- [현재 상태](../wiki/current-state.md) — provider 경계, 결과 적용 전이, `PaymentAttemptCreated`가 기록되기 시작한다는 사실, 남는 빈틈을 갱신한다.
- [도메인 모델](../wiki/domain-model.md) — 결제 이벤트 셋의 소속이 갈리는 이유를 적는다.
- 요구사항 변화: 없다. 결제 흐름이 정본이 예고한 대로 이어진다. API도 스키마도 그대로다.

## 분할 계획
22개 파일 / 1352줄이다. 경고선을 넘었지만 실패 한도 안이고 목표가 하나라 나누지 않는다 — 포트·전이·Fake·리스너 중 어느 하나만 내면 승인 경로가 이어지지 않는다.

## TDD 검증 기록
동작을 먼저 비우거나 지운 코드에서 의도한 assertion 실패를 봤다.

| 동작 | RED — 명령과 실패 이유 | GREEN |
|---|---|---|
| 세 결말이 상태와 사실이 된다 | 전이를 `Transition(this, emptyList())`로 두고 `--tests '*PaymentAttemptAuthorizationTest'` — 6개 중 5개 실패. 불변성 테스트 하나는 no-op에서 저절로 맞는다 | 구현 뒤 6개 통과 |
| Fake가 금액으로 가른다 | Fake를 늘 `Unknown`으로 두고 `--tests '*FakePaymentGatewayTest'` — 4개 중 3개 실패. "끝자리 2면 미확인"은 저절로 맞는다 | 규칙 구현 뒤 4개 통과 |
| 거절 뒤 재배달이 새 시도를 만들지 않는다 | `--tests '*PaymentAttemptFromOrderEventIntegrationTest'` — 시도 `expected: <1> but was: <2>` | 결말 무관 조회로 바꾼 뒤 통과 |
| provider 호출 중 트랜잭션이 없다 | `--tests '*PaymentAuthorizationIntegrationTest'` — `expected: <false> but was: <true>` | 리스너를 풀고 Facade를 나눈 뒤 통과 |
| 겹친 승인이 결과를 한 번만 적용한다 | `--tests '*PaymentAuthorizationConcurrencyIntegrationTest'` — 두 호출이 서로 다른 결말을 돌려받음 | 잠금 뒤 3회 연속 통과 |

통과 뒤에는 **동작을 하나씩 지워** 테스트가 다시 빨개지는지 봤다. 통과만 보면 테스트가 무엇을 지키는지 알 수 없다.

| 지운 동작 | 빨개진 테스트 |
|---|---|
| 결말이 난 시도에서 돌아서기 | Facade 단위 1개, 통합 2개(0원, 재요청) |
| 적용 트랜잭션의 잠금 | 경합 통합 — 3회 모두 |
| 결말 무관 조회(진행 중 조회로 되돌림) | 거절 뒤 재배달 통합 |
| 리스너의 `propagation = NOT_SUPPORTED` | 트랜잭션 없는 호출 통합 |

`없는 시도를 승인하려 하면 거절한다`는 동작이 처음부터 있어 RED가 없다. characterization이다.

## 결과
192개 통과다(직전 167 + 신규 25).

- **provider 호출이 트랜잭션 밖으로 나갔다.** 정본이 이미 정한 것이었는데 첫 구현은 리스너의 트랜잭션 안에서 불렀다. `@ApplicationModuleListener`의 `propagation`을 `NOT_SUPPORTED`로 두면 되고 발행 기록의 추적도 그대로라는 것을 기본값과 같은 관측 테스트로 확인했다. 그 원칙을 [도메인 모델](../wiki/domain-model.md) §4.3에 리스너 규칙으로 적었다.
- **밖으로 빼면서 잠금이 필수가 됐다.** 읽기와 적용 사이가 네트워크 왕복만큼 벌어지기 때문이다. 두 호출에 서로 다른 답을 주는 경합 테스트가 이것을 고정한다. 잠금이 막는 것은 DB 안의 이중 적용이고, provider 쪽 이중 호출은 provider의 멱등성이 막는다 — 테스트도 호출이 두 번이라는 사실을 함께 고정한다.
- **거절 뒤 재배달이 새 시도를 만들던 구멍을 닫았다.** 진행 중인 시도만 찾던 조회를 결말 무관으로 바꿨다. partial unique와 `INFLIGHT_STATUSES`는 그대로 둬서 재결제는 DB가 여전히 허용한다.
- **`afterCommit` 헬퍼가 사라졌다.** 리스너가 트랜잭션이 아니면 Facade의 반환이 곧 커밋 뒤라, 반환 뒤에 찍는 로그가 그대로 참이다.
- **통합 테스트 둘이 지키는 것이 없었다.** 0원 테스트는 리스너가 돌기 전에 0회를 읽을 수 있었고, 한 번만 불린다는 테스트는 재배달을 일으키지 않고 200ms를 기다렸다. 둘 다 조기 반환을 지워도 통과했다. 이제 앞은 발행 기록이 끝나길 기다리고, 뒤는 `authorize`를 직접 다시 부른다. 호출 횟수도 컨텍스트 전역이 아니라 시도별로 센다.
- **기존 통합 테스트 둘은 결말까지 기다리게 고쳤다.** `REQUESTED` 단언은 이제 순간의 타이밍이지 계약이 아니다. 어느 결말인지는 묻지 않아 Fake 규칙과 묶이지 않는다.
- **적용 어댑터의 행 없음은 도메인 예외로 바꾸지 않았다.** 리뷰가 제안했지만 그 자리는 방금 잠근 행이 사라진 경우라 클라이언트에 줄 답이 아니라 불변식 위반이다.
- **재발행 설정의 바인딩은 검증하지 않았다.** 첫 구현에서는 동시성 논증이 그 설정 하나에 기댔지만 이제 잠금이 닫으므로 그 설정은 논증의 기둥이 아니다.
