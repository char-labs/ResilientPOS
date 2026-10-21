# Runbook

알림이 울렸을 때 따라가는 절차다. 규칙의 정본은 [`docker/prometheus/alerts.yaml`](../../docker/prometheus/alerts.yaml)이고, 규칙마다 `runbook_url` annotation이 이 페이지의 같은 이름 절을 가리킨다. metric의 뜻은 [관측성](observability.md)에 있다.

절마다 뜻 → 먼저 볼 것 → 조치 → 하지 말 것 순서다. 탐지 SQL과 재전송 규칙은 [도메인 모델 §4.3](domain-model.md#43-이벤트-전달과-저장-형식)에 있고 여기서는 링크만 한다.

## 공통

- **울린 알림 보기.** 로컬은 Alertmanager가 없어 알림이 발송되지 않는다. Grafana(`localhost:3000`)의 Alerting → Alert rules에서 Prometheus 데이터소스가 관리하는 규칙의 상태를 보거나, Grafana 데이터소스 proxy로 `GET /api/datasources/proxy/uid/prometheus/api/v1/alerts`를 부른다.
- **로그에서 trace로.** Loki에서 `{service_name="ResilientPOS", level="ERROR"} |= "검색어"`로 줄을 찾는다. 줄마다 structured metadata `trace_id`가 붙어 있고, Grafana 로그 상세의 `trace_id` 링크가 Tempo의 그 trace를 연다. `orderId`·`paymentId`·`terminalId`는 label이 아니라 로그 본문에 있으므로 `|= "주문ID"`로 거른다.
- **trace에서 로그로.** Tempo에서 연 span의 "Logs for this span"이 같은 `trace_id`의 로그를 연다. 한 trace에 HTTP 요청, Kafka 전송(producer), 소비(consumer), 그 consumer가 낸 다음 이벤트가 이어진다. 재전송 작업이 다시 보낸 메시지는 원래 요청의 trace가 아니라 그 주기 작업(`resubmitAndPurge`)의 trace에 있다.
- **인스턴스.** pod마다 도는 작업과 신선도 알림에는 `instance` label이 붙는다. 같은 DB를 세는 gauge의 알림은 모든 인스턴스를 `max`로 모아 label이 없다.

## EventPublicationFailed

- **뜻.** 브로커로 보내지 못한 발행 기록(`event_publication_incomplete{status="failed"}`)이 한 시간 동안 0이 된 적이 없다. 재전송 작업이 1분마다 다시 보내므로 브로커가 살아 있으면 저절로 준다. 브로커 장애가 이어지거나, 영영 나가지 못하는 기록이 있다는 뜻이다.
- **먼저 볼 것.**
  1. `max(event_publication_incomplete{status="failed"})`가 줄고 있으면 재전송이 따라잡는 중이다.
  2. [EventPublicationResubmitterNotSucceeding](#eventpublicationresubmitternotsucceeding)이 함께 울렸으면 그 절부터 본다.
  3. `sum(increase(event_publication_resubmit_selected_total[10m]))`가 주기당 500건(10분에 5000건) 가까이에서 줄지 않으면 오래된 행이 읽기 자리를 차지하고 있다.
  4. ERROR 로그 `Listener … not found!`(리스너가 사라진 기록), `Unexpected error occurred in scheduled task`와 역직렬화 예외(역직렬화할 수 없는 기록).
  5. [도메인 모델 §4.3](domain-model.md#43-이벤트-전달과-저장-형식)의 "영영 나가지 못하는 기록" SQL로 `listener_id`·`event_type`별 개수와 `completion_attempts`를 본다.
- **조치.** 브로커 장애면 브로커를 되살리고 기다린다. 영영 나가지 못하는 기록이면 §4.3의 조치(원인인 리스너·타입을 되살리거나, 버리기로 결정한 기록만 완료 처리)를 따른다.
- **하지 말 것.** 행을 손으로 완료 처리하거나 지우지 않는다. 곧 다시 나갈 메시지를 버리게 된다.

## EventPublicationMetricStale

- **뜻.** 그 인스턴스가 5분 넘게 발행 기록을 세지 못했다(갱신 주기 1분). 세지 못한 인스턴스는 개수를 내리므로 [EventPublicationFailed](#eventpublicationfailed)가 그 인스턴스의 값을 보지 못한다. 모든 인스턴스가 막히면 실패 알림이 해소된 것처럼 풀린다.
- **먼저 볼 것.** 그 인스턴스의 WARN `발행 기록을 세지 못해 개수를 내리고 이번 갱신을 건너뛴다`와 예외, Hikari 대기(`hikaricp_connections_pending`), DB 상태.
- **조치.** DB나 커넥션 문제를 고친다. 다음 갱신에 `event_publication_incomplete_refreshed_seconds`가 다시 움직이면 풀린다.

## KafkaDeadLetter

- **뜻.** consumer가 처리하지 못한 메시지가 `원토픽.DLT`로 갔다. 그 주문의 다음 단계(결제 시도, `PAID` 전이, fulfillment, 대조 case)가 멈췄다. `reason`은 대응하는 사람을 가른다.
  - 도메인 오류 코드(예: `PAYMENT_APPROVAL_MISMATCH`): 업무 불일치다.
  - `UNSUPPORTED_EVENT_SCHEMA_VERSION`: 롤링 배포 중 옛 pod가 새 버전 메시지를 받았다. 배포가 끝난 뒤 그대로 재투입한다([이벤트 계약](event-contract.md) 5절).
  - `error`: 재시도를 모두 썼거나 재시도 목록 밖의 예외(버그, 변환 실패)다.
- 알림은 DLT로 간 뒤 10분 동안 울리고 저절로 풀린다. 풀렸다고 해소된 것이 아니다. DLT 메시지는 보존 기간이 없어 지워지지 않는다.
- **먼저 볼 것.** ERROR `DLT로 보냄 topic=… partition=… offset=… key=… reason=… exception=…`. `key`가 `orderId`다. 그 줄의 `trace_id`로 trace를 열어 실패한 consumer span과 예외를 본다.
- **조치.** 원인을 고친 뒤 재투입한다. 재투입 전에 [이벤트 계약 8절](event-contract.md#8-dlt-재투입-전에-볼-것)을 확인하고, 절차는 [도메인 모델 §4.3](domain-model.md#43-이벤트-전달과-저장-형식)의 "DLT 재투입"을 따른다. 플랫폼 운영자의 재투입 API는 #91이 만든다.
- **하지 말 것.** 원인을 고치기 전에 재투입하지 않는다. 같은 이유로 다시 DLT로 간다. DLT 토픽을 지우거나 보존 기간을 걸지 않는다.

## KafkaDeadLetterPublishFailed

- **뜻.** 메시지를 DLT로 보내는 것 자체가 실패했다. 메시지를 버리지 않으려고 재시도 상태가 처음으로 돌아가 같은 메시지를 다시 처리하므로, 그 파티션의 뒤 메시지가 모두 멈춘다.
- **먼저 볼 것.** ERROR `DLT 전송 실패, 처음부터 다시 시도한다 topic=… original=…`의 예외. 브로커 상태, DLT 토픽(`원토픽.DLT`)이 있는지, 쓰기 권한, 메시지 크기.
- **조치.** DLT 쪽 원인을 고친다. 고치면 다음 시도에 DLT로 가고 [KafkaDeadLetter](#kafkadeadletter)가 울린다.

## KafkaConsumerLag

- **뜻.** 한 파티션이 10분 넘게 1000건 넘게 밀렸다. 재시도는 blocking이라 한 메시지가 재시도 중이면 그 파티션 전체가 기다린다. DB에 닿지 못하는 실패는 횟수 없이 기다린다. 1000은 부하테스트 전의 시작값이다.
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

- **뜻.** 그 인스턴스의 결과 불명 확정 작업(`UnknownPaymentResolver.resolveUnknown`)이 20분 안팎 동안 한 번도 성공하지 못했다. 결과 불명 결제가 확정되지 않고, gauge `payment_attempt_unknown`·`payment_attempt_reconciliation_pending`이 마지막 성공값에 멈춘다. 한 건의 실패는 작업을 실패시키지 않는다(ERROR 로그만 남긴다). 실패는 대상 조회나 개수 세기가 던진 경우다.
- **먼저 볼 것.** `tasks_scheduled_execution_seconds_count{code_function="resolveUnknown"}`의 `outcome="ERROR"`와 `exception`, 스케줄러 ERROR 로그, DB 상태.
- **조치.** 원인(DB)을 고친다. 다음 주기(1분)에 성공하면 풀린다.

## UnknownPaymentResolutionStuck

- **뜻.** reconciliation으로 넘기지 않은 결과 불명 승인 시도가 2시간 넘게 남아 있다. 정상이면 한 건은 조회 상한 60회(약 1시간) 안에 확정되거나 넘어간다. 작업이 멈췄거나, 조회나 확정이 건마다 실패하고 있다.
- **먼저 볼 것.** [UnknownPaymentResolverNotSucceeding](#unknownpaymentresolvernotsucceeding)이 함께 울렸는지. ERROR `결과 불명 결제 확정에 실패했다 paymentId=…`(건마다 실패), WARN `조회로도 결과를 확인하지 못했다 … queryCount=…`의 `queryCount`가 오르는지(조회는 돌고 있다).
- **조치.** 작업이나 건별 실패의 원인을 고친다. 결제 상태는 작업이 확정한다.
- **하지 말 것.** `t_payment_attempt`의 상태를 DB에서 직접 고치지 않는다. 승인 이벤트가 나가지 않아 주문이 `PAID`가 되지 않는다.

## ReconciliationPendingUnresolved

- **뜻.** reconciliation으로 넘긴 뒤 아직 결과 불명인 승인 시도가 24시간 넘게 있다. 점주가 대조 case를 해소하지 않았다. 그 주문은 `CHECKOUT_REQUESTED`에 머물고 재결제도 막혀 있다.
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

- **뜻.** 락(`platform.Lock`)이 잡지 못하고 그냥 진행하는 일이 10분 넘게 이어진다. 정합성은 DB 제약이 지키므로 데이터는 깨지지 않는다. 대신 같은 요청이 겹치면 늦은 쪽이 앞 결과를 받지 못하고 경합 오류(409)로 끝나고, 주기 작업은 pod 사이에서 겹쳐 돈다. 한 번만 일어난 fail-open은 이 알림에 잡히지 않을 수 있다.
  - `reason="error"`: Redis에 닿지 못한다. [OwnerSessionStoreUnavailable](#ownersessionstoreunavailable)도 함께 울릴 것이다.
  - `reason="timeout"`: 누군가 같은 키를 3초 넘게 쥐고 있다. `lock`이 `platform:event-publication`이면 재전송 블록이, `payment:unknown-resolve`면 조회 배치가 길다. `order:create-lock`·`payment:checkout-lock`이면 같은 주문의 요청이 몰린다.
- **먼저 볼 것.** WARN `잠금 없이 진행한다(Redis 오류) key=…`나 `잠금 없이 진행한다(대기 3000ms 초과) key=…`의 key. Redis 상태.
- **조치.** Redis 장애면 Redis를 되살린다. timeout이면 긴 블록의 원인(DB 지연, 배치 크기)을 본다. 락 대기·TTL 값은 부하테스트 뒤 조정한다.

## SyncConflict

- **뜻.** 확정된 순번에 다른 내용이나 모르는 operation이 왔다. 단말과 서버의 상태가 갈라졌다. 그 항목은 아무것도 쓰지 않았고 단말 큐에서 빠지지 않는다.
- **먼저 볼 것.** WARN `sync 충돌 terminalId=… operationId=… sequence=… : 이유`. 이유가 "확정된 순번에 모르는 operation"이면 단말이 순번을 처음부터 다시 매겼을 가능성이 크다(재설치, 데이터 초기화). "이미 기록된 operation이 새 순번으로 왔다"면 단말이 operationId를 다른 순번에 다시 썼다.
- **조치.** 그 단말의 큐와 서버의 접수 기록(`t_sync_operation`, `t_sync_watermark`)을 대조하고 단말 쪽을 바로잡는다.
- **하지 말 것.** watermark나 접수 기록을 DB에서 직접 고치지 않는다. 이미 적용된 주문이 두 번 생길 수 있다.

## SyncGapPersistent

- **뜻.** 앞 순번이 확정되지 않은 항목(`GAP`)이 30분 넘게 계속 온다. 단말이 `lastAckedSequence` 다음부터 다시 보내지 않고 있어 그 단말의 큐가 비지 않는다. 30분은 시작값이다.
- **먼저 볼 것.** INFO `sync 배치 처리 terminalId=… lastAckedSequence=… counts={GAP=…}`로 단말을 찾는다.
- **조치.** 그 단말의 재전송 로직(응답의 `lastAckedSequence` 다음부터 보내는지)을 확인한다.

## OwnerSessionStoreUnavailable

- **뜻.** 점주 요청이 5분 넘게 `503 SERVICE_UNAVAILABLE`로 실패한다. 지금 503을 내는 곳은 점주 세션 저장소(Redis) 장애 처리뿐이다. 단말 경로(`/v1/**`)는 Redis를 쓰지 않아 영향이 없다.
- **먼저 볼 것.** ERROR `errorCode=SERVICE_UNAVAILABLE` 로그, Redis 상태. [LockFailOpen](#lockfailopen)(`reason="error"`)도 함께 울릴 것이다.
- **조치.** Redis를 되살린다. 503은 변경이 커밋되지 않았다는 뜻이 아니다. 단말 등록·자격 회전 뒤 503을 받았으면 목록으로 결과를 확인한다([관측성](observability.md) 점주 세션 저장소 항목).
