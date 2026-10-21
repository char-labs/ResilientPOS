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

**3. 규칙은 14개다.** 여러 pod가 같은 DB를 세는 gauge는 `max`로 모으고, pod마다 도는 작업은 `instance`별로 본다(범위 조정 9~12).

| 규칙 | 조건 | for | severity |
|---|---|---|---|
| ResilientPosMetricsAbsent | `absent(process_uptime_seconds{application="ResilientPOS"})`. 앱 인스턴스가 하나도 보이지 않음 | 5m | critical |
| EventPublicationOverdue | `max(event_publication_incomplete_oldest_seconds) > 3600`. 완료되지 않은 가장 오래된 발행 기록이 1시간을 넘음 | 5m | warning |
| EventPublicationMetricStale | 인스턴스별 `time() - event_publication_incomplete_refreshed_seconds > 300` | 5m | warning |
| KafkaDeadLetter | 10분 안에 `event_kafka_dead_letter_total`이 늘었거나, 10분 전까지 1시간 동안 없던 series가 0보다 큰 값으로 생김(`unless last_over_time(…[1h] offset 10m)`). `keep_firing_for: 24h` | 없음 | critical |
| KafkaDeadLetterPublishFailed | 위와 같은 조건을 `event_kafka_dead_letter_failed_total`에. `keep_firing_for: 24h` | 없음 | critical |
| KafkaConsumerLag | `max by (topic, partition) (kafka_consumer_fetch_manager_records_lag) > 1000` | 10m | warning |
| EventPublicationResubmitterNotSucceeding | 기동한 지 5분이 지난 인스턴스(`process_uptime_seconds > 300`)에서 10분 동안 `tasks_scheduled_execution_seconds_count{code_function="resubmitAndPurge", outcome="SUCCESS"}`가 늘지 않음 | 10m | warning |
| UnknownPaymentResolverNotSucceeding | 같은 조건을 `code_function="resolveUnknown"`에 | 10m | warning |
| UnknownPaymentResolutionStuck | `max(payment_attempt_unknown) - max(payment_attempt_reconciliation_pending) > 0` | 2h | warning |
| ReconciliationPendingUnresolved | `max(payment_attempt_reconciliation_pending) > 0` | 24h | warning |
| LockFailOpen | `sum by (lock, reason) (increase(lock_fail_open_total[2m])) > 0` | 10m | warning |
| SyncConflict | `sum(increase(sync_operation_total{status="CONFLICT"}[10m])) > 0` | 없음 | warning |
| SyncGapPersistent | `sum(increase(sync_operation_total{status="GAP"}[5m])) > 0` | 30m | warning |
| OwnerSessionStoreUnavailable | `sum(increase(http_server_requests_seconds_count{status="503", uri!~"/actuator/.*"}[2m])) > 0` | 5m | critical |

- EventPublicationMetricStale·KafkaDeadLetter·KafkaDeadLetterPublishFailed와 UnknownPaymentResolutionStuck·ReconciliationPendingUnresolved·OwnerSessionStoreUnavailable은 [관측성](../wiki/observability.md)이 이미 적어 둔 조건에서 출발했다. `EventPublicationOverdue`는 그 wiki의 `failed > 0 for: 1h` 조건이 브로커 장애를 놓쳐 나이로 바꾼 것이다(범위 조정 9).
- **감지 창은 `for:`보다 짧게 둔다.** `increase(x[W]) > 0`은 마지막 증가 뒤에도 W 동안 참이라, W와 `for:`가 같으면 짧은 묶음이 끝난 뒤에 울린다. 창은 scrape 간격(15초)의 네 배 이상으로 둔다(범위 조정 12).
- **첫 DLT.** 도메인 reason counter는 `platform`이 `contract`를 참조하면 순환하므로 미리 등록하지 않는다([event-contract-compatibility](2026-10-20-event-contract-compatibility.md) 제외 절). 새 series의 첫 값은 `increase()`가 0으로 본다. 그래서 규칙이 "10분 전까지 1시간 동안 없던 series"를 `unless last_over_time(… [1h] offset 10m)`로 따로 잡는다. range 조회라 scrape가 한 번 실패해 생긴 stale marker를 "없던 series"로 보지 않는다. 재기동으로 다시 생기는 `reason="error"` series는 0으로 등록되므로 울리지 않는다. 이 처리로 wiki의 "배포 뒤 DLT를 직접 확인한다"는 단서를 지운다.
- **DLT 알림은 24시간 붙잡는다.** DLT 메시지는 지워지지 않는데 증가 조건은 10분 뒤 풀린다. `keep_firing_for: 24h`로 마지막 DLT 뒤 하루 동안 firing을 유지해, 새벽의 DLT를 아침에도 보게 한다. 재투입과 업무 상태를 확인한 뒤에는 사람이 silence로 끈다(범위 조정 11).
- `event_kafka_dead_letter_failed_total{topic}`은 지금 처음 실패할 때 생긴다. 토픽마다 기동할 때 0으로 등록한다(`reason="error"`와 같다). 그래야 아래 이름 대조 테스트가 이름을 확인할 수 있다.
- consumer lag는 `records_lag_max`가 아니라 `records_lag`를 쓴다. `records_lag_max`는 fetch가 없는 동안 `NaN`이다(로컬 compose에서 확인).
- 작업 생존에는 새 metric을 만들지 않는다. Spring이 `@Scheduled`마다 observation `tasks.scheduled.execution`을 남겨 `tasks_scheduled_execution_seconds_count{code_namespace, code_function, outcome}`가 나간다(Boot 4.1.1 `ScheduledTasksObservationAutoConfiguration`, 로컬 compose에서 두 작업 모두 확인). 기동한 뒤 한 번도 성공하지 못한 작업은 `SUCCESS` series가 없으므로 살아 있는 인스턴스를 기준으로 잡는다. 기준은 `process_uptime_seconds{application="ResilientPOS"}`다(범위 조정 1).
- 근거 없는 값(lag 1000, 발행 지연 1시간, 감지 창, SyncGap의 for)은 한계 절에 적고 부하테스트 뒤 조정한다.

**4. 더하는 metric.**
- `event.publication.incomplete.oldest`(gauge, Prometheus `event_publication_incomplete_oldest_seconds`): 완료되지 않은(`completion_date is null`) 발행 기록 가운데 가장 오래된 것의 나이(발행 시각부터, 초)다. 없으면 0이다. `EventPublicationMetrics`가 같은 1분 갱신에서 함께 센다. 조회가 실패한 인스턴스는 `NaN`을 내 `max`에서 빠지고 신선도 시각이 멈춘다. 상태가 `FAILED`와 `RESUBMITTED`를 오가도 나이는 계속 늘어서, 브로커 장애가 이어지는 동안 알림이 끊기지 않는다. 시스템 설계 7절의 "publication oldest age"다(범위 조정 9).
- `event.publication.resubmit.selected`(counter, Prometheus `event_publication_resubmit_selected_total`): 재전송 한 주기에 Modulith가 읽어 다시 보내려 한 `FAILED` 행 수다. `ResubmissionOptions.withFilter`의 술어에서 센다. Modulith 2.1.1의 `processPublications`는 읽은 행마다 이 술어를 부른 뒤 `markResubmitted`를 하므로, 이 값은 "보낸 수"가 아니라 "고른 수"다. 락이 빠져 두 pod가 같은 행을 읽으면 두 번 센다. 전송 건수는 세지 않는다. `resubmit`이 `void`이고 전송 완료는 externalizer가 나중에 따로 표시한다. 알림에는 쓰지 않고 runbook의 진단(매 주기 500건을 읽는지)에 쓴다. 고치는 곳은 `resubmitAndPurge`의 `failed.resubmit(...)` 한 줄과 생성자다. #94가 고치는 KDoc과 `markStalePublicationsFailed`는 건드리지 않는다.
- `lock.fail.open`(counter, `lock_fail_open_total{lock, reason}`): `reason`은 `timeout`(대기 3초 초과)과 `error`(Redis 오류)다. `lock`은 키의 앞 두 마디(`order:create-lock`, `payment:checkout-lock`, `platform:event-publication`, `payment:unknown-resolve`)다. 키 규칙 "모듈:이름:식별자…"를 `Lock` KDoc에 적는다. 이 규칙을 어기는 키는 식별자가 label로 새어 시계열이 폭증한다. `platform`은 업무 모듈의 키를 모르므로 미리 등록하지 않는다. 그래서 처음 일어난 fail-open은 `increase()`가 놓칠 수 있다. 규칙은 2분 창이 10분 동안 이어질 때만 울리므로 감수한다. Redis 장애 때는 주기 작업 둘이 pod마다 1분에 한 번 락을 잡아 창이 끊기지 않는다.
- `sync.operation`(counter, `sync_operation_total{status}`): 배치 항목 결과가 정해질 때마다 1씩 오른다. 뒤 항목이 인프라 예외로 실패해도 그 전에 커밋된 항목은 세어진다(범위 조정 13). `status`는 `APPLIED`·`DUPLICATE`·`REJECTED`·`GAP`·`CONFLICT`이고 기동할 때 다섯 값을 0으로 등록한다. 시스템 설계 7절의 `sync_gap_total`은 `status="GAP"`이다. 이름을 설계와 다르게 둔 이유는 CONFLICT(지금 WARN 로그로만 보인다)도 같은 counter로 알림을 걸기 위해서다.
- `sync.convergence`(timer, `sync_convergence_seconds`): 단말이 operation을 만든 시각부터 서버가 처음 확정한 시각까지다. SLO bucket 1s·10s·1m·5m·15m·1h·6h·24h만 낸다. Micrometer 기본 bucket의 상한(30초)으로는 오프라인 수렴을 담지 못한다. 알림은 걸지 않는다. 긴 값은 매장 네트워크가 오래 끊겼다는 뜻이라 플랫폼이 조치할 것이 없다.

**5. sync 요청에 operation 생성 시각(`createdAt`)을 더한다.**
- `CREATE_ORDER`·`REQUEST_CHECKOUT` 항목의 선택 필드다. ISO-8601 offset 시각(예: `2026-10-21T09:00:00+09:00`) 문자열이다. 없으면 수렴 시간을 기록하지 않는다. 시각이 없는 기존 단말과 시연 화면의 배치는 그대로 받는다.
- payload hash에 넣지 않는다. 같은 operation을 시각만 다르게 재전송해도 `CONFLICT`가 되지 않는다. 그래서 `OrderPayload`·`CheckoutPayload`가 아니라 `SyncCommand.ReceiveBatch.Operation`에 둔다.
- 저장하지 않는다. 수렴 시간은 metric에만 남는다. 도메인 모델 "스키마 변경 검토"의 필요성 기준("나중에 채울 수 있으면 넣지 않는다")을 따른다.
- 처음 확정할 때(`APPLIED`·`REJECTED`) 한 번만 기록한다. `DUPLICATE`·`GAP`·`CONFLICT`에서는 기록하지 않는다. 확정 시각은 주입한 `Clock`으로 잰다.
- 단말 시계가 앞서 차이가 음수면 기록하지 않는다. 시각이 어긋나도 배치를 거절하지 않는다. 오프라인 POS가 sync에서 막히면 안 되기 때문이다. 단말 시계가 늦은 만큼 수렴 시간이 크게 잡히는 것은 알아낼 방법이 없다(한계).
- 형식이 틀린 `createdAt`(offset이 없는 시각, 숫자, 날짜가 아닌 문자열)은 거절하지 않고 없는 것으로 다룬다. 저장하지 않는 진단용 필드가 배치 전체를 400으로 막으면 그 단말은 같은 봉투를 계속 다시 보낸다. 금액·수량을 raw로 받는 이유와 같다. 처음 확정한 항목의 값이 형식에 맞지 않으면 counter `sync.created-at.invalid`(Prometheus `sync_created_at_invalid_total`, 기동 때 0)가 올라 단말의 형식 버그를 알 수 있다. 요청 DTO는 문자열로 받고 Facade가 파싱한다. REST Docs의 요청 필드에 형식과 이 처리를 적는다(범위 조정 14).

**6. trace 구성.**
- 의존성: `spring-boot-starter-opentelemetry`(Micrometer Tracing OTel bridge와 OTLP exporter)와 `spring-modulith-observability-core`(runtime, 모듈 API 호출 span과 `module_events_published` counter)를 더한다. 버전은 Boot·Modulith BOM이 관리한다.
- 샘플링은 1.0이다(`management.tracing.sampling.probability`, Boot 기본 0.1). 로그에 찍힌 traceId가 모두 Tempo에 있어서 runbook이 trace에 기댈 수 있다. 환경 변수로 낮출 수 있다. 샘플러는 Boot 기본 `parent-based-trace-id-ratio`라 consumer는 producer의 결정을 따른다.
- 수집 경로: 앱 → Alloy(OTLP HTTP 4318) → lgtm의 Tempo다. metric·로그와 같은 수집기를 쓴다. 앱의 OTLP endpoint는 compose 환경 변수로만 준다. endpoint가 없는 실행(IDE·테스트)에서는 exporter를 만들지 않는다(아래 "확인 결과").
- Kafka 전파: `spring.kafka.template.observation-enabled`와 `spring.kafka.listener.observation-enabled`를 켠다. 둘 다 Boot 기본값은 `false`다(spring-boot-kafka 4.1.1 `KafkaProperties`). 켜면 template이 레코드 header에 trace context를 싣고, listener가 그것을 읽어 consumer span을 이어 붙인다. Spring Kafka 4.1.1 문서에 따르면 그 대가로 Spring Kafka의 Micrometer timer를 observation이 맡는다(이름과 태그가 바뀔 수 있다). 알림 규칙은 이 timer를 쓰지 않는다. DLT 전송(`DeadLetterPublishingRecoverer`)도 같은 template을 쓰므로 같은 trace에 남는다.
- Modulith externalization: externalizer(`EventExternalizerModuleListener`)는 `@ApplicationModuleListener`라 커밋 뒤 Boot의 `applicationTaskExecutor` 스레드에서 `KafkaOperations.send(Message)`로 보낸다(Modulith 2.1.1 `KafkaEventExternalizerConfiguration` 소스). 요청 스레드의 trace를 그 스레드로 넘기도록 `spring.task.execution.propagate-context`를 켠다. Boot 기본값은 `false`다(Boot 4.1.1 `TaskExecutionProperties`). 켜지 않으면 producer span이 새 trace로 끊긴다.
- 로그: Micrometer Tracing이 MDC에 `traceId`·`spanId`를 넣고, Boot의 ECS 형식은 MDC 쌍을 모두 JSON에 넣는다(Boot 4.1.1 logging 문서). 실제 필드 이름은 compose에서 확인해 결과 절에 적는다. Alloy가 그 traceId를 Loki의 label이 아니라 structured metadata `trace_id`로 올린다. otel-lgtm의 Tempo 데이터소스는 trace에서 로그로 갈 때 `| trace_id = "…"`로 거르고, Loki 데이터소스는 `trace_id`에서 Tempo로 링크한다(이미지의 `grafana-datasources.yaml`에서 확인).
- 재전송 작업이 다시 보낸 메시지는 원래 요청의 trace를 잇지 못하고 그 주기 작업의 trace에 남는다. 발행 기록에 trace context가 저장되지 않는다(한계).

**7. runbook은 `docs/wiki/runbook.md` 한 페이지다.** 규칙 이름마다 절을 두고, 절마다 뜻 → 먼저 볼 것(PromQL·Loki 쿼리·trace) → 조치 → 하지 말 것 순서로 적는다. 탐지 SQL과 재전송 규칙은 [도메인 모델](../wiki/domain-model.md) §4.3, 재투입 전 확인은 [이벤트 계약](../wiki/event-contract.md) 8절에 있는 것을 링크만 한다. DLT 재투입 절차는 #91이 맡으므로 지금은 §4.3의 절차를 링크하고, #91이 운영 API를 만들면 그 PR이 링크를 바꾼다.

**8. 규칙 검증은 `./gradlew test`에 넣는다.**
- `docker/prometheus/alerts.test.yaml`(promtool 단위 테스트)을 JUnit 테스트가 Testcontainers로 `prom/prometheus:v3.15.0`을 띄워 `promtool test rules`로 돌린다. 로컬 lgtm의 Prometheus와 같은 버전이다. `for:`, 시계열이 사라지는 경우, 첫 DLT series, 기동 직후, 작업이 한 번도 성공하지 못한 경우를 시나리오로 둔다. `promtool check rules`도 함께 돈다.
- 같은 테스트 클래스가 규칙 식에서 metric 이름을 뽑아 앱의 `/actuator/prometheus`에 있는지 대조한다. 코드가 metric 이름을 바꿨는데 규칙을 고치지 않으면 규칙이 조용히 아무것도 보지 못하는데, promtool 테스트는 가짜 series로 돌아서 이것을 잡지 못한다. 기동 직후에는 없는 이름은 이유를 적은 예외 목록으로 둔다. 처음 fail-open될 때 생기는 `lock_fail_open_total`, 테스트에서 돌지 않는 주기 작업의 `tasks_scheduled_execution_seconds_count`, Micrometer가 60초마다 묶는 `kafka_consumer_fetch_manager_records_lag`다(범위 조정 1·6). 예외로 둔 이름이 규칙에서 빠지면 테스트가 목록도 고치라고 실패한다.

### Kafka·Modulith 전파 근거
- [Spring for Apache Kafka 4.1.1, Micrometer Observation](https://docs.spring.io/spring-kafka/reference/kafka/micrometer.html): template과 container의 `observationEnabled`를 켜면 observation이 timer를 맡는다. 배치 listener는 기본적으로 observation을 만들지 않는다. 이 저장소의 listener는 레코드 단위라 해당하지 않는다.
- [Spring Boot 4.1.1, Tracing](https://docs.spring.io/spring-boot/reference/actuator/tracing.html): `spring-boot-starter-opentelemetry`, 기본 샘플링 10%, MDC의 `traceId`·`spanId`.
- [Spring Boot 4.1.1, Observability의 Context Propagation](https://docs.spring.io/spring-boot/reference/actuator/observability.html): 자동 구성된 `AsyncTaskExecutor`는 `spring.task.execution.propagate-context`로 opt-in한다.
- [Spring Modulith 2.1.1, Production-ready Features](https://docs.spring.io/spring-modulith/reference/production-ready.html): `spring-modulith-observability-core`가 모듈 API 빈 호출에 span을 만든다.
- 기본값은 문서가 말하지 않는 것을 sources jar나 기동 로그로 확인한다. 대상은 OTLP exporter의 batch span processor(대기열 크기·보내는 주기·export 시간 상한), consumer span이 producer span의 자식인지 link인지, ECS의 MDC 필드 모양이다.
- 확인 결과(2026-10-21)
  - OTLP span exporter는 `management.opentelemetry.tracing.export.otlp.endpoint`가 있을 때만 만들어진다(spring-boot-micrometer-tracing-opentelemetry 4.1.1 `OtlpTracingConfigurations`의 `@ConditionalOnProperty`). 그래서 IDE와 테스트에서는 exporter가 없고 연결 오류도 남지 않는다. Boot는 `SpanExporter` 빈을 모두 batch span processor 하나에 모은다(`OpenTelemetryTracingAutoConfiguration`). 테스트의 in-memory exporter가 이 경로로 붙는다.
  - batch span processor 기본값: 대기열 2048, 한 번에 512, 5초마다 보내고 export 상한 30초다. OTLP 호출 상한과 연결 상한은 10초, transport는 `http`, 압축은 없다(Spring Boot 4.1.1 application properties 부록). 대기열이 차면 span을 버리고 요청 스레드는 막히지 않는다.
  - 소비 span은 전송 span의 자식이다(link가 아니다). 로컬 Tempo에서 네 토픽 모두 소비 span의 부모가 같은 토픽의 전송 span이었다. 전송 span의 부모는 커밋을 감싼 `TxRunner` 호출의 Modulith span(`runTx`, 락 경로는 `runNewTx`)이었다.
  - span 속성: 전송 span은 `messaging.destination.name`, 소비 span은 `messaging.source.name`에 토픽을 둔다(spring-kafka 4.1.1 `KafkaTemplateObservation`·`KafkaListenerObservation`).
  - ECS 로그는 MDC의 `traceId`·`spanId`를 최상위 필드로 싣는다(예: `"message":"audit action=OWNER_SIGNED_UP …","traceId":"f5e94bba…","spanId":"43fed43d…"`). Alloy는 이 `traceId`를 structured metadata `trace_id`로 올린다.

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
- 결과 불명 gauge(`payment_attempt_unknown`·`payment_attempt_reconciliation_pending`)는 기동 직후 첫 주기(1분)까지 0이다. replica가 하나이거나 전부 재기동하면 `UnknownPaymentResolutionStuck`(2h)·`ReconciliationPendingUnresolved`(24h)의 `for:`가 처음부터 다시 센다. 매일 배포하면 24시간 규칙은 사실상 울리지 않는다. replica가 여럿이면 다른 pod가 값을 내 유지된다. 근본 해법(가장 오래된 `UNKNOWN` 시도와 미해소 case의 나이 gauge)은 payment·reconciliation 변경이라 후속으로 넘긴다(범위 조정 10).
- `kafka_consumer_fetch_manager_records_lag`는 앱의 Kafka 클라이언트가 내는 값이다. consumer가 없거나 fetch가 멈추면 사라지거나 마지막 값에 멈춘다. 운영에서는 브로커 쪽 lag도 따로 본다.
- `/actuator/prometheus` scrape와 health probe도 샘플링 1.0으로 trace가 된다(scrape 15초마다). 비용이 보이면 actuator 요청의 observation을 뺀다. 그때 요청 metric도 함께 빠진다.
- 실제 PG를 붙여 결과 불명 조회 배치가 락 TTL(10초)이나 대기(3초)를 넘기면 주기 작업 락의 `timeout` fail-open이 늘어 `LockFailOpen`이 울 수 있다. 실제 PG PR이 조회 타임아웃과 함께 다시 본다.
- 단말 시계가 1970년으로 초기화된 값처럼 아주 오래된 `createdAt`은 그대로 기록돼 `_sum`·`_max`를 키운다. bucket 분포는 `+Inf`에만 더해진다.
- 앱 컨테이너는 늘 Alloy로 span을 보낸다. `obs` profile 없이 띄우면 OTel exporter가 ERROR(`Failed to export spans`)를 남긴다(Alloy를 멈춘 25초 동안 1줄, 2026-10-21 실측).
- 로컬 compose의 Alloy·otel-lgtm은 `latest` 태그다(로컬 개발 전용 이미지의 기존 사용자 결정). 실측은 otel-lgtm v0.37.0(Prometheus 3.15.0)이고 `AlertRulesTest`는 그 버전으로 고정했다. 이미지가 바뀌어 `/otel-lgtm/prometheus.yaml` 경로나 Prometheus 버전이 달라지면 compose 실측을 다시 한다.
- Modulith가 `platform` 빈 호출마다 span을 만든다(checkout trace 32개 중 14개). 끄면 trace가 끊기거나 부모가 사라져 그대로 둔다(범위 조정 3). 다시 볼 조건: 부하테스트에서 span 저장량이 문제가 될 때, Modulith에 모듈을 골라 끄는 설정이 생길 때.
- 한 JVM에 컨텍스트가 여럿 뜨면 Modulith 모듈 span은 처음 뜬 컨텍스트의 registry로 간다(범위 조정 7). 테스트에서만 드러나고 `Tx`·`Lock`의 전역 한계와 같은 종류다.

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
1. `docker/prometheus/alerts.yaml`이 위 14개 규칙을 담고 `promtool check rules`를 통과한다. 규칙마다 `severity` label과 `summary`·`runbook_url` annotation이 있고, `runbook_url`의 앵커가 `docs/wiki/runbook.md`의 절과 맞는다(테스트로 대조한다).
2. `promtool test rules`가 규칙별 경계를 통과한다. 첫 DLT series, scrape 실패 뒤 옛 DLT를 다시 울리지 않음, DLT 알림의 24시간 유지, 발행 기록의 나이가 1시간을 넘을 때, 작업이 기동한 뒤 한 번도 성공하지 못할 때, 신선도가 기동 직후 0일 때, 짧은 503·fail-open·gap 묶음은 울리지 않음, `/actuator`의 503은 울리지 않음, 앱 metric이 모두 사라짐을 포함한다.
3. 규칙이 쓰는 metric 이름이 앱의 `/actuator/prometheus`에 있다(예외 목록 밖).
4. 락이 fail-open되면 `lock_fail_open_total{lock="키 앞 두 마디", reason="timeout" 또는 "error"}`가 오르고, 기동할 때 토픽마다 `event_kafka_dead_letter_failed_total`이 0으로 나간다.
5. 재전송 한 주기에 읽은 `FAILED` 행 수만큼 `event_publication_resubmit_selected_total`이 오른다.
6. sync 배치의 항목 결과마다 `sync_operation_total{status}`가 오르고, 다섯 status가 기동할 때 0으로 나간다. 뒤 항목이 인프라 예외로 실패해도 그 전에 커밋된 항목은 세어진다.
7. `createdAt`이 있는 항목이 처음 확정되면 `sync_convergence_seconds`에 한 번 기록된다. `createdAt`이 없거나, 차이가 음수거나, 결과가 `DUPLICATE`·`GAP`·`CONFLICT`면 기록되지 않는다. `createdAt`만 다른 재전송은 `DUPLICATE`다. 형식이 틀리면 배치는 정상 처리되고 수렴 시간 대신 `sync_created_at_invalid_total`이 오른다. REST Docs의 요청 필드에 `createdAt`이 있다.
8. 주문 생성과 checkout 요청 하나가 만든 HTTP span, Kafka producer span, consumer span, 그 consumer가 낸 다음 이벤트의 producer span이 같은 traceId다(통합 테스트).
9. consumer 스레드의 로그 줄에도 그 traceId가 실린다(통합 테스트, 평문). ECS 줄의 필드 이름(`traceId`)과 Loki `trace_id`는 compose에서 확인한다.
10. `docker compose -f docker/docker-compose.yaml --profile app --profile obs up -d --build`에서 다음을 확인하고 결과를 이 spec에 남긴다.
    - Prometheus가 규칙을 모두 싣고(첫 실측 때 13개), 실제로 일으킨 장애 하나 이상에서 해당 규칙이 firing이 된다. 예: Redis를 멈춰 LockFailOpen과 OwnerSessionStoreUnavailable, DLT를 일으켜 KafkaDeadLetter.
    - Tempo에서 checkout 요청의 trace가 HTTP → Kafka → consumer로 이어진다. Modulith 모듈 span도 보인다.
    - Loki에서 그 traceId로 로그가 조회되고, Grafana에서 trace와 로그가 서로 링크된다.
11. 기존 테스트가 모두 통과한다.

## 검증
- 인수 1~3: `./gradlew test --tests 'com.resilientpos.platform.AlertRulesTest' --tests 'com.resilientpos.platform.ObservabilityConfigurationTest'`. Docker가 필요하다.
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
- [도메인 모델](../wiki/domain-model.md): sync의 `createdAt`(저장하지 않고 payload hash에도 넣지 않는 선택 필드)을 §4.7 스키마 변경 검토의 sync 표 문단에 한 줄 더한다(범위 조정 8). 용어집에 새 단어는 없다.
- [이벤트 계약](../wiki/event-contract.md): 5절의 "이 reason의 counter는 처음 DLT로 보낼 때 생겨 `increase()` 알림이 첫 건을 놓칠 수 있으니 배포 뒤 DLT를 직접 확인한다"를 규칙 처리(결정 3)로 바꾼다.
- README: "OpenTelemetry trace는 아직 없습니다"를 고치고 기술 표의 운영 상태 확인 행에 OpenTelemetry를 더한다.
- Modulith·Kafka 키워드(볼트): PR 전에 그 문서의 갱신 규칙대로 Kafka observation과 externalization 전파 항목을 고친다.
- 볼트의 현재 상태 문서는 머지 뒤 고친다.
- 요구사항 변화: 없다. 시스템 설계 7절이 정한 파이프라인(trace, alert → runbook)과 필수 signal 중 `sync_gap_total`·`sync_convergence_seconds`를 채운다. `sync_gap_total`은 `sync_operation_total{status="GAP"}`로 이름이 다르다(결정 4). `payment_unknown_total`·`payment_reconciliation_age_seconds`·publication oldest age는 이 PR이 더하지 않는다. gauge `payment_attempt_unknown`·`payment_attempt_reconciliation_pending`과 `failed` 1시간 규칙이 같은 질문에 답한다.

## 분할 계획
한 개발 컨텍스트(이슈 #76 관측 마무리)다. 사용자 결정으로 trace도 함께 담는다(결정 1).

겹침 확인(2026-10-21, origin/main c7810af):
- 열린 PR #94(refactor/modulith-2-1-2-resubmit, draft): `build.gradle.kts`, `EventPublicationResubmitter.kt`, `EventPublicationResubmitterIntegrationTest.kt`, `docs/wiki/domain-model.md`를 고친다. `build.gradle.kts`는 33행만 고치므로 새 의존성 줄을 그 근처에 두지 않는다. `EventPublicationResubmitter.kt`는 #94가 KDoc(16~31행)과 `markStalePublicationsFailed`(70~84행)를 고친다. 이 PR은 생성자와 65행(`failed.resubmit`)만 고친다. 선택 건수 테스트는 다른 파일에 두고, 재전송 통합 테스트 파일은 생성자를 직접 부르는 193행과 import 한 줄만 고친다(범위 조정 4). `domain-model.md`는 #94가 §4.3을, 이 PR이 §4.7을 고친다.
- 진행 중인 #91(feature/platform-operator-dlt-redrive, 아직 커밋 없음): `docs/wiki/observability.md`가 겹칠 수 있다. push·PR 생성·머지 직전에 다시 확인하고, 먼저 머지된 쪽에 맞춰 rebase한다.
  - (2026-10-21) #91 세션 확인: "지금 있는 것"의 "점주 세션 저장소(Redis)" 항목 바로 앞에 항목 하나만 더하고 기존 줄은 고치지 않는다. DLT 재투입 절차는 domain-model §4.3의 같은 자리에서 API 절차로 바뀌므로 runbook 링크는 그대로 둔다. 그 항목의 알림 후보(재투입 counter, `/admin` 503)는 #91이 정한다.
- 열린 draft PR #102(feature/inbound-rate-limit, 2026-10-21 확인): `docs/wiki/observability.md`, `src/main/resources/application.yaml`, `SyncControllerTest.kt`가 겹친다. `git merge-tree`로 시험하면 application.yaml과 테스트는 자동으로 합쳐지고 wiki는 충돌한다. #102가 "단말 빈도 상한" 항목을 더하는 자리가 이 PR이 고친 "로그" 항목 바로 옆이라서다. 나중에 머지되는 쪽이 두 항목을 모두 살린다. #102의 새 응답은 429이고 빈도 상한은 fail-open이라 `OwnerSessionStoreUnavailable`의 전제(지금 503은 세션 저장소 장애뿐)는 그대로다. #102의 `inbound_rate_limited_total`에 알림을 걸려면 `docker/prometheus/alerts.yaml`에 규칙을 더한다.
- perf/k6-capacity-measurement(#77, PR 없음): 이 PR 머지 뒤 rebase해서 `RedisDistributedLock.lockName`을 그대로 쓴다(그 세션 확인). 이 함수의 이름과 위치를 바꾸면 그쪽에 알린다.

## 범위 조정
1. **작업 생존의 기준을 `up`에서 `process_uptime_seconds`로 바꿨다.** `up`은 Prometheus가 만드는 series라 `application` label이 없어서, 여러 서비스가 한 Prometheus를 쓰면 이 앱만 거를 수 없다. `process_uptime_seconds{application="ResilientPOS"}`는 인스턴스가 살아 있을 때만 나오고, 기동 직후 유예(5분)를 값으로 줄 수 있다. 그래서 이름 대조 테스트의 `up` 예외도 필요 없어졌다.
2. **OTLP metric registry를 끈다.** `spring-boot-starter-opentelemetry` 4.1.1이 `micrometer-registry-otlp`를 runtime으로 들이고, Boot의 `management.otlp.metrics.export.enabled` 기본값은 `true`다(spring-boot-micrometer-metrics 4.1.1 설정 metadata). metric은 Prometheus scrape 한 경로로만 내보내므로 `application.yaml`에서 끈다.
3. **Modulith의 `platform` span을 그대로 둔다(사용자 결정).** checkout trace 하나가 span 32개이고 그중 14개가 `platform` 빈 호출(`TxRunner` 5, 응답 advice 4, 보안 필터·락 등)이었다. 처음에는 `ObservationPredicate`로 `platform` 모듈만 끄기로 했다. 그런데 실측에서 Kafka 전송 span이 새 trace로 시작했다. 커밋 뒤 리스너 제출이 `TxRunner` observation 안에서 일어나서, 그 observation이 no-op이 되면 executor로 넘긴 context에 요청의 span이 없기 때문이다. span은 만들되 내보내지 않는 `SpanExportingPredicate`도 검토했다. 하지만 Kafka 전송 span의 부모가 바로 `TxRunner` span이라 Tempo에서 부모 없는 span이 된다. 두 결과를 보고 사용자가 그대로 두기로 했다. 다시 볼 조건은 한계 절에 더했다.
4. **재전송 통합 테스트 파일을 두 줄 고쳤다.** `보존 기간이 0 이하면 만들지 않는다` 테스트가 `EventPublicationResubmitter` 생성자를 직접 불러서, 생성자에 `MeterRegistry`를 더하자 컴파일이 깨졌다. 193행에 `SimpleMeterRegistry()` 인자, import 한 줄을 더했다. #94의 이 파일 hunk는 166~187행이라 사이에 바뀌지 않는 줄이 다섯 있다. `git merge-tree --write-tree HEAD origin/refactor/modulith-2-1-2-resubmit`가 충돌 없이 끝났다(2026-10-21).
5. **test task의 입력에 `docker/prometheus`를 선언했다.** `AlertRulesTest`와 이름 대조 테스트는 이 디렉터리의 파일을 읽는데, Gradle은 그것을 test task의 입력으로 몰라서 규칙만 고치면 테스트를 up-to-date로 건너뛰었다(이름을 틀리게 바꾼 규칙으로 실측). `build.gradle.kts`의 `tasks.withType(Test)` 블록에 `inputs.dir`을 더했다. #94가 고치는 33행과 멀다.
6. **이름 대조의 예외에 `kafka_consumer_fetch_manager_records_lag`를 더했다.** Kafka 클라이언트가 파티션의 첫 fetch 뒤에 만들고, Micrometer `KafkaMetrics`가 새 클라이언트 metric을 60초마다 묶는다(micrometer-core 1.17.1 `DEFAULT_REFRESH_INTERVAL`). 테스트의 대기 20초 안에 나오지 않는다. 이름은 Kafka 클라이언트가 정하므로 이 저장소의 이름 변경과 무관하다.
7. **trace 통합 테스트는 Modulith 모듈 span을 단언하지 않는다.** Modulith가 모듈별 interceptor를 정적 캐시(`ModuleEntryInterceptor.CACHE`, 2.1.1)에 두어, JVM에서 먼저 뜬 다른 테스트 컨텍스트의 `ObservationRegistry`를 계속 쓴다. 단독 실행에서는 보이고 전체 실행에서는 보이지 않았다. 운영은 JVM에 컨텍스트가 하나라 영향이 없다. 모듈 span은 compose 실측(인수 10)으로 확인한다.
8. **도메인 모델은 §4.6이 아니라 §4.7을 고친다.** §4.6은 컨텍스트 사이 동기 호출의 규칙이고, "저장하지 않는다"는 결정은 §4.7 스키마 변경 검토의 sync 표 문단이 맞는 자리다.

multi-review(Claude 76, Codex 74, Gemini는 CLI 없음) 뒤 사용자 결정으로 아래를 바꾼다.

9. **`EventPublicationFailed`(`failed > 0 for: 1h`)를 `EventPublicationOverdue`(가장 오래된 미완료 기록의 나이 > 1시간, `for: 5m`)로 바꾼다.** 재전송 작업이 행을 `RESUBMITTED`로 바꾸면 전송이 실패로 끝날 때까지(브로커 장애 때 약 2분) 그 행은 `failed`에 잡히지 않는다(`EventPublicationMetrics`의 조회가 `FAILED`와 null만 센다). 행이 적으면 갱신 순간 `failed`가 0이 되어 series가 사라지고 `for: 1h`가 처음부터 다시 센다. 역직렬화할 수 없는 행도 `RESUBMITTED`로 5분씩 머물러 같다. 나이는 상태와 무관하게 늘어난다. 새 gauge는 `EventPublicationMetrics`의 같은 갱신에 조회 하나를 더한다(#94와 겹치지 않는 파일).
10. **앱 부재 규칙 `ResilientPosMetricsAbsent`(critical)를 더한다.** 13개 규칙이 모두 앱의 series에 기대서, 모든 pod가 죽거나 scrape가 끊기면 아무것도 울리지 않는다. `absent(process_uptime_seconds{application="ResilientPOS"})`에 `for: 5m`이다. 기준선이 필요 없는 규칙이라 제외 사유에 해당하지 않는다. 결과 불명 gauge의 재기동 리셋은 한계 절에 적고 나이 gauge는 후속으로 넘긴다.
11. **DLT 두 규칙에 `keep_firing_for: 24h`를 더한다.** 증가 조건은 10분 뒤 풀려 처리하지 않은 DLT가 남아 있어도 정상처럼 보였다. 미해소 건수 gauge는 #91의 재투입 기록과 함께 후속으로 넘긴다.
12. **감지 창을 줄이고 규칙 식 둘을 고친다.** (a) 창과 `for:`가 같아 Redis가 1~2분 끊겨도 회복한 뒤에 critical이 울렸다. 503·락은 `[2m]`, gap은 `[5m]`으로 줄인다. (b) 503 규칙이 `/actuator/health`의 503(DB health DOWN, 기동·종료 중 readiness)까지 셌다. `uri!~"/actuator/.*"`로 뺀다. 필터 단계의 503은 `uri="UNKNOWN"`이라 그대로 잡힌다. (c) 첫 DLT 절의 `offset 10m` instant 조회는 scrape가 한 번 실패한 자리의 stale marker를 "없던 series"로 보아 옛 DLT를 다시 울렸다. `last_over_time(…[1h] offset 10m)`로 바꾼다. 각 반례를 promtool 시나리오로 둔다.
13. **sync counter를 항목 결과가 정해질 때마다 올린다.** 배치 끝에 모아 세면 뒤 항목의 인프라 예외로 앞에서 커밋된 항목이 영영 세지지 않았다. 재전송하면 `DUPLICATE`로만 세어지고, 같은 항목의 수렴 시간은 기록돼 두 metric이 어긋났다.
14. **형식이 틀린 `createdAt`을 거절하지 않는다.** 결정 5를 바꾼다. 요청 DTO는 문자열로 받고, Facade가 확정할 때 파싱해 실패하면 수렴 시간 대신 `sync_created_at_invalid_total`을 올린다. 400 문서는 지운다.
15. **테스트와 문서를 보강한다.** runbook 앵커 대조, `promtool check config`, 실패할 때 promtool 출력을 메시지에 싣기, Kafka metric 테스트의 다섯 번째 토픽, consumer 스레드 로그의 traceId 단언을 더한다. wiki의 consumer lag 이름(`records_lag_max` → 규칙이 쓰는 `records_lag`)과 "503은 세션 저장소뿐" 문장을 고친다. runbook의 "10분에 5000건"은 pod 수와 실행 수에 따라 달라 인스턴스별 주기당 건수로 고친다.

## TDD 검증 기록
업무 동작 변경(sync 요청 필드와 metric, 락·재전송 metric)과 trace 전파에 적용한다. 규칙 파일·compose·Alloy 설정은 promtool 테스트와 compose 실측으로 대신한다. sync 세 동작은 `tdd-driver`가 한 동작씩 맡았다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 6, `SyncCommandFacadeIntegrationTest`의 `sync operation counter는 다섯 status 모두 기동 때 0 이상으로 등록돼 있다` 외 3개(적용·중복·gap, 거절·충돌, 상한 거절) | `./gradlew test --tests 'com.resilientpos.sync.*'`. 73개 중 새 4개만 실패. meter가 없어 `APPLIED ==> expected: not null`, 나머지는 증가분 헬퍼의 `checkNotNull` | 같은 명령. 73개 통과 | 같은 명령. 통과. 기존 로그의 counts 계산을 metric 증가와 합쳤다 |
| 인수 7(HTTP), `SyncControllerTest`의 `createdAt이 있는 CREATE_ORDER와 REQUEST_CHECKOUT은 Instant로 Facade에 전달되고 요청 필드에 문서화된다`, `형식이 틀린 createdAt이면 400 BAD_REQUEST이고 Facade를 부르지 않는다` | `./gradlew test --tests 'com.resilientpos.sync.internal.adapter.web.SyncControllerTest'`. `Operation.createdAt` 최소 선언 뒤 11개 중 새 2개 실패. 요청 클래스가 필드를 몰라 무시했고 mock 기대와 달라 200·400 대신 500 | `./gradlew test --tests 'com.resilientpos.sync.*'`. 75개 통과. 스니펫 `sync-receive-batch-created-at`·`sync-receive-batch-bad-created-at` 생성(뒤의 400 문서는 범위 조정 14에서 지웠다) | sealed interface의 쓰지 않는 `createdAt` 선언을 지운 뒤 `SyncControllerTest` 11개 통과 |
| 인수 7(기록), `SyncCommandFacadeIntegrationTest`의 `createdAt이 있는 APPLIED 항목은 단말이 만든 뒤 확정까지의 시간을 한 번 기록한다` 외 6개(REJECTED, 없음, 미래, `createdAt`만 다른 재전송은 DUPLICATE, GAP·CONFLICT, 기동 때 등록) | `./gradlew test --tests 'com.resilientpos.sync.internal.application.SyncCommandFacadeIntegrationTest'`. 40개 중 새 7개 실패. timer가 없어 `assertNotNull`과 헬퍼의 `checkNotNull` 실패. 기록하지 않음을 기대하는 사례도 같은 이유로 실패했다 | 같은 명령. 통과 | `./gradlew test --tests 'com.resilientpos.sync.*'`. 82개 통과 |
| 인수 4, `LockTest`의 `Redis 오류면 던지지 않고 토큰 없이 돌아오며 키 앞 두 마디와 error로 센다`, `대기 한도를 넘겨 잠금 없이 진행하면 timeout으로 센다`, `커넥션 팩토리가 닫혀도 던지지 않고 토큰 없이 돌아온다`, `metric의 lock은 키의 앞 두 마디다` | `./gradlew test --tests 'com.resilientpos.platform.LockTest'`. 생성자 인자와 키를 그대로 돌려주는 `lockName` 최소 선언 뒤 6개 중 4개 실패(count 0, `lockName` 불일치) | 같은 명령과 `LockIntegrationTest`. 6개, 5개 통과 | 같은 명령. 통과 |
| 인수 5, `EventPublicationResubmitSelectedIntegrationTest`의 `재전송 한 주기가 읽은 FAILED 행 수만큼 선택 건수가 오른다` | `./gradlew test --tests 'com.resilientpos.platform.EventPublicationResubmitSelectedIntegrationTest'`. `expected 1.0 but was 0.0` | 같은 명령과 `EventPublicationResubmitterIntegrationTest`. 1개, 7개 통과 | 같은 명령. 통과(범위 조정 4의 생성자 호출 수정 포함) |
| 인수 4, `ObservabilityConfigurationTest`의 Kafka metric 테스트에 토픽마다 `event_kafka_dead_letter_failed_total` 단언 | `./gradlew test --tests 'com.resilientpos.platform.ObservabilityConfigurationTest'`. `order.payment-requested 의 event_kafka_dead_letter_failed_total이 없다` | 같은 명령과 `KafkaRetryIntegrationTest`. 3개, 7개 통과 | 같은 명령. 통과 |
| 인수 8·9, `TracePropagationIntegrationTest`의 `checkout 요청의 trace가 Kafka 네 토픽의 전송과 소비로 이어지고 로그에 그 traceId가 남는다` | `./gradlew test --tests 'com.resilientpos.platform.TracePropagationIntegrationTest'`. 의존성만 넣고 설정 전에 실패. checkout HTTP span이 없고 잡힌 span은 Modulith span 하나였다(Boot 기본 샘플링 0.1, Kafka observation 꺼짐) | 같은 명령. 샘플링 1.0, template·listener observation, `propagate-context` 뒤 통과. 중간에 소비 span의 토픽 속성이 `messaging.source.name`임을 알고 테스트를 고쳤다 | 같은 명령을 `ObservabilityConfigurationTest`와 함께 돌려 다른 컨텍스트가 먼저 떠도 통과(범위 조정 7) |
| 인수 1~2, `AlertRulesTest`의 `알림 규칙이 promtool 검사와 단위 테스트를 통과한다` | RED 없음. 규칙과 promtool 테스트를 먼저 docker로 돌려 맞춘 뒤 JUnit으로 옮겼다(characterization). 그 전 docker 실행에서 annotation 누락과 lookback보다 긴 샘플 간격으로 실패했다. 첫 DLT 절(`unless … offset 10m`)을 지운 규칙으로는 첫 DLT 시나리오가 실패했다 | `./gradlew test --tests 'com.resilientpos.platform.AlertRulesTest'`. 통과 | 같은 명령. 통과 |
| 인수 3, `ObservabilityConfigurationTest`의 `알림 규칙이 쓰는 metric 이름이 모두 나간다` | 처음부터 통과(characterization). 규칙의 `sync_operation_total`을 `sync_operations_total`로 잠시 바꾸면 `없는 이름: [sync_operations_total]`로 실패했다(범위 조정 5의 입력 선언 뒤) | `./gradlew test --tests 'com.resilientpos.platform.ObservabilityConfigurationTest'`. 4개 통과 | 같은 명령. 통과 |
| 범위 조정 13, `SyncCommandFacadeIntegrationTest`의 `둘째 항목 저장이 인프라 예외로 실패해도 이미 커밋된 첫 항목은 APPLIED로 세고 실패한 항목은 세지 않는다` | `./gradlew test --tests 'com.resilientpos.sync.internal.application.SyncCommandFacadeIntegrationTest'`. 41개 중 새 1개 실패, `expected: {APPLIED=1.0} but was: {}` | `./gradlew test --tests 'com.resilientpos.sync.*'`. 83개 통과 | 같은 명령. 통과 |
| 범위 조정 14, `SyncCommandFacadeIntegrationTest`의 `형식이 틀린 createdAt은 항목을 정상 처리하고 수렴 시간 대신 invalid를 센다` 외 3개(REJECTED도 셈, 재전송·GAP·CONFLICT는 다시 세지 않음, `Z`와 offset은 둘 다 기록), `sync created-at invalid counter는 기동 때 0으로 등록돼 있다`. `SyncControllerTest`의 `형식이 틀린 createdAt도 200이고 원문 그대로 Facade에 넘긴다` | `./gradlew test --tests 'com.resilientpos.sync.*'`. `createdAt`을 문자열로 바꾼 최소 선언 뒤 88개 중 5개 실패(invalid counter가 없다). Controller 테스트는 타입 변경만으로 통과했다(characterization). JSON 숫자 `1729500000000`도 문자열로 받아 200이다 | 같은 명령. 88개 통과. 400 스니펫 `sync-receive-batch-bad-created-at`을 지웠다 | 같은 명령. 통과 |
| 범위 조정 9, `EventPublicationMetricsTest`의 `가장 오래된 미완료 기록의 나이를 내고 조회가 실패하면 NaN이다` | `./gradlew test --tests 'com.resilientpos.platform.EventPublicationMetricsTest'`. 13개 중 새 1개 실패, `MeterNotFoundException`(gauge가 없다) | 같은 명령과 `EventPublicationMetricsSchedulingTest`. 13개, 1개 통과 | 같은 명령. 통과 |
| 범위 조정 10~12, `alerts.test.yaml` 시나리오(앱 부재, 발행 지연과 NaN, 신선도 기동 직후 0, DLT 24시간 유지, scrape 실패 뒤 옛 DLT, 짧은 fail-open·gap·503 묶음, `/actuator` 503) | docker promtool로 먼저 돌렸다. 고친 식을 옛 식(`offset 10m` instant, uri 필터 없는 `[5m]`)으로 되돌리면 "scrape가 한 번 실패해도 옛 DLT를 다시 울리지 않는다"와 "짧은 503 묶음과 health의 503은 울리지 않는다"가 실패했다 | `./gradlew test --tests 'com.resilientpos.platform.AlertRulesTest'`. 시나리오 19개와 runbook 앵커 대조 통과 | 같은 명령. 통과 |
| 범위 조정 15, `TracePropagationIntegrationTest`의 consumer 스레드 로그 단언, `ObservabilityConfigurationTest`의 다섯 번째 토픽 | 처음부터 통과(characterization). 이름 대조의 자기 점검이 규칙에서 빠진 `event_publication_incomplete` 예외를 잡아 목록에서 지웠다 | `./gradlew test --tests 'com.resilientpos.platform.AlertRulesTest' --tests 'com.resilientpos.platform.ObservabilityConfigurationTest' --tests 'com.resilientpos.platform.TracePropagationIntegrationTest'`. 통과 | 같은 명령. 통과 |

## 결과
- 인수 1~3: `AlertRulesTest`가 `prom/prometheus:v3.15.0`의 promtool로 `SUCCESS: 13 rules found`와 단위 테스트 15개 시나리오를 통과했다. 이름 대조 테스트가 통과했고, 규칙의 이름을 틀리게 바꾸면 실패했다(TDD 기록).
- 인수 4~7: TDD 기록의 테스트가 모두 통과했다.
- 인수 8~9: `TracePropagationIntegrationTest`가 통과했다. 전체 실행 순서처럼 다른 컨텍스트가 먼저 떠도 통과한다.
- 인수 10: `docker compose -f docker/docker-compose.yaml --profile app --profile obs up -d --build --wait`로 띄워 확인했다(2026-10-21, UTC). 호스트 3000 포트를 다른 프로세스가 잡고 있어 Prometheus(9090)·Tempo(3200)·Loki(3100)는 lgtm 컨테이너 안에서 HTTP API로 조회했다.
  - 규칙: Prometheus `/api/v1/rules`가 group `resilientpos`의 규칙 13개를 모두 `health=ok`로 실었다.
  - metric: 점주 가입 → 매장 → 단말 등록 → 주문·checkout → `createdAt`이 3분 전인 sync 한 건을 보낸 뒤 `sync_operation_total`(APPLIED 1, 나머지 넷 0), `sync_convergence_seconds_bucket`(`le="300.0"`부터 1), `tasks_scheduled_execution_seconds_count`(`resubmitAndPurge`·`resolveUnknown`의 `SUCCESS`), `event_kafka_dead_letter_failed_total`(5개 토픽 0), `event_publication_resubmit_selected_total`(0), `module_events_published_total`이 조회됐다.
  - 바로 울리는 규칙: 06:44:00에 확정된 순번에 다른 operation을 보내 `CONFLICT`를 받고, `order.paid`에 형식이 틀린 메시지를 넣어 `DLT로 보냄 … reason=error`를 남겼다. 다음 평가(06:44:53)에 `SyncConflict`(warning)와 `KafkaDeadLetter`(critical, `topic=order.paid`, `reason=error`)가 firing이 됐고, 10분 감지 창이 지난 06:55에는 둘 다 풀려 있었다.
  - `for:`가 있는 규칙: 06:44:23에 Redis를 멈추고 15초마다 점주 로그인(503)과 주문 생성(락 fail-open, 주문은 201)을 보냈다. `OwnerSessionStoreUnavailable`(critical)은 06:44:53 pending, 06:50:55 이전에 firing이 됐다. `LockFailOpen`(`lock="order:create-lock"`, `reason="error"`)은 06:44:53 pending, 06:55:07 firing이 됐다. 같은 시간 `payment:unknown-resolve`·`platform:event-publication` 락도 따로 pending이었다(06:46:53부터). 06:55:08에 Redis를 다시 올렸다. 공유 compose 스택이라 다른 worktree 세션(#77, #91)에 미리 알렸고 둘 다 스택을 쓰지 않고 있었다.
  - trace: Tempo에서 `{ name = "http post /v1/orders/{orderId}/checkout" }`로 찾은 trace(`6bd4e9a4…`) 하나에 span 32개(SERVER 1, PRODUCER 4, CONSUMER 4, CLIENT 2, INTERNAL 21)가 있었다. 네 토픽의 전송·소비가 모두 이 trace 안에 있고, Modulith 모듈 span은 Fulfillment·Order·Payment·Platform·Terminal에서 나왔다.
  - 로그↔trace: Loki에서 `{service_name="ResilientPOS"} | trace_id="6bd4e9a4…"`가 이 trace의 로그 10줄(결제 승인 결과 적용, 주문 결제 반영, fulfillment 기록 등)을 돌려줬다. 앱 로그에 OTLP export 오류는 없었다.
- 인수 11: `./gradlew test` 732개 중 실패 0.
- `python3 scripts/check_pr.py --base origin/main --worktree`: PASS.
- multi-review(Claude 76, Codex 74, Gemini는 CLI 없음) 반영 뒤(범위 조정 9~15):
  - promtool: `SUCCESS: 14 rules found`, 단위 테스트 시나리오 19개 통과. `promtool check config /rules/prometheus.yaml`도 통과한다.
  - `./gradlew test` 740개 중 실패 0(07:50:58~07:54:33Z).
  - compose 재실측은 하지 않았다. 공유 compose 스택을 #77(k6 측정)이 쓰고 있어 바뀐 규칙(14개)은 promtool로만 검증했다. 앱 쪽 새 metric(`event_publication_incomplete_oldest_seconds`, `sync_created_at_invalid_total`)은 이름 대조 테스트가 `/actuator/prometheus`에서 확인한다.
  - 발행 지연 1시간의 근거(상대 비교, #77 측정): 로컬 compose 한 대, 측정 빌드 measure/k6-local 90db602(#77 d8c7e05와 이 PR 529a06d를 로컬에서만 합침), Apple M3 Pro, OrbStack CPU 11·메모리 16GiB, app `mem_limit` 1g. 500 RPS 단계(07:31:38~07:34:29Z, #91의 Testcontainers 테스트가 겹침) 뒤 미완료 발행 기록이 최대 50,784건(07:41:47Z, PUBLISHED 33,925·FAILED 15,847·RESUBMITTED 33·PROCESSING 8)까지 쌓였고, 07:53:09Z 표본에서 0건이 됐다. 회복은 약 19분이고 가장 오래된 기록(07:31:23Z 발행)의 나이는 최대 약 22분이라 `EventPublicationOverdue`(1시간)는 이 과부하에서 울리지 않는다. 그 측정 빌드에는 나이 gauge가 없어 나이는 발행 시각과 0건 시각으로 계산했다. 그동안 옛 규칙의 `failed`는 1만 5천 건대였다.
