# PR spec: 결과 불명 결제의 나이와 미처리 DLT 수로 알림을 상태에 맞춘다

## 목적
알림 셋이 실제 상태와 어긋난다(이슈 #104, #105, [observability-alerts-tracing](2026-10-21-observability-alerts-tracing.md) 범위 조정 10·11).
- `UnknownPaymentResolutionStuck`(`for: 2h`)과 `ReconciliationPendingUnresolved`(`for: 24h`)는 결과 불명 개수 gauge가 0보다 큰 시간을 센다. 그런데 이 gauge는 기동 직후 첫 주기(1분)까지 0이다. replica가 하나이거나 전부 재기동하면 `for:`가 처음부터 다시 세서, 매일 배포하면 24시간 규칙은 사실상 울리지 않는다.
- `KafkaDeadLetter`는 DLT counter의 증가를 보고 `keep_firing_for: 24h`로 하루 붙잡는다. DLT 메시지는 지워지지 않는데도 처리하지 않은 메시지가 24시간 뒤에는 정상처럼 보인다. 재투입을 끝내도 24시간 동안 울려 사람이 silence로 꺼야 한다.

기대 결과:
- 두 결제 규칙은 DB에서 계산한 가장 오래된 행의 나이로 판단한다. 재기동해도 첫 갱신 뒤 5분이면 다시 울린다.
- `KafkaDeadLetter`는 재투입도 폐기도 하지 않은 DLT 메시지가 있는 동안 울리고, 처리하면 다음 갱신에 풀린다. silence가 필요 없다.
- 재투입하지 않을 메시지(예: `PAYMENT_APPROVAL_MISMATCH`)는 플랫폼 운영자가 사유와 함께 폐기를 표시한다.

## 범위
소유 모듈은 `payment`(나이 gauge)와 `platform`(미처리 DLT gauge, 폐기 표시 API와 기록, 알림 규칙)이다. `reconciliation` 코드는 바꾸지 않는다.

### 결정
**1. 한 PR로 낸다(사용자 결정).** 두 이슈가 같은 규칙 파일, promtool 시나리오, runbook을 고친다.

**2. 결과 불명 나이 gauge 둘은 `payment`가 낸다(사용자 결정).** `UnknownPaymentResolver`가 주기마다 개수를 센 직후 같은 자리에서 센다.
- `payment.attempt.resolution.oldest`(Prometheus `payment_attempt_resolution_oldest_seconds`): reconciliation으로 넘기지 않은(`reconciliation_requested_at is null`) `UNKNOWN` 승인 시도 가운데 가장 오래된 것의 나이다(시도 생성 시각 `created_at`부터, 초). V013 partial 인덱스 `ix_payment_attempt_unknown_authorization (id)`를 `id` 순으로 읽어 첫 행의 `created_at`을 쓴다. `id`가 UUID v7이라 `id` 순서가 생성 순서다.
- `payment.attempt.reconciliation.pending.oldest`(`payment_attempt_reconciliation_pending_oldest_seconds`): 넘긴 `UNKNOWN` 승인 시도 가운데 가장 이른 넘긴 시각(`min(reconciliation_requested_at)`)부터의 나이다. 같은 partial 인덱스로 `UNKNOWN` 행만 읽는다. 지금 넘긴 시도 수를 세는 조회와 범위가 같다.
- 대상이 없으면 0이다. 첫 갱신 전에는 `NaN`이다. 선례 `event_publication_incomplete_oldest_seconds`와 같고, `max`가 `NaN`을 빼고 계산해 여러 pod면 다른 pod의 값이 남는다(사용자 결정). 세기가 던지면 지금 개수처럼 마지막 성공값이 남고, 작업 실패는 `UnknownPaymentResolverNotSucceeding`이 잡는다.
- 넘긴 쪽을 `reconciliation`의 case 나이(`MANUAL_REVIEW` case의 `opened_at`)로 내지 않는다. `reconciliation`에 주기 작업을 새로 두고 `(status, opened_at)` 인덱스(마이그레이션)를 더해야 한다. 지금 인덱스 `(store_id, status, id)`는 매장을 가리지 않는 조회에 쓰지 못한다. 또 case 열기가 실패해 `payment.reconciliation-requested.DLT`에 간 건을 보지 못한다. `payment`의 넘긴 시각은 그 건까지 담고, 지금 규칙이 보는 pending gauge와 뜻이 같다.
- "`UNKNOWN`이 된 시각" 컬럼은 두지 않는다. 정상 흐름에서는 승인 요청 한 번(수 초) 안에 `REQUESTED`가 `UNKNOWN`이 되므로 `created_at`과 거의 같다. 확정 작업의 한도도 시간이 아니라 조회 횟수(60회)다. 대가로 `payment.attempt-created`가 DLT에 머물다 늦게 재투입된 시도는 `created_at`이 오래되어, 넘기기 전에 2시간 알림이 울릴 수 있다(한계).
- 개수 gauge `payment_attempt_unknown`·`payment_attempt_reconciliation_pending`은 남긴다(사용자 결정). 규칙에서는 빠지고 runbook의 진단(몇 건인지)에 쓴다.

**3. 미처리 DLT gauge `event.kafka.dead-letter.unhandled{topic}`(Prometheus `event_kafka_dead_letter_unhandled`).**
- 뜻: DLT에 남아 있는 레코드 가운데 재투입 기록도 폐기 기록도 없는 수다. `topic`은 원 토픽이다(DLT counter·재투입 counter와 같다). partition label은 두지 않는다.
- 계산: DLT 파티션마다 끝 offset에서 시작 offset을 빼고, 그 offset 범위에 있는 재투입·폐기 기록의 합집합 수를 뺀다. 합집합은 DLT 레코드(좌표와 레코드 시각) 기준이라 둘 다 있는 레코드는 한 번 센다. 0 아래면 0이다. DLT는 보존 기간이 없고(`retention.ms=-1`, compaction 없음) 트랜잭션 producer를 쓰지 않아 offset 사이에 빈자리가 없으므로 끝에서 시작을 뺀 값이 레코드 수다.
- 읽기: Kafka `Admin` 하나를 빈 수명 동안 쓴다. Boot의 `KafkaAdmin` 설정(`getConfigurationProperties`)으로 만들고 빈이 파괴될 때 닫는다. `describeTopics`로 파티션을, `listOffsets`로 earliest와 latest를 읽는다. 한 갱신의 브로커 호출 전체 상한은 5초다(`DeadLetterReader`와 같은 값). `KafkaAdmin.describeTopics` 같은 메서드는 호출마다 `AdminClient`를 만들고 닫아서 1분마다 `AdminClientConfig` INFO 블록을 남긴다. consumer로 읽으면 만들 때마다 `ConsumerConfig` INFO 블록이 남는다(#107 결정 8). 그래서 둘 다 쓰지 않는다.
- 주기: `@Scheduled` 1분, 기동 직후 바로 한 번 돈다. pod마다 돈다. 공용 스케줄러 스레드 둘을 재전송·결과 불명 작업과 나눠 쓰고, 한 번의 갱신은 브로커 상한 5초와 DB 조회 둘이다.
- 갱신이 실패하면(브로커·DB·권한) 마지막 성공값을 그대로 두고 예외를 던진다(사용자 결정). 브로커에 닿지 못하는 동안에는 새 DLT가 쌓이지 않고, 재투입과 폐기도 DLT를 읽어야 해서 일어나지 않는다. 그래서 실제 미처리 수가 변하지 않는다. 던진 실패는 `tasks_scheduled_execution_seconds_count{outcome="ERROR"}`와 스케줄러의 ERROR 로그로 남는다. 첫 갱신 전에는 설정된 토픽마다 `NaN`이다.
- 재투입 직후의 갱신은 기록 커밋 전이면 이전 값을 내고 다음 갱신에 반영한다. 재투입한 메시지가 다시 실패하면 새 DLT 레코드라 다시 센다.
- 운영에서 Kafka ACL을 쓰면 앱 principal에 DLT의 Describe가 있어야 한다(`DescribeTopics`·`ListOffsets`). #107의 배포 확인 항목(`*.DLT` Read·Describe)에 이미 들어 있다.

**4. 폐기 표시: `POST /admin/v1/dead-letters/{topic}/{partition}/{offset}/dismiss`, 본문 `{reason}`(사용자 결정).**
- 순서: 사유와 운영자(`sub`) 검사, DLT에서 레코드 읽기, 같은 레코드의 폐기 기록 확인, 기록이다. 위치가 틀리면 `400 INVALID_DEAD_LETTER_POSITION`, 그 offset에 레코드가 없으면 `404 DEAD_LETTER_NOT_FOUND`, 브로커에 닿지 못하면 `503`이다. 재투입과 같은 `DeadLetterReader.readRecord`를 쓴다.
- 같은 레코드(좌표와 레코드 시각)의 폐기 기록이 있으면 처음 기록을 `200`으로 돌려준다. 사유가 달라도 처음 기록을 바꾸지 않는다. 동시 요청이 unique에 걸리면 이긴 쪽 기록을 `200`으로 돌려준다.
- 사유 규칙은 재투입과 같다. 앞뒤 공백을 지운 뒤 1~500자이고 NUL 문자가 없어야 한다. 틀리면 새 code `400 INVALID_DISMISS_REASON`이고 DLT를 읽지 않는다. 운영자 `sub` 검사(1~255자, 제어 문자 없음, 틀리면 500)는 재투입과 같은 Controller 함수다.
- 읽기 검사(`422 DEAD_LETTER_NOT_READABLE`)는 하지 않는다. 지금 코드가 읽지 못하는 메시지도 폐기할 수 있어야 한다.
- 재투입과 폐기는 서로를 확인하지 않는다(사용자 결정). 폐기한 레코드도 재투입할 수 있고 재투입한 레코드도 폐기할 수 있으며, 기록은 둘 다 남는다. gauge는 둘 중 하나라도 있는 레코드를 처리된 것으로 센다. 그래서 #107의 재투입 코드는 바꾸지 않고 충돌 오류 code도 없다. 잘못 폐기한 메시지는 재투입으로 처리한다.
- 응답은 `dltTopic`, `dltPartition`, `dltOffset`, `operator`, `dismissedAt`이다. 목록(`GET /admin/v1/dead-letters`) 항목에 `dismissal`(`operator`·`dismissedAt`, 없으면 null)을 더한다.
- 처음 폐기한 뒤 INFO 한 줄 `audit action=DEAD_LETTER_DISMISSED operator=… dltTopic=… dltPartition=… dltOffset=…`을 남긴다. 사유 원문은 남기지 않는다(재투입과 같은 기준). 같은 레코드의 재요청과 경합에 진 쪽은 남기지 않는다. 아무것도 보내지 않으므로 재투입의 WARN 감사 줄 같은 경로가 없다.
- counter는 두지 않는다. 상태는 gauge가, 행위는 감사 줄과 표가 보여 준다.
- 기록은 바꾸거나 지우지 않는다. 폐기 취소는 없다.

**5. 폐기 기록은 새 표 `t_dead_letter_dismissal`(V020)이다.** 컬럼은 `id`(UUID v7), `dlt_topic`, `dlt_partition`, `dlt_offset`, `dlt_timestamp`, `operator_subject`, `reason`, `BaseEntity` 공통 컬럼이다. 폐기 시각은 `created_at`이다. unique `ux_dead_letter_dismissal_source(dlt_topic, dlt_partition, dlt_offset, dlt_timestamp)`다. 재투입 표에 행을 넣지 않는 이유는 그 표의 넣은 자리(`target_partition`·`target_offset`)가 not null이고, "기록이 있으면 넣은 메시지가 브로커에 ack된 것이다"라는 #107 불변식이 깨지기 때문이다.

**6. 규칙(사용자 결정).** 15개가 된다.

| 규칙 | 조건 | for | severity |
|---|---|---|---|
| UnknownPaymentResolutionStuck | `max(payment_attempt_resolution_oldest_seconds) > 7200` | 5m | warning |
| ReconciliationPendingUnresolved | `max(payment_attempt_reconciliation_pending_oldest_seconds) > 86400` | 5m | warning |
| KafkaDeadLetter | `max by (topic) (event_kafka_dead_letter_unhandled) > 0`. `keep_firing_for`를 지운다 | 없음 | critical |
| KafkaDeadLetterPublishFailed | 식은 그대로 두고 `keep_firing_for: 24h`를 지운다 | 없음 | critical |
| KafkaDeadLetterMetricNotSucceeding(새 규칙) | 기동한 지 5분이 지난 인스턴스에서 10분 동안 미처리 DLT 갱신 작업의 `SUCCESS`가 늘지 않음 | 10m | warning |

- 임계값 2시간과 24시간은 유지한다. 2시간은 조회 상한 60회(약 1시간)에 여유를 둔 값이고, 24시간은 점주 해소를 기다리는 시작값이다. 바꿀 근거가 없다.
- 재기동: 나이 gauge는 첫 갱신 전 `NaN`이라 `max`에서 빠진다. pod가 여럿이면 알림이 끊기지 않는다. pod가 하나면 풀렸다가 첫 갱신 뒤 5분이면 다시 울린다. 지금처럼 2시간·24시간을 처음부터 세지 않는다. promtool 시나리오로 두 경우를 보인다.
- `KafkaDeadLetter`는 `reason` label을 잃는다. 원인은 counter `event_kafka_dead_letter_total{topic, reason}`과 목록 API의 `exception`으로 본다(runbook). 첫 DLT series를 잡던 `unless … last_over_time(…)` 절도 필요 없어져 지운다.
- `KafkaDeadLetterPublishFailed`: DLT 전송이 다시 되면 메시지는 DLT로 가서 `KafkaDeadLetter`가 잡는다. 다시 처리해 성공하면 잃은 메시지가 없다. 계속 실패하면 counter 증가가 이어진다. 하루 붙잡을 이유가 없어졌다.
- 생존 규칙은 `EventPublicationResubmitterNotSucceeding`과 같은 모양이다. 처음부터 한 번도 성공하지 못한 경우(운영 ACL에 DLT Describe가 빠짐 등)도 잡는다. 그대로 두면 gauge가 계속 `NaN`이라 critical 알림이 신호 없이 죽어 있다.

**7. runbook.** 공통 절의 silence 항목은 "DLT 알림은 상태 알림이라 재투입이나 폐기로 풀린다"로 바꾼다. `KafkaDeadLetter` 절에 폐기 절차와 원인(`reason`)을 보는 곳을 적는다. 두 결제 절의 재기동 한계 줄을 지우고 나이의 기준 시각을 적는다. 새 생존 규칙 절을 더한다.

### Kafka·Spring Kafka 공식 문서와 소스 대조
- kafka-clients 4.2.2(Boot 4.1.1이 고른 버전) `Admin` javadoc: `create`가 돌려주는 인스턴스는 thread-safe다. `listOffsets`(파티션마다 `OffsetSpec` 하나를 담은 맵)가 파티션의 earliest·latest·시각 기준 offset을 돌려준다. 같은 파티션의 earliest와 latest는 한 요청에 담을 수 없어 두 번 부른다.
- spring-kafka 4.1.1 `KafkaAdmin`(sources jar): `describeTopics`·`createOrModifyTopics`·`clusterId`가 모두 `createAdmin()`으로 `AdminClient`를 만들고 try-with-resources로 닫는다. offset 조회 메서드는 없다.
- 이 PR에서 더 확인할 것(결과 절에 적는다): `ListOffsets`·`DescribeTopics`에 필요한 ACL(Kafka 공식 문서), `ListOffsetsOptions`의 격리 수준 기본값과 latest가 high watermark인지, `Admin`을 만들 때 남는 로그, 기동 로그의 `AdminClientConfig values`.

계약: 새 HTTP endpoint 하나(`POST /admin/v1/dead-letters/{topic}/{partition}/{offset}/dismiss`), 새 오류 code `INVALID_DISMISS_REASON`(400), 목록 응답 필드 `dismissal`, 새 metric 셋, 규칙 변경(위 표). 이벤트 형식·토픽·consumer group은 바뀌지 않는다.

불변식: 한 DLT 레코드(좌표와 레코드 시각)의 폐기 기록은 하나다. 기록은 바꾸거나 지우지 않는다. 설정된 DLT 토픽만 읽는다.

복구: 폐기 응답을 잃으면 같은 요청을 다시 보낸다. 503은 기록하지 않으므로 브로커가 돌아온 뒤 다시 요청한다. 잘못 폐기한 메시지는 재투입한다.

관측성: 이 PR의 대상이다. 위 결정 2·3·6.

## 제외
- 일괄 폐기. 운영자가 단건을 반복 호출한다(재투입과 같다).
- 폐기 취소와 기록 변경.
- 폐기 counter, gauge의 `reason`·`partition` label.
- `reconciliation`의 case 나이 gauge와 그 인덱스. 결정 2.
- "`UNKNOWN`이 된 시각" 컬럼. 결정 2.
- 재투입·폐기 기록의 보존 기간(#107 제외 그대로).
- DLT 토픽을 다시 만든 뒤의 정확한 계산. 한계 절.
- 공용 스케줄러 스레드 수 변경. 열린 PR(#77·#102)이 `application.yaml`을 고치고 있어 이 PR은 건드리지 않는다.
- 운영 콘솔 화면, 로컬 IdP. compose에서는 `/admin`을 부를 수 없다(#107 제외 그대로).

## 한계
- DLT 토픽을 다시 만들면(클러스터 이전·DR) offset이 0부터 다시 시작해, 옛 기록이 같은 offset의 새 레코드를 처리된 것으로 센다. 그래서 미처리를 적게 센다. 레코드 시각을 맞추려면 레코드를 읽어야 해서 하지 않는다. runbook에 "DLT 토픽을 다시 만든 뒤에는 목록 API로 미처리를 확인한다"를 적는다.
- DLT에 머물다 늦게 재투입된 결제 시도는 `created_at`부터 재므로 2시간 알림이 넘기기 전에 울릴 수 있다. 넘기면(최대 약 1시간) 풀린다.
- pod가 하나면 재기동할 때 결제 두 알림이 풀렸다가 약 6분 뒤 다시 울린다.
- 미처리 DLT gauge는 갱신이 실패하는 동안 마지막 값에 멈춘다. 멈춤은 생존 규칙이 20분 안팎에 알린다.
- `KafkaDeadLetter`의 지연은 갱신 주기(1분), scrape(15초), 평가 주기를 더한 값이다. 증가를 보던 지금 규칙과 비슷하다.

## 관련 문서
없음. 이 PR이 따르는 장기 스펙 문서가 없다. 앞 PR은 [observability-alerts-tracing](2026-10-21-observability-alerts-tracing.md), [platform-operator-dlt-redrive](2026-10-21-platform-operator-dlt-redrive.md), [payment-reconciliation-case](2026-10-20-payment-reconciliation-case.md)다.

## 변경 경로
- docs/spec/2026-10-22-alert-state-gauges.md
- docker/prometheus/**
- src/main/kotlin/com/resilientpos/payment/**
- src/main/kotlin/com/resilientpos/platform/**
- src/main/resources/db/migration/V020__dead_letter_dismissal.sql
- src/test/kotlin/com/resilientpos/**
- src/test/resources/**
- docs/wiki/runbook.md
- docs/wiki/observability.md
- docs/wiki/domain-model.md
- docs/wiki/event-contract.md
- docs/wiki/entity-map.md
- docs/wiki/architecture.md
- docs/wiki/README.md
- README.md

## 인수 조건
1. `payment_attempt_resolution_oldest_seconds`는 넘기지 않은 `UNKNOWN` 승인 시도 가운데 가장 오래된 것의 `created_at`부터의 나이이고, 넘긴 시도와 결말 난 시도는 보지 않는다. `payment_attempt_reconciliation_pending_oldest_seconds`는 넘긴 `UNKNOWN` 시도의 가장 이른 `reconciliation_requested_at`부터의 나이다. 대상이 없으면 둘 다 0이고 첫 갱신 전에는 `NaN`이다.
2. 두 나이 조회의 실행 계획이 `ix_payment_attempt_unknown_authorization`을 쓴다.
3. `event_kafka_dead_letter_unhandled{topic}`은 설정된 토픽마다 DLT 레코드 수에서 재투입·폐기된 레코드 수(둘 다 있으면 한 번)를 뺀 값이다. 첫 갱신 전에는 `NaN`이고, 갱신이 실패하면 마지막 값을 유지하고 작업은 실패로 끝난다.
4. DLT 레코드를 폐기하면 `200`과 응답 필드가 오고, `t_dead_letter_dismissal`에 행 하나와 사유가 남으며, 감사 INFO 한 줄이 남고 그 줄에 사유가 없다. 그 토픽의 미처리 gauge가 다음 갱신에 1 줄고, 목록 항목의 `dismissal`이 채워진다.
5. 같은 레코드를 다시 폐기하면 처음 기록이 `200`이고 행과 감사 줄이 늘지 않는다. 동시 요청이 unique에 걸리면 이긴 쪽 기록을 `200`으로 돌려준다.
6. 빈 사유·500자 초과·NUL 문자가 든 사유는 `400 INVALID_DISMISS_REASON`이고 DLT를 읽지 않는다. 설정에 없는 토픽·없는 partition·음수 offset은 `400 INVALID_DEAD_LETTER_POSITION`, 없는 offset은 `404 DEAD_LETTER_NOT_FOUND`, 브로커에 닿지 못하면 `503`이다. 모두 기록하지 않는다. `sub`가 틀리면 500이고 DLT를 읽지 않는다.
7. 지금 코드가 읽지 못하는 메시지도 폐기할 수 있다. 재투입한 레코드를 폐기해도, 폐기한 레코드를 재투입해도 둘 다 기록되고 gauge는 그 레코드를 한 번만 뺀다.
8. V020 뒤 `t_dead_letter_dismissal`이 있고 `(dlt_topic, dlt_partition, dlt_offset, dlt_timestamp)`가 unique다.
9. `docker/prometheus/alerts.yaml`이 15개 규칙을 담고 promtool 검사를 통과한다. promtool 시나리오가 다음을 보인다. 나이가 임계값을 넘은 지 5분 뒤 울린다. 한 pod 재기동(`NaN` 구간)이면 풀렸다가 첫 갱신 뒤 5분에 다시 울린다. 여러 pod면 한 pod의 재기동에 끊기지 않는다. 미처리 DLT가 0보다 큰 동안 울리고 0이 되면 바로 풀린다. pod 사이는 `max`로 묶는다. DLT 전송 실패 알림이 24시간 붙잡지 않는다. 갱신 작업이 한 번도 성공하지 못하면 생존 규칙이 울린다.
10. 규칙이 쓰는 metric 이름이 앱의 `/actuator/prometheus`에 있다(예외 목록 밖).
11. REST Docs 스니펫이 폐기 endpoint의 요청·응답 필드와 400·404 예, 목록의 `dismissal` 필드를 문서화한다.
12. `docker compose -f docker/docker-compose.yaml --profile app --profile obs up -d --build`에서 다음을 확인하고 결과 절에 남긴다. 규칙 15개가 실린다. DLT를 일으키면 `KafkaDeadLetter`가 firing이 되고, 그 레코드의 폐기 행을 넣으면(compose에서는 `/admin`을 부를 수 없어 SQL로 넣는다) 다음 갱신 뒤 풀린다. 결과 불명 시도의 `created_at`을 2시간 전으로 옮기면 `UnknownPaymentResolutionStuck`이 5분 뒤 firing이 되고, 앱을 재기동하면 풀렸다가 다시 울린다.
13. `ModuleStructureTest`·`DependencyDirectionTest`·`TransactionDeclarationTest`를 포함한 기존 테스트가 모두 통과한다.

## 스키마 변경 검토
`V020__dead_letter_dismissal.sql`이 새 표 하나와 unique 제약 하나를 더한다.

```sql
create table t_dead_letter_dismissal (
    id uuid primary key,
    dlt_topic varchar(249) not null,
    dlt_partition integer not null,
    dlt_offset bigint not null,
    dlt_timestamp timestamptz not null,
    operator_subject varchar(255) not null,
    reason varchar(500) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz,
    constraint ux_dead_letter_dismissal_source unique (dlt_topic, dlt_partition, dlt_offset, dlt_timestamp)
);
```

- 질문 1(도출): "이 DLT 레코드를 넣지 않기로 했다"는 사실은 어디에도 없다. Kafka는 레코드에 표시를 남길 수 없고, 로그는 보존 기간이 있으며 정본이 아니다. 재투입 기록과 같은 표로 도출할 수 없다(결정 5). 원 토픽(`dlt_topic`에서 `.DLT`를 뺀 값)과 폐기 시각(`created_at`)은 도출할 수 있어 컬럼을 두지 않는다.
- 질문 2(지금 읽는 코드): 미처리 gauge가 범위로 세고, 폐기가 좌표와 레코드 시각으로 기존 기록을 찾아 멱등 응답을 만들고, 목록이 범위로 찾아 `dismissal`을 채운다. `operator_subject`는 응답과 목록이 읽는다. `reason`은 읽는 코드가 없다. 사람이 DB에서 보는 감사 정본이고 폐기 시점에만 알아 나중에 채울 수 없다(질문 3, `t_dead_letter_redrive.reason`과 같은 판단).
- 질문 3(나중에 채우기): 폐기 시점에만 아는 값이라 나중에 채울 수 없다.
- 질문 4(마지막 방어선): unique `(dlt_topic, dlt_partition, dlt_offset, dlt_timestamp)`가 "한 DLT 레코드의 폐기 기록은 하나"와 멱등 응답의 유일한 방어선이다. 레코드 시각을 넣는 이유는 재투입 기록과 같다(DLT 토픽을 다시 만들면 offset이 재사용된다). 지우는 명령이 없고 멱등 키라 soft delete된 행에도 걸리게 둔다(partial이 아니다). 조회는 `@SoftDelete`로 삭제 행을 보지 못하므로 이 표의 행은 SQL로도 soft delete하지 않는다.
- 질문 5(정본): 폐기 기록의 정본은 이 표 하나다. 감사 INFO 로그는 사유가 없는 사본이고 커밋 뒤 남긴다. 미처리 gauge는 Kafka offset과 두 표에서 매번 계산하는 사본이라 따로 저장하지 않는다.
- 질문 6(check-then-act): 기록 확인 뒤 기록한다. 경합은 unique가 막고 진 쪽은 위반을 잡아 이긴 쪽 기록을 돌려준다. 기다리는 수단(row lock·`Lock`)은 두지 않는다. 행을 고치는 경로가 없어 `@Version`도 없다. 재투입 표와의 배타는 두지 않는다(결정 4). 두 표에 걸친 제약은 DB가 지킬 수 없고, gauge가 합집합으로 세어 둘 다 있어도 틀리지 않는다.
- 질문 7(락·크기): 새 표 생성이라 다른 표를 잠그지 않는다. 기존 행이 없어 위반 행 검사가 필요 없고 `lock_timeout`을 두지 않는다(V019와 같다).
- 질문 8(이전 버전 호환): 이전 버전 애플리케이션은 이 표를 모르고 쓰지 않는다. `validate`는 매핑된 엔티티만 보고, Flyway는 모르는 미래 버전을 무시하므로(`ignoreMigrationPatterns=*:future`) 이전 버전이 새 스키마에서 뜬다. 롤링 배포 중 이전 버전 pod는 폐기 기록을 모르므로 그 pod의 gauge는 폐기한 레코드를 미처리로 센다. 규칙이 `max`로 묶어 배포가 끝날 때까지 `KafkaDeadLetter`가 울 수 있다.
- 질문 9(이벤트·캐시): 이벤트 payload나 캐시 DTO에 실리지 않는다.
- `dlt_topic varchar(249)`, `operator_subject varchar(255)`, `reason varchar(500)`, `dlt_timestamp timestamptz`는 재투입 기록과 같은 근거다(V019).

## 검증
- 인수 1·2: `UnknownPaymentResolver`의 통합 테스트(실제 PostgreSQL). 실행 계획은 같은 테스트 컨테이너에서 `EXPLAIN`으로 확인하고 결과 절에 적는다.
- 인수 3~8: `./gradlew test --tests 'com.resilientpos.platform.deadletter.*' --tests 'com.resilientpos.SchemaMigrationTest'`. DLT 레코드는 기존 테스트처럼 실제 `DeadLetterPublishingRecoverer.accept`로 넣는다.
- 인수 9·10: `./gradlew test --tests 'com.resilientpos.platform.AlertRulesTest' --tests 'com.resilientpos.platform.ObservabilityConfigurationTest'`. Docker가 필요하다.
- 인수 11: `DeadLetterControllerTest`와 `build/generated-snippets`.
- 인수 12: compose를 띄워 lgtm 컨테이너 안에서 Prometheus(9090) HTTP API로 조회한다. 공유 스택이라 `docker ps`와 #77 측정 상태를 먼저 확인하고, 측정 중이면 기다리거나 사용자에게 묻는다. 다른 worktree가 띄운 스택은 내리지 않는다.
- 인수 13: `./gradlew test` 전체.
- 동작을 지워 테스트가 빨개지는지 본다: 합집합(둘 다 있는 레코드를 두 번 빼면 실패), 폐기 기록 확인, 갱신 실패 때 값 유지.
- `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전에는 `--worktree` 없이 한 번 더. PR 전에 `resilientpos-multi-review`.

## Wiki 영향
- [runbook](../wiki/runbook.md): 결정 7.
- [관측성](../wiki/observability.md): 알림 규칙 항목(14개에서 15개, DLT 두 규칙의 `keep_firing_for` 문장), Kafka 소비 항목의 첫 DLT 처리 문장, 운영자 항목의 폐기와 미처리 gauge, 결과 불명 항목의 나이 gauge와 규칙 조건, 주기 작업 생존 항목의 새 작업, "아직 없는 것"의 #104·#105 두 줄을 지운다.
- [도메인 모델](../wiki/domain-model.md): §4.3 "DLT 재투입"에 폐기 단계, §4.7 사례에 `t_dead_letter_dismissal`. 용어집에 새 단어 "폐기(dismissal)"가 있는지 보고 없으면 더한다.
- [이벤트 계약](../wiki/event-contract.md) 5절: "규칙 `KafkaDeadLetter`가 10분 전에 없던 series를 따로 잡는다"를 미처리 gauge 규칙으로 바꾼다. 8절에 재투입하지 않을 메시지는 폐기한다는 한 줄.
- [엔티티 맵](../wiki/entity-map.md)과 [wiki 인덱스](../wiki/README.md)의 엔티티 수: `t_dead_letter_dismissal`.
- [아키텍처](../wiki/architecture.md) 모듈 표의 `platform` 책임: "DLT 재투입 API와 기록"에 폐기를 더한다.
- README: `/admin/**` 문장의 "(DLT 재투입)"에 폐기를 더한다.
- Modulith·Kafka 키워드(볼트): PR 전에 그 문서의 갱신 규칙대로 미처리 DLT 계산과 폐기 항목을 고친다. 볼트의 현재 상태 문서는 머지 뒤 고친다.
- 요구사항 변화: 시스템 설계 7절의 alert → runbook 연결과 "payment_reconciliation_age_seconds"(넘긴 시도의 나이)를 채운다. 플랫폼 운영자 행위에 폐기가 더해지고 감사는 재투입과 같은 기준이다. 업무 정합성 요구는 그대로다.

## 분할 계획
한 개발 컨텍스트(알림을 실제 상태에 맞추기)다. 폐기 표시는 미처리 DLT gauge가 맞으려면 필요해서 함께 담는다(사용자 결정). `PAYMENT_APPROVAL_MISMATCH` 메시지는 설계상 재투입하지 않고 DLT에 남는 case 자리라([도메인 모델 §4.3](../wiki/domain-model.md#43-이벤트-전달과-저장-형식), `KafkaConfiguration` KDoc), 폐기 없이는 상태 알림이 꺼지지 않는다.

겹침 확인(2026-10-22, origin/main `8221d79`):
- 열린 draft PR #102(feature/inbound-rate-limit): `docs/wiki/observability.md`(7~15행 근처 항목 추가와 "점주 세션 저장소" 항목 수정)와 `DeadLetterControllerTest.kt`(클래스 annotation 한 줄)를 고친다. 이 PR은 observability의 다른 항목(알림 규칙·Kafka 소비·운영자·결과 불명·주기 작업·아직 없는 것)을 고치고, 폐기 Controller 테스트를 같은 파일에 더해 #102의 annotation을 rebase 때 그대로 받는다. #102가 `inbound_rate_limited_total` 알림을 `alerts.yaml`에 더하면 규칙 수가 겹치므로 늦게 머지되는 쪽이 `AlertRulesTest`의 규칙 수를 맞춘다.
- 열린 draft PR #94(refactor/modulith-2-1-2-resubmit): `docs/wiki/domain-model.md` §4.3의 재전송 항목을 고친다. 이 PR은 §4.3의 DLT 재투입 항목과 §4.7 사례를 고친다.
- 진행 중인 #77(perf/k6-capacity-measurement, push 전): `docs/wiki/observability.md` 17행 근처("결제 대조 case" 항목)와 `docs/wiki/domain-model.md`를 고친다. 이 PR은 "결제 대조 case" 항목을 고치지 않는다. 개수 gauge를 남기므로 그 항목의 "미해소 건수는 위 pending gauge로 본다"가 그대로 맞다.
- 예정된 #103(SLO·시작값 알림)은 `alerts.yaml`을 고친다. 점주 이메일 작업(#92)이 새 마이그레이션을 더할 수 있어 push 전에 V020 번호 충돌을 확인한다.
- 공용 헬퍼: 사유 검사(1~500자·NUL)는 `DeadLetterRedriver`와 같은 규칙이라 그 자리에서 함께 쓰도록 뽑을지 구현 때 본다. main과 열린 PR에 폐기·미처리 DLT 코드는 없다.

## TDD 검증 기록
업무 동작(나이 gauge, 미처리 gauge, 폐기 API)은 TDD로 한다. 규칙 파일은 promtool 시나리오로 먼저 실패를 보고 고친다. 개발 전 테스트 의도이고 실행 뒤 채운다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 1: 넘기지 않은·넘긴 `UNKNOWN`의 가장 오래된 나이, 결말 난 시도 제외, 없으면 0, 첫 갱신 전 `NaN` | 미실행 | 미실행 | 미실행 |
| 인수 3: 미처리 DLT 수(재투입·폐기 합집합), 첫 갱신 전 `NaN`, 실패하면 값 유지와 작업 실패 | 미실행 | 미실행 | 미실행 |
| 인수 4~7: 폐기 API와 기록, 멱등, 오류, 읽지 못하는 메시지, 재투입과 독립 | 미실행 | 미실행 | 미실행 |
| 인수 8: V020 unique | 미실행 | 미실행 | 미실행 |
| 인수 9·10: promtool 시나리오와 이름 대조 | 미실행 | 미실행 | 미실행 |
| 인수 11: REST Docs | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
