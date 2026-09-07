# ResilientPOS — Development Guide

> 문서 버전: v2.0 · 기준일: 2026-09-03  
> 목적: 구현자가 같은 경계·계약·신뢰성 규약을 일관되게 적용하도록 한다.

## 1. Repository and package layout

```text
resilient-pos/
├── pos-server/
│   ├── src/main/kotlin/com/resilientpos/
│   │   ├── ResilientPosApplication.kt
│   │   ├── store/           # public API + internal/
│   │   ├── terminal/
│   │   ├── order/
│   │   ├── payment/
│   │   ├── sync/
│   │   ├── reconciliation/
│   │   ├── fulfillment/
│   │   ├── foundation/      # tiny framework-free stable value types only
│   │   └── platform/        # JPA, OTel, Kafka, HTTP adapters/configuration
│   ├── src/main/resources/db/migration/
│   └── src/test/kotlin/
├── pos-client/              # local durable queue, terminal sequence, client UUID v7
├── contracts/
│   ├── openapi/
│   └── asyncapi/
├── observability/
├── load-test/
├── chaos/
└── infra/
```

초기에는 `pos-server` 하나의 Gradle project다. 패키지 경계가 안정되고 독립 build/test/dependency 또는 external contract artifact가 실제로 필요할 때만 Gradle subproject를 추가한다. Gradle 분할 자체가 domain boundary의 증거는 아니다.

### 1.1 Public API rule

다른 모듈은 아래만 import할 수 있다.

```text
payment/
├── PaymentManagement.kt     # public facade
├── PaymentId.kt
├── PaymentConfirmed.kt      # public event contract
└── internal/
    ├── PaymentService.kt
    ├── PaymentJpaRepository.kt
    ├── PgClient.kt
    └── PaymentEntity.kt
```

`payment.internal.*`, JPA entity, repository, controller, infrastructure adapter를 타 모듈에서 참조하면 CI 실패다. cross-module join도 금지다. 기능이 다른 module state를 바꿔야 하면 public facade 또는 domain event를 사용한다.

## 2. Dependency governance and configuration

### 2.1 핵심 원칙 — package module과 Gradle module을 혼동하지 않는다

초기 `order`, `payment` 등은 **Spring Modulith application module(패키지 경계)** 이다. 이들은 자체 `build.gradle.kts`를 가지지 않으며, 각 패키지에 Spring Boot starter를 추가하는 개념도 없다. 실제 classpath와 starter의 소유자는 하나의 실행 조립 지점인 `:pos-server:app`이다.

나중에 Gradle subproject로 분리하더라도, "도메인 하나 = starter 하나"는 금지한다. starter는 편의를 위해 넓은 transitive dependency 집합을 가져오므로 **실행 애플리케이션(`app`)만** 가질 수 있다. 순수 domain/application 모듈은 Spring Boot, JPA, Web, Kafka, OTel exporter에 의존하지 않는다.

| 레이어/향후 Gradle project | 허용 의존성 | 금지/주의 |
|---|---|---|
| `foundation` | Kotlin/JDK value type, 작은 검증 라이브러리 | Spring, JPA, Jackson, Web, Kafka, logging backend |
| `*:domain` | `foundation`와 domain-local code | Spring annotations, repository, HTTP/DB/broker client |
| `*:application` | own domain, 다른 모듈의 **public contract** | Boot starter, JPA entity/repository, adapter implementation |
| `*:adapter-persistence-jpa` | JPA/Spring Data의 필요한 API와 own application/domain | `spring-boot-starter-data-jpa`를 module마다 선언하지 않음 |
| `*:adapter-web` | 필요한 Spring Web API와 own application | embedded server/starter를 module마다 선언하지 않음 |
| `*:adapter-kafka` | `spring-kafka`, Modulith Kafka externalization — 외부 consumer가 생긴 뒤 | order/payment/domain에 Kafka dependency 전파 |
| `:app` (composition root) | 필요한 Boot starter, runtime driver/exporter, configuration | business rule/aggregate 구현, 범용 `common` 역할 |

독립 subproject에는 Gradle `java-library`의 `implementation`을 기본으로 쓴다. dependency의 public type이 해당 module의 public method/field/signature에 실제로 노출될 때만 `api`를 허용한다. 이 규칙은 내부 dependency가 consumer compile classpath로 새는 것을 막는다.

### 2.2 App composition root baseline

다음 선언은 **`app` 한 곳**의 최소 baseline이다. `spring-boot-starter-data-jpa`는 업무 JPA persistence가 필요해서, `spring-modulith-starter-jpa`는 Event Publication Registry의 JPA 저장소가 필요해서 각각 남긴다. 반면 `spring-modulith-starter-insight`는 Actuator와 Modulith observability를 포함하므로 `spring-boot-starter-actuator`를 별도 선언하지 않는다.

```kotlin
// :app/build.gradle.kts — package module이나 domain subproject에 복사 금지
plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management) // Spring Boot BOM import
}

dependencies {
    implementation(platform(libs.spring.modulith.bom))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.modulith:spring-modulith-starter-jpa")
    implementation("com.github.f4b6a3:uuid-creator:6.1.1")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.springframework.modulith:spring-modulith-starter-insight")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
}
```

추가 조건:

- Kafka는 Phase 3에서 **externalized event와 독립 consumer가 승인된 뒤에만** `:adapter-kafka`/`:app`에 넣는다. 현재 domain/application과 client에는 Kafka client가 없다.
- Redis는 cache/rate-limit이라는 실제 use case가 승인된 뒤에만 app adapter에 추가한다. correctness 경로는 Redis에 의존하지 않는다.
- OpenTelemetry exporter, Loki appender, test container 등은 필요한 runtime/test configuration에만 둔다. domain이나 production compile classpath에 넣지 않는다.
- 운영 코드가 starter의 transitive library API를 직접 import한다면 해당 minimal library를 해당 adapter에 명시적으로 선언한다. 우연한 transitive dependency에 기대지 않는다.

### 2.3 Version, scope, and review gates

1. Spring Boot BOM과 Spring Modulith BOM은 root의 version catalog/platform 한 곳에서 관리한다. managed dependency에는 개별 version을 반복하지 않는다.
2. external library 버전 override는 security/compatibility 사유와 test evidence가 있을 때만 ADR 또는 PR에 기록한다.
3. `runtimeOnly`는 JDBC driver, exporter처럼 컴파일 API가 필요 없는 runtime component에만 쓴다. `testImplementation`은 test fixture/test engine 전용이다.
4. dependency lockfile을 CI에 커밋하고, lockfile 변경은 dependency tree diff와 CVE/license review를 요구한다.
5. 새 dependency PR에는 "어느 Gradle project가 왜 필요한가, 더 작은 artifact가 있는가, 기존 starter가 이미 제공하는가, 제거 조건은 무엇인가"를 적는다.

권장 확인 명령:

```text
./gradlew :app:dependencies --configuration runtimeClasspath
./gradlew :app:dependencyInsight --dependency <artifact> --configuration runtimeClasspath
./gradlew :<module>:dependencies --configuration compileClasspath
```

CI는 최소한 (a) package/module architecture verification, (b) forbidden dependency/starter check, (c) resolved dependency tree/lockfile diff, (d) license·CVE scan을 통과해야 한다. dependency 수 자체를 KPI로 삼지 않는다. 필요한 기능을 지원되는 BOM 조합으로 정확히 선언하고, 중복·누출·사용하지 않는 runtime component를 제거하는 것이 목표다.

### 2.4 Architecture test

```kotlin
@Test
fun `application modules obey dependency rules`() {
    ApplicationModules.of(ResilientPosApplication::class.java).verify()
}
```

모듈 API에 `@NamedInterface` 또는 명시적 module metadata가 필요해질 만큼 public surface가 커지면 추가한다. `@ApplicationModuleTest`로 필요한 collaborator만 부트스트랩하는 module-level integration test를 쓴다.

### 2.5 Migration ownership

- Flyway/Liquibase migration 파일은 owning module의 prefix와 issue/ADR를 가진다. 예: `V014__payment_add_pg_reference.sql`.
- platform이 EPR/outbox schema bootstrap을 돕더라도, retention/archive migration과 access control은 명시적으로 관리한다.
- schema migration은 backward compatible expand → application dual-read/write → backfill → contract switch → later cleanup 순서를 따른다.
- MSA 전환 중에는 consumer service의 DB migration과 producer event migration을 같은 release에 묶지 않는다. 둘 모두 old/new version을 읽는 기간을 둔다.

## 3. Domain implementation rules

도메인별 aggregate, command/query catalogue, event-storming flow와 Facade/Service의 상세 책임은 [Domain Development Blueprint](05_Domain_Development_Blueprint.md)를 따른다.

### 3.1 IDs

```kotlin
interface IdentifierGenerator {
    fun next(): UUID
}

class UuidV7IdentifierGenerator : IdentifierGenerator {
    override fun next(): UUID = UuidCreator.getTimeOrderedEpoch()
}
```

- application service/factory가 persistence 이전에 ID를 만든다. `@GeneratedValue`, DB `IDENTITY`, Hibernate 기본 UUID v4 generator는 domain ID에 사용하지 않는다.
- `UUID`는 PostgreSQL native `uuid`에 매핑한다. `VARCHAR(36)`와 string parse를 기본 저장 방식으로 쓰지 않는다.
- offline terminal은 별도의 표준 UUID v7 generator를 구현/검증한다. server library implementation을 wire protocol 계약으로 만들지 않는다.
- `operationId`의 중복 방지와 `terminalSequence`의 누락 탐지는 서로 대체하지 않는다. 둘 다 durable storage에 기록한다.

### 3.2 Money

```kotlin
data class Money private constructor(
    val amount: BigDecimal,
    val currency: Currency,
) {
    companion object {
        fun of(raw: String, currency: Currency): Money {
            val canonicalScale = CurrencyScalePolicy.scaleOf(currency)
            return Money(
                raw.toBigDecimal().setScale(canonicalScale, RoundingMode.UNNECESSARY),
                currency,
            )
        }
    }
}
```

- raw `Double`/`Float`, `BigDecimal(double)`, unqualified `setScale()`와 암묵적 rounding을 금지한다.
- calculation scale(세금/할인)과 charge/receipt final scale을 분리한다. final rounding은 store/country rule로 명시한다.
- JPA adapter는 amount `NUMERIC(19,4)`와 currency code를 mapping한다. API/event serializer는 decimal string을 사용한다.
- `Money`는 immutable이다. currency mismatch나 column precision overflow는 validation/domain error로 반환한다.

### 3.3 Aggregate, policy, and command guard

- aggregate는 자신의 invariant와 상태 전이만 변경한다. controller/service가 enum을 직접 set하지 않는다.
- 금액, 할인, 반올림, 상태 전이 가능 여부처럼 데이터에 가까운 검증·가공은 immutable Value Object 또는 `*Policy` data class에 둔다. Service가 DTO를 조립하며 규칙을 중복하지 않는다.
- `*Service`는 `Checkout`, `AcceptSyncOperation`, `ResolvePayment`처럼 비즈니스 행위를 표현하고 Aggregate/Policy를 조율한다. 하나의 Aggregate에 자연스럽게 둘 수 있는 계산·검증은 Service로 빼지 않는다.
- 위험한 transition(결제 확정, 취소, 금액 변경)은 optimistic lock/version을 사용한다.
- payment idempotency record에는 key, request payload hash, result/status, expiry policy를 저장한다. 같은 key와 다른 hash는 `409 IDEMPOTENCY_CONFLICT`다.
- PG timeout이면 `UNKNOWN` 상태와 provider reference를 저장하고 lookup/reconciliation을 시작한다. 새로운 authorize 요청으로 retry하지 않는다.
- external request timeout/retry는 idempotency key와 bounded attempt/deadline이 없으면 금지한다.

## 4. API and event delivery workflow

외부 provider adapter와 resilience policy의 구현 기준은 [External Integration Strategy](04_External_Integration_Strategy.md)를 따른다.

### 4.1 Contract repository workflow

1. OpenAPI/AsyncAPI spec와 example payload를 먼저 바꾼다.
2. compatibility check와 producer/consumer contract test를 실행한다.
3. server/client code를 generation 또는 local mapping으로 구현한다.
4. deployment 전 canary/shadow consumer에서 representative payload를 검증한다.
5. deprecation window 종료 전에는 removal/rename을 하지 않는다.

모든 public request/event에는 `schemaVersion`, stable ID, correlation/causation information을 정의한다. external event는 module internal event를 그대로 serialize하지 말고 dedicated integration DTO로 매핑한다.

### 4.2 Event publishing

```text
@Transactional command handler
  ├─ change one aggregate
  ├─ publish immutable fact
  └─ commit
       └─ Event Publication Registry records listener completion
```

- `@ApplicationModuleListener` handler는 replay-safe 해야 한다.
- handler side effect가 DB write면 `(consumer, event_id)` unique row를 same local transaction에 기록하거나 natural key upsert를 쓴다.
- retry는 exponential backoff + bounded attempts + classification을 사용한다. non-retryable schema/validation failure는 DLQ/reconciliation으로 간다.
- incomplete publication/outbox는 dashboard와 alert 대상이다. newest count가 아니라 `oldest age`를 주요 paging signal로 쓴다.

Kafka를 붙이는 시점에는 `@Externalized`를 명시적으로 선택하고 topic/key/schema/retention/DLQ/owner를 AsyncAPI에 등록한다. broker 실패, relay crash, duplicate delivery, out-of-order delivery를 local test 환경에서 재현한다.

### 4.3 Sync handler

```text
validate terminal credential
  → read durable lastAckedSequence
  → deduplicate operation ID
  → evaluate sequence/gap/state precondition
  → apply one safe command transactionally
  → return per-item result + watermark/missing range
```

500개 batch는 한 거대 transaction으로 처리하지 않는다. item 또는 작은 chunk 단위로 안전하게 commit해 partial result를 명확히 하고, client는 accepted operation만 queue에서 제거한다.

## 5. Observability implementation conventions

### 5.1 Required attributes

구조화 log와 trace에 `traceId`, `spanId`, `correlationId`, `causationId`, `storeId`, `terminalId`, `orderId`, `paymentId`, `eventId`, `stateFrom`, `stateTo`, `errorCode`, `retryable`을 상황에 맞게 담는다. 카드 번호, 결제 토큰, 고객 PII, raw request body는 금지한다.

`orderId`/`paymentId`/`terminalId`는 high-cardinality Prometheus label이 아니다. log/trace attribute 또는 searchable field로 쓴다. metric dimension에는 low-cardinality service/module/event type/status/error class만 사용한다.

### 5.2 Required metrics and dashboards

| Area | Signal |
|---|---|
| API | RED(request rate, errors, duration) |
| Payment | attempts, `UNKNOWN` rate/age, idempotency hit/conflict, PG latency/error |
| Sync | batch size, gap count, backlog, convergence duration |
| Event | publication/outbox oldest age, retry attempts, lag, DLQ count |
| Runtime | connection pool, lock wait, JVM/GC, CPU/memory, pod restart |
| Telemetry | collector queue, dropped spans/logs, exporter failures |

모든 alert에는 Grafana dashboard link, trace/log query, 최근 deployment, runbook, 안전한 recovery entry point가 있다. OTel Collector/exporter 장애는 주문 요청을 실패시키지 않아야 하며, telemetry drop 자체를 관찰한다.

## 6. Test and chaos matrix

| Level | Required evidence |
|---|---|
| Unit | state transition, Money scale/rounding, idempotency hash conflict, UUID version/format |
| Module integration | Modulith verification, JPA constraint, EPR publication/retry, optimistic locking |
| Contract | OpenAPI/AsyncAPI compatibility, Fake PG callback/lookup, POS payload examples |
| E2E | online payment, offline sync, response loss, cancellation, reconciliation |
| Load | 500 RPS, 1,000 terminals, 500-item sync batch, UUID v4 vs v7 insertion comparison |
| Chaos | network partition, process kill, duplicate/out-of-order event, Redis/Kafka/telemetry failure |

### 6.1 Mandatory failure tests

| Fault | Pass condition | Required observable proof |
|---|---|---|
| DB commit 뒤 payment HTTP response drop | same idempotency key returns original outcome; no second approval | trace of timeout and idempotency-hit metric |
| POS offline 30min / 500 ops | durable queue survives; no silent gap; final mismatch 0 | backlog/convergence dashboard |
| listener/relay kill | restart/resubmit; no permanent loss | oldest age alert then drain |
| consumer commit 뒤 ACK kill | duplicate delivery but one side effect | processed-event record and event trace |
| PG timeout with actual approval | UNKNOWN then lookup/reconcile to final state | UNKNOWN age, reconciliation case |
| Redis/Collector outage | correctness continues | fallback/drop metrics and alert |

테스트 결과는 총 요청 수, injected failure 비율, duplicate prevented count, real duplicate side effect count, UNKNOWN mean recovery time, final mismatch count, p50/p95/p99, MTTD/MTTR로 남긴다.

## 7. Delivery and operations checklist

### 7.1 Pull request checklist

- [ ] owning module 밖의 `internal`/repository/entity import가 없다.
- [ ] DB/API/event contract 변경은 backward compatibility와 migration plan을 설명한다.
- [ ] 새 async side effect는 idempotency key와 retry/DLQ/reconciliation path를 가진다.
- [ ] 새 metric은 cardinality review를 통과하고 alert/runbook 필요성을 검토했다.
- [ ] PII/payment secret가 telemetry와 example fixture에 없다.
- [ ] failure test와 success test를 함께 추가했다.

### 7.2 Release checklist

- [ ] Flyway/Liquibase expand migration이 먼저 배포되었다.
- [ ] OpenAPI/AsyncAPI compatibility CI와 contract tests가 통과했다.
- [ ] dashboard, alert, runbook, rollback step이 release artifact에 연결되었다.
- [ ] outbox/EPR backlog, consumer lag, DLQ가 정상 범위다.
- [ ] canary에서 idempotency/sync/reconciliation synthetic probe가 통과했다.
- [ ] MSA extraction이면 old/new path shadow comparison과 rollback window가 준비되었다.

## 8. Implementation milestones / Definition of Done

| Milestone | Deliverable | DoD |
|---|---|---|
| M1 | domain, DB, API, Fake PG | online order/payment and state transition tests pass |
| M2 | idempotency, UNKNOWN, reconciliation | response loss test has zero duplicate approval |
| M3 | offline sync | 30min offline + 500 ops converges |
| M4 | external event path | relay/consumer kill has no permanent loss |
| M5 | observability/deployment | four dashboards, SLO alerts, rolling shutdown test |
| M6 | evidence report | 100k+ fault run and reproducible README |

The project is complete only when every P0 acceptance criterion passes, fault-injection shows zero duplicate orders/payments and zero final mismatch, and an arbitrary Sev-1/2 scenario can be detected in 60 seconds, diagnosed from a single ID in 5 minutes, safely recovered, and verified in telemetry.

## 9. Reference set

설계 근거와 최신 링크는 [시스템 설계의 References](02_System_Design.md#9-references)에 집중 관리한다. 의존성 규칙은 [Gradle Java Library의 `api`/`implementation` 분리](https://docs.gradle.org/current/userguide/java_library_plugin.html), [Spring Boot Gradle dependency management](https://docs.spring.io/spring-boot/gradle-plugin/managing-dependencies.html), [Spring Modulith starter 구성](https://docs.spring.io/spring-modulith/reference/appendix.html), [Spring Modulith Insight starter](https://docs.spring.io/spring-modulith/reference/production-ready.html)를 따른다. 구현 시에는 [Spring Modulith events](https://docs.spring.io/spring-modulith/reference/events.html), [OpenAPI](https://spec.openapis.org/oas/latest.html), [AsyncAPI](https://www.asyncapi.com/docs/tutorials/getting-started/asyncapi-documents), [OpenTelemetry semantic conventions](https://opentelemetry.io/docs/specs/semconv/), [AWS transactional outbox guidance](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)를 함께 확인한다.
