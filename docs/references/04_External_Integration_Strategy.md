# ResilientPOS — External API Integration & Resilience Strategy

> 문서 버전: v1.0 · 기준일: 2026-09-03  
> 목적: 외부 API를 포트폴리오의 신뢰성 검증 대상으로 도입하되, 외부 장애가 주문·결제 정확성을 훼손하지 않게 한다.

## 1. Decision summary

외부 API는 "연동 개수"가 아니라 업무 가치와 실패 모델을 기준으로 선택한다. ResilientPOS의 기본 권장 순서는 다음과 같다.

| 우선순위 | 후보 | 가치 | 비용/무료 조건 | 결정 |
|---|---|---|---|---|
| P0 | Local Fake PG + Toxiproxy/WireMock | 결정론적 timeout, 응답 유실, 중복 callback, process kill 재현 | 완전 로컬/무료 | **항상 유지** |
| P1 | Toss Payments 테스트 환경 | 실제 외부 payment 승인·조회·취소·test error/header·webhook 경계 검증 | 테스트 키에서는 가상 승인, 실제 출금 없음 | **첫 외부 연동 권장** |
| P1 | Slack incoming webhook / Alertmanager | 장애 탐지 결과를 운영 채널로 전달 | Slack workspace에서 webhook 설정 | **운영 알림용 권장** |
| P2 | Frankfurter FX API | 다통화 견적/표시와 cache·stale fallback 실험 | API key/계정/일일 quota 없음; abuse rate limit 존재 | 다통화 기능이 생길 때만 |
| P3 | PortOne sandbox | 다중 PG abstraction/routing을 보여줄 때 | 지원 수단별 sandbox 제공 | Toss direct와 **동시에** 붙이지 않고 대안으로 선택 |

첫 구현에서 Fake PG를 Toss sandbox로 교체하지 않는다. Fake PG는 재현성과 chaos test를 담당하고, Toss adapter는 실제 외부 boundary 검증을 담당한다. 이 둘을 같은 `PaymentGateway` port의 서로 다른 adapter로 둔다.

## 2. Candidate integrations

### 2.1 Payment gateway — Toss Payments test environment (recommended)

토스페이먼츠는 테스트 키에서 가상 승인을 제공하며 실제 결제수단에서 금액을 차감하지 않는다. 테스트용 키와 라이브 키를 섞으면 오류가 나므로 environment 별 secret/endpoint/configuration을 엄격히 분리한다. 테스트 환경의 오류 재현 기능을 이용해 timeout/거절/invalid response와 reconciliation 경로를 검증한다.

**범위:** `authorize`, `get payment`, `cancel`, callback/webhook receipt. 실제 매장 운영이나 라이브 키 사용은 이 프로젝트의 범위 밖이다.

**개인 개발자 범위:** 이 프로젝트는 사업자 등록이나 전자결제 계약을 전제하지 않는다. 개발자센터 가입과 문서/개발 연동 체험 상점에서 제공하는 **test-only** 흐름으로 승인·조회·취소·오류·웹훅 경계를 검증한다. 라이브 상점, 실제 고객, 실제 정산, 사업자/MID 기반 운영 설정은 구현·데모·문서의 범위 밖이다.

**무료 시작 방법:** 개발자센터에 이메일로 가입하고 계약 전 이용 가능한 테스트 키/체험 상점 또는 공식 문서의 테스트 연동 흐름을 사용한다. secret은 `.env`/secret manager에만 저장하고, Git·클라이언트·로그·trace·테스트 fixture에는 절대 넣지 않는다. 토스 문서의 공개 예제 키도 프로젝트 설정으로 복사하지 않는다.

#### 2.1.1 공식 콘솔 발급 및 테스트 상점 준비

| 순서 | 개발자센터/콘솔에서 할 일 | ResilientPOS에서 확인할 것 |
|---|---|---|
| 1 | [토스페이먼츠 개발자센터](https://developers.tosspayments.com/)에 이메일로 가입·로그인한다. | 팀/개인별 계정을 공유하지 않고 권한을 부여한다. |
| 2 | **내 개발정보 → API 키**에서 계약 전 이용 가능한 테스트 키를 확인한다. 범위가 제한되면 개발 연동 체험 상점/공식 문서의 테스트 연동 흐름으로 먼저 검증한다. | 키 prefix가 `test_`인지 검사한다. 라이브 키는 이 프로젝트의 profile에서 거절한다. |
| 3 | POS simulator의 결제 UX를 **결제창형 또는 주문서형 결제위젯**으로 하나 선택한다. 새 구현은 V2 SDK를 기본으로 한다. | React/PWA에는 client key만 전달한다. secret key는 서버에만 둔다. |
| 4 | 테스트 결제 UI/지원 결제수단을 선택한다. own merchant/MID가 필요한 설정, 결제 UI admin customization, 라이브 기능은 건너뛴다. | client/secret 키가 같은 테스트 세트인지 시작 시 검증한다. |
| 5 | **내 테스트 결제내역**에서 가상 승인·취소 결과를 확인한다. 접근 가능한 로그 범위가 제한되면 server audit/trace를 주 증거로 둔다. | 서버 DB의 `paymentKey`, `orderId`, amount, status와 한 건씩 대조한다. |
| 6 | webhook을 지원/설정할 수 있는 test context에서만 URL을 등록한다. 로컬 테스트는 public HTTPS tunnel을 이용한다. | tunnel URL은 test-only이며, 운영 설정/문서에 영구 URL로 남기지 않는다. |

테스트 키를 사용한 결제는 가상 승인이라 실제 결제수단에서 출금되지 않는다. 개인 개발자 프로젝트에서는 `live` profile 자체를 제공하지 않고, CI도 `live_` key/production endpoint를 탐지하면 실패해야 한다. 계약 전과 후에 노출되는 테스트 키·결제수단에는 차이가 있을 수 있으므로, 현재 console에 표시되는 범위를 공식 API 키/환경 가이드에서 확인한다.

#### 2.1.2 Local configuration and secret policy

```text
# local-only .env (commit 금지; 값은 예시가 아니라 실제 발급 키로 주입)
TOSS_MODE=test
TOSS_CLIENT_KEY=<test client key>
TOSS_SECRET_KEY=<test secret key>
TOSS_SUCCESS_URL=http://localhost:3000/payments/success
TOSS_FAIL_URL=http://localhost:3000/payments/fail
TOSS_WEBHOOK_URL=https://<temporary-public-tunnel>/webhooks/toss
```

앱은 boot 시 다음을 fail-fast 검증한다.

1. `TOSS_MODE=test`이면 client/secret key 모두 `test_` prefix여야 하고, live key 또는 비어 있는 키면 기동을 거절한다.
2. `TOSS_MODE=live`는 이 개인 포트폴리오 repository에서 지원하지 않는다. 발견 시 모든 환경에서 기동/배포를 거절한다.
3. client key는 browser에 전달될 수 있지만 secret key는 API server의 secret store에서만 읽는다. Basic 인증용 secret과 trailing `:`의 base64 결과도 로그에 남기지 않는다.
4. `successUrl`/`failUrl`은 allowlist된 origin만 사용한다. webhook URL은 HTTPS 공개 주소여야 한다. 로컬에서는 [공식 webhook 가이드](https://docs.tosspayments.com/guides/v2/webhook)의 tunnel 방식으로만 테스트한다.

#### 2.1.3 Recommended end-to-end payment flow

```text
1. POS → ResilientPOS: create payment intent
   Server: Order/Payment(PENDING) + stable orderId + local idempotency record persist

2. POS browser → Toss SDK: requestPayment(client key, orderId, amount, successUrl, failUrl)

3. successUrl → POS → ResilientPOS: paymentKey/orderId/amount 전달
   Server: DB의 orderId/amount/allowed state를 다시 검증

4. ResilientPOS → Toss confirm API: secret-key Basic auth, server-side only
   Server: 200 Payment response의 paymentKey/orderId 저장 후 local Payment 확정

5. ResilientPOS → module event/EPR → fulfillment/reconciliation
```

`successUrl` query parameter는 browser를 경유한 **신뢰할 수 없는 입력**이다. 서버는 confirm 호출 전에 local DB의 주문 존재 여부, `orderId`, 금액, currency, terminal/store authorization, payment 상태를 재검증한다. `failUrl`은 인증 실패/사용자 취소 경로이므로 confirm API를 호출하지 않고 오류 code를 표시한다.

토스 가이드에 따라 인증 성공 뒤 승인 API는 10분 안에 호출한다. 200 응답이 오면 `paymentKey`와 `orderId`를 필수로 저장하고, cancellation/query에 사용한다. `paymentKey`가 이미 존재하거나 local idempotency key와 payload hash가 맞지 않으면 신규 side effect를 만들지 않는다.

#### 2.1.4 Provider idempotency and test errors

ResilientPOS의 domain/payment ID는 UUID v7을 유지한다. 토스 POST 요청용 `Idempotency-Key`는 provider-scoped field로 별도 저장하며, 토스의 권장 형식과 유효 기간을 따르는 secure-random UUID v4를 사용한다. 이 키는 같은 **외부 요청**의 재전송에만 재사용하며, 새로운 business payment attempt에 재활용하지 않는다.

테스트 환경은 API당 분당 100건 제한이 있으므로, load test가 provider를 직접 두드리지 않게 한다. 대량/chaos 테스트는 Fake PG/WireMock을 사용하고 Toss test 호출은 contract/E2E suite에 제한한다. 테스트 전용 profile에서만 `TossPayments-Test-Code` header를 허용해 승인 거절 등 오류를 재현한다. 이 header는 client 입력이나 production request에서 절대 전달하지 않는다. 실제 고객/개인정보를 테스트 fixture나 포트폴리오 캡처에 사용하지 않는다.

#### 2.1.5 Query, cancel, and webhook checklist

- **Query:** confirmation 결과가 timeout/ambiguous이면 `paymentKey` 또는 provider reference로 조회한다. 조회 결과를 local state와 reconciliation evidence로 기록한다.
- **Cancel:** cancel은 별도 local `CANCEL_PENDING` attempt와 provider idempotency key를 기록한다. timeout이면 성공/실패로 단정하지 않고 query/reconciliation 한다.
- **Webhook:** 토스의 일반 결제 webhook에는 signature header가 없으므로 payload만으로 상태를 확정하지 않는다. 받은 `paymentKey`로 server-side Payment Query API를 다시 호출해 status/amount/order ID를 검증한 뒤 durable inbox와 payment state에 반영한다. signature가 제공되는 다른 webhook product는 해당 공식 HMAC 규칙을 적용한다.
- **Ordering/duplicates:** webhook, success redirect, poll 조회는 중복되거나 역순일 수 있다. `(provider, paymentKey/event identity)` unique inbox, aggregate version, local idempotency 규칙으로 한 번만 적용한다.
- **Console evidence:** 각 E2E 실행에서 개발자센터의 테스트 결제내역, ResilientPOS payment audit, trace ID를 연결해 screenshot 또는 test report로 남긴다.

### 2.2 Operations notification — Slack incoming webhook

Slack webhook은 JSON payload를 특정 URL에 POST해 메시지를 게시한다. 직접적인 주문 처리 경로에는 넣지 않는다. Alertmanager 또는 별도의 best-effort notification adapter가 `UNKNOWN` 급증, publication backlog, reconciliation 오래된 case를 알린다.

webhook 실패는 Slack 알림의 실패일 뿐 주문 실패가 아니다. Alertmanager의 retry/aggregation을 우선 활용하고, app에서 별도 전송이 필요하면 durable notification event와 제한된 retry를 사용한다.

### 2.3 FX reference rate — Frankfurter (optional)

Frankfurter는 key/account 없이 공개 환율/과거 환율을 제공한다. 다만 reference rate는 실시간 환전·결제 승인 가격이 아니다. 다통화 견적이나 영수증 표시 기능을 시작할 때만 도입한다.

- 요청은 `base`, `quote`, `rate date/provider`까지 좁혀 호출한다.
- historical rate는 immutable이므로 영구 cache 가능하다. latest rate도 공급자 갱신 주기에 맞춘 짧은 TTL을 둔다.
- 승인된 주문에는 계산 당시의 `rate`, `rateDate`, `provider`, `source`를 snapshot으로 저장한다. 나중의 latest rate로 과거 결제 금액을 재계산하지 않는다.
- stale cache fallback은 **표시/견적**에만 허용하며, 정산·환불의 금융 기준값을 조용히 대체하지 않는다.

### 2.4 PortOne sandbox (alternative, not an extra default)

여러 PG 또는 결제수단의 routing을 비교하고 싶다면 PortOne sandbox를 사용할 수 있다. 그러나 Toss direct adapter와 PortOne adapter를 동시에 첫 목표로 삼으면 결제 lifecycle보다 provider wiring이 커진다. 첫 외부 연동은 Toss direct 하나를 완성하고, multi-PG routing이 별도 learning goal이 될 때 PortOne을 대체/추가한다.

## 3. Integration architecture

### 3.1 Ports and adapters

```text
payment domain/application
       │  PaymentGateway port (provider-neutral command/result)
       ▼
payment adapter
  ├─ FakePaymentGateway            # deterministic local fault scenarios
  ├─ TossPaymentsGateway           # HTTPS, auth, provider DTO mapping
  └─ PortOneGateway (optional)     # later, isolated alternative
       │
platform/external-resilience
  ├─ timeout / retry / circuit / bulkhead / outbound rate limit
  ├─ trace, metrics, audit-safe logging
  └─ secret/configuration provider
```

```kotlin
interface PaymentGateway {
    suspend fun authorize(command: AuthorizePayment): GatewayAuthorization
    suspend fun findByMerchantReference(reference: MerchantPaymentReference): GatewayPaymentStatus
    suspend fun cancel(command: CancelPayment): GatewayCancellation
}
```

`PaymentGateway`와 command/result는 provider SDK DTO, HTTP status, secret, client library를 노출하지 않는다. Toss-specific request/response mapping, signature validation, error mapping은 `payment.internal.adapter.toss`에 둔다. provider ID는 payment record의 external reference로 저장하지만, provider status를 곧바로 domain state로 취급하지 않는다.

새 외부 supplier마다 범용 `ExternalApiClient`를 만들지 않는다. 각 adapter가 자신의 auth, timeout, error taxonomy, idempotency semantics, callback verification을 소유한다. 공통화가 필요한 경우에도 `HttpClientFactory`, telemetry convention, secret resolver처럼 기술적으로 안정된 platform capability만 공유한다.

### 3.2 Authorize state flow

```text
client command (stable idempotency key)
  → create/load local Payment attempt (PENDING)
  → bounded call to provider adapter
       ├─ explicit accepted/rejected result → persist final local fact
       ├─ transport timeout / ambiguous response → persist UNKNOWN
       └─ local validation/auth/config fault → fail fast; no provider retry
  → publish payment fact after local commit
  → UNKNOWN → provider lookup/reconciliation → CONFIRMED or FAILED
```

`authorize`의 timeout은 실패 확정이 아니다. POST를 맹목적으로 retry하면 이중 승인이 될 수 있다. 안정적인 merchant reference/idempotency key와 provider의 공식 idempotency/조회 보장이 모두 확인된 경우에만 재호출하고, 기본 경로는 provider lookup → reconciliation이다.

## 4. Resilience policy

### 4.1 Policy order

외부 호출마다 다음 순서를 적용한다. 모든 정책은 provider·operation별 configuration을 가지며 domain module에는 퍼지지 않는다.

```text
deadline/timeout → bulkhead → outbound rate limit → retry (safe operations only)
→ circuit breaker → provider call → classified outcome / fallback
```

| Policy | 목적 | Payment authorize | Lookup / FX / Slack |
|---|---|---|---|
| Deadline & timeout | 무한 대기와 thread starvation 방지 | connect와 total deadline을 명시; timeout은 `UNKNOWN` 후보 | 짧고 명시적 timeout |
| Bulkhead | 한 provider 지연이 server worker/pool을 점유하지 않게 함 | provider별 bounded concurrent calls, 짧거나 0인 queue | provider별 별도 제한 |
| Outbound rate limit | provider quota/429와 self-amplification 방지 | terminal/store 폭주를 제한; 거절은 재시도 예약/상태로 표현 | token bucket; `Retry-After` 존중 |
| Retry | 일시적 네트워크 실패 복구 | 기본 자동 retry 금지; lookup 우선 | idempotent GET은 bounded exponential backoff + jitter |
| Circuit breaker | 이미 망가진 provider로의 추가 부하/대기 차단 | open이면 새 승인 요청을 `PENDING/UNAVAILABLE`로 안내, 성공으로 위장 금지 | stale cache, async retry, 또는 drop-with-metric |
| Fallback | 사용자 영향 최소화 | `UNKNOWN` + reconciliation; **Fake success 금지** | 아래 provider별 fallback 적용 |

### 4.2 Starting values (measure and tune)

아래 값은 production claim이 아닌 load/chaos test의 출발점이다. provider SLA, real latency, worker pool, 결제 UX에 따라 ADR와 metric evidence로 조정한다.

| Setting | Toss payment | Frankfurter FX | Slack notification |
|---|---:|---:|---:|
| connect timeout | 1 s | 500 ms | 500 ms |
| call deadline | 3 s | 1 s | 1 s |
| bulkhead concurrency | 20 | 10 | 5 |
| queue | 0–small bounded | 10 | async bounded queue |
| circuit sample | last 20 calls | last 20 calls | last 20 calls |
| open threshold | failure/slow-call 50% | failure 50% | failure 50% |
| open duration / half-open probes | 30 s / 3 | 30 s / 2 | 60 s / 1 |

429는 application error가 아니라 provider backpressure signal이다. `Retry-After`가 있으면 존중하고, retry budget을 소진하면 background queue/next scheduled fetch로 넘긴다. circuit-open, bulkhead-rejected, rate-limited, timeout, provider-4xx, provider-5xx를 서로 다른 error code/metric으로 남긴다.

### 4.3 Operation-specific retry matrix

| Operation | Automatic retry | Fallback / next action |
|---|---|---|
| Payment `authorize` POST | No by default | local `UNKNOWN`, provider lookup, reconciliation; user에게 승인 확정이라고 말하지 않음 |
| Payment `GET`/status lookup | Yes: max 2, exponential backoff+jitter, overall deadline 내 | reconciliation queue; last known state에 `stale` 표기 |
| Payment cancel POST | No by default | cancel-pending/UNKNOWN, provider lookup, operator/reconciliation |
| Verified webhook receipt | Provider retry protocol에 맞춰 durable inbox 후 빠르게 ack | validation/signature failure는 quarantine/DLQ and alert |
| FX latest GET | Yes: max 2 | TTL cache, stale label, scheduled refresh |
| Slack notification POST | Async bounded retry | drop/aggregate with metric; alert delivery failure는 alternate channel 또는 dashboard로 노출 |

## 5. Rate limiting, security, and webhooks

### 5.1 Inbound versus outbound limits

- **Inbound:** API gateway/edge와 app에서 terminal credential/store 단위 token bucket을 적용한다. sync batch size(최대 500), payload size, concurrent sync도 제한한다. 정상 offline backlog가 단말을 영구 차단하지 않게 retry-after와 recovery window를 제공한다.
- **Outbound:** provider별 rate limiter/bulkhead를 둔다. inbound 한 요청이 provider retry storm으로 증폭되는 것을 막고, 429/timeout의 retry budget을 별도 관리한다.
- payment의 rate limit은 단순 429로 끝나면 안 된다. local payment state, idempotency result, 재조회 방법을 함께 반환해야 한다.

### 5.2 Secret and transport control

- test/live secret은 environment 별 secret manager 또는 로컬 `.env`에서 주입하고 source control에 저장하지 않는다.
- secret rotation, provider endpoint allowlist, TLS certificate validation, egress network policy, least-privilege deployment identity를 적용한다.
- request/response body는 card/secret/PII masking policy를 거친 뒤에만 log/trace한다. raw `Authorization` header와 webhook secret은 절대 기록하지 않는다.
- provider contract의 request ID/payment key를 local payment ID·trace ID와 매핑해 audit하되, sensitive reference의 retention/access scope를 정의한다.

### 5.3 Webhook receiver

```text
provider webhook
  → TLS endpoint + signature/timestamp verification
  → durable webhook inbox (event ID / provider payment key unique)
  → fast acknowledgement after durable acceptance
  → asynchronous idempotent processor
  → payment public event / reconciliation case
```

webhook은 신뢰할 수 없는 네트워크 입력이다. 각 provider의 signature 검증 방식, timestamp replay window, event ID deduplication을 adapter에 구현한다. webhook과 client response의 경합은 payment aggregate version/idempotency/reconciliation 규칙으로 해결하며, callback 도착 순서만 신뢰하지 않는다.

## 6. Dependency and rollout policy

외부 연동 도입이 domain/application classpath를 넓혀서는 안 된다.

| Need | Added where | Do not add |
|---|---|---|
| Toss HTTPS adapter | `payment:adapter-toss` + `app` wiring | domain/application/other modules |
| timeout/HTTP client | 해당 adapter; JDK `HttpClient` 또는 이미 채택한 Spring HTTP client 우선 | provider SDK를 모든 모듈에 전파 |
| circuit/retry/rate/bulkhead | `platform:external-resilience` 또는 해당 adapter | Spring Cloud starter를 모든 module에 일괄 추가 |
| Kafka / Slack notifier | external adapter가 실제 생길 때 | payment core 또는 POS client |
| testing | test fixture/Testcontainers/WireMock configuration | production implementation classpath |

Resilience4j를 선택한다면 필요한 primitives만 추가한다(`circuitbreaker`, `retry`, `ratelimiter`, `bulkhead`; metric bridge는 observability adapter). Spring Cloud CircuitBreaker starter는 Spring Cloud abstraction이 실제로 필요한 경우에만 `app`/external adapter에 도입하며, 그 이유와 transitive tree를 PR에 남긴다. HTTP client 자체는 JDK/Spring Boot 기존 선택지를 먼저 사용해 별도 SDK dependency를 피한다.

롤아웃은 `Fake → shadow read/contract test → Toss test environment → chaos/load → test-only end-to-end demo` 순이다. feature flag로 adapter selection을 제어하되 flag가 domain state를 바꾸지 않게 한다. test key/live key 혼용, live callback을 test environment로 수신하는 배포는 release gate에서 차단한다.

## 7. Observability and operational runbook

### 7.1 Required telemetry

| Signal | Low-cardinality dimensions | Use |
|---|---|---|
| `external_api_calls_total` | provider, operation, outcome, http status class | success/error/429/circuit-open 추세 |
| `external_api_duration` | provider, operation | provider latency/slow-call breaker |
| `external_api_bulkhead_rejected_total` | provider, operation | self-protection saturation |
| `external_api_rate_limited_total` | provider, direction | inbound/outbound pressure |
| `payment_unknown_age_seconds` | provider | ambiguity/reconciliation SLO |
| `webhook_inbox_pending_age_seconds` | provider | callback processing health |

trace에는 `server.address`, `http.request.method`, provider/operation, local payment ID, correlation ID, idempotency decision, circuit state를 남긴다. API secret·authorization header·full body는 제외한다. provider request ID는 mask/access policy가 승인된 경우에만 log/trace attribute로 쓴다.

### 7.2 Required runbooks

1. **Toss timeout/5xx/circuit open:** provider status와 breaker/bulkhead metric 확인 → 신규 payment를 정확히 `UNKNOWN/PENDING`으로 안내 → lookup/reconciliation backlog 확인 → half-open recovery 검증.
2. **Provider 429:** `Retry-After`, outbound limiter, terminal burst, retry queue 확인 → rate를 낮추고 backlog를 drain → 정상화 후 limiter configuration 재검토.
3. **Webhook validation/DLQ:** signature/timestamp/key rotation·inbox dedup 확인 → 안전한 replay/reconcile → duplicate side effect 0 확인.
4. **FX stale rate:** cache age/provider/date 확인 → 견적에 stale label 적용 → settlement/receipt snapshot은 변경하지 않음.
5. **Slack failure:** Alertmanager delivery metric과 webhook secret/config 확인 → app correctness에 영향이 없음을 확인 → alternate dashboard/notification path로 전환.

## 8. Acceptance tests

- Toss test key로 authorize, lookup, cancel 그리고 제공되는 error simulation을 검증한다. test secret이 log/CI artifact에 없는지 확인한다.
- WireMock/Toxiproxy로 DNS/connect/read timeout, 429 + `Retry-After`, 5xx burst, malformed body, duplicate/late webhook, half-open circuit을 재현한다.
- authorize timeout 뒤 provider lookup이 기존 승인과 연결되고 새 승인 요청이 만들어지지 않음을 증명한다.
- circuit open/bulkhead saturation 중에도 unrelated order/sync API latency가 악화되지 않음을 load test로 확인한다.
- FX cache를 의도적으로 stale하게 만들고, 화면/quote fallback과 settlement snapshot 보존이 구분됨을 확인한다.
- Slack webhook을 중단해도 payment/order transaction이 성공하고 notification failure metric/alert가 생성됨을 확인한다.

## 9. References

- [Toss Payments API keys and test/live separation](https://docs.tosspayments.com/reference/using-api/api-keys) and [test environment/error simulation](https://docs.tosspayments.com/guides/v2/get-started/environment)
- [Toss Payments payment-widget integration](https://docs.tosspayments.com/guides/payment-widget/integration)
- [Slack incoming webhooks](https://api.slack.com/incoming-webhooks)
- [Frankfurter API](https://frankfurter.dev/) — free, no-key reference-rate API and self-hosting option
- [PortOne Korea sandbox availability](https://docs.portone.cloud/docs/portone-korea)
- [Spring Cloud Circuit Breaker](https://docs.spring.io/spring-cloud-circuitbreaker/reference/) and [bulkhead configuration](https://docs.spring.io/spring-cloud-circuitbreaker/reference/spring-cloud-circuitbreaker-resilience4j/bulkhead-properties-configuration.html)
- [Java `HttpClient` timeouts](https://docs.oracle.com/en/java/javase/26/docs/api/java.net.http/java/net/http/HttpClient.Builder.html)
- [AWS transactional outbox guidance](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)
- [OpenTelemetry messaging semantic conventions](https://opentelemetry.io/docs/specs/semconv/messaging/messaging-spans/)
