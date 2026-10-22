# PR spec: 결과 불명 결제의 경과 시간과 미처리 DLT 수로 알림을 상태에 맞춘다

## 목적
알림 셋이 실제 상태와 어긋난다(이슈 #104, #105, [observability-alerts-tracing](2026-10-21-observability-alerts-tracing.md) 범위 조정 10·11).
- `UnknownPaymentResolutionStuck`(`for: 2h`)과 `ReconciliationPendingUnresolved`(`for: 24h`)는 결과 불명 개수 gauge가 0보다 큰 시간을 센다. 그런데 이 gauge는 기동 직후 첫 주기(1분)까지 0이다. replica가 하나이거나 전부 재기동하면 `for:`가 처음부터 다시 세서, 매일 배포하면 24시간 규칙은 사실상 울리지 않는다.
- `KafkaDeadLetter`는 DLT counter의 증가를 보고 `keep_firing_for: 24h`로 하루 붙잡는다. DLT 메시지는 지워지지 않는데도 처리하지 않은 메시지가 24시간 뒤에는 정상처럼 보인다. 재투입을 끝내도 24시간 동안 울려 사람이 silence로 꺼야 한다.

기대 결과:
- 두 결제 규칙은 DB에서 계산한 가장 오래된 행의 경과 시간으로 판단한다. 재기동해도 첫 갱신 뒤 5분이면 다시 울린다.
- `KafkaDeadLetter`는 재투입도 폐기도 하지 않은 DLT 메시지가 있는 동안 울리고, 처리하면 다음 갱신에 풀린다. silence가 필요 없다.
- 재투입하지 않을 메시지(예: `PAYMENT_APPROVAL_MISMATCH`)는 플랫폼 운영자가 사유와 함께 폐기를 표시한다.

## 범위
소유 모듈은 `payment`(경과 시간 gauge)와 `platform`(미처리 DLT gauge, 폐기 표시 API와 기록, 알림 규칙)이다. `reconciliation` 코드는 바꾸지 않는다.

### 결정
**1. 한 PR로 낸다(사용자 결정).** 두 이슈가 같은 규칙 파일, promtool 시나리오, runbook을 고친다.

**2. 결과 불명 경과 시간 gauge 둘은 `payment`가 낸다(사용자 결정).** `UnknownPaymentResolver`가 주기마다 개수를 센 직후 같은 자리에서 센다.
- `payment.attempt.resolution.oldest`(Prometheus `payment_attempt_resolution_oldest_seconds`): reconciliation으로 넘기지 않은(`reconciliation_requested_at is null`) `UNKNOWN` 승인 시도 가운데 가장 오래된 것의 경과 시간이다(시도 생성 시각 `created_at`부터, 초). V013 partial 인덱스 `ix_payment_attempt_unknown_authorization (id)`를 `id` 순으로 읽어 첫 행의 `created_at`을 쓴다. `id`가 UUID v7이라 `id` 순서가 생성 순서다.
- `payment.attempt.reconciliation.pending.oldest`(`payment_attempt_reconciliation_pending_oldest_seconds`): 넘긴 `UNKNOWN` 승인 시도 가운데 가장 이른 넘긴 시각(`min(reconciliation_requested_at)`)부터의 경과 시간이다. 같은 partial 인덱스로 `UNKNOWN` 행만 읽는다. 지금 넘긴 시도 수를 세는 조회와 범위가 같다.
- 대상이 없으면 0이다. 첫 갱신 전에는 `NaN`이다. 선례 `event_publication_incomplete_oldest_seconds`와 같고, `max`가 `NaN`을 빼고 계산해 여러 pod면 다른 pod의 값이 남는다(사용자 결정). 세기가 던지면 지금 개수처럼 마지막 성공값이 남고, 작업 실패는 `UnknownPaymentResolverNotSucceeding`이 잡는다.
- 넘긴 쪽을 `reconciliation`의 case 경과 시간(`MANUAL_REVIEW` case의 `opened_at`)로 내지 않는다. `reconciliation`에 주기 작업을 새로 두고 `(status, opened_at)` 인덱스(마이그레이션)를 더해야 한다. 지금 인덱스 `(store_id, status, id)`는 매장을 가리지 않는 조회에 쓰지 못한다. 또 case 열기가 실패해 `payment.reconciliation-requested.DLT`에 간 건을 보지 못한다. `payment`의 넘긴 시각은 그 건까지 담고, 지금 규칙이 보는 pending gauge와 뜻이 같다.
- "`UNKNOWN`이 된 시각" 컬럼은 두지 않는다. 정상 흐름에서는 승인 요청 한 번(수 초) 안에 `REQUESTED`가 `UNKNOWN`이 되므로 `created_at`과 거의 같다. 확정 작업의 한도도 시간이 아니라 조회 횟수(60회)다. 대가로 `payment.attempt-created`가 DLT에 머물다 늦게 재투입된 시도는 `created_at`이 오래되어, 넘기기 전에 2시간 알림이 울릴 수 있다(한계).
- 개수 gauge `payment_attempt_unknown`·`payment_attempt_reconciliation_pending`은 남긴다(사용자 결정). 규칙에서는 빠지고 runbook의 진단(몇 건인지)에 쓴다.

**3. 미처리 DLT gauge `event.kafka.dead-letter.unhandled{topic}`(Prometheus `event_kafka_dead_letter_unhandled`).**
- 뜻: DLT에 남아 있는 레코드 가운데 재투입 기록도 폐기 기록도 없는 수다. `topic`은 원 토픽이다(DLT counter·재투입 counter와 같다). partition label은 두지 않는다.
- 계산: DLT 파티션마다 끝 offset에서 시작 offset을 빼고, 그 offset 범위에 있는 재투입·폐기 기록의 합집합 수를 뺀다. 합집합은 DLT 레코드(좌표와 레코드 시각) 기준이라 둘 다 있는 레코드는 한 번 센다. 0 아래면 0이다. DLT는 보존 기간이 없고(`retention.ms=-1`, compaction 없음) 트랜잭션 producer를 쓰지 않아 offset 사이에 빈자리가 없으므로 끝에서 시작을 뺀 값이 레코드 수다.
- 읽기: Kafka `Admin` 하나를 빈 수명 동안 쓴다. Boot의 `KafkaAdmin` 설정(`getConfigurationProperties`)으로 만들고 빈이 파괴될 때 닫는다. `describeTopics`로 파티션을, `listOffsets`로 earliest와 latest를 읽는다. 한 갱신의 브로커 호출 전체 상한은 5초다(`DeadLetterReader`와 같은 값). `KafkaAdmin.describeTopics` 같은 메서드는 호출마다 `AdminClient`를 만들고 닫아서 1분마다 `AdminClientConfig` INFO 블록을 남긴다. consumer로 읽으면 만들 때마다 `ConsumerConfig` INFO 블록이 남는다(#107 결정 8). 그래서 둘 다 쓰지 않는다.
- 주기: `@Scheduled` 1분, 기동 직후 바로 한 번 돈다. pod마다 돈다. 공용 스케줄러 스레드 둘을 재전송·결과 불명 작업과 나눠 쓰고, 한 번의 갱신은 브로커 상한 5초와 DB 조회 둘이다.
- 갱신이 실패하면(브로커·DB·권한) 마지막 성공값을 그대로 두고 예외를 던진다(사용자 결정). 브로커에 닿지 못하는 동안에는 새 DLT가 쌓이지 않고, 재투입과 폐기도 DLT를 읽어야 해서 일어나지 않는다. 그래서 실제 미처리 수가 변하지 않는다. 던진 실패는 `tasks_scheduled_execution_seconds_count{outcome="ERROR"}`와 스케줄러의 ERROR 로그로 남는다. 첫 갱신 전에는 설정된 토픽마다 `NaN`이다.
- 마지막 성공 시각 gauge `event.kafka.dead-letter.unhandled.refreshed`(Prometheus `event_kafka_dead_letter_unhandled_refreshed_seconds`, epoch 초, 첫 갱신 전 0)를 함께 낸다(범위 조정 1, 사용자 결정). 규칙은 5분 안에 센 인스턴스가 하나라도 있으면 그 인스턴스들의 값만 보고, 모두 세지 못하면 마지막 값으로 판단한다. 한 인스턴스만 계속 실패해도 그 옛 값이 `max`로 알림을 붙잡지 않고, 브로커 전체 장애 때는 위 이유로 알림이 그대로 유지된다. 선례는 `event_publication_incomplete_refreshed_seconds`다.
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

**6. 규칙(사용자 결정).** 이 PR로 15개가 되고, main에 먼저 들어온 #102의 `InboundStoreRateLimited`와 합쳐 16개다.

| 규칙 | 조건 | for | severity |
|---|---|---|---|
| UnknownPaymentResolutionStuck | `max(payment_attempt_resolution_oldest_seconds) > 7200` | 5m | warning |
| ReconciliationPendingUnresolved | `max(payment_attempt_reconciliation_pending_oldest_seconds) > 86400` | 5m | warning |
| KafkaDeadLetter | 5분 안에 센 인스턴스만 모은 `max by (topic) (event_kafka_dead_letter_unhandled) > 0`, 그런 인스턴스가 없으면 모든 인스턴스의 마지막 값으로 같은 식(결정 3). `keep_firing_for`를 지운다 | 없음 | critical |
| KafkaDeadLetterPublishFailed | 식은 그대로 두고 `keep_firing_for: 24h`를 지운다 | 없음 | critical |
| KafkaDeadLetterMetricNotSucceeding(새 규칙) | 기동한 지 5분이 지난 인스턴스에서 10분 동안 미처리 DLT 갱신 작업의 `SUCCESS`가 늘지 않음 | 10m | warning |

- 임계값 2시간과 24시간은 유지한다. 2시간은 조회 상한 60회(약 1시간)에 여유를 둔 값이고, 24시간은 점주 해소를 기다리는 시작값이다. 바꿀 근거가 없다.
- 재기동: 경과 시간 gauge는 첫 갱신 전 `NaN`이라 `max`에서 빠진다. pod가 여럿이면 알림이 끊기지 않는다. pod가 하나면 풀렸다가 첫 갱신 뒤 5분이면 다시 울린다. 지금처럼 2시간·24시간을 처음부터 세지 않는다. promtool 시나리오로 두 경우를 보인다.
- `KafkaDeadLetter`는 `reason` label을 잃는다. 원인은 counter `event_kafka_dead_letter_total{topic, reason}`과 목록 API의 `exception`으로 본다(runbook). 첫 DLT series를 잡던 `unless … last_over_time(…)` 절도 필요 없어져 지운다.
- `KafkaDeadLetterPublishFailed`: DLT 전송이 다시 되면 메시지는 DLT로 가서 `KafkaDeadLetter`가 잡는다. 다시 처리해 성공하면 잃은 메시지가 없다. 계속 실패하면 counter 증가가 이어진다. 하루 붙잡을 이유가 없어졌다.
- 생존 규칙은 `EventPublicationResubmitterNotSucceeding`과 같은 모양이다. 처음부터 한 번도 성공하지 못한 경우(운영 ACL에 DLT Describe가 빠짐 등)도 잡는다. 그대로 두면 gauge가 계속 `NaN`이라 critical 알림이 신호 없이 죽어 있다. severity는 warning이다. 한 인스턴스의 실패는 다른 인스턴스가 세므로 DLT 알림을 막지 않고, 모든 인스턴스가 실패하는 것은 브로커·DB 전체 장애라 다른 신호가 함께 나온다(범위 조정 2).

**7. runbook.** 공통 절의 silence 항목은 "DLT 알림은 상태 알림이라 재투입이나 폐기로 풀린다"로 바꾼다. `KafkaDeadLetter` 절에 폐기 절차와 원인(`reason`)을 보는 곳을 적는다. 두 결제 절의 재기동 한계 줄을 지우고 경과 시간의 기준 시각을 적는다. 새 생존 규칙 절을 더한다.

### Kafka·Spring Kafka 공식 문서와 소스 대조
- kafka-clients 4.2.2(Boot 4.1.1이 고른 버전) `Admin` javadoc: `create`가 돌려주는 인스턴스는 thread-safe다. `listOffsets`(파티션마다 `OffsetSpec` 하나를 담은 맵)가 파티션의 earliest·latest·시각 기준 offset을 돌려준다. 같은 파티션의 earliest와 latest는 한 요청에 담을 수 없어 두 번 부른다.
- spring-kafka 4.1.1 `KafkaAdmin`(sources jar): `describeTopics`·`createOrModifyTopics`·`clusterId`가 모두 `createAdmin()`으로 `AdminClient`를 만들고 try-with-resources로 닫는다. offset 조회 메서드는 없다.
- [Kafka 4.1 Authorization and ACLs](https://kafka.apache.org/41/security/authorization-and-acls/)의 "Operations and Resources on Protocols" 표: `LIST_OFFSETS`와 `METADATA`(토픽 조회) 모두 Topic의 Describe가 필요하다. 운영 ACL 확인 항목은 #107이 이미 적은 `*.DLT` Read·Describe로 충분하다.
- kafka-clients 4.2.2 `ListOffsetsOptions`(sources jar): 기본 격리 수준은 `READ_UNCOMMITTED`라 latest는 high watermark다. DLT는 트랜잭션 producer를 쓰지 않아 격리 수준에 따라 값이 달라지지 않는다.
- 기동 로그(테스트 컨텍스트): `AdminClientConfig values` 블록은 컨텍스트마다 두 번이다. 하나는 `KafkaAdmin`의 토픽 생성, 하나는 미처리 DLT gauge의 `Admin.create`다. 갱신마다 새로 찍히지 않는다(같은 `Admin`을 쓴다).
- spring-context 7.0.9 `ScheduledAnnotationBeanPostProcessor`(sources jar): `initialDelay`가 기본값(-1)이면 0으로 바꿔 `fixedDelay` 작업이 기동 직후 바로 처음 돈다.

계약: 새 HTTP endpoint 하나(`POST /admin/v1/dead-letters/{topic}/{partition}/{offset}/dismiss`), 새 오류 code `INVALID_DISMISS_REASON`(400), 목록 응답 필드 `dismissal`, 새 metric 셋, 규칙 변경(위 표). 이벤트 형식·토픽·consumer group은 바뀌지 않는다.

불변식: 한 DLT 레코드(좌표와 레코드 시각)의 폐기 기록은 하나다. 기록은 바꾸거나 지우지 않는다. 설정된 DLT 토픽만 읽는다.

복구: 폐기 응답을 잃으면 같은 요청을 다시 보낸다. 503은 기록하지 않으므로 브로커가 돌아온 뒤 다시 요청한다. 잘못 폐기한 메시지는 재투입한다.

관측성: 이 PR의 대상이다. 위 결정 2·3·6.

## 제외
- 일괄 폐기. 운영자가 단건을 반복 호출한다(재투입과 같다).
- 폐기 취소와 기록 변경.
- 폐기 counter, gauge의 `reason`·`partition` label.
- `reconciliation`의 case 경과 시간 gauge와 그 인덱스. 결정 2.
- "`UNKNOWN`이 된 시각" 컬럼. 결정 2.
- 재투입·폐기 기록의 보존 기간(#107 제외 그대로).
- DLT 토픽을 다시 만든 뒤의 정확한 계산. 한계 절.
- 공용 스케줄러 스레드 수 변경. 열린 PR(#77·#102)이 `application.yaml`을 고치고 있어 이 PR은 건드리지 않는다.
- 운영 콘솔 화면, 로컬 IdP. compose에서는 `/admin`을 부를 수 없다(#107 제외 그대로).

## 한계
- DLT 토픽을 다시 만들면(클러스터 이전·DR) offset이 0부터 다시 시작해, 옛 기록이 같은 offset의 새 레코드를 처리된 것으로 센다. 그래서 미처리를 적게 센다. 레코드 시각을 맞추려면 레코드를 읽어야 해서 하지 않는다. runbook에 "DLT 토픽을 다시 만든 뒤에는 목록 API로 미처리를 확인한다"를 적는다.
- DLT에 머물다 늦게 재투입된 결제 시도는 `created_at`부터 재므로 2시간 알림이 넘기기 전에 울릴 수 있다. 넘기면(최대 약 1시간) 풀린다.
- pod가 하나면 재기동할 때 결제 두 알림이 풀렸다가 약 6분 뒤 다시 울린다.
- 미처리 DLT gauge는 갱신이 실패하는 동안 마지막 값에 멈춘다. 다른 인스턴스가 세고 있으면 규칙이 5분 뒤부터 그 값을 보지 않고, 멈춤은 생존 규칙이 20분 안팎에 알린다. 모든 인스턴스가 세지 못하는 동안에는 새 DLT에 울지 못한다.
- 결과 불명 경과 시간 gauge는 그 처리를 하지 않았다(사용자 결정). 한 인스턴스의 확정 작업만 멈추면 그 인스턴스의 경과 시간이 마지막 값에 멈춰 `max`로 warning 알림을 붙잡을 수 있다. runbook이 `max by (instance)`로 보고 `UnknownPaymentResolverNotSucceeding`이 가리키는 인스턴스를 고치게 한다.
- 미처리 수의 두 조회(재투입 수, 재투입 없는 폐기 수)는 같은 시점을 읽지 않는다. 폐기한 레코드를 재투입하는 커밋이 두 조회 사이에 끼면 한 갱신 동안 겹친 레코드 수만큼 많게 세어 `KafkaDeadLetter`가 1분쯤 잘못 울 수 있다. 적게 세지는 않는다(기록을 지우지 않는다). 다음 갱신에 맞는다.
- 설정된 DLT 토픽 하나라도 조회에 실패하면(없거나 권한 없음) 그 갱신 전체가 실패해 모든 토픽의 값이 멈춘다. 생존 규칙이 알린다.
- pod가 하나면 재기동하는 동안 `KafkaDeadLetter`도 잠깐 풀렸다가 기동 직후 첫 갱신에서 다시 운다.
- 이 gauge를 처음 내는 배포 직후에는 재투입·폐기 기록이 없는 기존 DLT 메시지가 모두 미처리로 잡혀 바로 운다. 일괄 폐기가 없어 한 건씩 처리한다. 배포는 앱을 먼저 하고 규칙을 나중에 올린다. 규칙이 먼저 올라가면 `KafkaDeadLetter`는 데이터가 없어 조용하고, 옛 pod가 10분 넘게 남으면 생존 규칙이 운다.
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
1. `payment_attempt_resolution_oldest_seconds`는 넘기지 않은 `UNKNOWN` 승인 시도 가운데 가장 오래된 것의 `created_at`부터의 경과 시간이고, 넘긴 시도와 결말 난 시도는 보지 않는다. `payment_attempt_reconciliation_pending_oldest_seconds`는 넘긴 `UNKNOWN` 시도의 가장 이른 `reconciliation_requested_at`부터의 경과 시간이다. 대상이 없으면 둘 다 0이고 첫 갱신 전에는 `NaN`이다.
2. 두 경과 시간 조회의 실행 계획이 `ix_payment_attempt_unknown_authorization`을 쓴다.
3. `event_kafka_dead_letter_unhandled{topic}`은 설정된 토픽마다 DLT 레코드 수에서 재투입·폐기된 레코드 수(둘 다 있으면 한 번)를 뺀 값이다. 첫 갱신 전에는 `NaN`이고, 갱신이 실패하면 마지막 값을 유지하고 작업은 실패로 끝난다. 마지막 성공 시각 `event_kafka_dead_letter_unhandled_refreshed_seconds`는 첫 갱신 전 0이고 성공할 때만 바뀐다.
4. DLT 레코드를 폐기하면 `200`과 응답 필드가 오고, `t_dead_letter_dismissal`에 행 하나와 사유가 남으며, 감사 INFO 한 줄이 남고 그 줄에 사유가 없다. 그 토픽의 미처리 gauge가 다음 갱신에 1 줄고, 목록 항목의 `dismissal`이 채워진다.
5. 같은 레코드를 다시 폐기하면 처음 기록이 `200`이고 행과 감사 줄이 늘지 않는다. 동시 요청이 unique에 걸리면 이긴 쪽 기록을 `200`으로 돌려준다.
6. 빈 사유·500자 초과·NUL 문자가 든 사유는 `400 INVALID_DISMISS_REASON`이고 DLT를 읽지 않는다. 설정에 없는 토픽·없는 partition·음수 offset은 `400 INVALID_DEAD_LETTER_POSITION`, 없는 offset은 `404 DEAD_LETTER_NOT_FOUND`, 브로커에 닿지 못하면 `503`이다. 모두 기록하지 않는다. `sub`가 틀리면 500이고 DLT를 읽지 않는다.
7. 지금 코드가 읽지 못하는 메시지도 폐기할 수 있다. 재투입한 레코드를 폐기해도, 폐기한 레코드를 재투입해도 둘 다 기록되고 gauge는 그 레코드를 한 번만 뺀다.
8. V020 뒤 `t_dead_letter_dismissal`이 있고 `(dlt_topic, dlt_partition, dlt_offset, dlt_timestamp)`가 unique다.
9. `docker/prometheus/alerts.yaml`이 16개 규칙(#102의 규칙 포함)을 담고 promtool 검사를 통과한다. promtool 시나리오가 다음을 보인다. 경과 시간이 임계값을 넘은 지 5분 뒤 울린다. 한 pod 재기동(`NaN` 구간)이면 풀렸다가 첫 갱신 뒤 5분에 다시 울린다. 여러 pod면 한 pod의 재기동에 끊기지 않는다. 미처리 DLT가 0보다 큰 동안 울리고 0이 되면 바로 풀린다. pod 사이는 `max`로 묶는다. 다른 pod가 세고 있으면 갱신이 멈춘 pod의 옛 값은 5분 뒤 빠지고, 모든 pod가 세지 못하면 마지막 값으로 유지한다. DLT 전송 실패 알림이 24시간 붙잡지 않는다. 갱신 작업이 한 번도 성공하지 못하면 생존 규칙이 울린다.
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
- 질문 2(지금 읽는 코드): 미처리 gauge가 범위로 세고, 폐기가 좌표와 레코드 시각으로 기존 기록을 찾아 멱등 응답을 만들고, 목록이 범위로 찾아 레코드 시각까지 맞는 기록으로 `dismissal`을 채운다. `operator_subject`는 응답과 목록이 읽는다. `reason`은 읽는 코드가 없다. 사람이 DB에서 보는 감사 정본이고 폐기 시점에만 알아 나중에 채울 수 없다(질문 3, `t_dead_letter_redrive.reason`과 같은 판단).
- 질문 3(나중에 채우기): 폐기 시점에만 아는 값이라 나중에 채울 수 없다.
- 질문 4(마지막 방어선): unique `(dlt_topic, dlt_partition, dlt_offset, dlt_timestamp)`가 "한 DLT 레코드의 폐기 기록은 하나"와 멱등 응답의 유일한 방어선이다. 레코드 시각을 넣는 이유는 재투입 기록과 같다(DLT 토픽을 다시 만들면 offset이 재사용된다). 지우는 명령이 없고 멱등 키라 soft delete된 행에도 걸리게 둔다(partial이 아니다). 조회는 `@SoftDelete`로 삭제 행을 보지 못하므로 이 표의 행은 SQL로도 soft delete하지 않는다. 삭제 명령이 생기면 조회가 삭제 행도 보게 한다(재투입 기록과 같다).
- 질문 5(정본): 폐기 기록의 정본은 이 표 하나다. 감사 INFO 로그는 사유가 없는 사본이고 커밋 뒤 남긴다. 미처리 gauge는 Kafka offset과 두 표에서 매번 계산하는 사본이라 따로 저장하지 않는다. 어긋나면 다음 갱신(1분)이 맞추고, 갱신이 실패하는 동안은 마지막 값에 멈추며 `KafkaDeadLetterMetricNotSucceeding`이 알린다(결정 3).
- 질문 6(check-then-act): 기록 확인 뒤 기록한다. 경합은 unique가 막고 진 쪽은 위반을 잡아 이긴 쪽 기록을 돌려준다. 기다리는 수단(row lock·`Lock`)은 두지 않는다. 행을 고치는 경로가 없어 `@Version`도 없다. 재투입 표와의 배타는 두지 않는다(결정 4). 두 표에 걸친 제약은 DB가 지킬 수 없고, gauge가 합집합으로 세어 둘 다 있어도 틀리지 않는다. 다만 합집합의 두 조회가 같은 시점을 읽지 않아, 폐기한 레코드의 재투입이 그 사이에 커밋되면 한 갱신 동안 겹친 레코드 수만큼 많게 센다(한계).
- 질문 7(락·크기): 새 표 생성이라 다른 표를 잠그지 않는다. 기존 행이 없어 위반 행 검사가 필요 없고 `lock_timeout`을 두지 않는다(V019와 같다).
- 질문 8(이전 버전 호환): 이전 버전 애플리케이션은 이 표를 모르고 쓰지 않는다. `validate`는 매핑된 엔티티만 보고, Flyway는 모르는 미래 버전을 무시하므로(`ignoreMigrationPatterns=*:future`) 이전 버전이 새 스키마에서 뜬다. 롤링 배포 중 이전 버전 pod에는 미처리 gauge도 폐기 endpoint도 없다. 규칙은 새 pod의 값만 보고, 새 pod는 폐기 기록을 읽어 폐기한 레코드를 미처리로 세지 않는다. 이전 버전 pod로 간 폐기 요청은 실패하므로 배포가 끝난 뒤 다시 보낸다. 리뷰 반영 전의 V020(머리 주석 6행이 다름)을 적용한 로컬 DB는 checksum 검증에서 막히므로 초기화한다. 공유 compose DB는 실측 뒤 019로 되돌렸다(결과).
- 질문 9(이벤트·캐시): 이벤트 payload나 캐시 DTO에 실리지 않는다.
- `dlt_topic varchar(249)`, `operator_subject varchar(255)`, `reason varchar(500)`, `dlt_timestamp timestamptz`는 재투입 기록과 같은 근거다(V019).
- 대조 기록: code-reviewer 에이전트(읽기 전용), 2026-10-22, 첫 대조에서 질문 8 불일치·질문 6 보강·V020 주석 6행 지적 뒤 재대조, V020·V019·DeadLetterDismissalEntity·DeadLetterDismissalJpaRepository·DeadLetterDismisser·DeadLetterReader·DeadLetterHandledQueries·UnhandledDeadLetterMetrics·DeadLetterController·DeadLetterReason·DeadLetterDismissResponse·DeadLetterDismissalSummary·BaseEntity·application.yaml·도메인 모델 §4.7·platform-operator-dlt-redrive spec, 불일치: 없음

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
- [관측성](../wiki/observability.md): 알림 규칙 항목(14개에서 15개, DLT 두 규칙의 `keep_firing_for` 문장), Kafka 소비 항목의 첫 DLT 처리 문장, 운영자 항목의 폐기와 미처리 gauge, 결과 불명 항목의 경과 시간 gauge와 규칙 조건, 주기 작업 생존 항목의 새 작업, "아직 없는 것"의 #104·#105 두 줄을 지운다.
- [도메인 모델](../wiki/domain-model.md): §4.3 "DLT 재투입"에 폐기 단계, §4.7 사례에 `t_dead_letter_dismissal`. 용어집에 새 단어 "폐기(dismissal)"가 있는지 보고 없으면 더한다.
- [이벤트 계약](../wiki/event-contract.md) 5절: "규칙 `KafkaDeadLetter`가 10분 전에 없던 series를 따로 잡는다"를 미처리 gauge 규칙으로 바꾼다. 8절에 재투입하지 않을 메시지는 폐기한다는 한 줄.
- [엔티티 맵](../wiki/entity-map.md)과 [wiki 인덱스](../wiki/README.md)의 엔티티 수: `t_dead_letter_dismissal`.
- [아키텍처](../wiki/architecture.md) 모듈 표의 `platform` 책임: "DLT 재투입 API와 기록"에 폐기를 더한다.
- README: `/admin/**` 문장의 "(DLT 재투입)"에 폐기를 더한다.
- Modulith·Kafka 키워드(볼트): PR 전에 그 문서의 갱신 규칙대로 미처리 DLT 계산과 폐기 항목을 고친다. 볼트의 현재 상태 문서는 머지 뒤 고친다.
- 요구사항 변화: 시스템 설계 7절의 alert → runbook 연결과 "payment_reconciliation_age_seconds"(넘긴 시도의 경과 시간)을 채운다. 플랫폼 운영자 행위에 폐기가 더해지고 감사는 재투입과 같은 기준이다. 업무 정합성 요구는 그대로다.

## 분할 계획
한 개발 컨텍스트(알림을 실제 상태에 맞추기)다. 폐기 표시는 미처리 DLT gauge가 맞으려면 필요해서 함께 담는다(사용자 결정). `PAYMENT_APPROVAL_MISMATCH` 메시지는 설계상 재투입하지 않고 DLT에 남는 case 자리라([도메인 모델 §4.3](../wiki/domain-model.md#43-이벤트-전달과-저장-형식), `KafkaConfiguration` KDoc), 폐기 없이는 상태 알림이 꺼지지 않는다.

겹침 확인(2026-10-22, origin/main `8221d79`):
- 열린 draft PR #102(feature/inbound-rate-limit): `docs/wiki/observability.md`(7~15행 근처 항목 추가와 "점주 세션 저장소" 항목 수정)와 `DeadLetterControllerTest.kt`(클래스 annotation 한 줄)를 고친다. 이 PR은 observability의 다른 항목(알림 규칙·Kafka 소비·운영자·결과 불명·주기 작업·아직 없는 것)을 고치고, 폐기 Controller 테스트를 같은 파일에 더해 #102의 annotation을 rebase 때 그대로 받는다. #102가 `inbound_rate_limited_total` 알림을 `alerts.yaml`에 더하면 규칙 수가 겹치므로 늦게 머지되는 쪽이 `AlertRulesTest`의 규칙 수를 맞춘다.
- 다시 확인(2026-10-22, origin/main `8221d79` 그대로): #102가 ready가 됐고 규칙 `InboundStoreRateLimited`를 더해 `AlertRulesTest`를 14에서 15로 바꿨다. 이 PR도 같은 줄을 같은 글자로 바꿔 git은 충돌 없이 합치지만 실제 규칙은 16개가 된다. 나중에 머지되는 쪽이 `AlertRulesTest`의 규칙 수와 관측성 wiki의 "rule 15개"를 16으로 고치고 promtool·runbook 앵커 대조를 다시 돌린다. #102와 겹치는 그 밖의 경로(`alerts.test.yaml`·`runbook.md`·`observability.md`·`architecture.md`·`README.md`·`DeadLetterControllerTest.kt`)는 서로 다른 절·항목이다.
- push 직전 확인(2026-10-22 06:0x UTC): #102가 main에 머지됐다(`dbaa09e`). origin/main 위로 rebase했고 spec 첫 커밋은 그대로 첫 커밋이다. 줄 단위 충돌은 `observability.md` 두 곳이었다(#102가 고친 "노출 범위"·"점주 세션 저장소" 항목의 이웃 줄). main 쪽 줄 위에 이 PR의 문장을 다시 적용했다. 나머지 겹친 경로(`alerts.yaml`·`alerts.test.yaml`·`runbook.md`·`architecture.md`·`README.md`·`DeadLetterControllerTest.kt`)는 자동으로 합쳐졌다. 규칙이 16개가 되어 `AlertRulesTest`와 관측성 wiki의 규칙 수를 16으로 고쳤다.
- PR 생성 뒤 확인(2026-10-22): #77이 PR #109(perf/k6-capacity-measurement, `dbaa09e` 위)로 열렸다. 겹치는 경로는 `domain-model.md`·`observability.md`·`README.md`·`UnknownPaymentResolver.kt`·`ObservabilityConfigurationTest.kt`이고 `git merge-tree --write-tree HEAD origin/perf/k6-capacity-measurement`가 충돌 없이 끝났다. 코드 쪽은 #109가 `UnknownPaymentResolver` KDoc의 락 대기 숫자와 `ObservabilityConfigurationTest`의 테스트 메서드 하나를 바꿔 이 PR의 변경과 뜻이 겹치지 않는다. 먼저 머지된 쪽에 맞춰 다른 쪽이 rebase하고 전체 테스트를 다시 돌린다.
- 열린 draft PR #94(refactor/modulith-2-1-2-resubmit): `docs/wiki/domain-model.md` §4.3의 재전송 항목을 고친다. 이 PR은 §4.3의 DLT 재투입 항목과 §4.7 사례를 고친다.
- 진행 중인 #77(perf/k6-capacity-measurement, push 전): `docs/wiki/observability.md` 17행 근처("결제 대조 case" 항목)와 `docs/wiki/domain-model.md`를 고친다. 이 PR은 "결제 대조 case" 항목을 고치지 않는다. 개수 gauge를 남기므로 그 항목의 "미해소 건수는 위 pending gauge로 본다"가 그대로 맞다.
- 예정된 #103(SLO·시작값 알림)은 `alerts.yaml`을 고친다. 점주 이메일 작업(#92)이 새 마이그레이션을 더할 수 있어 push 전에 V020 번호 충돌을 확인한다.
- 공용 헬퍼: 사유 검사(1~500자·NUL)는 `DeadLetterRedriver`와 같은 규칙이라 그 자리에서 함께 쓰도록 뽑을지 구현 때 본다. main과 열린 PR에 폐기·미처리 DLT 코드는 없다.

## 범위 조정
multi-review(Claude 84점, Codex 72점 "주의 병합", Gemini는 CLI가 없어 미실행)와 스키마 대조(code-reviewer) 뒤 아래를 바꾼다.
1. **갱신이 멈춘 인스턴스의 옛 값이 알림을 붙잡지 않게 한다(두 리뷰, 사용자 결정).** 실패하면 마지막 값을 유지하는 결정과 `max`가 만나면, 한 인스턴스만 계속 실패할 때 그 옛 값이 처리 뒤에도 `KafkaDeadLetter`를 붙잡았다. 마지막 성공 시각 gauge를 더하고 규칙이 5분 안에 센 인스턴스만 보게 했다(결정 3·6). 모든 인스턴스가 세지 못하면 마지막 값으로 유지해 앞의 결정(브로커 장애 때 알림을 풀지 않는다)을 지킨다. 인스턴스를 자동 재시작하는 방법은 버렸다. 브로커 전체 장애 때 모든 pod가 재시작되고, 앱은 브로커 없이 뜨지 않아(`spring.kafka.admin.fail-fast`) 재시작만 되풀이한다. 결과 불명 경과 시간 알림(warning)에는 적용하지 않고 runbook에 대처를 적었다(한계).
2. **생존 규칙은 warning으로 둔다(Claude는 critical을 제안).** 1의 규칙으로 한 인스턴스의 실패는 DLT 알림을 막지 않는다. 모든 인스턴스가 실패하면 브로커·DB 전체 장애라 다른 신호가 함께 나온다.
3. **질문 8과 runbook의 롤링 배포 문장을 고친다(세 리뷰).** 이전 버전 pod에는 미처리 gauge가 아예 없어 폐기한 레코드를 미처리로 세는 pod가 없다. 첫 배포 직후의 기존 DLT와 배포 순서(앱 먼저, 규칙 나중)를 한계에 적었다.
4. **V020 머리 주석 6행을 고친다(스키마 대조, Claude).** soft delete된 행이 있으면 "기록한 뒤"가 아니라 기록하지 못하고 500이다. 머지 뒤에는 checksum 때문에 고칠 수 없다.
5. **promtool 시나리오를 보강한다(Codex, Claude).** `for: 5m` 경계를 정확히 고정하는 평가(9분, 39분), 두 pod 값이 다를 때 큰 쪽을 내는 평가, 1의 두 시나리오(멈춘 pod의 옛 값은 5분 뒤 빠짐, 모두 멈추면 유지)를 더하고 주석의 분을 바로잡았다. `ReconciliationPendingUnresolved`의 summary를 "reconciliation으로 넘긴 결과 불명 결제가 24시간 넘게 해소되지 않았다"로 바꿨다. case 열기가 실패한 시도도 이 경과 시간에 들기 때문이다.
6. **runbook과 wiki 세부를 고친다(Claude).** 재투입했는데 기록이 남지 않은 경우(`DEAD_LETTER_REDRIVE_UNRECORDED`)의 대처, 경과 시간 알림에서 멈춘 인스턴스를 찾는 법, 도메인 모델의 엔티티 수·목록 항목·gauge 나열, 엔티티 맵의 마이그레이션 목록.
7. **테스트를 보강한다(Codex, Claude).** 실제 DB에서 같은 레코드를 동시에 폐기하는 경합, 폐기의 503에서 기록하지 않음, API 폐기 뒤 gauge 감소, 다른 partition·범위 밖 기록을 빼지 않음, 갱신 실패 때 값 유지의 변이 시험. 증가분 테스트는 스케줄러가 도는 빈 대신 새 인스턴스로 센다(스케줄 실행과 겹치면 값이 덮인다).
8. **문서의 "나이"를 "경과 시간"으로 바꾼다(사용자 결정).** "나이"는 운영 모니터링의 영어 age를 그대로 옮긴 말이라 어색하다. 수명(살 수 있는 전체 기간)과 생명주기(상태 단계)는 뜻이 달라 쓰지 않는다. 살아 있는 문서(runbook·관측성·도메인 모델 wiki, 알림 규칙 주석과 promtool 시나리오 이름, 이 spec)와 코드 주석·gauge 설명·테스트 이름을 바꾸고, #76이 쓴 발행 기록 설명도 함께 통일했다. 도메인 모델 용어집에 `oldest_seconds`(경과 시간) 행을 더했다. metric 이름은 영어 `…_oldest_seconds` 그대로다. 머지된 옛 spec은 기록이라 고치지 않는다.
- **받지 않은 것.** 합집합의 두 조회를 한 문장으로 합치기(드물고 1분이면 맞는다, 한계에 적음). offset distinct 집계(DLT 토픽 재생성 한계와 같은 범주). 갱신 주기를 설정 키로 열기(운영 설정을 늘리지 않고 테스트는 새 인스턴스로 피한다). 토픽별 실패 격리(한계에 적음). `KafkaDeadLetter`의 짧은 `keep_firing_for`(한 pod 재기동, 한계에 적음). `DLT_SUFFIX` 중복 정리(기존 두 곳과 같은 모양이라 이 PR 밖). 경과 시간이 음수일 때 0으로 자르기, earliest·latest 동시 호출(효과가 작다).

## TDD 검증 기록
업무 동작(경과 시간 gauge, 미처리 gauge, 폐기 API)은 TDD로 한다. 규칙 파일은 promtool 시나리오로 먼저 실패를 보고 고친다. 개발 전 테스트 의도이고 실행 뒤 채운다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 1: `UnknownPaymentResolverIntegrationTest`의 `넘기지 않은 가장 오래된 UNKNOWN 승인 시도의 경과 시간을 gauge로 낸다`, `넘긴 시도는 첫 gauge에서 빠지고 pending gauge는 넘긴 시각부터 잰다`, `첫 갱신 전 경과 시간 gauge는 NaN이다`, `대상이 없으면 경과 시간 gauge는 0이다`, `경과 시간을 세다 던지면 경과 시간 gauge는 마지막 성공값으로 남는다`, `경과 시간 조회는 kind와 status를 리터럴로 렌더한다`(tdd-driver) | port·adapter·NaN gauge 등록까지 두고 갱신 코드 없이 `./gradlew test --tests 'com.resilientpos.payment.internal.application.UnknownPaymentResolverIntegrationTest'`. 18개 중 4개 실패(`Expected 259200.024 … actual NaN`, `Expected 518400.01 … actual NaN`, `expected 0.0 but was NaN`, 던짐 기대에 정상 종료) | 같은 명령. 18개 통과 | `./gradlew test --tests 'com.resilientpos.payment.*' --tests 'com.resilientpos.ModuleStructureTest' --tests 'com.resilientpos.TransactionDeclarationTest' --tests 'com.resilientpos.ConstraintTranslationTest'`. 27개 클래스 179개 통과 |
| 인수 3·7: `UnhandledDeadLetterMetricsIntegrationTest`의 `DLT 레코드는 재투입이나 폐기 기록이 있으면 하나씩 빠지고 둘 다 있어도 한 번만 빠진다`(실제 브로커·DB, 증가분 +3 → +2 → +1 → +0), `첫 갱신 전에는 설정된 토픽마다 NaN이다`, `갱신이 실패하면 예외를 던지고 마지막 성공값을 유지한다`(두 번째 갱신의 DB 조회가 던짐), `브로커에 닿지 못하면 상한 안에 Kafka 예외를 던지고 값은 NaN이다`(`localhost:1`, 8초 안)(tdd-driver) | gauge 등록과 빈 `countUnhandled`만 두고 `./gradlew test --tests 'com.resilientpos.platform.deadletter.UnhandledDeadLetterMetrics*' --tests 'com.resilientpos.platform.ObservabilityConfigurationTest'`. 8개 중 3개 실패(기준값이 NaN, `미처리 수는 하나 이상이다: NaN`, `TimeoutException` 기대에 정상 종료). 처음에는 증가분 테스트가 `assertEquals`의 NaN 대 NaN으로 통과해 기준값이 NaN이 아니라는 단언을 더했다 | 같은 명령. 8개 통과. 합집합 대신 두 수를 그냥 더하게(`not exists`를 항상 참으로) 바꾸면 증가분 테스트가 마지막 단계에서 실패했고 되돌렸다 | `./gradlew test --tests 'com.resilientpos.platform.*'`와 SchemaMigration·구조 테스트 5종. 234개 통과. 그 뒤 리뷰에서 테스트 대역만을 위한 `fun interface`를 지우고(대역은 `DeadLetterHandledQueries`를 상속한 익명 객체) `DeadLetterRange`를 자기 파일로 옮겼다. `UnhandledDeadLetterMetricsIntegrationTest`·`ObservabilityConfigurationTest`·`AlertRulesTest` 10개 통과 |
| 인수 4~8: `DeadLetterDismissIntegrationTest` 7개(폐기 200·행 하나·사유 trim·감사 줄 하나에 사유 없음·목록 `dismissal`, 같은 레코드 재요청은 같은 응답·행과 감사 줄 그대로, 읽지 못하는 메시지(JSON 아님)도 폐기, 재투입한 레코드를 폐기해도 두 기록이 남고 목록에 `redrive`와 `dismissal`이 함께, 폐기하지 않은 레코드는 `dismissal` null, 레코드 시각이 다른 옛 기록은 목록에 붙지 않음, 없는 offset 404·잘못된 사유 400이고 기록 없음), `DeadLetterDismisserTest` 7개(500자 통과·공백 제거 저장·감사 줄에 사유 없음, 사유 400이면 reader·repository·EntityManager 무호출, 위치 400이면 기록 조회 없음, 404, 기존 기록이면 persist 없이 처음 기록, unique 위반이면 이긴 기록·감사 줄 없음, 다른 제약 위반은 그대로), `SchemaMigrationTest`의 `V020 뒤 DLT 폐기 기록은 DLT 레코드의 좌표와 레코드 시각 하나에 한 행만 들어간다`와 적용 버전·표 목록(tdd-driver) | 엔티티·repository·V020·DTO와 `TODO()` 본문의 서비스, 매핑 없는 Controller만 두고 `./gradlew test --tests 'com.resilientpos.platform.deadletter.*' --tests 'com.resilientpos.SchemaMigrationTest'`. 70개 중 17개 실패(Controller·통합은 `Status expected 200 but was 404`와 `NOT_FOUND`, 단위는 `NotImplementedError`와 예외 미발생, 목록 키에 `dismissal` 없음). V020을 선언으로 먼저 넣어 `SchemaMigrationTest`는 RED에서 이미 통과했다 | 같은 명령. 70개 통과. 기존 폐기 기록 확인 한 줄을 지우면 단위 2개(기존 기록, unique 위반)가 실패하고 통합 재요청은 unique 경로가 같은 200을 돌려줘 통과했다(확인은 불필요한 INSERT와 롤백을 막는다). 되돌렸다 | `./gradlew test --tests 'com.resilientpos.platform.*'`와 SchemaMigration·구조 테스트 5종. 230개 중 1개 실패(`ObservabilityConfigurationTest`의 이름 대조, 미처리 DLT gauge가 아직 없다. 다음 행이 GREEN으로 만든다) |
| 인수 9: `alerts.test.yaml`의 새 시나리오 8개(미처리 DLT가 있는 동안 울리고 처리하면 풀림, pod 사이 max와 첫 갱신 전 NaN, DLT 전송 실패를 하루 붙잡지 않음, 갱신 작업이 한 번도 성공하지 못함, 경과 시간 2시간 초과, 여러 pod 재기동, 경과 시간 24시간 초과, 한 pod 재기동) | 시나리오만 바꾸고 옛 규칙으로 `docker run --rm -v "$PWD/docker/prometheus:/rules:ro" --entrypoint promtool prom/prometheus:v3.15.0 test rules /rules/alerts.test.yaml`. 미처리 DLT·경과 시간·생존 시나리오가 `got:[]`, DLT 전송 실패는 20분에도 firing(`keep_firing_for: 24h`) | 규칙을 바꾼 뒤 `promtool check config`와 같은 명령. `SUCCESS: 15 rules found`, 시나리오 전부 통과. 첫 실행에서 DLT 시나리오의 `a+bxn`이 n+1개라 16분에도 1이었고 시나리오 시각을 고쳤다 | `./gradlew test --tests 'com.resilientpos.platform.AlertRulesTest'`(promtool 검사·시나리오와 runbook 앵커 대조) 통과 |
| 인수 10: `ObservabilityConfigurationTest`의 `알림 규칙이 쓰는 metric 이름이 모두 나간다`와 Kafka metric 테스트의 토픽별 `event_kafka_dead_letter_unhandled` 단언 | 폐기 표시 단계의 회귀에서 `없는 이름`에 `event_kafka_dead_letter_unhandled`가 나와 실패했다(규칙만 바뀐 상태). 미처리 DLT gauge의 껍데기가 gauge를 등록한 뒤에는 통과해 그 단계의 RED로는 재현하지 못했다 | 미처리 DLT gauge 구현 뒤 같은 테스트 통과. 갱신 작업이 기동 직후 돌아 `tasks_scheduled_execution_seconds_count`를 예외 목록에서 뺐다 | 위와 같다 |
| 인수 11: `DeadLetterControllerTest`의 `폐기는 200과 폐기한 운영자 시각을 돌려주고 토큰의 sub를 운영자로 넘긴다`, `폐기 사유가 규칙에 어긋나면 400 INVALID_DISMISS_REASON이고 본문에 사유가 없으면 빈 사유로 넘긴다`, `폐기할 offset에 레코드가 없으면 404 DEAD_LETTER_NOT_FOUND다`, sub 500 테스트에 폐기 endpoint, 목록의 `dismissal` 필드 | 위 인수 4~8 행의 RED에 포함(매핑 없음, `Status expected 200 but was 404`) | 위 GREEN에 포함. `build/generated-snippets`에 `dead-letters-dismiss`·`-dismiss-invalid-reason`·`-dismiss-not-found`가 생기고 `dead-letters-list`에 `dismissal`·`dismissal.operator`·`dismissal.dismissedAt`이 문서화됐다 | 위와 같다 |
| 범위 조정 1(앱): `UnhandledDeadLetterMetricsIntegrationTest`의 `마지막 성공 시각은 첫 갱신 전 0이고 성공하면 그 갱신의 시각이며 실패하면 그대로다`(조작 가능한 Clock), `ObservabilityConfigurationTest`의 이름 대조(tdd-driver) | 생성자에 `Clock`만 더해 컴파일한 뒤 `./gradlew test --tests 'com.resilientpos.platform.deadletter.UnhandledDeadLetterMetricsIntegrationTest' --tests 'com.resilientpos.platform.ObservabilityConfigurationTest'`. 9개 중 2개 실패(`MeterNotFoundException`, `없는 이름: [event_kafka_dead_letter_unhandled_refreshed_seconds]`) | `./gradlew test --tests 'com.resilientpos.platform.deadletter.*' --tests 'com.resilientpos.platform.ObservabilityConfigurationTest' --tests 'com.resilientpos.platform.AlertRulesTest'` 통과 | `./gradlew test --tests 'com.resilientpos.platform.*'`와 SchemaMigration·구조 테스트 5종. 239개 통과 |
| 범위 조정 1(규칙): `alerts.test.yaml`의 `다른 pod가 세고 있으면 갱신이 멈춘 pod의 옛 값은 DLT 알림을 붙잡지 않는다`, `모든 pod가 세지 못하면 마지막 값으로 DLT 알림을 유지한다` | 시나리오만 더하고 옛 규칙으로 docker promtool. 첫 시나리오가 11분에 `got` firing으로 실패, 둘째는 통과(옛 규칙도 max라 유지) | 규칙을 바꾼 뒤 같은 명령. `SUCCESS: 15 rules found`, 시나리오 전부 통과. 마지막 성공 시각 series가 없는 기존 DLT 시나리오는 "모두 세지 못함" 쪽으로 계속 통과한다 | `AlertRulesTest` 통과(위 행의 회귀에 포함) |
| 범위 조정 5: `for: 5m` 경계(9분·39분 평가), pod 값이 다를 때 큰 쪽(10분 평가), summary 변경 | 경계 평가는 규칙 사본의 `for`를 6m으로 바꾸면 `넘기지 않은 결과 불명의 경과 시간이 2시간을 넘으면 5분 뒤 울린다`가 9분에서 실패한다(변이 확인, 저장소 파일은 그대로) | docker promtool 통과 | 위와 같다 |
| 범위 조정 7: `UnhandledDeadLetterMetricsIntegrationTest`의 `다른 partition이나 범위 밖 offset이나 다른 DLT 토픽의 기록 행은 미처리 수에서 빼지 않는다`, 증가분 테스트를 새 인스턴스로; `DeadLetterDismissIntegrationTest`의 `API로 폐기하면 그 토픽의 미처리 수가 다음 갱신에 하나 준다`, `기록 확인이 커밋 전인 같은 레코드의 기록을 못 보고 저장하면 unique 위반을 번역해 이긴 기록을 200으로 돌려주고 행은 하나이며 감사 줄을 남기지 않는다`(JDBC 트랜잭션과 `pg_blocking_pids`로 실제 경합); `DeadLetterDismisserTest`의 `DLT를 읽다 브로커에 닿지 못하면 기록하지 않고 DependencyUnavailableException을 그대로 던진다`(tdd-driver) | 모두 처음부터 통과(characterization). 갱신 시작 직후 모든 토픽 값을 NaN으로 쓰게 잠깐 깨면 `갱신이 실패하면 예외를 던지고 마지막 성공값을 유지한다`가 `expected 1.0 but was NaN`으로 실패했고 되돌렸다. 범위 밖 기록과 실제 DB 경합 테스트는 운영 코드를 지워 보는 확인을 하지 않았다 | `./gradlew test --tests 'com.resilientpos.platform.deadletter.*'` 통과 | 위 범위 조정 1(앱) 행의 회귀에 포함 |

## 결과
- 인수 12(compose, 2026-10-22 UTC). 공유 스택(#77 측정용)이라 #77 세션과 맞춰 03:13~03:28에 썼다. lgtm에는 데이터 볼륨이 없어 다시 만들면 #77의 기록이 사라지므로 lgtm·alloy는 건드리지 않았다. app만 이 브랜치로 다시 빌드하고(`--profile app up -d --build --no-deps app`), 같은 compose 네트워크에 `prom/prometheus:v3.15.0`(이 worktree의 `alerts.yaml`, app을 15초마다 scrape, 평가 15초)을 따로 띄워 `/api/v1/rules`·`/api/v1/alerts`를 15초마다 기록했다.
  - 03:13:33 Flyway가 019에서 V020을 적용했고 03:13:37 기동했다. 규칙 15개가 모두 `health=ok`로 실렸다. `event_kafka_dead_letter_unhandled`가 다섯 토픽에 나왔고(`order.paid`는 #76 실측이 남긴 DLT 1건), 경과 시간 gauge 둘은 첫 갱신 전이라 `NaN`이었다. `KafkaDeadLetter`(`order.paid`)는 처음부터 firing이었다.
  - 03:14:33 결과 불명 시도 둘을 SQL로 넣었다(넘기지 않은 것은 3시간 전 생성, 넘긴 것은 25시간 전 넘김, 금액 끝자리 4라 조회해도 불명). 03:14:38 `order.paid`에 JSON이 아닌 메시지를 넣어 03:14:39 `DLT로 보냄 … reason=error`가 남았다.
  - 03:14:49 경과 시간 10,803초·90,003초로 `UnknownPaymentResolutionStuck`·`ReconciliationPendingUnresolved`가 pending, 03:19:52 둘 다 firing(5분)이었다. 그때 미처리 DLT는 2건이었다.
  - 03:19:58 두 DLT 레코드(`order.paid.DLT` partition 0 offset 0·1)의 폐기 행을 SQL로 넣었다(compose에서는 IdP가 없어 `/admin`을 부를 수 없다). 03:20:52 미처리가 0이 되고 `KafkaDeadLetter`가 풀렸다.
  - 03:20:58 app 컨테이너를 재시작했다(03:21:09 기동). 03:21:23 경과 시간이 `NaN`이 되어 두 결제 알림이 풀렸고, 03:22:23 확정 작업의 첫 주기(기동 1분 뒤)에서 경과 시간 11,254초·90,454초로 다시 pending, 03:27:27 다시 firing이었다. 2시간·24시간을 처음부터 세지 않았다.
  - 정리: 시험 행 둘을 지우고 `t_dead_letter_dismissal`과 V020 이력 행을 지워 공유 DB를 019로 돌렸다(뒤에 V020 주석을 고쳐 checksum이 바뀌기 때문이다). `order.paid.DLT` offset 1의 시험 메시지는 남아 있다. 범위 조정 1(마지막 성공 시각과 멈춘 인스턴스 제외)은 실측 뒤에 바꾼 것이라 promtool로만 확인했다.
- 인수 1·3~11·13: TDD 검증 기록의 테스트가 통과했다. `./gradlew test` 전체는 127개 클래스, 832개 테스트, 실패·오류·건너뜀 0이었다(2026-10-22 03:06~03:10 UTC, `80336c9` 위). 범위 조정을 반영한 뒤 다시 돌려 127개 클래스, 837개 테스트, 실패·오류·건너뜀 0이다(05:58~06:04 UTC, `52b1ae5` 위). 공유 compose 스택을 쓰는 #77 측정과 시간을 맞춰 돌렸다. #102 머지 뒤 rebase하고 규칙 수를 16으로 고친 트리에서 다시 돌려 131개 클래스, 887개 테스트, 실패·오류·건너뜀 0이다(06:06~06:13 UTC). promtool은 `SUCCESS: 16 rules found`, 시나리오 전부 통과다.
- 인수 9: promtool `SUCCESS: 15 rules found`, 시나리오 전부 통과(`AlertRulesTest`).
- `python3 scripts/check_pr.py --base origin/main --worktree`: PASS. 스키마 대조 기록 경고는 code-reviewer 재대조 뒤 해소했다.
- 인수 2(실행 계획, 2026-10-22): compose와 무관한 PostgreSQL 18(`postgres:18-alpine`) 컨테이너에 V001~V019를 적용하고 승인 시도 20만 건(`APPROVED`·`DECLINED`)과 `UNKNOWN` 50건(20건은 넘김)을 넣어 `analyze`했다. Hibernate가 렌더하는 모양(`kind`·`status` 리터럴, `deleted_at is null`)으로 prepare해 `plan_cache_mode = force_generic_plan`에서 보면 두 조회 모두 `Index Scan using ix_payment_attempt_unknown_authorization`이다. 넘기지 않은 쪽은 `Limit`이 인덱스 순서로 첫 행을 받고(넘긴 행 20개를 Filter로 거름), 넘긴 쪽은 `UNKNOWN` 50행만 읽어 `min`을 낸다(shared hit 3).
