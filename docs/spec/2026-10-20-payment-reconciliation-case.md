# PR spec: 조회로도 확정되지 않는 결제를 대조 case로 넘겨 점주가 근거와 함께 해소한다

## 목적
#84로 결과 불명(`UNKNOWN`) 결제를 provider 조회로 확정하는 길이 생겼다. 하지만 조회로도 확정되지 않는 시도는 넘겨받을 곳이 없다. 그 시도는 매분 다시 조회될 뿐 `UNKNOWN`에 남고, 주문은 `CHECKOUT_REQUESTED`에 머문다. `UNKNOWN`은 재결제도 막는다. README 원칙 `UNKNOWN -> provider query -> reconciliation`의 마지막 단계를 만든다. `payment`는 조회 상한을 넘었거나 provider가 다른 금액으로 승인했다고 답한 시도를 `reconciliation` case로 넘기고, 그 뒤로는 그 시도를 더 조회하지 않는다. 점주는 자기 매장의 case를 PG 가맹점 화면에서 확인한 근거와 함께 승인 또는 거절로 해소한다. 결론은 `payment`의 좁은 공개 command가 적용한다. 승인이면 기존 경로로 주문이 `PAID`가 되고, 거절이면 기존 재결제(#89)가 열린다.

## 범위
소유 모듈은 `reconciliation`(case)과 `payment`(넘김과 결론 적용)다. `contract`에 넘김 사실과 그 사유 enum을 더한다. `order`는 `ponytail:` 표식 문구만 고치고 동작을 바꾸지 않는다.

### 결정
**1. 해소 주체는 점주이고 자기 매장 case만 다룬다.** 점주는 자기 PG 가맹점 관리 화면에서 실제 승인 여부를 확인할 수 있는 당사자다. 점주 세션 인증과 CSRF(`/owner/**`), 매장 소유 확인(`StoreManagement.requireOwned`, 남의 매장과 없는 매장은 같은 `404 STORE_NOT_FOUND`)이 이미 있어 플랫폼 운영자 주체(#91)를 기다리지 않는다. 대가는 점주에게 승인으로 해소해 주문을 `PAID`로 만들 동기가 있다는 점이다. 그래서 승인 해소에는 PG 승인 참조와 점주가 PG 화면에서 본 승인 금액이 필수다. 금액은 시도 금액과 대조하고, 행위자와 사유를 감사 기록에 남긴다. 여러 매장을 한꺼번에 처리하는 일은 플랫폼 운영자 주체가 생길 때 다룬다(제외).

**2. case로 넘기는 조건은 둘이다.**
- `QUERY_LIMIT_EXCEEDED`: 조회 결과가 불명인 채로 횟수가 상한에 닿았다.
- `AMOUNT_MISMATCH`: 조회 결과가 승인인데 provider가 알려 준 승인 금액(통화 포함)이 시도 금액과 다르다. 확정하지 않고 바로 넘긴다.
- 참조는 대조하지 않는다. `UNKNOWN` 시도에는 저장된 provider 참조가 없고, 조회 키(`paymentId`)는 우리가 보낸 값이라 비교할 대상이 없다.

**3. 조회 상한은 60회다(`resilientpos.payment.unknown-query-limit`, 기본 60).** 기본 간격 1분에서 약 1시간이고, #84가 observability에 적은 "결과 불명이 1시간 넘게 이어지면 사람이 본다"와 같은 시점에 사람이 볼 자리(case)가 생긴다. 실제 PG를 근거로 삼지 못해 이 운영 기준에 맞췄다. 경과 시간이 아니라 횟수로 세는 이유는 둘이다. 작업이 오래 멈췄다 돌아와도 한 번 조회하고 바로 넘기지 않고, provider에 실제로 60번 물었다는 근거가 남는다. 대가로 간격 설정을 바꾸면 상한이 뜻하는 시간도 같이 바뀐다. 실제 PG 어댑터 PR이 조회 타임아웃과 함께 이 값을 다시 본다.

**4. 넘긴 시도는 `payment`가 더 조회하지 않는다.** 상한은 "조회를 멈추고 사람에게 넘기는 시점"이라는 한 가지 뜻이다. 금액이 다른 시도도 같은 불일치를 매분 다시 보지 않는다. 넘긴 시도는 `UNKNOWN`에 머물고 reconciliation의 결론으로만 확정된다. provider가 나중에 결과를 알게 돼도 점주가 해소해야 끝난다.

**5. 승인 요청 응답의 금액이 다르면 확정하지 않고 `UNKNOWN`으로 둔다.** `PaymentGateway`의 승인 결과(`AuthorizationOutcome.Approved`)에 provider가 승인한 금액을 싣는다. 그러면 승인 요청(`authorize`) 응답에도 금액이 오므로 그 금액도 대조한다. 다르면 `APPROVED`로 굳히지 않고 `markUnknown`을 적용한다. 그대로 승인하면 주문의 `markPaid`가 `PAYMENT_APPROVAL_MISMATCH`로 거절해 메시지가 DLT로 간다. provider가 분명히 승인했는데도 "믿을 수 없으니 모른다"로 다루는 해석이고, `PaymentAttempt` KDoc과 domain-model §2에 이 해석을 적는다. 다음 조회에서 다시 불일치가 나오면 곧바로 `AMOUNT_MISMATCH`로 넘어간다.
- `UNKNOWN`이 된 사유를 시도 행에 남긴다(`unknown_reason`). 값은 `RESULT_UNCONFIRMED`(전송 실패·시간 초과처럼 결과를 받지 못함)와 `AMOUNT_MISMATCH`(받은 승인의 금액이 다름) 둘이다. `UNKNOWN`이 될 때 정하고 그 뒤로 바꾸지 않는다. 승인 요청 응답의 금액·참조는 WARN 로그에 남긴다.

**6. case 상태는 System Design §5의 다섯 개다: `OPEN → INVESTIGATING → AUTO_REPAIRED 또는 MANUAL_REVIEW → RESOLVED`.**
- 분류 규칙 v1은 두 사유 모두 `MANUAL_REVIEW`로 보낸다. 두 조건 모두 사람 없이 안전하게 내릴 결론이 없기 때문이다. case를 여는 명령이 `OPEN` 생성, `INVESTIGATING`(규칙 v1 평가), `MANUAL_REVIEW` 분류를 감사 기록 세 줄로 남기고 한 커밋에 처리한다. `OPEN`·`INVESTIGATING`은 감사 기록에만 보이고 행의 상태로 관측되지 않는다.
- `AUTO_REPAIRED`는 enum 값과 상태 모델 문서에만 있다. 그리로 가는 전이와 규칙은 자동 조치 규칙을 만드는 PR이 더한다.
- 분류는 사유에 대한 else 없는 `when`이다. 사유가 늘면 분류를 정하지 않고는 컴파일되지 않는다. 규칙이 하나라 전략 객체로 올리지 않는다.
- case는 분류 규칙 버전(정수, 지금 1)을 저장한다. 규칙이 바뀌어도 옛 case가 왜 `MANUAL_REVIEW`로 갔는지 남는다. 여는 조건 자체는 버전 대신 사유와 그때의 `query_count`를 근거로 남긴다. 상한은 설정 값이라 버전을 붙여도 설정 이력과 맞출 수 없다.

**7. 해소 명령.** 점주는 결론과 근거를 낸다.
- `APPROVED`: PG 승인 참조와 PG 화면에서 본 승인 금액이 필수다. 승인 금액이 case가 넘겨받은 시도 금액(통화 포함)과 다르면 거절한다(`RECONCILIATION_AMOUNT_MISMATCH`). 금액이 정말 다르면 점주는 PG에서 취소한 뒤 `DECLINED`로 해소하고, 그 경위를 사유에 적는다.
- `DECLINED`: 참조는 선택이다. 돈이 움직이지 않았거나 PG에서 취소했다는 점주의 확인이다.
- 사유는 둘 다 필수이고 500자 이하다. 결론에 필요한 값이 빠졌거나 사유가 비었거나 길면 `INVALID_RECONCILIATION_RESOLUTION`으로 거절한다.
- 대조는 case aggregate가 한다. "근거·규칙·감사 없이 해결하지 않는다"가 case의 불변식이기 때문이다. 시도 금액은 바뀌지 않는 값이라 case가 넘겨받은 사본으로 대조해도 정본과 어긋나지 않는다.
- `MANUAL_REVIEW`에서만 해소한다. 이미 `RESOLVED`인 case에 같은 결론이 다시 오면 기존 결과를 돌려주고 감사 기록을 더하지 않는다(응답을 잃은 재요청). 다른 결론이면 `RECONCILIATION_CASE_ALREADY_RESOLVED`(409)다.

**8. 해소는 reconciliation 트랜잭션 하나에 `payment`가 합류한다. §4.6을 개정한다.**
- 흐름: `ReconciliationManagement.resolve`가 `Tx.masterTx`를 연다. case 행을 매장 조건으로 잠그고(`findByIdAndStoreIdForUpdate`, 대기 3초) aggregate가 결론을 근거와 대조한다. 이어 `PaymentManagement.resolveFromReconciliation`을 부르고, 그 메서드는 `REQUIRED`로 합류한다. payment는 시도 행을 잠그고 확정하며, 같은 커밋에 발행 기록을 남긴다. 마지막으로 case를 `RESOLVED`로 저장한다. 결제 확정과 case 해소가 한 커밋이라 둘이 어긋나는 틈이 없다.
- 잠금 순서는 case 행 다음 시도 행으로 고정이다. 시도 행을 잠그는 다른 경로(조회 확정, 승인 적용, 재결제)는 case 행을 잠그지 않아 순환 대기가 없다.
- §4.6의 "허용되는 동기 쓰기 호출" 목록에 `reconciliation → payment`의 이 command 하나를 더한다. 조건은 `sync → order`와 같다. 호출자가 트랜잭션을 소유하고, 호출된 Facade는 `REQUIRED`로 합류하며, `REQUIRES_NEW`로 커밋을 나누지 않는다. "트랜잭션 안에서만 부른다"는 KDoc 계약이고 검사 코드는 없다(`createInCallerTransaction`과 같다). 독립 배포로 나눌 때 이 경로도 `sync → order`와 함께 다시 본다. 반대 방향(`payment → reconciliation`)은 이벤트다.
- `resolveFromReconciliation`은 provider를 부르지 않으므로 `NOT_SUPPORTED`가 아니다.

**9. 주문까지의 경로는 바꾸지 않는다.** 승인 해소는 기존 `PaymentApproved → PaymentApprovedListener → markPaid`로 주문을 `PAID`로 만든다. 거절 해소는 시도를 `DECLINED`로 만들고 기존 재결제 endpoint(#89)가 열린다. `Order.markPaid`의 `ponytail:` 전제("한 회차의 승인은 하나")는 그대로다. reconciliation 해소도 `UNKNOWN`만 확정하고, `UNKNOWN`은 partial unique `ux_payment_attempt_inflight_authorization`의 술어 안에 있다. 그래서 해소 전에는 같은 회차에 새 시도가 들어가지 못한다. 표식의 해제 조건 문구만 고친다.

### payment
- **port**: `AuthorizationOutcome.Approved(providerReference, amount)`. `amount`는 provider가 승인했다고 말한 금액(통화 포함)이다.
- **`FakePaymentGateway`**: 최소 단위 끝자리로 일곱 경로를 낸다. "1단위 많음"은 시도 금액에 그 통화의 최소 단위 하나를 더한 금액이다.

| 끝자리 | `authorize` | `query` |
|---|---|---|
| 1 | `Declined` | 부르지 않음 |
| 2 | `Unknown` | `Approved`(같은 금액) |
| 3 | `Unknown` | `Declined` |
| 4 | `Unknown` | `Unknown` |
| 5 | `Unknown` | `Approved`(1단위 많음) |
| 6 | `Approved`(1단위 많음) | `Approved`(1단위 많음) |
| 그 밖 | `Approved`(같은 금액) | `authorize`와 같다 |

  끝자리 5·6은 지금 승인 경로다. 그 금액으로 승인을 기대하는 기존 테스트 픽스처는 다른 끝자리로 옮긴다.
- **aggregate `PaymentAttempt`**: `queryCount`, `unknownReason`, `reconciliationRequestedAt`(넘긴 시각)을 더한다.
  - `markUnknown(reason)`은 사유를 받는다.
  - 불명 조회 기록: `UNKNOWN`이고 넘기지 않은 시도만 받는다. `queryCount`를 하나 올리고, 상한에 닿으면 넘긴 시각을 정하며 `PaymentReconciliationRequested(QUERY_LIMIT_EXCEEDED)`를 낸다. 상한 전이면 사실이 없다.
  - 금액 불일치 넘김: `UNKNOWN`이고 넘기지 않은 시도만 받는다. `queryCount`를 하나 올리고, 넘긴 시각과 함께 `PaymentReconciliationRequested(AMOUNT_MISMATCH, 조회가 알려 준 금액·참조)`를 낸다.
  - 조회 확정(`confirmApproved`·`confirmDeclined`): 넘기지 않은 `UNKNOWN`만 받는다.
  - reconciliation 결론 적용(승인·거절 두 전이): 넘긴 `UNKNOWN`만 받는다. 결과 상태와 사실은 조회 확정과 같다(`APPROVED`·참조·`PaymentApproved`, `DECLINED`·참조·`PaymentEvent.Declined`). 넘기지 않은 `UNKNOWN`이면 `PAYMENT_NOT_IN_RECONCILIATION`(CONFLICT)이다. case는 넘긴 시도에만 생기므로 정상 경로에서는 오지 않는다. 결말이 난 시도면 기존 `PAYMENT_RESULT_ALREADY_APPLIED`다.
  - 넘김은 조회 확정과 같은 이유로 "provider 결과는 한 번만 적용한다"를 깨지 않는다. `UNKNOWN`은 결과가 없다는 기록이고, 결과를 처음 적용하는 것은 reconciliation의 결론이다.
- **`PaymentManagement.resolveUnknown`**(#84 Facade)
  - 넘긴 시도면 provider를 부르지 않고 지금 상태를 돌려준다.
  - 조회 결과가 불명이면 `Tx.masterTx`에서 행을 잠그고 다시 확인한 뒤 불명 조회를 기록한다. #84의 "불명이면 아무것도 쓰지 않는다"가 "횟수만 쓴다"로 바뀐다. 상한에 닿아 넘기면 같은 커밋에 발행한다.
  - 조회 결과가 승인이고 금액이 다르면 같은 방식으로 금액 불일치 넘김을 적용한다.
  - 금액이 같은 승인과 거절은 지금처럼 확정한다.
- **`PaymentManagement.authorize`의 적용(`applyOutcome`)**: 승인 금액이 다르면 `markUnknown(AMOUNT_MISMATCH)`를 적용한다.
- **`PaymentManagement.resolveFromReconciliation(PaymentCommand.ResolveFromReconciliation(paymentId, conclusion, providerReference))`**
  - 결과는 `PaymentCommand.ResolveFromReconciliation.Result(paymentId, status)`다. `conclusion`은 커맨드 안의 enum(`APPROVED`·`DECLINED`)이다. payment가 reconciliation 타입을 알면 순환이 생기므로 자기 타입으로 받는다.
  - `Tx.masterTx`(합류)에서 `findByIdForUpdate`로 잠그고 다시 읽는다. 결말이 난 시도면 쓰지 않고 지금 상태를 돌려준다. 넘긴 `UNKNOWN`이면 결론 전이를 저장하고 발행한다.
  - 호출자는 결과 상태가 결론과 같은지 확인한다. 다르면 모순이라(넘긴 시도는 이 경로로만 확정된다) 예외를 던져 둘 다 롤백한다.
- **주기 작업 `UnknownPaymentResolver`**: 대상 조회에 `reconciliation_requested_at is null`을 더한다. `kind`·`status` 리터럴 렌더와 V013 인덱스 사용은 그대로다. 같은 주기에 넘긴 뒤 아직 `UNKNOWN`인 승인 시도 수를 세어 gauge로 낸다.

### contract
- **`PaymentReconciliationRequested`**(새 이벤트, `@Externalized`, 토픽 `payment.reconciliation-requested`, key `orderId`)
  - 필드: `eventId`, `occurredAt`(넘긴 시각), `paymentId`·`orderId`(raw `UUID`), `storeId`, `terminalId`, `checkoutNo`, `amount`(시도 금액), `reason`(`PaymentReconciliationReason`), `unknownReason`(`PaymentUnknownReason`), `queryCount`, `reportedAmount`(`AMOUNT_MISMATCH`일 때 조회가 알려 준 승인 금액, 아니면 null), `reportedProviderReference`(같은 조건, 아니면 null).
  - 직렬화는 기존 contract 이벤트(`PaymentApproved`)의 `Money`·`StoreId`·`TerminalId` 형식과 같다.
  - #78이 이 이벤트의 golden fixture를 붙일 수 있게 이 필드 목록을 계약으로 삼는다. 이 PR의 consumer 통합 테스트는 이 형식으로 직렬화된 메시지를 받는다.
- **새 enum**
  - `PaymentReconciliationReason`(`QUERY_LIMIT_EXCEEDED`, `AMOUNT_MISMATCH`)
  - `PaymentUnknownReason`(`RESULT_UNCONFIRMED`, `AMOUNT_MISMATCH`)
  - 둘 다 `PaymentAttemptKind`처럼 `contract`에 둔다. `payment`의 aggregate와 이벤트가 함께 쓰기 때문이다.
- **`ModuleStructureTest`**: 새 이벤트의 정규 이름을 리터럴로 고정한다.
- **`resilientpos.kafka.topics`**: `payment.reconciliation-requested`를 더한다. `KafkaConfiguration`이 토픽과 DLT를 만든다.

### reconciliation
- **aggregate `ReconciliationCase`**(식별자 `ReconciliationCaseId`). 모듈에 처음 생기는 코드라 `order`·`payment`의 계층(`internal/domain`, `internal/application`, `internal/adapter/persistence/{command,query}`, `internal/adapter/web`)을 그대로 따른다.
  - 루트: 상태, 사유, 분류 규칙 버전, 그리고 넘겨받은 근거(`paymentId`, `orderId`, `storeId`, `terminalId`, `checkoutNo`, 시도 금액, 불명 사유, 조회 횟수, 조회가 알려 준 금액·참조).
  - 자식: 감사 기록 `ReconciliationCaseAction`. 상태 전이마다 한 줄이다. 전이한 뒤 상태, 행위자 종류(`SYSTEM`·`OWNER`), 점주 ID, 시각을 담는다. 해소 줄에는 결론·참조·승인 금액·사유가 더 들어간다. 기록은 넣기만 하고 바꾸지 않는다. 수가 상태 수로 한정돼 루트가 목록으로 든다.
  - 전이는 여는 팩토리와 해소 둘이다. 해소의 멱등 처리는 결정 7이다.
- **`ReconciliationManagement`**(공개 Facade, 구체 클래스)
  - `open(ReconciliationCommand.Open)`: `Tx.masterTx`에서 같은 `paymentId`의 case가 있으면 그것을 돌려준다. 없으면 열어 저장한다. 같은 사실이 겹쳐 오면 unique `payment_id`가 두 번째를 막는다. 커밋에서 난 `DataIntegrityViolationException`은 Kafka 재시도 목록에 있어 다시 처리되고, 두 번째 시도는 기존 case를 찾는다. 새로 열었을 때만 counter와 INFO 로그를 남긴다.
  - `resolve(ReconciliationCommand.Resolve)`: 결정 8의 흐름이다.
- **consumer `PaymentReconciliationRequestedListener`**(`@KafkaListener`, 그룹 `reconciliation.payment-reconciliation-requested`): 사실을 `ReconciliationCommand.Open`으로 바꿔 부르기만 한다. 재시도·DLT는 기존 `KafkaConfiguration`의 오류 처리를 따른다.
- **조회 `ReconciliationQueryFacade.findReviewable(ReconciliationQuery.FindReviewable(storeId))`**: `ReconciliationCaseView` 목록을 돌려준다. 그 매장의 `MANUAL_REVIEW` case를 최신순(`id` 내림차순)으로 최대 100건 읽는다. 미해소 건은 점주가 처리하면 줄어드는 목록이라 페이지를 두지 않는다. 100건을 넘는 매장이 생기면 페이지를 더한다. 해소된 case의 이력 조회는 제외다.
- **점주 API**(`reconciliation.internal.adapter.web`, 모든 endpoint는 먼저 `requireOwned`)
  - `GET /owner/v1/stores/{storeId}/reconciliation-cases`: 미해소 목록. 항목은 `caseId`, `paymentId`, `orderId`, `terminalId`, `checkoutNo`, `reason`, `unknownReason`, `status`, `ruleVersion`, `amount`·`currency`(시도), `queryCount`, `reportedAmount`·`reportedCurrency`·`reportedProviderReference`(있을 때), `openedAt`이다.
  - `POST /owner/v1/stores/{storeId}/reconciliation-cases/{caseId}/resolve`: 본문은 `decision`, `providerReference`, `approvedAmount`, `approvedCurrency`, `reason`이다. 응답은 `caseId`, `status`, `decision`, `paymentId`, `paymentStatus`다.
  - 금액은 기존 응답처럼 통화 기준 decimal string과 통화 코드다. 다른 매장의 case와 없는 case는 같은 `404 RECONCILIATION_CASE_NOT_FOUND`다.
  - 해소가 커밋된 뒤 Controller가 감사 INFO 한 줄을 남긴다(operator-authentication 결정 11과 같은 모양). 항목은 `ownerId`, `action=RECONCILIATION_CASE_RESOLVED`, `storeId`, `caseId`, `paymentId`, `decision`, `paymentStatus`다. 사유 원문은 로그에 남기지 않는다. 자유 입력이라 개인정보가 섞일 수 있고, DB 감사 기록이 정본이다.
  - REST Docs를 붙인다.

### 계약 요약
- **새 공개 타입**
  - `PaymentCommand.ResolveFromReconciliation`(+`Result`, `Conclusion`)
  - `contract`의 `PaymentReconciliationRequested`·`PaymentReconciliationReason`·`PaymentUnknownReason`
  - `reconciliation`의 `ReconciliationManagement`, `ReconciliationQueryFacade`, `ReconciliationCommand`, `ReconciliationQuery`, `ReconciliationCaseView`, `ReconciliationCaseId`, `ReconciliationCaseStatus`, `ReconciliationDecision`
- **새 설정 키**: `resilientpos.payment.unknown-query-limit`
- **새 토픽**: `payment.reconciliation-requested`와 그 DLT
- **새 오류 code**
  - `PAYMENT_NOT_IN_RECONCILIATION`(409)
  - `PAYMENT_IN_RECONCILIATION`(409): 이미 넘긴 시도에 조회 확정·불명 기록·넘김이 다시 오면 aggregate가 거절한다. Facade가 잠근 뒤 넘김 여부를 다시 확인하므로 정상 경로에서는 오지 않는다(2026-10-20 범위 조정)
  - `RECONCILIATION_CASE_NOT_FOUND`(404)
  - `INVALID_RECONCILIATION_RESOLUTION`(400)
  - `RECONCILIATION_AMOUNT_MISMATCH`(422)
  - `RECONCILIATION_CASE_ALREADY_RESOLVED`(409)
- **스키마**: `t_payment_attempt` 컬럼 셋, 새 표 둘(스키마 변경 검토)
- **바뀌지 않는 것**: `PaymentAttemptStatus`에 새 값이 없다. 기존 이벤트의 `event_type`·`listener_id`도 바뀌지 않는다.

### 실패와 복구
- **넘김 트랜잭션 실패**: 횟수도 넘김도 남지 않고 다음 주기에 다시 한다.
- **case 열기 실패**: consumer가 실패하면 기존 Kafka 재시도·DLT로 간다. 넘김은 이미 커밋됐으므로 그동안 시도는 `UNKNOWN`이고 조회 대상이 아니다. pending gauge가 이 시도를 센다. DLT 재투입은 기존 절차(#91 전에는 수동)다.
- **해소 실패**: 결정 8의 한 트랜잭션이 통째로 롤백되고 점주가 다시 요청한다.
- **작업이 멈춘 경우**: 결과 불명이 쌓일 뿐 잘못 확정되지 않는다.

### 관측성
- **gauge `payment.attempt.reconciliation.pending`**(Prometheus `payment_attempt_reconciliation_pending`): 넘긴 뒤 아직 `UNKNOWN`인 승인 시도 수다. #84의 `payment.attempt.unknown`과 같은 주기에 세어 갱신하므로 scrape가 DB를 부르지 않는다. 기존 gauge의 뜻(결과 불명 승인 시도 전체)은 바꾸지 않는다.
- **counter**
  - `reconciliation.case.opened`(태그 `reason`): 새로 열었을 때만 센다.
  - `reconciliation.case.resolved`(태그 `decision`): 처음 해소했을 때만 센다.
  - counter는 재기동하면 0부터라 미해소 건수는 gauge로 본다.
- **로그**
  - 넘김 WARN: `paymentId`·`orderId`·`reason`·`queryCount`, 불일치면 알려 준 금액·참조.
  - 승인 요청 금액 불일치 WARN: `paymentId`·기대 금액·응답 금액·참조.
  - case 열림 INFO: `caseId`·`paymentId`·`storeId`·`reason`·`status`·`ruleVersion`.
  - 해소 감사 INFO: 위.
  - #84의 "조회로도 불명" WARN에 `queryCount`를 더한다.
- **알림 조건**(observability wiki, 알림 규칙 파일은 아직 없다)
  - `max(payment_attempt_unknown) - max(payment_attempt_reconciliation_pending) > 0`이 2시간 넘게 이어지면 작업이 멈췄는지 본다. 정상이면 한 건은 상한(약 1시간) 안에 확정되거나 넘어간다.
  - `max(payment_attempt_reconciliation_pending) > 0`이 24시간 넘게 이어지면 점주가 해소하지 않은 것이라 플랫폼이 연락한다.
  - #84의 "결과 불명이 1시간 넘게 0보다 큼"은 이 둘로 바꾼다. 60회에 자동으로 넘기므로 옛 조건은 점주가 아직 해소하지 않은 건까지 울린다.

## 제외
- 플랫폼 운영자 주체(#91)와 여러 매장 일괄 해소. 결정 1.
- `REQUESTED`에 멈춘 시도(승인 요청 consumer가 끝내 실패해 DLT에 간 것). provider를 실제로 불렀는지 모르는 상태의 조회와 "그런 요청 없음" 처리를 정해야 한다. #84는 그 처리를 실제 PG 어댑터 PR로 미뤘다. 단서는 DLT 메시지이고 재투입은 기존 절차다.
- 주문 쪽 승인 불일치(`PAYMENT_APPROVAL_MISMATCH`)로 case를 여는 일. `PaymentApproved` KDoc이 말하는 경로다. 그 해소에는 취소·환불 시도가 필요한데 그것을 만드는 코드가 없다. 결정 5로 금액이 다른 승인은 `APPROVED`로 굳지 않으므로, 이 경로는 회차·통화 같은 다른 불일치만 남는다.
- 자동 조치 규칙과 `AUTO_REPAIRED`로 가는 전이. 결정 6.
- 넘긴 뒤의 재조회와 provider가 늦게 확정한 경우의 자동 해소. 결정 4.
- 해소된 case의 이력 조회 API, 점주 알림(푸시·메일), 담당자 배정.
- 단말 화면이 "점주 확인 필요"를 보여 주는 일. 단말은 지금처럼 결제 시도 조회(#89)로 `UNKNOWN`을 본다.
- `PaymentEvent.Declined`·`Unknown`의 `contract` 이동. reconciliation이 결말 사실을 받을 일이 없다(결정 4·8).
- 실제 PG 어댑터, 조회 타임아웃, 실제 PG 근거로 다시 정할 상한 값.
- 감사 표의 보존 기간과 사유 원문 마스킹.

## 관련 문서
- [payment-unknown-resolution](2026-10-01-payment-unknown-resolution.md)(제외 절이 이 작업의 출발점)
- [payment-status-query](2026-10-06-payment-status-query.md)(재결제 endpoint)
- [operator-authentication](2026-10-06-operator-authentication.md)(점주 인증, 감사 로그 모양)
- [kafka-event-redelivery](2026-09-26-kafka-event-redelivery.md)(consumer 재시도·DLT)

## 변경 경로
- src/main/kotlin/com/resilientpos/payment/**
- src/main/kotlin/com/resilientpos/contract/**
- src/main/kotlin/com/resilientpos/reconciliation/**
- src/main/java/com/resilientpos/reconciliation/package-info.java
- src/main/kotlin/com/resilientpos/order/internal/domain/Order.kt
- src/main/resources/application.yaml
- src/main/resources/db/migration/**
- src/test/kotlin/com/resilientpos/**
- src/test/resources/**
- docs/spec/2026-10-20-payment-reconciliation-case.md
- docs/wiki/domain-model.md
- docs/wiki/entity-map.md
- docs/wiki/observability.md

## 인수 조건
1. `FakePaymentGateway`가 위 표대로 답한다. 끝자리 1~4와 그 밖은 지금과 같은 결말이고, 승인에는 금액이 실린다.
2. 승인 요청 응답의 금액이 시도와 다르면 시도는 `UNKNOWN`이고 `unknown_reason`은 `AMOUNT_MISMATCH`다. 응답이 불명이면 `RESULT_UNCONFIRMED`다.
3. 조회가 불명이면 `query_count`가 하나 오른다. 상한에 닿는 조회에서 넘긴 시각이 정해지고, `PaymentReconciliationRequested(QUERY_LIMIT_EXCEEDED, queryCount = 상한)`의 발행 기록이 같은 커밋에 남는다. 상한 전에는 발행 기록이 없다.
4. 조회가 다른 금액의 승인이면 확정하지 않는다. 바로 넘기고 `PaymentReconciliationRequested(AMOUNT_MISMATCH, 알려 준 금액·참조)`의 발행 기록이 같은 커밋에 남는다.
5. 넘긴 시도는 주기 작업이 고르지 않고, `resolveUnknown`에 직접 넘겨도 provider를 부르지 않는다. 대상 조회의 `kind`·`status`는 여전히 리터럴로 렌더된다.
6. 같은 `paymentId`의 사실이 두 번 와도 case는 하나다. 열린 case는 `MANUAL_REVIEW`이고 분류 규칙 버전 1이며, 감사 기록 세 줄(`OPEN`·`INVESTIGATING`·`MANUAL_REVIEW`, 행위자 `SYSTEM`)을 가진다.
7. 점주가 자기 매장의 case를 `APPROVED`(참조·같은 금액·사유)로 해소하면 한 커밋에 다음이 모두 남는다. case는 `RESOLVED`가 되고 감사 기록(`OWNER`)이 생긴다. 시도는 `APPROVED`와 그 참조가 되고 `PaymentApproved` 발행 기록이 생긴다. 그 뒤 주문이 `PAID`가 된다(Kafka 포함 통합).
8. `DECLINED`로 해소하면 시도가 `DECLINED`, 주문은 `CHECKOUT_REQUESTED`다. 그 시도의 재결제가 승인되면 주문이 `PAID`가 된다.
9. 승인 금액이 다르면 `422 RECONCILIATION_AMOUNT_MISMATCH`이고 아무것도 바뀌지 않는다. 승인에 참조나 금액이 없거나, 사유가 비었거나 500자를 넘으면 `400 INVALID_RECONCILIATION_RESOLUTION`이다.
10. 다른 매장의 case와 없는 case는 `404 RECONCILIATION_CASE_NOT_FOUND`, 남의 매장 경로는 `404 STORE_NOT_FOUND`, 세션이 없으면 401이다.
11. 해소된 case에 같은 결론이 다시 오면 200과 같은 결과이고 감사 기록이 늘지 않는다. 다른 결론이면 `409 RECONCILIATION_CASE_ALREADY_RESOLVED`다.
12. 같은 case의 해소가 동시에 둘 와도 결제 확정과 감사 기록은 하나다.
13. 미해소 목록은 그 매장의 `MANUAL_REVIEW` case만 최신순으로 돌려준다.
14. 한 주기 뒤 gauge `payment.attempt.reconciliation.pending`이 넘긴 뒤 아직 `UNKNOWN`인 승인 시도 수와 같다. 새로 연 case와 처음 해소한 case가 counter를 하나씩 올린다.
15. 마이그레이션 뒤 기존 `UNKNOWN` 행의 `unknown_reason`은 `RESULT_UNCONFIRMED`, `query_count`는 0이다.
16. `./gradlew test` 전체와 `python3 scripts/check_pr.py --base origin/main --worktree`가 통과한다.

### Checkpoint와 검증 명령
| Checkpoint | 검증 명령 | 의존 |
|---|---|---|
| fake·port (1) | `./gradlew test --tests 'com.resilientpos.payment.internal.adapter.gateway.FakePaymentGatewayTest'` | 없음 |
| 시도 전이 (2~4 도메인) | `./gradlew test --tests 'com.resilientpos.payment.internal.domain.*'` | 없음 |
| payment Facade·작업 (2~5, 14 gauge) | `./gradlew test --tests 'com.resilientpos.payment.*'` | fake, 시도 전이 |
| case 도메인 (6, 9, 11) | `./gradlew test --tests 'com.resilientpos.reconciliation.internal.domain.*'` | 없음 |
| case 열기·해소 (6~8, 11, 12, 14 counter) | `./gradlew test --tests 'com.resilientpos.reconciliation.*'` | payment Facade·작업, case 도메인 |
| 점주 API (9~11, 13) | `./gradlew test --tests 'com.resilientpos.reconciliation.internal.adapter.web.*'` | case 열기·해소 |
| 마이그레이션 (15) | `./gradlew test --tests 'com.resilientpos.SchemaMigrationTest'` | 없음 |
| 전체 (16) | `./gradlew test` · `python3 scripts/check_pr.py --base origin/main --worktree` | 모두 |

## 스키마 변경 검토
두 마이그레이션을 더한다. 번호는 작업 시작 시 main의 마지막(V015) 다음이다. 머지 전에 main이나 열린 PR이 같은 번호를 쓰면 옮긴다.

`V016__payment_reconciliation_handover.sql`
```sql
set local lock_timeout = '3s';
alter table t_payment_attempt
    add column query_count integer not null default 0,
    add column unknown_reason varchar(32),
    add column reconciliation_requested_at timestamptz;
update t_payment_attempt set unknown_reason = 'RESULT_UNCONFIRMED' where status = 'UNKNOWN';
```

`V017__reconciliation_case.sql`
```sql
create table t_reconciliation_case (
    id uuid primary key,
    payment_id uuid not null,
    order_id uuid not null,
    store_id uuid not null,
    terminal_id uuid not null,
    checkout_no integer not null,
    reason varchar(32) not null,
    status varchar(32) not null,
    rule_version integer not null,
    amount numeric(19,4) not null,
    currency char(3) not null,
    unknown_reason varchar(32) not null,
    query_count integer not null,
    reported_amount numeric(19,4),
    reported_currency char(3),
    reported_provider_reference varchar(255),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz,
    constraint ux_reconciliation_case_payment unique (payment_id)
);
create index ix_reconciliation_case_store on t_reconciliation_case (store_id, status, id);

create table t_reconciliation_case_action (
    id uuid primary key,
    case_id uuid not null,
    status varchar(32) not null,
    actor_kind varchar(16) not null,
    owner_id uuid,
    decision varchar(16),
    provider_reference varchar(255),
    approved_amount numeric(19,4),
    approved_currency char(3),
    note varchar(500),
    occurred_at timestamptz not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz
);
create index ix_reconciliation_case_action_case on t_reconciliation_case_action (case_id, id);
```

질문별 답(도메인 모델 §4.7):
1. **도출할 수 있는가.**
   - `query_count`: 조회 횟수는 어디에도 저장되지 않는다. 로그는 정본이 아니다.
   - `unknown_reason`: 승인 요청 응답의 금액은 저장되지 않아 "금액 불일치로 불명"을 다른 컬럼으로 알 수 없다.
   - `reconciliation_requested_at`: "넘겼다"는 사실은 `t_reconciliation_case`에도 생긴다. 하지만 `payment`는 다른 모듈의 표를 읽지 않고, 그 표는 넘김 커밋 뒤에 이벤트로 생긴다. 같은 트랜잭션에서 조회 대상에서 빼려면 시도 행에 있어야 한다.
   - case의 근거 컬럼(금액·불명 사유·조회 횟수·알려 준 금액)은 넘긴 시점의 사본이다(질문 5). 알려 준 금액·참조는 다른 곳에 없다.
   - `t_reconciliation_case_action`은 행위자·시각·결론·사유를 남기는 유일한 기록이다.
   - `owner_id`는 행위자 ID라 `t_store.owner_id`에서 도출하지 않는다. 지금은 매장당 점주가 하나라 같은 값이지만, 감사 기록은 그 시점에 누가 했는지를 남긴다.
   - 근거 컬럼과 감사 컬럼을 따로 표(evidence)로 빼지 않는다. 출처가 payment 하나라 모양이 고정이다. POS·provider 같은 출처가 늘면 그 PR이 뺀다.
   - `occurred_at`은 `created_at`과 다르다. 여는 줄의 시각은 consumer가 처리한 때가 아니라 넘긴 시각(이벤트의 `occurredAt`)이다. 감사 컬럼(`created_at`)에 업무 시각을 싣지 않는다.
2. **지금 읽는가.**
   - `query_count`: 상한 판정과 넘김 이벤트가 읽는다.
   - `unknown_reason`: 넘김 이벤트가 읽고, case 근거로 점주 목록에 보인다.
   - `reconciliation_requested_at`: 주기 작업의 대상 조회, gauge, 조회 확정·결론 적용의 허용 판정이 읽는다.
   - case 컬럼은 점주 목록·해소 대조·결론 적용이 읽는다. `rule_version`은 점주 목록이 보여 준다.
   - 감사 기록은 이 PR에서 읽는 API가 없다. "감사 없이 해결하지 않는다"(domain-model §2)는 불변식이 쓰기 시점에 요구하는 기록이고, 나중에 채울 수 없는 값이라 질문 4로 둔다.
3. **기존 행은 채울 수 있는가.**
   - `query_count`는 0이다. 기존 `UNKNOWN` 행은 이미 여러 번 조회됐을 수 있지만 기록이 없어 0부터 센다. 그래서 배포 뒤 최대 60회(약 1시간) 더 조회한 뒤 넘어간다.
   - `unknown_reason`은 기존 `UNKNOWN` 행만 `RESULT_UNCONFIRMED`로 채운다. 이 PR 전에는 금액을 대조하지 않았으므로 불명 사유는 그것 하나였고, 그래서 정확히 채울 수 있다. 이미 확정된 행은 null로 둔다(기록 이전).
   - `reconciliation_requested_at`은 null이다(넘긴 적 없음).
4. **불변식의 방어선인가.**
   - unique `ux_reconciliation_case_payment`는 "시도 하나에 case 하나"의 마지막 방어선이다. 같은 사실이 재전달되거나 consumer가 동시에 처리해도 case가 둘이 되지 않는다. 멱등 키라 soft delete된 행에도 걸려야 해서 전체 unique로 둔다. case를 지우는 명령은 없다.
   - `reconciliation_requested_at`은 "넘긴 시도는 조회로 확정하지 않고 reconciliation의 결론으로만 확정한다"의 판정 값이다. 판정은 시도 행을 잠근 뒤 다시 읽어서 한다(질문 6).
   - 감사 기록은 "감사 없이 해결하지 않는다"의 기록이라 PostgreSQL에 둔다.
5. **정본은 하나인가.**
   - case의 근거 컬럼은 `t_payment_attempt`의 사본이다. 넘긴 시점의 사실을 근거로 고정하려는 것이다. 시도 금액·회차·매장은 바뀌지 않는 값이라 어긋나지 않는다.
   - `query_count`는 넘긴 뒤 바뀌지 않는다. 넘긴 시도는 조회하지 않기 때문이다.
   - "넘겼다"의 정본은 `reconciliation_requested_at`이고, case 행은 이벤트로 만든 사본이다. case가 없으면(consumer 실패) Kafka 재시도와 DLT가 복구 경로이고, pending gauge가 그 시도를 센다.
   - 결론 적용 뒤 시도 상태와 case의 결론은 한 커밋으로 함께 바뀐다(결정 8).
   - 매장 소속의 정본은 `t_store.owner_id`다. case의 `store_id`는 시도의 사본이고 바뀌지 않는다.
6. **읽고 판단한 뒤 쓰는 경로가 있는가.**
   - 불명 조회 기록·넘김·조회 확정·결론 적용은 모두 `findByIdForUpdate`로 시도 행을 잠근 뒤 `UNKNOWN`과 넘김 여부를 다시 확인한다. `t_payment_attempt`에는 `@Version`이 없고 row lock이 막는다.
   - case 열기는 있는지 확인한 뒤 넣는다. 겹치면 unique가 막고 재시도가 기존 case를 찾는다. 잠글 행이 없는 경합이지만 `Lock`을 쓰지 않는다. Kafka key가 `orderId`라 같은 시도의 사실은 한 파티션에서 차례로 처리되고, 남는 경합(재배치 중 중복)은 unique가 막기 때문이다.
   - 해소는 case 행을 매장 조건으로 잠근 뒤(대기 3초) 상태를 확인하고, 이어서 시도 행을 잠근다(대기 3초). 순서는 결정 8로 고정이다. `t_reconciliation_case`에는 `@Version`을 두지 않는다. 해소만 행을 바꾸고 그 경로는 row lock이 줄 세운다.
   - 감사 기록은 루트와 같은 트랜잭션에서 넣기만 한다.
7. **락과 테이블 크기.**
   - V016의 `add column`은 셋 다 메타데이터 변경이다. `query_count`는 상수 기본값, 나머지는 nullable이다. `ACCESS EXCLUSIVE`를 짧게 잡으며, 긴 트랜잭션 뒤에서 기다리며 뒤 쿼리를 세우지 않게 `lock_timeout` 3초를 둔다. 시간 초과로 실패하면 다시 배포한다.
   - 같은 마이그레이션의 `update`는 `UNKNOWN` 행만 고친다. V013 partial 인덱스가 그 행을 고르므로 원장 전체를 훑지 않고, 평소 `UNKNOWN`은 수십 건 이하다. 이 문장은 그 행들의 row lock을 잠깐 잡는다. 주기 작업이 같은 행을 잠그고 있으면 기다리고, 상한은 위 `lock_timeout`이다.
   - 트랜잭션 안에서 도는 마이그레이션이라 `CONCURRENTLY`가 없다.
   - V017은 새 표라 기존 쿼리를 막지 않는다. 제약을 기존 행에 거는 일이 없어 위반 행 검사는 없다.
8. **이전 버전과 함께 뜨는가.**
   - 이전 버전은 새 컬럼과 표를 모르고, JPA `validate`는 매핑된 컬럼만 본다. `query_count`는 기본값이 있어 이전 버전의 INSERT가 실패하지 않는다.
   - 롤링 배포 중 이전 버전이 만든 `UNKNOWN` 행은 `unknown_reason`이 null이다. 새 버전의 adapter는 `UNKNOWN`인데 null인 행을 `RESULT_UNCONFIRMED`로 읽는다. 이전 버전에는 금액 대조가 없으므로 정확하다. 코드 주석에 이유를 남긴다.
   - 이전 버전의 주기 작업은 넘김을 모르므로, 그 사이 넘긴 시도를 조회로 확정할 수 있다. 그러면 그 case의 해소는 결제가 이미 결말이 난 상태를 만난다. 같은 결론이면 닫히고, 다르면 롤백되어 점주가 결제의 결말과 같은 결론으로 다시 해소한다. 넘김은 60회 조회 뒤에야 일어나고 배포 창은 몇 분이라 드물다.
9. **payload에 실리는가.**
   - `query_count`·`unknown_reason`·넘긴 시각(`occurredAt`)은 새 이벤트 `PaymentReconciliationRequested`에 실린다. 첫 버전이라 하위 호환을 따질 이전 형식이 없고, 필드 목록이 계약이다(#78).
   - case 표는 이벤트나 캐시에 실리지 않는다. 점주 API 응답은 DTO다.
- 대조 기록: 미작성

## 검증
- **정상**
  - 끝자리 4: 상한(테스트 3)까지 조회한 뒤 case `MANUAL_REVIEW`가 된다. 점주가 승인 해소하면 주문이 `PAID`다.
  - 끝자리 5: 조회 한 번에 `AMOUNT_MISMATCH` case가 열린다.
  - 끝자리 6: 승인 요청이 `UNKNOWN(AMOUNT_MISMATCH)`, 다음 조회에서 case가 열린다.
  - 거절 해소 뒤 재결제가 승인된다.
- **실패**
  - 금액이 다른 승인 해소, 근거 누락, 다른 매장, 이미 다른 결론으로 해소된 case를 거절한다.
  - 같은 사실이 재전달돼도 case는 하나다.
  - 동시 해소는 한 번만 적용된다.
  - 넘긴 시도는 조회하지 않는다.
  - 해소 중 payment가 실패하면 case도 그대로다.
- 기존 테스트가 끝자리 5·6을 승인으로 기대했다면 픽스처를 옮긴다. 테스트 설정의 상한은 작게 둔다(3).
- 통합 테스트는 Testcontainers의 PostgreSQL·Redis·Kafka를 쓴다(Docker는 OrbStack).
- Kafka·Modulith: 새 `@Externalized` 이벤트와 consumer를 기존 설정(외부화, 재시도·DLT, `max-poll-records`)에 얹는다. [Spring Modulith 이벤트](https://docs.spring.io/spring-modulith/reference/events.html)와 [Spring Kafka 오류 처리](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html)로 대조하고, 근거를 결과 절에 남긴다.

## Wiki 영향
- [domain-model](../wiki/domain-model.md)
  - §2 `payment` 불변식에 "넘긴 시도는 reconciliation의 결론으로만 확정한다"와 결정 5의 해석(금액이 다른 승인은 결과 불명)을 더한다.
  - §2 `reconciliation` 행에 "점주가 자기 매장 case를 해소한다"를 더한다. 모듈 사이 규칙의 `resolveFromReconciliation` 문장을 실제 이름과 한 트랜잭션 합류로 고친다. `PaymentApproved → order·reconciliation`은 `order`만 받는 지금 사실로 고친다.
  - §1 용어 표의 `UNKNOWN` 행에서 "case는 아직 없다"를 지우고 상한·넘김을 적는다.
  - §3 용어집에 넘김(`reconciliation requested`), 불명 사유, 분류 규칙 버전을 더한다.
  - §4.3 주기 작업에 상한과 넘김을 더한다.
  - §4.6에 두 번째 동기 쓰기 경로를 더한다(결정 8).
- [entity-map](../wiki/entity-map.md): `t_payment_attempt`의 새 컬럼과 `UNKNOWN → case` 흐름을 적고, `t_reconciliation_case`·`t_reconciliation_case_action`을 더한다.
- [observability](../wiki/observability.md): gauge 하나, counter 둘, 로그와 바뀐 알림 조건을 적는다.
- modulith-kafka-keywords(`docs/wiki/modulith-kafka-keywords.md`, 볼트): 새 토픽·consumer group·외부화 이벤트를 PR 전에 그 문서의 갱신 규칙대로 적는다.
- current-state(`docs/wiki/current-state.md`, 볼트)는 볼트 정본이라 이 저장소 diff에 들지 않는다. 머지 뒤 볼트에서 갱신한다.

## 분할 계획
한 개발 컨텍스트("조회로도 확정되지 않는 결제가 사람의 결론으로 수렴")다. 넘김(payment), 사실(contract), case와 해소(reconciliation), 점주 API는 서로 없으면 검증할 수 없어 나누지 않는다. 플랫폼 운영자(#91), 주문 쪽 불일치 case, 자동 조치 규칙은 후속이다.

2026-10-20 작업 시작 시 origin/main은 9c34aaf다. 동시에 진행 중인 작업과의 경로 겹침은 이렇다.
- 열린 PR #94(`refactor/modulith-2-1-2-resubmit`): `docs/wiki/domain-model.md`가 겹친다.
- `fix/contention-error-mapping`(#79, 아직 PR 전): `docs/wiki/entity-map.md`가 겹친다. 그 브랜치가 `platform.UniqueViolation`(unique 위반 판별)을 더한다. 이 PR은 case 열기 경합을 Kafka 재시도로 풀어 그 헬퍼가 필요 없고, 같은 것을 만들지 않는다.
- `test/event-contract-compatibility`(#78, 커밋 전): 이 PR의 새 contract 이벤트에 fixture를 붙일 쪽이다.

push·PR 생성·머지 직전에 다시 확인하고, 먼저 머지된 쪽에 맞춰 rebase한다. 마이그레이션 번호가 겹치면 옮긴다.

## TDD 검증 기록

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 1·fake·port: `FakePaymentGatewayTest`의 `승인에는 시도와 같은 금액이 실린다`·`끝자리가 5이면 …`·`끝자리가 6이면 …`·`1단위 많음은 그 통화의 최소 단위 하나다` | `./gradlew test --tests 'com.resilientpos.payment.internal.adapter.gateway.FakePaymentGatewayTest'`. `Approved(providerReference, amount)`를 선언하고 Fake가 항상 시도 금액을 싣게 한 상태에서 13건 중 3건 assertion 실패(끝자리 5 승인 요청이 Approved, 끝자리 6·USD 최소 단위 금액이 같은 금액) | 같은 명령 통과. 끝자리 5·6 분기와 `oneMinorUnitMore`(`Money`에 최소 단위 연산이 없어 Fake 안에 둠) 추가 | 아래 마지막 행 |
| 4·시도 전이: `PaymentAttemptAuthorizationTest`의 `미확인을 적용하면 받은 사유가 시도에 남고 …`, `PaymentAttemptConfirmationTest`의 `확정해도 UNKNOWN이 된 때 정한 사유를 지우지 않는다` | `./gradlew test --tests 'com.resilientpos.payment.internal.domain.*'`. `PaymentUnknownReason`·`unknownReason`·`markUnknown(reason, ...)`을 사유를 무시하는 최소 선언으로 둔 상태에서 28건 중 2건 assertion 실패(사유가 null) | 같은 명령 통과. `copy`가 사유를 받고 `markUnknown`만 정하며 확정 전이는 그대로 이어받음 | 아래 마지막 행 |
| 2·3·`PaymentManagement.authorize`: `PaymentManagementTest`의 `승인 응답의 금액이 시도와 다르면 …`·`승인 응답의 통화가 시도와 다르면 …`, `미확인을 적용하면 …`에 `RESULT_UNCONFIRMED` 단언 추가 | `./gradlew test --tests 'com.resilientpos.payment.PaymentManagementTest'`. 23건 중 2건 assertion 실패(다른 금액·다른 통화 승인이 APPROVED로 굳음). `RESULT_UNCONFIRMED` 단언은 사유 전달을 먼저 연결해 처음부터 통과(characterization) | 같은 명령 통과. `applyOutcome`이 금액(통화 포함) 불일치면 WARN 후 `markUnknown(AMOUNT_MISMATCH)` | 아래 마지막 행 |
| 5·영속: `PaymentAuthorizationIntegrationTest`의 `승인 응답의 금액이 시도와 다르면 UNKNOWN이고 사유는 AMOUNT_MISMATCH …`, `미확인이 나는 금액이면 …`에 `unknown_reason` 단언 추가 | `./gradlew test --tests 'com.resilientpos.payment.internal.application.PaymentAuthorizationIntegrationTest'`. adapter가 `applyResult`에 사유 대신 null을 넘기게 임시로 바꾼 상태에서 9건 중 2건 assertion 실패(`unknown_reason`이 null). 확인 뒤 되돌림 | 같은 명령 통과(9건). 엔티티 `unknown_reason` `@Enumerated(STRING)` 매핑, `toDomain()`이 UNKNOWN인데 null이면 `RESULT_UNCONFIRMED`로 읽음(롤링 배포 중 이전 버전 행) | 아래 마지막 행 |
| 15 일부·`SchemaMigrationTest`의 `V016은 기존 UNKNOWN 행의 unknown_reason을 RESULT_UNCONFIRMED로 채우고 query_count는 0으로 둔다`, `baseline 마이그레이션이 성공으로 기록된다`에 016 추가, `PaymentAttemptSchemaTest`의 컬럼 목록에 세 컬럼 추가 | `./gradlew test --tests 'com.resilientpos.SchemaMigrationTest'`. V016의 `update`를 임시로 주석 처리한 상태에서 V016 테스트 assertion 실패(`unknown_reason`이 null). 확인 뒤 되돌림. baseline 테스트는 016 적용으로 기대 목록이 어긋나 실패해 갱신 | 같은 명령 통과. 전체 `./gradlew test`에서 600건 중 `PaymentAttemptSchemaTest`만 실패해 컬럼 목록을 갱신 | 아래 마지막 행 |
| 위 동작 전체 회귀 | - | - | `./gradlew test --tests 'com.resilientpos.payment.*' --tests 'com.resilientpos.order.*' --tests 'com.resilientpos.SchemaMigrationTest' --tests 'com.resilientpos.ModuleStructureTest' --tests 'com.resilientpos.TransactionDeclarationTest'` BUILD SUCCESSFUL, 268건 통과·실패 0. 이 묶음에서 `resolveUnknown`의 금액 대조와 case 넘김, 조회 횟수·넘김 시각 컬럼 사용, 끝자리 5·6 픽스처 이동은 없음(기존 테스트가 5·6 금액으로 승인을 기대하지 않아 옮길 것이 없었다) |

| 3·시도 전이(넘김): `PaymentAttemptReconciliationTest`의 `상한 전의 불명 조회는 …`·`상한에 닿는 불명 조회는 …`·`상한이 하나면 …`·`금액 불일치 넘김은 …`·`UNKNOWN이 아닌 시도는 어느 넘김도 받지 않고 …`·`이미 넘긴 시도는 …`·`넘긴 시도는 조회 확정을 거절하고 …`·`새 시도와 재결제 시도의 조회 횟수는 0이고 …` | `./gradlew test --tests 'com.resilientpos.payment.internal.domain.*'`. `queryCount`·`reconciliationRequestedAt`·`PAYMENT_IN_RECONCILIATION`과 두 전이(`recordUnknownQuery`·`requestReconciliationForAmountMismatch`)를 상태·사실 없이 `Transition(this, emptyList())`만 돌려주는 최소 선언으로 둔 상태에서 36건 중 7건 assertion 실패(횟수·넘긴 시각·사실 없음, 거절 code 없음). 재결제 시도 0·null 단언은 처음부터 통과(characterization) | 같은 명령 통과(36건). 전이 둘과 `requireUnknownNotHandedOver`(조회 확정도 넘긴 시도를 `PAYMENT_IN_RECONCILIATION`으로 거절), 사실은 `checkNotNull(unknownReason)`으로 non-null 싣음 | 아래 마지막 행 |
| 3·4·5·`PaymentManagement.resolveUnknown`: `PaymentManagementTest`의 `조회로도 불명이면 상한 전에는 조회 횟수만 저장하고 …`(기존 "아무것도 저장하지 않는다" 단언을 횟수만 오르도록 고침)·`조회 횟수가 상한에 닿으면 …`·`조회가 다른 금액의 승인이면 …`·`조회가 다른 통화의 승인이면 …`·`넘긴 시도는 provider를 조회하지 않고 …`·`조회하는 동안 다른 호출이 먼저 넘겼으면 …`(둘) | `./gradlew test --tests 'com.resilientpos.payment.PaymentManagementTest'`. 생성자에 상한(`unknownQueryLimit`)만 받고 `resolveUnknown`은 그대로인 상태에서 29건 중 6건 assertion 실패(횟수 저장·넘김 사실 없음, 다른 금액 승인이 APPROVED 확정, 넘긴 시도를 조회). 잠근 뒤 이미 넘겼으면 쓰지 않는 단언 하나는 처음부터 통과(characterization) | 같은 명령 통과. `resolveUnknown`이 결과별로 확정/금액 불일치 넘김/불명 기록으로 나뉘고 잠근 뒤 "UNKNOWN이고 넘기지 않음"을 다시 확인, 커밋 뒤 WARN | 아래 마지막 행 |
| 3·4·5 영속·발행: `PaymentUnknownResolutionIntegrationTest`의 `조회로도 불명이면 query_count만 …`(기존 "행이 바뀌지 않는다"를 고침)·`조회 불명이 상한에 닿으면 …`·`조회가 다른 금액의 승인이면 …`·`승인 요청에서 UNKNOWN(AMOUNT_MISMATCH)이 된 시도도 …` | `./gradlew test --tests 'com.resilientpos.payment.internal.application.PaymentUnknownResolutionIntegrationTest'`. 토픽 `payment.reconciliation-requested`를 `resilientpos.kafka.topics`에 더하지 않은 상태에서 8건 중 1건 실패(발행 기록이 브로커 전송을 마치지 못함, 자동 토픽 생성이 꺼져 있음). 나머지(entity 매핑·Facade 기존 구현)는 characterization으로 통과 | 같은 명령과 `ModuleStructureTest` 통과(토픽 추가, 정규 이름 리터럴 추가) | 아래 마지막 행 |
| 5·14 gauge·대상 조회: `UnknownPaymentResolverIntegrationTest`의 `넘긴 시도는 작업이 고르지 않고 조회하지 않는다`·`한 주기 뒤 pending gauge가 …`, `대상 조회는 …리터럴로 렌더한다`에 `reconciliationRequestedAt` 단언 추가 | `./gradlew test --tests 'com.resilientpos.payment.internal.application.UnknownPaymentResolverIntegrationTest'`. 대상 조회가 넘긴 시도를 거르지 않고 pending 세기가 0을 돌려주는 최소 선언 상태에서 11건 중 3건 assertion 실패 | 같은 명령 통과(11건). 대상 조회에 `reconciliation_requested_at is null`, 세기에 `is not null`(둘 다 kind·status 리터럴 유지), gauge `payment.attempt.reconciliation.pending` | 아래 마지막 행 |
| 위 넘김 동작 회귀 | - | - | `./gradlew test --tests 'com.resilientpos.payment.*' --tests 'com.resilientpos.order.*' --tests 'com.resilientpos.SchemaMigrationTest' --tests 'com.resilientpos.ModuleStructureTest' --tests 'com.resilientpos.TransactionDeclarationTest' --tests 'com.resilientpos.platform.*'` 393건 중 1건 실패. `KafkaEventDeliveryIntegrationTest`의 `consumer가 듣는 토픽은 모두 설정 목록에 있어 앱이 만든다`가 새 토픽을 듣는 consumer가 아직 없어(reconciliation 모듈의 다음 동작) 실패한다. consumer를 더하면 해소된다 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
