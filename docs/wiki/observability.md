# 관측성

애플리케이션은 metric과 로그를 표준 형식으로 내보내기만 한다. 수집·저장·대시보드는 밖에서 붙인다(Grafana LGTM 전제). 설계 방향은 [시스템 설계 7절](../references/02_System_Design.md#7-nfr-and-observability)을 따른다.

## 지금 있는 것
- **metric**: `GET /actuator/prometheus`가 Prometheus text 형식을 돌려준다. 요청 수·지연·오류율(`http_server_requests_seconds_*`, 지연은 p95 등을 계산할 수 있는 histogram bucket 포함), JVM, Hikari 풀 metric이 들어 있다. 모든 metric에 `application="ResilientPOS"` 태그가 붙는다.
- **이벤트 발행 기록**: `event_publication_incomplete{status, module, listener}`가 커밋 뒤 리스너가 끝내지 못한 기록 수다. `status="failed"`는 리스너가 던진 기록(상태가 비어 있는 미완료 행 포함), `status="stale"`은 발행·처리·재전송 상태인 채 기본 5분(`resilientpos.event-publication.stale-after`)이 지난 기록이다. 1분마다 갱신하고, 마지막 갱신 시각은 `event_publication_incomplete_refreshed_seconds`(epoch 초)다. 조회가 실패한 인스턴스는 개수를 내리고 이 시각을 멈춘다. 모든 인스턴스가 같은 DB를 세므로 개수는 `max`로 모으고, 신선도는 인스턴스별로(또는 `min`으로) 보며 기동 직후를 위해 `for:`를 둔다. 갱신 스레드는 종료 때 가장 먼저 멈추며 최대 3초를 쓰므로 배포의 종료 유예 시간에 더한다. 규칙과 조치 절차는 [도메인 모델](domain-model.md) §4.3에 있다. 모듈 사이 이벤트가 Kafka로 가면서 이 metric은 브로커 전송 실패를 센다(`listener="EventExternalizerModuleListener"`). `failed`에는 주기 작업이 1분마다 다시 보내는 중인 기록도 들어 있다. 끊긴 기록은 5분이 지나면 같은 작업이 `failed`로 옮기므로 `stale`은 잠깐만 보인다. 사람이 볼 조건은 `failed`가 한 시간 내내 0이 아닌 경우다. 규칙은 `max(event_publication_incomplete{status="failed"}) > 0`에 `for: 1h`를 둔다. 개수가 0이 되면 시계열이 사라져 `for:`가 처음부터 다시 센다. `min_over_time`은 시계열이 없는 구간을 0으로 세지 않아 한 번 깜빡인 실패에도 울린다. 브로커 장애가 이어지거나 영영 나가지 못하는 기록이 있다는 뜻이고, 가르는 SQL은 도메인 모델 §4.3에 있다.
- **Kafka 소비**: `event_kafka_dead_letter_total{topic, reason}`이 토픽별로 DLT에 보낸 메시지 수다. `reason`은 도메인 오류 코드(예: `PAYMENT_APPROVAL_MISMATCH`, 업무 불일치)나 `error`(재시도 소진·버그)다. `reason="error"`는 기동할 때 0으로 등록되므로 `increase(...) > 0`으로 첫 증가를 잡는다. Boot가 Kafka 클라이언트 metric(`kafka_consumer_*`·`kafka_producer_*`)을 함께 내보낸다. consumer lag는 `kafka_consumer_fetch_manager_records_lag_max`이고 `client_id`에 consumer group 이름이 든다. 재시도마다 WARN(`소비 실패 topic=… key=… attempt=…`), DLT로 보낼 때 ERROR(`DLT로 보냄 …`) 로그가 한 줄씩 남는다. key는 `orderId`이고 로그 필드로만 남긴다. DLT 전송 자체가 실패하면 `event_kafka_dead_letter_failed_total{topic}`이 오르고 ERROR(`DLT 전송 실패…`)가 남는다. 0보다 크면 브로커·권한·메시지 크기를 먼저 본다. 재시도·DLT 규칙과 재투입 절차는 [도메인 모델](domain-model.md) §4.3에 있다.
- **노출 범위**: Actuator는 `health`와 `prometheus`만 연다. 앱은 `/v1/**`만 단말 토큰으로 인증하고 `/actuator/**`는 인증 대상이 아니므로 외부 차단은 배포(ingress·네트워크)에서 한다. 배포 전에 ingress가 `/actuator/**`를 외부로 라우팅하지 않는지 확인한다.
- **인증 실패**: 401 `UNAUTHENTICATED`와 403 `TERMINAL_MISMATCH`는 `ApiControllerAdvice`의 WARN 로그(`method`·`uri`·`errorCode`)로 남는다. 인증 실패 전용 metric은 없다. 토큰과 `Authorization` 헤더 값은 로그와 예외 메시지에 남기지 않는다. 401 예외는 스택 트레이스를 만들지 않아, 토큰 대입이 반복돼도 요청마다 한 줄만 쌓인다. 앱 밖의 리버스 프록시·ingress access log는 이 보장 밖이다. 배포 전에 그 로그가 `Authorization` 헤더를 남기지 않거나 가리는지 확인한다.
- **결과 불명 결제 확정**: 주기 작업(1분)이 provider 조회로 `UNKNOWN`을 확정하면 INFO(`결과 불명 결제를 확정했다 paymentId=… orderId=… checkoutNo=… status=…`), 조회로도 불명이면 WARN(`조회로도 결과를 확인하지 못했다 paymentId=… orderId=…`), 한 건이 던지면 ERROR(paymentId와 예외)가 남는다. 결과 불명 승인 시도 수는 gauge `payment_attempt_unknown`이다. 작업이 주기마다 세어 갱신하므로 scrape는 DB를 부르지 않고, 세기가 실패한 주기에는 마지막 성공값이 남는다. 모든 인스턴스가 같은 DB를 세므로 `max`로 모은다. 사람이 볼 조건은 `max(payment_attempt_unknown) > 0`이 한 시간 넘게 이어지는 경우다(`for: 1h`). provider가 그 요청을 끝내 확인해 주지 않는다는 뜻이고, 그 건을 넘겨받을 reconciliation case가 아직 없어 사람이 같은 `paymentId`의 WARN을 찾아 provider 콘솔에서 확인한다. 알림 규칙 파일은 아직 없다. 작업 규칙은 [도메인 모델](domain-model.md) §4.3에 있다.
- **로그**: 기본은 평문이다. `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`를 주면 stdout이 한 줄에 ECS JSON 객체 하나가 된다.

## LGTM을 붙일 때
- Prometheus(또는 Alloy의 `prometheus.scrape`)가 각 인스턴스의 `/actuator/prometheus`를 scrape하고 `remote_write`로 Mimir에 보낸다. 애플리케이션 설정은 바꾸지 않는다.
- 배포 환경에 `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`를 주고, Alloy가 컨테이너 stdout을 읽어 Loki로 보낸다. Loki label 이름에는 점을 쓸 수 없으므로 Alloy가 ECS 필드 `service.name`·`log.level`을 꺼내 `service_name`·`level` label로 올린다. label은 이 둘로 한정한다. `log.logger`처럼 값이 많은 필드는 label로 두지 않고 쿼리에서 `| json`으로 거른다.
- `orderId`·`paymentId`·`terminalId`는 metric label로 쓰지 않는다. 로그 필드나 trace attribute로 남긴다.

## 로컬에서 띄우기
`docker compose -f docker/docker-compose.yaml --profile app --profile obs up -d --build`가 앱 컨테이너, Alloy, `grafana/otel-lgtm`을 띄운다. PostgreSQL과 Kafka(KRaft 단일 노드, 호스트에서는 `localhost:9092`)는 profile 없이 늘 뜬다. 설정은 [docker-compose.yaml](../../docker/docker-compose.yaml)과 [Alloy 설정](../../docker/alloy/config.alloy)이다. Grafana는 `localhost:3000`이다.
- 위 "LGTM을 붙일 때"의 방식을 그대로 쓴다. Alloy가 `app:8080/actuator/prometheus`를 15초마다 scrape하고, docker socket으로 이 compose 프로젝트의 `app` 컨테이너 stdout만 읽어 `service_name`·`level` label을 올린다.
- 운영과 다른 점: Mimir 자리를 otel-lgtm 안의 Prometheus가 remote_write로 받는다. 호스트(IDE)에서 띄운 앱은 수집하지 않는다.
- kotlin-logging이 기동할 때 logback을 거치지 않고 평문 한 줄(`kotlin-logging: initializing...`)을 찍는다. JSON이 아니라 label이 없어서 Loki가 `service_name="unknown_service"`로 둔다.

## 아직 없는 것
- trace(OpenTelemetry·Tempo), traceId와 correlation id의 MDC 전파.
- 그 밖의 도메인 metric(`payment_unknown_total` 같은 발생 수)과 주기 작업의 생존 metric(마지막 성공 시각). 알림 규칙을 만드는 PR이 더한다.
- histogram bucket 범위와 SLO 경계. 지금은 Micrometer 기본 bucket이다. endpoint가 늘어 series 비용이 보이면 `minimum-expected-value`·`maximum-expected-value`나 `slo`로 좁힌다.
