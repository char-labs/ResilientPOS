# ResilientPOS — System Design

> 문서 버전: v2.0 · 기준일: 2026-09-03  
> 목적: 현재의 신뢰성 설계와, 검증 가능한 MSA 전환 경로를 함께 정의한다.

## 1. Architectural baseline

### 1.1 결정: Spring Modulith 기반 modular monolith

서버는 하나의 Spring Boot deployable로 시작한다. `order`, `payment`, `sync`, `reconciliation`의 경계는 코드·테스트·데이터 소유권에서 강제하지만, 첫 단계에서 서로를 네트워크 호출하게 만들지 않는다. 이는 분산 배포를 피하기 위한 단순화가 아니라, offline/결제/정합성의 핵심 문제를 단일 로컬 트랜잭션 안에서 먼저 검증하기 위한 선택이다.

```text
POS client / simulator
  ├─ online REST API ───────────────────────────────────────────┐
  └─ durable offline queue → batch sync ────────────────────────┤
                                                             Server
                                                       ┌─────────▼──────────┐
                                                       │ Spring Modulith app │
                                                       │ store / terminal    │
                                                       │ order / payment     │
                                                       │ sync / reconcile    │
                                                       │ fulfillment         │
                                                       └─────────┬──────────┘
                                                                 │
                 PostgreSQL: business state + event publication registry/outbox
                                                                 │
                      optional external boundary ─ Kafka ─ KDS / receipt / CRM
```

Spring Modulith의 package application module 모델과 구조 검증은 이 경계를 CI에서 검사한다. `ApplicationModules.verify()`는 모듈 순환과 비허용 의존을 실패시켜야 한다.

### 1.2 Bounded context와 책임

구체적인 aggregate 분할과 event-storming 결과는 [Domain Development Blueprint](05_Domain_Development_Blueprint.md)에서 관리한다.

| 모듈 | 소유하는 책임 | 외부에 공개하는 것 | 금지되는 것 |
|---|---|---|---|
| `store` | 매장 정책·설정 | `StoreManagement`, ID/value types | 타 모듈의 테이블 수정 |
| `terminal` | 단말 등록·인증·상태 | `TerminalManagement` | sync watermark 직접 조작 |
| `order` | 주문 lifecycle·상업 기록 | `OrderManagement`, `OrderCompleted` | payment repository 접근 |
| `payment` | 승인/취소/UNKNOWN·PG 참조 | `PaymentManagement`, 결제 이벤트 | order entity 직접 변경 |
| `sync` | command intake, gap/duplicate/conflict | `SyncManagement`, sync 결과 | 주문 테이블 bulk write |
| `reconciliation` | 불일치 case·복구 workflow | `ReconciliationManagement` | 근거 없는 상태 강제 변경 |
| `fulfillment` | KDS/영수증 후속 처리 | 소비 이벤트 | 주문/결제 lifecycle 소유 |

한 모듈은 모듈 루트의 facade, value type, event만 공개하고 JPA entity/repository/service/adaptor는 `internal`에 둔다. `common`은 공유 도메인 모델을 담는 장소가 아니다. 프레임워크 없는 `Money`, `Clock`, 식별자 같은 안정적이고 중립적인 value type만 제한적으로 공유한다. 작은 DTO 중복은 결합된 공유 라이브러리보다 낫다.

### 1.3 동기 호출과 이벤트

- 단일 즉시 결과와 강한 일관성이 필요한 경우에만 다른 모듈의 public facade를 동기 호출한다. 향후 `checkout` orchestration module이 `order`와 `payment`를 조율할 수 있다.
- 주방 표시, 영수증, 분석, reconciliation 시작처럼 나중에 수렴해도 되는 작업은 immutable domain event를 사용한다.
- 이벤트 소비자는 idempotent하다. 재전달은 정상 경로이며, event ID를 processed-event store 또는 자연 멱등키로 차단한다.
- aggregate version은 **한 aggregate의 상태 순서**를, terminal sequence는 **한 단말의 offline command 순서**를 나타낸다. UUID 생성 순서나 Kafka 전체 순서는 업무 순서가 아니다.

## 2. 데이터와 일관성 설계

### 2.1 Logical database ownership

초기에는 한 PostgreSQL cluster/DB를 사용해도 각 모듈이 논리적으로 테이블, migration, repository, write model을 소유한다. 다른 모듈은 table join이나 repository import로 데이터를 읽지 않는다. 필요한 정보는 public query facade, 이벤트 기반 projection, 또는 명시적인 read model을 통해 얻는다.

| Store | 핵심 키/제약 | 소유자 | 용도 |
|---|---|---|---|
| `orders` | UQ(`terminal_id`, `client_order_id`), optimistic `version` | order | 주문 source of truth |
| `payments` | UQ(`idempotency_key`), UQ(`pg_tx_id`) | payment | 승인 시도와 결과 |
| `terminal_sync` | PK(`terminal_id`) | sync | ack watermark/gap 상태 |
| event publications/outbox | event ID, aggregate ID, type, payload, completion/published state | event owner + platform | durable async hand-off |
| `processed_events` | PK(`consumer`, `event_id`) | consumer module | 중복 side effect 차단 |
| `reconciliation_cases` | case ID, evidence, rule, state, action | reconciliation | 불일치/복구 감사 |

교차 모듈 FK는 기본적으로 만들지 않는다. 외래 aggregate의 ID를 보관할 수는 있지만 존재 확인/변경은 owning module의 API 또는 이벤트에 따른 상태로 다룬다. 이 원칙은 나중에 database-per-service로 옮길 때 distributed join과 hidden write dependency를 줄인다.

### 2.2 Money

`Money(amount: BigDecimal, currency: Currency)`가 금액의 canonical value object다. PostgreSQL에는 `NUMERIC(19,4)`와 ISO-4217 `CHAR(3)`를 저장한다. API/event에서 amount는 JSON number가 아닌 decimal string이다.

| 규약 | 결정 |
|---|---|
| 입력 | string 또는 integer minor unit만 받으며 `BigDecimal(double)` 사용 금지 |
| 통화 | 서로 다른 통화의 연산 금지; currency는 값의 일부 |
| scale | 통화 정책으로 canonical scale 결정(KRW=0); 저장 한도는 scale 4 |
| rounding | 계산/세금과 최종 청구 경계를 분리하고 `RoundingMode`를 명시 |
| equality | canonical scale 정규화 또는 `compareTo`; `BigDecimal.equals`의 scale 민감성을 인지 |
| 한계 초과 | driver가 묵시적으로 반올림하지 않게 validation에서 거절 |

`NUMERIC(19,4)`는 현재 제품의 명시적 한도다. 더 높은 fraction precision이 필요한 통화/상품을 도입하면 migration, API/event compatibility, 정산 규칙을 별도 ADR로 처리한다.

### 2.3 Identifier

모든 aggregate, payment, sync batch, event, client operation의 기본 식별자는 **application-generated UUID v7**이며 PostgreSQL native `uuid`로 저장한다. 서버와 offline POS 모두 persistence/전송 전에 ID를 만든다.

| 후보 | 분산 생성 | index locality | 저장/표현 | 결론 |
|---|---|---|---|---|
| `Long` sequence | 불가 | 매우 좋음 | 8 byte | offline 생성 불가·노출 위험으로 primary ID 제외 |
| UUID v4 | 가능 | 랜덤 insert | native 16 byte | 보조 random token 용도 외 기본값 제외 |
| UUID v7 | 가능 | time prefix로 v4보다 좋음 | native 16 byte | **채택** |
| ULID | 가능 | 좋음 | binary 16 byte/text 26 char | 사람이 입력할 public code가 필요할 때만 검토 |
| TSID | 가능(노드 설정 필요) | 좋음 | 64-bit/13 char | generator node/clock 운영 부담으로 기본값 제외 |

UUID v7은 timestamp를 일부 노출하므로 권한 검사를 대체하지 않는다. object access는 tenant/store/principal ownership을 항상 검증한다. 정렬은 best-effort 생성 시각이지 business causality가 아니며, sync는 terminal sequence로 정렬한다. 백엔드에는 `com.github.f4b6a3:uuid-creator:6.1.1`을 `IdentifierGenerator` adapter 뒤에 사용한다. Hibernate의 기본 UUID 생성은 v4이므로 domain ID 생성에는 의존하지 않는다.

## 3. Command, API, Event Contracts

### 3.1 Offline command envelope

```json
{
  "operationId": "uuid-v7",
  "terminalId": "T-01",
  "sequence": 101,
  "occurredAt": "2026-09-03T10:00:00Z",
  "type": "ORDER_CREATED",
  "schemaVersion": 1,
  "idempotencyKey": "uuid-v7-or-stable-key",
  "payload": { "...": "..." }
}
```

서버는 `(terminalId, operationId)` unique constraint와 payload hash로 exact retry를 판별한다. 동일 key에 다른 payload면 `409 IDEMPOTENCY_CONFLICT`; gap/충돌은 item-level `GAP`, `CONFLICT`, `REQUIRES_RECONCILIATION` 결과로 반환한다. partial success를 HTTP 200 하나로 숨기지 않는다.

주요 endpoint는 `POST /v1/orders`, `POST /v1/terminals/{id}/sync`, `GET /v1/terminals/{id}/sync-status`, `POST /v1/payments`, `GET /v1/payments/{id}`, `POST /v1/payments/{id}/cancel`이다. 운영 API(reconciliation, replay)는 별도 role/scope 및 audit를 요구한다.

### 3.2 Event envelope

외부화 가능한 이벤트는 domain entity와 분리된 integration contract로 매핑한다.

```json
{
  "eventId": "uuid-v7",
  "eventType": "payment.confirmed",
  "schemaVersion": 1,
  "occurredAt": "2026-09-03T10:00:00Z",
  "producer": "payment",
  "aggregateId": "uuid-v7",
  "correlationId": "...",
  "causationId": "...",
  "data": { "...": "..." }
}
```

- event는 과거형 fact이며 mutable entity snapshot이 아니다.
- Kafka partition key는 aggregate별 순서가 필요하면 `aggregateId`를 사용한다.
- consumer는 at-least-once delivery를 가정하며 event ID로 멱등 처리한다.
- payload에는 카드 원문·불필요한 PII를 포함하지 않는다. trace context는 표준 messaging propagation으로 전파한다.

### 3.3 Contract-first and compatibility rules

OpenAPI는 동기 HTTP 계약, AsyncAPI는 message/event 계약의 source of truth다. 스펙은 코드와 함께 버전 관리하고 CI에서 producer/consumer compatibility와 example payload를 검증한다.

1. 기존 field의 의미·type·필수성·enum 값을 breaking change로 바꾸지 않는다.
2. 새 field는 optional/additive로 도입하고, consumer는 모르는 field를 무시할 수 있어야 한다.
3. rename/remove는 새 field/topic/version을 추가한 뒤 dual-read/dual-publish와 deprecation window를 거친다.
4. API version은 명시적으로 URL/media type/contract version 중 한 정책으로 관리한다. event version은 topic 이름보다 envelope `schemaVersion`과 호환성 규칙을 우선한다.
5. 공유 domain JAR을 서비스 간 배포 결합 수단으로 쓰지 않는다. contract schema와 generated client/model은 허용하되, 각 서비스가 자신의 anti-corruption mapper와 domain model을 유지한다.

## 4. Reliable async processing

외부 payment/notification/FX 경계의 adapter, timeout, circuit breaker, fallback, webhook 설계는 [External Integration Strategy](04_External_Integration_Strategy.md)를 따른다.

### 4.1 In-process: Event Publication Registry

Phase 1에는 Spring Modulith EPR와 `@ApplicationModuleListener`를 사용한다. owner transaction이 상태와 publication record를 만든 뒤, post-commit listener 완료 상태를 durable하게 추적한다. 실패한 publication은 관찰하고 재전송할 수 있다.

EPR은 listener 실패를 추적하는 기반이지, idempotency나 exactly-once의 증명이 아니다. incomplete publication count, oldest age, completion attempts를 metric/alert로 내보내고, restart/republication failure test를 갖춘다.

### 4.2 External: outbox and Kafka

독립 배포 consumer가 실제로 생긴 event만 `spring-modulith-events-kafka`로 externalize한다. Broker publication은 반복될 수 있으므로 consumer 멱등성을 유지한다. 더 강한 DB-to-broker handoff가 필요하면 Spring Modulith outbox externalization mode 또는 명시적 transactional outbox/CDC를 채택하고 outage를 통해 검증한다.

```text
business DB transaction
  ├─ aggregate update
  └─ outbox/publication record insert
            │ committed rows only
            ▼
 relay / outbox externalizer ──► Kafka topic ──► idempotent consumer
```

DLQ는 버리는 장소가 아니라 운영 상태다. 원 event, 실패 사유/stack category, attempt, owner, replay authorization, before/after audit를 보관한다. replay는 새 business command가 아니라 동일 event identity의 재처리여야 한다.

## 5. Payment, sync, reconciliation state model

| 대상 | 대표 상태 | 핵심 규칙 |
|---|---|---|
| Order | `OFFLINE_CREATED → SYNCED → PAYMENT_PENDING → PAID → COMPLETED`, `CANCELLED` | paid 뒤 임의 금액 변경 금지; version 단조 증가 |
| Payment | `INITIATED → PENDING → CONFIRMED`, `FAILED`, `UNKNOWN → CONFIRMED/FAILED` | confirmed 승인 1개; UNKNOWN에서 신규 승인 retry 금지 |
| Sync | `RECEIVED → APPLIED`, `DUPLICATE`, `GAP`, `CONFLICT` | lastAckedSequence 감소 금지 |
| Reconciliation case | `OPEN → INVESTIGATING → AUTO_REPAIRED/MANUAL_REVIEW → RESOLVED` | evidence·rule version·audit 없는 강제 수정 금지 |

PG timeout은 transport 관찰의 실패이지 PG outcome의 실패가 아니다. PG reference와 idempotency key로 lookup을 우선하고, 확인 불가한 동안 `UNKNOWN`을 유지한다. reconciliation은 POS/server/PG의 근거를 비교하고 자동 조치가 안전한 규칙과 operator 승인이 필요한 규칙을 구분한다.

## 6. MSA Evolution Architecture

### 6.1 분리 원칙

MSA는 목표가 아니라 독립적인 change/scaling/availability/security requirement에 대한 비용 있는 해법이다. 현재 모듈을 배포 단위로 바꾸는 것은 아래의 증거와 준비가 모두 있을 때만 ADR로 승인한다.

| 분리 신호 | 필요한 증거 | 선행 조건 |
|---|---|---|
| 독립 배포 빈도 | 한 모듈의 배포가 다른 모듈 release를 반복적으로 막음 | versioned API/event, contract test, canary/rollback |
| 독립 확장 | workload/profile이 달라 같은 pod scaling이 낭비/병목 | per-module SLI, capacity data, rate limit/backpressure |
| 장애 격리 | 한 기능의 failure가 다른 핵심 흐름을 반복 저해 | timeout/bulkhead, fallback, ownership/on-call |
| 데이터 자율성 | schema change·read model 요구가 지속 충돌 | service-owned store, migration/replication plan, no cross-db join |
| 팀 자율성 | 명확한 장기 owner가 있고 변경 경로가 독립적 | API owner, runbook, SLO/error budget, cost model |

처음 후보는 payment보다 `fulfillment`(KDS/receipt) 또는 read-only projection처럼 결제 핵심 불변식을 공유하지 않는 영역이다. `payment`는 PG/정합성/UNKNOWN lifecycle의 복잡도가 독립 운영 이득을 넘어설 때만 분리한다.

### 6.2 Extraction playbook

1. Modulith 모듈 API와 event를 안정화하고, internal import/table access를 0으로 만든다.
2. 기존 앱 내부에서 event-driven projection 또는 facade를 먼저 사용해 remote boundary의 latency/failure를 시뮬레이션한다.
3. 새로운 service-owned database와 migration ownership을 만든다. 다른 서비스는 DB credential이나 join 권한을 갖지 않는다.
4. outbox로 compatibility event를 dual-publish하고 새 service는 shadow-consume/read-compare 한다.
5. SLO, error budget, trace resource attributes, dashboard, DLQ/replay runbook, on-call owner를 새 service에 이관한다.
6. traffic를 점진적으로 전환하고 결과/lag/mismatch를 비교한다. rollback window가 지난 뒤에만 old path를 제거한다.

이 과정에서 distributed transaction을 만들지 않는다. cross-service business workflow는 local transaction + event + compensation/reconciliation으로 설계한다. 분산 saga가 필요하다면 orchestration ownership, timeout, compensation idempotency, audit, operator escalation을 명시한다.

### 6.3 Reuse without coupling

- 재사용 대상은 protocol contract, observability convention, security library, test fixture처럼 stable한 platform capability다.
- `Money`, `Order`, `Payment` 같은 business domain model을 여러 service가 공유 라이브러리로 참조하지 않는다. 각 context에서 local type으로 mapping한다.
- platform starter는 versioned BOM과 compatibility matrix를 제공하되 runtime/framework upgrade를 서비스의 독립 release와 분리한다.
- 공통 data warehouse/read model은 event로 구축한다. operational query를 위해 source DB를 직접 읽지 않는다.
- API gateway/BFF는 composition과 auth edge를 담당하되, business invariant를 소유하지 않는다.

## 7. NFR and observability

| 영역 | 목표 | 측정 |
|---|---|---|
| Availability | 주문 API 99.9%, 단말 offline ordering은 네트워크와 독립 | synthetic + error budget |
| Latency | 주문 p95 < 200ms, PG 제외 결제 처리 p95 < 150ms | histogram/load test |
| Sync | 500 operation batch p95 < 2s | batch test |
| Durability | 승인 완료 주문/결제 영구 유실 0 | crash/restart test |
| Convergence | fault 종료 뒤 mismatch 0 | reconciliation report |
| Scale baseline | 1,000 terminals, peak 500 RPS | representative load test |

OpenTelemetry/Micrometer, structured logs, trace context propagation, Prometheus/Loki/Tempo/Grafana를 기본 파이프라인으로 둔다. Spring Modulith observability를 활성화해 module invocation과 event publication의 spans/metrics도 수집한다.

필수 domain signal은 `payment_unknown_total`, `payment_reconciliation_age_seconds`, `sync_gap_total`, `sync_convergence_seconds`, publication/outbox oldest age, consumer lag, DLQ count다. `orderId`, `paymentId`, `terminalId`는 Prometheus label로 쓰지 않고 trace/log search attribute로 남긴다. 모든 alert는 dashboard → trace → log → runbook → replay/reconcile action으로 연결한다.

## 8. Architecture verification scenarios

- PG 승인 성공 직후 HTTP response 유실: 같은 idempotency key가 기존 outcome을 반환하며 신규 승인이 없다.
- POS 30분 offline 후 500 operation: duplicate/gap/partial result가 명시되고 유효 operation이 수렴한다.
- listener/outbox relay/consumer가 DB commit 뒤 종료: 재처리돼도 side effect 1회이며 영구 유실이 없다.
- 낮은 aggregate version event가 늦게 도착: projection이 최신 상태를 덮어쓰지 않는다.
- Redis/telemetry exporter 장애: correctness path는 살아 있고 degradation telemetry가 별도로 탐지된다.
- MSA extraction shadow phase: old/new read model, event lag, mismatch, API compatibility가 기준치를 만족한다.

## 9. References

- [Spring Modulith — fundamentals and application modules](https://docs.spring.io/spring-modulith/reference/index.html)
- [Spring Modulith — event publication and externalization](https://docs.spring.io/spring-modulith/reference/events.html)
- [Spring Modulith — production-ready observability](https://docs.spring.io/spring-modulith/reference/production-ready.html)
- [AWS Prescriptive Guidance — transactional outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)
- [OpenAPI Specification](https://spec.openapis.org/oas/latest.html) and [AsyncAPI documents](https://www.asyncapi.com/docs/tutorials/getting-started/asyncapi-documents)
- [RFC 9562 — UUID](https://www.rfc-editor.org/rfc/rfc9562.html), [PostgreSQL UUID](https://www.postgresql.org/docs/current/datatype-uuid.html), [PostgreSQL numeric](https://www.postgresql.org/docs/current/datatype-numeric.html)
- [Java BigDecimal API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html), [Hibernate UUID generation](https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html), [UUID Creator](https://github.com/f4b6a3/uuid-creator)
- [Microsoft — data sovereignty per microservice](https://learn.microsoft.com/en-us/dotnet/architecture/microservices/architect-microservice-container-applications/data-sovereignty-per-microservice)
- [OpenTelemetry semantic conventions](https://opentelemetry.io/docs/specs/semconv/) and [messaging conventions](https://opentelemetry.io/docs/specs/semconv/messaging/messaging-spans/)
