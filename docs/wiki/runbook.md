# Runbook

알림이 울렸을 때 따라가는 절차다. 규칙의 정본은 [`docker/prometheus/alerts.yaml`](../../docker/prometheus/alerts.yaml)이고, 규칙마다 `runbook_url` annotation이 이 페이지의 같은 이름 절을 가리킨다. metric의 뜻은 [관측성](observability.md)에 있다.

절마다 뜻 → 먼저 볼 것 → 조치 → 하지 말 것 순서다. 탐지 SQL과 재전송 규칙은 [도메인 모델 §4.3](domain-model.md#43-이벤트-전달과-저장-형식)에 있고 여기서는 링크만 한다.

## 공통

- **울린 알림 보기.** 로컬은 Alertmanager가 없어 알림이 발송되지 않는다. Grafana(`localhost:3000`)의 Alerting → Alert rules에서 Prometheus 데이터소스가 관리하는 규칙의 상태를 보거나, Grafana 데이터소스 proxy로 `GET /api/datasources/proxy/uid/prometheus/api/v1/alerts`를 부른다.
- **로그에서 trace로.** Loki에서 `{service_name="ResilientPOS", level="ERROR"} |= "검색어"`로 줄을 찾는다. 줄마다 structured metadata `trace_id`가 붙어 있고, Grafana 로그 상세의 `trace_id` 링크가 Tempo의 그 trace를 연다. `orderId`·`paymentId`·`terminalId`는 label이 아니라 로그 본문에 있으므로 `|= "주문ID"`로 거른다.
- **trace에서 로그로.** Tempo에서 연 span의 "Logs for this span"이 같은 `trace_id`의 로그를 연다. 한 trace에 HTTP 요청, Kafka 전송(producer), 소비(consumer), 그 consumer가 낸 다음 이벤트가 이어진다. 재전송 작업이 다시 보낸 메시지는 원래 요청의 trace가 아니라 그 주기 작업(`resubmitAndPurge`)의 trace에 있다.
- **인스턴스.** pod마다 도는 작업과 신선도 알림에는 `instance` label이 붙는다. 같은 DB를 세는 gauge의 알림은 모든 인스턴스를 `max`로 모아 label이 없다.
- **silence.** 알림은 상태가 풀리면 스스로 풀린다. DLT 알림(`KafkaDeadLetter`)은 그 메시지를 재투입하거나 폐기를 표시하면 다음 갱신(1분)에 풀리므로 silence로 끄지 않는다. silence는 원인을 알고 고치는 동안 같은 알림이 반복되는 것을 막을 때만, 만료 시각을 두고 쓴다.

## ResilientPosMetricsAbsent

- **뜻.** ResilientPOS 인스턴스의 metric(`process_uptime_seconds{application="ResilientPOS"}`)이 5분 넘게 하나도 보이지 않는다. 앱이 모두 내려갔거나, 기동하지 못하거나, scrape 경로(Alloy·Prometheus·Mimir)가 끊겼다. 이때 다른 규칙은 모두 빈 결과라 울리지 않는다.
- **먼저 볼 것.**
  1. 배포 플랫폼의 pod 상태와 재시작 횟수, 컨테이너 로그의 마지막 줄.
  2. 기동 실패면 원인부터 본다. 브로커에 닿지 못하면 앱이 뜨지 않는다(`spring.kafka.admin.fail-fast`). DB 인증 실패(`password authentication failed`)와 Flyway 마이그레이션 실패도 기동을 막는다.
  3. 앱은 떠 있는데 metric만 없으면 수집 경로를 본다. 로컬은 Alloy 컨테이너와 그 로그, 운영은 scrape 대상과 Mimir 수집 상태다.
- **조치.** 기동을 막는 원인을 고치거나 수집 경로를 되살린다. 앱이 다시 보이면 이 알림은 풀리고 다른 규칙이 그동안의 상태를 다시 평가한다.

## EventPublicationOverdue

- **뜻.** 완료되지 않은 발행 기록 가운데 가장 오래된 것(`event_publication_incomplete_oldest_seconds`)이 1시간을 넘었다. 재전송 작업이 1분마다 다시 보내므로 브로커가 살아 있으면 저절로 나간다. 브로커 장애가 이어지거나, 영영 나가지 못하는 기록이 있다는 뜻이다. 경과 시간은 행이 `FAILED`와 `RESUBMITTED`를 오가도 계속 늘어서, `failed` 개수가 잠깐 0이 되어도 이 알림은 끊기지 않는다.
- **먼저 볼 것.**
  1. `max(event_publication_incomplete_oldest_seconds)`가 줄었다가 다시 오르면 새 기록은 나가고 있다. 계속 오르면 같은 기록이 막혀 있다. `max(event_publication_incomplete{status="failed"})`로 막힌 양을 본다.
  2. [EventPublicationResubmitterNotSucceeding](#eventpublicationresubmitternotsucceeding)이 함께 울렸으면 그 절부터 본다.
  3. `max by (instance) (increase(event_publication_resubmit_selected_total[1m]))`가 500 가까이에서 줄지 않으면 오래된 행이 한 주기의 읽기 자리(500건)를 모두 차지하고 있다. 락이 정상이면 그 주기를 처음 잡은 인스턴스만 읽으므로 인스턴스별로 본다.
  4. ERROR 로그 `Listener … not found!`(리스너가 사라진 기록), `Unexpected error occurred in scheduled task`와 역직렬화 예외(역직렬화할 수 없는 기록).
  5. [도메인 모델 §4.3](domain-model.md#43-이벤트-전달과-저장-형식)의 "영영 나가지 못하는 기록" SQL로 `listener_id`·`event_type`별 개수와 `completion_attempts`를 본다.
- **조치.** 브로커 장애면 브로커를 되살리고 기다린다. 영영 나가지 못하는 기록이면 §4.3의 조치(원인인 리스너·타입을 되살리거나, 버리기로 결정한 기록만 완료 처리)를 따른다.
- **하지 말 것.** 행을 손으로 완료 처리하거나 지우지 않는다. 곧 다시 나갈 메시지를 버리게 된다.

## EventPublicationMetricStale

- **뜻.** 그 인스턴스가 5분 넘게 발행 기록을 세지 못했다(갱신 주기 1분). 세지 못한 인스턴스는 개수를 내리고 경과 시간을 `NaN`으로 내므로 [EventPublicationOverdue](#eventpublicationoverdue)가 그 인스턴스의 값을 보지 못한다. 모든 인스턴스가 막히면 발행 지연 알림이 해소된 것처럼 풀린다.
- **먼저 볼 것.** 그 인스턴스의 WARN `발행 기록을 세지 못해 개수를 내리고 이번 갱신을 건너뛴다`와 예외, Hikari 대기(`hikaricp_connections_pending`), DB 상태.
- **조치.** DB나 커넥션 문제를 고친다. 다음 갱신에 `event_publication_incomplete_refreshed_seconds`가 다시 움직이면 풀린다.

## KafkaDeadLetter

- **뜻.** `원토픽.DLT`에 재투입도 폐기도 하지 않은 메시지가 있다(`event_kafka_dead_letter_unhandled{topic}`, `topic`은 원 토픽). consumer가 처리하지 못한 메시지라 그 주문의 다음 단계(결제 시도, `PAID` 전이, fulfillment, 대조 case)가 멈춰 있다. 메시지가 남아 있는 동안 계속 울리고, 모두 재투입하거나 폐기하면 다음 갱신(1분)에 풀린다. 인스턴스마다 세어 `max`로 묶는데, 갱신이 실패한 인스턴스는 마지막 값을 유지한다. 5분 안에 센 인스턴스가 하나라도 있으면 그 값만 보므로 한 인스턴스가 멈춰도 그 옛 값이 알림을 붙잡지 않는다. 모든 인스턴스가 세지 못하면(브로커 장애) 마지막 값으로 유지한다. 원인은 DLT로 보낼 때의 `reason`으로 가른다(아래 먼저 볼 것 1).
  - 도메인 오류 코드(예: `PAYMENT_APPROVAL_MISMATCH`): 업무 불일치다. 불일치 승인은 다시 넣어도 같은 불일치라 재투입하지 않고, 사람이 확인한 뒤 폐기를 표시한다.
  - `UNSUPPORTED_EVENT_SCHEMA_VERSION`: 롤링 배포 중 옛 pod가 새 버전 메시지를 받았다. 배포가 끝난 뒤 그대로 재투입한다([이벤트 계약](event-contract.md) 5절).
  - `error`: 재시도를 모두 썼거나 재시도 목록 밖의 예외(버그, 변환 실패)다.
- **먼저 볼 것.**
  1. `sum by (topic, reason) (increase(event_kafka_dead_letter_total[1d]))`로 어느 토픽에 어떤 `reason`이 쌓였는지 본다. counter는 재기동하면 0부터라 오래된 메시지는 목록 API의 `exception`·`exceptionMessage`로 본다.
  2. 목록 `GET /admin/v1/dead-letters?topic=원토픽.DLT&partition=…`에서 `redrive`와 `dismissal`이 모두 null인 항목이 처리하지 않은 메시지다. `key`가 `orderId`다.
  3. ERROR `DLT로 보냄 topic=… partition=… offset=… key=… reason=… exception=…`의 `trace_id`로 trace를 열어 실패한 consumer span과 예외를 본다.
- **조치.** 원인을 고친 뒤 재투입한다. 재투입 전에 [이벤트 계약 8절](event-contract.md#8-dlt-재투입-전에-볼-것)을 확인하고, 절차는 [도메인 모델 §4.3](domain-model.md#43-이벤트-전달과-저장-형식)의 "DLT 재투입"을 따른다. 넣지 않기로 한 메시지(불일치 승인, 이미 다른 경로로 처리된 주문, 시험용 메시지)는 같은 절의 폐기 표시(`POST …/dismiss`)로 사유와 함께 남긴다. 둘 다 기록이 남고 이 알림이 풀린다. 재투입했는데 WARN `audit action=DEAD_LETTER_REDRIVE_UNRECORDED`가 남고 이 알림이 풀리지 않으면 재투입 기록이 없는 것이다. 다시 재투입하거나(한 번 더 보내지고 consumer가 멱등으로 견딘다) 그 `cause`를 사유에 적어 폐기한다.
- **하지 말 것.** 원인을 고치기 전에 재투입하지 않는다. 같은 이유로 다시 DLT로 간다. 알림을 끄려고 폐기하지 않는다. 폐기는 "넣지 않는다"는 결정의 기록이다. DLT 토픽을 지우거나 보존 기간을 걸지 않는다.
- **한계.** DLT 토픽을 다시 만들면(클러스터 이전·DR) offset이 0부터 다시 시작해 옛 재투입·폐기 기록이 같은 offset의 새 메시지를 처리된 것으로 센다. 그 뒤에는 목록 API로 미처리를 직접 확인한다. 이 알림의 gauge를 처음 내는 배포 직후에는 재투입·폐기 기록이 없는 기존 DLT 메시지가 모두 미처리로 잡혀 바로 운다. 한 건씩 재투입하거나 폐기한다. 롤링 배포 중 옛 버전 pod에는 이 gauge와 폐기 endpoint가 없다. 옛 pod로 간 폐기 요청은 실패하므로 배포가 끝난 뒤 다시 보낸다. pod가 하나면 재기동하는 동안 잠깐 풀렸다가 기동 직후 첫 갱신에서 다시 운다. 같은 레코드가 폐기된 뒤 재투입되는 순간과 갱신이 겹치면 한 갱신(1분) 동안 1건 많게 셀 수 있다. 다음 갱신에 맞는다.

## KafkaDeadLetterPublishFailed

- **뜻.** 메시지를 DLT로 보내는 것 자체가 실패했다. 메시지를 버리지 않으려고 재시도 상태가 처음으로 돌아가 같은 메시지를 다시 처리하므로, 그 파티션의 뒤 메시지가 모두 멈춘다.
- **먼저 볼 것.** ERROR `DLT 전송 실패, 처음부터 다시 시도한다 topic=… original=…`의 예외. 브로커 상태, DLT 토픽(`원토픽.DLT`)이 있는지, 쓰기 권한, 메시지 크기.
- **조치.** DLT 쪽 원인을 고친다. 고치면 다음 시도에 DLT로 가고 [KafkaDeadLetter](#kafkadeadletter)가 울린다. 다시 처리해 성공한 메시지는 DLT로 가지 않는다. 실패가 멈추면 이 알림은 감지 창(10분) 뒤 풀린다.

## KafkaConsumerLag

- **뜻.** 한 파티션이 10분 넘게 1000건 넘게 밀렸다. 재시도는 blocking이라 한 메시지가 재시도 중이면 그 파티션 전체가 기다린다. DB에 닿지 못하는 실패는 횟수 없이 기다린다. 1000은 #77에서 판별만 확인된 값이다(정상 부하 lag 0, provider가 1초 느려지면 1분 실행에 lag 최대 2,398). provider 지연이 1초면 승인 파티션 하나가 초당 1건을 처리하므로 1000건은 약 17분 밀린 것이다.
- **먼저 볼 것.** WARN `소비 실패 topic=… key=… attempt=…`가 같은 key로 반복되면 그 메시지가 재시도 중이다. 예외가 커넥션 획득 실패면 DB부터 본다. 반복이 없으면 처리량이 모자란 것이라 `spring_kafka_listener_seconds`(처리 시간)와 DB 지연을 본다.
- **조치.** 원인을 고치면 재시도가 이어서 처리한다. 처리량이 모자라면 파티션 수를 바꾸는 PR로 다룬다([도메인 모델 §4.3](domain-model.md#43-이벤트-전달과-저장-형식) 토픽 항목). 환경 변수로 바꾸지 않는다.

## EventPublicationResubmitterNotSucceeding

- **뜻.** 그 인스턴스의 재전송 작업(`EventPublicationResubmitter.resubmitAndPurge`)이 20분 안팎 동안 한 번도 끝까지 성공하지 못했다. 브로커로 보내지 못한 기록이 다시 나가지 않고 완료 기록도 지워지지 않는다. 기동한 뒤 5분은 보지 않는다.
- **먼저 볼 것.**
  1. `tasks_scheduled_execution_seconds_count{code_function="resubmitAndPurge"}`의 `outcome="ERROR"`가 늘면 실행이 던지고 있다. `exception` label이 예외 클래스다.
  2. 아무 outcome도 늘지 않으면 실행이 걸려 있거나 스케줄러가 돌지 않는다. `tasks_scheduled_execution_active_seconds_max{code_function="resubmitAndPurge"}`가 크면 실행이 걸려 있다. 공용 스케줄러 스레드는 둘(`spring.task.scheduling.pool.size`)이라 결과 불명 확정 작업이 길어지면 함께 밀린다.
  3. ERROR `Unexpected error occurred in scheduled task`와 역직렬화 예외면 역직렬화할 수 없는 기록이 실행을 끊는 것이다(spring-modulith#1904).
- **조치.** [도메인 모델 §4.3](domain-model.md#43-이벤트-전달과-저장-형식)의 재전송 항목과 "영영 나가지 못하는 기록" 절차를 따른다. 락이 fail-open이어도 작업은 성공으로 끝난다. 이 알림의 원인이 아니다.

## UnknownPaymentResolverNotSucceeding

- **뜻.** 그 인스턴스의 결과 불명 확정 작업(`UnknownPaymentResolver.resolveUnknown`)이 20분 안팎 동안 한 번도 성공하지 못했다. 결과 불명 결제가 확정되지 않고, 개수 gauge(`payment_attempt_unknown`·`payment_attempt_reconciliation_pending`)와 경과 시간 gauge(`payment_attempt_resolution_oldest_seconds`·`payment_attempt_reconciliation_pending_oldest_seconds`)가 마지막 성공값에 멈춘다. 모든 인스턴스가 멈추면 경과 시간이 늘지 않아 [UnknownPaymentResolutionStuck](#unknownpaymentresolutionstuck)이 늦게 울리거나 울리지 않는다. 한 건의 실패는 작업을 실패시키지 않는다(ERROR 로그만 남긴다). 실패는 대상 조회나 개수 세기가 던진 경우다.
- **먼저 볼 것.** `tasks_scheduled_execution_seconds_count{code_function="resolveUnknown"}`의 `outcome="ERROR"`와 `exception`, 스케줄러 ERROR 로그, DB 상태.
- **조치.** 원인(DB)을 고친다. 다음 주기(1분)에 성공하면 풀린다.

## KafkaDeadLetterMetricNotSucceeding

- **뜻.** 그 인스턴스의 미처리 DLT 갱신 작업(`UnhandledDeadLetterMetrics.countUnhandled`)이 20분 안팎 동안 한 번도 성공하지 못했다. 갱신이 실패하는 동안 `event_kafka_dead_letter_unhandled`는 마지막 성공값에 멈추고 마지막 성공 시각(`event_kafka_dead_letter_unhandled_refreshed_seconds`)도 멈춘다. 기동한 뒤 한 번도 성공하지 못했으면 값은 `NaN`이다. 다른 인스턴스가 세고 있으면 [KafkaDeadLetter](#kafkadeadletter)는 멈춘 인스턴스의 값을 5분 뒤부터 보지 않으므로 알림은 맞게 동작한다. 모든 인스턴스가 이러면 마지막 값으로 유지되어 새 DLT에 울지 못한다. 기동한 뒤 5분은 보지 않는다. severity는 warning이다. 한 인스턴스의 실패는 DLT 알림을 막지 않고, 모든 인스턴스가 실패하는 것은 브로커·DB 전체 장애라 다른 신호가 함께 나온다.
- **먼저 볼 것.** `tasks_scheduled_execution_seconds_count{code_function="countUnhandled"}`의 `outcome="ERROR"`와 `exception`, 스케줄러 ERROR 로그 `Unexpected error occurred in scheduled task`의 예외.
  - Kafka `TimeoutException`: 브로커에 닿지 못한다. 그동안 새 DLT도 재투입도 없어 마지막 값이 실제와 같다. 브로커를 되살린다.
  - `TopicAuthorizationException`·`ClusterAuthorizationException`: 앱 principal에 DLT 토픽의 Describe 권한이 없다(운영 Kafka ACL). `*.DLT`에 Read·Describe를 준다([platform-operator-dlt-redrive](../spec/2026-10-21-platform-operator-dlt-redrive.md) 결정 2의 배포 확인 항목).
  - DB 예외: 재투입·폐기 기록을 세지 못한다. DB를 본다.
- **조치.** 원인을 고친다. 다음 주기(1분)에 성공하면 풀리고 gauge가 지금 값을 낸다. 그동안 DLT는 목록 API로 직접 본다.

## UnknownPaymentResolutionStuck

- **뜻.** reconciliation으로 넘기지 않은 결과 불명 승인 시도 가운데 가장 오래된 것이 만들어진 지 2시간이 넘었다(`payment_attempt_resolution_oldest_seconds`, 시도의 `created_at`부터). 정상이면 한 건은 조회 상한 60회(약 1시간) 안에 확정되거나 넘어간다. 작업이 멈췄거나, 조회나 확정이 건마다 실패하고 있다. 경과 시간은 DB에서 다시 세므로 재기동해도 첫 갱신 뒤 5분이면 다시 운다. pod가 하나면 재기동하는 동안 잠깐 풀렸다가 다시 운다.
- **먼저 볼 것.** [UnknownPaymentResolverNotSucceeding](#unknownpaymentresolvernotsucceeding)이 함께 울렸는지. `max(payment_attempt_unknown) - max(payment_attempt_reconciliation_pending)`로 몇 건인지. ERROR `결과 불명 결제 확정에 실패했다 paymentId=…`(건마다 실패), WARN `조회로도 결과를 확인하지 못했다 … queryCount=…`의 `queryCount`가 오르는지(조회는 돌고 있다). `payment.attempt-created.DLT`에 오래 머물다 늦게 재투입된 시도는 만들어진 지 오래라 바로 이 알림에 걸린다. 그 시도는 조회 상한(약 1시간) 안에 넘어가면 풀린다.
- **조치.** 작업이나 건별 실패의 원인을 고친다. 결제 상태는 작업이 확정한다. 확정됐는데 풀리지 않으면 `max by (instance) (payment_attempt_resolution_oldest_seconds)`로 인스턴스별 값을 본다. 작업이 멈춘 인스턴스는 경과 시간이 마지막 성공값에 멈춰 `max`를 붙잡는다. [UnknownPaymentResolverNotSucceeding](#unknownpaymentresolvernotsucceeding)이 가리키는 인스턴스를 고치거나 재시작한다. [ReconciliationPendingUnresolved](#reconciliationpendingunresolved)도 같다.
- **하지 말 것.** `t_payment_attempt`의 상태를 DB에서 직접 고치지 않는다. 승인 이벤트가 나가지 않아 주문이 `PAID`가 되지 않는다.

## ReconciliationPendingUnresolved

- **뜻.** reconciliation으로 넘긴 뒤 아직 결과 불명인 승인 시도 가운데 가장 오래된 것이 넘긴 지 24시간이 넘었다(`payment_attempt_reconciliation_pending_oldest_seconds`, `reconciliation_requested_at`부터). 점주가 대조 case를 해소하지 않았거나, case 열기가 실패했다. 그 주문은 `CHECKOUT_REQUESTED`에 머물고 재결제도 막혀 있다. 경과 시간은 DB에서 다시 세므로 재기동해도 첫 갱신 뒤 5분이면 다시 운다.
- **먼저 볼 것.** 해소를 기다리는 case와 매장을 찾는다.
  ```sql
  select store_id, count(*), min(opened_at) from t_reconciliation_case
  where status = 'MANUAL_REVIEW' and deleted_at is null
  group by store_id order by 3;
  ```
  pending 수보다 case 수가 적으면 case 열기가 실패한 것이다. `payment.reconciliation-requested.DLT`를 보고 [KafkaDeadLetter](#kafkadeadletter) 절차를 따른다.
- **조치.** 플랫폼이 그 매장 점주에게 연락해, PG 가맹점 화면에서 승인 여부를 확인하고 해소하도록 안내한다. 점주는 `POST /owner/v1/stores/{storeId}/reconciliation-cases/{caseId}/resolve`로 근거와 함께 해소한다([payment-reconciliation-case](../spec/2026-10-20-payment-reconciliation-case.md)).
- **하지 말 것.** 결제나 case 상태를 DB에서 직접 고치지 않는다. case는 근거·규칙·감사 없이 해결하지 않는다.

## LockFailOpen

- **뜻.** 락(`platform.Lock`)이 잡지 못하고 그냥 진행하는 일이 10분 넘게 이어진다. 주기 작업 락(`platform:event-publication`, `payment:unknown-resolve`)의 대기 초과는 보지 않는다. pod가 여럿이면 늦은 pod가 앞 pod의 긴 블록(회복 중 재전송, PG 조회)을 기다리다 매분 대기 한도를 넘기는 것이 정상이다. 그 락의 Redis 오류는 본다. 정합성은 DB 제약이 지키므로 데이터는 깨지지 않는다. 대신 같은 요청이 겹치면 늦은 쪽이 앞 결과를 받지 못하고 경합 오류(409)로 끝나고, 주기 작업은 pod 사이에서 겹쳐 돈다. 한 번만 일어난 fail-open은 이 알림에 잡히지 않을 수 있다.
  - `reason="error"`: Redis에 닿지 못한다. [OwnerSessionStoreUnavailable](#ownersessionstoreunavailable)도 함께 울릴 것이다.
  - `reason="timeout"`: 누군가 같은 키를 대기 한도(1.5초) 넘게 쥐고 있다. `order:create-lock`·`payment:checkout-lock`이면 같은 주문의 요청이 몰리거나 락 블록 안에서 커넥션을 기다린다(#77의 400 RPS에서 보유가 11초까지 늘었다).
- **먼저 볼 것.** WARN `잠금 없이 진행한다(Redis 오류) key=…`나 `잠금 없이 진행한다(대기 1500ms 초과) key=…`의 key. Redis 상태. 대기 초과면 `histogram_quantile(0.999, sum by (le, lock) (rate(distributed_lock_held_seconds_bucket[5m])))`로 보유 시간을 본다.
- **조치.** Redis 장애면 Redis를 되살린다. timeout이면 긴 블록의 원인(DB 지연, 커넥션 대기)을 본다. 대기 한도·TTL과 Redisson을 다시 볼 조건은 [용량 기준](capacity.md#redisson이-필요해지는-시점)에 있다.

## SyncConflict

- **뜻.** 확정된 순번에 다른 내용이나 모르는 operation이 왔다. 단말과 서버의 상태가 갈라졌다. 그 항목은 아무것도 쓰지 않았고 단말 큐에서 빠지지 않는다.
- **먼저 볼 것.** WARN `sync 충돌 terminalId=… operationId=… sequence=… : 이유`. 이유가 "확정된 순번에 모르는 operation"이면 단말이 순번을 처음부터 다시 매겼을 가능성이 크다(재설치, 데이터 초기화). "이미 기록된 operation이 새 순번으로 왔다"면 단말이 operationId를 다른 순번에 다시 썼다.
- **조치.** 그 단말의 큐와 서버의 접수 기록(`t_sync_operation`, `t_sync_watermark`)을 대조하고 단말 쪽을 바로잡는다.
- **하지 말 것.** watermark나 접수 기록을 DB에서 직접 고치지 않는다. 이미 적용된 주문이 두 번 생길 수 있다.

## SyncGapPersistent

- **뜻.** 앞 순번이 확정되지 않은 항목(`GAP`)이 30분 넘게 5분 간격 안으로 계속 온다. 단말이 `lastAckedSequence` 다음부터 다시 보내지 않고 있어 그 단말의 큐가 비지 않는다. 식이 모든 단말의 합이라, 단말이 많으면 서로 다른 단말의 잠깐 생긴 gap이 이어 붙어 울릴 수도 있다. 5분과 30분은 시작값이다.
- **먼저 볼 것.** INFO `sync 배치 처리 terminalId=… lastAckedSequence=… counts={GAP=…}`로 단말을 찾는다.
- **조치.** 그 단말의 재전송 로직(응답의 `lastAckedSequence` 다음부터 보내는지)을 확인한다.

## OwnerSessionStoreUnavailable

- **뜻.** 점주 요청이 5분 넘게 `503 SERVICE_UNAVAILABLE`로 실패한다. `/actuator` 밖에서 503을 내는 곳은 점주 세션 저장소(Redis) 장애 처리와 플랫폼 운영자 경로(`/admin/**`)의 IdP·Kafka 브로커 장애 처리다. 운영자 경로의 503도 이 규칙에 잡힌다. `/actuator/health`의 503(DB health DOWN, 기동·종료 중)은 이 규칙이 세지 않는다. 단말 경로(`/v1/**`)는 Redis 장애에 막히지 않는다.
- **먼저 볼 것.** ERROR `errorCode=SERVICE_UNAVAILABLE` 로그의 `uri`. `/admin/`으로 시작하면 Redis가 아니라 IdP나 브로커 장애다(줄 끝의 `operator IdP unavailable`·`kafka broker unavailable`, [관측성](observability.md) 플랫폼 운영자 항목). 그 밖이면 Redis 상태를 본다. [LockFailOpen](#lockfailopen)(`reason="error"`)도 함께 울릴 것이다. 점주 요청이 드물면 2분 창이 끊겨 이 알림은 늦거나 울리지 않는다. Redis 장애의 주 신호는 주기 작업이 매분 만드는 `LockFailOpen`이다.
- **조치.** Redis를 되살린다. 503은 변경이 커밋되지 않았다는 뜻이 아니다. 단말 등록·자격 회전 뒤 503을 받았으면 목록으로 결과를 확인한다([관측성](observability.md) 점주 세션 저장소 항목).

## InboundStoreRateLimited

- **뜻.** 매장 bucket이 비어 그 매장 단말의 `/v1` 요청이 5분 넘게 `429 RATE_LIMITED`로 거절된다. 매장 bucket 거절은 그 매장 단말 전부를 막는다. 단말 하나는 자기 bucket에 먼저 막혀 매장 bucket을 혼자 비우지 못하고, 한 매장 단말 4대가 함께 재연결해 backlog를 보내도 매장 bucket은 비지 않았다(#77 측정, [inbound-rate-limit](../spec/2026-10-21-inbound-rate-limit.md) 결정 8). 그래서 이어지는 매장 거절은 정상 회복이 아니다. 한 매장에서 여러 단말이 함께 고장 났거나, 매장의 단말 수가 상한 값이 전제한 수(4대)보다 많다. counter에 매장 ID가 없어 이 알림은 어느 매장인지 모르고, 여러 매장이 번갈아 짧게 거절돼도 울릴 수 있다. 단말 bucket 거절은 이 알림이 보지 않는다.
- **먼저 볼 것.** Loki에서 `{service_name="ResilientPOS"} |= "빈도 상한으로 거절했다 scope=store"`로 거절 줄의 `storeId`를 본다(scope별 10초에 한 줄). 줄의 `terminalId`는 매장 bucket이 빈 순간 막힌 단말이라 원인이 아닐 수 있다. 같은 매장의 `scope=terminal` 줄에 한 `terminalId`가 되풀이되면 그 단말이 자기 상한까지 보내고 있다. 그런 줄 없이 여러 단말이 막혀 있으면 단말들이 각자 상한 안에서 합쳐 매장 bucket을 비운 것이니 그 매장의 등록 단말 수를 본다.
- **조치.** 고장 단말이면 매장에 그 단말의 재시작이나 업데이트를 요청한다(`Retry-After`를 지키는지, 같은 sync 배치를 되풀이하는지). 정상 단말이 많은 매장이면 매장 상한 값(`resilientpos.rate-limit.store.*`)을 다시 정한다. 값은 spec 결정 6의 조건(매장 capacity는 매장 단말 수 × 단말 capacity 이상)을 따른다.
- **하지 말 것.** 상한을 크게 올리거나 꺼서 알림을 끄지 않는다. 매장 bucket은 한 매장의 단말들이 다른 매장 몫의 처리량을 쓰지 못하게 하는 장치다.

## HttpLatencyAboveTarget

- **뜻.** 단말 경로 하나(`uri` label)의 5분 p95가 원문 목표를 10분 넘게 넘는다. 목표는 주문 생성(`POST /v1/orders`) 200ms, checkout(`POST /v1/orders/{orderId}/checkout`) 150ms, sync(`POST /v1/terminals/{terminalId}/sync`) 2초다([시스템 설계 §7](../references/02_System_Design.md#7-nfr-and-observability)). checkout은 승인을 Kafka 뒤로 넘겨 PG를 부르지 않는다. sync의 2초는 가장 큰 배치(500 op)의 목표라, sync가 울면 bucket 보간 오차 안에서 배치 크기와 상관없이 요청의 5% 넘게 최악 목표보다 느리다(분위수는 bucket 안을 고르게 보간한 추정값이다). 429는 빼고 보고, 5분에 그 uri 요청이 20건 미만이면 판정하지 않는다. 5분보다 짧은 튐(단말 동시 재연결)에는 울지 않는다. 앞 단계가 실패하면 뒤 단계 uri는 요청이 없어 판정하지 않는다(주문 생성이 실패하는 동안 checkout). 근거는 [slo-alert-baseline](../spec/2026-10-23-slo-alert-baseline.md) 결정 2다.
- **먼저 볼 것.**
  1. 어느 pod인지 본다. `histogram_quantile(0.95, sum by (le, instance) (rate(http_server_requests_seconds_bucket{uri="울린 uri", method="POST", status!="429"}[5m])))`. 한 pod만 느리면 그 pod의 CPU·힙·GC를 본다.
  2. 커넥션 대기. `max(hikaricp_connections_pending)`와 `max(hikaricp_connections_acquire_seconds_max)`. #77에서 무너진 단계(400 RPS)는 커넥션 대기가 먼저 쌓였다([용량 기준](capacity.md#인스턴스-하나의-용량)).
  3. 부하. 인스턴스 하나의 용량은 단말 요청 전체의 혼합 RPS라 `sum(rate(http_server_requests_seconds_count{uri=~"/v1/.*", status!="429"}[5m]))`를 [용량 기준](capacity.md#인스턴스-하나의-용량)의 인스턴스 하나 용량 × pod 수와 견준다.
  4. 느린 실패인지 본다. `sum by (status) (rate(http_server_requests_seconds_count{uri="울린 uri"}[5m]))`에 500이 있으면 커넥션 획득 대기(3초 뒤 실패)가 p95를 올리는 것일 수 있고 [HttpErrorBudgetBurn](#httperrorbudgetburn)도 함께 운다.
  5. sync만 울면 큰 배치가 몰렸는지 본다. INFO `sync 배치 처리 terminalId=…`의 항목 수와 같은 시각의 단말 수.
  6. Tempo에서 그 uri의 느린 trace를 열어 긴 span(DB 문장, 커넥션 획득, 락 대기)을 찾는다. 락 대기는 `distributed_lock_wait_seconds`로도 본다.
- **조치.** 부하가 용량을 넘으면 pod를 늘린다. 이벤트 외부화 상한은 DB 커밋에 묶여 pod를 늘려도 풀리지 않는다([용량 기준](capacity.md#인스턴스-하나의-용량)). DB 지연이면 DB를 본다. 배포 직후 시작됐으면 그 배포를 의심한다.
- **하지 말 것.** Hikari 풀을 환경 변수로 바로 키우지 않는다. #77에서 20으로 늘려도 외부화 처리량은 늘지 않았고(병목은 DB 커밋), 지연에 주는 효과는 실행기 스레드 수와 섞여 가르지 못했다. 바꾸려면 그 근거를 재는 PR로 다룬다.

## HttpErrorBudgetBurn

- **뜻.** 단말 API(`/v1/**`)의 5xx가 오류 예산을 빠르게 쓰고 있다. SLO는 원문 "주문 API 가용성 99.9%"를 30일 예산으로 둔 것이다. 1시간과 5분 창이 모두 1.44%(예산의 2%를 1시간에 씀)를 넘거나, 6시간과 30분 창이 모두 0.6%(예산의 5%를 6시간에 씀)를 넘으면 운다(Google SRE Workbook "Alerting on SLOs"). 429(빈도 상한의 정상 거절)는 분모에서도 빼고, 409와 그 밖의 4xx는 실패로 세지 않는다. 긴 창의 요청이 70건(1시간)·167건(6시간)보다 적으면 그 쌍을 판정하지 않으므로, 6시간에 167건(시간당 약 28건)보다 적은 영업 외 시간에는 전부 실패해도 울지 않는다. 시간당 70~138건이면 같은 5분 안의 실패 2건으로도 운다. 서버가 끝낸 요청만 세므로 클라이언트가 먼저 끊은 요청과 ingress·LB의 5xx는 들지 않는다. 원인이 멈추면 짧은 창이 깨끗해질 때 풀린다. 큰 장애 뒤에는 30분 창 때문에 30분쯤 더 울 수 있다. 근거는 [slo-alert-baseline](../spec/2026-10-23-slo-alert-baseline.md) 결정 3·4다.
- **먼저 볼 것.**
  1. `sum by (uri, status) (rate(http_server_requests_seconds_count{uri=~"/v1/.*", status=~"5.."}[5m]))`로 어느 경로의 어느 상태인지 본다. 어느 쌍이 울었는지는 창마다 실패율을 본다. `sum(rate(http_server_requests_seconds_count{uri=~"/v1/.*", status=~"5.."}[1h])) / sum(rate(http_server_requests_seconds_count{uri=~"/v1/.*", status!="429"}[1h]))`에서 `[1h]`를 `[5m]`·`[6h]`·`[30m]`으로 바꿔 1.44%(1시간·5분)와 0.6%(6시간·30분)에 견준다. 5분 창이 이미 깨끗한데 울고 있으면 30분 창이 빠지기를 기다리는 중이다.
  2. Loki에서 `{service_name="ResilientPOS", level="ERROR"} |= "errorCode=INTERNAL_ERROR"`의 예외를 본다. `Connection is not available, request timed out`이면 커넥션 풀이 비었다. #77에서 무너진 단계(400 RPS)의 500은 모두 이것이었다.
  3. 함께 운 알림. [HttpLatencyAboveTarget](#httplatencyabovetarget)이 함께 울면 과부하나 DB 지연일 가능성이 크다. 지연 없이 500만 나면 배포한 코드의 버그를 의심한다.
- **조치.** 원인(과부하, DB, 배포)을 고친다. 배포 직후 시작됐으면 이전 버전으로 되돌린다. 과부하면 [HttpLatencyAboveTarget](#httplatencyabovetarget)의 조치를 따른다.
- **하지 말 것.** 원인을 알기 전에 silence로 끄지 않는다. 이 알림은 critical이고, 끄는 동안 예산이 계속 줄어든다.
