# ResilientPOS — Backend Requirements & Architecture Decisions

> Status: **Approved baseline**  
> Last updated: 2026-09-03  
> Scope: POS server, offline-client synchronization, payment/reconciliation, and their operational platform.

## 1. Product and backend intent

ResilientPOS is a POS system that continues to accept orders when a terminal is offline, converges safely after reconnecting, and makes payment ambiguity observable and recoverable. The backend is therefore not only a CRUD API: it must preserve transaction intent, support idempotent replay, detect divergence, and expose enough evidence to investigate an incident end-to-end.

Primary capabilities:

| Capability | Backend responsibility |
|---|---|
| Store / terminal | Store policy, terminal identity, terminal status, and authorization scope |
| Order | Order lifecycle and immutable commercial record |
| Payment | Approval/cancel lifecycle, idempotency, and `UNKNOWN` outcome handling |
| Sync | Intake, deduplication, ordering checks, conflict classification, and replay of offline terminal changes |
| Reconciliation | Compare POS, server, and PG records; create an auditable repair workflow |
| Fulfillment | KDS, receipt, and notification work triggered after the order lifecycle advances |

## 2. Architecture Decision / Engineering Principles

### ADR-001 — Start as a Spring Modulith modular monolith

**Decision.** The server starts as one Git repository and **one Spring Boot deployable**. Inside that deployable it uses Spring Modulith application modules organized by package, not a microservice per business noun.

```text
resilient-pos/
├── pos-server/             # one Spring Boot process / deployable
├── pos-client/             # offline-capable POS client + local database
├── observability/          # OTel Collector, Grafana, Loki, Tempo, alert rules
├── load-test/              # k6 or Gatling scenarios
├── chaos/                  # Toxiproxy / failure scenarios
└── infra/                  # Compose / Kubernetes / database provisioning
```

This keeps a single local transaction boundary for the phase where correctness, replay, and observability are the product’s difficult problems. It does **not** mean a layered "big ball of mud": application-module boundaries are verified in tests and visible at runtime.

**Why not MSA first?** Multiple deployment units would add broker, network, deployment, distributed tracing, and cross-service consistency work before the business boundaries are proven. Kafka is introduced only at a real external/process boundary (ADR-005).

### ADR-002 — Application modules are packages; business capability owns its data

Spring Modulith detects direct packages below the application root as application modules. Public types live at the module root; implementation belongs in `internal`. A sibling module must never import an `internal` type.

```text
com.resilientpos
├── ResilientPosApplication.kt
├── store/
│   ├── StoreManagement.kt                 # public API
│   ├── StoreId.kt                         # public value type
│   └── internal/
├── terminal/
│   ├── TerminalManagement.kt
│   ├── TerminalId.kt
│   └── internal/
├── order/
│   ├── OrderManagement.kt
│   ├── OrderId.kt
│   ├── OrderCompleted.kt                  # published event contract
│   └── internal/
├── payment/
│   ├── PaymentManagement.kt
│   ├── PaymentId.kt
│   ├── PaymentConfirmed.kt
│   ├── PaymentOutcomeUnknown.kt
│   └── internal/
├── sync/
│   ├── SyncManagement.kt
│   ├── SyncBatchId.kt
│   └── internal/
├── reconciliation/
│   ├── ReconciliationManagement.kt
│   ├── ReconciliationRequested.kt
│   └── internal/
├── fulfillment/
│   └── internal/
└── platform/                              # technical adapters only
    ├── persistence/
    ├── observability/
    └── messaging/
```

`sync` is a domain module, not an HTTP/infrastructure detail: offline convergence is a defining business capability. `payment` remains independent of `order` because a payment can move through `PROCESSING → UNKNOWN → RECONCILING → CONFIRMED` independently of an order’s simple lifecycle.

Each module has **logical database ownership**. It owns its tables, migrations, repository implementations, and write model. Other modules access a module through its public facade or published event, never by querying its table/repository. PostgreSQL may initially be one physical database/schema, but table prefixes or dedicated schemas make ownership explicit (for example `orders.*`, `payments.*`, `sync.*`). Foreign keys across modules are avoided unless there is a strong integrity requirement that cannot be expressed at the owning boundary; store only the other aggregate’s ID by default.

### ADR-003 — Strict module-dependency and transaction rules

1. Dependencies form a DAG. `ApplicationModules.of(ResilientPosApplication::class.java).verify()` runs in CI.
2. Module root/public named interfaces are the only import surface. `internal`, JPA entities, repositories, controllers, and adapter clients do not cross module boundaries.
3. No generic `common` dependency is created for convenience. A type becomes shared only when it is stable, domain-neutral, and genuinely used by at least two modules. It must not contain a domain entity, repository, business service, or a dependency back into a module. Prefer duplication of a small DTO over premature shared coupling.
4. A synchronous public-facade call is permitted only when the caller needs a single, immediate business outcome and both state changes belong in the same local transaction. A future `checkout` orchestration module may coordinate `order` and `payment`; neither module reaches into the other’s internals.
5. Downstream work that can converge later uses a domain event. Event listeners are idempotent and record/derive their own completion state.
6. An aggregate transaction changes one owning module’s state. A cross-module event is published only after the owning state has committed.

Expected dependency direction:

```text
store ───────┐
terminal ────┼──► order ───► fulfillment
             │       │
             │       └──► payment ───► reconciliation
             └──► sync ──────────────► order/payment (public API or events)

platform = adapter implementation; it does not own business policy
```

### ADR-004 — Use domain events and Spring Modulith’s Event Publication Registry

**Decision.** In-process module integration is event-first when eventual consistency is acceptable. Use `spring-modulith-starter-jpa` with the Event Publication Registry (EPR), and use `@ApplicationModuleListener` for transactional, tracked consumers.

```text
owner transaction
  ├─ persist Order / Payment / Sync state
  └─ publish immutable domain event
            │ after commit
            ▼
 Event Publication Registry (durable publication + listener completion)
            │
            ├─ fulfillment listener
            ├─ reconciliation listener
            └─ optional externalization listener
```

The EPR lets incomplete publications be inspected and resubmitted after a listener failure instead of silently losing a post-commit action. It is not a substitute for consumer idempotency or an "exactly once" claim: handlers use `eventId` as an idempotency key and retain a processed-event record where their side effect is not naturally idempotent. Monitor outstanding publications and completion attempts; exercise restart and replay in failure tests.

Event contracts are immutable, versioned deliberately, and contain only the facts needed by consumers. They include `eventId`, `occurredAt`, `aggregateId`, `storeId`, `terminalId` where applicable, correlation/causation IDs, and a schema version. No JPA entity is emitted. A representative public event:

```kotlin
data class PaymentConfirmed(
    val eventId: UUID,
    val occurredAt: Instant,
    val paymentId: PaymentId,
    val orderId: OrderId,
    val storeId: StoreId,
    val amount: Money,
    val correlationId: String,
    val schemaVersion: Int = 1,
)
```

The publication table is operational data. Retain completed rows for the incident-audit period or archive them; alert on the age and count of incomplete publications. Its schema/migrations are owned by platform persistence, not by a business `common` module.

### ADR-005 — Kafka is an optional externalization step, not the internal default

**Phase 1.** Domain events and the EPR decouple modules inside the single deployable. No Kafka is required for KDS/receipt work that is local and can be retried through the EPR.

**Phase 2.** Externalize only a stable integration event when a separate KDS, CRM, analytics pipeline, or independently deployable consumer exists. Use `spring-modulith-events-kafka`; select events explicitly (`@Externalized`) and map the internal event to a dedicated integration payload. Keep topic, message key, headers, schema version, retention, retry/DLQ policy, and owner documented with the event.

For a delivery guarantee stronger than transactional-listener broker publication, use Spring Modulith’s supported outbox externalization mode or an explicit outbox implementation and verify it under a broker outage. Consumers remain at-least-once safe through `eventId` deduplication. Kafka partition order is scoped to a key only; use `aggregateId` as the key where per-aggregate ordering matters. Do not use broker offset or UUID order as a business sequence.

### ADR-006 — Keep Gradle as one server module until a proven build boundary appears

**Initial shape:** one `pos-server` Gradle project, package modules verified by Spring Modulith. This maximizes refactoring speed and prevents Gradle wiring from impersonating a domain boundary.

Introduce Gradle subprojects only when at least one of these is true:

- the POS client or another executable needs a separately versioned, framework-light contract artifact;
- a bounded context has independent build/test/dependency needs and a stable API;
- build time or ownership makes the boundary materially useful;
- an adapter is reusable and does not pull an application module with it.

Possible later structure:

```text
pos-server/
├── app/                         # executable composition root
├── modules/order/               # only after stability is earned
├── modules/payment/
├── contracts/                   # versioned external contracts, not a dumping ground
└── platform/                     # persistence/OTel/Kafka adapters
```

Gradle source-set visibility supplements, but never replaces, `ApplicationModules.verify()` and module tests. Do not split a module merely because it owns a table.

### ADR-007 — Observability is a P0 cross-cutting requirement

Every request, sync batch, payment attempt, reconciliation run, and event publication must be traceable from symptom to corrective action.

```text
POS client ── W3C trace context ──► API
                                      │ OpenTelemetry SDK / Micrometer
                                      ▼
                              OTel Collector
                           ┌──────┼────────┐
                           ▼      ▼        ▼
                    Prometheus  Loki     Tempo
                     metrics    logs     traces
                           └──────┼────────┘
                                  ▼
                               Grafana
                                  ▼
                       Alert → runbook → replay / reconcile / rollback
```

Required implementation:

- Spring Boot Actuator, Micrometer, OpenTelemetry export, structured JSON logs, and trace/log correlation.
- `spring-modulith-starter-insight` (or its observability/Actuator artifacts) so module API invocations and domain-event publications appear as module-tagged spans and metrics.
- Propagate W3C trace context across HTTP, Kafka headers, and asynchronous event work. Include `correlationId`, `terminalId`, `storeId`, `orderId`, and `paymentId` in logs/traces; avoid placing raw card/PII/secrets in telemetry.
- Dashboards and alerts: API error/latency, sync backlog and conflict rate, payment `UNKNOWN` age/count, reconciliation mismatch count/age, event publication backlog/retry attempts, Kafka consumer lag/DLQ when enabled, and SLI/SLO burn rate.
- Every high-severity alert links to a concise runbook containing query links, safe replay/reconciliation steps, rollback criteria, and escalation policy.

## 3. Money Decision

### ADR-008 — `Money` is a value object backed by `BigDecimal`; persistence is `NUMERIC(19,4)`

**Decision.** Monetary values use a domain `Money(amount: BigDecimal, currency: Currency)` type. The canonical server database representation is PostgreSQL `NUMERIC(19,4)` plus `CHAR(3)` ISO-4217 currency; API JSON represents `amount` as a decimal **string**, never a JSON floating point number. `Double`/`Float` are prohibited for money.

`BigDecimal` is chosen over a `Long` minor-unit-only representation because ResilientPOS must model currency-specific fraction digits, tax/discount/rate calculations, and precise intermediate amounts without a hidden global scale. The cost is that scale and rounding must be explicit. For a closed, single-currency, no-fraction product, `Long` minor units would be simpler and faster; it is deliberately not the default here.

| Concern | Required rule |
|---|---|
| Input | Parse a decimal string or integer minor amount; never call `BigDecimal(double)` |
| Currency | ISO-4217 code; currency is part of the value and cannot be mixed in arithmetic |
| Canonical scale | Per currency policy. KRW is scale `0`; ordinary settlement amounts must obey the configured currency scale |
| DB scale | `NUMERIC(19,4)` is the maximum persisted amount scale for this product. Reject values that cannot fit; do not let the driver round implicitly |
| Arithmetic | Use explicit scale and `RoundingMode` at each business boundary. Do not rely on an implicit JVM default |
| Rounding policy | Tax/discount/intermediate precision may use a separately documented calculation scale; final charge/receipt rounding is a store/country policy, initially `HALF_UP` only where that policy explicitly applies |
| Equality | Monetary comparison uses `compareTo`, or normalize to canonical scale before `equals`; `BigDecimal.equals` is scale-sensitive |
| Serialization | `"amount":"12500"`, `"currency":"KRW"`; persist raw amount and currency in an embeddable/converter |

Example (illustrative, not a generic shared-model mandate):

```kotlin
data class Money private constructor(
    val amount: BigDecimal,
    val currency: Currency,
) {
    companion object {
        fun of(amount: String, currency: Currency): Money {
            val scale = CurrencyScalePolicy.scaleOf(currency) // KRW -> 0
            val normalized = amount.toBigDecimal().setScale(scale, RoundingMode.UNNECESSARY)
            return Money(normalized, currency)
        }
    }
}
```

`Money` is shared only as a small, stable value type (for example in a `foundation` package with no Spring/JPA dependency). Payment/order-specific pricing, tax, and discount policy stays with its owning module. Add a JPA `@Embeddable` or `AttributeConverter` in the persistence adapter without leaking entities between modules.

Migration note: `NUMERIC(19,4)` is a product limit, not a claim that every currency has four fraction digits. If a future currency/product requires more precision, introduce a documented migration and compatibility plan; do not silently change the column scale.

## 4. Identifier Decision

### ADR-009 — Application-generated UUID v7 is the default persistent identifier

**Decision.** Aggregate IDs, payment IDs, sync-batch IDs, event IDs, and client-originated idempotency keys use **application-generated UUID version 7**, stored in PostgreSQL’s native `uuid` type and represented in Kotlin as `java.util.UUID`. Generate the ID before persistence and before network transfer, both on the server and in the offline client.

This supports offline-first creation and safe retries without a central sequence round-trip. UUID v7 is standardized in RFC 9562 and contains a Unix-epoch time-ordered prefix; compared with random v4 it gives substantially better insertion locality for a PostgreSQL B-tree while retaining 128-bit collision resistance. It is chronological by generation time, **not a total causal/business order**: sync uses `(terminalId, terminalSequence)` for per-terminal ordering, server `receivedAt` for intake order, and explicit state/version rules for conflict resolution.

PostgreSQL 18 can generate `uuidv7()` itself, but ResilientPOS does not make that the primary generator: a database-generated value cannot be created by an offline terminal and would couple the design to a particular PostgreSQL release. It remains a useful administrative/backfill option.

Backend implementation baseline:

```kotlin
// application service / factory, before entity persistence
val id: UUID = UuidCreator.getTimeOrderedEpoch() // UUID v7
```

```kotlin
// build.gradle.kts — pin the reviewed version via dependency management
implementation("com.github.f4b6a3:uuid-creator:6.1.1")
```

Use a thin `IdentifierGenerator` port so the library is not spread throughout domain code. Hibernate/JPA’s `GenerationType.UUID` and default `@UuidGenerator` are intentionally not used for these domain IDs: Hibernate’s default UUID generator is v4, and persistence-generated IDs are unavailable to offline producers. Map `UUID` to PostgreSQL `uuid` natively; do not store it as `VARCHAR(36)`.

### Options considered

| Strategy | Distributed/offline creation | PostgreSQL B-tree locality | Size / storage | Sortability | Exposure / operations | Decision |
|---|---|---|---|---|---|---|
| `Long` identity / sequence | No; central DB allocates it | Excellent | 8 bytes | DB allocation order | Short and easy to inspect, but exposes volume/sequence and blocks offline creation | Reject as primary ID |
| UUID v4 | Yes | Poorer: uniformly random inserts | 16-byte native `uuid` | No useful time order | Widely supported; opaque-looking but never an authorization control | Reject as default; acceptable for privacy-focused random tokens |
| UUID v7 | Yes | Good time-prefix locality | 16-byte native `uuid` | Approximate time order | Standard UUID type, familiar tooling. Timestamp leaks coarse creation time | **Adopt** |
| ULID | Yes | Good lexical/time locality | 16 bytes binary; 26 chars as text | Yes | Human-friendly Crockford Base32, but needs conversion/mapping to native UUID | Not default; use only if a human-entered ID format becomes a product requirement |
| TSID | Yes, with node/counter configuration | Excellent | 64-bit integer / 13-char string | Yes | Compact and debug-friendly, but node configuration/clock behavior adds operational coordination and it lacks native UUID interoperability | Not default; reconsider for a server-only high-volume technical table |

#### Consequences and safeguards

- UUID v7 is not secret. It reveals approximate creation time; authorization always scopes by tenant/store/principal and checks object ownership. If a public link needs stronger non-enumerability with no timestamp disclosure, use a separate v4/random public token rather than repurposing the primary key.
- UUIDs are larger than `BIGINT`. Use native `uuid`, index only query paths that need it, and avoid duplicating wide UUIDs in unnecessary secondary indexes. The locality benefit is expected to reduce random-insert pressure versus v4, but it must be measured using the project’s representative write load.
- The terminal’s persisted sequence and a terminal-local durable outbox are mandatory for sync correctness. UUID uniqueness does not by itself detect a missing operation, duplicate command, clock rollback, or conflict.
- Preserve the UUID string in structured logs and traces. Operators can copy it through API, PostgreSQL, Grafana, and Kafka; provide a short display code only as a presentation layer feature.
- For technical event tables, use a separate surrogate only if a measured database/retention need demands it. It must not escape as the domain/event identifier.

### ULID and TSID library assessment

The reviewed Java libraries are viable but are **not introduced** in the baseline:

- `com.github.f4b6a3:ulid-creator:5.2.4` generates 128-bit time-sortable ULIDs and can convert/store them as UUIDs. It is the fallback if a 26-character Crockford Base32 public identifier is later required.
- `com.github.f4b6a3:tsid-creator` creates 64-bit time-sorted IDs. Its node/counter allocation trades off nodes against IDs per millisecond; operating that safely across multiple server/client generators is extra configuration that UUID v7 avoids.

Any later replacement requires a compatibility migration, dual-read/dual-write period, foreign-key/index review, and client generator rollout. IDs are immutable once published.

## 5. Offline sync, payment, and recovery requirements

### 5.1 Client-generated command envelope

Each client operation carries a UUID v7 `operationId`, terminal ID, monotonically increasing terminal sequence, occurred time, payload schema version, idempotency key, and W3C trace context when available. The client persists this envelope with the business operation before attempting network delivery.

The server applies the following behavior:

1. Deduplicate on `(terminalId, operationId)` / command idempotency key using a unique constraint.
2. Return the previous accepted result for an exact retry; do not perform the payment/order side effect twice.
3. Detect a terminal sequence gap or incompatible state as a sync-domain result (`GAP`, `CONFLICT`, `REQUIRES_RECONCILIATION`), not a generic 500 error.
4. Record accepted/rejected/conflicted decisions with correlation IDs and emit a domain event only for the committed decision.

### 5.2 Payment ambiguity is explicit

An HTTP timeout after a PG approval is not a payment failure. Model `UNKNOWN` and schedule/query reconciliation using the provider transaction reference plus the client idempotency key. Cancellation, refund, and replay are separate auditable transitions. An incident operator needs to answer: what did the terminal believe, what did the server record, what did the PG accept, and what repair action was taken.

### 5.3 Reconciliation workflow

`reconciliation` owns mismatch detection and repair-case lifecycle. It consumes stable facts/events or uses public module query APIs; it does not reach into payment/order repositories. Each case records source evidence, rule/version, affected IDs, operator/system action, before/after state, and trace/correlation links. Automated repairs are idempotent and gated by an explicit safe rule; uncertain cases are surfaced for operator review.

## 6. Verification and delivery gates

Before calling this baseline complete, CI must demonstrate:

- Spring Modulith structural verification (no cycles, no illegal `internal` dependencies) and module-focused integration tests.
- Money tests for invalid scale, `BigDecimal(double)` prohibition, rounding boundaries, mixed currency rejection, JSON string serialization, and `NUMERIC(19,4)` fit.
- UUID v7 tests for format/version, persistence as PostgreSQL `uuid`, client/server generation, duplicate-command retry, clock anomaly behavior, and B-tree insert benchmark versus v4 under representative concurrency.
- Failure tests: server crash after domain commit, listener failure/restart/republication, PG timeout/ambiguous payment, duplicate sync delivery, sequence gap, Kafka outage (when enabled), and reconciliation replay.
- Observability tests that prove a sample checkout/sync trace reaches logs, metrics, and trace storage with module and correlation attributes; alerts must link to a runnable runbook.

## 7. Reference register

The decisions above were checked against the following primary specifications, official framework documentation, and implementation libraries (accessed 2026-09-03):

1. [Spring Modulith reference — application modules and fundamentals](https://docs.spring.io/spring-modulith/reference/index.html) — package-based modules, verification, testing, and runtime support.
2. [Spring Modulith — working with application events](https://docs.spring.io/spring-modulith/reference/events.html) — Event Publication Registry, listeners, event externalization, Kafka integration, and the limitations of listener-based externalization.
3. [Spring Modulith — production-ready / observability](https://docs.spring.io/spring-modulith/reference/production-ready.html) — module invocation spans and module-event metrics.
4. [Spring Modulith appendix](https://docs.spring.io/spring-modulith/reference/appendix.html) — starter/artifact composition and externalization modes.
5. [RFC 9562 — UUIDs](https://www.rfc-editor.org/rfc/rfc9562.html) — UUID v7 standardized time-ordered format.
6. [PostgreSQL UUID type](https://www.postgresql.org/docs/current/datatype-uuid.html) and [UUID functions](https://www.postgresql.org/docs/current/functions-uuid.html) — native `uuid` storage and current `uuidv7()` support.
7. [PostgreSQL numeric type](https://www.postgresql.org/docs/current/datatype-numeric.html) — exact numeric storage and precision/scale behavior.
8. [Java `BigDecimal` API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html) and [Java `Currency` API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Currency.html) — decimal scale, rounding, and currency fraction-digit metadata.
9. [Hibernate ORM User Guide — UUID generation](https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html) — `GenerationType.UUID`, `@UuidGenerator`, and the default random v4 behavior.
10. [UUID Creator](https://github.com/f4b6a3/uuid-creator) — reviewed UUID v7 generator used by the backend baseline.
11. [ULID Creator](https://github.com/f4b6a3/ulid-creator) and [TSID Creator](https://github.com/f4b6a3/tsid-creator) — alternatives assessed for their encoding, ordering, storage, and node/counter trade-offs.

## 8. Deferred decisions

- Exact offline conflict-resolution policy per command type (last-write-wins is not assumed).
- Country/store-specific tax and cash-rounding rule catalog.
- PostgreSQL version baseline and whether to use one physical schema or schema-per-module.
- Kafka topic naming, retention, schema registry, DLQ ownership, and outbox implementation — deferred until an external consumer is real.
- Public display/reference number format; this must remain separate from the immutable UUID v7 primary identifier.
