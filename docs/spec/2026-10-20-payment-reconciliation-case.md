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
- 분류 규칙 v1은 두 사유 모두 `MANUAL_REVIEW`로 보낸다. 두 조건 모두 사람 없이 안전하게 내릴 결론이 없기 때문이다. case를 여는 명령이 `OPEN` 생성, `INVESTIGATING`(규칙 v1 평가), `MANUAL_REVIEW` 분류를 한 커밋에 처리하고 행에는 분류 결과만 남는다. `OPEN`·`INVESTIGATING`은 같은 커밋 안의 중간 상태라 관측되지도 따로 기록되지도 않는다. 그 전이는 연 시각(`opened_at`, 넘긴 시각)·분류 규칙 버전·상태가 말한다. 처음에는 전이마다 감사 줄을 남겼지만, 세 줄이 같은 시각에 새 정보 없이 생겨 없앴다(2026-10-20 범위 조정).
- `AUTO_REPAIRED`는 enum 값과 상태 모델 문서에만 있다. 그리로 가는 전이와 규칙은 자동 조치 규칙을 만드는 PR이 더한다.
- 분류는 사유에 대한 else 없는 `when`이다. 사유가 늘면 분류를 정하지 않고는 컴파일되지 않는다. 규칙이 하나라 전략 객체로 올리지 않는다.
- case는 분류 규칙 버전(정수, 지금 1)을 저장한다. 규칙이 바뀌어도 옛 case가 왜 `MANUAL_REVIEW`로 갔는지 남는다. 여는 조건 자체는 버전 대신 사유와 그때의 `query_count`를 근거로 남긴다. 상한은 설정 값이라 버전을 붙여도 설정 이력과 맞출 수 없다.

**7. 해소 명령.** 점주는 결론과 근거를 낸다.
- `APPROVED`: PG 승인 참조와 PG 화면에서 본 승인 금액이 필수다. 승인 금액이 case가 넘겨받은 시도 금액(통화 포함)과 다르면 거절한다(`RECONCILIATION_AMOUNT_MISMATCH`). 금액이 정말 다르면 점주는 PG에서 취소한 뒤 `DECLINED`로 해소하고, 그 경위를 사유에 적는다.
- `DECLINED`: 돈이 움직이지 않았거나 PG에서 취소했다는 점주의 확인이다. 참조는 선택이다. 다만 provider가 승인이라고 답한 적이 있는 case(넘김 사유나 불명 사유가 `AMOUNT_MISMATCH`)는 돈이 움직였을 가능성이 높아 PG 취소 참조가 필수다. 취소하지 않은 채 거절하면 재결제가 열려 이중 청구가 된다. 결과를 받지 못한 건은 PG에 거래가 없으면 줄 참조가 없어 선택으로 둔다(2026-10-20 범위 조정, multi-review).
- 사유는 둘 다 필수이고 500자 이하다. PG 승인 참조는 255자 이하다(저장 컬럼 `provider_reference varchar(255)`. 넘으면 커밋에서 실패해 500이 된다). 결론에 필요한 값이 빠졌거나 사유가 비었거나 사유·참조가 길거나 NUL 문자(U+0000, PostgreSQL varchar에 들어가지 못해 커밋에서 500이 된다)를 담으면 `INVALID_RECONCILIATION_RESOLUTION`으로 거절한다. 공백뿐인 참조는 없는 것으로 다룬다(2026-10-20 범위 조정, 참조 길이와 multi-review).
- 대조는 case aggregate가 한다. "근거·규칙·감사 없이 해결하지 않는다"가 case의 불변식이기 때문이다. 시도 금액은 바뀌지 않는 값이라 case가 넘겨받은 사본으로 대조해도 정본과 어긋나지 않는다.
- `MANUAL_REVIEW`에서만 해소한다. 이미 `RESOLVED`인 case에 같은 결론이 다시 오면 기존 결과를 돌려주고 해소 기록을 바꾸지 않는다(응답을 잃은 재요청). 다른 결론이면 `RECONCILIATION_CASE_ALREADY_RESOLVED`(409)다.
- 해소 기록(점주 ID, 해소 시각, 결론, 참조, 승인 금액, 사유)은 case 행의 컬럼이다. case당 해소는 한 번이라(`RESOLVED`는 끝 상태) 목록이 필요 없다. 재개·담당자 배정·2인 승인처럼 이력이 필요한 기능이 생기면 그 PR이 이력 표를 만들고 이 컬럼으로 기존 행을 채운다(2026-10-20 범위 조정).

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
  - 호출자는 결과 상태가 결론과 같은지 확인한다. 같으면(이미 같은 결말) 시도를 건드리지 않고 case만 해소한다. 다르면 `RECONCILIATION_PAYMENT_ALREADY_CONCLUDED`(409, 메시지에 결제 상태)로 거절해 둘 다 롤백한다. 넘긴 시도는 이 경로로만 확정되므로 롤링 배포 중에만 만나는 경우다(스키마 변경 검토 질문 8).
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
  - 루트: 상태, 사유, 분류 규칙 버전, 연 시각(넘긴 시각), 넘겨받은 근거(`paymentId`, `orderId`, `storeId`, `terminalId`, `checkoutNo`, 시도 금액, 불명 사유, 조회 횟수, 조회가 알려 준 금액·참조), 해소 기록(식별자 없는 값 객체 `Resolution`: 점주 ID, 해소 시각, 결론, 참조, 승인 금액, 사유. 해소 전에는 없다).
  - 자식 엔티티는 없다. 한 행이라 한 쿼리로 읽고 한 엔티티로 저장한다.
  - 전이는 여는 팩토리와 해소 둘이다. 해소의 멱등 처리는 결정 7이다.
- **`ReconciliationManagement`**(공개 Facade, 구체 클래스)
  - `open(ReconciliationCommand.Open)`: `Tx.masterTx`에서 같은 `paymentId`의 case가 있으면 그것을 돌려준다. 없으면 열어 저장한다. 같은 사실이 겹쳐 오면 unique `payment_id`가 두 번째를 막는다. 커밋에서 난 `DataIntegrityViolationException`은 Kafka 재시도 목록에 있어 다시 처리되고, 두 번째 시도는 기존 case를 찾는다. 새로 열었을 때만 counter와 INFO 로그를 남긴다.
  - `resolve(ReconciliationCommand.Resolve)`: 결정 8의 흐름이다.
- **consumer `PaymentReconciliationRequestedListener`**(`@KafkaListener`, 그룹 `reconciliation.payment-reconciliation-requested`): 사실을 `ReconciliationCommand.Open`으로 바꿔 부르기만 한다. 재시도·DLT는 기존 `KafkaConfiguration`의 오류 처리를 따른다.
- **조회 `ReconciliationQueryFacade.findReviewable(ReconciliationQuery.FindReviewable(storeId))`**: `ReconciliationCaseView` 목록을 돌려준다. 그 매장의 `MANUAL_REVIEW` case를 최신순(`id` 내림차순)으로 최대 100건 읽는다. 미해소 건은 점주가 처리하면 줄어드는 목록이라 페이지를 두지 않는다. 100건을 넘는 매장이 생기면 페이지를 더한다. 해소된 case의 이력 조회는 제외다.
- **점주 API**(`reconciliation.internal.adapter.web`, 모든 endpoint는 먼저 `requireOwned`)
  - `GET /owner/v1/stores/{storeId}/reconciliation-cases`: 미해소 목록. 항목은 `caseId`, `paymentId`, `orderId`, `terminalId`, `checkoutNo`, `reason`, `unknownReason`, `status`, `ruleVersion`, `amount`·`currency`(시도), `queryCount`, `reportedAmount`·`reportedCurrency`·`reportedProviderReference`(있을 때), `openedAt`이다.
  - `POST /owner/v1/stores/{storeId}/reconciliation-cases/{caseId}/resolve`: 본문은 `decision`, `providerReference`, `approvedAmount`, `approvedCurrency`, `note`(사유)다. 목록 응답의 `reason`(넘김 사유)과 한 단어가 두 뜻이 되지 않게 사유는 `note`라 부른다(2026-10-20 범위 조정, multi-review). 금액·통화 형식이 틀리면 기존 `Money` 변환의 오류 code(`INVALID_MONEY_FORMAT` 등)다. 응답은 `caseId`, `status`, `decision`, `paymentId`, `paymentStatus`다.
  - 금액은 기존 응답처럼 통화 기준 decimal string과 통화 코드다. 다른 매장의 case와 없는 case는 같은 `404 RECONCILIATION_CASE_NOT_FOUND`다.
  - 처음 해소가 커밋된 뒤 감사 INFO 한 줄을 남긴다(operator-authentication 결정 11과 같은 모양). 같은 결론의 재요청은 남기지 않는다(해소 건수를 이중으로 세지 않게). 처음 해소인지 아는 곳이 Facade(counter를 올리는 분기)라 이 줄은 Controller가 아니라 `ReconciliationManagement.resolve`가 트랜잭션 반환 뒤에 남긴다. 공개 결과 타입에 로그용 플래그를 더하지 않으려는 것이다(2026-10-20 범위 조정). 항목은 `ownerId`, `action=RECONCILIATION_CASE_RESOLVED`, `storeId`, `caseId`, `paymentId`, `decision`, `paymentStatus`다. 사유 원문은 로그에 남기지 않는다. 자유 입력이라 개인정보가 섞일 수 있고, DB 감사 기록이 정본이다.
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
  - `RECONCILIATION_PAYMENT_ALREADY_CONCLUDED`(409): 결제 시도가 이미 해소 결론과 다른 결말이다. case는 해소되지 않은 채라 `RECONCILIATION_CASE_ALREADY_RESOLVED`와 구분한다(2026-10-20 범위 조정)
- **스키마**: `t_payment_attempt` 컬럼 셋, 새 표 둘(스키마 변경 검토)
- **바뀌지 않는 것**: `PaymentAttemptStatus`에 새 값이 없다. 기존 이벤트의 `event_type`·`listener_id`도 바뀌지 않는다.

### 실패와 복구
- **넘김 트랜잭션 실패**: 횟수도 넘김도 남지 않고 다음 주기에 다시 한다.
- **case 열기 실패**: consumer가 실패하면 기존 Kafka 재시도·DLT로 간다. 넘김은 이미 커밋됐으므로 그동안 시도는 `UNKNOWN`이고 조회 대상이 아니다. pending gauge가 이 시도를 센다. DLT 재투입은 기존 절차(#91 전에는 수동)다.
- **해소 실패**: 결정 8의 한 트랜잭션이 통째로 롤백되고 점주가 다시 요청한다.
- **작업이 멈춘 경우**: 결과 불명이 쌓일 뿐 잘못 확정되지 않는다.

### 관측성
- **gauge `payment.attempt.reconciliation.pending`**(Prometheus `payment_attempt_reconciliation_pending`): 넘긴 뒤 아직 `UNKNOWN`인 승인 시도 수다. #84의 `payment.attempt.unknown`과 같은 주기에 세어 갱신하므로 scrape가 DB를 부르지 않는다. 기존 gauge의 뜻(결과 불명 승인 시도 전체)은 바꾸지 않는다. 두 gauge 모두 주기 시작이 아니라 그 주기의 조회·넘김이 끝난 뒤 센다. 시작에 세면 그 주기에 넘기거나 확정한 시도가 다음 주기까지 반영되지 않는다(2026-10-20 범위 조정, multi-review).
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
- 해소 기록의 보존 기간과 사유 원문 마스킹. 상태 전이마다 줄을 남기는 이력 표(결정 6·7).

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
- docs/wiki/architecture.md
- docs/wiki/domain-model.md
- docs/wiki/entity-map.md
- docs/wiki/observability.md

## 인수 조건
1. `FakePaymentGateway`가 위 표대로 답한다. 끝자리 1~4와 그 밖은 지금과 같은 결말이고, 승인에는 금액이 실린다.
2. 승인 요청 응답의 금액이 시도와 다르면 시도는 `UNKNOWN`이고 `unknown_reason`은 `AMOUNT_MISMATCH`다. 응답이 불명이면 `RESULT_UNCONFIRMED`다.
3. 조회가 불명이면 `query_count`가 하나 오른다. 상한에 닿는 조회에서 넘긴 시각이 정해지고, `PaymentReconciliationRequested(QUERY_LIMIT_EXCEEDED, queryCount = 상한)`의 발행 기록이 같은 커밋에 남는다. 상한 전에는 발행 기록이 없다.
4. 조회가 다른 금액의 승인이면 확정하지 않는다. 바로 넘기고 `PaymentReconciliationRequested(AMOUNT_MISMATCH, 알려 준 금액·참조)`의 발행 기록이 같은 커밋에 남는다.
5. 넘긴 시도는 주기 작업이 고르지 않고, `resolveUnknown`에 직접 넘겨도 provider를 부르지 않는다. 대상 조회의 `kind`·`status`는 여전히 리터럴로 렌더된다.
6. 같은 `paymentId`의 사실이 두 번 와도 case는 하나다. 열린 case는 `MANUAL_REVIEW`이고 분류 규칙 버전 1이며, 연 시각은 넘긴 시각이고 해소 기록은 없다.
7. 점주가 자기 매장의 case를 `APPROVED`(참조·같은 금액·사유)로 해소하면 한 커밋에 다음이 모두 남는다. case는 `RESOLVED`가 되고 해소 기록(점주·시각·결론·참조·승인 금액·사유)이 남는다. 시도는 `APPROVED`와 그 참조가 되고 `PaymentApproved` 발행 기록이 생긴다. 그 뒤 주문이 `PAID`가 된다(Kafka 포함 통합).
8. `DECLINED`로 해소하면 시도가 `DECLINED`, 주문은 `CHECKOUT_REQUESTED`다. 그 시도의 재결제가 승인되면 주문이 `PAID`가 된다.
9. 승인 금액이 다르면 `422 RECONCILIATION_AMOUNT_MISMATCH`이고 아무것도 바뀌지 않는다. 승인에 참조나 금액이 없거나, 금액 불일치 case의 거절에 참조가 없거나, 사유가 비었거나 500자를 넘거나, 사유·참조에 NUL 문자가 있으면 `400 INVALID_RECONCILIATION_RESOLUTION`이다.
10. 다른 매장의 case와 없는 case는 `404 RECONCILIATION_CASE_NOT_FOUND`, 남의 매장 경로는 `404 STORE_NOT_FOUND`, 세션이 없으면 401이다.
11. 해소된 case에 같은 결론이 다시 오면 200과 같은 결과이고 해소 기록이 바뀌지 않는다. 다른 결론이면 `409 RECONCILIATION_CASE_ALREADY_RESOLVED`다.
12. 같은 case의 해소가 동시에 둘 와도 결제 확정은 한 번이고 해소 기록은 먼저 커밋한 요청의 것이다.
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
set local lock_timeout = '5s';
alter table t_payment_attempt
    add column query_count integer not null default 0,
    add column unknown_reason varchar(32),
    add column reconciliation_requested_at timestamptz;
update t_payment_attempt set unknown_reason = 'RESULT_UNCONFIRMED' where kind = 'AUTHORIZATION' and status = 'UNKNOWN';
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
    opened_at timestamptz not null,
    resolved_at timestamptz,
    resolved_by_owner_id uuid,
    decision varchar(16),
    provider_reference varchar(255),
    approved_amount numeric(19,4),
    approved_currency char(3),
    note varchar(500),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz,
    constraint ux_reconciliation_case_payment unique (payment_id)
);
create index ix_reconciliation_case_store on t_reconciliation_case (store_id, status, id);
```

질문별 답(도메인 모델 §4.7):
1. **도출할 수 있는가.**
   - `query_count`: 조회 횟수는 어디에도 저장되지 않는다. 로그는 정본이 아니다.
   - `unknown_reason`: 승인 요청 응답의 금액은 저장되지 않아 "금액 불일치로 불명"을 다른 컬럼으로 알 수 없다.
   - `reconciliation_requested_at`: "넘겼다"는 사실은 `t_reconciliation_case`에도 생긴다. 하지만 `payment`는 다른 모듈의 표를 읽지 않고, 그 표는 넘김 커밋 뒤에 이벤트로 생긴다. 같은 트랜잭션에서 조회 대상에서 빼려면 시도 행에 있어야 한다.
   - case의 근거 컬럼(금액·불명 사유·조회 횟수·알려 준 금액)은 넘긴 시점의 사본이다(질문 5). 알려 준 금액·참조는 다른 곳에 없다.
   - 해소 컬럼(`resolved_at`·`resolved_by_owner_id`·`decision`·`provider_reference`·`approved_amount`·`approved_currency`·`note`)은 누가·언제·무엇으로·왜 해소했는지 남기는 유일한 기록이다.
   - `resolved_by_owner_id`는 행위자 ID라 `t_store.owner_id`에서 도출하지 않는다. 지금은 매장당 점주가 하나라 같은 값이지만, 해소 기록은 그 시점에 누가 했는지를 남긴다.
   - 근거와 해소 기록을 따로 표(evidence·이력)로 빼지 않는다. 근거는 출처가 payment 하나라 모양이 고정이고, 해소는 case당 한 번이다. 상태 전이마다 줄을 남기는 이력 표도 두지 않는다. `OPEN`·`INVESTIGATING`·`MANUAL_REVIEW`는 같은 커밋·같은 시각이라 `status`·`rule_version`·`opened_at` 이상의 정보가 없다. POS·provider 같은 출처나 재개·배정 같은 이력이 생기면 그 PR이 뺀다(질문 3으로 기존 행을 이 컬럼에서 채울 수 있다).
   - `opened_at`은 `created_at`과 다르다. consumer가 처리한 때가 아니라 넘긴 시각(이벤트의 `occurredAt`)이다. `resolved_at`도 같은 이유로 `updated_at`과 따로 둔다. 감사 컬럼에 업무 시각을 싣지 않는다.
2. **지금 읽는가.**
   - `query_count`: 상한 판정과 넘김 이벤트가 읽는다.
   - `unknown_reason`: 넘김 이벤트가 읽고, case 근거로 점주 목록에 보인다.
   - `reconciliation_requested_at`: 주기 작업의 대상 조회, gauge, 조회 확정·결론 적용의 허용 판정이 읽는다.
   - case 컬럼은 점주 목록·해소 대조·결론 적용이 읽는다. `rule_version`은 점주 목록이 보여 준다.
   - 해소의 멱등 판정이 `decision`을, 점주 목록이 `opened_at`(`openedAt`)을 읽는다. 해소 기록의 나머지(점주·시각·참조·승인 금액·사유)는 이 PR에서 읽는 API가 없다. 하지만 "근거·감사 없이 해결하지 않는다"(domain-model §2)가 쓰기 시점에 요구하는 기록이고 나중에 채울 수 없는 값이라 질문 4로 둔다.
3. **기존 행은 채울 수 있는가.**
   - `query_count`는 0이다. 기존 `UNKNOWN` 행은 이미 여러 번 조회됐을 수 있지만 기록이 없어 0부터 센다. 그래서 배포 뒤 최대 60회(약 1시간) 더 조회한 뒤 넘어간다.
   - `unknown_reason`은 기존 `UNKNOWN` 행만 `RESULT_UNCONFIRMED`로 채운다. 이 PR 전에는 금액을 대조하지 않았으므로 불명 사유는 그것 하나였고, 그래서 정확히 채울 수 있다. 이미 확정된 행은 null로 둔다(기록 이전).
   - `reconciliation_requested_at`은 null이다(넘긴 적 없음).
4. **불변식의 방어선인가.**
   - unique `ux_reconciliation_case_payment`는 "시도 하나에 case 하나"의 마지막 방어선이다. 같은 사실이 재전달되거나 consumer가 동시에 처리해도 case가 둘이 되지 않는다. 멱등 키라 soft delete된 행에도 걸려야 해서 전체 unique로 둔다. case를 지우는 명령은 없다.
   - `reconciliation_requested_at`은 "넘긴 시도는 조회로 확정하지 않고 reconciliation의 결론으로만 확정한다"의 판정 값이다. 판정은 시도 행을 잠근 뒤 다시 읽어서 한다(질문 6).
   - 해소 기록은 "감사 없이 해결하지 않는다"의 기록이라 PostgreSQL에 둔다.
   - 멱등 조회(`findByPaymentId`)는 `@SoftDelete` 필터를 거쳐 삭제된 case를 보지 않는다. 지금은 case를 지우는 명령이 없다. 그런 명령이 생기면 같은 사실이 다시 왔을 때 "없음 → 넣기 → unique 위반 → 재시도"를 되풀이하다 DLT로 가므로, 그 PR이 이 조회가 삭제된 행도 보게 한다.
   - 짝 컬럼(`reported_amount`·`reported_currency`, `approved_amount`·`approved_currency`)이 함께 있거나 함께 비는 것과, 해소 컬럼이 `status = 'RESOLVED'`일 때만 채워지는 것은 DB 제약 없이 코드가 지킨다(엔티티가 도메인의 `Money`·`Resolution` 값 하나에서 함께 쓴다). 저장소의 다른 표도 CHECK 제약을 두지 않는다.
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
   - case는 한 행이라 한 쿼리로 읽는다. 해소 기록도 같은 행의 컬럼이라 READ COMMITTED에서 서로 다른 커밋을 섞어 읽을 일이 없다.
7. **락과 테이블 크기.**
   - V016의 `add column`은 셋 다 메타데이터 변경이다. `query_count`는 상수 기본값, 나머지는 nullable이다. 긴 트랜잭션 뒤에서 기다리며 뒤 쿼리를 세우지 않게 `lock_timeout` 5초를 둔다(V005·V008·V011·V014와 같다). 시간 초과로 실패하면 다시 배포한다. `lock_timeout`은 락을 얻으려고 기다리는 시간만 제한한다. 얻은 `ACCESS EXCLUSIVE`는 같은 트랜잭션의 `update`가 끝나 커밋할 때까지 쥔다.
   - 그래서 같은 마이그레이션의 `update`가 짧아야 한다. 술어를 V013 partial 인덱스의 술어와 같게(`kind = 'AUTHORIZATION' and status = 'UNKNOWN'`) 두어 그 인덱스가 대상 행만 고르게 한다. 평소 `UNKNOWN`은 수십 건 이하다. `status`만으로 거르면 술어가 인덱스 술어를 함의하지 않아 `ACCESS EXCLUSIVE`를 쥔 채 원장 전체를 순차 스캔한다(대조 리뷰 지적). 취소 시도는 만드는 코드가 없어 `UNKNOWN` 취소 행이 없고, 있더라도 adapter의 null 대체 읽기(질문 8)가 덮는다. 주기 작업이 행을 잠그고 있으면 기다리는 것은 `alter table`의 락 획득이고 상한은 위 `lock_timeout`이다. 락을 얻은 뒤의 `update`는 행 잠금을 기다리지 않는다.
   - 트랜잭션 안에서 도는 마이그레이션이라 `CONCURRENTLY`가 없다.
   - V017은 새 표 하나라 기존 쿼리를 막지 않는다. 제약을 기존 행에 거는 일이 없어 위반 행 검사는 없다.
8. **이전 버전과 함께 뜨는가.**
   - 이전 버전은 새 컬럼과 표를 모르고, JPA `validate`는 매핑된 컬럼만 본다. `query_count`는 기본값이 있어 이전 버전의 INSERT가 실패하지 않는다.
   - 롤링 배포 중 이전 버전이 만든 `UNKNOWN` 행은 `unknown_reason`이 null이다. 새 버전의 adapter는 `UNKNOWN`인데 null인 행을 `RESULT_UNCONFIRMED`로 읽는다. 이전 버전에는 금액 대조가 없으므로 정확하다. 코드 주석에 이유를 남긴다.
   - 새 버전이 만든 `UNKNOWN(AMOUNT_MISMATCH)` 시도는 아직 넘기지 않았으면 이전 버전의 주기 작업이 금액 대조 없이 승인으로 확정할 수 있다. 이 PR 전의 동작과 같아 회귀는 아니다.
   - 이전 버전의 주기 작업은 넘김을 모르므로, 그 사이 넘긴 시도를 조회로 확정할 수 있다. 그러면 그 case의 해소는 결제가 이미 결말이 난 상태를 만난다. 같은 결론이면 닫히고, 다르면 `409 RECONCILIATION_PAYMENT_ALREADY_CONCLUDED`로 롤백되고 점주는 메시지의 결제 결말과 같은 결론으로 다시 해소한다. 넘김은 60회 조회 뒤에야 일어나고 배포 창은 몇 분이라 드물다.
   - 새 버전이 만든 `PaymentReconciliationRequested` 발행 기록이 브로커 전송에 실패해 남으면, 배포 창 동안 이전 버전 pod의 재전송 작업(`EventPublicationResubmitter`)은 그 타입을 몰라 역직렬화에 실패하고 그 실행이 끊긴다(spring-modulith#1904, domain-model §4.3). 그 기록은 새 버전 pod가 보낸다.
9. **payload에 실리는가.**
   - `query_count`·`unknown_reason`·넘긴 시각(`occurredAt`)은 새 이벤트 `PaymentReconciliationRequested`에 실린다. 첫 버전이라 하위 호환을 따질 이전 형식이 없고, 필드 목록이 계약이다(#78).
   - case 표는 이벤트나 캐시에 실리지 않는다. 점주 API 응답은 DTO다.
- 대조 기록: 재대조 대기(2026-10-20 감사 표를 case 한 표로 접어 V017과 답이 바뀌었다). 이전 기록: code-reviewer 에이전트(읽기 전용), 2026-10-20, 수정 후 재대조 불일치 없음, 첫 대조의 불일치 2건(질문 7 V016 update 술어의 kind 누락, 질문 2 감사 기록을 읽는 코드 누락)은 33d48df·ad8d1b8에서 고쳐졌다(V006·V013·V016·V017, PaymentAttemptEntity·PaymentAttemptPersistenceAdapter, ReconciliationCaseEntity·ReconciliationCaseActionEntity·ReconciliationCasePersistenceAdapter·ReconciliationCaseQueries, PaymentAttempt·PaymentManagement·UnknownPaymentResolver, ReconciliationCase·ReconciliationCaseAction·ReconciliationManagement·PaymentReconciliationRequestedListener, PaymentReconciliationRequested, JdslQueryExecutor·BaseEntity·ApiControllerAdvice, SchemaMigrationTest·ReconciliationCaseResolveTest)

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
  - §3 용어집의 `UNKNOWN` 행에서 "case는 아직 없다"를 지우고 넘김과 불명 사유를 적는다.
  - §3 용어집에 넘김(`reconciliation requested`), 불명 사유, 분류 규칙 버전을 더한다.
  - §4.3 주기 작업에 상한과 넘김을 더한다.
  - §4.6에 두 번째 동기 쓰기 경로를 더한다(결정 8).
- [architecture](../wiki/architecture.md): domain-model §4.6 제목이 바뀌어 그 앵커 링크만 고친다.
- [entity-map](../wiki/entity-map.md): `t_payment_attempt`의 새 컬럼과 `UNKNOWN → case` 흐름을 적고, `t_reconciliation_case`를 더한다.
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

2026-10-20 구현 끝에 다시 확인했다. origin/main은 여전히 9c34aaf다.
- #79는 PR #97로 열렸다. 겹치는 경로는 `docs/wiki/entity-map.md` 하나이고 고친 줄이 다르다(#97은 `OrderEntity`·4.1·4.2, 이 PR은 1·2절 표, `PaymentAttemptEntity`, 4.4, 4.6, 5절).
- #78은 PR #98로 열렸다. 겹치는 경로는 `docs/wiki/domain-model.md`다. #98은 contract 이벤트에 `schemaVersion`과 생성자 가드(`contract.requireKnownSchemaVersion`)를 더하고 golden fixture(`src/test/resources/event-contract/토픽/버전.json`)를 둔다. 이 PR의 새 이벤트 `PaymentReconciliationRequested`도 그 규칙을 따라야 한다. #98이 먼저 머지되면 이 PR이 rebase하면서 `schemaVersion`·가드·fixture를 더한다. 이 PR이 먼저면 #98이 더한다(볼트 modulith-kafka-keywords 8.34).

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
| 8·9·결론 적용(`resolveFromReconciliation`): `PaymentAttemptReconciliationTest`의 `넘긴 시도에 승인 결론을 …`·`넘긴 시도에 거절 결론을 …`·`UNKNOWN이 아닌 시도는 어느 결론도 …`·`넘기지 않은 UNKNOWN 시도는 어느 결론도 …`, `PaymentManagementTest`의 `넘긴 시도에 승인 결론을 적용하면 …`·`넘긴 시도에 거절 결론을 적용하면 …`·`이미 결말이 난 시도에는 결론을 쓰지 않고 …`·`넘기지 않은 UNKNOWN 시도에 결론을 …`·`없는 시도에 결론을 …`, `PaymentUnknownResolutionIntegrationTest`의 `호출자 트랜잭션 안에서 …`·`호출자 트랜잭션이 롤백되면 …` | 도메인: `./gradlew test --tests 'com.resilientpos.payment.internal.domain.PaymentAttemptReconciliationTest'`. 두 전이를 `Transition(this, emptyList())`로만 선언한 상태에서 12건 중 4건 assertion 실패. Facade: `./gradlew test --tests 'com.resilientpos.payment.PaymentManagementTest'`. 메서드가 현재 상태만 돌려주게 임시로 바꾼 상태에서 34건 중 3건 assertion 실패(저장·발행 없음, 넘기지 않은 시도를 거절하지 않음). 통합: `./gradlew test --tests 'com.resilientpos.payment.internal.application.PaymentUnknownResolutionIntegrationTest'`. 같은 임시 스텁이면 승인 적용 테스트 실패, `Tx.masterTx`를 `Tx.newTx`로 임시 변경하면 롤백 테스트 실패(합류 증거), 각각 10건 중 1건. 확인 뒤 되돌림. 이미 결말·없는 시도 단언은 처음부터 통과(characterization) | 같은 명령 통과. `requireHandedOver`(UNKNOWN이 아니면 `PAYMENT_RESULT_ALREADY_APPLIED`, 넘기지 않았으면 `PAYMENT_NOT_IN_RECONCILIATION`)와 기존 `approved`·`declined` 재사용, Facade는 `Tx.masterTx`로 합류해 잠그고 `updateAndPublish` | 아래 회귀 |
| 위 결론 적용 회귀 | - | - | `./gradlew test --tests 'com.resilientpos.payment.*' --tests 'com.resilientpos.order.*' --tests 'com.resilientpos.ModuleStructureTest' --tests 'com.resilientpos.TransactionDeclarationTest'` BUILD SUCCESSFUL, 290건 통과·실패 0 |
| 6·9·11·case 도메인: `ReconciliationCaseOpenTest`의 `두 사유 모두 MANUAL_REVIEW와 규칙 버전 1로 열리고 이벤트는 없다`·`감사 기록은 OPEN INVESTIGATING MANUAL_REVIEW 세 줄이고 모두 SYSTEM이다`, `ReconciliationCaseResolveTest`의 `승인으로 해소하면 …`·`거절은 참조 없이도 해소된다`·`사유 500자는 통과한다`·`해소된 case에 같은 결론이 다시 오면 …`·`해소된 case에 다른 결론이 오면 …`·`승인 금액이 case 금액과 다르면 …`·`승인에 참조나 금액이 없으면 …`·`거절에 승인 금액을 실으면 …`·`사유가 비었거나 500자를 넘으면 …` | `./gradlew test --tests 'com.resilientpos.reconciliation.*'`. 타입·오류 code 상수와 `open`(상태 OPEN·규칙 버전 0·감사 줄 없음)·`resolve`(`Transition(this, emptyList())`)를 최소 선언으로 둔 상태에서 13건 중 11건 assertion 실패(상태·규칙 버전·감사 줄, 거절 예외 없음). 근거 그대로 담기·`notFound`는 처음부터 통과(characterization) | 같은 명령과 `ModuleStructureTest`·`DependencyDirectionTest` 통과(20건). `open`은 분류(else 없는 `when`)와 감사 세 줄, `resolve`는 상태 `when`(MANUAL_REVIEW 검증 뒤 OWNER 줄, RESOLVED 멱등·충돌, 나머지 `error`) | `./gradlew test --tests 'com.resilientpos.reconciliation.*' --tests 'com.resilientpos.ModuleStructureTest' --tests 'com.resilientpos.DependencyDirectionTest'` BUILD SUCCESSFUL, 20건 통과·실패 0 |
| 6~11·14 영속·Facade: `ReconciliationManagementIntegrationTest`의 `case를 열면 MANUAL_REVIEW와 규칙 버전 1과 SYSTEM 감사 세 줄이 남고 …`·`같은 시도의 사실이 다시 와도 기존 case를 돌려주고 …`·`승인으로 해소하면 한 커밋에 …`·`거절로 해소하면 …`·`승인 금액이 다르면 …`·`다른 매장의 storeId로 해소하면 …`·`해소된 case에 같은 결론이 다시 오면 … payment를 부르지 않는다`·`해소된 case에 다른 결론이 오면 …`·`시도가 이미 해소 결론과 다른 결말이면 …`·`시도가 이미 같은 결말이면 …`·`payment가 결론을 거절하면 한 트랜잭션이 통째로 롤백되어 …`, `SchemaMigrationTest`의 baseline 목록·표 목록에 V017 | `./gradlew test --tests 'com.resilientpos.reconciliation.*' --tests 'com.resilientpos.SchemaMigrationTest'`. V017 없이 baseline 목록만 017로 고친 상태에서 SchemaMigrationTest assertion 실패. `ReconciliationManagement`를 case도 시도도 쓰지 않고 임의 결과만 돌려주는 최소 선언으로 둔 상태에서 Facade 통합 12건 중 11건 assertion 실패(case 행 없음, 해소 후에도 UNKNOWN, 예외 없음) | 같은 명령 통과. V017·엔티티 둘(`ReconciliationCaseEntity`·`ReconciliationCaseActionEntity`, `@Version` 없음)·`Repository` 상속 Spring Data 둘·`ReconciliationCasePersistenceAdapter`(`findByIdAndStoreIdForUpdate`는 `findFirstForUpdate`, `save`는 새 case면 `persist`·기존이면 `apply`와 새 감사 줄만 `persist`)·`ReconciliationManagement.open`(`Tx.masterTx`, 있으면 반환)·`resolve`(case 잠금 → `case.resolve` → 멱등이면 payment 생략 → `resolveFromReconciliation` → 결말이 결론과 다르면 `RECONCILIATION_CASE_ALREADY_RESOLVED`(CONFLICT)로 롤백 → 저장) | 아래 마지막 행 |
| 12·`ReconciliationResolveConcurrencyIntegrationTest`의 `같은 case의 해소가 동시에 둘 와도 …` | 같은 명령. `findByIdAndStoreIdForUpdate`를 `findFirst`(잠금 없음)로 임시 변경한 상태에서 1건 assertion 실패(OWNER 감사 줄 기대 1, 실제 2). 확인 뒤 백업으로 그 파일만 되돌림. 구현 전 최소 선언 단계에서는 두 결과가 달라 실패 | 같은 명령 통과 | 아래 마지막 행 |
| 6~8·consumer: `ReconciliationFlowIntegrationTest`의 `상한까지 조회가 불명이면 case가 … 같은 사실을 다시 받아도 하나다`·`조회가 다른 금액의 승인이면 …`·`점주가 승인으로 해소하면 … 주문이 PAID가 된다`·`점주가 거절로 해소하면 … 재결제가 승인되면 PAID가 된다`, `KafkaEventDeliveryIntegrationTest`의 `consumer가 듣는 토픽은 모두 설정 목록에 있어 앱이 만든다`(기대 토픽에 넘김 토픽 추가) | `./gradlew test --tests 'com.resilientpos.reconciliation.*'`. consumer가 없는 상태에서 4건 모두 `case가 열린다`를 20초 기다리다 실패. `KafkaEventDeliveryIntegrationTest`는 넘김 토픽을 듣는 consumer가 없어 실패 중이었다(위 "넘김 동작 회귀" 행) | `./gradlew test --tests 'com.resilientpos.reconciliation.*' --tests 'com.resilientpos.platform.KafkaEventDeliveryIntegrationTest'` 통과. `PaymentReconciliationRequestedListener`가 사실을 `ReconciliationCommand.Open`으로 바꿔 `open`만 부름. 재결제 승인은 테스트 전용 gateway(같은 금액의 두 번째 paymentId를 승인)로 밟음 | 아래 마지막 행 |
| 11·결제 결말과 다른 해소 결론의 오류 code(2026-10-20 범위 조정): `ReconciliationManagementIntegrationTest`의 `시도가 이미 해소 결론과 다른 결말이면 결제 상태를 담은 충돌로 거절하고 case는 그대로다` | `./gradlew test --tests 'com.resilientpos.reconciliation.internal.application.ReconciliationManagementIntegrationTest'`. 기대 code를 `RECONCILIATION_PAYMENT_ALREADY_CONCLUDED`로 먼저 바꿔 11건 중 1건 실패(`expected: RECONCILIATION_PAYMENT_ALREADY_CONCLUDED but was: RECONCILIATION_CASE_ALREADY_RESOLVED`) | Facade의 code를 바꾼 뒤 같은 명령 11건 통과 | `./gradlew test --tests 'com.resilientpos.reconciliation.*'` 29건 통과 |
| 위 case 열기·해소 회귀 | - | - | `./gradlew test` 전체 BUILD SUCCESSFUL, 659건 통과·실패 0(`build/test-results/test/*.xml` 집계). 점주 API·조회 Facade·`resolve` 오류의 HTTP 변환은 이 묶음에 없음. 새 `ReconciliationManagement` 단위(in-memory 저장소) 테스트는 쓰지 않음(트랜잭션 합류·잠금·Kafka가 인수 조건이라 통합으로 봄) |
| 9~11·점주 API 웹 슬라이스: `ReconciliationCaseOwnerControllerTest`의 `목록은 200과 미해소 case를 …`·`승인으로 해소하면 200과 결과를 …`·`거절로 해소하면 금액 없이 …`·`승인 금액과 통화 중 하나만 오면 400 …`·`승인에 참조가 없거나 사유가 잘못되면 400 …`·`승인 금액이 시도 금액과 다르면 422 …`·`case가 없거나 다른 매장의 것이면 404 …`·`이미 다른 결론으로 해소된 case는 409 …`·`결제 시도가 이미 해소 결론과 다른 결말이면 409 …`·`남의 매장이거나 없는 매장이면 404 STORE_NOT_FOUND …`·`로그인하지 않으면 401 …`·`CSRF 토큰이 없는 해소 요청은 403 …` | `./gradlew test --tests 'com.resilientpos.reconciliation.internal.adapter.web.ReconciliationCaseOwnerControllerTest'`. 조회 타입·Facade·조회 클래스 최소 선언(빈 목록)과 endpoint 없는 빈 Controller만 있는 상태에서 12건 중 10건 assertion 실패(매핑이 없어 404·405). 401·403 두 건은 보안 체인이 이미 처리해 처음부터 통과(characterization). 그 앞의 컨텍스트 로드 실패(`TerminalManagement` 빈 없음)는 환경 문제라 RED가 아니며 단말 인증 인터셉터용 `@MockitoBean`을 더해 해결 | 같은 명령 통과(12건). Controller의 두 endpoint(`requireOwned` 뒤 Facade 호출, 해소 뒤 감사 INFO 한 줄)와 DTO·금액·통화 짝 검사 구현. 스니펫 `reconciliation-cases-list`·`-resolve`·`-resolve-invalid`·`-resolve-amount-mismatch`·`-resolve-not-found`·`-resolve-already-resolved`·`-resolve-payment-concluded`·`-store-not-found`·`-unauthenticated`·`-access-denied`가 `build/generated-snippets`에 생성됨 | 아래 마지막 행 |
| 10·13·HTTP부터 DB까지: `ReconciliationCaseOwnerApiIntegrationTest`의 `목록은 자기 매장의 MANUAL_REVIEW case만 최신순으로 …`(openedAt은 OPEN 감사 줄 시각) | `./gradlew test --tests 'com.resilientpos.reconciliation.internal.adapter.web.ReconciliationCaseOwnerApiIntegrationTest'`. 조회 클래스가 빈 목록을 돌려주는 최소 선언 상태에서 4건 중 1건 assertion 실패(목록 길이 0). 나머지 세 건은 이미 있던 Facade와 방금 만든 Controller가 이어져 처음부터 통과(characterization) | 같은 명령 통과(4건). `ReconciliationCaseQueries.findReviewable`이 case와 `OPEN` 감사 줄을 조인해 한 쿼리로 읽고(`selectNew`), 그 매장의 `MANUAL_REVIEW`만 `id` 내림차순 100건 | 아래 마지막 행 |
| 7·9·10·11·HTTP부터 DB까지: 같은 클래스의 `승인으로 해소하면 case는 RESOLVED가 되고 … 같은 결론의 재요청은 …, 다른 결론은 409다`·`승인 금액이 다르면 422이고 … 참조가 없으면 400이다`·`다른 매장의 case를 자기 매장 경로로 … 404 STORE_NOT_FOUND이며 아무것도 바뀌지 않는다` | 위 행과 같은 명령. characterization이라 RED 없음(실제 세션·CSRF·DB·감사 로그 줄·사유 원문 미노출을 처음 한 흐름으로 확인) | 같은 명령 통과 | `./gradlew test` 전체 BUILD SUCCESSFUL, 675건 통과·실패 0·건너뜀 0(`build/test-results/test/*.xml` 집계). 점주 API 웹 슬라이스 12건과 HTTP 통합 4건이 새로 늘어난 건수 |
| 대조 리뷰 반영: PG 승인 참조 길이와 V016 backfill 술어 / `ReconciliationCaseResolveTest`의 `PG 승인 참조 255자는 통과하고 256자는 거절한다`, `SchemaMigrationTest`의 V016 backfill 테스트 | `./gradlew test --tests 'com.resilientpos.reconciliation.internal.domain.ReconciliationCaseResolveTest'`. 11건 중 1건 실패(256자 참조에 `Expected an exception of class DomainException to be thrown, but was completed successfully`). V016 술어 변경은 결과 행이 같아 기존 backfill 테스트가 그대로 통과(characterization) | 길이 검사를 더한 뒤 같은 명령 통과. REST Docs 요청 필드 설명에 255자를 적음 | `./gradlew test --tests 'com.resilientpos.reconciliation.*' --tests 'com.resilientpos.SchemaMigrationTest'` 54건 통과 |
| 6·7·11·12 감사 표를 case 한 표로 접음(REFACTOR, 동작 유지): V017(`t_reconciliation_case_action` 제거, `opened_at`·해소 컬럼 추가), `Resolution` 값 객체, `ReconciliationCaseOpenTest`의 `두 사유 모두 … 연 시각은 넘긴 시각이며 해소 기록과 이벤트는 없다`, `ReconciliationCaseResolveTest`의 `승인으로 해소하면 … 해소 기록이 점주와 시각과 결론과 근거를 담는다`·`해소된 case에 같은 결론이 다시 오면 … 해소 기록을 바꾸지 않는다`(두 번째 요청을 60초 늦게 보내도 기록 불변), `ReconciliationManagementIntegrationTest`의 열기·해소·거절·재요청 단언을 해소 컬럼 단언으로 교체(`isUnresolved`로 롤백된 case의 해소 컬럼이 모두 null), `ReconciliationFlowIntegrationTest`·`ReconciliationCaseOwnerApiIntegrationTest`의 감사 줄 단언을 `caseRow`로 교체, `SchemaMigrationTest` 표 목록에서 감사 표 제거 | RED 없음(리팩터링). 동작 테스트의 단언 모양만 바뀌어 같은 사실을 고정한다 | `./gradlew test --tests 'com.resilientpos.reconciliation.*' --tests 'com.resilientpos.SchemaMigrationTest' --tests 'com.resilientpos.ModuleStructureTest' --tests 'com.resilientpos.EntityVisibilityTest'` BUILD SUCCESSFUL, 60건 통과·실패 0. 도메인 `resolve`·`open`에서 식별자 인자를 뺌, 엔티티가 `Resolution`을 컬럼으로 펼침, 목록 조회에서 조인 제거 | 위 명령 |
| 12·동시 해소 관측 방식 교체: `ReconciliationResolveConcurrencyIntegrationTest`의 `같은 case의 해소가 동시에 둘 와도 … 처음 해소는 하나이고 … 해소 기록은 한 요청의 것이다`(두 요청의 사유를 다르게 보냄) | `./gradlew test --tests 'com.resilientpos.reconciliation.internal.application.ReconciliationResolveConcurrencyIntegrationTest'`. 감사 줄이 없어져 "OWNER 줄 하나"를 관측할 수 없으므로 resolved counter(처음 해소했을 때만 센다)가 하나만 오르는지로 바꿨다. `findByIdAndStoreIdForUpdate`를 `findFirst`(잠금 없음)로 임시 변경한 상태에서 세 번 모두 assertion 실패(counter가 호출 전보다 둘 오름, 테스트 61행). 확인 뒤 백업으로 그 파일만 되돌림 | 같은 명령 통과 | 위 명령 |
| 14·gauge를 주기 끝에 센다(multi-review): `UnknownPaymentResolverIntegrationTest`의 `한 주기 뒤 gauge가 그 주기가 확정한 시도를 뺀 결과 불명 승인 시도 수와 같다`(기존 "주기 시작 값" 계약을 교체), `그 주기에 넘긴 시도가 같은 주기 뒤 pending gauge에 보인다` | `./gradlew test --tests 'com.resilientpos.payment.internal.application.UnknownPaymentResolverIntegrationTest'`. 12건 중 2건 assertion 실패(기대 `1.0`, 실제 `2.0`은 그 주기에 확정한 시도가 unknown gauge에 남음, 기대 `1.0`, 실제 `0.0`은 그 주기에 넘긴 시도가 pending gauge에 없음) | 두 count를 `Lock.executeWithLockNoTx` 블록 뒤로 옮기고 KDoc을 고친 뒤 같은 명령 12건 통과 | 위 마지막 회귀 행 |
| 6·12·case 동시 열기의 복구 경로: `ReconciliationOpenConcurrencyIntegrationTest`의 `같은 시도의 열기가 겹쳐 한쪽이 unique 위반으로 실패해도 다시 부르면 기존 case를 돌려주고 case는 하나다`, `consumer의 열기가 unique 위반으로 실패하면 DLT로 가지 않고 재시도가 기존 case를 찾아 case는 하나다` | `./gradlew test --tests 'com.resilientpos.reconciliation.internal.application.ReconciliationOpenConcurrencyIntegrationTest'`. characterization이라 RED 없음(처음부터 통과, 2건). 겹침은 B의 삽입을 바깥 트랜잭션이 커밋에서 flush하게 붙잡고 그동안 다른 스레드의 A를 커밋시켜 결정적으로 만든다(`PaymentAttemptCreationConcurrencyIntegrationTest`와 같은 방법). Kafka 테스트는 `ReconciliationManagement` spy의 첫 호출에만 겹침을 만든다. 재시도 허용 목록이 이 사실을 고정하는지 보려고 `KafkaConfiguration`에서 `DataIntegrityViolationException`을 임시로 뺀 상태로 돌리면 그 테스트가 실패한다(`consumer가 재시도까지 처리한다` 20초 초과, DLT로 감). 확인 뒤 백업으로 그 파일만 되돌림 | 같은 명령 통과(2건). 던진 예외는 `ux_reconciliation_case_payment` 위반이고 consumer 호출은 첫 처리·A·재시도 세 번, case 행 하나, DLT 메시지 없음 | `./gradlew test` 전체 BUILD SUCCESSFUL, 678건 통과·실패 0·건너뜀 0(`build/test-results/test/*.xml` 집계) |
| multi-review 반영 1~3·거절 참조 규칙: `ReconciliationCaseResolveTest`의 `금액 불일치 case의 거절은 PG 취소 참조가 없거나 공백이면 거절하고 있으면 해소한다`·`사유나 참조에 NUL 문자가 있으면 거절한다`·`공백뿐인 거절 참조는 없는 것으로 다뤄 해소 기록에 null로 남긴다`, `ReconciliationManagementIntegrationTest`의 `공백뿐인 거절 참조는 해소 기록과 시도의 provider_reference에 null로 남는다`, `ReconciliationCaseOwnerApiIntegrationTest`의 `금액 불일치 case의 거절은 PG 취소 참조가 없으면 400이고 …` | `./gradlew test --tests 'com.resilientpos.reconciliation.internal.domain.ReconciliationCaseResolveTest'`. 14건 중 3건 assertion 실패(금액 불일치 거절이 참조 없이 해소됨, NUL 사유·참조가 통과, 공백 참조가 `"   "` 그대로 남음). 통합 `ReconciliationManagementIntegrationTest`는 12건 중 1건 실패(시도의 `provider_reference`가 공백 문자열, Facade가 명령의 원래 참조를 payment에 넘김). HTTP 400은 도메인 구현 뒤라 처음부터 통과(characterization) | 같은 명령 통과. `requireValidResolution`에 금액 불일치(`reason`·`unknownReason`) 거절의 참조 필수와 NUL 검사를 더하고, `resolve`가 공백 참조를 null로 정규화하며, Facade가 해소 기록의 참조를 payment에 넘김. `ReconciliationManagementIntegrationTest`·`ReconciliationCaseOwnerApiIntegrationTest` 통과. 기존 `ReconciliationFlowIntegrationTest`의 거절 흐름은 끝자리 5(금액 불일치 case)라 참조를 싣도록 고침 | 아래 마지막 행 |
| multi-review 반영 4·해소 요청 본문 `reason` → `note`: `ReconciliationCaseOwnerControllerTest`의 JSON·REST Docs 필드와 `ReconciliationCaseOwnerApiIntegrationTest`의 본문을 `note`로 바꿈 | `./gradlew test --tests '*ReconciliationCaseOwnerControllerTest'`. 12건 중 7건 실패(옛 DTO가 `note`를 읽지 못해 사유가 비어 커맨드 불일치·400) | DTO `note`로 같은 명령 통과(12건). 목록 응답의 `reason`(넘김 사유)은 그대로 | 아래 마지막 행 |
| multi-review 반영 5·감사 INFO를 처음 해소한 Facade 분기에서 남김: `ReconciliationCaseOwnerApiIntegrationTest`의 `승인으로 해소하면 … 같은 결론의 재요청은 …`에 "재요청 뒤에도 감사 줄 1"·"다른 결론 409 뒤에도 1" 단언 추가 | `./gradlew test --tests '*ReconciliationCaseOwnerApiIntegrationTest'`. 5건 중 1건 assertion 실패(재요청마다 Controller가 로그를 남겨 줄이 2) | Controller 로그 삭제, `ReconciliationManagement.resolve`의 처음 해소 분기에서 같은 형식으로 로그. 같은 명령 통과. 첫 해소 줄의 필드와 사유 원문 미노출 단언은 그대로 통과 | 아래 마지막 행 |
| multi-review 반영 6·멱등 판정을 상태로(REFACTOR): `resolveInTransaction`이 해소 전 `status == RESOLVED`로 가름 | - | - | 같은 동작이라 기존 `ReconciliationManagementIntegrationTest`·`ReconciliationResolveConcurrencyIntegrationTest` 통과. 결론→결제 상태·결론→`Conclusion`의 두 `when`은 합치지 않음(합치면 매핑 표 하나가 더 생겨 두 줄보다 길다) |
| multi-review 반영 7·롤링 배포 null 대체 읽기: `PaymentUnknownResolutionIntegrationTest`의 `이전 버전이 만든 unknown_reason이 null인 UNKNOWN 행도 상한에서 넘기고 사유는 RESULT_UNCONFIRMED로 읽는다` | characterization(구현은 이미 있음). `PaymentAttemptEntity.toDomain`의 대체 읽기를 `unknownReason = unknownReason`으로 임시로 바꾸자 11건 중 1건 실패(`IllegalStateException`, UNKNOWN인데 사유 없음). 확인 뒤 백업으로 그 파일만 되돌림 | `./gradlew test --tests '*PaymentUnknownResolutionIntegrationTest'` 통과(11건) | 아래 마지막 행 |
| multi-review 반영 회귀 | - | - | `./gradlew test` 전체 BUILD SUCCESSFUL, 684건 통과·실패 0·건너뜀 0(`build/test-results/test/*.xml` 집계) |

## 결과
- 인수 조건 1~15를 위 TDD 기록의 테스트로 확인했다. 16의 최종 실행 결과는 아래 검증 줄에 남긴다.
- 범위 조정 셋을 구현 전에 spec에 먼저 적었다.
  - `PAYMENT_IN_RECONCILIATION`(409): 이미 넘긴 시도에 조회 확정·불명 기록·넘김이 다시 오면 aggregate가 거절한다.
  - `RECONCILIATION_PAYMENT_ALREADY_CONCLUDED`(409): 결제가 이미 해소 결론과 다른 결말이다. 처음에는 `RECONCILIATION_CASE_ALREADY_RESOLVED`를 빌려 썼다. 하지만 이 경우 case는 해소되지 않은 채라 점주가 "이미 해소됨"으로 오해한다. 그래서 나눴다.
  - 감사 기록 자식 표(`t_reconciliation_case_action`)를 없애고 case 한 표로 접었다(사용자 결정). 전이마다 남기던 SYSTEM 세 줄은 같은 시각에 새 정보가 없었고, 해소는 case당 한 번이라 목록이 필요 없었다. 자식 엔티티·저장소·도메인 타입 다섯 개와 목록 조회의 조인이 사라졌다.
- **구현에서 정한 것**
  - 시도의 전이 이름은 `recordUnknownQuery`(불명 조회 기록, 상한에 닿으면 넘김), `requestReconciliationForAmountMismatch`(금액 불일치 넘김), `concludeApproved`·`concludeDeclined`(reconciliation 결론)다.
  - case 전이는 `ReconciliationCase.open`·`resolve`이고, 이벤트가 없어 사실 타입 자리에 `Nothing`을 둔 `Transition`을 돌려준다.
  - Fake의 "1단위 많음"은 `Money`에 최소 단위 연산이 없어 Fake 안의 private 함수(scale로 최소 단위를 만든다)로 계산한다.
  - 점주 목록의 `openedAt`은 `created_at`이 아니라 `opened_at`(넘긴 시각)이다.
  - 해소 본문에서 승인 금액과 통화 중 하나만 오거나 사유가 없으면 `400 INVALID_RECONCILIATION_RESOLUTION`이다. `decision`이 없거나 모르는 값이면 기존 advice의 `400 BAD_REQUEST`다.
  - 해소 감사 INFO는 `audit action=RECONCILIATION_CASE_RESOLVED ownerId=… storeId=… caseId=… paymentId=… decision=… paymentStatus=…`이고 사유 원문은 없다(통합 테스트가 로그에 사유가 없음을 확인한다).
  - `KafkaEventDeliveryIntegrationTest`의 토픽·consumer 일치 검사에 새 토픽을 더했다. 같은 클래스의 checkout 흐름 단언(토픽마다 key가 `orderId`인 메시지 1건)에는 넣지 않았다. checkout 흐름은 넘김을 일으키지 않기 때문이다.
- **Kafka·Modulith**
  - 새 발행 지점(넘김)은 기존 Facade처럼 업무 상태 저장과 같은 트랜잭션에서 발행한다. 그래서 [Spring Modulith 이벤트 문서](https://docs.spring.io/spring-modulith/reference/events.html)의 계약("발행 기록은 발행한 트랜잭션과 함께 커밋되고 `@Externalized` 이벤트는 커밋 뒤 외부화된다")을 그대로 따른다. 통합 테스트가 발행 기록이 같은 커밋에 남고 브로커 전송으로 완료됨을 확인한다. 토픽이 없으면(`auto.create.topics` 꺼짐) 완료되지 않는 것도 RED에서 관측했다.
  - consumer는 기존 `KafkaConfiguration`의 `DefaultErrorHandler`(허용 목록 재시도, 소진되면 `원토픽.DLT`)를 그대로 쓴다. [Spring Kafka 오류 처리 문서](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html)의 `addRetryableExceptions` 분류대로, case 열기 경합의 unique 위반(`DataIntegrityViolationException`)은 재시도되고 두 번째 처리가 기존 case를 찾는다. 외부화 설정·`max-poll-records`·재시도 간격은 바꾸지 않았다.
  - 결정 기록은 볼트 modulith-kafka-keywords 8.34(넘김은 이벤트, 해소는 동기 합류)와 4절의 토픽·group 목록, 8.28 덧붙임에 남겼다.
- **multi-review(2026-10-20)**: Claude 86점, Codex 87점(`codex exec` 읽기 전용), Gemini는 CLI가 없어 미실행. Critical은 둘 다 없었다.
  - 반영
    - 두 리뷰 공통: domain-model §4.3 이벤트 표에 새 토픽 행, §4.6 제목, case 동시 열기(unique 위반 → Kafka 재시도) 테스트.
    - Codex: gauge를 주기 끝에 센다.
    - Claude: 금액 불일치 case 거절의 취소 참조 필수(사용자 결정), null 대체 읽기 테스트, 감사 INFO를 처음 해소 때만, 요청 필드 `reason`을 `note`로, NUL·공백 참조 처리, 멱등을 상태로 판정, 질문 8의 이전 pod 재전송, 한계 절 항목들, wiki 잔여(엔티티 수·멱등 표·마이그레이션 목록·로그 형식).
    - 리뷰 사이에 사용자가 감사 기록 자식 표를 없애고 case 한 표로 접기로 했다(범위 조정).
  - 반영하지 않음
    - 안 쓰는 Spring Data 저장소 삭제(Codex): 볼트 rules가 "엔티티마다 두고 지금 쓰지 않아도 지우지 않는다"로 정했다. 감사 엔티티 쪽은 표를 접으며 사라졌다.
    - 목록을 오래된 순으로(Claude): 사용자 결정으로 최신순을 유지하고 잘림 위험을 한계에 적었다.
    - `Conclusion`을 sealed 타입으로, `recordAndLog`의 Boolean 플래그 제거, 결론 매핑 두 `when` 합치기: 호출부가 한 곳이고 합치면 오히려 길어진다. 승인 참조 필수는 case 검증이 먼저 지킨다.
    - 금액 형식 오류 code 통일: 기존 `Money` 변환의 code(`INVALID_MONEY_FORMAT` 등)를 그대로 쓰고 범위 절에 적었다.
    - `unknown-query-limit` 값 검증, 조회 중 넘겨진 시도의 provider 결론 WARN: 영향이 낮아 후속으로 둔다.
- **한계**
  - 대상 조회에 더한 `reconciliation_requested_at is null`은 V013 partial 인덱스의 술어 밖이라 인덱스 뒤 Filter로 남는다. 넘긴 시도도 `UNKNOWN`이라 인덱스에 남는다. 실행 계획은 다시 재지 않았다. 넘긴 채 해소되지 않은 건이 수천 건으로 쌓이면 술어에 넣은 인덱스를 다시 본다.
  - 점주 목록은 최신순 최대 100건이고 그 이상의 페이지는 없다(범위 절, 사용자 결정으로 최신순 유지). 100건을 넘는 매장이 생기면 가장 오래된 case부터 화면에서 빠지고 잘렸다는 신호도 없다. 그 전에 pending gauge(24시간 알림)로 쌓임을 본다.
  - "60회 ≈ 1시간"은 결과 불명 승인 시도가 배치 크기(100) 이하일 때다. 더 많으면 한 건은 `ceil(불명 건수 / 100)`분마다 조회되어 넘김까지 그만큼 늦어진다(PG 장애로 1,000건이면 약 10시간). 알림 조건 `unknown - pending > 0 for 2h`도 "불명이 하나라도 계속 있음"을 재므로 새 불명이 꾸준히 들어오면 작업이 멀쩡해도 울린다. 작업 생존 metric과 함께 알림 규칙을 만드는 PR이 다시 본다.
  - 승인 응답에서 금액 불일치로 불명이 된 시도가 이후 조회에서 계속 불명이면 `QUERY_LIMIT_EXCEEDED`로 넘어가고, provider가 처음 말한 금액·참조는 WARN 로그에만 남는다(case 근거에는 불명 사유 `AMOUNT_MISMATCH`만 있다). 그래도 거절 해소에는 취소 참조가 필수다(결정 7).
  - V015 전에 등록된 단말의 매장은 `t_store` 행이 없어 어떤 점주도 `requireOwned`를 통과하지 못한다. 그런 매장의 case는 플랫폼 운영자 주체(#91)가 생길 때까지 해소할 사람이 없다.
  - 조회가 알려 준 참조(`reported_provider_reference varchar(255)`)는 provider 값이라 길이를 검사하지 않는다. 255자를 넘으면 case 열기가 재시도 뒤 DLT로 간다. 실제 PG 어댑터 PR이 참조 형식과 함께 확인한다.
  - 롤링 배포 중 이전 버전이 넘긴 시도를 조회로 확정할 수 있다(스키마 변경 검토 질문 8). 그러면 점주는 `409 RECONCILIATION_PAYMENT_ALREADY_CONCLUDED`의 메시지를 보고 결제 결말과 같은 결론으로 다시 해소한다.
