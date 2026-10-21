# PR spec: 알림 규칙·runbook·trace로 관측을 마무리한다

## 목적
metric과 ECS 로그는 나가지만 그것을 읽어 사람을 부르는 규칙과, 불린 사람이 따라갈 절차가 없다. 그래서 장애를 사람이 알아차릴 길이 없다. 재전송 작업과 결과 불명 확정 작업이 멈춰도, 락이 계속 fail-open이어도, 단말과 서버의 sync 상태가 갈라져도 로그 한 줄만 남는다. README가 요구하는 trace도 없어서 요청 → Kafka → consumer 흐름을 하나로 따라갈 수 없다(이슈 #76).

기대 결과:
- Prometheus rule 파일 하나가 알림 규칙의 정본이다. 로컬 compose의 Prometheus가 그 파일을 싣고, 운영에서는 같은 파일을 Mimir ruler에 올린다. 규칙마다 runbook 절이 있다.
- 규칙이 기대는 metric이 모두 나간다. 재전송 작업 생존과 선택 건수, 락 fail-open, sync 항목 결과와 수렴 시간이다.
- HTTP 요청에서 Kafka producer, consumer, 다음 이벤트까지 trace 하나로 이어지고, ECS 로그 줄에 그 traceId가 실린다.

## 범위
소유 모듈은 `platform`(락·재전송·Kafka 설정·trace 설정)과 `sync`(항목 결과 counter, 수렴 시간, 요청의 `createdAt`)다. 그 밖의 업무 모듈 코드는 바꾸지 않는다.

### 결정
**1. 한 PR로 낸다.** 수집기·알림 규칙, 미뤄진 metric, runbook, trace를 이슈 #76 그대로 묶는다(사용자 결정). trace는 결정 갈래(의존성·전파·샘플링·수집 경로)가 따로 있지만 이 PR spec 한 곳에 둔다. `build.gradle.kts`는 열린 #94와 겹치므로 새 줄을 #94가 고치는 33행(Modulith BOM)에서 떨어진 자리에 둔다.

**2. 알림 규칙의 정본은 Prometheus rule 파일 `docker/prometheus/alerts.yaml`이다.**
- `grafana/otel-lgtm`의 Prometheus는 `/otel-lgtm/prometheus.yaml`로 뜨고 `rule_files`가 없다(이미지 v0.37.0, Prometheus 3.15.0에서 확인). 그래서 `docker/prometheus/prometheus.yaml`을 그 자리에 마운트해 `rule_files`로 규칙을 싣는다. 이미지 기본 파일의 OTLP 속성 승격 설정은 빠진다. 우리 metric은 OTLP가 아니라 scrape로 들어오므로 영향이 없다.
- Grafana alerting provisioning은 쓰지 않는다. Grafana 전용 형식이라 Mimir ruler로 옮길 수 없고 promtool로 테스트할 수 없다.
- 로컬에는 Alertmanager가 없어 알림이 발송되지 않는다. firing 상태는 Prometheus `/api/v1/alerts`와 Grafana의 알림 목록에서 본다. 운영 발송 경로(Alertmanager·Mimir alertmanager)는 배포가 정해질 때 붙인다.
- 규칙마다 `severity`(`critical`·`warning`) label과 `summary`·`runbook_url` annotation을 둔다. `runbook_url`은 `https://github.com/char-labs/ResilientPOS/blob/main/docs/wiki/runbook.md#규칙이름소문자`다.

**3. 규칙은 13개다.** 여러 pod가 같은 DB를 세는 gauge는 `max`로 모으고, pod마다 도는 작업은 `instance`별로 본다.

| 규칙 | 조건 | for | severity |
|---|---|---|---|
| EventPublicationFailed | `max(event_publication_incomplete{status="failed"}) > 0` | 1h | warning |
| EventPublicationMetricStale | 인스턴스별 `time() - event_publication_incomplete_refreshed_seconds > 300` | 5m | warning |
| KafkaDeadLetter | 10분 안에 `event_kafka_dead_letter_total`이 늘었거나, 10분 전에는 없던 series가 0보다 큰 값으로 생김 | 없음 | critical |
| KafkaDeadLetterPublishFailed | 위와 같은 조건을 `event_kafka_dead_letter_failed_total`에 | 없음 | critical |
| KafkaConsumerLag | `max by (topic, partition) (kafka_consumer_fetch_manager_records_lag) > 1000` | 10m | warning |
| EventPublicationResubmitterNotSucceeding | 살아 있는 인스턴스(`up == 1`)에서 10분 동안 `tasks_scheduled_execution_seconds_count{code_function="resubmitAndPurge", outcome="SUCCESS"}`가 늘지 않음 | 10m | warning |
| UnknownPaymentResolverNotSucceeding | 같은 조건을 `code_function="resolveUnknown"`에 | 10m | warning |
| UnknownPaymentResolutionStuck | `max(payment_attempt_unknown) - max(payment_attempt_reconciliation_pending) > 0` | 2h | warning |
| ReconciliationPendingUnresolved | `max(payment_attempt_reconciliation_pending) > 0` | 24h | warning |
| LockFailOpen | `sum by (lock, reason) (increase(lock_fail_open_total[10m])) > 0` | 10m | warning |
| SyncConflict | `sum(increase(sync_operation_total{status="CONFLICT"}[10m])) > 0` | 없음 | warning |
| SyncGapPersistent | `sum(increase(sync_operation_total{status="GAP"}[30m])) > 0` | 30m | warning |
| OwnerSessionStoreUnavailable | `sum(increase(http_server_requests_seconds_count{status="503"}[5m])) > 0` | 5m | critical |

- 앞의 넷과 UnknownPaymentResolutionStuck·ReconciliationPendingUnresolved·OwnerSessionStoreUnavailable은 [관측성](../wiki/observability.md)이 이미 적어 둔 조건을 그대로 옮긴다.
- **첫 DLT.** 도메인 reason counter는 `platform`이 `contract`를 참조하면 순환하므로 미리 등록하지 않는다([event-contract-compatibility](2026-10-20-event-contract-compatibility.md) 제외 절). 새 series의 첫 값은 `increase()`가 0으로 본다. 그래서 규칙이 "10분 전에 없던 series"를 `unless … offset 10m`로 따로 잡는다. 재기동으로 다시 생기는 `reason="error"` series는 0으로 등록되므로 울리지 않는다. 이 처리로 wiki의 "배포 뒤 DLT를 직접 확인한다"는 단서를 지운다.
- `event_kafka_dead_letter_failed_total{topic}`은 지금 처음 실패할 때 생긴다. 토픽마다 기동할 때 0으로 등록한다(`reason="error"`와 같다). 그래야 아래 이름 대조 테스트가 이름을 확인할 수 있다.
- consumer lag는 `records_lag_max`가 아니라 `records_lag`를 쓴다. `records_lag_max`는 fetch가 없는 동안 `NaN`이다(로컬 compose에서 확인).
- 작업 생존에는 새 metric을 만들지 않는다. Spring이 `@Scheduled`마다 observation `tasks.scheduled.execution`을 남겨 `tasks_scheduled_execution_seconds_count{code_namespace, code_function, outcome}`가 나간다(Boot 4.1.1 `ScheduledTasksObservationAutoConfiguration`, 로컬 compose에서 두 작업 모두 확인). 기동한 뒤 한 번도 성공하지 못한 작업은 `SUCCESS` series가 없으므로 기준을 `up`으로 잡는다.
- 근거 없는 값(lag 1000, 감지 창 10분·30분, SyncGap의 for)은 한계 절에 적고 부하테스트 뒤 조정한다.

**4. 더하는 metric.**
- `event.publication.resubmit.selected`(counter, Prometheus `event_publication_resubmit_selected_total`): 재전송 한 주기에 Modulith가 읽어 다시 보내려 한 `FAILED` 행 수다. `ResubmissionOptions.withFilter`의 술어에서 센다. Modulith 2.1.1의 `processPublications`는 읽은 행마다 이 술어를 부른 뒤 `markResubmitted`를 하므로, 이 값은 "보낸 수"가 아니라 "고른 수"다. 락이 빠져 두 pod가 같은 행을 읽으면 두 번 센다. 전송 건수는 세지 않는다. `resubmit`이 `void`이고 전송 완료는 externalizer가 나중에 따로 표시한다. 알림에는 쓰지 않고 runbook의 진단(매 주기 500건을 읽는지)에 쓴다. 고치는 곳은 `resubmitAndPurge`의 `failed.resubmit(...)` 한 줄과 생성자다. #94가 고치는 KDoc과 `markStalePublicationsFailed`는 건드리지 않는다.
- `lock.fail.open`(counter, `lock_fail_open_total{lock, reason}`): `reason`은 `timeout`(대기 3초 초과)과 `error`(Redis 오류)다. `lock`은 키의 앞 두 마디(`order:create-lock`, `payment:checkout-lock`, `platform:event-publication`, `payment:unknown-resolve`)다. 키 규칙 "모듈:이름:식별자…"를 `Lock` KDoc에 적는다. 이 규칙을 어기는 키는 식별자가 label로 새어 시계열이 폭증한다. `platform`은 업무 모듈의 키를 모르므로 미리 등록하지 않는다. 그래서 한 번만 일어난 fail-open은 `increase()`가 놓칠 수 있다. 규칙이 `for: 10m`로 이어지는 fail-open만 보므로 감수한다.
- `sync.operation`(counter, `sync_operation_total{status}`): 배치 항목 결과마다 1씩 오른다. `status`는 `APPLIED`·`DUPLICATE`·`REJECTED`·`GAP`·`CONFLICT`이고 기동할 때 다섯 값을 0으로 등록한다. 시스템 설계 7절의 `sync_gap_total`은 `status="GAP"`이다. 이름을 설계와 다르게 둔 이유는 CONFLICT(지금 WARN 로그로만 보인다)도 같은 counter로 알림을 걸기 위해서다.
- `sync.convergence`(timer, `sync_convergence_seconds`): 단말이 operation을 만든 시각부터 서버가 처음 확정한 시각까지다. SLO bucket 1s·10s·1m·5m·15m·1h·6h·24h만 낸다. Micrometer 기본 bucket의 상한(30초)으로는 오프라인 수렴을 담지 못한다. 알림은 걸지 않는다. 긴 값은 매장 네트워크가 오래 끊겼다는 뜻이라 플랫폼이 조치할 것이 없다.

**5. sync 요청에 operation 생성 시각(`createdAt`)을 더한다.**
- `CREATE_ORDER`·`REQUEST_CHECKOUT` 항목의 선택 필드다. ISO-8601 offset 시각(예: `2026-10-21T09:00:00+09:00`)이고 `Instant`로 받는다. 없으면 수렴 시간을 기록하지 않는다. 시각이 없는 기존 단말과 시연 화면의 배치는 그대로 받는다.
- payload hash에 넣지 않는다. 같은 operation을 시각만 다르게 재전송해도 `CONFLICT`가 되지 않는다. 그래서 `OrderPayload`·`CheckoutPayload`가 아니라 `SyncCommand.ReceiveBatch.Operation`에 둔다.
- 저장하지 않는다. 수렴 시간은 metric에만 남는다. 도메인 모델 "스키마 변경 검토"의 필요성 기준("나중에 채울 수 있으면 넣지 않는다")을 따른다.
- 처음 확정할 때(`APPLIED`·`REJECTED`) 한 번만 기록한다. `DUPLICATE`·`GAP`·`CONFLICT`에서는 기록하지 않는다. 확정 시각은 주입한 `Clock`으로 잰다.
- 단말 시계가 앞서 차이가 음수면 기록하지 않는다. 시각이 어긋나도 배치를 거절하지 않는다. 오프라인 POS가 sync에서 막히면 안 되기 때문이다. 단말 시계가 늦은 만큼 수렴 시간이 크게 잡히는 것은 알아낼 방법이 없다(한계).
- 형식이 틀린 `createdAt`은 다른 필드처럼 Jackson 역직렬화가 거절해 배치 전체가 `400 BAD_REQUEST`다. REST Docs의 요청 필드에 더한다.

**6. trace 구성.**
- 의존성: `spring-boot-starter-opentelemetry`(Micrometer Tracing OTel bridge와 OTLP exporter)와 `spring-modulith-observability-core`(runtime, 모듈 API 호출 span과 `module_events_published` counter)를 더한다. 버전은 Boot·Modulith BOM이 관리한다.
- 샘플링은 1.0이다(`management.tracing.sampling.probability`, Boot 기본 0.1). 로그에 찍힌 traceId가 모두 Tempo에 있어서 runbook이 trace에 기댈 수 있다. 환경 변수로 낮출 수 있다. 샘플러는 Boot 기본 `parent-based-trace-id-ratio`라 consumer는 producer의 결정을 따른다.
- 수집 경로: 앱 → Alloy(OTLP HTTP 4318) → lgtm의 Tempo다. metric·로그와 같은 수집기를 쓴다. 앱의 OTLP endpoint는 compose 환경 변수로만 준다. endpoint가 없는 실행(IDE·테스트)에서 exporter가 어떻게 동작하는지(만들지 않는지, 연결 오류를 남기는지)는 Boot 4.1.1 소스로 확인해 결과 절에 적고, 오류를 반복해 남기면 endpoint가 있을 때만 export하도록 설정한다.
- Kafka 전파: `spring.kafka.template.observation-enabled`와 `spring.kafka.listener.observation-enabled`를 켠다. 둘 다 Boot 기본값은 `false`다(spring-boot-kafka 4.1.1 `KafkaProperties`). 켜면 template이 레코드 header에 trace context를 싣고, listener가 그것을 읽어 consumer span을 이어 붙인다. Spring Kafka 4.1.1 문서에 따르면 그 대가로 Spring Kafka의 Micrometer timer를 observation이 맡는다(이름과 태그가 바뀔 수 있다). 알림 규칙은 이 timer를 쓰지 않는다. DLT 전송(`DeadLetterPublishingRecoverer`)도 같은 template을 쓰므로 같은 trace에 남는다.
- Modulith externalization: externalizer(`EventExternalizerModuleListener`)는 `@ApplicationModuleListener`라 커밋 뒤 Boot의 `applicationTaskExecutor` 스레드에서 `KafkaOperations.send(Message)`로 보낸다(Modulith 2.1.1 `KafkaEventExternalizerConfiguration` 소스). 요청 스레드의 trace를 그 스레드로 넘기도록 `spring.task.execution.propagate-context`를 켠다. Boot 기본값은 `false`다(Boot 4.1.1 `TaskExecutionProperties`). 켜지 않으면 producer span이 새 trace로 끊긴다.
- 로그: Micrometer Tracing이 MDC에 `traceId`·`spanId`를 넣고, Boot의 ECS 형식은 MDC 쌍을 모두 JSON에 넣는다(Boot 4.1.1 logging 문서). 실제 필드 이름은 compose에서 확인해 결과 절에 적는다. Alloy가 그 traceId를 Loki의 label이 아니라 structured metadata `trace_id`로 올린다. otel-lgtm의 Tempo 데이터소스는 trace에서 로그로 갈 때 `| trace_id = "…"`로 거르고, Loki 데이터소스는 `trace_id`에서 Tempo로 링크한다(이미지의 `grafana-datasources.yaml`에서 확인).
- 재전송 작업이 다시 보낸 메시지는 원래 요청의 trace를 잇지 못하고 그 주기 작업의 trace에 남는다. 발행 기록에 trace context가 저장되지 않는다(한계).

**7. runbook은 `docs/wiki/runbook.md` 한 페이지다.** 규칙 이름마다 절을 두고, 절마다 뜻 → 먼저 볼 것(PromQL·Loki 쿼리·trace) → 조치 → 하지 말 것 순서로 적는다. 탐지 SQL과 재전송 규칙은 [도메인 모델](../wiki/domain-model.md) §4.3, 재투입 전 확인은 [이벤트 계약](../wiki/event-contract.md) 8절에 있는 것을 링크만 한다. DLT 재투입 절차는 #91이 맡으므로 지금은 §4.3의 절차를 링크하고, #91이 운영 API를 만들면 그 PR이 링크를 바꾼다.

**8. 규칙 검증은 `./gradlew test`에 넣는다.**
- `docker/prometheus/alerts.test.yaml`(promtool 단위 테스트)을 JUnit 테스트가 Testcontainers로 `prom/prometheus:v3.15.0`을 띄워 `promtool test rules`로 돌린다. 로컬 lgtm의 Prometheus와 같은 버전이다. `for:`, 시계열이 사라지는 경우, 첫 DLT series, 기동 직후, 작업이 한 번도 성공하지 못한 경우를 시나리오로 둔다. `promtool check rules`도 함께 돈다.
- 같은 테스트 클래스가 규칙 식에서 metric 이름을 뽑아 앱의 `/actuator/prometheus`에 있는지 대조한다. 코드가 metric 이름을 바꿨는데 규칙을 고치지 않으면 규칙이 조용히 아무것도 보지 못하는데, promtool 테스트는 가짜 series로 돌아서 이것을 잡지 못한다. Prometheus가 만드는 `up`과, 처음 일어날 때 생기는 series(`lock_fail_open_total`, 테스트에서 돌지 않는 주기 작업의 `tasks_scheduled_execution_seconds_count`)는 이유를 적은 예외 목록으로 둔다.

### Kafka·Modulith 전파 근거
- [Spring for Apache Kafka 4.1.1, Micrometer Observation](https://docs.spring.io/spring-kafka/reference/kafka/micrometer.html): template과 container의 `observationEnabled`를 켜면 observation이 timer를 맡는다. 배치 listener는 기본적으로 observation을 만들지 않는다. 이 저장소의 listener는 레코드 단위라 해당하지 않는다.
- [Spring Boot 4.1.1, Tracing](https://docs.spring.io/spring-boot/reference/actuator/tracing.html): `spring-boot-starter-opentelemetry`, 기본 샘플링 10%, MDC의 `traceId`·`spanId`.
- [Spring Boot 4.1.1, Observability의 Context Propagation](https://docs.spring.io/spring-boot/reference/actuator/observability.html): 자동 구성된 `AsyncTaskExecutor`는 `spring.task.execution.propagate-context`로 opt-in한다.
- [Spring Modulith 2.1.1, Production-ready Features](https://docs.spring.io/spring-modulith/reference/production-ready.html): `spring-modulith-observability-core`가 모듈 API 빈 호출에 span을 만든다.
- 기본값은 문서가 말하지 않는 것을 sources jar나 기동 로그로 확인한다. 대상은 OTLP exporter의 batch span processor(대기열 크기·보내는 주기·export 시간 상한), consumer span이 producer span의 자식인지 link인지, ECS의 MDC 필드 모양이다. 결과는 이 절에 추가한다.

계약: 단말 sync 요청에 선택 필드 `createdAt`이 생긴다. 응답은 바뀌지 않는다. Kafka 레코드에 trace header(`traceparent`)가 붙는다. consumer는 header를 payload로 읽지 않으므로 이벤트 계약(payload JSON)은 바뀌지 않는다.

복구: 상태를 바꾸는 코드가 없다. 알림이 울린 뒤의 복구는 runbook이 기존 절차(재전송·DLT 재투입·대조 case 해소)로 안내한다.

관측성: 이 PR이 그 마무리다.

## 제외
- 기준선이 없어 넣지 않는 알림. 기준선이 생기는 PR(부하테스트, 대시보드)이 더한다. 이 목록은 [관측성](../wiki/observability.md)의 "아직 없는 것"에도 같은 조건으로 남긴다.
  - HTTP 지연·오류율 SLO 알림(주문 p95 200ms 등, 시스템 설계 7절). 부하테스트 기준선이 없다.
  - 수렴 시간 알림. 결정 4.
  - 재전송 선택 건수 알림. 진단용이고, 실패 1시간 알림이 같은 상황을 덮는다.
- 재전송 전송 건수. `FailedEventPublications.resubmit`이 `void`라 API로 알 수 없다.
- Alertmanager와 알림 발송 경로(webhook·메신저). 받을 곳이 없다. 배포가 정해질 때 붙인다.
- Grafana 대시보드 provisioning과 exemplar(metric에서 trace로 가는 링크). 쓸 대시보드가 아직 없다.
- tail sampling. 샘플링은 1.0으로 둔다. 다시 볼 조건: 부하테스트(k6)에서 exporter CPU나 Tempo 저장량이 보이면 Alloy tail sampling(오류·느린 trace는 모두, 나머지는 일부)으로 바꾼다.
- 재전송 메시지가 원래 trace를 잇는 것. 발행 기록에 trace context를 저장해야 한다.
- `createdAt` 저장과 시연 화면(`static/terminal`)이 `createdAt`을 보내는 것.
- 락 호출부가 label 이름을 따로 넘기는 API(`Lock.executeWithLock(name, key)`). 키 앞 두 마디로 대신한다(사용자 결정).
- DLT 재투입 API와 그 runbook 절차. #91이다.
- metric 접근 제한(인증, management 포트 분리). 배포 형태가 정해질 때 정한다.

## 한계
- 임계값과 감지 창 대부분이 근거 없는 시작값이다(lag 1000, 작업 생존 10분, LockFailOpen 10분, SyncGap 30분). 부하테스트(k6) 뒤 락 시간과 함께 다시 본다.
- 단말 시계가 늦으면 수렴 시간이 그만큼 크게 잡히고, 이것을 알아낼 방법이 없다. 단말 시계가 앞서 음수가 나온 값은 버리므로 세어지지도 않는다.
- 한 번만 일어난 락 fail-open은 알림이 놓칠 수 있다(결정 4).
- `lock` label은 키 규칙에 기댄다. 규칙을 어긴 새 키가 생기면 식별자가 label로 샌다. 다시 볼 조건: 키를 만드는 호출부가 다섯 곳을 넘거나 규칙 밖 키가 필요해질 때 호출부가 이름을 넘기는 API로 바꾼다.
- 재전송 선택 건수는 락이 빠지면 두 번 셀 수 있다.
- 로컬에서는 알림이 발송되지 않고 firing 상태로만 보인다.
- 샘플링 1.0의 비용은 재지 않았다.

## 관련 문서
- [관측성](../wiki/observability.md): metric·로그·trace 노출과 수집 방식의 정본이다.
- [시스템 설계 7절](../references/02_System_Design.md#7-nfr-and-observability): 필수 domain signal과 alert → trace → log → runbook 연결.
- [event-publication-resubmit](2026-09-29-event-publication-resubmit.md), [sync-intake](2026-09-30-sync-intake.md), [event-contract-compatibility](2026-10-20-event-contract-compatibility.md): 이 PR이 받는 제외 항목.
- [prometheus-structured-logging](2026-09-25-prometheus-structured-logging.md), [local-docker-compose](2026-09-26-local-docker-compose.md): 수집 기반.

## 변경 경로
- docs/spec/2026-10-21-observability-alerts-tracing.md
- build.gradle.kts
- src/main/resources/application.yaml
- docker/docker-compose.yaml
- docker/alloy/config.alloy
- docker/prometheus/**
- src/main/kotlin/com/resilientpos/platform/**
- src/main/kotlin/com/resilientpos/sync/**
- src/test/kotlin/com/resilientpos/platform/**
- src/test/kotlin/com/resilientpos/sync/**
- src/test/kotlin/com/resilientpos/*.kt
- src/test/resources/**
- docs/wiki/observability.md
- docs/wiki/runbook.md
- docs/wiki/README.md
- docs/wiki/event-contract.md
- docs/wiki/domain-model.md
- README.md

## 인수 조건
1. `docker/prometheus/alerts.yaml`이 위 13개 규칙을 담고 `promtool check rules`를 통과한다. 규칙마다 `severity` label과 `summary`·`runbook_url` annotation이 있고, `runbook_url`의 앵커가 `docs/wiki/runbook.md`의 절과 맞는다.
2. `promtool test rules`가 규칙별 경계를 통과한다. 첫 DLT series, `failed`가 1시간 이어질 때와 중간에 0이 될 때, 작업이 기동한 뒤 한 번도 성공하지 못할 때, 신선도가 기동 직후 0일 때를 포함한다.
3. 규칙이 쓰는 metric 이름이 앱의 `/actuator/prometheus`에 있다(예외 목록 밖).
4. 락이 fail-open되면 `lock_fail_open_total{lock="키 앞 두 마디", reason="timeout" 또는 "error"}`가 오르고, 기동할 때 토픽마다 `event_kafka_dead_letter_failed_total`이 0으로 나간다.
5. 재전송 한 주기에 읽은 `FAILED` 행 수만큼 `event_publication_resubmit_selected_total`이 오른다.
6. sync 배치의 항목 결과마다 `sync_operation_total{status}`가 오르고, 다섯 status가 기동할 때 0으로 나간다.
7. `createdAt`이 있는 항목이 처음 확정되면 `sync_convergence_seconds`에 한 번 기록된다. `createdAt`이 없거나, 차이가 음수거나, 결과가 `DUPLICATE`·`GAP`·`CONFLICT`면 기록되지 않는다. `createdAt`만 다른 재전송은 `DUPLICATE`다. 형식이 틀리면 `400 BAD_REQUEST`다. REST Docs의 요청 필드에 `createdAt`이 있다.
8. 주문 생성과 checkout 요청 하나가 만든 HTTP span, Kafka producer span, consumer span, 그 consumer가 낸 다음 이벤트의 producer span이 같은 traceId다(통합 테스트).
9. ECS 로그 줄에 그 traceId가 실린다.
10. `docker compose -f docker/docker-compose.yaml --profile app --profile obs up -d --build`에서 다음을 확인하고 결과를 이 spec에 남긴다.
    - Prometheus가 규칙 13개를 싣고, 실제로 일으킨 장애 하나 이상에서 해당 규칙이 firing이 된다. 예: Redis를 멈춰 LockFailOpen과 OwnerSessionStoreUnavailable, DLT를 일으켜 KafkaDeadLetter.
    - Tempo에서 checkout 요청의 trace가 HTTP → Kafka → consumer로 이어진다. Modulith 모듈 span도 보인다.
    - Loki에서 그 traceId로 로그가 조회되고, Grafana에서 trace와 로그가 서로 링크된다.
11. 기존 테스트가 모두 통과한다.

## 검증
- 인수 1~3: `./gradlew test --tests 'com.resilientpos.platform.AlertRulesTest'`(이름은 구현 때 확정). Docker가 필요하다.
- 인수 4~6: platform과 sync의 해당 테스트를 `./gradlew test --tests`로 좁혀 돌린다.
- 인수 7: `SyncControllerTest`(REST Docs, 400)와 `SyncCommandFacadeIntegrationTest`(기록 여부).
- 인수 8~9: trace 통합 테스트. span은 테스트용 in-memory exporter로 모은다.
- 인수 10: compose를 띄워 Prometheus·Tempo·Loki HTTP API(Grafana 데이터소스 proxy)로 조회하고 결과 절에 적는다.
- 인수 11: `./gradlew test` 전체.
- `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전에는 `--worktree` 없이 한 번 더.
- PR 전에 `resilientpos-multi-review`.

## Wiki 영향
- [관측성](../wiki/observability.md): "지금 있는 것"에 알림 규칙 파일과 새 metric(재전송 선택 건수, 락 fail-open, sync 항목 결과와 수렴 시간, 작업 생존으로 쓰는 `tasks_scheduled_execution`), trace를 더한다. 첫 DLT를 직접 확인하라는 단서를 규칙 처리로 바꾼다. "LGTM을 붙일 때"에 Mimir ruler와 OTLP 경로를, "로컬에서 띄우기"에 rule 로드·Tempo·로그↔trace 링크를 더한다. "아직 없는 것"을 제외 절의 조건과 맞춘다.
- [runbook](../wiki/runbook.md): 새 페이지다. [wiki 목차](../wiki/README.md)에 한 줄 건다.
- [도메인 모델](../wiki/domain-model.md): sync의 `createdAt`과 수렴 시간(저장하지 않는 선택 필드)을 §4.6 sync 서술에 한 줄 더한다. 용어집에 새 단어는 없다.
- [이벤트 계약](../wiki/event-contract.md): 5절의 "이 reason의 counter는 처음 DLT로 보낼 때 생겨 `increase()` 알림이 첫 건을 놓칠 수 있으니 배포 뒤 DLT를 직접 확인한다"를 규칙 처리(결정 3)로 바꾼다.
- README: "OpenTelemetry trace는 아직 없습니다"를 고치고 기술 표의 운영 상태 확인 행에 OpenTelemetry를 더한다.
- Modulith·Kafka 키워드(볼트): PR 전에 그 문서의 갱신 규칙대로 Kafka observation과 externalization 전파 항목을 고친다.
- 볼트의 현재 상태 문서는 머지 뒤 고친다.
- 요구사항 변화: 없다. 시스템 설계 7절이 정한 파이프라인(trace, alert → runbook)과 필수 signal 중 `sync_gap_total`·`sync_convergence_seconds`를 채운다. `sync_gap_total`은 `sync_operation_total{status="GAP"}`로 이름이 다르다(결정 4). `payment_unknown_total`·`payment_reconciliation_age_seconds`·publication oldest age는 이 PR이 더하지 않는다. gauge `payment_attempt_unknown`·`payment_attempt_reconciliation_pending`과 `failed` 1시간 규칙이 같은 질문에 답한다.

## 분할 계획
한 개발 컨텍스트(이슈 #76 관측 마무리)다. 사용자 결정으로 trace도 함께 담는다(결정 1).

겹침 확인(2026-10-21, origin/main c7810af):
- 열린 PR #94(refactor/modulith-2-1-2-resubmit, draft): `build.gradle.kts`, `EventPublicationResubmitter.kt`, `EventPublicationResubmitterIntegrationTest.kt`, `docs/wiki/domain-model.md`를 고친다. `build.gradle.kts`는 33행만 고치므로 새 의존성 줄을 그 근처에 두지 않는다. `EventPublicationResubmitter.kt`는 #94가 KDoc(16~31행)과 `markStalePublicationsFailed`(70~84행)를 고친다. 이 PR은 생성자와 65행(`failed.resubmit`)만 고친다. 재전송 통합 테스트 파일은 고치지 않고 선택 건수 테스트를 다른 파일에 둔다. `domain-model.md`는 #94가 §4.3을, 이 PR이 §4.6을 고친다.
- 진행 중인 #91(feature/platform-operator-dlt-redrive, 아직 커밋 없음): `docs/wiki/observability.md`가 겹칠 수 있다. push·PR 생성·머지 직전에 다시 확인하고, 먼저 머지된 쪽에 맞춰 rebase한다.

## TDD 검증 기록
업무 동작 변경(sync 요청 필드와 metric, 락·재전송 metric)과 trace 전파에 적용한다. 규칙 파일·compose·Alloy 설정은 promtool 테스트와 compose 실측으로 대신한다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 개발 전에 테스트 의도를 적고 실행 후 관측 결과를 기록 | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
