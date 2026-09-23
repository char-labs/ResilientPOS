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

| 결과 | 상태 | 내는 이벤트 |
|---|---|---|
| Approved | `APPROVED` | `PaymentApproved` |
| Declined | `DECLINED` | 없다 |
| Unknown | `UNKNOWN` | `PaymentUnknown` |

**`PaymentUnknown`을 더한다.** [용어집](../wiki/domain-model.md) §3이 이미 정한 이름이다. `reconciliation`이 받을 사실이지만 그 모듈이 비어 있어 지금은 소비자가 없다. 그래서 `contract`가 아니라 `payment`의 sealed 계층에 둔다 — 경계를 넘는 것만 `contract`가 소유한다는 §4.4를 따른다.

**거절에는 이벤트를 두지 않는다.** 용어집에 이름이 없고 받을 쪽도 없다. 거절 사실은 시도 행의 `status`가 말한다. 소비자가 생기는 PR이 이름과 payload를 정한다.

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
- 거절 사유를 저장하는 것. 컬럼이 없고, 만들면 스키마 변경이 된다. 받을 쪽도 아직 없다.
- 리스너 스레드 풀과 종료 대기. Fake는 네트워크를 타지 않아 지연이 없고, 실제 provider 없이 코어 수·큐 상한·거절 정책을 정하면 근거 없는 숫자가 박힌다. 실제 provider가 붙는 PR이 정한다.
- 실제 PG 연동. 토스페이먼츠 같은 구현은 이 포트 뒤에 붙는다.
- provider 호출의 타임아웃 설정. Fake에 걸 대상이 없다.

## 관련 문서
정본은 [도메인 모델](../wiki/domain-model.md) §2·§3과 [현재 상태](../wiki/current-state.md)다. 앞 PR은 [payment-attempt-from-order-event](2026-09-22-payment-attempt-from-order-event.md)다. 외부 연동의 원문은 [외부 연동 전략](../references/04_External_Integration_Strategy.md)이다.

## 변경 경로
- src/main/kotlin/com/resilientpos/payment/**
- src/test/kotlin/com/resilientpos/payment/**
- docs/wiki/current-state.md
- docs/wiki/domain-model.md
- docs/spec/2026-09-23-payment-gateway-authorization.md

## 인수 조건
1. 승인이 나는 금액이면 시도가 `APPROVED`가 되고 provider 참조가 남는다.
2. 거절이 나는 금액이면 시도가 `DECLINED`가 되고 이벤트가 없다.
3. 미확인이 나는 금액이면 시도가 `UNKNOWN`이 된다.
4. 0원 주문은 provider를 부르지 않는다.
5. 이미 결말이 난 시도에 결과를 적용하면 `PAYMENT_RESULT_ALREADY_APPLIED`로 거절한다.
6. 주문 생성부터 checkout, 시도 기록, 승인까지 한 흐름이 통합 테스트로 돈다.
7. `PaymentUnknown`의 정규 이름이 고정된다.
8. 기존 테스트가 하나도 깨지지 않는다.

## 검증
- `./gradlew test` — 전부 통과. Docker가 필요하다.
- `python3 scripts/check_pr.py --base origin/main --worktree` — PASS.
- 인수 조건 4는 provider를 부른 횟수를 세어 확인한다. 상태만 보면 0원이 원래 `APPROVED`라 구분되지 않는다.

## Wiki 영향
- [현재 상태](../wiki/current-state.md) — provider 경계, 결과 적용 전이, `PaymentAttemptCreated`가 기록되기 시작한다는 사실, 남는 빈틈을 갱신한다.
- [도메인 모델](../wiki/domain-model.md) — `PaymentUnknown`의 소속과 거절에 이벤트를 두지 않은 이유를 적는다.
- 요구사항 변화: 없다. 결제 흐름이 정본이 예고한 대로 이어진다. API도 스키마도 그대로다.

## 분할 계획
예상 12개 파일 / 600줄 안팎. 한도 안이다.

## 결과
구현 전이다. 관측한 RED와 최종 테스트 결과를 여기에 적는다.
