# PR spec: SLO 알림에 발송 경로·느린 소진·probe와 재연결 기준 수렴 알림을 더하고 시작값을 다시 판정한다

## 목적
[slo-alert-baseline](2026-10-23-slo-alert-baseline.md)(PR #112)가 남긴 후속 넷(#113~#116)을 한 PR로 묶는다(사용자 결정). 배포 환경이 없고 따로 띄울 계획도 없다(2026-10-23 사용자 결정). 그래서 로컬 compose(`docker/docker-compose.yaml`, lgtm의 Prometheus·Grafana)를 유일한 실행 환경으로 본다. #113의 선행 조건 "배포 형태 결정"과 #115의 선행 조건 "운영 배포와 metric 수집"은 이 결정으로 대체한다. 운영 경로가 없어 할 수 없는 일은 제외·한계 절에 적는다.

기대 결과:
- 울린 알림이 severity별 수신자로 간다. critical은 `page`, warning은 `ticket`이다.
- 오류 예산을 천천히 쓰는 상황(3일 창)이 ticket으로 울린다.
- 트래픽이 없어도 앱이 응답하지 못하거나 DB가 내려가면 probe 알림이 운다.
- 단말이 재연결한 뒤 60초 안에 확정되지 않은 sync 항목의 비율을 재고, 그 비율이 G-03(99.99%) 예산을 빠르게 쓰면 ticket으로 운다.
- 시연 단말의 sync timeout이 부하 때의 배치 시간보다 길다.
- burn rate 알림은 실패 2건으로는 울지 않고, 트래픽이 적어도 실패가 이어지면 운다.
- runbook의 진단 조회가 같은 저장소에 올라간 다른 앱의 같은 metric과 섞이지 않는다.
- 시작값 가운데 이번에 다시 볼 조건이 찬 값의 판정이 이 spec에 남는다.

## 범위
소유 모듈은 `sync`(수렴 metric과 sync 요청 계약)와 `platform`(알림 규칙과 그 테스트)이다. 업무 판정(순번·멱등·거절)은 바뀌지 않는다. 바뀌는 것은 다음과 같다.
- 알림: 규칙 파일(`docker/prometheus/alerts.yaml`)과 promtool 시나리오, Prometheus 설정, 새 Alertmanager 설정, compose, Alloy 설정, `AlertRulesTest`.
- sync: 요청 본문의 선택 필드 `reconnectedAt`, 수렴 metric 계산, 형식 오류 counter, REST Docs.
- 시연 단말(`src/main/resources/static/terminal/index.html`): 재연결 시각 규칙과 timeout.
- k6(`load-test/`): timeout 인자와 `reconnectedAt`.
- wiki: runbook, 관측성, 용량 기준, 도메인 모델.

### 결정

#### 발송 경로·느린 소진·probe(#113)

**1. Alertmanager를 compose obs profile에 더하고 수신자 둘로 라우팅한다(사용자 결정).**
- `grafana/otel-lgtm` 이미지에는 Alertmanager가 없다. `prom/alertmanager`(버전 고정)를 obs profile에 두고, compose가 이미 이미지 설정 대신 싣는 `docker/prometheus/prometheus.yaml`에 `alerting.alertmanagers`로 붙인다. 설정은 `docker/alertmanager/alertmanager.yaml`이다.
- 라우팅: `severity="critical"`은 `page`로, 그 밖은 기본 수신자 `ticket`으로 간다. 묶음 기준은 `alertname`이다.
- 수신자는 연동 설정 없이 이름만 둔다. 로컬에는 호출하거나 티켓을 만들 곳이 없다. 실제 발송(HTTP 전달, 반복 간격)은 확인하지 않는다(한계). 받을 곳이 생기면 그 수신자에 연동을 더한다.
- 라우팅은 `AlertRulesTest`에서 `amtool check-config`와 `amtool config routes test`로 고정하고, compose에서는 Alertmanager API의 수신자로 확인한다.
- 억제 규칙(inhibit)은 두지 않는다. 앱이 내려가면 probe 알림과 `ResilientPosMetricsAbsent`가 함께 운다.
- 버린 것: 로컬 webhook echo 컨테이너(운영 의미가 없는 컨테이너와 이미지 고정 관리가 늘어난다), Slack 같은 외부 채널(Alertmanager가 환경 변수를 읽지 못해 비밀 파일 마운트가 필요하고, 채널 준비가 먼저다).

**2. 느린 소진 ticket 규칙 `HttpErrorBudgetSlowBurn`(warning)을 더한다.**
- Workbook "Alerting on SLOs"의 ticket 권장값이다. 3일·6시간 창이 모두 배수 1(실패율 0.1%, 30일 예산의 10%를 3일에 씀)을 넘으면 운다. 분자와 분모는 `HttpErrorBudgetBurn`과 같다(`/v1/**`의 5xx, 429를 뺀 요청).
- 3일 창을 원시 series로 평가마다 읽지 않도록 recording rule 둘을 둔다. `/v1` 5xx의 5분 rate 합과 429를 뺀 `/v1` 요청의 5분 rate 합이다. 3일 비율은 두 기록의 3일 `avg_over_time` 비다. 6시간 창은 기존 규칙처럼 원시 series로 읽는다.
- recording rule은 이 규칙이 쓰는 둘만 둔다. 기존 빠른 소진 규칙은 원시 식 그대로다. #112 제외 절의 다시 볼 조건("같은 비율 식을 쓰는 규칙이나 대시보드가 셋을 넘을 때")에 이르지 않았다.
- 하한은 결정 9다.

**3. synthetic probe는 Alloy 내장 blackbox로 health를 부르고, 알림은 `HealthProbeFailing`(critical)이다(사용자 결정).**
- Alloy의 `prometheus.exporter.blackbox`가 `http://app:8080/actuator/health`를 15초마다 부르고, 결과를 앱 metric과 같은 경로(remote_write)로 넣는다. 컨테이너는 늘지 않는다. 2xx만 성공이다. probe series는 고정 label로 다른 series와 가른다.
- health는 DB를 보고 Redis는 보지 않는다(`application.yaml`, [관측성](../wiki/observability.md) 점주 세션 저장소 항목). 그래서 probe는 트래픽이 없을 때의 앱 멈춤·응답 없음·DB 장애를 잡는다. #112 한계 절의 "영업 외 시간의 전체 장애"를 덮는 수단이다.
- 규칙: 1분 창의 probe가 모두 실패한 상태(`max_over_time(probe_success[1m]) == 0`)가 `for: 1m` 이어지면 운다. 평가 주기 1분에서 탐지까지 약 2~3분이 걸린다. 한 번의 timeout(GC, 호스트 부하)에는 울지 않는다.
- probe는 데이터를 쓰지 않고 `/actuator` 경로라 `/v1` SLI에 섞이지 않는다.
- `probe_success`는 앱이 내는 metric이 아니다. 규칙의 metric 이름을 앱 출력과 대조하는 `ObservabilityConfigurationTest`에 앱 밖에서 만드는 이름으로 이유와 함께 더한다(범위 조정, 2026-10-23).
- 버린 것: 합성 단말의 실제 sync(주문이 DB에 쌓이고 probe 트래픽이 SLI에 섞이며 단말 자격 준비가 필요하다), 단말 인증 읽기 요청(자격과 주문 준비가 필요하고 `/v1` 오류율 분모에 들어간다).
- 치르는 것: 주문 API 경로(인증, 도메인 로직) 자체의 고장은 probe가 보지 않는다. 그것은 오류율·지연 규칙의 몫이고, 트래픽이 있어야 판정한다.

#### 재연결 기준 수렴과 G-03(#114)

**4. 단말이 배치에 재연결 시각 `reconnectedAt`을 싣는다(사용자 결정).**
- 요청 본문 최상위의 선택 필드다. 형식은 항목의 `createdAt`과 같은 ISO-8601 offset 시각이다. 저장하지 않고 payload hash에도 넣지 않으며 수렴 metric에만 쓴다. 그래서 스키마와 sync 판정은 바뀌지 않는다.
- 단말 규칙: 오프라인 표시(브라우저의 오프라인, 강제 오프라인)나 fetch 네트워크 오류가 나면 "끊김"으로 둔다. 끊긴 동안에는 요청마다 자기 전송 시각을 `reconnectedAt`으로 싣는다. HTTP 응답(상태 무관)이나 timeout을 받으면 그 값을 고정하고 끊김을 푼다. 서버에 닿은 요청은 곧 네트워크가 살아난 시각을 싣고 있는 셈이다. 페이지를 연 직후는 끊김으로 시작한다.
- 그래서 매장 네트워크의 단절은 빠지고(브라우저가 온라인으로 아는 동안의 단절 포함), 5xx·timeout·429 같은 서버 쪽 지연만 들어간다.
- 시연 단말은 지금 `createdAt`을 싣지 않는다([observability-alerts-tracing](2026-10-21-observability-alerts-tracing.md) 제외 절). 서버는 `createdAt`이 없으면 기록하지 않아 `reconnectedAt`만으로는 효과가 없으므로, 시연 단말이 operation마다 대기열에 넣은 시각을 `createdAt`으로 싣는다(범위 조정, 2026-10-23).
- 치르는 것: 패킷을 버리는 단절로 timeout이 나면 서버 책임으로 잡힌다. 단말 시계를 믿는다(`createdAt`과 같은 전제). 시계가 N초 뒤처지면 값이 N초 길어지고, N초 앞서면 N초보다 짧은 항목은 차이가 음수라 기록되지 않는다. 재연결 직후 항목은 경과가 짧아 이 일이 전보다 흔하다.
- 버린 것: 서버가 단말별 수신 공백으로 추정(heartbeat가 없어 쉬는 단말과 끊긴 단말을 가르지 못하고, 서버에 닿기 전에 실패한 재시도 시간을 놓친다), 단말이 수렴 시간을 직접 보고(서버 판정이 아니라 구현마다 정의가 갈리고, 마지막 배치의 값은 다음 배치가 와야 들어온다), 단말 시계로만 경과를 계산(필드가 둘이 되고 요청 전송 시간이 빠진다).

**5. `sync_convergence_seconds`의 정의를 바꾼다(사용자 결정).**
- 처음 확정된 항목(`APPLIED`·`REJECTED`)마다 서버 시각에서 `createdAt`과 `reconnectedAt` 가운데 늦은 쪽을 뺀 값을 기록한다. 재연결 뒤에 만든 항목은 만든 시각부터 잰다.
- `createdAt`이 없으면 지금처럼 기록하지 않는다. `reconnectedAt`이 없으면 지금처럼 `createdAt`부터 잰다. 차이가 음수면 기록하지 않는다(지금과 같다).
- `reconnectedAt`의 형식이 틀리면 배치를 거절하지 않고 없는 것으로 다룬다. 대신 counter `sync_reconnected_at_invalid_total`을 그 배치에서 한 번 센다(기동 때 0으로 등록). `createdAt`과 같은 처리다. metric용 필드 때문에 업무 요청을 거절하지 않는다.
- bucket(1s·10s·1m·5m·15m·1h·6h·24h)은 그대로다. 1m이 G-03의 경계다.
- 치르는 것: 매장 단절 길이의 분포는 더 보이지 않는다. 필드를 보내지 않는 클라이언트의 값은 `createdAt`부터라 길게 잡힌 값이 섞인다. 서버가 확정한 시각까지라 단말이 응답을 받은 시각은 아니다(한계).
- 버린 것: 새 timer를 따로 더하기(series가 늘고, 단절 길이 metric을 쓰는 곳이 없다).

**6. G-03 알림 `SyncConvergenceBudgetBurn`(warning)을 더한다(사용자 결정).**
- SLI는 재연결 뒤 60초를 넘겨 확정된 항목의 비율(1에서 60초 bucket ÷ 전체를 뺀 값)이다. SLO는 G-03의 99.99%(예산 0.01%, 기간 30일)다.
- `HttpErrorBudgetBurn`과 같은 두 쌍이다. 1시간과 5분 창이 모두 14.4배(0.144%)를 넘거나 6시간과 30분 창이 모두 6배(0.06%)를 넘으면 운다. 하한은 결정 9다.
- warning(ticket)이다. 확정 시점에 기록하는 후행 지표라 서버 장애 중에는 값이 비고, 장애가 끝난 뒤에 운다. 장애 자체는 오류율·지연·probe 알림이 page한다. 이 규칙만 잡는 것은 서버가 5xx 없이 수렴을 늦추는 경우(429 거절, 409 뒤 재시도)다.
- 버린 것: critical(장애 뒤 회복 중에 `HttpErrorBudgetBurn`과 거의 늘 겹쳐 두 번 호출한다), 알림 없이 조회만 두기(429·409로 수렴이 늦어져도 아무 알림도 울지 않는다).

**7. 시연 단말의 sync 계약은 배치 50, timeout 30초, 실패 뒤 5초 재시도다(사용자 결정).**
- timeout만 5초에서 30초로 늘린다. S3(단말 100대 × 500 op, 39.9초, op당 약 80ms)를 환산하면 배치 50은 같은 부하에서 약 4초라 5초 timeout에 바로 걸린다. 단말이 끊고 다시 보내도 서버는 앞 배치를 계속 처리한다. #117(2026-10-23 머지)부터 같은 pod로 간 재전송은 앞 배치가 끝날 때까지 429를 받는다. 결국 5초 timeout은 서버 처리를 앞당기지 못하고 단말이 확정 응답을 받는 시점만 늦춘다.
- 30초는 환산한 배치 시간의 7배가 넘고, 조용히 끊긴 연결을 붙잡는 시간이 G-03 60초의 절반 아래다. 환산값이라 S3 변형으로 확인한다(결정 8).
- 배치 50과 5초 재시도는 그대로다. 5초는 429의 `Retry-After`(1초)보다 길어 #87 재시도 규약 안이다. 500 op backlog는 배치 10개라 단말 bucket capacity(10)에 들어 빈도 상한에 걸리지 않는다.
- 시연 단말이 이 재시도를 지키도록 고친다(리뷰 반영, 2026-10-23). 지금은 요청 중에 5초 주기가 남긴 재전송 요청 때문에 timeout 직후 바로 다시 보내고, 빨리 끝난 실패 뒤에는 다음 주기까지 0~5초만 기다린다. 실패하면 5초 뒤에 다시 보내고 그 전에는 보내지 않는다.
- 버린 것: timeout 60초(k6의 응답 대기와 같지만 끊긴 연결 하나가 G-03 창 전체를 쓸 수 있다), 지금 계약 유지(부하 때 단말이 확정 응답을 늦게 받고 429 재전송이 는다).

#### 시작값 재판정(#115)

**8. 이번 PR에서 다시 볼 조건이 찬 값만, 계산과 S3 변형 두 번으로 판정한다(사용자 결정).**
- 운영 분포가 없으므로 "운영 첫 주"로는 다시 판정하지 않는다. 이슈가 든 나머지 시작값(작업 생존 10분, `LockFailOpen`의 `for` 10분, `OwnerSessionStoreUnavailable` 2분·5분, consumer lag 1000)은 유지한다. 그 다시 볼 조건 가운데 운영 분포에 기댄 것은 로컬에서 관찰할 수 있는 조건으로 고쳐 결과 절의 판정 표에 적는다.
- `SyncGapPersistent`(다시 볼 조건: 단말 재전송 주기가 정해질 때): 재전송 주기가 5초로 정해졌다. 창 5분에는 재전송이 60번 들어가므로, 다음 재전송에 풀리는 잠깐의 gap과 5초마다 GAP을 내는 멈춘 단말을 가른다. `for: 30m`은 감지 지연이라 계약에서 답이 나오지 않는다([slo-alert-baseline](2026-10-23-slo-alert-baseline.md) 결정 7).
- `HttpLatencyAboveTarget`의 `for: 10m`(다시 볼 조건: 단말 sync timeout·재전송 계약이 정해질 때): 최악 backlog(1,800 op)를 가진 단말 100대가 S3 처리량(약 1,250 op/s)으로 빠지는 데 약 2.4분이 걸린다. 창 5분을 더해도 7.4분이라 10분 안이다.
- S3 변형 두 번(배치 50, timeout 30초, 단말 100대): 500 op(G-03의 트래픽 가정)로는 G-03 60초와 배치 시간 환산값(약 4초)을, 1,800 op(빈도 상한의 최악)로는 지연 규칙 `for: 10m`의 계산과 timeout 30초를 확인한다. 두 실행에서 `sync_convergence_seconds`의 60초 비율과 새 알림·지연 알림의 상태를 함께 본다.
- 판정이 바뀐 값은 규칙 파일의 주석도 판정대로 고친다([slo-alert-baseline](2026-10-23-slo-alert-baseline.md) 결정 7의 관례, 범위 조정 2026-10-23). 근거가 생긴 값은 근거를 적고, 다시 볼 조건을 로컬에서 관찰할 수 있는 조건으로 바꾼 값은 그 조건을 적는다.
- 1,800 op는 G-03 판정에 쓰지 않는다. 100대 × 1,800 op는 계산상 약 144초가 걸려 G-03 가정(500 op) 밖이다.
- S3는 실패한 배치를 시연 단말처럼 5초 뒤 다시 보낸다(범위 조정, 2026-10-23). 지금 S3는 바로 다시 보내서, timeout 뒤의 재전송이 앞 배치가 도는 동안 #117의 429를 연달아 받고 5번 만에 수렴하지 못한 것으로 세어진다. 실패가 없던 지금까지의 S3 결과는 바뀌지 않는다.

**9. burn rate 규칙의 하한을 요청 수에서 "긴 창의 실패 3건"으로 바꾼다(사용자 결정).**
- 지금 하한(1시간 70건, 6시간 167건)은 "실패 한 건으로는 울지 않는다"만 막는다. 시간당 70~138건이면 실패 2건으로 critical이 울고, 시간당 28건 미만이면 요청이 전부 실패해도 울지 않는다.
- 긴 창의 실패가 3건 이상일 때만 그 쌍을 판정한다. 실패 2건까지는 울지 않고, 트래픽이 아무리 적어도 실패가 3건 이상이고 비율이 배수를 넘으면 운다. Workbook 저트래픽 절의 최소 실패 건수 방식이다.
- 적용 대상: `HttpErrorBudgetBurn` 두 쌍, `HttpErrorBudgetSlowBurn`(3일 창), `SyncConvergenceBudgetBurn` 두 쌍(60초를 넘긴 항목 3건).
- 3일 창의 실패 수는 1분마다 남긴 기록(5분 rate)을 더해 60을 곱해 어림하고, 문턱은 2.3이다. scrape 15초에서 실패 한 건이 0.84~1.05로 잡혀 2건은 2.1 아래, 3건은 2.5 위다. 기록이 3일보다 짧거나 Prometheus가 멈춘 구간은 그만큼 덜 센다. 처음에는 평균 rate에 3일을 곱하고 문턱을 2.5로 두었으나, 기록이 짧으면 실패 1건이 몇 건으로 부풀어 바꿨다(리뷰 반영, 2026-10-23).
- 치르는 것: 3이라는 값에는 측정 근거가 없다. 밤에 20건 가운데 3건이 실패해도 critical이 운다.

**10. 오류 예산 기간은 30일로 확정한다(사용자 결정).** Workbook 기본값이고, 배수 14.4·6·1이 30일에서 나온 값이며, 운영 환경이 없어 다른 값을 고를 근거가 없다. 원문에 없는 값이라는 사실은 남는다. 28일(4주)은 배수와 시나리오 경계를 모두 다시 계산해야 해서 버렸다.

#### 문서 정리(#116)

**11. runbook의 진단 조회와 문구를 고친다.**
- runbook의 진단 PromQL에 `application="ResilientPOS"`를 일괄로 붙인다. 같은 Mimir tenant에 여러 앱이 올라가면 같은 metric이 합산된다. 규칙 식에는 이미 있다.
- `LockFailOpen` 절의 "`OwnerSessionStoreUnavailable`도 함께 울릴 것이다"를 "점주 요청이 이어지면 함께 울 수 있다"로 고친다. `OwnerSessionStoreUnavailable` 절의 "점주 요청이 드물면 늦거나 울리지 않는다"와 어긋나지 않게 한다. 반대 방향(`OwnerSessionStoreUnavailable` 절의 "`LockFailOpen`도 함께 울릴 것이다")은 주기 작업이 매분 락을 잡으므로 맞아서 그대로 둔다.
- 공통 절의 "로컬은 Alertmanager가 없어 알림이 발송되지 않는다"를 결정 1에 맞게 고친다.
- 볼트 `current-state.md`의 "속도 제한(rate limit)도 없다"는 2026-10-23에 확인했을 때 이미 단말 동시·빈도 상한 문단으로 고쳐져 있었다. 이 PR이 할 일은 없다.
- 알림 summary에 `$value`를 넣지 않는다(사용자 결정). `HttpErrorBudgetBurn`의 `$value`는 갈래에 따라 1시간 비율이었다가 6시간 비율이 되고, promtool의 `exp_annotations`는 서식된 값까지 정확히 맞아야 한다. 값은 runbook 진단 조회로 본다.

## 제외
- ingress·LB의 5xx·timeout 지표. 로컬에는 ingress·LB가 없다.
- 클라이언트가 먼저 끊은 요청의 서버 쪽 집계. probe와 G-03 수렴 metric이 일부를 덮는다.
- 실제 호출·티켓 연동(결정 1). 수신자는 이름만 있다.
- 억제 규칙(결정 1).
- G-06(정의한 Sev-1/2 시나리오의 95%를 60초 안에 탐지, 사용자 결정). probe를 맞추려면 평가 주기를 15초로 바꿔야 하고, 기존 critical 규칙(`ResilientPosMetricsAbsent`·`OwnerSessionStoreUnavailable`의 `for: 5m`)도 만족하지 못해 critical 규칙 전체를 검토하는 일이다.
- 주문 API 경로를 끝에서 끝까지 확인하는 synthetic(결정 3).
- 운영 분포로 하는 재판정과, 결정 8 밖의 시작값을 위한 추가 측정.
- 재전송 선택 건수 알림(#112 결정 6 그대로).
- Grafana에 Alertmanager 데이터소스를 붙이는 것. 알림은 Alertmanager API와 Prometheus에서 본다.

## 한계
- 수신자에 연동이 없어 알림이 사람에게 가지 않는다. 라우팅만 확인한다.
- lgtm 컨테이너에는 볼륨이 없어 다시 만들면 metric이 사라진다. 3일 창은 스택이 3일 넘게 떠 있어야 찬다. 느린 소진 규칙의 동작은 promtool 시나리오로만 확인한다.
- probe는 health만 본다. 주문 API 경로의 고장과 Redis 장애는 보지 않는다(Redis 장애의 주 신호는 `LockFailOpen`이다).
- 수렴 metric은 서버가 확정한 시각까지라, 단말이 응답을 받은 시각은 아니다. 단말 시계가 앞서면 짧은 항목이 빠지고, 60초 넘게 뒤처진 단말 한 대로도 G-03 알림이 울 수 있다(결정 4).
- 서버가 연결을 거부하는 장애(앱 정지 등)는 단말이 단절로 보고 복구 뒤 시각부터 재므로 G-03 알림에 잡히지 않는다. 그 장애는 probe 알림이 잡는다.
- 느린 소진의 3일 비율은 기록이 3일보다 짧으면 있는 기록만의 비율이다.
- 단말 bucket capacity(10)의 근거는 배치 500 기준이다. 단말 sync 계약(배치 50)으로는 1,800 op가 요청 36개라, 서버가 한가하면 10개 남짓 뒤 429를 받고 5초 뒤 이어 보낸다(계산, 미측정). 값을 바꾸는 일은 이 PR 밖이다.
- 시연 단말 selftest는 순수 함수만 확인한다. `flush`의 재시도 간격과 `postSync`의 배선은 확인하지 않는다.
- 패킷을 버리는 단절로 생긴 timeout은 서버 책임으로 잡힌다(결정 4).
- 실패 3건이라는 하한, `SyncGapPersistent`의 `for: 30m`, probe의 `for: 1m`에는 측정 근거가 없다.
- S3 변형은 로컬 한 대의 상대 측정이다(#77과 같은 전제). #117의 동시 상한 위에서 재고, pod가 하나라 재전송 겹침은 모두 429가 된다. pod가 여럿이면 다른 pod로 간 재전송은 겹친다.
- 5xx series는 그 status·uri의 첫 실패 때 1로 생겨, `increase`가 첫 건의 일부를 놓칠 수 있다. 처음 나는 실패 묶음은 3건보다 조금 많아야 하한을 넘는다.

## 관련 문서
없음

## 변경 경로
- docs/spec/2026-10-23-slo-alert-followups.md
- docker/docker-compose.yaml
- docker/prometheus/**
- docker/alertmanager/**
- docker/alloy/config.alloy
- src/main/kotlin/com/resilientpos/sync/**
- src/test/kotlin/com/resilientpos/sync/**
- src/test/kotlin/com/resilientpos/platform/AlertRulesTest.kt
- src/test/kotlin/com/resilientpos/platform/ObservabilityConfigurationTest.kt
- src/main/resources/static/terminal/index.html
- load-test/lib/api.js
- load-test/s3-sync-batch.js
- load-test/README.md
- docs/wiki/runbook.md
- docs/wiki/observability.md
- docs/wiki/capacity.md
- docs/wiki/domain-model.md

## 인수 조건
1. 알림이 severity별로 간다. `amtool config routes test`가 critical을 `page`로, warning을 `ticket`으로 보내는 것을 `AlertRulesTest`에서 확인한다. compose에서 critical 하나(probe)와 warning 하나가 각 수신자에 도착한 것을 Alertmanager API로 확인해 결과 절에 남긴다.
2. `/v1/**`의 5xx가 3일과 6시간 창에서 모두 0.1%를 넘고 3일 창의 실패가 3건 이상이면 `HttpErrorBudgetSlowBurn`(warning)이 울리고, 6시간 창이 0.1% 아래로 내려가면 풀린다. 0.1% 바로 위아래와 실패 2·3건으로 판별한다. promtool 시나리오로 확인한다.
3. health probe가 1분 넘게 모두 실패하고 그 상태가 1분 이어지면 `HealthProbeFailing`(critical)이 울린다. 한 번의 실패에는 울지 않는다. promtool 시나리오로 확인하고, compose에서 DB를 멈춰 firing과 해소를 확인해 결과 절에 남긴다.
4. sync 배치에 `reconnectedAt`이 있으면 처음 확정된 항목의 수렴 시간을 `createdAt`과 `reconnectedAt` 가운데 늦은 쪽부터 잰다. 없으면 `createdAt`부터 잰다. 형식이 틀리면 배치는 정상 처리되고 `createdAt`부터 재며 `sync_reconnected_at_invalid_total`이 1 오른다. 통합 테스트로 확인하고 REST Docs에 필드를 적는다.
5. 재연결 뒤 60초를 넘겨 확정된 항목의 비율이 1시간·5분 창에서 모두 0.144%를 넘거나 6시간·30분 창에서 모두 0.06%를 넘고, 긴 창에서 60초를 넘긴 항목이 3건 이상이면 `SyncConvergenceBudgetBurn`(warning)이 울린다. 60초 이하 항목만 있거나 넘긴 항목이 2건이면 울지 않는다. promtool 시나리오로 확인한다.
6. `HttpErrorBudgetBurn`은 긴 창의 실패가 2건이면 울지 않고, 3건이며 비율이 배수를 넘으면 시간당 요청이 28건보다 적어도 운다. 기존 시나리오의 요청 수 하한 경계를 실패 수 경계로 바꾼다.
7. 시연 단말은 operation마다 `createdAt`을 싣고, 끊긴 뒤 서버에 닿은 첫 요청의 전송 시각을 `reconnectedAt`으로 싣고, 응답이나 timeout을 받은 뒤에는 그 값을 유지하며, sync timeout이 30초다. 페이지 selftest가 이 규칙을 확인한다.
8. S3 변형 두 번(배치 50, timeout 30초, 단말 100대, 500 op와 1,800 op)의 결과가 결과 절에 남는다. 수렴 시간, timeout 수, sync p95, `sync_convergence_seconds`의 60초 비율, 알림 상태를 적고 결정 7·8의 판정을 내린다.
9. 결정 8의 판정이 결과 절의 시작값 판정 표에 남는다.
10. 새 규칙과 바뀐 규칙의 시나리오는 규칙을 고치기 전에 promtool에서 실패한다(TDD 검증 기록).
11. `AlertRulesTest`가 새 규칙 수로 통과하고, 새 알림마다 runbook에 같은 이름 절이 있다.
12. runbook의 진단 PromQL이 모두 `application="ResilientPOS"`로 거르고, `LockFailOpen`·공통 절의 문구가 결정 11대로 바뀐다.

## 검증
- promtool 직접: `docker run --rm -v "$PWD/docker/prometheus:/rules:ro" --entrypoint promtool prom/prometheus:v3.15.0 test rules /rules/alerts.test.yaml`
- amtool 직접: Alertmanager 이미지로 `amtool check-config`와 `amtool config routes test --config.file=... severity=critical`(기대 `page`), `severity=warning`(기대 `ticket`).
- `./gradlew test --tests 'com.resilientpos.platform.AlertRulesTest' --tests 'com.resilientpos.sync.*'`, 그리고 `./gradlew test` 전체.
- `python3 scripts/check_pr.py --base d850d3f --worktree`, 제출 전에는 `--worktree` 없이 한 번 더.
- 단말 selftest: 시연 페이지에서 selftest를 실행해 "selftest 통과"를 확인한다.
- compose 실측: 공유 스택(`name: resilientpos`)을 다른 세션이 쓰는지 `docker ps`와 그 세션 상태로 확인한 뒤 띄운다. 다른 세션이 측정 중이면 기다리거나 사용자에게 묻는다. PostgreSQL 정지는 공유 인프라라 사용자 확인을 받고 한다.
  - 발송: probe 알림(PostgreSQL 정지)이 `page`로, warning 하나가 `ticket`으로 가는 것을 Alertmanager `/api/v2/alerts`의 수신자로 본다.
  - S3 변형: `k6 run -e TERMINALS=100 -e BACKLOG_SALES=250 -e BATCH=50 -e TIMEOUT=30s load-test/s3-sync-batch.js`와 `BACKLOG_SALES=900`.

## Wiki 영향
- [runbook](../wiki/runbook.md): `HttpErrorBudgetSlowBurn`·`HealthProbeFailing`·`SyncConvergenceBudgetBurn` 절을 더한다. `HttpErrorBudgetBurn` 절의 하한 설명을 실패 3건으로 고친다. 진단 PromQL에 `application` 필터를 붙이고, `LockFailOpen`과 공통 절의 문구를 고친다(결정 11).
- [관측성](../wiki/observability.md): sync 항목의 수렴 시간 정의와 `reconnectedAt`·형식 오류 counter, probe와 Alertmanager, 규칙 수를 고친다. "아직 없는 것"에서 수렴 시간 알림·느린 소진·synthetic probe·Alertmanager를 지우고 남는 것(edge 지표, 실제 연동, G-06)을 적는다.
- [용량 기준](../wiki/capacity.md): 트래픽 가정 아래에 단말 sync 계약(배치 50, timeout 30초, 재시도 5초)과 S3 변형 결과를 적고, "sync 동시 실행 상한" 행의 "단말 timeout이 배치 시간보다 짧은 동안 성립하지 않는다"를 측정대로 고친다. `sync MAX_OPERATIONS` 행의 "단말 sync 계약 작업에서 본다"도 고친다.
- [도메인 모델](../wiki/domain-model.md): sync 요청의 선택 필드 설명에 `reconnectedAt`(저장하지 않고 컬럼을 두지 않는다)을 더한다.
- 요구사항 영향: 업무 요구사항은 바뀌지 않는다. 원문 G-03을 재는 metric과 알림을 더하고, 시연 단말과 k6의 sync timeout을 바꾼다. sync 판정·스키마·이벤트 계약은 무영향이다.

## 분할 계획
- 개발 컨텍스트는 "#112 SLO 알림의 후속(발송, 느린 소진, probe, 재연결 기준 수렴, 시작값, 문서)" 하나다. 네 이슈를 묶은 것은 사용자 결정이다.
- 따로 둔 것: G-06 검토, 실제 호출·티켓 연동(제외).
- 겹침 확인(2026-10-23, origin/main 5e5721a): 열린 PR은 #117(`feature/terminal-concurrency-limit`)뿐이다. 겹치는 경로는 `docs/wiki/observability.md`, `docs/wiki/capacity.md`, `load-test/README.md`다. 규칙 파일, sync 코드, `load-test/lib/api.js`·`s3-sync-batch.js`, runbook은 겹치지 않는다. 먼저 머지된 쪽 위로 rebase하고, push·PR 생성·머지 직전에 다시 확인한다.
- 다시 확인(2026-10-23): #117이 `d850d3f`로 머지됐고 열린 PR은 없다. 그 위로 rebase하고 PR 기준을 `d850d3f`로 바꾼다. 측정도 #117 위에서 한다.
- #117과의 관계: 그 PR의 동시 거절도 429라 결정 9의 오류율 정의(429 제외)와 맞는다. 결정 7의 timeout 근거(같은 pod 재전송은 429)는 그 PR의 동작을 전제로 하지만, timeout을 늘리는 판단은 그 PR 없이도 성립한다(서버는 끊긴 배치를 계속 처리한다).

## TDD 검증 기록
sync의 수렴 metric과 요청 계약은 업무 동작 변경이라 통합 테스트·REST Docs를 먼저 두고 실패를 본다. 알림 규칙은 promtool 시나리오를 먼저 두고 실패를 본 뒤 고친다. Alertmanager 라우팅은 amtool 검사를 먼저 둔다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| reconnectedAt이 수렴 시간의 기준점을 늦춘다(인수 조건 4), `SyncCommandFacadeIntegrationTest`의 `reconnectedAt이 createdAt보다 늦으면 reconnectedAt부터 확정까지를 잰다` 외 2개 | `./gradlew test --tests 'com.resilientpos.sync.internal.application.SyncCommandFacadeIntegrationTest'`. 새 7개 가운데 이 동작 1개 실패(1,800초 전 createdAt부터 잼). `createdAt이 reconnectedAt보다 늦으면 createdAt부터 잰다`와 `createdAt이 없으면 기록하지 않는다`는 지금 동작을 고정하는 characterization이라 통과 | `./gradlew test --tests 'com.resilientpos.sync.*' --tests 'com.resilientpos.FixtureFactoriesTest'`. 100개 통과 | 리팩터링 없음. rebase 뒤 전체 `./gradlew test`는 결과 절 |
| 형식이 틀린 reconnectedAt은 무시하고 invalid를 센다(인수 조건 4), 같은 클래스의 counter 테스트 4개 | 같은 명령. 4개 실패(`sync.reconnected-at.invalid` counter가 없음) | 같은 명령. 통과 | 리팩터링 없음 |
| REST Docs의 reconnectedAt 필드(인수 조건 4), `SyncControllerTest`의 `createdAt이 있는 CREATE_ORDER와 REQUEST_CHECKOUT은 원문 문자열 그대로 Facade에 전달되고 요청 필드에 문서화된다` | `./gradlew test --tests 'com.resilientpos.sync.internal.adapter.web.SyncControllerTest'`. 12개 중 1개 실패(요청이 reconnectedAt을 Facade에 넘기지 않아 mock 기대와 다름) | 위 sync 명령. 통과, 스니펫 `sync-receive-batch-created-at`에 필드 추가 | 리팩터링 없음 |
| 느린 소진 시나리오(인수 조건 2) | promtool `test rules /rules/alerts.test.yaml`(검증 절의 명령). 규칙이 없어 양성 3곳(3일, 3일 10분, 실패 3건) 실패. 음성 시나리오는 규칙이 없어도 통과하므로 아래 결함 주입으로 판별력을 본다 | 같은 명령. `SUCCESS` | 규칙에 결함을 하나씩 넣어(배수 0.9, 실패 수 하한 제거, 6시간 조건 제거, 기록의 분모에 429, 분자에 `/v1` 밖) 모두 실패를 확인. 처음 판별하지 못한 둘(분자 오염, 배수 0.9)은 0.09% 음성 시나리오를 "6시간 0.2%·3일 0.095%"로 바꿔 잡았다 |
| probe 시나리오(인수 조건 3) | 같은 명령. 7분의 firing 기대 실패 | 같은 명령. `SUCCESS` | 결함 주입(`max_over_time`→`min_over_time`, `for` 제거) 모두 실패 확인 |
| G-03 시나리오(인수 조건 5) | 같은 명령. 양성 5곳(30분, 85분, 0.15%, 0.065%, 3건) 실패 | 같은 명령. `SUCCESS` | 결함 주입(10초·300초 bucket, 예산 0.1%, 1시간 배수 13, 6시간 배수 5, 하한 제거, 30분 창 제거) 모두 실패 확인 |
| 오류율 실패 하한 시나리오(인수 조건 6) | 같은 명령. 3곳 실패(1시간 2건이 울림, 시간당 18건의 3건이 울지 않음, 6시간 2건이 울림). 6시간 3건 시나리오는 옛 하한(167건)에서도 울어 통과 | 같은 명령. `SUCCESS` | 결함 주입(1시간·6시간 하한 제거) 모두 실패 확인 |
| Alertmanager 라우팅과 규칙 수(인수 조건 1·11), `AlertRulesTest` | 실패를 먼저 보지 않았다. 설정과 규칙을 쓴 뒤 테스트를 고쳤다. 첫 실행에서 runbook에 새 절이 없어 `규칙의 runbook_url은 runbook의 같은 이름 절을 가리킨다`가 실패했다. amtool 판별력은 틀린 기대(`severity=warning`을 `page`로)가 exit 1로 끝나는 것으로 확인 | `./gradlew test --tests 'com.resilientpos.platform.AlertRulesTest' --tests 'com.resilientpos.platform.ObservabilityConfigurationTest'`. 8개 통과(`SUCCESS: 23 rules found`) | 리팩터링 없음 |
| 시연 단말의 createdAt과 재연결 시각(인수 조건 7), 페이지 selftest | 헤드리스 Chrome으로 `index.html?selftest`. 제목이 "selftest 통과"가 아니고 콘솔에 `selftest 실패: createdAt`, `stampLink is not defined` | 같은 명령. 제목 "selftest 통과" | 리팩터링 없음 |
| 규칙 metric 이름 검사의 `probe_success` 예외(결정 3 범위 조정), `ObservabilityConfigurationTest` | 실패를 먼저 보지 않았다. 앱이 내지 않는 이름이라 규칙을 더한 순간 실패하는 것이 확실해 예외를 함께 더했다 | 위 platform 명령. 통과 | 리팩터링 없음 |
| 짧은 기록의 느린 소진 실패 수(리뷰 반영, 결정 9), `기록이 3일보다 짧으면 실패를 그 기록만큼만 센다` | HEAD 규칙과 새 시나리오로 promtool 실행. 450분에 실패 1건을 9.6건으로 어림해 울어 실패 | 실패 수를 기록의 합으로 바꾸고 문턱을 2.3으로. 같은 명령 `SUCCESS` | 결함 주입(문턱 1.5·2.9) 모두 실패 확인. 1.5는 2건 시나리오, 2.9는 3건과 짧은 기록 시나리오가 잡았다 |
| G-03 1시간 쌍의 창별 판별(리뷰 반영, 인수 조건 5), `G-03 1시간 쌍은 5분 창이 깨끗하면 울지 않는다` 외 1개 | 지금 규칙이 이미 맞아 통과하는 판별 시나리오라 RED가 없다. 판별력은 결함 주입으로 본다 | promtool `SUCCESS` | 1시간 쌍의 5분 창 제거, 1시간 창 제거가 각 시나리오에서 실패 확인 |
| probe 창 길이(리뷰 반영, 인수 조건 3), `probe 창은 1분 전체를 본다` | 위와 같은 판별 시나리오 | promtool `SUCCESS` | 창 30초·45초 모두 실패 확인 |
| 60초 bucket 고정(리뷰 반영, 결정 6), `ObservabilityConfigurationTest`의 `prometheus 엔드포인트가 application 태그와 지연 histogram이 붙은 요청 metric을 돌려준다` | 수렴 timer의 60초 bucket을 61초로 잠시 바꾸자 이 테스트가 실패(5개 중 1개) | 되돌린 뒤 platform 명령으로 통과 | 리팩터링 없음 |
| 시연 단말의 실패 뒤 5초 재시도(리뷰 반영, 결정 7) | selftest가 `flush`를 덮지 않아 실패를 먼저 보지 않았다. 코드에서 timeout 직후 `U.again`으로 바로 다시 보내는 경로를 확인했다 | 헤드리스 Chrome selftest 통과(회귀 확인만) | 리팩터링 없음 |

## 결과
- 규칙: 기록 규칙 2개와 알림 3개를 더해 규칙이 23개(알림 21, 기록 2)다. 새 알림은 `HttpErrorBudgetSlowBurn`(warning), `HealthProbeFailing`(critical), `SyncConvergenceBudgetBurn`(warning)이다. burn rate 규칙의 하한은 긴 창의 실패 3건이다(결정 9). 판정이 바뀐 값은 규칙 주석도 고쳤다(결정 8).
- 발송: `prom/alertmanager:v0.34.1`을 obs profile에 더했다. critical은 `page`, 그 밖은 `ticket`으로 간다. Alloy blackbox가 `/actuator/health`를 15초마다 부른다.
- sync: 배치의 선택 필드 `reconnectedAt`과 counter `sync_reconnected_at_invalid_total`을 더했고, 수렴 시간을 `createdAt`과 `reconnectedAt` 가운데 늦은 쪽부터 잰다. 스키마와 sync 판정은 그대로다.
- 시연 단말은 operation마다 `createdAt`을 싣고, 끊긴 뒤의 요청에 `reconnectedAt`을 실으며, sync 응답을 30초 기다린다. S3는 `TIMEOUT`을 받고 실패한 배치를 5초 뒤 다시 보낸다.
- 범위 조정 넷은 모두 구현 전에 이 spec에 먼저 적었다. `ObservabilityConfigurationTest`의 앱 밖 metric 예외(결정 3), 시연 단말의 `createdAt`(결정 4), S3의 5초 재시도(결정 8), 규칙 주석 고치기(결정 8)다.
- 검증: promtool 시나리오가 `SUCCESS`이고 결함 주입으로 판별력을 확인했다(TDD 검증 기록). origin/main d850d3f(#117) 위로 rebase한 뒤 `./gradlew test` 전체 934개가 통과했다(실패 0). 그 뒤 규칙 주석을 고치고 promtool을 다시 돌렸다(`SUCCESS`). 리뷰 반영 뒤 promtool `SUCCESS`, 결함 주입 6종 모두 실패, 시연 단말 selftest 통과, `./gradlew test` 전체 934개 통과(실패 0)를 다시 확인했다.
- 리뷰: `resilientpos-multi-review`를 Claude·Codex로 돌렸다. Gemini는 CLI가 없어 미실행이고, Codex는 첫 시도가 사용량 한도로 미실행이라 한도가 풀린 뒤 다시 돌렸다. Claude 82/100, Codex는 점수 없이 발견 1건이다. 반영한 것은 다음과 같다.
  - 느린 소진의 3일 실패 수 어림이 기록이 짧으면 부푸는 결함(결정 9). 실패 수를 기록의 합으로 바꾸고, scrape 15초의 여유를 보고 문턱을 2.3으로 내렸다.
  - 시연 단말이 timeout 직후 바로 다시 보내는 결함(결정 7).
  - 판별 시나리오: G-03 1시간 쌍의 창별 시나리오 둘, probe 창 시나리오, 60초 bucket 단언.
  - 문서: 단말 시계·연결 거부·bucket capacity 한계, runbook의 "3건 이상"과 단말 시계 항목, 수치(70.5초, 요청 수), 시나리오 주석 하나.
  - S3 기본 `TIMEOUT`(60초)이 단말 계약과 다른 점(Codex). 기본값은 #77 측정과 같게 두고 README에 단말 계약 인자를 적었다.
- 반영하지 않은 것은 다음과 같다.
  - 음수 차이를 0으로 기록하거나 따로 세기: 결정 5(음수는 기록하지 않는다)를 바꾸는 일이라 한계로 적었다.
  - `reconnectedAt`이 JSON 객체·배열이면 400인 점: `createdAt`과 같은 요청 형식 오류라 그대로 둔다.
  - 미래 `reconnectedAt`과 `reconnectedAt`만 다른 재전송의 테스트: 앞은 `createdAt` 음수 테스트와 같은 분기이고, 뒤는 배치 필드라 구조상 hash에 들지 않는다.
  - probe 대상이 pod 하나인 점: 배포 환경이 없다.
  - 관측성 wiki의 "락 Timer 둘만 slo 고정 bucket" 문장: 이 PR 전부터 있던 문구라 손대지 않았다.

### compose 실측(2026-10-23)
- 환경: 공유 스택(`name: resilientpos`)은 다른 worktree가 쓰고 있어 건드리지 않았다. d71e975에서 빌드한 앱과 obs profile을 별도 프로젝트(`docker compose -p rpos-slo`)로 띄웠고, 공유 스택과 겹치는 postgres·redis·kafka의 호스트 포트는 override 파일로 닫았다. PostgreSQL 정지도 이 격리 스택에서 했다.
- 호스트: Apple M5 Pro, OrbStack, 앱 `mem_limit: 1g`, k6 v1.7.0. load average는 6~8이었다.

발송과 probe(인수 조건 1·3). Alertmanager `/api/v2/alerts`의 `receivers`로 읽었다.

| 시각(UTC) | 일 | 결과 |
|---|---|---|
| 11:13:53 | S3 1,800 op 뒤 | `SyncConvergenceBudgetBurn`(warning) firing, `ticket` 수신 |
| 11:19:21 | PostgreSQL 정지 | 트래픽 없음 |
| 11:20:53 | 평가 | `HealthProbeFailing` pending |
| 11:21:53 | 평가 | `HealthProbeFailing`(critical) firing, `page` 수신. 정지 2분 32초 뒤 |
| 11:22:04 | PostgreSQL 재시작 | |
| 11:22:53 | 평가 | `HealthProbeFailing` 풀림, Alertmanager 목록에서 빠짐 |

정지 동안 `ResilientPosMetricsAbsent`는 울지 않았다(앱은 metric을 계속 냈다). 트래픽이 없어 `HttpErrorBudgetBurn`도 울지 않았다. 트래픽 없는 장애를 probe만 잡는다는 결정 3의 전제와 맞다.

S3 변형(인수 조건 8). 배치 50, timeout 30초, 단말 100대, `BACKLOG_SALES=250`(500 op)과 `900`(1,800 op)이다. 수렴과 응답 시간은 k6 값, 60초 비율과 p95는 서버 metric이다.

| 항목 | 500 op | 1,800 op |
|---|---|---|
| 실행(UTC) | 11:06:17~11:06:42 | 11:12:04~11:13:16 |
| 단말 수렴 최소·중앙·최대 | 19.7·21.7·22.1초 | 63.3·69.4·70.5초 |
| sync 응답 p95·최대 | 3.87·4.73초 | 2.93·4.72초 |
| 실패 배치·timeout·429·GAP | 모두 0(sync 1,000건과 준비 요청 128건 모두 성공) | 모두 0(sync 3,600건과 준비 요청 128건 모두 성공) |
| 서버 sync 5분 p95 | 2.42초 | 2.98초 |
| 커넥션 대기 최대·획득 최대·대기 초과 | 93·0.81초·0 | 95·0.81초·0 |
| 60초 안에 확정된 항목 | 50,000개 모두(서버 최대 22.1초) | 180,000개 가운데 158,010개(87.8%) |
| 형식 오류 counter 둘 | 0 | 0 |
| `HttpLatencyAboveTarget` | 11:06:53 pending, 11:11:57 풀림(firing 없음) | 11:12:53 pending, 11:18:53 풀림(firing 없음) |
| `SyncConvergenceBudgetBurn` | 울지 않음 | 11:13:53 firing |

판정:
- 결정 7(timeout 30초): 두 실행의 배치 최대가 4.7초로 환산값(약 4초)에 맞았고, 30초 안이라 timeout은 0이었다. 판정이 성립한다. 다만 "5초 timeout에 바로 걸린다"는 이번 측정에서 나타나지 않았다. 최대 4.73초라 5초였어도 끊긴 배치는 없었을 것이고, 여유가 0.3초뿐이라 커넥션 대기나 호스트 부하가 조금만 늘어도 끊긴다.
- G-03(500 op): 모든 항목이 60초 안(최대 22.1초)에 확정됐고 `SyncConvergenceBudgetBurn`은 울지 않았다. 트래픽 가정(100대 × 500 op)에서 G-03을 지킨다.
- 1,800 op는 G-03 판정에 쓰지 않는다(결정 8). 그래도 기록한다. 빈도 상한의 최악 backlog를 100대가 한꺼번에 가져오면 12.2%가 60초를 넘겨 G-03을 지키지 못하고, 이 규칙이 ticket으로 알린다. 처리량이 초당 약 2,550 op로 결정 8의 계산(약 1,250 op)보다 높아 소진은 계산(약 144초)의 절반인 70.5초였다.
- 지연 `for` 10분(결정 8): 두 실행 모두 pending만 되고 풀렸다. 1,800 op의 pending은 6분(소진 71초와 창 5분)으로 계산(7.4분)보다 짧았다.

### 시작값 판정
이슈 #115가 든 값과 이 PR이 새로 둔 값이다. #112 판정 표에 있던 나머지 값은 그 표 그대로다.

| 값 | 지금 | 판정 | 근거 | 다시 볼 조건 |
|---|---|---|---|---|
| `SyncGapPersistent` 창 5분 | 유지 | 근거 생김 | 단말 재전송 주기가 5초로 정해져(결정 7) 창에 재전송이 60번 들어간다. 다음 재전송에 풀리는 gap과 계속 GAP을 내는 단말을 가른다. 두 S3 변형에서 GAP 0 | 재전송 주기가 바뀔 때 |
| `SyncGapPersistent` for 30분 | 유지 | 근거 없음 | 감지 지연이라 재전송 계약에서 답이 나오지 않는다 | GAP이 5분 넘게 이어지다 스스로 풀리는 경우가 시연 단말이나 k6에서 보일 때(그 길이로 정한다) |
| `HttpLatencyAboveTarget` for 10분 | 유지 | 근거 생김 | 최악 backlog(100대 × 1,800 op)의 pending이 6분이었고 firing 없이 풀렸다 | 동시 재연결의 소진이 5분을 넘길 때, 단말 sync 계약(배치·timeout·재시도)이 바뀔 때 |
| 작업 생존 3개, 창 10분 + for 10분 | 유지 | 근거 없음 | 측정이 스케줄러 멈춤을 다루지 않았다 | 결과 불명 조회 한 주기(`tasks_scheduled_execution_seconds{code_function="resolveUnknown"}`)가 5분에 가까워질 때. provider 지연을 주입한 로컬 실행으로 잴 수 있다(원래 조건은 실제 PG) |
| `LockFailOpen` for 10분 | 유지 | 근거 없음 | #112 판정 그대로 | 업무 락의 대기 초과가 장애 밖에서 생길 때. 원래부터 로컬 S2로 볼 수 있는 조건이다 |
| `OwnerSessionStoreUnavailable` 창 2분, for 5분 | 유지 | 근거 없음 | 측정이 점주 경로를 다루지 않았다 | 점주 화면이나 k6에 점주 요청 주기가 생길 때. 그 주기가 2분을 넘으면 장애 중에도 창이 비어 풀린다(원래 조건은 점주 트래픽 분포) |
| `KafkaConsumerLag` 1000 | 유지 | 판별만 확인 | #112 판정 그대로 | provider 지연을 바꾼 S4에서 정상 부하의 lag가 1000에 가까워질 때. 그때 건수 대신 밀린 시간으로 보는 것을 함께 본다(원래 조건은 실제 PG 지연 분포) |
| 오류 예산 기간 30일 | 확정 | 결정 10 | Workbook 기본값이고 배수가 30일에서 나왔다 | 가용성 목표나 예산 기간이 원문·사용자 결정으로 정해질 때 |
| `HttpErrorBudgetSlowBurn` 3일·6시간, 배수 1 | 새 값 | 근거 있음 | Workbook ticket 권장값(결정 2) | 예산 기간이 바뀔 때 |
| burn rate 하한 실패 3건 | 새 값 | 근거 없음 | 결정 9 | 저트래픽 실행(S1을 낮은 RATE로)에서 오탐이나 미탐이 보일 때 |
| `HealthProbeFailing` 창 1분, for 1분 | 새 값 | 근거 없음 | 감지 지연으로 고른 값. 실측 탐지는 2분 32초 | G-06을 검토할 때(제외 절) |
| `SyncConvergenceBudgetBurn` 두 쌍, 예산 0.01% | 새 값 | 근거 있음 | 원문 G-03과 Workbook 배수(결정 6). 500 op에서 울지 않고 가정 밖 1,800 op에서 울었다 | G-03의 트래픽 가정(동시 재연결 규모, backlog)이 바뀔 때 |
| 단말 sync timeout 30초 | 새 값 | 근거 생김 | 배치 최대 4.7초에 timeout 0(결정 7) | 배치 시간이 단말 timeout에 가까워질 때 |
