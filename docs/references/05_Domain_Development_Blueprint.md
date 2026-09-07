# ResilientPOS — Domain Development Blueprint

> 문서 버전: v1.0 · 기준일: 2026-09-03  
> 목적: 이벤트 스토밍 결과를 Facade/Service, CQRS, JPA/Kotlin JDSL, Kafka/Redis 구현 규약으로 고정한다.

## 1. Approved implementation model

```text
HTTP / Event listener / Scheduler
          │ DTO → Command / Query
          ▼
      *Facade                         # use-case 조합 + transaction owner
          │
          ├─ *Service                  # 한 bounded context의 독립 business rule
          ├─ Command Repository        # aggregate load/save (JPA)
          ├─ Query Repository           # read DTO projection (Kotlin JDSL)
          └─ Domain event / EPR
                    │
                    └─ policy → owning module's Command Facade
```

| Concept | Mandatory responsibility | Explicitly not responsible for |
|---|---|---|
| `*Facade` | 하나의 use case에서 여러 Service/port를 조합, authorization/idempotency precondition 확인, local transaction 경계, `Command/Query → Result/View` 변환 | aggregate rule 구현, JPA entity 직접 조작, 다른 Facade 호출, remote HTTP/Kafka를 transaction 안에서 수행 |
| Aggregate / Value Object / `*Policy` | 상태 전이, 불변식 검증, 금액·할인·반올림·허용 여부 계산, domain event 생성 | repository/HTTP/provider SDK, 다른 bounded context 접근 |
| `*Service` | **한 bounded context의 비즈니스 행위**를 표현하고 Aggregate/Policy를 선택·조율. 규칙이 어느 한 Aggregate에도 자연스럽게 속하지 않을 때만 domain service로 명시 | DTO field 가공, 금액·상태 규칙 재구현, validation if-else 누적, controller/SDK 책임 |
| `*Command` | immutable한 쓰기 의도, command ID/idempotency/expected version | JPA entity, nullable filter 묶음, response serialization |
| `*Query` | immutable한 읽기 intent | 상태 변경, aggregate invariant 판단 |
| `*Criteria` | filter, cursor, sort, time range를 가진 immutable 검색 조건 | JPA `Specification`, JPQL/SQL, web request type |
| `*Result` / `*View` | immutable caller DTO; `Result`는 write 결과, `View`는 read projection | entity/lazy collection/provider raw DTO 노출 |

`Facade`와 `Service`는 Spring이 강제하는 이름이 아니라 이 프로젝트의 팀 규약이다. **데이터가 가진 규칙은 데이터 가까이에 둔다.** Aggregate가 자신의 상태 전이와 불변식을 검증하고, immutable Value Object/`*Policy` data class가 계산·가공·판정을 수행한다. Service는 이 객체들을 호출해 `Checkout`, `AcceptSyncOperation`, `ResolveReconciliation` 같은 비즈니스 행위를 표현한다. Service 안에 `amount + discount`, `status = ...`, 입력 field 조립, validation if-else가 늘어나면 Aggregate/Policy로 이동시킨다.

### 1.1 Naming examples

```kotlin
// command side
data class CreateOrderCommand(/* immutable intent */)
data class CreateOrderResult(val orderId: OrderId, val status: OrderStatus)

interface OrderCommandFacade {
    fun create(command: CreateOrderCommand): CreateOrderResult
}

interface OrderService { // order bounded context only
    fun create(command: CreateOrderCommand): Order // Order.create(policy)가 invariant/계산을 소유
}

// query side
data class FindOrdersQuery(val criteria: OrderSearchCriteria)
data class OrderSearchCriteria(
    val storeId: StoreId?,
    val statuses: Set<OrderStatus> = emptySet(),
    val cursor: OrderCursor? = null,
    val size: Int = 50,
)
data class OrderSummaryView(/* read-model-only fields */)
data class PageResult<T>(val items: List<T>, val nextCursor: String?)

interface OrderQueryFacade {
    fun find(query: FindOrdersQuery): PageResult<OrderSummaryView>
}
```

Controller는 HTTP request/response를 command/query/result로 mapping할 뿐이다. event listener/scheduler도 같은 Command Facade를 호출한다. **Facade끼리 호출하지 않는다.** 다른 module의 `internal` Service도 호출하지 않는다.

```kotlin
data class CheckoutPolicy(
    val currencyScale: Int,
    val roundingMode: RoundingMode,
) {
    fun snapshotOf(lines: List<OrderLine>): CheckoutSnapshot {
        require(lines.isNotEmpty()) { "EMPTY_ORDER" }
        // 합계/할인/반올림 계산은 policy가 소유한다.
    }
}

class Order(/* state */) {
    fun requestCheckout(policy: CheckoutPolicy): List<DomainEvent> {
        check(status == OrderStatus.OPEN) { "INVALID_ORDER_STATE" }
        checkoutSnapshot = policy.snapshotOf(lines)
        status = OrderStatus.CHECKOUT_REQUESTED
        return listOf(OrderPaymentRequested(/* snapshot */))
    }
}

class CheckoutService { // 비즈니스 행위의 조율, 계산/검증의 재구현 금지
    fun request(order: Order, policy: CheckoutPolicy): List<DomainEvent> =
        order.requestCheckout(policy)
}
```

## 2. Event storming — bounded contexts and aggregates

이 표는 주문·결제, 동기화·정산 관점을 교차 검토해 확정한 MVP aggregate map이다. `inventory`는 현재 비목표이며, 재고 예약 요구가 생길 때 별도 bounded context로 추가한다.

| Context | Aggregate root | Owns | Non-negotiable invariants |
|---|---|---|---|
| `store` | `Store` | 판매 정책, 통화/반올림, 매장 설정 | store policy는 versioned; 과거 주문 snapshot을 사후 변경하지 않음 |
| `terminal` | `Terminal` | terminal code, activation, credential rotation history | 매장 내 code 유일; inactive/retired terminal은 auth 불가; history 삭제 금지 |
| `order` | `Order` | line, discount, checkout amount/currency snapshot, lifecycle | `OPEN`에서만 금액/line 변경; checkout 이후 amount/currency immutable; paid event는 snapshot과 일치해야 함 |
| `payment` | `PaymentAttempt` | provider ref, provider idempotency, approval/cancel attempt, canonical status | `(orderId, attemptNo)` unique; amount/currency immutable; `UNKNOWN`은 failed가 아님; provider result의 한 번만 적용 |
| `sync` | `TerminalSyncState` | terminal watermark, command receipt/dedup, gap/conflict | `lastAckedSequence` monotonic; gap을 건너뛰어 전진 금지; `(terminalId, operationId)` hash mismatch 거절 |
| `reconciliation` | `ReconciliationCase` | source evidence, rule version, approval/action audit, workflow | evidence/rule/audit 없이 resolve/repair 금지; payment table 직접 수정 금지 |
| `fulfillment` | `KitchenTicket`, `ReceiptDelivery` | 주문 이후 ticket/receipt lifecycle | source order당 natural key로 한 번만 생성; retry side effect idempotent |

### 2.1 Cross-context ownership

```text
Order ── OrderPaymentRequested ──► Payment
Payment ── PaymentApproved / Declined / Unknown ──► Order, Reconciliation
Reconciliation ── narrow resolve command ──► Payment
Payment ── resolved fact ──► Reconciliation
Order ── OrderPaid ──► Fulfillment
Terminal/Sync ── accepted command ──► Order or Payment
```

- `Payment`만 payment canonical state를 바꾼다. `Reconciliation`은 case/evidence를 소유하고, 결론을 `PaymentManagement.resolveFromReconciliation(command)`이라는 좁은 public command로 요청한다.
- `Order`와 `Payment`는 서로의 table/entity/repository를 읽거나 쓰지 않는다.
- `SyncOperationApplied`는 기술적 receipt/audit event일 뿐 `OrderCreated`나 `PaymentAttemptCreated`를 대체하지 않는다.
- `OrderCompleted`처럼 모호한 이름은 쓰지 않는다. `OrderAccepted`, `OrderPaid`, `FulfillmentCompleted`, `OrderClosed`로 사실을 분리한다.

## 3. Event storming flows

### 3.1 Order → payment flow

```text
[CreateOrder]                 → OrderCreated
[ChangeOrderLine]             → OrderLineChanged
[RequestCheckout]             → CheckoutRequested, OrderPaymentRequested
                                    │ policy
                                    ▼
                             [CreatePaymentAttempt] → PaymentAttemptCreated
                                    │ provider authentication / redirect
                             [ConfirmPayment]       → PaymentApproved
                                                         │ policy → [MarkOrderPaid] → OrderPaid
                                                         ├───────→ PaymentDeclined
                                                         │          policy → [MarkOrderPaymentFailed]
                                                         └───────→ PaymentUnknown
                                                                    policy → [OpenReconciliationCase]
```

`PaymentApproved`를 받은 Order는 `orderId`, amount, currency, checkout version을 기존 checkout snapshot과 대조한다. 하나라도 다르면 `OrderPaid`로 전이하지 않고 reconciliation case를 연다. 초기 정책은 주문당 `PaymentAttempt` 한 건이다. 분할 결제/부분 환불이 실제 요구가 될 때만 `attemptNo`와 payment allocation을 확장한다.

### 3.2 Offline sync flow

```text
[ReceiveSyncBatch]
  → terminal auth + envelope validation
  → receipt/idempotency/sequence check
     ├─ duplicate        → return saved item result; no new semantic event
     ├─ gap              → SyncGapDetected; return GAP/BLOCKED_BY_GAP; watermark unchanged
     ├─ conflict          → SyncConflictDetected; return conflict result
     └─ accepted command  → owning Command Facade
                              ├─ CreateOrder → OrderCreated
                              └─ RequestPayment → PaymentAttemptCreated / existing result
                           → persist receipt + advance watermark
                           → SyncOperationApplied (audit/metric)
```

500개 batch는 하나의 거대 transaction이 아니다. item 또는 작은 chunk마다 commit하고, 이미 commit된 item만 `APPLIED`로 응답한다. `PAYMENT_REQUESTED` 재전송은 stable idempotency key로 기존 attempt/result를 반환하며, provider 승인 timeout을 다시 authorize하지 않는다.

### 3.3 Payment ambiguity and reconciliation

```text
provider timeout/5xx/response lost → PaymentUnknown
                                  → OpenReconciliationCase
                                  → Collect POS + local DB + provider query evidence
                                  → ProposeRepair / (required) ApproveRepair
                                  → Payment resolve command
                                  → PaymentConfirmed | PaymentFailed
                                  → ReconciliationCaseResolved
```

자동 repair는 rule version과 안전 조건을 기록한다. operator action에는 principal, reason, evidence, before/after, trace/correlation ID를 남긴다. `UNKNOWN` 중에는 성공/실패를 UI나 downstream event로 위장하지 않는다.

### 3.4 Fulfillment

`OrderPaid` 또는 정책상 허용된 `OrderAccepted`만 consumer로 받는다. `CreateKitchenTicketFromPaidOrderCommand`는 `(orderId, ticketType)` natural unique key로 멱등 처리하고 `KitchenTicketCreated`를 발행한다. Fulfillment는 결제/주문 상태를 직접 바꾸지 않는다.

## 4. Command/query catalog

| Context | Write Commands → Results | Read Query / Criteria → View |
|---|---|---|
| Store | `ChangeStorePolicyCommand → StorePolicyResult` | `GetStoreQuery → StoreView` |
| Terminal | `RegisterTerminal`, `ActivateTerminal`, `RotateCredential` → `TerminalResult` | `FindTerminals(TerminalSearchCriteria) → TerminalView` |
| Order | `CreateOrder`, `ChangeOrderLine`, `RequestCheckout`, `MarkOrderPaid` → `OrderResult` | `GetOrder`, `FindOrders(OrderSearchCriteria) → OrderDetailView/OrderSummaryView` |
| Payment | `CreatePaymentAttempt`, `ConfirmTossPayment`, `CancelPayment`, `ResolveFromReconciliation` → `PaymentResult` | `GetPayment`, `FindPayments(PaymentSearchCriteria) → PaymentView` |
| Sync | `ReceiveSyncBatch` → `SyncBatchResult(item results + watermark)` | `GetTerminalSyncStatus`, `FindSyncReceipts(SyncReceiptCriteria) → SyncStatusView` |
| Reconciliation | `OpenCase`, `AttachEvidence`, `ProposeRepair`, `ApproveRepair`, `ResolveCase` → `ReconciliationCaseResult` | `FindReconciliationCases(ReconciliationCaseCriteria) → ReconciliationCaseView` |
| Fulfillment | `CreateKitchenTicket`, `CompleteKitchenTicket`, `RequestReceipt` → `FulfillmentResult` | `FindFulfillmentBoard(FulfillmentBoardCriteria) → KitchenTicketView` |

Command에는 caller가 retry해도 유지되는 `commandId`/idempotency key와, stale write를 막는 `expectedVersion`을 둔다. Query Criteria에는 default/max page size, deterministic cursor (`createdAt`, `id`), allowed sort field를 고정한다. client가 entity ID, SQL column, arbitrary sort string을 주입할 수 없다.

## 5. CQRS and transaction rules

CQRS는 첫 단계에서 **write/read code model과 transaction policy의 분리**다. 처음부터 database나 deployment를 분리하지 않는다.

| Concern | Command side | Query side |
|---|---|---|
| Model | aggregate + invariant + command result | denormalized DTO/projection/view |
| Adapter | JPA command repository | Kotlin JDSL query repository |
| Transaction | Facade가 `@Transactional`; aggregate mutation + outbox/EPR record가 한 PostgreSQL TX | `@Transactional(readOnly = true)`; no mutation/flush |
| Return | `*Result` | `*View`, `PageResult<T>` |
| Consistency | command commit 즉시 정본 | projection은 eventual consistency 가능; `lastProjectedAt`/lag 노출 |

1. transaction owner는 Command Facade **한 곳**이다. inner Service/repository의 transaction annotation은 outer policy를 대체하지 않는다고 가정한다.
2. remote provider HTTP, Kafka publish, Redis call은 DB transaction 안에서 하지 않는다. write+publication/outbox만 commit하고 이후 policy/relay가 처리한다.
3. `REQUIRES_NEW`는 audit처럼 독립 실패가 명시적으로 허용되는 경우만 ADR로 쓴다. connection pool 고갈/lock 증가 위험이 있어 기본값이 아니다.
4. query는 write entity/lazy relation을 return하거나 serialize하지 않는다. strong consistency가 필요한 결제 확정 직후 화면은 Command Result 또는 write DB query를 사용하며 stale read projection에 의존하지 않는다.
5. 물리 read replica/projection service는 read lag SLO, fallback, data rebuild, contract version이 준비된 뒤 Query port 구현체를 교체하는 방식으로 도입한다.

## 6. Persistence design — JPA write, Kotlin JDSL read

### 6.1 Package / dependency boundary

```text
order/
├── api/                         # Facade contract, Command/Query/Criteria/Result/View, events
├── internal/
│   ├── domain/                   # aggregate, value object, *Service; JPA/JDSL 없음
│   ├── application/              # Facade implementation/policy
│   └── adapter/persistence/
│       ├── command/              # JPA entity, Spring Data command repository, mapper
│       └── query/                # Kotlin JDSL criteria → projection
```

Kotlin/JPA plugin과 JPA dependencies는 persistence adapter에만, Kotlin JDSL dependencies는 **query adapter가 생긴 module에만** 둔다. domain/application이 `EntityManager`, `JpaRepository`, `jpql {}`, Spring Data `Pageable`을 import하면 architecture test 실패다. domain의 `*Policy` data class는 persistence annotation 없이 immutable input/output과 검증·계산만 가진다.

### 6.2 Write-side rules

- `@Version`으로 optimistic locking을 사용한다. conflict 시 blind retry 대신 최신 aggregate를 읽어 command precondition을 다시 판단한다.
- `(terminalId, clientOrderId)`, `(orderId, attemptNo)`, `idempotency_record(scope, key)`, `(consumer, eventId)` 같은 DB unique constraint가 정확성의 마지막 방어선이다.
- cross-context entity association, cascade across aggregate, bidirectional association, `CascadeType.ALL`를 기본 사용하지 않는다.
- Kotlin JPA entity는 `data class`로 만들지 않는다. `kotlin("plugin.jpa")`/all-open 규칙, no-arg/proxy, nullable/default constructor policy를 build convention에 고정한다.
- Kotlin JDSL의 `update`/`delete` bulk query는 aggregate lifecycle/`@Version`을 우회할 수 있다. write path에는 금지하며, retention/운영 job만 별도 ADR·audit·명시 predicate에서 허용한다.

### 6.3 Read-side rules

Kotlin JDSL은 generated metamodel 없이 Kotlin property 기반 JPQL을 조립할 수 있으므로 동적 criteria와 DTO projection에 사용한다. 예시:

```kotlin
@Transactional(readOnly = true)
fun find(criteria: OrderSearchCriteria): PageResult<OrderSummaryView> {
    val query = jpql {
        selectNew<OrderSummaryView>(
            path(OrderEntity::id),
            path(OrderEntity::status),
            path(OrderEntity::totalAmount),
            path(OrderEntity::createdAt),
        ).from(entity(OrderEntity::class))
         .whereAnd(
             criteria.storeId?.let { path(OrderEntity::storeId).eq(it.value) },
             criteria.statuses.takeIf { it.isNotEmpty() }?.let { path(OrderEntity::status).`in`(it) },
             criteria.cursor?.let { cursorPredicate(it) },
         ).orderBy(path(OrderEntity::createdAt).desc(), path(OrderEntity::id).desc())
    }
    // EntityManager/JDSL execution and cursor mapping are adapter-internal.
}
```

목록은 offset보다 `(createdAt, id)` keyset cursor를 기본으로 한다. 운영 화면의 cross-context 조회는 다른 source entity를 join하지 말고 event-driven read projection을 만들거나 각 module public query를 composition layer에서 조합한다.

Kotlin JDSL 현재 안정 문서는 core `jpql-dsl`, `jpql-render`와 Spring Boot 세대에 맞는 Spring Data support artifact를 구분한다. 따라서 query adapter가 없으면 이 dependency도 없다. Kotlin value class를 JDSL parameter로 사용할 때는 공식 serializer/unboxing 제약을 테스트한다.

## 7. Redis and Kafka decision matrix

### 7.1 Redis — optional acceleration, never financial truth

| Use case | Allow? | Rule |
|---|---|---|
| Order/payment idempotency record | **No** | PostgreSQL unique record + response/hash가 authoritative |
| Sync watermark/receipt | **No** | terminal sequence 정확성은 PostgreSQL durable state |
| Store settings/read DTO/FX rate cache | Yes | cache-aside, TTL+jitter, cache miss → DB/source fallback, stale policy documented |
| Terminal/store rate limit | Yes | Redis unavailable fallback policy 필요; limiter가 payment truth를 바꾸지 않음 |
| in-flight duplicate burst suppression | Yes | short TTL hint만; DB idempotency 검사를 생략하지 않음 |
| Session/heartbeat | Yes | eviction/flush를 정상 상황으로 취급하고 re-auth/recovery path 제공 |

Redis는 DB read bottleneck 측정, multi-instance rate limit 필요, high-volume duplicate burst라는 근거가 있을 때만 `adapter-redis`에 추가한다. key는 `resilientpos:{env}:{owner}:{purpose}:{id}`로 namespace/owner/TTL/data classification을 문서화한다. write commit 뒤 cache eviction 또는 event-driven invalidation을 수행한다.

### 7.2 Kafka — optional externalization

| Trigger | Decision |
|---|---|
| 동일 deployable 내 후속 처리 | Spring Modulith event + EPR 유지 |
| fulfillment/notification/analytics가 독립 deployable 또는 independent replay/scale 필요 | integration event만 Kafka outbox로 externalize |
| payment/sync correctness가 강한 동안 | Kafka를 state authority로 만들지 않고 local transaction + EPR/reconciliation 유지 |

Kafka 도입 후에도 business state와 outbox row는 같은 DB transaction에 기록한다. relay는 duplicate publish할 수 있고 consumer는 `(subscriberId, eventId)` inbox record와 business update를 같은 local transaction으로 처리한다. Kafka producer idempotence/EOS는 Kafka log/offset 경계의 보장이므로 PostgreSQL 또는 external PG side effect의 exactly-once를 대체하지 않는다.

첫 external topic 후보는 `order.lifecycle.v1`, `payment.lifecycle.v1`, `fulfillment.lifecycle.v1`이다. key는 aggregate ID다. `SyncOperationApplied`, heartbeat처럼 volume이 큰 기술 이벤트는 metrics/logs가 기본이며 Kafka externalization 대상이 아니다.

## 8. Observability and test gates

| Signal | Trace/log attributes | Metric dimensions (low cardinality only) |
|---|---|---|
| Command | command ID, facade, aggregate ID, idempotency decision | module, command type, result code |
| Query | query/facade, criteria class, cursor mode | module, query type, duration bucket |
| JPA | tx ID, lock/optimistic conflict, SQL error class | module, operation, outcome |
| EPR/outbox/inbox | event ID, aggregate ID, correlation/causation | event type, module, status, age bucket |
| Redis | cache key hash/owner, fallback reason | cache name, hit/miss, eviction/fallback |
| Kafka | topic, partition, event ID, consumer group | topic, consumer, lag, retry/DLQ |

`orderId`, `paymentId`, terminal ID, cache key는 metric label이 아니다. trace/log search attribute로만 남긴다.

Required tests:

- module architecture: no `internal` import, no dependency cycle, only allowed named interface.
- command: same command/idempotency key replay, payload hash conflict, stale `expectedVersion`, aggregate invariant violation.
- query: Criteria cursor/sort/tenant/store scope, DTO only, `readOnly` transaction, no N+1/accidental entity serialization.
- sync: duplicate/gap/partial batch never emits duplicate semantic event; watermark never skips gap.
- payment: response loss → `UNKNOWN` → provider query/reconciliation; no duplicate approval.
- cache: Redis unavailable/evicted/stale → correct DB fallback or documented degraded response.
- Kafka: relay/consumer crash → duplicate delivery allowed, side effect once, no permanent event loss.

## 9. Deferred event-storming questions

These are product decisions, not implementation guesses. Resolve each with an ADR before adding the model.

1. 재고 예약을 checkout 전/후 어느 시점에 할지, oversell과 reservation expiry를 허용할지.
2. offline POS가 PG 확정 전 어떤 화면/영수증 상태를 보여줄지.
3. 분할 결제, 복수 tender, 부분 환불을 MVP에 포함할지.
4. cancellation과 refund의 상태 조합, provider callback이 늦거나 중복될 때의 customer/merchant policy.
5. reconciliation auto-repair의 rule, operator dual approval, evidence retention period.
6. read model을 서비스/replica로 분리할 때 허용 가능한 lag와 strong-read fallback.

## 10. References

- [Spring Modulith application modules](https://docs.spring.io/spring-modulith/reference/fundamentals.html) and [events / Event Publication Registry](https://docs.spring.io/spring-modulith/reference/events.html)
- [Spring Data JPA transactionality](https://docs.spring.io/spring-data/jpa/reference/jpa/transactions.html), [projections](https://docs.spring.io/spring-data/jpa/reference/repositories/projections.html), and [query/scroling options](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html)
- [Azure CQRS pattern](https://learn.microsoft.com/en-us/azure/architecture/patterns/cqrs)
- [Kotlin JDSL](https://kotlin-jdsl.gitbook.io/docs) and [JPQL dependencies/support modules](https://kotlin-jdsl.gitbook.io/docs/jpql-with-kotlin-jdsl)
- [Gradle `api` versus `implementation`](https://docs.gradle.org/current/userguide/java_library_plugin.html)
- [Redis cache-aside strategy](https://redis.io/learn/howtos/solutions/caching-architecture/cache-aside)
- [AWS transactional outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html) and [Apache Kafka delivery semantics](https://kafka.apache.org/40/design/design/)
- [OpenTelemetry semantic conventions](https://opentelemetry.io/docs/specs/semconv/) and [Toss Securities Kafka observability case](https://toss.tech/article/MSA-observability)
