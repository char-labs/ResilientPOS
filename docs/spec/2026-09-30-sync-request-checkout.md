# PR spec: 단말의 오프라인 결제 요청 operation을 sync 배치로 받는다

## 목적
단말은 오프라인에서 주문을 만들고 곧바로 결제를 요청한다. 재연결 뒤 sync 배치로 주문은 보낼 수 있지만 결제 요청은 보낼 길이 없다. `POST /v1/orders/{orderId}/checkout`은 서버가 만든 `orderId`를 요구하는데, 단말은 같은 배치의 앞 항목이 적용되기 전에는 그 값을 모른다. 단말이 아는 것은 자기가 만든 `clientOrderId`다([sync-intake](2026-09-30-sync-intake.md) 결정 3과 제외 절 첫 항목).

기대 결과:
- sync 배치 항목이 종류(`kind`)를 가진다. `CREATE_ORDER`와 `REQUEST_CHECKOUT` 둘이다.
- `REQUEST_CHECKOUT` 항목은 `clientOrderId`와 단말이 본 합계(`amount`)를 싣고, 서버는 그 단말의 주문을 찾아 확정 금액이 그 합계와 같을 때만 checkout을 요청한다. 판정·checkout 확정·`OrderPaymentRequested` 발행 기록·접수 기록·watermark 전진이 한 커밋이다(도메인 모델 §4.6).
- 접수 기록 `t_sync_operation`이 종류를 남긴다.

## 범위
소유 모듈은 `sync`(종류 분기, 접수 기록의 종류)와 `order`(`clientOrderId`로 찾는 checkout 요청 경로)다.

**결정 1: `order`에 `clientOrderId`로 찾는 checkout 요청을 새 커맨드와 메서드로 더한다.**
- 문제: 단말은 오프라인에서 `orderId`를 모른다.
- 제약: `findByIdForUpdate`는 그 트랜잭션에서 주문의 첫 접촉이어야 한다. sync가 `clientOrderId`로 먼저 읽고 `orderId`로 다시 잠그면 이 조건을 어긴다. 기존 `RequestCheckout(orderId)`는 HTTP 계약이라 모양을 바꾸지 않는다.
- 수단: `OrderCommand.RequestCheckoutByClientOrderId(terminalId, clientOrderId, expectedAmount)`(`expectedAmount`는 결정 4)와 그것을 받는 `OrderCommandFacade.requestCheckout` 오버로드. 결과는 `OrderCommand.RequestCheckout.Result`를 그대로 쓴다. `OrderRepository.findByClientOrderIdForUpdate(terminalId, clientOrderId)`가 키로 잠그고 읽는다(`PESSIMISTIC_WRITE`, 대기 3초). 주문이 없으면 `ORDER_NOT_FOUND`다. 전이·저장·발행·결과 변환은 두 메서드가 private 함수 하나를 함께 쓴다.
- `create`와 달리 합류 전용 메서드를 따로 두지 않는다. `requestCheckout`은 `Lock` 블록이 아니라 `Tx.masterTx`(`REQUIRED`)라서 sync 항목 트랜잭션 안에서 부르면 그대로 합류한다. 트랜잭션 밖에서 불러도 스스로 한 트랜잭션이라 호출 조건 계약이 필요 없다.
- `terminalId`는 sync 경로의 값이므로 다른 단말의 주문은 찾지 못한다.

**결정 2: 요청 항목은 `kind`를 반드시 가진다.** 없거나 모르는 값이면 Jackson이 기존 형식 오류와 같은 경로로 배치를 `400 BAD_REQUEST`로 거절한다. 서버가 종류를 추측하지 않는다. 배포된 단말이 없어 `kind` 없는 지금의 본문을 받아 줄 이유가 없다.

```json
{
  "operations": [
    { "operationId": "0199-a", "sequence": 1, "kind": "CREATE_ORDER",
      "order": { "storeId": "0199-s", "clientOrderId": "0199-c", "currency": "KRW", "lines": [] } },
    { "operationId": "0199-b", "sequence": 2, "kind": "REQUEST_CHECKOUT",
      "clientOrderId": "0199-c", "amount": "3502" }
  ]
}
```

- `adapter/web`: `SyncOperationRequest`를 `kind`로 갈리는 sealed 계층(`CreateOrder`, `RequestCheckout`)으로 바꾼다.
- `domain`: `sealed interface OperationPayload`가 `kind`와 `hash()`를 가진다. 구현은 기존 `OrderPayload`와 새 `CheckoutPayload(clientOrderId, amount)`다. `amount`는 `OrderPayload`의 단가처럼 단말이 보낸 raw `BigDecimal`이다. `SyncOperationKind`(`CREATE_ORDER`·`REQUEST_CHECKOUT`)는 요청의 `kind` 값이자 DB 저장 값이다.
- `SyncCommand.ReceiveBatch.Operation`의 `order`는 `payload`가 된다.
- `SyncCommandFacade`는 항목 트랜잭션 안에서 else 없는 `when`으로 갈라 `createInCallerTransaction` 또는 `requestCheckout`을 부른다. `when`은 payload 타입으로 갈리므로 `OperationPayload` 구현을 더하면 이 `when`이 컴파일되지 않는다. enum 상수나 요청 DTO의 하위 타입만 더하는 것은 컴파일러가 잡지 않는다. 분기가 한 곳이라 전략 클래스로 올리지 않는다.
- `CheckoutPayload.hash()`는 `OrderPayload`와 같은 공식이다. `REQUEST_CHECKOUT`, `clientOrderId`, raw `amount.toString()`을 각각 `길이:값;`으로 이은 문자열의 SHA-256 hex다. `toPlainString()`을 쓰지 않는다. hash는 `Money`의 거절보다 먼저 돌고, `toPlainString()`은 `1E+999999999` 같은 지수 표기를 자릿수만큼 펴서 입력 하나로 메모리를 다 쓴다. 같은 금액이라도 scale이 다르면(`3502`와 `3502.0`) 다른 hash다. `3502`와 `3.502E3`은 같은 값·같은 scale이라 같은 hash다. 단말은 저장한 봉투를 그대로 다시 보낸다. `OrderPayload`의 접두어와 필드 순서는 그대로다. 단가 표기는 결정 6이 바꾼다.

**결정 3: `t_sync_operation`에 `kind` 컬럼을 더한다.** 업무 코드가 읽지 않는 쓰기 전용 컬럼이다. 그래도 지금 더하는 이유는 나중에 채울 수 없어서다. 두 종류가 섞인 뒤에는 기존 행의 종류를 다른 컬럼에서 알아낼 수 없다(hash는 단방향이고 `order_id`는 두 종류 모두 가진다). 지금은 모든 행이 `CREATE_ORDER`라 상수 기본값이 정확한 값이다. 답은 아래 스키마 변경 검토 절에 있다.

**결정 4: `REQUEST_CHECKOUT`은 단말이 본 합계를 싣고 `order`가 확정 금액과 대조한다(범위 조정, 제출 전 리뷰 뒤 추가).**
- 문제: `clientOrderId`만으로는 단말이 어느 내용의 주문을 가리키는지 서버가 모른다. 서버에 같은 `(terminalId, clientOrderId)`의 주문이 다른 내용으로 이미 있으면(HTTP 생성, 단말의 `clientOrderId` 재사용) 앞 순번의 `CREATE_ORDER`는 `IDEMPOTENCY_CONFLICT`로 거절되는데, 뒤의 `REQUEST_CHECKOUT`은 그 기존 주문을 찾아 단말이 모르는 금액으로 결제 요청을 낸다. 제출 전 리뷰가 찾았다.
- 제약: 금액은 `payload_hash` 공식에 들어가야 재전송 판정이 내용을 본다. `REQUEST_CHECKOUT` 행이 쌓이기 전인 지금만 공식을 하나로 정할 수 있다. 나중에 더하면 금액이 있는 공식과 없는 공식 둘이 된다.
- 수단: 요청 항목에 `amount`(주문 통화의 decimal string)를 필수로 둔다. `order`는 주문 행 잠금 아래에서 전이한 뒤 저장하기 전에 최신 checkout 회차의 확정 금액을 `Money.of(expectedAmount, 주문 통화)`와 대조하고, 다르면 `DomainException(CHECKOUT_AMOUNT_MISMATCH, CONFLICT)`로 던진다. 대조 규칙은 `Order`에 둔다(`verifySamePayload`와 같은 자리). 던지면 아무것도 저장하지 않고 이벤트도 내지 않는다. 이미 `CHECKOUT_REQUESTED`·`PAID`인 주문도 최신 회차 금액과 대조한다.
- `Money` 변환은 `order` 안에서 주문의 통화로 한다. 통화 자릿수에 맞지 않는 금액(`INVALID_MONEY_SCALE` 등)도 `DomainException`이라 sync에서는 그 항목의 `REJECTED`다.
- `orderId`로 찾는 HTTP checkout은 바꾸지 않는다. 그 `orderId`는 단말이 생성 응답으로 받은 값이라 가리키는 주문이 분명하다.

**판정.** `REQUEST_CHECKOUT` 항목도 기존 판정 표를 그대로 탄다([sync-intake](2026-09-30-sync-intake.md) 범위 절).
- 다음 순번이고 주문이 있고 금액이 같으면 `APPLIED`와 `orderId`. 주문이 이미 `CHECKOUT_REQUESTED`나 `PAID`여도 도메인의 멱등 전이라 `APPLIED`다. 단말은 응답만으로 새 checkout인지 이미 요청된 것인지 구분하지 못한다. 같은 주문에 다른 `operationId`로 결제 요청이 두 번 와도 둘 다 `APPLIED`이고 checkout 회차는 하나다.
- 주문이 없으면 `order`가 `ORDER_NOT_FOUND`로 거절하므로 `REJECTED`다. 앞 순번의 `CREATE_ORDER`가 입력 규칙(빈 상품명, 금액 자릿수)으로 거절됐을 때가 대표적이다. 합계가 금액 상한을 넘는 주문의 `INVALID_MONEY_AMOUNT`도 같은 경로다.
- 주문은 있는데 확정 금액이 단말의 `amount`와 다르면 `CHECKOUT_AMOUNT_MISMATCH`로 `REJECTED`다. 앞 순번의 `CREATE_ORDER`가 `IDEMPOTENCY_CONFLICT`로 거절됐을 때가 대표적이다. 주문은 `OPEN`으로 남는다.
- 재전송은 `DUPLICATE`, 같은 `operationId`에 다른 `clientOrderId`나 다른 `amount`, 다른 종류가 오면 hash가 달라 `CONFLICT`다.
- 주문 행 잠금 대기 초과는 인프라 실패라 항목 결과가 아니고 배치 오류(`409 CONCURRENT_REQUEST`)다.

**잠금.** `REQUEST_CHECKOUT` 항목 트랜잭션은 행 잠금을 둘 기다린다. watermark 행 다음에 주문 행이고 각각 대기 3초다. 순서는 늘 같다. HTTP checkout과 `markPaid`는 주문 행만 잡고 watermark를 건드리지 않으므로 서로를 기다리는 순환이 없다. Redis `Lock`은 이 경로에 없다.

**결정 5: `productCode`·`productName`의 길이 상한을 `OrderLine`이 거절한다(범위 조정, 병목 점검 뒤 추가).**
- 문제: 컬럼 상한(`product_code varchar(64)`, `product_name varchar(255)`)을 넘는 `CREATE_ORDER` 항목이 커밋에서 `DataIntegrityViolationException`으로 실패해 배치가 500이다. 재전송도 같은 자리에서 500이고 watermark가 전진하지 않아 그 단말의 뒤 항목(주문과 결제 요청)이 영구히 멈춘다. `productName` 256자로 HTTP에서 재현했다.
- 제약: sync는 `DomainException`만 항목의 `REJECTED`로 바꾼다. DB 오류를 거절로 바꾸면 unique 경합 같은 인프라 실패까지 거절로 확정된다.
- 수단: `OrderLine`이 빈 값 검사 옆에서 길이를 검사하고 `PRODUCT_CODE_TOO_LONG`·`PRODUCT_NAME_TOO_LONG`(`INVALID`)로 던진다. 상한 상수는 컬럼 길이를 따라간다(`Money.MAX_INTEGER_DIGITS`와 같은 방식). 길이는 PostgreSQL `varchar(n)`과 같은 문자(code point) 수로 센다. sync에서는 그 항목이 `REJECTED`가 되어 단말이 버리고 나아가고, `POST /v1/orders`는 500 대신 400이 된다. 이미 주문이 있는 키는 지금처럼 멱등 판정이 먼저다.

**결정 6: `OrderPayload.hash()`의 단가를 `toString()`으로 넣는다(범위 조정, 병목 점검 뒤 추가).**
- 문제: hash는 `Money`의 거절보다 먼저 도는데 `toPlainString()`이 지수 표기를 자릿수만큼 편다. `unitPrice`가 `1E+40000000`인 요청이 hash를 계산한 뒤에야 거절되는 것을 HTTP로 확인했다. 지수에 비례해 메모리를 쓰므로 더 큰 지수 하나로 힙을 다 쓸 수 있다.
- 제약: `payload_hash`는 저장 형식이다. 다만 배포된 환경이 없어 기록된 행이 없다. `toString()`은 scale이 0 이상이고 조정 지수(precision - 1 - scale)가 -6 이상이면 평문으로 적는다. 그런 값(`4500`, `4500.50`)은 `toPlainString()`과 같은 문자열이다. 갈리는 기준은 입력의 표기가 아니라 값의 scale이다.
- 수단: 분기 없이 `toString()`으로 바꾼다. `CheckoutPayload`와 공식이 하나가 된다. 위 조건에 드는 단가의 hash는 그대로다(`PayloadHashTest`의 고정 hex가 그대로 통과한다). 달라지는 것은 scale이 음수인 값(`1E+3`)과 조정 지수가 -6보다 작은 값(`0.0000001`, 소수 0이 일곱 개 이상인 `0.0000000`)이다. 뒤의 것은 평문으로 와도 달라지고, 그중 0은 `Money`가 받아들여 `APPLIED`가 되는 값이다. 범위를 미리 거절하는 검사는 두지 않는다. 거절은 지금처럼 항목 트랜잭션 안의 `Money`가 한다.

**병목 점검(범위 조정, PR을 연 뒤 추가).** 결제 요청 항목 하나가 내는 문장을 Hibernate 통계와 SQL 로그로 쟀다.
- 적용 경로는 항목당 18문장이다. watermark 잠금(`lock_timeout` 조회·설정·해제 3, 잠금 조회 1), `findOperation` 1, 주문 잠금(`lock_timeout` 3, 잠금 조회 1, line 조회 1, checkout 조회 1), 저장(line·checkout 다시 조회 2, checkout INSERT 1, 주문 UPDATE 1), 발행 기록 INSERT 1, 접수 기록 INSERT 1, watermark UPDATE 1이다. 배치마다 watermark 확인 조회와 응답용 조회가 하나씩 더 나간다.
- **이미 요청된 주문의 checkout은 저장하지 않는다.** 전이가 낸 이벤트가 없으면 바뀐 것이 없는데도 `save`가 line과 checkout을 다시 읽었다. `confirmCheckout`이 그 경우 `save`를 건너뛴다. HTTP checkout의 재요청도 같이 줄어든다.
- 새로 확정하는 경로의 자식 재조회 2문장은 그대로 둔다. 없애려면 persistence adapter가 읽은 자식을 기억해야 하고 `markPaid`와 주문 생성도 같은 `save`를 쓴다. 제외 절에 있다.
- 주문 잠금 조회의 조건 `(terminal_id, client_order_id)`는 V002의 unique 제약이 만든 인덱스가 받친다. 잠그는 것은 루트 행뿐이다.
- 교착 순환은 없다. watermark 잠금은 항목 트랜잭션의 첫 문장 한 곳뿐이고, 주문을 잠그는 세 경로는 트랜잭션마다 주문 하나만 잡는다. 항목마다 커밋하므로 주문 잠금이 항목 사이에 이어지지 않는다.
- 문장 수는 `SyncStatementCountIntegrationTest`가 멱등 재요청(9문장)과 재전송(4문장)을 고정한다. Hibernate 통계 기준이고 `lock_timeout` 문장은 통계에 잡히지 않는다. 항목당 한 문장이 늘면 500개 배치에서 500문장이다. 새로 확정하는 적용 경로는 고정하지 않는다. 커밋 직후 externalizer와 `payment` consumer가 같은 SessionFactory로 문장을 내 측정값이 실행마다 달랐다(14와 16).

**이벤트.** `OrderPaymentRequested`는 합류한 항목 트랜잭션에서 발행되어 발행 기록이 접수 기록과 같은 커밋에 들어간다. 이벤트 모양·토픽·consumer는 바뀌지 않는다. 결제 시도 생성은 지금처럼 `payment`의 consumer가 잇는다.

**HTTP.** endpoint와 응답 모양은 그대로다. `REQUEST_CHECKOUT` 항목의 응답도 `operationId`·`status`·`orderId`·`errorCode`다.

**계약**: 새 `DomainException` code는 셋이다. `PRODUCT_CODE_TOO_LONG`·`PRODUCT_NAME_TOO_LONG`(`INVALID`)은 `POST /v1/orders`에서 400으로, sync에서는 항목의 `errorCode`로 나간다. 나머지는 `CHECKOUT_AMOUNT_MISMATCH`(`CONFLICT`)다. sync에서는 항목의 `errorCode`로 나가고 HTTP 상태로는 나가지 않는다. 요청 항목의 `kind`와 `REQUEST_CHECKOUT`의 `amount`가 필수가 되는 것이 단말 계약의 변화다. `SyncOperationKind`의 이름은 세 곳에 묶여 있다. 단말 요청의 `kind` 값, `t_sync_operation.kind`의 저장 값, `payload_hash` 공식의 접두어다. 앞의 둘은 바꾸면 단말 계약과 데이터 마이그레이션이 필요하고, 셋째는 되돌릴 수 없다. hash가 단방향이라 다시 계산할 수 없어서 이름을 바꾸면 확정된 operation의 재전송이 모두 `CONFLICT`가 된다. 그래서 이름을 바꾸지 않는다. `PayloadHashTest`의 고정 hex가 이것을 잡는다.

**관측성**: 기존 배치 INFO 로그와 `CONFLICT` WARN 로그 그대로다. 더하는 것은 없다.

## 제외
- 응답 항목에 `checkoutNo`·확정 금액을 싣는 것. `DUPLICATE`에도 같은 값을 주려면 접수 기록에 컬럼이 더 필요하고 지금 읽는 단말이 없다. nullable 필드 추가라 나중에 넣어도 계약이 깨지지 않는다.
- `orderId`로 찾는 HTTP checkout의 금액 대조. 결정 4의 이유로 두지 않는다.
- 금액이 같으면 내용이 달라도 통과한다. 같은 `clientOrderId`에 합계만 같은 다른 주문이 있는 경우다. 내용까지 보려면 checkout 요청이 주문 내용을 다시 실어야 한다.
- 종류와 맞지 않는 필드는 조용히 무시된다. `REQUEST_CHECKOUT` 항목에 `order`가 함께 오거나 `CREATE_ORDER` 항목에 최상위 `clientOrderId`가 와도 200이고, 무시된 필드는 hash에도 들어가지 않는다. 이 앱의 Jackson이 모르는 필드를 거절하지 않기 때문이다.
- 주문 행 잠금 대기 초과가 배치 오류(409)가 되는 것을 sync 경로에서 잇는 테스트는 없다. `order`의 잠금 대기 테스트와 [sync-intake](2026-09-30-sync-intake.md)의 같은 제외를 따른다.
- 거절된 결제의 재결제 operation. 입구는 `PaymentManagement.retryAuthorization`뿐이고 단말이 거절 사실을 아는 조회도 아직 없다.
- `kind` 컬럼의 기본값 제거. 새 코드는 늘 값을 적으므로 기본값은 이전 버전 인스턴스가 내려간 뒤에는 쓰이지 않는다. 앱 코드는 `kind` 없이 접수 기록을 만들 수 없으므로 기본값이 남아서 생기는 일은 `kind`를 빠뜨린 수동 SQL이 실패하지 않고 `CREATE_ORDER`로 기록되는 것 하나다. 제거는 이 컬럼을 적는 코드만 배포에 남은 다음 PR의 일이다.
- **도메인 규칙이 아닌 이유로 늘 실패하는 항목은 여전히 단말 큐를 막는다.** 길이 상한은 결정 5가 닫았지만 같은 모양의 입력이 남아 있다. 확인한 것은 NUL 문자다. `productCode`나 `productName`에 `\u0000`이 든 `CREATE_ORDER` 항목은 PostgreSQL이 `invalid byte sequence for encoding "UTF8": 0x00`으로 거절해 `DataIntegrityViolationException`이 되고 배치가 500이며 watermark가 전진하지 않는다(sync Facade로 관측). 항목 트랜잭션이 `DomainException`이 아닌 예외로 결정적으로 실패하면 재전송마다 같은 배치 오류다. 찾으면 그 입력을 소유 컨텍스트의 도메인 규칙으로 거절한다.
- scale이 음수이거나 조정 지수가 -6보다 작은 단가의 hash가 #71과 달라진다(결정 6). 거절된 operation의 hash도 기록되므로 `APPLIED`가 될 수 없는 값도 여기에 든다. 배포된 행이 없어 옮길 데이터가 없다는 전제에 기댄다. 그 전제가 틀리면 그 operation들의 재전송이 `CONFLICT`가 된다.
- **값 때문에 배치 전체가 400이 되는 입력도 단말 큐를 막는다.** 역직렬화에서 끊기면 앞 순번의 정상 항목까지 적용되지 않고 재전송마다 같은 400이다. 65,536자를 넘는 문자열(256자부터 65,536자까지의 상품명은 `REJECTED`인데 그 위는 배치 400이다), 1,000자를 넘는 숫자, Int 범위 밖이거나 소수인 `quantity`, ISO 4217에 없는 `currency`가 그렇다. `POST /v1/orders`에서는 같은 상한을 테스트가 확인하고 sync endpoint로는 실행해 보지 않았다. 닫으려면 그 필드를 raw로 받아 항목 트랜잭션 안에서 거절해야 한다.
- 길이 판정이 `varchar(n)`과 어긋나는 경우가 하나 있다. PostgreSQL은 초과분이 모두 공백이면 잘라서 저장하는데 도메인은 거절한다. 도메인이 더 엄격한 쪽이고, 잘린 값이 재시도의 내용 대조를 깨던 것도 없어진다.
- 대조에 통화가 없다. 같은 키의 KRW 3502원 주문이 있는데 단말이 JPY 3502엔으로 만들어 결제를 요청하면 통과한다. 통화를 선택 필드로 나중에 더해도 없을 때의 hash 공식이 지금과 같아 호환된다.
- 새로 확정하는 checkout의 `save`가 line과 checkout을 다시 읽는 2문장. 병목 점검 문단에 이유가 있다. 다시 볼 조건은 부하 측정에서 이 조회가 드러날 때다.
- 배치의 동시 실행 수·빈도·전체 시간에는 상한이 없다. 배치 하나가 커넥션 하나를 거의 연속으로 쓰므로 동시에 도는 배치가 풀 크기(10)를 넘으면 풀이 상시 포화다. 수치는 재지 않았다. k6 부하 측정에서 단말 수와 배치 크기로 `hikaricp_connections_pending`과 배치 시간을 본 뒤 권장 배치 크기나 동시 실행 상한을 정한다.
- 브로커 장애 때 externalizer가 커넥션을 쥔 채 막히는지는 소스로 확인했고 해당하지 않는다. Modulith 2.1.1의 `EventExternalizerModuleListener.externalize`는 전파 `SUPPORTS`라 트랜잭션을 열지 않는다.
- 종류별 전략 클래스나 핸들러 레지스트리. 분기가 Facade의 `when` 한 곳이다. 같은 분기가 두 곳 이상에 생기면 다시 본다.
- **`ORDER_NOT_FOUND` 거절은 다른 거절과 달리 시점에 따라 달라질 수 있다.** 거절이 기록된 뒤에, 또는 첫 트랜잭션이 롤백되고 거절을 기록하는 두 번째 트랜잭션 전에 같은 `clientOrderId`의 주문이 HTTP로 만들어져도 그 operation은 `REJECTED`로 남고 단말은 버린다. 잠금 조회 시점에 그 주문의 INSERT가 커밋 전이어서 보이지 않았을 때도 같다. 단말이 새 `operationId`로 다시 요청해야 한다.
- 롤링 배포 중 이전 버전 인스턴스가 `REQUEST_CHECKOUT` 항목을 받으면 `order` 필드가 없어 역직렬화에서 배치가 `400`이다. 그 배치의 항목은 하나도 적용되지 않고 단말의 재전송이 새 인스턴스에 닿으면 처음부터 적용된다.
- 셋째 종류를 더하는 롤링 배포에서 옛 인스턴스가 모르는 `kind` 값의 행을 `findOperation`으로 읽으면 enum 복원이 실패해 `CONFLICT`가 아니라 배치 오류가 된다. 그 operationId가 옛 인스턴스에 다시 와야 하는 드문 경로이고 `status`도 같은 성질이다. 셋째 종류를 더하는 PR이 다룬다.
- 단말 인증·소유권 검사, `SyncOperationApplied` 이벤트, metric은 [sync-intake](2026-09-30-sync-intake.md)의 제외 그대로다.

## 관련 문서
없음

## 변경 경로
- src/main/kotlin/com/resilientpos/sync/**
- src/main/kotlin/com/resilientpos/order/OrderCommand.kt
- src/main/kotlin/com/resilientpos/order/OrderCommandFacade.kt
- src/main/kotlin/com/resilientpos/order/internal/domain/OrderRepository.kt
- src/main/kotlin/com/resilientpos/order/internal/domain/Order.kt
- src/main/kotlin/com/resilientpos/order/internal/domain/OrderLine.kt
- src/main/kotlin/com/resilientpos/order/internal/adapter/persistence/command/OrderPersistenceAdapter.kt
- src/main/resources/db/migration/V011__sync_operation_kind.sql
- src/test/kotlin/com/resilientpos/**
- docs/wiki/domain-model.md
- docs/wiki/entity-map.md
- docs/spec/2026-09-30-sync-request-checkout.md

## 인수 조건
1. `OrderCommandFacade.requestCheckout(RequestCheckoutByClientOrderId)`가 그 단말의 `OPEN` 주문을 `CHECKOUT_REQUESTED`로 전이하고 `orderId` 경로와 같은 `Result`(`orderId`·`status`·`checkoutNo`·`amount`)를 돌려주며 `t_order_checkout`에 한 행이 생긴다. 다시 부르면 같은 결과이고 행이 늘지 않는다.
2. 그 키의 주문이 없으면 `ORDER_NOT_FOUND`(`NOT_FOUND`)다. 같은 `clientOrderId`라도 다른 `terminalId`면 찾지 못한다.
3. 순번 1 `CREATE_ORDER`, 순번 2 같은 `clientOrderId`의 `REQUEST_CHECKOUT`을 한 배치로 보내면 둘 다 `APPLIED`이고 `orderId`가 같다. 주문은 `CHECKOUT_REQUESTED`, `t_order_checkout` 한 행, `t_sync_operation` 두 행의 `kind`가 각각 `CREATE_ORDER`·`REQUEST_CHECKOUT`이며 watermark는 2다.
4. 같은 배치를 다시 보내면 둘 다 `DUPLICATE`이고 같은 `orderId`이며 checkout 행과 접수 기록이 늘지 않는다.
5. 주문이 없는 `clientOrderId`의 `REQUEST_CHECKOUT`은 `REJECTED`와 `errorCode` `ORDER_NOT_FOUND`이고 순번은 확정된다. 재전송은 같은 `errorCode`의 `DUPLICATE`이고 그 뒤 순번은 정상 적용된다.
6. 확정된 `REQUEST_CHECKOUT`의 `operationId`에 다른 `clientOrderId`가 오거나, `CREATE_ORDER`로 확정된 `operationId`가 `REQUEST_CHECKOUT`으로 오면 `CONFLICT`이고 아무것도 바뀌지 않는다.
7. 항목 트랜잭션은 하나다. checkout을 확정한 뒤 접수 기록 저장이 unique `(terminal_id, sequence)`로 실패하면 주문은 `OPEN`으로 남고 checkout 행과 `OrderPaymentRequested` 발행 기록이 없다.
8. `CheckoutPayload.hash()`는 같은 `clientOrderId`에 같은 값, 다른 `clientOrderId`에 다른 값이고 같은 `clientOrderId`를 가진 `OrderPayload`의 hash와 다르다. scale이 0 이상이고 조정 지수가 -6 이상인 단가의 `OrderPayload.hash()` 값은 이 PR 전과 같다(고정 입력의 기대 hex로 확인). Spring 없는 단위 테스트다.
9. HTTP: `kind`가 `REQUEST_CHECKOUT`인 항목이 `SyncCommand`로 옮겨져 Facade에 닿는다. `kind`가 없거나 모르는 값이면 `400 BAD_REQUEST`이고 Facade를 부르지 않는다. REST Docs 요청 필드에 `kind`와 `REQUEST_CHECKOUT`의 `clientOrderId`를 적는다. HTTP부터 PostgreSQL까지 이어지는 통합 테스트 하나가 인수 3을 HTTP로 확인한다.
10. V011이 `kind varchar(32) not null default 'CREATE_ORDER'`를 더하고 `ddl-auto: validate`가 통과한다. `kind`를 적지 않은 INSERT(이전 버전 인스턴스를 흉내 낸 JDBC)가 성공하고 그 행의 `kind`가 `CREATE_ORDER`다. `SchemaMigrationTest`의 버전 목록에 011이 있다.
11. 기존 `CREATE_ORDER` 동작([sync-intake](2026-09-30-sync-intake.md) 인수 1~6·9)이 그대로 통과한다.
12. `./gradlew test` 전체와 `python3 scripts/check_pr.py --base origin/main --worktree`가 통과한다.
13. `requestCheckout(RequestCheckoutByClientOrderId)`의 `expectedAmount`가 확정 금액과 다르면 `CHECKOUT_AMOUNT_MISMATCH`(`CONFLICT`)이고 주문은 `OPEN`으로 남으며 checkout 행과 발행 기록이 없다. 이미 `CHECKOUT_REQUESTED`인 주문에 다른 금액이 와도 같은 거절이고 아무것도 바뀌지 않는다. 통화 자릿수에 맞지 않는 금액은 `Money`의 거절 code다. 대조 규칙은 `Order`의 Spring 없는 단위 테스트로 고정한다.
14. 서버에 같은 `(terminalId, clientOrderId)`의 주문이 다른 내용·다른 합계로 이미 있을 때 순번 1 `CREATE_ORDER`(다른 내용), 순번 2 `REQUEST_CHECKOUT`(단말의 합계)을 보내면 순번 1은 `REJECTED`(`IDEMPOTENCY_CONFLICT`), 순번 2는 `REJECTED`(`CHECKOUT_AMOUNT_MISMATCH`)이고 기존 주문은 `OPEN`으로 남으며 checkout 행이 없고 watermark는 2다.
15. `CheckoutPayload.hash()`는 `amount`가 다르면 다른 값이다. 확정된 `REQUEST_CHECKOUT`의 `operationId`에 다른 `amount`가 오면 `CONFLICT`다. HTTP 요청의 `amount`가 없으면 `400 BAD_REQUEST`이고 REST Docs 요청 필드에 `amount`가 있다.
16. 같은 주문에 다른 `operationId`의 `REQUEST_CHECKOUT`이 두 번 오면 둘 다 `APPLIED`이고 checkout 행과 발행 기록은 하나다.
17. 이미 요청된 주문의 checkout은 `save`를 부르지 않는다. 결제 요청 항목의 문장 수(멱등 재요청 9, 재전송 4)를 Hibernate 통계로 고정한다. 적용 경로의 문장 수는 비동기 문장이 섞여 고정하지 않는다. 금액 불일치로 거절된 operation의 재전송은 같은 `errorCode`의 `DUPLICATE`이고, 그 뒤 올바른 금액의 새 `operationId`는 `APPLIED`다. `amount`가 `1E+3`인 `CheckoutPayload`의 hash가 고정 hex와 같고 `1E+999999999`의 hash가 끝난다.
18. `productCode`가 65자이거나 `productName`이 256자인 line은 `PRODUCT_CODE_TOO_LONG`·`PRODUCT_NAME_TOO_LONG`(`INVALID`)이고 64자·255자는 통과한다. 길이는 code point로 센다(surrogate pair 하나는 한 글자다). `POST /v1/orders`는 `400`과 그 code를 돌려준다. sync에서 그런 `CREATE_ORDER` 항목은 `REJECTED`와 그 `errorCode`이고 순번이 확정되며 다음 항목은 적용된다.
19. `OrderPayload.hash()`는 단가가 `1E+999999999`·`1E-999999999`여도 64자 hex를 돌려주고, 단가가 `1E+3`·`1E+4`·`0.0000000`인 입력의 hash는 고정 hex와 같다(`4:1E+3;`, `4:1E+4;`, `4:0E-7;`). `1001`과 `4500.50`의 고정 hex는 그대로다. 이모지 64자 상품 코드와 255자 상품명의 주문이 PostgreSQL에 저장된다. sync에 단가가 `1E+999999999`인 항목을 보내면 `REJECTED`(`INVALID_MONEY_AMOUNT`)다.

### Checkpoint와 검증 명령
| Checkpoint | 검증 명령 | 의존 |
|---|---|---|
| order-checkout-by-key (1, 2) | `./gradlew test --tests 'com.resilientpos.order.*'` | 없음 |
| sync-domain (8) | `./gradlew test --tests 'com.resilientpos.sync.internal.domain.*'` | 없음 |
| sync-facade (3~7, 10, 11) | `./gradlew test --tests 'com.resilientpos.sync.internal.application.*' --tests 'com.resilientpos.SchemaMigrationTest'` | order-checkout-by-key, sync-domain |
| sync-api (9) | `./gradlew test --tests 'com.resilientpos.sync.internal.adapter.web.*'` | sync-facade |
| order-checkout-amount (13) | `./gradlew test --tests 'com.resilientpos.order.*'` | order-checkout-by-key |
| sync-checkout-amount (14~17) | `./gradlew test --tests 'com.resilientpos.sync.*'` | order-checkout-amount, sync-facade, sync-api |
| input-hardening (18, 19) | `./gradlew test --tests 'com.resilientpos.order.*' --tests 'com.resilientpos.sync.*'` | sync-checkout-amount |
| 전체 (12) | `./gradlew test` · `python3 scripts/check_pr.py --base origin/main --worktree` | 모두 |

## 스키마 변경 검토
`V011__sync_operation_kind.sql`이 `t_sync_operation`에 컬럼 하나를 더한다.

```sql
set local lock_timeout = '5s';

alter table t_sync_operation
    add column kind varchar(32) not null default 'CREATE_ORDER';
```

매핑은 `SyncOperationEntity.kind`(`SyncOperationKind`, `EnumType.STRING`)이고 같은 표의 `status`와 같은 구성이다.

- 질문 1(도출): 도출할 수 없다. `payload_hash`는 종류 접두어를 넣어 만들지만 단방향이고, `order_id`는 두 종류 모두 가지며, `error_code`는 거절된 행에만 있다.
- 질문 2(읽는 코드): 업무 코드는 읽지 않는다. 재전송 판정은 `payload_hash`가, `DUPLICATE` 응답은 `order_id`·`error_code`가 한다. 읽는 것은 사람의 조사(`CONFLICT` 원인, 어떤 operation이 거절됐나)와 테스트다. 읽는 코드가 없는데도 더하는 근거는 질문 3이다.
- 질문 3(나중에 채우기): 이 PR 뒤에는 채울 수 없다. 지금은 모든 행이 `CREATE_ORDER`라 상수 기본값이 정확하다. `REQUEST_CHECKOUT` 행이 하나라도 쌓이면 기존 행의 종류를 알 방법이 없어 나중에 더하는 컬럼은 그 전 행을 "모름"으로 남긴다. 도메인 모델 §4.7의 "추가하지 않는다" 판정은 나중에 채울 수 있는 값이 전제라 여기에 해당하지 않는다.
- 질문 4(마지막 방어선): 아니다. 제약이 없고 불변식을 지키지 않는다. "같은 operation을 두 번 적용하지 않는다"는 기존 unique 둘과 `payload_hash`가 지킨다.
- 질문 5(정본): 종류라는 사실이 `payload_hash`의 접두어와 `kind` 두 곳에 있다. 둘 다 같은 `OperationPayload`에서 나와 같은 INSERT로 쓰이고, 접수 기록은 넣기만 하고 바꾸지 않아 어긋날 경로가 없다. 판정의 정본은 hash이고 `kind`는 사람이 읽는 사본이다.
- 질문 6(check-then-act): `kind`를 읽고 판단하는 경로는 없다. 이 PR이 더하는 check-then-act는 주문 상태를 읽고 checkout을 확정하는 경로이고 주문 행 잠금(`findByClientOrderIdForUpdate`, `PESSIMISTIC_WRITE`, 대기 3초)이 막는다. 그 뒤의 방어선은 기존 unique `(order_id, checkout_no)`와 `t_order`의 `@Version`이다. 한 경로에 행 잠금이 둘(watermark, 주문)이고 순서가 고정이며 Redis `Lock`과 함께 기다리지 않는다.
- 질문 7(락): 상수 기본값이 있는 컬럼 추가라 메타데이터 변경이고 재작성이 없다. `ACCESS EXCLUSIVE`를 잠깐 잡는다. 긴 트랜잭션 뒤에서 기다리며 뒤 쿼리를 세우지 않도록 `set local lock_timeout`을 둔다. 값은 V005·V008과 같은 5초다. 마이그레이션은 배포 중 한 번 돌고 재실행할 수 있어 요청 경로의 행 잠금(3초)보다 길게 잡는다. Flyway가 이 마이그레이션을 한 트랜잭션으로 실행하므로 `set local`이 그 문장에 걸린다. 시간 초과로 실패하면 기동이 멈추고 다시 실행한다. 행 수는 접수한 operation 수이고 배포된 환경은 아직 없다.
- 질문 8(이전 버전): 호환된다. 이전 버전의 엔티티는 `kind`를 매핑하지 않아 `validate`가 통과하고, INSERT가 `kind`를 적지 않아도 기본값이 채운다. 이전 버전은 `CREATE_ORDER`만 만들므로 그 기본값이 옳은 값이다. SELECT는 매핑된 컬럼만 명시해 영향이 없다. 이전 버전 인스턴스는 요청의 `kind`를 모르는 필드로 무시하므로(이 앱의 Jackson은 모르는 필드를 거절하지 않는다. 이전 DTO에 `kind`를 넣은 요청이 200인 것을 관측했다) 새 형식의 `CREATE_ORDER` 항목은 그대로 적용한다. `REQUEST_CHECKOUT` 항목을 받는 경우는 제외 절에 있다.
- 질문 9(payload): 이벤트 payload와 캐시되는 DTO에 실리지 않는다. 요청의 `kind` 값과 같은 이름이 저장되고 같은 이름이 `payload_hash`의 접두어로 들어간다(범위 절의 계약 문단). 요청 DTO의 하위 타입 이름과 enum 이름은 따로 선언돼 있어 컴파일러가 묶지 않는다. 둘이 같다는 것은 HTTP로 보낸 값과 저장된 값을 두 종류 모두 비교하는 `SyncApiIntegrationTest`가 지킨다.
- 대조 기록: code-reviewer(읽기 전용 에이전트), 2026-09-30. V011·V010, SyncOperationEntity, SyncPersistenceAdapter, sync.internal.domain의 SyncOperation·SyncOperationKind·payload 셋, SyncCommandFacade, SyncBatchRequest, OrderCommandFacade, OrderRepository·OrderPersistenceAdapter, platform JdslQueryExecutor, application.yaml을 질문 1~9의 답과 대조했다. 이전 버전 엔티티는 main 체크아웃(7712a45)의 파일로 확인했다. 불일치는 없었다. 누락 6건을 받았다. enum 이름이 hash 접두어로 들어가 이름을 바꾸면 되돌릴 수 없다는 결합, 요청 DTO의 종류 이름과 enum 이름이 따로 선언된 것, `lock_timeout`이 선례(5초)와 다른 3초였던 것, 기본값을 남기는 대가, 모르는 `kind` 값을 가진 행을 옛 인스턴스가 읽으면 복원에 실패한다는 것, `when`이 막는 범위다. DDL의 `lock_timeout`을 5초로 맞추고 `SyncOperationKind` KDoc에 hash 결합을 적었으며 나머지는 이 절과 범위·제외 절의 문장으로 반영했다. 리뷰어는 셸이 없어 테스트와 Flyway를 실행하지 않았다. 실행 확인은 구현 쪽의 `./gradlew test`(V011 적용, `validate`, `kind` 없는 INSERT)가 했다.

## 검증
- 정상: 주문 생성과 결제 요청을 한 배치로 보내면 둘 다 `APPLIED`, 재전송은 둘 다 `DUPLICATE`.
- 실패: 주문 없는 결제 요청과 금액이 다른 결제 요청은 `REJECTED`와 전진, 다른 내용의 재전송은 `CONFLICT`, `kind`·`amount` 누락은 400, 접수 기록 저장 실패는 checkout까지 롤백.
- Docker(OrbStack)가 필요한 통합 테스트는 Testcontainers의 PostgreSQL·Redis·Kafka를 쓴다.
- `./gradlew test`, `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전 `--worktree` 없이 한 번 더.

## Wiki 영향
- [domain-model](../wiki/domain-model.md)
  - §4.6: sync가 부르는 `order` 경로에 `requestCheckout(RequestCheckoutByClientOrderId)`를 더하고, 그 항목이 watermark 행과 주문 행을 차례로 잠근다는 것을 적는다.
  - §4.7 사례의 sync 항목에 `kind` 판단(질문 2·3)을 더한다.
- [entity-map](../wiki/entity-map.md): `OrderLineEntity`의 방어선에 길이 상한, `SyncOperationEntity`의 `kind`, 주문을 바꾸는 곳에 sync의 checkout 요청, 오프라인 흐름 표에 `REQUEST_CHECKOUT` 단계를 더한다.
- current-state는 볼트 파일이라 저장소 커밋 없이 볼트에서 고친다. `sync` 문단의 "`REQUEST_CHECKOUT` operation은 없다"를 바꾸고 `order` 공개 계약에 새 커맨드를 적는다.
- README·`modulith-kafka-keywords`는 무영향이다. 이벤트·토픽·consumer가 바뀌지 않는다.

## 분할 계획
개발 컨텍스트는 하나다. sync의 둘째 operation 종류를 HTTP부터 커밋까지 잇는 일이고, 그것이 요구하는 `order` 진입점과 접수 기록의 종류 컬럼이 딸려 온다. 작업 시작 시점(origin/main 7712a45, #71 머지 뒤)에 열린 PR이 없어 변경 경로가 겹치지 않는다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 1, `OrderRequestCheckoutIntegrationTest`의 `clientOrderId로 requestCheckout하면 orderId 경로와 같은 결과이고 재요청은 행을 늘리지 않는다` | `./gradlew test --tests 'com.resilientpos.order.internal.application.OrderRequestCheckoutIntegrationTest'`. 커맨드·오버로드·포트를 선언하고 어댑터의 잠금 조회가 늘 null을 돌려주는 최소 선언으로 실행. `DomainException: 주문을 찾을 수 없습니다`로 실패 | 같은 명령. 어댑터를 `(terminal_id, client_order_id)` 잠금 조회로 채운 뒤 통과(11개) | `./gradlew test --tests 'com.resilientpos.order.*'` 통과(95개) |
| 인수 2, 같은 클래스의 `clientOrderId의 주문이 없거나 다른 단말의 키면 ORDER_NOT_FOUND다` | RED 미관측. 늘 null을 돌려주는 최소 선언이 기대와 같은 결과를 내 통과했다 | 같은 명령. 실제 키 조회에서도 통과 | 위와 같은 회귀 통과 |
| 인수 8, `PayloadHashTest`의 `CheckoutPayload의 hash는 같은 clientOrderId에 같고 다른 clientOrderId에 다르다`·`CheckoutPayload의 hash는 종류와 clientOrderId를 길이 접두어로 이은 SHA-256 hex다`(결정 4 뒤의 이름은 `…종류·clientOrderId·amount를…`) | `./gradlew test --tests 'com.resilientpos.sync.internal.domain.*'`. `CheckoutPayload.hash()`가 빈 문자열을 돌려주는 최소 선언으로 실행. 둘 다 `AssertionFailedError` | 같은 명령. 공식을 채운 뒤 통과(10개) | 길이 접두어·SHA-256을 `lengthPrefixedSha256` 하나로 모은 뒤 같은 명령 통과 |
| 인수 8, 같은 클래스의 `OrderPayload의 hash는 저장 형식이라 고정 입력의 기대 hex가 바뀌지 않는다`·`같은 clientOrderId를 가진 OrderPayload와 CheckoutPayload의 hash는 다르다`·`payload는 자기 종류를 돌려준다` | RED 미관측(characterization). 기대 hex는 변경 전 공식을 Python `hashlib`으로 따로 계산한 값이다. 제출 전에 origin/main(7712a45)을 따로 체크아웃해 같은 입력·같은 기대 hex의 테스트 하나를 그 코드로 실행했고 통과했다(`./gradlew test --tests 'com.resilientpos.sync.internal.domain.OrderPayloadHashOnMainTest'`, 임시 테스트라 커밋하지 않았다) | 같은 명령 통과 | 위와 같은 회귀 통과 |
| 인수 3~6, `SyncCommandFacadeIntegrationTest`의 `주문 생성 다음 순번의 결제 요청이 같은 배치로 오면 둘 다 적용되고 주문은 CHECKOUT_REQUESTED다`·`결제 요청까지 적용된 배치를 다시 보내면 모두 중복이고 checkout 행과 접수 기록이 늘지 않는다`·`주문이 없는 clientOrderId의 결제 요청은 ORDER_NOT_FOUND 거절이고 순번은 확정되며 재전송은 같은 거절의 중복이다`·`확정된 결제 요청 operationId에 다른 clientOrderId가 오거나 주문 생성 operationId가 결제 요청으로 오면 충돌이고 아무것도 바뀌지 않는다` | `./gradlew test --tests 'com.resilientpos.sync.*' --tests 'com.resilientpos.SchemaMigrationTest'`. V011·`kind` 매핑은 갖추고 Facade의 결제 요청 분기만 `UNSUPPORTED` 거절을 던지는 최소 선언으로 실행. 4개 모두 `AssertionFailedError`(expected APPLIED but was REJECTED, checkout 행 expected 1 but was 0, expected ORDER_NOT_FOUND but was UNSUPPORTED) | 같은 명령. 분기에서 `requestCheckout`을 부른 뒤 통과(35개) | `./gradlew test` 통과(317개) |
| 인수 7·10, 같은 클래스의 `결제 요청의 접수 기록 저장이 실패하면 checkout 행과 OrderPaymentRequested 발행 기록도 남지 않고 주문은 OPEN이다`, `SchemaMigrationTest`의 버전 목록 011 | RED 미관측. 최소 선언의 거절 기록도 같은 unique에 걸려 같은 예외와 같은 남은 행을 냈다. V011은 테스트보다 먼저 있었다 | 같은 명령 통과. `kind` 없는 JDBC INSERT가 `CREATE_ORDER`로 채워진다 | 위와 같은 회귀 통과 |
| 인수 9, `SyncControllerTest`의 `배치를 커맨드로 옮겨 Facade를 부르고 200과 항목별 결과·watermark를 돌려준다`(두 종류를 싣도록 고침), `SyncApiIntegrationTest`의 `주문 생성 다음 순번의 결제 요청을 HTTP로 보내면 둘 다 APPLIED이고 같은 주문이 CHECKOUT_REQUESTED다` | `./gradlew test --tests 'com.resilientpos.sync.internal.adapter.web.*'`. 테스트만 고치고 요청 DTO는 그대로 둔 채 실행. 둘 다 `AssertionError`(Status expected 200 but was 400). 기존 DTO가 `kind`를 무시하고 `order`를 요구했다 | 같은 명령. 요청 DTO를 `kind`로 갈리는 sealed 계층으로 바꾼 뒤 통과(6개). 스니펫 `sync-receive-batch`의 요청 필드에 `operations[].kind`·`operations[].clientOrderId`가 생겼다 | `./gradlew test --tests 'com.resilientpos.sync.*'` 통과(32개) |
| 인수 9, `SyncControllerTest`의 `kind가 없거나 모르는 값이면 400 BAD_REQUEST이고 Facade를 부르지 않는다` | RED 미관측. 본문에 `order`가 없어 기존 DTO도 400을 냈다 | 같은 명령 통과. 구현 뒤의 400은 Jackson이 하위 타입을 정하지 못해 나는 `HttpMessageNotReadableException`이고(`missing type id property 'kind'`, `Could not resolve type id`) 기존 `ApiControllerAdvice`가 `BAD_REQUEST`로 바꾼다 | 위와 같은 회귀 통과 |
| 인수 13, `OrderTest`의 `verifyCheckoutAmount는 확정 금액과 다르면 CHECKOUT_AMOUNT_MISMATCH를 던지고 주문은 그대로다`·`verifyCheckoutAmount는 통화 자릿수에 맞지 않는 금액을 Money의 거절 code로 던진다`, `OrderRequestCheckoutIntegrationTest`의 `clientOrderId 경로의 금액이 확정 금액과 다르면 CHECKOUT_AMOUNT_MISMATCH이고 주문은 OPEN이며 행도 이벤트도 없다`·`이미 CHECKOUT_REQUESTED인 주문에 다른 금액이 와도 같은 거절이고 checkout 행과 상태는 그대로다` | `./gradlew test --tests 'com.resilientpos.order.internal.domain.OrderTest' --tests 'com.resilientpos.order.internal.application.OrderRequestCheckoutIntegrationTest' --tests 'com.resilientpos.sync.*' --continue`. 필드만 더하고 대조는 빈 본문인 최소 선언으로 실행. 넷 다 `Expected an exception of class DomainException to be thrown, but was completed successfully` | `./gradlew test --tests 'com.resilientpos.order.*' --tests 'com.resilientpos.sync.*'`. `Order.verifyCheckoutAmount`와 저장 전 호출을 채운 뒤 통과 | `./gradlew test` 통과(330개) |
| 인수 13, `OrderTest`의 `verifyCheckoutAmount는 최신 회차의 확정 금액과 같으면 표기가 달라도 던지지 않는다` | RED 미관측(characterization). 빈 본문도 통과하는 조건이다 | 같은 명령 통과 | 위와 같은 회귀 통과 |
| 인수 15, `PayloadHashTest`의 `CheckoutPayload의 hash는 amount가 다르면 다르고 같은 금액도 표기가 다르면 다르다`·`CheckoutPayload의 hash는 종류·clientOrderId·amount를 길이 접두어로 이은 SHA-256 hex다` | 같은 RED 명령. hash가 `amount`를 무시하는 최소 선언으로 실행. `expected: not equal`, 기대 hex 불일치로 실패 | hash에 `amount`를 넣은 뒤 통과. 기대 hex는 Python `hashlib`으로 따로 계산했다 | 위와 같은 회귀 통과. 그 뒤 `toPlainString()`을 `toString()`으로 바꾸고 `./gradlew test --tests 'com.resilientpos.sync.*'` 통과 |
| 인수 14·15, `SyncCommandFacadeIntegrationTest`의 `서버에 같은 clientOrderId의 주문이 다른 합계로 있으면 주문 생성은 IDEMPOTENCY_CONFLICT 결제 요청은 CHECKOUT_AMOUNT_MISMATCH 거절이고 주문은 OPEN이다`·`확정된 결제 요청 operationId에 다른 amount가 오면 충돌이고 아무것도 바뀌지 않는다` | 같은 RED 명령. `expected [REJECTED, REJECTED] but was [REJECTED, APPLIED]`, `expected CONFLICT but was DUPLICATE`. 앞의 것이 리뷰가 찾은 결함의 재현이다 | 같은 GREEN 명령 통과 | 위와 같은 회귀 통과 |
| 인수 15, `SyncControllerTest`의 `amount가 없는 REQUEST_CHECKOUT 항목은 400 BAD_REQUEST이고 Facade를 부르지 않는다` | RED 미관측. 컴파일하려고 `amount` 필드를 먼저 선언했고 그때부터 누락이 400이었다 | 같은 GREEN 명령 통과. 스니펫 요청 필드에 `operations[].amount`가 생겼다 | 위와 같은 회귀 통과 |
| 리뷰 반영, `SyncControllerTest`의 `kind가 없거나 모르는 값이면 400 BAD_REQUEST이고 Facade를 부르지 않는다`(`kind`만 뺀 유효한 `CREATE_ORDER` 본문과 `kind`가 `REFUND`인 본문을 더함) | 요청 DTO의 `@JsonTypeInfo`에 `defaultImpl`을 넣은 변형으로 `./gradlew test --tests 'com.resilientpos.sync.*' --tests 'com.resilientpos.order.*'` 실행. `AssertionError`(Status expected 400)로 실패 | 변형을 되돌린 뒤 같은 명령 통과 | 같은 명령 통과 |
| 리뷰 반영, `OrderRequestCheckoutIntegrationTest`의 `커밋 전인 clientOrderId 경로 요청과 orderId 경로 요청이 겹치면 뒤 요청은 기다렸다가 같은 결과를 받는다` | `findByClientOrderIdForUpdate`를 잠금 없는 조회로 바꾼 변형으로 같은 명령 실행. 뒤 요청이 잠금을 기다리는 단계에서 `ConditionTimeoutException`으로 실패 | 변형을 되돌린 뒤 통과 | 같은 명령 통과 |
| 인수 16과 리뷰 반영, `SyncCommandFacadeIntegrationTest`의 `같은 clientOrderId의 결제 요청이 새 operationId로 다시 오면 적용되고 주문 행 checkout 행 발행 기록은 그대로다`, 성공 경로 테스트의 발행 기록 1건 단언 | RED 미관측(characterization). 지금 구현에서 통과한다. 발행 기록 1건 단언은 롤백 테스트의 0건 단언과 같은 조회를 쓰는 대조군이다 | 같은 명령 통과 | 같은 명령 통과 |
| 인수 17, `SyncStatementCountIntegrationTest`의 `이미 요청된 주문의 멱등 checkout 요청은 9문장이고 재전송은 4문장이다` | `./gradlew test --tests '*SyncStatementCountIntegrationTest'`. 수정 전 코드에서 `멱등 요청은 주문을 저장하지 않는다 expected 9 but was 11` | 같은 명령. `confirmCheckout`이 이벤트 없는 전이의 `save`를 건너뛴 뒤 통과. 전체 실행에서 다른 클래스의 비동기 문장이 섞여 한 번 10이 나와, 같은 요청을 다섯 번 재고 최솟값을 비교하게 했다(섞임은 수를 늘리기만 한다). 그 뒤 다섯 번 연속 통과 | `./gradlew test` 통과 |
| 인수 17, `PayloadHashTest`의 `CheckoutPayload의 hash는 지수 표기 amount를 toString 그대로 넣는다` | hash를 `toPlainString()`으로 되돌린 변형에서 기대 hex 불일치로 실패 | 되돌린 뒤 통과. 기대 hex는 Python `hashlib`으로 따로 계산했다 | 위와 같은 회귀 통과 |
| 인수 17, `PayloadHashTest`의 `CheckoutPayload의 hash는 극단적인 지수의 amount도 지수를 펴지 않고 끝난다`, `SyncCommandFacadeIntegrationTest`의 `금액 불일치로 거절된 결제 요청을 재전송하면 같은 거절의 중복이고 올바른 금액의 새 operation은 적용된다`, `SyncControllerTest`의 `amount` 입력 변형 둘 | RED 미관측(characterization). 앞의 것은 변형 상태로 돌리면 테스트 JVM이 죽어 관측하지 않았다 | 통과. `amount`가 null·빈 문자열·숫자가 아닌 문자열이면 400, JSON 숫자는 받아들인다 | 위와 같은 회귀 통과 |
| 인수 13 보강, `OrderTest`의 `verifyCheckoutAmount는 주문의 통화로 대조한다`·`…0원 주문을 0과 대조한다`·`…PAID 주문도 최신 회차의 확정 금액과 대조한다`·`…checkout이 없는 주문을 거절한다` | 통화를 KRW로 고정한 변형에서 `./gradlew test --tests 'com.resilientpos.order.internal.domain.OrderTest'` 실행. 통화 테스트가 실패. 나머지 셋은 변형을 넣지 않았다 | 되돌린 뒤 통과 | 위와 같은 회귀 통과 |
| 인수 18, `OrderTest`의 `productCode가 64자면 통과하고 65자면 PRODUCT_CODE_TOO_LONG으로 거절한다`·`productName이 255자면 통과하고 256자면 PRODUCT_NAME_TOO_LONG으로 거절한다`·`길이 상한은 UTF-16 길이가 아니라 문자 수로 센다` | `./gradlew test --tests 'com.resilientpos.order.internal.domain.OrderTest'`. code 상수만 선언한 최소 선언으로 실행. 셋 다 거절이 나지 않아 실패 | 같은 명령. `OrderLine`에 code point 기준 길이 검사를 넣은 뒤 통과(29개) | `./gradlew test` 통과(350개) |
| 인수 18, `SyncCommandFacadeIntegrationTest`의 `컬럼 길이를 넘는 상품명은 DB 오류가 아니라 거절이고 순번은 확정되며 다음 항목은 적용된다`·`컬럼 길이를 넘는 상품 코드는 …`, `OrderApiIntegrationTest`의 `productName이 256자인 line은 400 PRODUCT_NAME_TOO_LONG이고 t_order에 행이 생기지 않는다` | 검사를 뺀 코드로 `./gradlew test --tests '*OrderApiIntegrationTest' --tests '*SyncCommandFacadeIntegrationTest'` 실행. sync 둘은 `DataIntegrityViolationException`(PostgreSQL 값 길이 초과)으로 실패. 재현한 결함 그대로다. HTTP 테스트도 실패했으나 메시지는 확인하지 않았다. 필터를 좁히지 않아 인수 19의 극단 지수 테스트가 수정 전 hash로 함께 돌았고 테스트 JVM이 죽었다 | 검사를 되살린 뒤 통과 | 위와 같은 회귀 통과. 기존 `OrderCommandFacadeIntegrationTest`의 `트랜잭션 밖에서 부른 save가 line INSERT에서 실패하면 주문 행도 남지 않는다`는 65자 코드로 INSERT 실패를 만들었는데 이제 도메인이 거절하므로 NUL 문자로 바꿨다 |
| 인수 19, `PayloadHashTest`의 `OrderPayload의 hash는 지수 표기 단가를 toString 그대로 넣는다` | `./gradlew test --tests '*PayloadHashTest.OrderPayload의 hash는*'`. 수정 전 코드에서 기대 hex 불일치로 실패(`1E+3`이 `1000`으로 펴졌다) | `toString()`으로 바꾼 뒤 `./gradlew test --tests '*PayloadHashTest'` 통과. 기대 hex는 Python `hashlib`으로 따로 계산했다 | 위와 같은 회귀 통과 |
| 인수 19, `PayloadHashTest`의 `OrderPayload의 hash는 저장 형식이라 고정 입력의 기대 hex가 바뀌지 않는다`(기존)·`OrderPayload의 hash는 소수 단가의 평문 표기를 그대로 넣는다` | RED 미관측(characterization). 평문 표기는 두 메서드의 결과가 같아 수정 전에도 통과한다. 그것이 확인하려는 사실이다 | 수정 뒤에도 통과. 기존 고정 hex는 바꾸지 않았다 | 위와 같은 회귀 통과 |
| 인수 19, `PayloadHashTest`의 `OrderPayload의 hash는 극단적인 지수의 단가도 지수를 펴지 않고 끝난다`, `SyncCommandFacadeIntegrationTest`의 `극단적인 지수의 단가는 지수를 펴지 않고 INVALID_MONEY_AMOUNT 거절이며 다음 항목은 적용된다` | 의도한 RED는 없다. 수정 전 코드로는 테스트 JVM이 죽는다. 위 인수 18의 RED 실행에서 뒤의 테스트가 수정 전 코드로 돌아 실제로 죽었다 | 수정 뒤 통과 | 위와 같은 회귀 통과 |

## 결과
- `./gradlew test` 전체 통과(352개), 하네스 테스트 OK(132개), `python3 scripts/check_pr.py --base origin/main` PASS(`--worktree` 있이·없이).
- `order`에 `OrderCommand.RequestCheckoutByClientOrderId`와 `OrderCommandFacade.requestCheckout` 오버로드, `OrderRepository.findByClientOrderIdForUpdate`를 더했다. 두 `requestCheckout`은 전이·저장·발행을 private 함수 하나로 함께 쓴다.
- `sync`에 `SyncOperationKind`, `OperationPayload`(구현 `OrderPayload`·`CheckoutPayload`), 접수 기록의 `kind`, Facade의 종류 분기, `kind`로 갈리는 요청 DTO를 더했다. V011이 `t_sync_operation.kind`를 더한다.
- 스키마 대조(code-reviewer)에서 불일치는 없었고 누락 6건을 위 대조 기록대로 반영했다.
- 제출 전 리뷰는 Claude(84점)와 Codex(점수 없음, 지적 1건)가 했고 Gemini는 CLI가 없어 실행하지 않았다. Claude가 찾은 HIGH 1건이 결정 4가 됐다. 다른 내용의 기존 주문에 결제 요청이 적용되는 경로다. 그 밖에 판별력 없는 테스트 3곳(`kind` 기본 종류, 잠금 조회, 발행 기록 대조군)을 변형으로 실패를 관측하며 보강했고, 멱등 재요청 테스트, REST Docs 예시, private 함수 이름, KDoc, wiki §4.6의 옛 문장, 롤링 배포 설명을 고쳤다. Codex의 지적(watermark 잠금 대기 초과의 409를 HTTP로 잇는 테스트)은 [sync-intake](2026-09-30-sync-intake.md)가 제외로 둔 기존 빈틈이라 제외 절에 남겼다.
- PR을 연 뒤 병목 점검(spring-backend-specialist)과 금액 대조 코드의 재리뷰(Claude 87점, Codex 점수 없음)를 했다. 둘 다 CRITICAL·HIGH는 없었다. 병목에서는 멱등 checkout의 불필요한 `save`를 없애고 문장 수 테스트를 뒀다. 재리뷰에서는 hash의 `toString()` 선택, 금액 불일치 뒤 복구 경로, 통화·0원·`PAID` 대조, `amount` 입력 변형을 테스트로 고정하고 REST Docs·KDoc·wiki의 부정확한 문장을 고쳤다. 점검이 찾은 기존 결함 둘(길이 초과 입력이 단말 큐를 막는 것, `OrderPayload` hash의 지수 입력)은 HTTP로 재현했다. externalizer가 커넥션을 쥔 채 막힌다는 가설은 소스로 확인해 기각했다.
- 병목 점검이 찾은 기존 결함 둘을 이 PR에서 닫았다(결정 5·6). `OrderLine`이 상품 코드 64자·상품명 255자를 넘으면 `PRODUCT_CODE_TOO_LONG`·`PRODUCT_NAME_TOO_LONG`으로 거절하고, `OrderPayload.hash()`는 단가를 `toString()`으로 넣는다. scale이 0 이상이고 조정 지수가 -6 이상인 단가의 고정 hex는 그대로다. 그 과정에서 NUL 문자가 같은 방식으로 단말 큐를 막는 것을 관측했고 제외 절에 남겼다. 이 두 변경은 Claude가 다시 리뷰했다(86점, CRITICAL·HIGH 없음). hash가 달라지는 값의 조건을 정확히 고치고 spec의 모순 문장 셋을 바로잡았으며, `0.0000000`·`1E+4`의 고정 hex와 이모지 길이의 DB 저장 테스트를 더했다. 리뷰가 찾은 배치 400 계열의 입력은 제외 절에 적었다.
- `order`에 `Order.verifyCheckoutAmount`와 오류 code `CHECKOUT_AMOUNT_MISMATCH`를 더했다. `RequestCheckoutByClientOrderId`가 `expectedAmount`를 받고, 전이한 뒤 저장 전에 대조한다. sync의 `CheckoutPayload`와 요청 항목에 `amount`가 생겼다.
