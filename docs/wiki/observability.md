# 관측성

애플리케이션은 metric과 로그를 표준 형식으로 내보내기만 한다. 수집·저장·대시보드는 밖에서 붙인다(Grafana LGTM 전제). 설계 방향은 [시스템 설계 7절](../references/02_System_Design.md#7-nfr-and-observability)을 따른다.

## 지금 있는 것
- **metric**: `GET /actuator/prometheus`가 Prometheus text 형식을 돌려준다. 요청 수·지연·오류율(`http_server_requests_seconds_*`, 지연은 p95 등을 계산할 수 있는 histogram bucket 포함), JVM, Hikari 풀 metric이 들어 있다. 모든 metric에 `application="ResilientPOS"` 태그가 붙는다.
- **이벤트 발행 기록**: `event_publication_incomplete{status, module, listener}`가 커밋 뒤 리스너가 끝내지 못한 기록 수다. `status="failed"`는 리스너가 던진 기록(상태가 비어 있는 미완료 행 포함), `status="stale"`은 발행·처리·재전송 상태인 채 기본 5분(`resilientpos.event-publication.stale-after`)이 지난 기록이다. 1분마다 갱신하고, 마지막 갱신 시각은 `event_publication_incomplete_refreshed_seconds`(epoch 초)다. 조회가 실패한 인스턴스는 개수를 내리고 이 시각을 멈춘다. 모든 인스턴스가 같은 DB를 세므로 개수는 `max`로 모으고, 신선도는 인스턴스별로(또는 `min`으로) 보며 기동 직후를 위해 `for:`를 둔다. 갱신 스레드는 종료 때 가장 먼저 멈추며 최대 3초를 쓰므로 배포의 종료 유예 시간에 더한다. 규칙과 조치 절차는 [도메인 모델](domain-model.md) §4.3에 있다.
- **노출 범위**: Actuator는 `health`와 `prometheus`만 연다. 앱에 인증이 없으므로 외부 차단은 배포(ingress·네트워크)에서 한다. 배포 전에 ingress가 `/actuator/**`를 외부로 라우팅하지 않는지 확인한다.
- **로그**: 기본은 평문이다. `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`를 주면 stdout이 한 줄에 ECS JSON 객체 하나가 된다.

## LGTM을 붙일 때
- Prometheus(또는 Alloy의 `prometheus.scrape`)가 각 인스턴스의 `/actuator/prometheus`를 scrape하고 `remote_write`로 Mimir에 보낸다. 애플리케이션 설정은 바꾸지 않는다.
- 배포 환경에 `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`를 주고, Alloy가 컨테이너 stdout을 읽어 Loki로 보낸다. Loki label 이름에는 점을 쓸 수 없으므로 Alloy가 ECS 필드 `service.name`·`log.level`을 꺼내 `service_name`·`level` label로 올린다. label은 이 둘로 한정한다. `log.logger`처럼 값이 많은 필드는 label로 두지 않고 쿼리에서 `| json`으로 거른다.
- `orderId`·`paymentId`·`terminalId`는 metric label로 쓰지 않는다. 로그 필드나 trace attribute로 남긴다.

## 아직 없는 것
- trace(OpenTelemetry·Tempo), traceId와 correlation id의 MDC 전파.
- 도메인 metric(`payment_unknown_total` 등). 해당 기능 PR이 더한다.
- 로컬 LGTM 스택(docker compose).
- histogram bucket 범위와 SLO 경계. 지금은 Micrometer 기본 bucket이다. endpoint가 늘어 series 비용이 보이면 `minimum-expected-value`·`maximum-expected-value`나 `slo`로 좁힌다.
