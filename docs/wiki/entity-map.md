# 엔티티 지도

JPA 엔티티 9개가 각각 무엇을 맡고, 서로를 어떻게 가리키며, 단말과 운영자가 쓰는 흐름에서 어느 순서로 생기고 바뀌는지를 한곳에 모은 문서다.
엔티티 클래스의 KDoc 첫 문단이 같은 내용을 짧게 갖고, 여기가 전체 그림이다. 용어·aggregate 경계는 [도메인 모델](domain-model.md)이 정본이고,
Kotlin으로 엔티티를 어떤 형태로 쓰는지(생성자 `private`, `var … protected set`, `from`/`apply`/`toDomain`)는 볼트 규칙 `domain-modeling-kotlin.md` §5가 정본이다.

세 가지 전제가 모든 엔티티에 공통이다.
- **JPA 연관관계 매핑이 없다.** `@OneToMany`·`@ManyToOne`을 쓰지 않고 다른 행은 ID 컬럼(`order_id` 등)으로만 가리킨다. 조립과 변경 반영은 각 모듈의 persistence adapter가 한다. DB FK도 없다. 무결성은 aggregate를 한 트랜잭션으로 저장하는 소유 모듈이 지킨다.
- **다른 모듈의 테이블을 읽지 않는다.** `payment`·`fulfillment`·`sync`가 가진 `order_id`는 참조일 뿐이고, 값이 필요하면 이벤트 payload나 명령 결과로 받는다.
- **모두 `platform.BaseEntity`를 상속한다.** `created_at`·`updated_at`(애플리케이션 시각)과 `deleted_at`(Hibernate `@SoftDelete`)이 있다. 지금 어느 엔티티도 지우는 명령이 없다. soft delete는 조회만 거르고 unique 제약은 삭제된 행에도 걸린다.

## 1. 한눈에 보기

| 엔티티 | 테이블 | 모듈 | aggregate에서의 자리 | 가리키는 것(ID 컬럼) | 바뀌는가 |
|---|---|---|---|---|---|
| `OrderEntity` | `t_order` | order | 루트 `Order` | — | 상태·line 변경 시 `apply`. 루트만 `@Version` |
| `OrderLineEntity` | `t_order_line` | order | `Order`의 자식(판매 시점 품목) | `order_id` | 루트를 통해 `apply`(line을 바꾸는 명령은 아직 없다) |
| `OrderCheckoutEntity` | `t_order_checkout` | order | `Order`의 자식(회차별 확정 금액) | `order_id` | 넣기만 한다 |
| `PaymentAttemptEntity` | `t_payment_attempt` | payment | 루트 `PaymentAttempt` | `order_id`, `checkout_no`(회차 번호), `retried_from`(같은 표의 `id`) | 결말(`status`·`provider_reference`)만 `applyResult` |
| `FulfillmentEntity` | `t_fulfillment` | fulfillment | 루트 `Fulfillment` | `order_id` | 넣기만 한다 |
| `WatermarkEntity` | `t_sync_watermark` | sync | 루트 `Watermark`(단말당 하나) | `terminal_id`가 PK | `last_acked_sequence`만 `apply`. 행을 잠그고 바꾼다 |
| `SyncOperationEntity` | `t_sync_operation` | sync | `Watermark`와 같은 트랜잭션의 접수 기록 | `terminal_id`, `operation_id`(단말이 만든 값), `order_id` | 넣기만 한다 |
| `TerminalEntity` | `t_terminal` | terminal | 루트 `Terminal` | `store_id`(가리킬 표가 없다) | `status`만 `applyStatus`(`ACTIVE`→`INACTIVE`). 행을 잠그고 바꾼다 |
| `TerminalCredentialEntity` | `t_terminal_credential` | terminal | `Terminal`과 같은 트랜잭션의 credential 이력 | `terminal_id` | `revoked_at`만 `applyRevokedAt`(회전 때 한 번) |

`event_publication`(Spring Modulith 발행 기록)은 우리 엔티티가 아니다. Modulith의 JPA 엔티티가 스키마를 정하고 `t_` 접두어 규칙의 예외다([도메인 모델 §4.3](domain-model.md)).

## 2. 참조 관계

```text
t_sync_watermark.terminal_id ───(같은 단말 ID, FK·조인 없음)─── t_sync_operation.terminal_id
                                                                 t_sync_operation.order_id ──────┐
                                                                                                  ▼
t_order_line.order_id ──────────────────────────────────────────────────────────────────────► t_order.id
t_order_checkout.order_id ──────────────────────────────────────────────────────────────────► t_order.id
t_payment_attempt.order_id ─────────────────────────────────────────────────────────────────► t_order.id
t_payment_attempt.checkout_no ──(회차 번호, ID가 아니다)──► t_order_checkout.checkout_no
t_payment_attempt.retried_from ────────────────────────────────────────────────────────────► t_payment_attempt.id
t_fulfillment.order_id ─────────────────────────────────────────────────────────────────────► t_order.id
t_terminal_credential.terminal_id ─────────────────────────────────────────────────────────► t_terminal.id
```

- 화살표는 "값이 저 행의 ID다"라는 뜻이고 DB가 검사하지 않는다. `t_order.id`를 가리키는 표가 다섯(line·checkout·payment·fulfillment·sync)이지만 서로 조인하는 코드는 없다. 모듈 경계 밖의 값은 이벤트로 건너간다(`OrderPaymentRequested`·`PaymentApproved`·`OrderPaid`).
- `t_payment_attempt`는 checkout 회차를 `checkout_no`(1, 2, …)로 가리킨다. `t_order_checkout.id`가 아니다. 결제 승인을 주문에 대조할 때 `order.markPaid`가 최신 회차 번호·금액과 비교한다.
- `t_terminal`이 생겼지만 `t_order`·`t_sync_*`·`t_payment_attempt`의 `terminal_id`는 그 표와 FK·조인이 없다. 대신 요청을 받을 때 인증된 단말과 대조한다. 주문 생성은 본문의 `terminalId`·`storeId`를, sync는 경로의 `terminalId`와 `CREATE_ORDER` 항목의 `storeId`를 본다. `store` 모듈에는 아직 표가 없어 `store_id`(`t_terminal.store_id` 포함)가 가리키는 행은 없다.

## 3. 엔티티별 역할

### `OrderEntity` — 주문의 머리
- 역할: 주문 하나의 소유 단말·매장·통화·상태(`OPEN`→`CHECKOUT_REQUESTED`→`PAID`)를 든다. 금액 합계는 컬럼으로 두지 않는다(파생 값, line에서 접는다).
- 관계: 자식 `OrderLineEntity`·`OrderCheckoutEntity`가 `order_id`로 가리킨다. 다른 모듈(`payment`·`fulfillment`·`sync`)도 `order_id`로 가리키지만 이 표를 읽지 않는다.
- 방어선: unique `(terminal_id, client_order_id)`가 "같은 단말의 같은 주문 요청은 하나"의 마지막 방어선이다(멱등 키, 삭제 행에도 걸린다). `@Version`은 루트인 이 표에만 있다. 상태 전이는 행 잠금(`for no key update`, 대기 3초) 아래에서 한다.
- 만드는 곳: `OrderCommandFacade.create`(HTTP, `Lock` 블록)와 `createInCallerTransaction`(sync 항목 트랜잭션). 바꾸는 곳: `requestCheckout`·`markPaid`. `requestCheckout`은 `orderId`로 찾는 것(HTTP)과 `(terminalId, clientOrderId)`로 찾는 것(sync 항목 트랜잭션에 합류) 둘이고 둘 다 주문 행을 잠근다.

### `OrderLineEntity` — 판매 시점의 품목 한 줄
- 역할: 주문에 담긴 상품 코드·이름·단가·수량을 **그때 값 그대로** 남긴다. 상품 마스터 표가 없으므로 이 행이 "무엇을 얼마에 팔았는가"의 원본이다.
- 관계: `order_id`로 주문을 가리키고 `line_no`는 정렬용이다. 통화는 `t_order.currency`에서 받아 복원한다.
- 방어선: 없다(PK뿐). line 개수 상한(500)은 `Order`가, 상품 코드 64자·상품명 255자 상한과 NUL 문자는 `OrderLine`이 검사한다(상한은 컬럼 길이를 따라가고, NUL 문자는 코드 0번 글자 U+0000이고 값이 없다는 뜻의 `null`과 다르며, PostgreSQL text에 들어가지 못한다). 그런 값이 DB 오류가 되면 sync에서 그 단말의 배치가 계속 실패하므로 도메인 규칙으로 거절한다. 같은 line을 동시에 고치는 충돌은 루트 `t_order`의 잠금·`@Version`이 막는다.
- 만드는 곳: 주문 생성 때 루트와 함께. line을 고치는 명령은 아직 없다.

### `OrderCheckoutEntity` — N회차 결제 요청의 확정 금액
- 역할: 단말이 결제를 요청한 순간의 합계를 회차(`checkout_no`)별로 고정한다. 그 뒤 line이 바뀌어도(지금은 바뀌지 않지만) 결제는 이 값으로 대조한다. 통화 컬럼은 없고 주문의 통화를 쓴다.
- 관계: `order_id`로 주문을, `checkout_no`로 `t_payment_attempt`가 이 행을 가리킨다.
- 방어선: unique `(order_id, checkout_no)`. 한 주문에 같은 회차는 하나다.
- 만드는 곳: `OrderCommandFacade.requestCheckout`(HTTP와 sync의 `REQUEST_CHECKOUT` 항목). 바꾸지도 지우지도 않는다.

### `PaymentAttemptEntity` — provider에게 보낸 요청 1회
- 역할: 결제 승인(`AUTHORIZATION`)이나 취소·환불(`CANCELLATION`) 요청 한 번과 그 결말(`REQUESTED`→`APPROVED`/`DECLINED`/`UNKNOWN`)을 든다. 금액·통화를 직접 가진다. 결제 정본은 이 표이고 Redis·Kafka는 정본이 아니다.
- 관계: `order_id`·`checkout_no`로 어느 주문의 몇 회차인지, `retried_from`으로 재결제의 원래 시도를 가리킨다. `store_id`·`terminal_id`는 이벤트에서 받아 복사한다.
- 방어선: partial unique `ux_payment_attempt_inflight_authorization`(진행 중인 승인은 회차당 하나), `ux_payment_attempt_first_authorization`(사실로 만든 첫 승인 시도는 회차당 하나, 재결제는 `retried_from`이 있어 술어 밖). 행 잠금 아래에서 결말을 적용한다. `PaymentId`(이 행의 `id`)가 provider 멱등 키다.
- 만드는 곳: `PaymentManagement.createAttempt`(`OrderPaymentRequested` consumer, `Lock` 블록)·`retryAuthorization`. 바꾸는 곳: `authorize`의 결말 적용. 지우지 않는다(감사·reconciliation 근거).

### `FulfillmentEntity` — 결제된 주문 하나를 이행하는 단위
- 역할: "이 주문은 이행 대상이다"라는 사실 한 줄. 상태·품목·매장은 아직 없다(읽는 화면이 없다). 업종(주방·바·픽업)을 가리지 않는다.
- 관계: `order_id`로 주문을 가리킨다.
- 방어선: unique `(order_id)`. `OrderPaid`가 두 번 와도 한 번만 만든다.
- 만드는 곳: `FulfillmentCommandFacade.createFulfillment`(`OrderPaid` consumer). 바꾸지도 지우지도 않는다.

### `WatermarkEntity` — 단말이 어디까지 확정됐는지
- 역할: 단말별로 서버가 확정한 마지막 순번(`last_acked_sequence`). 단말은 응답의 이 값 다음부터 다시 보낸다.
- 관계: `terminal_id`가 PK다. `t_sync_operation`과 같은 `terminal_id`로 묶인다.
- 방어선: 항목 처리마다 이 행을 잠근다(`for no key update`, 대기 3초). 같은 단말의 배치가 동시에 와도 항목 단위로 직렬화된다. 잠금 없이 쓰는 경로가 없어 `@Version`이 없다.
- 만드는 곳: 배치 시작 때 없으면 `SyncCommandFacade`가 0으로 넣는다. 바꾸는 곳: 항목이 `APPLIED`·`REJECTED`로 확정될 때 +1.

### `SyncOperationEntity` — operation 하나의 판정 기록
- 역할: 단말이 만든 operation(`operation_id`)이 어떤 종류였는지(`kind`, `CREATE_ORDER`·`REQUEST_CHECKOUT`), 서버가 어떻게 판정했는지(`APPLIED`·`REJECTED`), 무엇을 받았는지(`payload_hash`), 결과가 무엇인지(`order_id`·`error_code`)를 남긴다. 재전송이 오면 이 행으로 `DUPLICATE`/`CONFLICT`를 가른다. 판정은 `payload_hash`가 하고 `kind`는 업무 코드가 읽지 않는 기록이다.
- 관계: `terminal_id`·`operation_id`가 단말 쪽 키, `sequence`가 확정한 순번, `order_id`가 만들어졌거나 결제를 요청한 주문이다.
- 방어선: unique `(terminal_id, operation_id)`(같은 operation을 두 번 적용하지 않는다), unique `(terminal_id, sequence)`(순번 하나에 기록 하나).
- 만드는 곳: `SyncCommandFacade`의 항목 트랜잭션. 바꾸지도 지우지도 않는다.

### `TerminalEntity` — 매장에 속한 단말 하나
- 역할: 단말의 소속 매장(`store_id`)과 활성 상태(`ACTIVE`/`INACTIVE`)를 든다. 소속 매장의 정본이고 바뀌지 않는다. 단말 코드는 아직 없다.
- 관계: `TerminalCredentialEntity`가 `terminal_id`로 가리킨다. `store_id`는 가리킬 표가 없다.
- 방어선: 회전·비활성화는 이 행을 잠그고 시작한다(`for no key update`, 대기 3초). 잠금 없이 바꾸는 경로가 없어 `@Version`이 없다.
- 만드는 곳: `TerminalManagement.register`(첫 credential과 한 커밋). 바꾸는 곳: `deactivate`(멱등, 되돌리지 않는다). HTTP endpoint는 없다.

### `TerminalCredentialEntity` — 단말을 인증하는 비밀값 한 건
- 역할: 토큰의 SHA-256 hex(`token_hash`)와 폐기 시각(`revoked_at`)을 든다. 평문 토큰은 저장하지 않는다. 폐기된 행도 이력으로 남는다. 만료는 없다.
- 관계: `terminal_id`로 단말을 가리킨다. 인증은 이 표와 `t_terminal`을 한 조회로 읽는다(해시가 같고 폐기되지 않았으며 단말이 `ACTIVE`).
- 방어선: unique `ux_terminal_credential_token_hash`(해시 하나가 두 단말을 가리키지 않는다), partial unique `ux_terminal_credential_active`(`revoked_at is null`인 행은 단말당 하나). 둘 다 삭제 행에도 걸린다.
- 만드는 곳: `register`와 `rotateCredential`. 회전은 옛 행에 `revoked_at`을 찍고 flush한 뒤 새 행을 넣는다. 먼저 내보내지 않으면 Hibernate가 INSERT를 먼저 보내 partial unique에 걸린다.

## 4. UX 흐름별 엔티티 동작

각 단계의 "보이는 것"은 단말 화면이나 운영자가 확인하는 응답이고, "표"는 그 단계에서 생기거나 바뀌는 행이다. 트랜잭션 경계는 굵게 표시한다.
단말의 모든 `/v1/**` 요청은 먼저 `t_terminal_credential`·`t_terminal`을 읽어 토큰을 인증한다(쓰지 않는다). 실패하면 `401 UNAUTHENTICATED`이고 아래 표의 어느 행도 생기지 않는다.

### 4.1 온라인 주문 → 결제 → 이행 (정상 경로)

| 단계 | 단말·운영자에게 보이는 것 | 표 | 트랜잭션·전달 |
|---|---|---|---|
| 1. 단말이 `POST /v1/orders` | 201, `orderId`·`status=OPEN` | `t_order` 1행 + `t_order_line` N행 | **한 커밋**(`Lock` 블록, 같은 `clientOrderId` 동시 요청은 줄 서서 같은 주문을 받는다) |
| 2. 단말이 `POST /v1/orders/{id}/checkout` | 200, `checkoutNo=1`·`amount`·`status=CHECKOUT_REQUESTED` | `t_order.status` 갱신 + `t_order_checkout` 1행 + `event_publication`(`OrderPaymentRequested`) | **한 커밋**(주문 행 잠금). 응답은 커밋 직후 |
| 3. Kafka `order.payment-requested` → `payment` | 단말에는 아직 아무것도 없다 | `t_payment_attempt` 1행(`REQUESTED`, 0원이면 바로 `APPROVED`) + `event_publication`(`AttemptCreated`) | **한 커밋**(`Lock` 블록). 같은 사실이 두 번 와도 기존 시도를 돌려준다 |
| 4. Kafka `payment.attempt-created` → provider 호출 | 없음 | 호출은 **트랜잭션 밖** | provider 응답 뒤 행을 잠그고 결말 적용 |
| 5. 결말 적용 | 없음(결제 조회 API는 아직 없다) | `t_payment_attempt.status`→`APPROVED`, `provider_reference` + `event_publication`(`PaymentApproved`) | **한 커밋** |
| 6. Kafka `payment.approved` → `order` | 단말이 주문을 다시 만들거나 checkout을 다시 부르면 `status=PAID` | `t_order.status`→`PAID` + `event_publication`(`OrderPaid`) | **한 커밋**(주문 행 잠금, 최신 회차·금액 대조) |
| 7. Kafka `order.paid` → `fulfillment` | 없음(보드 화면은 아직 없다) | `t_fulfillment` 1행 | **한 커밋**(unique `(order_id)`) |

단말 관점의 요점: 단말이 직접 보는 표는 `t_order`·`t_order_checkout`뿐이고, 결제·이행은 단말 응답 뒤에 이벤트로 이어진다. 2단계 직후 `status`는 아직 `CHECKOUT_REQUESTED`이고 `PAID`는 나중에 반영된다.

### 4.2 오프라인 주문 동기화

| 단계 | 단말에게 보이는 것 | 표 | 트랜잭션 |
|---|---|---|---|
| 0. 오프라인에서 주문을 만들고 `operationId`·`sequence`를 붙여 로컬 큐에 저장 | 로컬 화면 | 서버에는 아무것도 없다 | — |
| 1. 재연결 뒤 `POST /v1/terminals/{terminalId}/sync`에 배치 | — | 새 단말이면 `t_sync_watermark` 1행(0) | 짧은 커밋 하나 |
| 2. `CREATE_ORDER` 항목: watermark 잠금 → 순번 판정 → `order` 명령 변환 → `createInCallerTransaction` | 항목 `APPLIED`+`orderId` | `t_order`·`t_order_line` + `t_sync_operation`(`APPLIED`) + `t_sync_watermark.last_acked_sequence` +1 | **항목 하나가 한 커밋** |
| 2a. `REQUEST_CHECKOUT` 항목: watermark 잠금 → 순번 판정 → `clientOrderId`로 주문 행 잠금 → 전이 → 단말 합계 대조 → 저장 | 항목 `APPLIED`+`orderId` | `t_order.status`→`CHECKOUT_REQUESTED` + `t_order_checkout` 1행 + `event_publication`(`OrderPaymentRequested`) + `t_sync_operation`(`APPLIED`) + watermark +1. 이후는 4.1의 3단계부터 | **항목 하나가 한 커밋**. 행 잠금은 watermark 다음에 주문 순서다 |
| 2'. 항목이 도메인 규칙에 걸림(빈 상품명, 상품 코드·상품명의 길이 초과와 NUL 문자, 모르는 통화 코드, 정수가 아니거나 Int 범위 밖인 수량, 금액 자릿수, 같은 `clientOrderId`에 다른 내용, 결제 요청의 주문 없음, 단말 합계와 확정 금액 불일치, 인증된 단말과 다른 매장의 주문 생성 `STORE_MISMATCH`) | 항목 `REJECTED`+`errorCode` | 첫 트랜잭션은 롤백(그 항목이 쓴 행 없음). 두 번째 트랜잭션이 `t_sync_operation`(`REJECTED`)과 watermark +1 | 두 커밋. 단말은 그 항목을 버린다 |
| 2''. 이미 확정된 순번 | 같은 내용이면 `DUPLICATE`(기록된 `orderId`), 다른 내용·다른 순번·모르는 operation이면 `CONFLICT` | 아무것도 쓰지 않는다 | 읽기만 |
| 2'''. 순번이 비었다 | 그 항목부터 `GAP` | 아무것도 쓰지 않는다 | 첫 GAP 뒤는 트랜잭션도 열지 않는다 |
| 3. 응답의 `lastAckedSequence` | 단말은 그 다음 순번부터 다시 보내고 `APPLIED`·`DUPLICATE`·`REJECTED`는 큐에서 지운다 | — | — |
| 4. 이후 | 결제 요청을 배치에 싣지 않았으면 단말은 받은 `orderId`로 4.1의 2단계(checkout)부터 이어 간다 | 4.1과 같다 | — |

배치 전체가 409·500이면(잠금 대기 초과, DB 장애, unique 위반) 이미 커밋된 항목은 남고 재전송 때 `DUPLICATE`로 돌아온다.

### 4.3 결제 거절 뒤 재결제

| 단계 | 보이는 것 | 표 |
|---|---|---|
| 1. provider가 거절 | 없음(단말에 알릴 API가 아직 없다) | `t_payment_attempt.status`→`DECLINED`. 주문은 `CHECKOUT_REQUESTED`에 머문다 |
| 2. `PaymentManagement.retryAuthorization(paymentId)`(HTTP endpoint 없음, Facade만) | — | 새 `t_payment_attempt` 1행(`REQUESTED`, `retried_from`=거절된 시도). 거절된 행을 잠그고 최신 시도를 확인해 겹친 요청도 하나로 수렴 |
| 3. 이후 | 4.1의 4단계부터 | 같은 회차·같은 금액으로 provider를 다시 부른다 |

### 4.4 결제 미확인(`UNKNOWN`)

provider 응답이 끊기면 `t_payment_attempt.status`가 `UNKNOWN`으로 남는다. 실패가 아니다. 같은 회차에 새 승인 요청을 넣지 않고(partial unique가 막는다) provider 조회와 reconciliation으로 수렴시켜야 하는데, 그 경로(`reconciliation` 모듈, `ReconciliationCase`)는 아직 없다. 운영자는 `t_payment_attempt`에서 `status = 'UNKNOWN'`인 행을 직접 본다.

### 4.5 같은 요청이 다시 올 때(멱등)

| 다시 오는 것 | 판정하는 표·제약 | 결과 |
|---|---|---|
| 같은 `(terminalId, clientOrderId)` 주문 생성 | `t_order` unique + `Lock` | 201, 같은 `orderId`. 내용이 다르면 `409 IDEMPOTENCY_CONFLICT` |
| 같은 주문의 checkout | `t_order` 행 잠금 + 상태 | 200, 같은 회차·금액 |
| 같은 `OrderPaymentRequested` 재배달 | `t_payment_attempt` 조회 + partial unique | 기존 시도를 돌려주고 이벤트를 다시 내지 않는다 |
| 같은 `PaymentApproved`·`OrderPaid` 재배달 | 주문 상태 / `t_fulfillment` unique | 아무것도 하지 않는다 |
| 같은 sync operation | `t_sync_operation` unique + `payload_hash` | `DUPLICATE`(같은 결과) 또는 `CONFLICT` |

### 4.6 운영자가 무엇을 볼 것인가

| 증상 | 먼저 볼 표 | 그다음 |
|---|---|---|
| 결제는 승인됐는데 주문이 `CHECKOUT_REQUESTED`다 | `t_payment_attempt`(`APPROVED`)와 `t_order.status` | Kafka `payment.approved.DLT`와 `DLT로 보냄` 로그(회차·금액 불일치는 `PAYMENT_APPROVAL_MISMATCH`) |
| 주문은 `PAID`인데 fulfillment가 없다 | `t_fulfillment`에 `order_id`가 없는지 | `order.paid.DLT` |
| 시도가 `REQUESTED`·`UNKNOWN`에 오래 머문다 | `t_payment_attempt.updated_at` | provider 원장. 재호출은 `PaymentId` 멱등성에 기댄다 |
| 단말이 같은 배치를 계속 보낸다 | `t_sync_watermark.last_acked_sequence`와 `t_sync_operation`의 마지막 `sequence` | `sync 충돌` WARN 로그(`terminalId`·`operationId`·`sequence`). `CONFLICT`는 단말 상태가 서버와 갈라진 것이라 사람이 본다 |
| 이벤트가 나가지 않았다 | `event_publication`에서 `completion_date`가 빈 행 | 매분 도는 재전송과 `event.publication.incomplete` gauge |

## 5. 아직 엔티티가 없는 것

`Store`(정책·통화), `ReconciliationCase`(불일치 근거·조치), `ReceiptDelivery`(영수증 전달)는 [도메인 모델 §2](domain-model.md)에 자리만 있고 표와 엔티티는 없다. `store_id`는 인증된 단말의 매장과 대조할 뿐 매장 표로 검증하지 않는다([현재 상태](current-state.md)).

## 관련 문서
- [도메인 모델](domain-model.md) — aggregate 경계(§2), 용어집(§3), 스키마 변경 검토(§4.7)
- [현재 상태](current-state.md) — 모듈별 구현 상태와 알려진 빈틈
- 볼트 규칙 `domain-modeling-kotlin.md` §5 — 엔티티 작성 규칙(`.claude/rules`로 복사된다)
- 마이그레이션 `src/main/resources/db/migration/V002`·`V003`·`V005`~`V008`·`V010`~`V012` — 각 표의 DDL과 주석
