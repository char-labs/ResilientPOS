# PR spec: provider 경계를 포트로 세우고 승인 결과를 시도에 적용한다

## 목적
결제 시도가 `REQUESTED`에서 멈춰 있다. [정본](../wiki/current-state.md)이 "외부 provider 경계(`PaymentGateway` 포트, `FakePaymentGateway`)와 provider 결과 적용 전이는 다음 PR이 만든다"로 예고한 자리다.

지금 `OrderPaymentRequestedListener`가 시도를 기록하고 끝난다. provider를 부르는 곳이 없어서 `APPROVED`가 되는 경로는 0원 주문 하나뿐이고, `DECLINED`와 `UNKNOWN`은 enum에만 있고 아무도 만들지 않는다. `PaymentAttemptEntity`의 KDoc도 "승인 결과를 반영하는 명령이 생기면 그 PR이 `apply`를 더한다"고 적어 두었다.

## 범위
**provider 호출은 `PaymentAttemptCreated`를 받는 새 리스너가 한다.** 시도 기록과 provider 호출이 각각 자기 트랜잭션과 자기 발행 기록을 갖는다. 호출이 실패하면 `event_publication`에 미완료로 남아, 재전달이 생기는 날 그 기록이 그대로 복구 단서가 된다. 기존 리스너에서 이어서 하면 이미 커밋된 `REQUESTED` 행만 남고 그 행을 다시 집어들 단서가 어디에도 없다.

`PaymentAttemptCreated`는 지금까지 소비자가 없어 발행돼도 기록되지 않았다. 이 리스너가 첫 소비자다. **그 순간부터 이 이벤트가 `event_publication`에 기록된다.**

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

**Facade가 진입점이다.** `PaymentManagement.authorize(AuthorizePaymentCommand)`가 시도를 읽고, `REQUESTED`가 아니면 provider를 부르지 않고 현재 상태를 돌려준다. 0원 주문은 이미 `APPROVED`로 만들어지므로 이 조기 반환이 그 시도를 provider에서 지켜 준다.

**Fake는 금액으로 결과를 정한다.** 설정도 스텁 교체도 없이 통합 테스트가 세 경로를 다 밟게 하려는 것이다. 금액의 최소 단위 끝자리를 본다.

| 끝자리 | 결과 |
|---|---|
| 1 | Declined |
| 2 | Unknown |
| 그 밖 | Approved |

승인 참조는 `paymentId`에서 결정적으로 만든다. 같은 입력이 항상 같은 결과라 재현된다.

계약: `event_publication`에 새 `listener_id`와 새 `event_type`(`PaymentUnknown`)이 들어간다. 배포된 환경이 없어 옛 이름으로 남은 행이 없다. 스키마는 건드리지 않는다 — `status`와 `provider_reference` 컬럼이 이미 있다.

복구: provider 호출이 실패하면 시도가 `REQUESTED`로 남고 발행 기록이 미완료가 된다. 자동 재시도가 없어 사람이 `event_publication`을 봐야 드러난다. 이미 적힌 빈틈과 같은 건이다.

관측성: 승인·거절·미확인을 커밋 뒤에 로그로 남긴다. `paymentId`와 결과만 적고 provider 참조는 적지 않는다.

## 제외
- 취소·환불 시도. `PaymentAttemptKind`에 값은 있지만 되돌릴 승인이 이제야 생긴다.
- `order`가 `PaymentApproved`를 받아 주문을 전이시키는 것. `contract`로 옮겨 길은 열어 두었고 소비자는 그 PR이 만든다.
- `UNKNOWN`을 provider 조회로 수렴시키는 것. 조회 API가 포트에 없다. reconciliation이 생기는 PR이 정한다.
- 거절 사유를 저장하는 것. 컬럼이 없고, 만들면 스키마 변경이 된다. `PaymentDeclined`도 사유를 담지 않는다 — 받을 쪽이 없어 무엇이 필요한지 정할 근거가 없다.
- 리스너 스레드 풀과 종료 대기. Fake는 네트워크를 타지 않아 지연이 없고, 실제 provider 없이 코어 수·큐 상한·거절 정책을 정하면 근거 없는 숫자가 박힌다. 실제 provider가 붙는 PR이 정한다.
- 실제 PG 연동. 토스페이먼츠 같은 구현은 이 포트 뒤에 붙는다.
- provider 호출의 타임아웃 설정. Fake에 걸 대상이 없다.

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
8. 기존 테스트가 하나도 깨지지 않는다.

## 검증
- `./gradlew test` — 전부 통과. Docker가 필요하다.
- `python3 scripts/check_pr.py --base origin/main --worktree` — PASS.
- 인수 조건 4는 provider를 부른 횟수를 세어 확인한다. 상태만 보면 0원이 원래 `APPROVED`라 구분되지 않는다.

## Wiki 영향
- [현재 상태](../wiki/current-state.md) — provider 경계, 결과 적용 전이, `PaymentAttemptCreated`가 기록되기 시작한다는 사실, 남는 빈틈을 갱신한다.
- [도메인 모델](../wiki/domain-model.md) — 결제 이벤트 셋의 소속이 갈리는 이유를 적는다.
- 요구사항 변화: 없다. 결제 흐름이 정본이 예고한 대로 이어진다. API도 스키마도 그대로다.

## 분할 계획
22개 파일 / 982줄이다. 경고선을 넘었지만 실패 한도 안이고 목표가 하나라 나누지 않는다 — 포트·전이·Fake·리스너 중 어느 하나만 내면 승인 경로가 이어지지 않는다.

## TDD 검증 기록
전이를 no-op으로 두고 테스트를 먼저 써서 의도한 assertion 실패를 봤다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 세 결말이 상태와 사실이 된다 | `PaymentAttemptAuthorizationTest` — 전이가 `Transition(this, emptyList())`라 6개 중 5개 실패 | 전이 구현 뒤 6개 통과 | `./gradlew test` 통과 |
| Fake가 금액으로 결말을 가른다 | `FakePaymentGatewayTest` — 구현 전 컴파일 실패 | 구현 뒤 5개 통과 | 같음 |
| checkout이 승인까지 이어진다 | `PaymentAuthorizationIntegrationTest` — 리스너 전에는 `REQUESTED`에서 멈춤 | 리스너 추가 뒤 5개 통과 | 같음 |

## 결과
189개 통과다(직전 167 + 신규 22). 22개 파일 / 982줄이다.

- **불변성 테스트만 RED에서 통과했다.** 전이를 no-op으로 두면 원본이 그대로이므로 "원본 시도는 그대로 남는다"가 저절로 맞는다. 나머지 다섯이 실패하는 것을 보고 구현했다.
- **기존 통합 테스트 둘이 깨졌고 그게 맞았다.** `시도가 REQUESTED로 남는다`를 단언하던 테스트들이다. 이제 승인 리스너가 이어서 돌아 결말까지 간다. 단언을 느슨하게 푸는 대신 **결말을 기다린 뒤 `APPROVED`를 단언**하도록 고쳤다 — 기록 직후를 보면 두 상태 사이에서 흔들린다.
- **`승인된 시도도 "진행 중"에 든다`가 드러났다.** 중복 이벤트로 다시 부르면 돌려받는 상태가 `REQUESTED`에서 `APPROVED`로 바뀐다. `INFLIGHT_STATUSES`에 `APPROVED`가 들어 있어서이고, 그래서 승인된 뒤에도 새 시도가 생기지 않는다.
- **0원이 provider를 부르지 않는 것은 상태로 확인할 수 없다.** 부르든 안 부르든 `APPROVED`다. 통합 테스트가 포트를 감싸 호출 횟수를 세고, 0원에서 0회인 것과 일반 승인에서 1회인 것을 본다.
- **`afterCommit` 헬퍼를 리스너 둘이 공유한다.** 두 번째 리스너가 생기면서 같은 코드가 둘이 됐다. `payment/internal/application`의 internal 함수로 뺐다.
- **provider 호출이 리스너의 트랜잭션 안에 있다.** `@ApplicationModuleListener`가 자기 트랜잭션을 여는 구조라 호출만 밖으로 뺄 자리가 없다. Fake는 지연이 없어 지금 드러나지 않지만 실제 provider가 붙으면 커넥션을 쥐는 시간이 된다. 빈틈으로 적었다.
- **정본의 자기모순 하나를 고쳤다.** 종료 대기를 한 줄은 `0ms`(실측)로, 다른 줄은 `2초`로 적고 있었다. 실측값으로 맞췄다.
- **경고선을 넘었다.** 982줄이다. 포트·전이·Fake·리스너 중 어느 하나만 내면 승인 경로가 이어지지 않아 나누지 않았다. 실패 한도 안이다.
