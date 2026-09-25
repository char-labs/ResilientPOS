# PR spec: 결제된 주문에서 fulfillment를 한 번만 만든다

## 목적
주문이 `PAID`가 돼도 그 사실을 받는 쪽이 없어 `OrderEvent.Paid`는 기록도 전달도 되지 않고 사라진다. 원문 [청사진 §3.4](../references/05_Domain_Development_Blueprint.md)의 `OrderPaid → [CreateKitchenTicketFromPaidOrder]` 자리다. [order-mark-paid](2026-09-23-order-mark-paid.md)가 "받을 쪽이 없어 `order`에 둔다"고 미룬 소비자를 만든다.

## 범위
소유 모듈은 `fulfillment`다. 이 모듈의 첫 코드다.

**`OrderEvent.Paid`를 `contract.OrderPaid`로 옮긴다.** 경계를 넘는 이벤트는 `contract`가 소유한다(도메인 모델 §4.4). `orderId`는 raw `UUID`가 되고 **`storeId`·`terminalId`를 더한다.** 매장별 보드는 매장별로 조회하고 영수증 전달은 주문을 낸 단말로 가므로 두 값이 다음 소비자에 필요하다. 이벤트 필드 추가는 기본값이 있어야 하위 호환인데(도메인 모델 §4.3) 두 값에는 의미 있는 기본값이 없어서, 나중에 더하면 그 전에 저장된 기록을 위해 영구히 nullable이 된다. `OrderPaid`가 처음 저장 형식이 되는 이 PR이 가장 싼 시점이다. 같은 계약의 `OrderPaymentRequested`·`PaymentApproved`도 두 값을 싣는다. `t_fulfillment`에는 컬럼을 더하지 않는다 — 읽는 코드가 없다. `Order.markPaid`의 `Transition`은 이미 `DomainEvent`를 이벤트 타입으로 쓰므로 시그니처가 바뀌지 않는다. `OrderEvent`에는 `Created`만 남는다.

**`fulfillment`가 `OrderPaid`를 받아 `Fulfillment`를 만든다.** `OrderPaidListener`(`@ApplicationModuleListener(propagation = NOT_SUPPORTED)`)가 `FulfillmentCommandFacade.createFulfillment(FulfillmentCommand.Create)`를 부른다. 리스너는 옮기기만 하고 판단은 Facade가 한다. Facade·커맨드·`FulfillmentId`는 모듈 루트가 아니라 `internal`에 둔다. `fulfillment`의 공개 계약은 소비 이벤트뿐이고(도메인 모델 §2), Facade는 주문이 `PAID`인지 묻지 않고 호출자를 믿으므로 루트에 두면 다른 모듈이 결제 안 된 주문의 fulfillment를 만들 수 있다. 트랜잭션을 끄는 이유는 `PaymentApprovedListener`와 같다 — Facade가 반환하면 커밋 뒤라 로그가 참이다.

**aggregate 이름은 `Fulfillment`다.** 원문 청사진은 `KitchenTicket`이지만 kitchen은 F&B 업종을, ticket은 KDS·프린터의 표시 매체(화면 카드, 종이 주문표)를 가리킨다. 백엔드 모델에서 같은 개념은 Square Orders API의 `Fulfillment`(주문 하나의 이행 단위, `type` `PICKUP`·`SHIPMENT`·`DELIVERY`·`IN_STORE`, `state` `PROPOSED`→`PREPARED`→`COMPLETED`)이고, 주문당 하나라는 우리 불변식과 맞는 것도 이쪽이다. Shopify·Salesforce의 `FulfillmentOrder`는 위치별 품목 묶음이라 한 주문에 여럿이고 `Order`와 헷갈려 쓰지 않는다. 모듈과 aggregate 이름이 같은 것은 `order`의 `Order`와 같은 모양이다. 업종에 따른 구분은 이름이 아니라 나중의 이행 방식(`type`)이 맡는다. 테이블·코드가 배포되기 전이라 지금 바꾸는 비용이 가장 작고, 저장 형식인 `listener_id`는 `OrderPaidListener`라 영향이 없다.

**원본 주문당 한 번만 만든다.** Facade는 그 주문의 fulfillment가 있으면 그것을 돌려주고 없으면 만든다. 재배달이나 resubmit은 기존 fulfillment를 돌려받는다. 읽은 뒤 넣기 전의 창은 `t_fulfillment`의 unique `(order_id)`가 닫는다. 진 쪽은 커밋에서 unique 위반으로 실패한다.

**fulfillment는 `id`와 `orderId`만 가진다.** 상태·매장·품목 snapshot은 그것을 읽는 명령이나 조회가 없어서 두지 않는다(아래 스키마 변경 검토).

**`FulfillmentCreated`는 내지 않는다.** 받는 쪽이 없으면 기록도 전달도 되지 않는다(도메인 모델 §4.3). KDS 알림이나 외부 relay처럼 소비자가 생기는 PR이 더한다. 그래서 `Fulfillment.create`는 `Transition`이 아니라 aggregate를 돌려준다.

계약: `OrderPaid`에 받는 리스너가 생겨 이 이벤트가 처음으로 `event_publication`에 기록된다. `event_type`은 `com.resilientpos.contract.OrderPaid`이고 `listener_id`는 `com.resilientpos.fulfillment.internal.application.OrderPaidListener.on(com.resilientpos.contract.OrderPaid)`다. 옛 이름 `OrderEvent$Paid`는 받는 쪽이 없어 기록된 행이 없다. 새 `DomainException` code는 없다. API로 나가는 것도 없다.

복구: 리스너가 DB 장애·커넥션 대기 초과로 실패하면 발행 기록이 `FAILED`로 남고 **주문은 `PAID`인데 fulfillment가 없다.** 자동 재시도는 없다. 동시 배달에서 unique 위반으로 진 쪽은 다르다 — 이긴 쪽이 이미 fulfillment를 커밋했으므로 **fulfillment는 있는데 `FAILED` 기록이 남는다.** 그래서 아래 조건으로 찾은 행 중에는 fulfillment가 이미 있는 것이 섞일 수 있고, resubmit이 멱등이라 해가 없다. 찾는 조건은 `listener_id like '%OrderPaidListener%' and status = 'FAILED'`이고 `orderId`는 `serialized_event`에 있다. 다시 보내는 길은 `FailedEventPublications.resubmit`이고, 멱등이라 몇 번 보내도 fulfillment는 하나다. resubmit은 저장된 JSON을 복원하므로 `OrderPaid`의 복원 왕복을 테스트로 고정한다. 운영 진입점은 아직 없다.

관측성: fulfillment를 만들거나 기존 것을 돌려준 뒤 로그 한 줄(`orderId`, `fulfillmentId`, `created`). `created=false`가 재배달·resubmit의 흔적이다.

## 제외
- 영수증 전달(`ReceiptDelivery`). 전달 채널과 외부 호출·재시도 정책을 따로 정해야 한다.
- `FulfillmentCreated`와 그 소비자.
- fulfillment 상태와 `CompleteFulfillment`, 매장별 보드 조회(`FindFulfillmentBoard`)와 거기 필요한 `store_id`·품목 snapshot.
- `type`. 이행 방식이 하나뿐이다(아래 스키마 변경 검토).
- 매장 유형(`StoreType`)과 그에 따른 이행 방식 구분. `store` 모듈에 `StoreId`만 있고 매장 유형을 읽는 코드가 없다. 종류가 둘 이상 필요해지면 `store`가 매장 유형을 소유하고, `OrderPaid` payload나 조회로 fulfillment에 건넨다. 이행 방식(`type`)을 매장 유형으로 정해야 할 때의 이야기다.
- `OrderAccepted`로 fulfillment를 만드는 정책. 청사진이 "정책상 허용된" 경우로 남겨 두었고 그 정책이 없다.
- 실패한 발행 기록을 다시 보내는 운영 진입점.
- 이 PR이 키우지만 이 PR 밖에서 풀 병목 셋. 각각 다시 볼 조건을 남긴다.
  - 리스너 공유 실행기(코어 8, 큐 무제한). 주문 1건이 리스너 넷을 차례로 거치고 provider 호출도 같은 풀을 쓴다. 실제 provider가 붙어 지연이 생기면 fulfillment가 승인 호출 뒤에 줄을 선다. 풀 크기를 정하는 그 PR이 provider 호출용 실행기 분리를 함께 본다.
  - `event_publication` 무한 증가. `completion-mode: update`에 정리 작업이 없어 주문 1건당 4행이 쌓이고, 이 PR이 1행을 더한다. 보존 정책(`archive` 모드나 완료 기록 purge)은 운영 데이터가 생기기 전에 정한다.
  - 여러 인스턴스에서의 경합. 재발행이나 자동 resubmit을 켜면 같은 기록을 여러 인스턴스가 보내 진 쪽이 `FAILED`를 남긴다. 그 PR이 Facade를 `insert ... on conflict (order_id) do nothing` 뒤 조회로 바꾼다(코드에 `ponytail:` 표식).

## 관련 문서
정본은 [도메인 모델](../wiki/domain-model.md) §2·§4.3·§4.4다. 선행은 [order-mark-paid](2026-09-23-order-mark-paid.md), 멱등 생성의 선례는 [payment-attempt-from-order-event](2026-09-22-payment-attempt-from-order-event.md)다.

## 변경 경로
- src/main/kotlin/com/resilientpos/contract/OrderPaid.kt
- src/main/kotlin/com/resilientpos/order/**
- src/main/kotlin/com/resilientpos/fulfillment/**
- src/main/java/com/resilientpos/fulfillment/package-info.java
- src/main/resources/db/migration/V007__fulfillment.sql
- src/test/kotlin/com/resilientpos/order/**
- src/test/kotlin/com/resilientpos/fulfillment/**
- src/test/kotlin/com/resilientpos/ModuleStructureTest.kt
- src/test/kotlin/com/resilientpos/SchemaMigrationTest.kt
- docs/wiki/domain-model.md
- docs/spec/2026-09-24-fulfillment.md

## 인수 조건
1. `Order.markPaid`가 주문의 `storeId`·`terminalId`를 실은 `contract.OrderPaid`를 내고, 그 정규 이름이 `ModuleStructureTest`에 리터럴로 고정된다.
2. `OrderPaid`를 받으면 그 주문의 `Fulfillment`가 하나 생긴다.
3. 같은 주문의 `OrderPaid`가 다시 와도 fulfillment는 하나이고 Facade는 처음 fulfillment를 돌려준다.
4. 같은 `order_id`로 행을 두 번 넣으면 DB가 거절한다. 실제 PostgreSQL에서 확인한다.
5. checkout부터 결제 승인, 주문 `PAID`, fulfillment 생성까지 한 흐름이 통합 테스트로 돈다.
6. 기존 테스트가 깨지지 않는다.

## 스키마 변경 검토
새 테이블 `t_fulfillment(id uuid primary key, order_id uuid not null, created_at timestamptz not null, updated_at timestamptz not null, deleted_at timestamptz)`과 `constraint ux_fulfillment_order unique (order_id)`다. 매핑은 `t_payment_attempt`와 같은 구성이다(`UUID`↔`uuid`, `orderId`는 기본 naming으로 `order_id`, `Instant`↔`timestamptz`, `deleted_at`은 필드가 아니라 `BaseEntity`의 `@SoftDelete`). `ddl-auto: validate`가 통합 테스트의 컨텍스트 기동에서 대조한다.

- 질문 1(도출): fulfillment가 있다는 사실은 다른 어디에도 없다. `t_order.status = 'PAID'`는 "만들어야 한다"이지 "만들었다"가 아니고, `fulfillment`의 런타임 코드는 `order`의 테이블을 읽지 않는다.
- 질문 2(읽는 코드): `order_id`는 멱등 조회가 읽는다. `status`·`store_id`·품목 snapshot은 읽는 코드가 없어 넣지 않는다. `type`도 같다 — 청사진은 `(orderId, ticketType)`을 키로 들었지만 종류가 하나뿐이다.
- 질문 3(나중에 채우기): `status`는 상수 기본값을 가진 컬럼 추가로 기존 행을 채운다. `type`도 지금의 유일한 종류를 뜻하는 상수 기본값으로 더하고 unique를 `(order_id, type)`으로 바꾼다. 행 수가 주문 수를 따라가므로 그때는 §4.7 질문 7대로 `CREATE UNIQUE INDEX CONCURRENTLY`로 나눈다. `store_id` 컬럼은 보드 조회를 만드는 PR이 더한다. 값은 이미 `OrderPaid.storeId`에 실려 오므로 새 행은 이벤트에서, 기존 행은 같은 DB의 `t_order`에서 채운다. 품목은 그 PR이 `OrderPaid`에 nullable(미상) 필드로 더하고 기존 fulfillment 행은 `t_order_line`에서 채운다(질문 9). 일회성 backfill이라 질문 1의 모듈 경계에 대한 예외다. unique를 바꿀 때는 새 unique 인덱스를 `CONCURRENTLY`로 만드는 문장만 담은 마이그레이션을 따로 두고 `spring.flyway.postgresql.transactional-lock=false`를 함께 설정한다. 기존 제약을 지우는 문장은 짧은 `ACCESS EXCLUSIVE`를 잡으므로 `lock_timeout`을 둔다. 그 값은 상수 기본값이라 컬럼 추가가 재작성 없는 메타데이터 변경이다. 다시 볼 조건을 마이그레이션 주석에 남긴다.
- 질문 4(마지막 방어선): unique `(order_id)`가 "주문당 한 번"의 마지막 방어선이다. Event Publication Registry의 완료 표시는 리스너 커밋과 다른 트랜잭션이라 그 사이에 죽으면 재배달이 온다([payment-attempt-persistence](2026-09-22-payment-attempt-persistence.md)). 멱등 키이므로 soft delete된 행에도 걸리는 **전체 unique**로 둔다. fulfillment를 지우는 명령이 생기면 멱등 조회가 삭제된 행도 보게 해야 한다. 그러지 않으면 조회는 `@SoftDelete`가 붙이는 `deleted_at is null`로 삭제 행을 못 보고 INSERT는 unique에 걸려, 그 주문은 resubmit해도 영구히 `FAILED`다. 지금은 지우는 코드가 없다 — `FulfillmentJpaRepository`가 좁힌 `Repository`라 delete 메서드가 없다.
- 질문 5(정본): `order_id`는 `t_order.id`를 가리키는 참조일 뿐 사본이 아니다. DB FK는 두지 않는다 — 별개 컨텍스트이고 `t_payment_attempt`가 같은 선례다.
- 질문 6(check-then-act): "그 주문의 fulfillment가 있는가"를 읽고 없으면 넣는다. 창은 unique가 닫는다. 이 창이 실제로 열리는 길은 처리 중인 발행 기록을 resubmit하는 경우 하나다 — `markPaid`는 `PAID` 뒤에 `OrderPaid`를 다시 내지 않고, 재시작 시 재발행은 꺼져 있다. `@Version`은 두지 않는다. 행을 바꾸는 명령이 없다.
- 질문 7(락): 빈 테이블 `create table`이라 잠글 대상이 없다.
- 질문 8(이전 버전): 새 테이블이라 이전 버전은 읽지도 쓰지도 않는다. 이전 버전이 낸 `OrderEvent$Paid`가 남아 있을 수 없다 — 받는 쪽이 없어 기록되지 않았다. 롤링 배포라면 이전 버전 인스턴스가 `PAID`로 만든 주문은 리스너도 흔적도 없이 fulfillment가 빠지지만, 배포된 환경이 없어 해당하지 않는다.
- 질문 9(payload): `order_id`의 값은 `OrderPaid.orderId`에서 오고, `OrderPaid`는 이 PR에서 처음 `serialized_event`로 저장된다. `FAILED` 기록을 resubmit하려면 옛 payload가 복원돼야 하므로 필드 추가는 기본값이 있어야 하고 이름 변경·삭제는 새 이벤트 타입으로 한다(도메인 모델 §4.3). `storeId`·`terminalId`는 기본값이 없어 나중에 더하면 영구 nullable이 되므로 이 PR에서 싣는다. 품목은 빈 목록이 "품목 없음"과 구분되지 않아 기본값이 될 수 없다. 그래서 품목을 더하는 PR은 nullable(미상)로 더하고, 그 전에 저장된 미완료·`FAILED` `OrderPaid` 기록을 배포 전에 비우는 것을 배포 조건으로 둔다. 비우지 않으면 옛 payload의 resubmit이 품목 없는 fulfillment를 만든다. 캐시되는 DTO는 없다.
- 대조 기록: code-reviewer, 2026-09-24. V007 DDL·`KitchenTicketEntity`·`BaseEntity`·JPA repository·persistence adapter·Facade·리스너·`Order.markPaid`와 대조했다. 불일치 3건(동시 배달의 unique 위반은 ticket이 있는 채로 `FAILED`만 남긴다, 질문 9의 payload 하위 호환 규칙 누락, 질문 1과 3 사이 backfill 경계 예외 누락)과 누락 6건(컬럼 타입, validate 통과 근거, soft delete 행의 영구 `FAILED`, unique 교체 절차, 롤링 배포 창, `OrderPaid` 복원 검증)을 받아 spec을 고치고 복원 왕복 테스트를 더했다. 코드와 DDL은 바뀌지 않았다. 이어서 같은 날 `contract.OrderPaid`의 `storeId`·`terminalId` 추가를 `OrderPaid.kt`·`Order.markPaid`·`OrderMarkPaidTest`·복원 왕복 테스트·V007 주석과 대조했다. V007 DDL은 이전 대조와 같았다. 불일치 1건(품목의 빈 목록 기본값이 `storeId`를 지금 싣는 근거와 어긋나 옛 payload를 resubmit하면 품목 없는 ticket이 생긴다)을 받아 질문 3·9와 V007 주석을 고쳤다. 이어서 2026-09-25 `KitchenTicket`→`FulfillmentTicket` 이름 변경을 V007 DDL(`t_fulfillment_ticket`, `ux_fulfillment_ticket_order`)·`FulfillmentTicketEntity`의 `@Table`·fulfillment 테스트와 `SchemaMigrationTest`의 SQL·도메인 모델 §1·§2·§3·§4.7과 대조했다. 컬럼·타입·nullability·unique 대상 `(order_id)`와 저장 형식 `listener_id`·`event_type`은 이전과 같아 불일치가 없었다. 이 기록의 앞부분은 대조 당시의 이름을 그대로 둔다. 이어서 같은 날 `FulfillmentTicket`→`Fulfillment` 이름 변경을 V007 DDL(`t_fulfillment`, `ux_fulfillment_order`)·`FulfillmentEntity`의 `@Table`·fulfillment 테스트와 `SchemaMigrationTest`의 SQL·도메인 모델 §1·§2·§3·§4.7과 대조했다. 컬럼·타입·nullability·unique 대상 `(order_id)`와 저장 형식 `listener_id`·`event_type`은 이전과 같아 불일치가 없었고, 치환에서 생긴 조사 오류(`Fulfillment`을/이, "이행 방식가")만 고쳤다.

## 검증
- `./gradlew test` — 전부 통과. Docker가 필요하다.
- `python3 scripts/check_pr.py --base origin/main --worktree` — PASS.

## Wiki 영향
- [도메인 모델](../wiki/domain-model.md) — §4.7 사례에 `t_fulfillment`의 unique `(order_id)`와 `type`을 뺀 근거를 더한다.
- [현재 상태](../wiki/current-state.md)는 볼트에서 갱신한다(`OrderEvent.Paid` → `contract.OrderPaid`, 기록되는 이벤트 목록, `fulfillment`가 비어 있지 않음, 테스트 수).
- 요구사항 변화: 없다. 원문이 정한 흐름이 이어진다.

## 분할 계획
약 18개 파일 / 500줄로 예상했고 실제는 23개 파일 / 705줄 안팎이다(이름 변경 뒤 `check_pr.py` 기준). 한도 안이다.

## TDD 검증 기록
| 동작 | RED | GREEN |
|---|---|---|
| `markPaid`가 `contract.OrderPaid`를 낸다 (`storeId`·`terminalId` 포함은 아래 행) | `OrderPaid` 최소 선언 뒤 `--tests '*OrderMarkPaidTest'` — 여전히 `OrderEvent$Paid`가 나와 타입 단언 실패 | 발행 이벤트를 바꾼 뒤 9개 통과 |
| `OrderPaid`를 받으면 fulfillment가 하나 생긴다 | 리스너 없이 `OrderPaidListenerIntegrationTest` — 10초 대기 초과 | 리스너 뒤 통과 |
| 재배달은 기존 fulfillment를 돌려준다 | 기존 fulfillment 조회를 뺀 Facade로 `FulfillmentCommandFacadeTest` — 저장 2건 | 조회를 넣은 뒤 통과 |
| 같은 `order_id` 두 번째 INSERT를 DB가 거절한다 | unique 없는 `V007`로 `FulfillmentUniqueOrderIntegrationTest` — 두 번째 INSERT 성공 | unique 뒤 통과 |
| checkout부터 fulfillment까지 | 리스너 없이 `FulfillmentFlowIntegrationTest` — 10초 대기 초과 | 리스너 뒤 통과 |
| `OrderPaid`가 주문의 `storeId`·`terminalId`를 싣는다 | 범위 조정 뒤 두 단언을 더하고 `markPaid`에 임시 `StoreId(UUID(0,0))`을 넣어 `--tests '*OrderMarkPaidTest'` — `storeId` 단언 실패(9개 중 1개) | 주문의 값을 싣자 9개 통과 |
| 저장된 `OrderPaid`를 복원하면 발행한 값과 같다 | characterization — 리뷰 뒤 더했고 처음부터 통과했다 | `--tests 'com.resilientpos.fulfillment.*'` 5개 통과 |
| 재배달은 실제 DB에서 기존 fulfillment를 찾고 두 기록이 모두 완료된다 | 리뷰 뒤 더했다. Facade의 기존 fulfillment 조회를 항상 실패하게 바꾸고 `--rerun --tests '*OrderPaidListenerIntegrationTest'` — 새 테스트만 실패(3개 중 1개) | 되돌리면 통과 |

회귀: `./gradlew test --tests 'com.resilientpos.order.*' --tests 'com.resilientpos.ModuleStructureTest'` 97개 통과, 마지막에 `./gradlew test` 전체 통과.

## 결과
217개 통과다(211 + 신규 6). 범위 조정(`storeId`·`terminalId`) 뒤에도 같다 — 기존 테스트에 단언만 더했다. 복원 왕복 테스트가 두 필드를 고정 UUID로 함께 비교한다.

- 이름을 다시 `Fulfillment`로 바꿨다(`t_fulfillment`, `ux_fulfillment_order`, `V007__fulfillment.sql`, `FulfillmentCommand.Create`, `createFulfillment`, 결과 필드 `fulfillmentId`). ticket이 매표소의 표처럼 읽힌다는 사용자 지적 뒤 레퍼런스를 조사했다. 백엔드 모델은 Square Orders API의 `Fulfillment`가 주문당 이행 단위로 우리 불변식과 맞고, ticket은 Toast·Lightspeed·Odoo에서 KDS·프린터의 표시 매체를 가리켰다. 동작을 바꾸지 않는 리팩터링이라 바꾸기 전과 뒤의 `./gradlew test`(217개)로 확인했다.
- 이름을 `KitchenTicket`에서 `FulfillmentTicket`으로 바꿨다. 클래스·테이블·제약·마이그레이션 파일·커맨드(`CreateTicket`)·Facade 메서드(`createTicket`)·테스트 클래스가 함께 바뀌었고 동작은 그대로다. 동작을 바꾸지 않는 리팩터링이라 새 RED 없이 바꾸기 전과 뒤의 `./gradlew test`(217개)로 확인했다. 저장 형식(`event_type`·`listener_id`)은 영향이 없다.
- `OrderEvent`에는 `Created`만 남았다. `Order.markPaid`는 `contract.OrderPaid`를 내고 `Transition` 시그니처는 그대로다.
- `FulfillmentJpaRepository`는 `JpaRepository`가 아닌 좁힌 `Repository`이고 `findByOrderId`만 연다. 새 행은 adapter가 `EntityManager.persist`로 넣으므로 Spring Data의 `save`는 두지 않았다.
- `OrderPaidListener`가 모든 `PAID` 전이에 비동기로 끼어들지만 기존 통합 테스트는 하나도 깨지지 않았다. 테스트마다 주문 ID가 달라 서로의 ticket을 보지 않는다.
- 동시 배달 경합(두 트랜잭션이 함께 "없음"을 읽는 경우)은 테스트로 재현하지 않았다. unique 제약이 거절한다는 것만 인수 조건 4로 고정했고, 그때 리스너가 `FAILED`로 남는 경로는 선례 `PaymentManagement.createAttempt`와 같다.

검토: multi-review를 실행했다(Claude 85점·Codex 실행, Gemini는 CLI가 없어 미실행). Critical은 없었다.

**받았다 — 공개 Facade가 도메인 모델 §2의 "소비 이벤트만"과 어긋난다**(Claude). Facade는 주문 상태를 묻지 않으므로 루트에 두면 다른 모듈이 결제 안 된 주문의 ticket을 만들 수 있다. 정본인 §2를 따라 Facade·커맨드·ID를 `internal`로 옮겼다.

**받았다 — 재배달 경로를 실제 DB로 검증하지 않는다**(Claude, Codex 2번과 겹침). 같은 주문의 `OrderPaid`를 두 번 내고 ticket 하나와 두 기록의 `COMPLETED`를 확인한다. `COMPLETED` 조건이 있어야 두 번째가 unique 위반으로 `FAILED`가 된 경우와 구분된다.

**받았다 — `listener_id`·`event_type`을 실제 행에서 확인하지 않는다**(Claude·Codex 합의). 발행 기록을 전체 정규 `listener_id`로 조회하고 `event_type`과 `COMPLETED`를 단언한다.

**받았다 — 로그로 생성과 재배달을 구분할 수 없다, 경합 한계에 다시 볼 조건이 없다, 복원 테스트의 시각이 초 단위다, 쓰지 않는 import**(Claude). `Result.created`와 로그의 `created=`를 더하고, Facade에 `ponytail:` 표식으로 다시 볼 조건(재발행·Kafka가 생기면 `on conflict do nothing` 뒤 조회)을 남기고, 나노초 시각으로 바꾸고, import를 정리했다.

**받지 않았다 — 두 Facade 호출을 latch로 맞춘 동시 경합 재현과, `FAILED`를 만든 뒤 `FailedEventPublications.resubmit`으로 다시 보내는 테스트**(Codex 1·2번). 둘 다 실패를 주입하는 테스트 전용 장치가 필요하다. 경합의 마지막 방어선인 unique는 인수 조건 4가 실제 PostgreSQL로 고정하고, resubmit이 거치는 두 단계(저장된 이벤트 복원, 기존 ticket 조회)는 복원 왕복과 재배달 테스트가 각각 고정한다. 선례 `PaymentManagement.createAttempt`도 같은 수준에서 멈췄다.

**받지 않았다 — `FulfillmentTicketPersistenceAdapter.save`에 `@Transactional`**(Claude). 행이 하나라 §4.2가 막으려던 "자식 없는 루트"가 생기지 않고, 선례 `PaymentAttemptPersistenceAdapter.save`도 같다. §4.2 문구를 좁힐지는 별도 PR이 다룰 일이다.

**문제 아님 — 새 비동기 리스너가 기존 테스트의 발행 기록 개수 단언을 흔든다**(Claude, 확인 요청). 발행 기록을 조회하는 기존 테스트는 모두 리스너 이름과 주문 ID로 좁혀 조회한다.
