# PR spec: 결제된 주문에서 주방 ticket을 한 번만 만든다

## 목적
주문이 `PAID`가 돼도 그 사실을 받는 쪽이 없어 `OrderEvent.Paid`는 기록도 전달도 되지 않고 사라진다. 원문 [청사진 §3.4](../references/05_Domain_Development_Blueprint.md)의 `OrderPaid → [CreateKitchenTicketFromPaidOrder]` 자리다. [order-mark-paid](2026-09-23-order-mark-paid.md)가 "받을 쪽이 없어 `order`에 둔다"고 미룬 소비자를 만든다.

## 범위
소유 모듈은 `fulfillment`다. 이 모듈의 첫 코드다.

**`OrderEvent.Paid`를 `contract.OrderPaid`로 옮긴다.** 경계를 넘는 이벤트는 `contract`가 소유한다(도메인 모델 §4.4). 필드는 그대로이고 `orderId`만 raw `UUID`가 된다. `Order.markPaid`의 `Transition`은 이미 `DomainEvent`를 이벤트 타입으로 쓰므로 시그니처가 바뀌지 않는다. `OrderEvent`에는 `Created`만 남는다.

**`fulfillment`가 `OrderPaid`를 받아 `KitchenTicket`을 만든다.** `OrderPaidListener`(`@ApplicationModuleListener(propagation = NOT_SUPPORTED)`)가 `FulfillmentCommandFacade.createKitchenTicket(FulfillmentCommand.CreateKitchenTicket)`을 부른다. 리스너는 옮기기만 하고 판단은 Facade가 한다. 트랜잭션을 끄는 이유는 `PaymentApprovedListener`와 같다 — Facade가 반환하면 커밋 뒤라 로그가 참이다.

**원본 주문당 한 번만 만든다.** Facade는 그 주문의 ticket이 있으면 그것을 돌려주고 없으면 만든다. 재배달이나 resubmit은 기존 ticket을 돌려받는다. 읽은 뒤 넣기 전의 창은 `t_kitchen_ticket`의 unique `(order_id)`가 닫는다. 진 쪽은 커밋에서 unique 위반으로 실패한다.

**ticket은 `id`와 `orderId`만 가진다.** 상태·매장·품목 snapshot은 그것을 읽는 명령이나 조회가 없어서 두지 않는다(아래 스키마 변경 검토).

**`KitchenTicketCreated`는 내지 않는다.** 받는 쪽이 없으면 기록도 전달도 되지 않는다(도메인 모델 §4.3). KDS 알림이나 외부 relay처럼 소비자가 생기는 PR이 더한다. 그래서 `KitchenTicket.create`는 `Transition`이 아니라 aggregate를 돌려준다.

계약: `OrderPaid`에 받는 리스너가 생겨 이 이벤트가 처음으로 `event_publication`에 기록된다. `event_type`은 `com.resilientpos.contract.OrderPaid`이고 `listener_id`는 `com.resilientpos.fulfillment.internal.application.OrderPaidListener.on(com.resilientpos.contract.OrderPaid)`다. 옛 이름 `OrderEvent$Paid`는 받는 쪽이 없어 기록된 행이 없다. 새 `DomainException` code는 없다. API로 나가는 것도 없다.

복구: 리스너가 실패하면(DB 장애, 잠금 대기 초과, 동시 배달의 unique 위반) 발행 기록이 `FAILED`로 남고 **주문은 `PAID`인데 ticket이 없다.** 자동 재시도는 없다. 찾는 조건은 `listener_id like '%OrderPaidListener%' and status = 'FAILED'`이고 `orderId`는 `serialized_event`에 있다. 다시 보내는 길은 `FailedEventPublications.resubmit`이고, 멱등이라 몇 번 보내도 ticket은 하나다. 운영 진입점은 아직 없다.

관측성: ticket을 만들거나 기존 것을 돌려준 뒤 로그 한 줄(`orderId`, `ticketId`).

## 제외
- 영수증 전달(`ReceiptDelivery`). 전달 채널과 외부 호출·재시도 정책을 따로 정해야 한다.
- `KitchenTicketCreated`와 그 소비자.
- ticket 상태와 `CompleteKitchenTicket`, 주방 보드 조회(`FindFulfillmentBoard`)와 거기 필요한 `store_id`·품목 snapshot.
- `ticket_type`. ticket 종류가 하나뿐이다(아래 스키마 변경 검토).
- `OrderAccepted`로 ticket을 만드는 정책. 청사진이 "정책상 허용된" 경우로 남겨 두었고 그 정책이 없다.
- 실패한 발행 기록을 다시 보내는 운영 진입점.

## 관련 문서
정본은 [도메인 모델](../wiki/domain-model.md) §2·§4.3·§4.4다. 선행은 [order-mark-paid](2026-09-23-order-mark-paid.md), 멱등 생성의 선례는 [payment-attempt-from-order-event](2026-09-22-payment-attempt-from-order-event.md)다.

## 변경 경로
- src/main/kotlin/com/resilientpos/contract/OrderPaid.kt
- src/main/kotlin/com/resilientpos/order/**
- src/main/kotlin/com/resilientpos/fulfillment/**
- src/main/resources/db/migration/V007__fulfillment_kitchen_ticket.sql
- src/test/kotlin/com/resilientpos/order/**
- src/test/kotlin/com/resilientpos/fulfillment/**
- src/test/kotlin/com/resilientpos/ModuleStructureTest.kt
- src/test/kotlin/com/resilientpos/SchemaMigrationTest.kt
- docs/wiki/domain-model.md
- docs/spec/2026-09-24-fulfillment.md

## 인수 조건
1. `Order.markPaid`가 `contract.OrderPaid`를 내고, 그 정규 이름이 `ModuleStructureTest`에 리터럴로 고정된다.
2. `OrderPaid`를 받으면 그 주문의 `KitchenTicket`이 하나 생긴다.
3. 같은 주문의 `OrderPaid`가 다시 와도 ticket은 하나이고 Facade는 처음 ticket을 돌려준다.
4. 같은 `order_id`로 행을 두 번 넣으면 DB가 거절한다. 실제 PostgreSQL에서 확인한다.
5. checkout부터 결제 승인, 주문 `PAID`, ticket 생성까지 한 흐름이 통합 테스트로 돈다.
6. 기존 테스트가 깨지지 않는다.

## 스키마 변경 검토
새 테이블 `t_kitchen_ticket(id uuid primary key, order_id uuid not null, created_at, updated_at, deleted_at)`과 unique `(order_id)`다.

- 질문 1(도출): ticket이 있다는 사실은 다른 어디에도 없다. `t_order.status = 'PAID'`는 "만들어야 한다"이지 "만들었다"가 아니고, `fulfillment`는 `order`의 테이블을 읽지 않는다.
- 질문 2(읽는 코드): `order_id`는 멱등 조회가 읽는다. `status`·`store_id`·품목 snapshot은 읽는 코드가 없어 넣지 않는다. `ticket_type`도 같다 — 청사진은 `(orderId, ticketType)`을 키로 들었지만 종류가 하나뿐이다.
- 질문 3(나중에 채우기): `status`는 상수 기본값을 가진 컬럼 추가로 기존 행을 채운다. `ticket_type`도 기본값 `'KITCHEN'`으로 더하고 unique를 `(order_id, ticket_type)`으로 바꾼다. 행 수가 주문 수를 따라가므로 그때는 §4.7 질문 7대로 `CREATE UNIQUE INDEX CONCURRENTLY`로 나눈다. `store_id`·품목은 보드 조회를 만드는 PR이 `OrderPaid`에 기본값 있는 필드로 더하고, 기존 행은 같은 DB의 `t_order`·`t_order_line`에서 마이그레이션 SQL로 채울 수 있다. 다시 볼 조건을 마이그레이션 주석에 남긴다.
- 질문 4(마지막 방어선): unique `(order_id)`가 "주문당 한 번"의 마지막 방어선이다. Event Publication Registry의 완료 표시는 리스너 커밋과 다른 트랜잭션이라 그 사이에 죽으면 재배달이 온다([payment-attempt-persistence](2026-09-22-payment-attempt-persistence.md)). 멱등 키이므로 soft delete된 행에도 걸리는 **전체 unique**로 둔다. ticket을 지우는 명령이 생기면 멱등 조회가 삭제된 행도 보게 해야 한다. 지금은 지우는 코드가 없다.
- 질문 5(정본): `order_id`는 `t_order.id`를 가리키는 참조일 뿐 사본이 아니다. DB FK는 두지 않는다 — 별개 컨텍스트이고 `t_payment_attempt`가 같은 선례다.
- 질문 6(check-then-act): "그 주문의 ticket이 있는가"를 읽고 없으면 넣는다. 창은 unique가 닫는다. `@Version`은 두지 않는다. 행을 바꾸는 명령이 없다.
- 질문 7(락): 빈 테이블 `create table`이라 잠글 대상이 없다.
- 질문 8(이전 버전): 새 테이블이라 이전 버전은 읽지도 쓰지도 않는다. 이전 버전이 낸 `OrderEvent$Paid`가 남아 있을 수 없다 — 받는 쪽이 없어 기록되지 않았다.
- 질문 9(payload): 테이블 컬럼이 이벤트에 실리지 않는다. `OrderPaid`는 이 PR에서 처음 저장 형식이 된다.
- 대조 기록: 미작성. PR 제출 전에 구현자가 아닌 리뷰어가 채운다.

## 검증
- `./gradlew test` — 전부 통과. Docker가 필요하다.
- `python3 scripts/check_pr.py --base origin/main --worktree` — PASS.

## Wiki 영향
- [도메인 모델](../wiki/domain-model.md) — §4.7 사례에 `t_kitchen_ticket`의 unique `(order_id)`와 `ticket_type`을 뺀 근거를 더한다.
- [현재 상태](../wiki/current-state.md)는 볼트에서 갱신한다(`OrderEvent.Paid` → `contract.OrderPaid`, 기록되는 이벤트 목록, `fulfillment`가 비어 있지 않음, 테스트 수).
- 요구사항 변화: 없다. 원문이 정한 흐름이 이어진다.

## 분할 계획
약 18개 파일 / 500줄 예상. 한도 안이다.

## TDD 검증 기록
| 동작 | RED | GREEN |
|---|---|---|
| `markPaid`가 `contract.OrderPaid`를 낸다 | 미실행 | 미실행 |
| `OrderPaid`를 받으면 ticket이 하나 생긴다 | 미실행 | 미실행 |
| 재배달은 기존 ticket을 돌려준다 | 미실행 | 미실행 |
| 같은 `order_id` 두 번째 INSERT를 DB가 거절한다 | 미실행 | 미실행 |
| checkout부터 ticket까지 | 미실행 | 미실행 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
