# PR spec: 기준선으로 HTTP 지연·오류율 SLO 알림을 더하고 알림 시작값을 다시 판정한다

## 목적
#76은 기준선이 없는 알림을 넣지 않았고, 넣은 알림의 임계 대부분을 근거 없는 시작값으로 두었다([observability-alerts-tracing](2026-10-21-observability-alerts-tracing.md) 제외·한계 절). #77(PR #109)의 k6 측정으로 기준선이 생겼다(#103).

이 PR은 원문 NFR([시스템 설계 §7](../references/02_System_Design.md#7-nfr-and-observability))을 임계로 하는 HTTP 지연·오류율 알림을 더한다. 측정값은 그 임계가 견딘 부하와 무너진 부하를 가르는지 보이는 데 쓴다. 조건부였던 알림 둘(수렴 시간, 재전송 선택 건수)은 근거가 생겼는지 판정하고, #76 시작값을 값마다 다시 판정한다. #109가 락 대기 한도를 1.5초로 줄인 영향도 `LockFailOpen`에 반영한다.

기대 결과:
- 단말 경로의 지연이 원문 목표를 10분 넘게 넘으면 warning, 오류 예산을 빠르게 쓰면 critical이 울린다.
- 영업 외 시간처럼 트래픽이 적을 때 한 건의 실패나 느린 요청으로는 울리지 않는다.
- 여러 pod에서 주기 작업 락의 대기 초과가 이어져도 `LockFailOpen`이 울리지 않는다. Redis 장애는 그대로 잡는다.
- 시작값마다 근거가 생겼는지, 여전히 없는지, 언제 다시 보는지가 이 spec의 표에 남는다.

## 범위
운영 코드는 바뀌지 않는다. 바뀌는 것은 알림 규칙(`docker/prometheus/alerts.yaml`), 그 promtool 시나리오(`alerts.test.yaml`), 규칙 수를 확인하는 `AlertRulesTest`, wiki 셋(runbook, 관측성, 용량 기준)이다.

### 결정
**1. 임계는 원문 NFR이다. 로컬 측정값은 판별 근거로만 쓴다(사용자 결정).** 로컬 한 대의 측정은 상대 비교라 운영 절대값이 아니다([k6-capacity-measurement](2026-10-21-k6-capacity-measurement.md) 제외 절). 측정값은 "견딘 단계(300 RPS)에서는 임계 아래, 무너진 단계(400 RPS)에서는 위"라는 판별을 보이는 데만 쓴다. 운영 기준선은 운영 첫 주의 분포로 다시 잡는다([용량 기준](../wiki/capacity.md#http-지연-기준선)).

**2. 지연은 단순 임계 규칙 하나다(`HttpLatencyAboveTarget`, warning, 사용자 결정).**
- 원문 목표가 p95라는 분위수라 그대로 임계가 된다. burn rate로 바꾸려면 "목표보다 느린 요청 5%"를 예산으로 삼아야 하는데 배수 상한이 20배(100% ÷ 5%)라 빠른 소진 배수(14.4)가 거의 "모든 요청이 느림"이 된다.
- 대상 uri와 목표(모두 `POST`):

| uri | 목표 | 원문 |
|---|---|---|
| `/v1/orders` | 200ms | 주문 p95 200ms 미만 |
| `/v1/orders/{orderId}/checkout` | 150ms | PG 제외 결제 처리 p95 150ms 미만. 승인은 Kafka 뒤라 이 요청은 PG를 부르지 않는다 |
| `/v1/terminals/{terminalId}/sync` | 2초 | 500 operation 배치 p95 2초 미만 |

- sync를 넣는다(사용자 결정). 트래픽 가정에서 단말 요청의 80%가 sync이고 시연 단말도 판매를 sync로 보낸다. sync uri의 p95에는 1건 배치와 500 op 배치가 섞인다. 2초는 가장 큰 배치(`MAX_OPERATIONS` 500)의 목표라, sync의 추정 p95가 2초를 넘으면 bucket 보간 오차 안에서 배치 크기와 상관없이 요청의 5% 넘게 최악 목표를 어긴 것이다. classic histogram의 분위수는 bucket 안을 고르게 보간한 추정값이라 실제 2초 초과 비율을 정확히 내지는 못한다.
- 식은 5분 창의 `histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m])))`이다. pod를 모아 본다(단말이 겪는 지연). 429는 뺀다. 빈도 상한의 거절은 처리 없이 빨리 끝나 p95를 낮춘다.
- `for: 10m`. 창 5분에 측정한 가장 긴 정상 튐(S3 단말 100대 동시 재연결 39.9초, 1분으로 올림)을 더해도 6분이라, 짧은 튐이 창을 붙잡는 동안에는 울리지 않는다. 5분보다 짧은 튐은 울지 않는다. S3는 단말마다 배치를 한 번만 보냈다. 시연 단말처럼 5초에 끊고 같은 배치를 다시 보내거나 backlog가 배치 넷(1,800 op)이면 튐이 몇 분으로 길어질 수 있다(다시 볼 조건).
- Micrometer 기본 bucket이 세 목표 근처에 촘촘해(150ms는 0.134~0.157초, 200ms는 0.179~0.201초, 2초는 1.79~2.15초 bucket 안. 로컬 compose 앱의 `/actuator/prometheus`에서 확인) 보간 오차가 bucket 폭(약 12~17%) 안이다. `slo` bucket은 더하지 않는다.

**3. 오류율은 다중 창 burn rate 규칙 하나다(`HttpErrorBudgetBurn`, critical, 사용자 결정).**
- 원문 가용성 목표 "주문 API 99.9%"를 단말 API(`/v1/**`)의 SLO로 둔다. 예산 기간은 Google SRE Workbook "Alerting on SLOs"의 기본값 30일이다(원문에 없다).
- 실패는 5xx(`status=~"5.."`)다. 분모는 429를 뺀 모든 요청이다(사용자 결정). 409(겹친 요청·행 잠금 대기 초과·업무 충돌)와 다른 4xx는 성공 쪽이다. `ApiControllerAdvice`가 409를 "서버 오류로 집계하지 않는다"고 정해 둔 것과 맞춘다. 고장 단말의 429 폭주가 분모를 키워 실패율을 묽게 만들지 못하게 429는 분모에서도 뺀다.
- 창과 배수는 Workbook의 page 권장값 두 쌍이다. 1시간과 5분 창이 모두 14.4배(실패율 1.44%, 30일 예산의 2%를 1시간에 씀)를 넘거나, 6시간과 30분 창이 모두 6배(0.6%, 예산의 5%를 6시간에 씀)를 넘으면 울린다. 짧은 창은 원인이 멈춘 뒤 빨리 풀리게 하는 장치라 `for:`를 두지 않는다. 느린 소진(3일 창, ticket)은 넣지 않는다(제외).

**4. 트래픽이 적을 때는 판정하지 않는다. 하한은 임계에서 계산한다(사용자 결정).**
- 지연: 5분 창에 그 uri 요청이 20건 이상일 때만 판정한다. 1 ÷ (1 − 0.95) = 20이라 요청 하나가 p95를 정하지 못한다.
- 오류율: 실패 한 건만으로는 배수를 넘지 못할 만큼 요청이 있을 때만 그 쌍을 판정한다. 1시간 창은 70건(1 ÷ 1.44% = 69.4를 올림), 6시간 창은 167건(1 ÷ 0.6% = 166.7을 올림)이다.
- 치르는 것: 한 시간에 70건보다 적으면 1시간 쌍이, 6시간에 167건(시간당 약 28건)보다 적으면 두 쌍 모두 판정하지 않는다. 시간당 28~70건이 3시간 넘게 전부 실패하면 6시간 쌍이 운다. 시간당 70~138건이면 같은 5분 안의 실패 2건(2건 ÷ 138건 = 1.45%)으로도 critical이 운다. 하한은 "실패 한 건만으로는 울지 않는다"까지만 막는다(한계).

**5. `LockFailOpen`에서 주기 작업 락의 대기 초과를 뺀다(사용자 결정).** 주기 작업 둘(`platform:event-publication`, `payment:unknown-resolve`)은 pod마다 1분에 한 번 같은 키를 잡는다. pod가 둘 이상이고 앞 pod의 블록이 대기 한도(#109가 3초에서 1.5초로 줄임)를 넘기면 늦은 pod는 매분 `timeout`으로 잠금 없이 돈다. 회복 중 재전송 500건이나 실제 PG의 결과 불명 조회(최대 100건 × deadline 3초)가 그런 블록이다. [용량 기준](../wiki/capacity.md#redisson이-필요해지는-시점)도 이것을 정상으로 보고 Redisson 기준에서 뺐다. 지금 규칙은 이때 2분 창이 끊기지 않아 복구 내내 운다. 그래서 모든 fail-open을 보되 (주기 작업 락, `timeout`) 쌍만 `unless`로 뺀다. 빼는 쪽을 목록으로 두어 새로 생기는 락과 새 `reason`은 기본으로 알림에 든다. 주기 작업이 멈추거나 결과가 밀리면 `*NotSucceeding`·`EventPublicationOverdue`·`UnknownPaymentResolutionStuck`이 잡는다.

**6. 수렴 시간 알림과 재전송 선택 건수 알림은 넣지 않는다(사용자 결정).**
- 수렴 시간(`sync_convergence_seconds`)은 단말이 operation을 만든 시각부터 잰다. 30분 끊겼던 단말은 재연결 즉시 처리돼도 30분으로 찍혀 매장 네트워크 사정이 그대로 들어간다. 원문 G-03은 "재연결 뒤 60초"라 이 metric으로 잴 수 없다. 재연결 뒤 처리 시간은 sync 지연 규칙(2초)이 덮는다. 다시 볼 조건: 재연결 시각부터 재는 metric이 생길 때.
- 재전송 선택 건수(`event_publication_resubmit_selected_total`)는 과부하 뒤 정상 회복에서도 상한(주기마다 500건)에 붙는다. #77에서 `FAILED` 15,847건이 19분, 큐 상한 200 실행이 27분 만에 빠졌다(#77 과부하 뒤 회복, 3차 측정). 선택 건수 counter 자체는 읽지 않았고, 15,847건을 1분에 500건씩 다시 보내면 32분 동안 상한에 붙는다는 계산이다(실제로는 그보다 빨랐다). "500에서 줄지 않음"을 정상 회복에 울지 않게 잡으려면 30분 넘게 기다려야 한다. `EventPublicationOverdue`는 가장 오래된 기록이 1시간을 넘고 `for` 5분 뒤라, 같은 막힘을 약 35분 늦게 잡는다. 그 35분 동안 재전송은 계속 돌고 데이터는 잃지 않으며 조치(브로커·영영 나가지 못하는 기록 확인)도 같아서 감수한다(한계). 다시 볼 조건: 발행 지연 알림보다 일찍 알아야 하는 사고가 생길 때.

**7. 시작값은 바꾸지 않고 판정을 표로 남긴다(사용자 결정).** 감지 지연은 "얼마나 빨리 알아야 하나"라는 운영 요구로 정하는 값이라 측정으로 답이 나오지 않는다. 판정은 아래 "시작값 판정" 절이다. 근거가 생긴 값은 규칙의 `shortcut:` 주석을 근거로 바꾸고, 여전히 없는 값은 주석을 그대로 둔다.

### 측정값과 판별
#77 S1(같은 머신, 플랫폼 스레드 8, Hikari 10)의 p95다. 300 RPS는 견딘 마지막 단계, 400 RPS는 무너진 단계다.

| uri | 목표 | 100 RPS | 300 RPS | 400 RPS | 판별 |
|---|---|---|---|---|---|
| 주문 생성 | 200ms | 96ms | 127ms | 4,935ms | 견딘 단계는 아래, 무너진 단계는 위 |
| checkout | 150ms | 50ms | 77ms | 3,384ms | 같음 |
| sync | 2초 | 134ms | 180ms | 4,394ms | 같음 |

- 오류율: 300 RPS까지 k6 실패율 0.00%, 400 RPS 1.17%(커넥션 대기 초과 310건이 500), 500 RPS 35.90%다. k6 실패율은 클라이언트 쪽 값(timeout 포함)이라 서버가 끝낸 요청만 세는 이 규칙의 비율과 정의가 다르다. 1.17%는 1시간 쌍 배수(1.44%) 아래라 빠른 소진은 울지 않고, 이어지면 6시간 쌍(0.6%)이 운다. 400 RPS처럼 무너지는 단계는 지연 규칙이 10분에 먼저 잡는다. 500 RPS는 두 쌍 모두 넘는다.
- 22일 2차 측정(호스트 부하로 흔들림)에서는 50·100 RPS에서도 생성 p95가 613ms~3.2초였다. 흔들린 실행은 판정에 쓰지 않았다(#77). 운영에서 이런 호스트 압박은 실제 열화라 지연 규칙이 우는 것이 맞다.

### 시작값 판정
| 값 | 지금 | 판정 | 근거 | 다시 볼 조건 |
|---|---|---|---|---|
| `EventPublicationOverdue` 가장 오래된 기록 1시간 | 유지 | 근거 생김 | 측정한 회복이 가장 길어도 27분이다(3차 측정, 바탕 70 RPS에서 Kafka 1분 정지, 큐 상한 200, Kafka 재시작부터 미완료 0까지). S1 과부하(500 RPS) 뒤 19분, 재전송 주기 5초 12분. 1시간은 측정한 정상 회복에 울지 않는다. 경과 시간 gauge 자체는 측정 때 읽지 않았고, 발행 시각부터 재므로 장애 길이와 backlog 소진 시간을 더한 값이다. 피크 부하의 backlog(재전송은 클러스터 전체에서 1분에 500건)는 측정보다 클 수 있다 | 외부화 상한이나 재전송 배치(500건)·주기(1분)가 바뀔 때, 운영 회복이 30분을 넘길 때 |
| `KafkaConsumerLag` 1000, for 10분 | 유지 | 판별만 확인 | 정상 부하(S4 지연 0)에서 lag 최대 0, provider 1초 지연에서 1분 실행에 2,398, 3초에서 5,870이다(S4 표의 "lag 최대". 파티션별인지 group 합계인지는 그 표가 적지 않았다. 합계여도 파티션당 약 800이 1분에 쌓여 곧 1000을 넘는다). 그 사이 어느 값이든 같은 판별이라 1000 자체의 근거는 없다 | 실제 PG 지연 분포가 생길 때. 건수 대신 밀린 시간으로 보는 것을 함께 본다 |
| 작업 생존 3개, 창 10분 + for 10분 | 유지 | 근거 없음 | 측정이 스케줄러 멈춤을 다루지 않았다 | 실제 PG에서 결과 불명 조회 배치가 길어질 때(최대 100건 × deadline 3초 = 5분) |
| `LockFailOpen` 창 2분, for 10분 | 값 유지, 대상 변경(결정 5) | 대상은 근거 생김, 시간은 근거 없음 | 대기 한도 1.5초(#109)로 주기 작업 락의 대기 초과가 정상이 됐다. 업무 락은 20개 동시 같은 키에서만 대기 초과(2.8%)가 났다 | 업무 락의 대기 초과가 장애 밖에서 생길 때(용량 기준의 Redisson 조건) |
| `SyncGapPersistent` 창 5분, for 30분 | 유지 | 근거 없음 | 측정 전 구간에서 GAP 0이었다 | 단말 재전송 주기가 정해질 때 |
| `OwnerSessionStoreUnavailable` 창 2분, for 5분 | 유지 | 근거 없음 | 측정이 점주 경로를 다루지 않았다 | 점주 트래픽 분포가 생길 때 |
| `InboundStoreRateLimited` 창 2분, for 5분 | 유지 | #102 결정 그대로 | 감지 지연으로 고른 시작값([inbound-rate-limit](2026-10-21-inbound-rate-limit.md) 범위 조정) | 그 spec의 조건 |
| 감지 창 2분·5분의 하한 | 유지 | 기계적 근거 | scrape 15초의 네 배 이상, `for:`보다 짧게(#76 범위 조정 12) | scrape 간격이 바뀔 때 |
| `HttpLatencyAboveTarget` 창 5분, for 10분 | 새 값 | 근거 있음 | 결정 2 | 정상 튐이 1분보다 길어질 때, 단말 sync timeout·재전송 계약이 정해질 때 |
| `HttpErrorBudgetBurn` 창·배수 | 새 값 | 근거 있음 | Workbook 권장값과 원문 99.9%(결정 3). 예산 기간 30일은 Workbook 기본값이다 | 가용성 목표나 예산 기간이 정해질 때 |

`ResilientPosMetricsAbsent`, `EventPublicationMetricStale`, DLT 둘, 결과 불명 둘의 값은 #103의 대상이 아니다. 그 근거는 #76과 [alert-state-gauges](2026-10-22-alert-state-gauges.md)에 있다.

## 제외
- 느린 소진(3일·6시간 창, 배수 1) ticket 규칙. 3일 창을 원시 series로 평가마다 읽어야 해 recording rule이 먼저 필요하고, 예산 기간 30일 자체가 가정이다. 예산 소진 추세를 볼 대시보드나 ticket 경로를 붙일 때 함께 본다.
- synthetic probe(원문 §7의 "synthetic + error budget"). 서버가 끝내지 못한 요청과 영업 외 시간의 공백(한계)을 덮는 수단이다. 배포 경로(ingress·LB)가 정해질 때 edge 5xx와 함께 본다.
- Alertmanager와 발송 경로(#76 제외 그대로).
- recording rule. 새 규칙이 둘이라 식을 바로 쓴다. 다시 볼 조건: 같은 비율 식을 쓰는 규칙이나 대시보드가 셋을 넘을 때.
- HTTP histogram의 `slo` bucket. 결정 2.
- 점주(`/owner/**`)·운영자(`/admin/**`) 경로의 SLO. 원문 목표가 없다. 503은 `OwnerSessionStoreUnavailable`이 본다.
- 결제 재시도(`POST /v1/payment-attempts/{paymentId}/retry`)의 지연. PG를 동기로 불러 "PG 제외 결제 처리" 목표가 맞지 않는다. 오류율에는 든다.
- 재연결 시각부터 재는 수렴 metric(결정 6).
- 시작값 바꾸기와 그것을 위한 추가 측정(결정 7).
- 운영 기준선. 운영 첫 주 분포로 다시 잡는다.

## 한계
- 6시간에 167건(시간당 약 28건)보다 적은 트래픽에서는 요청이 전부 실패해도 오류율 알림이 울리지 않고, 시간당 70건보다 적으면 1시간 쌍이 판정하지 않는다. 지연 규칙도 5분에 20건 미만이면 판정하지 않는다. 그 시간대의 전체 장애는 `ResilientPosMetricsAbsent`(앱이 내려갔을 때)만 잡는다. 시간당 70~138건이면 같은 5분 안의 실패 2건으로 critical이 운다(결정 4).
- 앞 단계가 실패하는 장애에서는 뒤 단계 uri의 요청이 사라져 하한 아래로 떨어지고, 그 uri의 지연은 판정하지 않는다. 주문 생성이 실패하면 checkout이 그렇다(compose 실측). 그때는 앞 단계의 지연·오류율 알림이 운다.
- 서버가 끝낸 요청만 센다. 클라이언트가 먼저 끊은 요청(시연 단말은 sync를 5초에 끊는다), ingress·LB의 502·504, Tomcat 큐에서 거절된 요청은 오류율에 들지 않는다. 끝낸 요청이 느리면 지연 규칙이 덮는다. 나머지는 synthetic probe·edge 지표의 일이다(제외).
- 인증에 실패한 단말 무리의 401은 빈도 상한(인증 뒤에 돈다)에 막히지 않고 빨리 끝나, 429처럼 지연 p95를 낮추고 오류율 분모를 키운다. edge의 요청 상한에 기댄다([관측성](../wiki/observability.md) 노출 범위 항목).
- 재전송이 상한에 붙어 줄지 않는 막힘은 `EventPublicationOverdue`가 선택 건수 알림보다 약 35분 늦게 잡는다(결정 6).
- sync는 1건 배치만 느려지는 열화(예: 180ms가 1.5초가 됨)를 잡지 못한다. p95가 2초를 넘어야 운다.
- 지연은 pod를 모아 본다. pod 하나만 느리면 다른 pod의 요청에 묽어진다.
- 행 잠금 대기 초과로 난 409는 서버 열화여도 오류율에 들지 않는다. 그때는 대개 지연 규칙이 먼저 운다.
- 400 RPS처럼 실패율이 0.6~1.44%로 이어지는 열화는 6시간 쌍이 차야 오류율 알림이 운다. 지연 규칙이 먼저 운다.
- 예산 기간 30일은 원문에 없는 가정이다.
- 로컬에서는 알림이 발송되지 않고 firing 상태로만 보인다(#76 한계 그대로).

## 관련 문서
없음

## 변경 경로
- docs/spec/2026-10-23-slo-alert-baseline.md
- docker/prometheus/alerts.yaml
- docker/prometheus/alerts.test.yaml
- src/test/kotlin/com/resilientpos/platform/AlertRulesTest.kt
- docs/wiki/runbook.md
- docs/wiki/observability.md
- docs/wiki/capacity.md

## 인수 조건
1. 생성·checkout·sync 중 한 uri의 5분 p95가 그 목표(200ms·150ms·2초)를 10분 넘게 넘으면 `HttpLatencyAboveTarget`(warning)이 그 `uri` label로 울리고, p95가 목표 아래로 내려가면 풀린다. 목표 바로 위아래로 판별한다(생성 0.21초는 울고 0.19초는 조용하다, checkout 0.16초와 0.1375초, sync 1.75초는 조용하다). promtool 시나리오로 확인한다.
2. 지연 규칙은 1분짜리 튐, 5분에 20건보다 적은 요청, 빠른 429에 묻힌 느린 요청에서 결정 2·4대로 동작한다. 앞의 둘에는 울지 않고, 셋째에는 429를 빼고 판정해 운다.
3. `/v1/**`의 5xx가 1시간·5분 창 모두 1.44%를 넘거나 6시간·30분 창 모두 0.6%를 넘으면 `HttpErrorBudgetBurn`(critical)이 울리고, 짧은 창이 배수 아래로 내려가면 풀린다.
4. 오류율 규칙은 한 시간 70건 미만 트래픽의 실패 한 건, 409, 429 폭주에서 결정 3·4대로 동작한다. 앞의 둘에는 울지 않고, 429 폭주가 있어도 5xx 비율은 429를 뺀 분모로 판정해 운다.
5. `LockFailOpen`은 주기 작업 락의 `timeout`이 이어져도 울지 않고, 주기 작업 락의 `error`와 업무 락의 `timeout`이 이어지면 운다.
6. 새 규칙과 바뀐 규칙의 시나리오는 규칙을 고치기 전에 promtool에서 실패한다(TDD 검증 기록).
7. `AlertRulesTest`가 규칙 18개로 통과하고, 새 규칙마다 runbook에 같은 이름 절이 있다.
8. 로컬 compose에서 두 새 규칙이 실제로 firing되고 해소되는 것을 Prometheus `/api/v1/alerts`로 확인해 결과 절에 남긴다.
9. runbook·관측성·용량 기준 wiki가 새 규칙, 넣지 않은 알림의 이유, 락 대기 1.5초를 반영한다.

## 검증
- promtool 직접: `docker run --rm -v "$PWD/docker/prometheus:/rules:ro" --entrypoint promtool prom/prometheus:v3.15.0 test rules /rules/alerts.test.yaml`
- `./gradlew test --tests 'com.resilientpos.platform.AlertRulesTest' --tests 'com.resilientpos.platform.ObservabilityConfigurationTest'`, 그리고 `./gradlew test` 전체.
- `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전에는 `--worktree` 없이.
- compose 실측: 공유 스택(`name: resilientpos`)을 다른 세션이 쓰는지 `docker ps`와 그 세션 상태로 확인한 뒤 띄운다. 낮은 부하의 k6 혼합에 장애(예: PostgreSQL을 멈춰 커넥션 획득이 3초 뒤 실패하게 함)를 넣어 두 규칙이 울리는지, 장애를 걷은 뒤 풀리는지 lgtm 컨테이너 안에서 `curl localhost:9090/api/v1/alerts`로 본다. 다른 세션이 측정 중이면 기다리거나 사용자에게 묻는다.

## Wiki 영향
- [runbook](../wiki/runbook.md): `HttpLatencyAboveTarget`·`HttpErrorBudgetBurn` 절을 더한다. `LockFailOpen` 절의 대기 한도를 1.5초로 고치고 주기 작업 락의 대기 초과를 보지 않는다고 적는다. `KafkaConsumerLag` 절의 "부하테스트 전의 시작값"을 이 spec의 판정으로 바꾼다(`EventPublicationOverdue` 절에는 시작값 문구가 없어 그대로다). Hikari 풀 문장은 #77 표대로 "외부화 처리량이 늘지 않았다"로 고친다.
- [관측성](../wiki/observability.md): 규칙 수(18개), 분산 락 항목의 알림 범위, "아직 없는 것"의 SLO 항목 삭제와 수렴 시간·재전송 선택 건수 알림을 넣지 않는 이유(결정 6).
- [용량 기준](../wiki/capacity.md): "HTTP 지연 기준선" 절이 지연 알림의 임계와 이 spec을 가리키게 한다.
- 요구사항 영향: 업무 요구사항은 바뀌지 않는다. 원문 NFR을 알림 임계로 옮길 뿐이다. 도메인 모델·이벤트 계약은 무영향이다.

## 분할 계획
- 개발 컨텍스트는 "기준선으로 정하는 알림 규칙과 그 시작값 판정" 하나다. 운영 코드·metric 변경이 없다.
- 따로 둔 것: 재연결 시각 수렴 metric, 시작값을 바꾸는 측정, 발송 경로(제외).
- 겹침 확인(2026-10-23, origin/main 63b73bc): 열린 PR은 #94(`build.gradle.kts`, `docs/wiki/domain-model.md`, `EventPublicationResubmitter.kt`와 그 테스트, 그 spec)뿐이고 이 PR의 경로와 겹치지 않는다. 진행 중인 `feature/terminal-concurrency-limit`(#101)은 spec(e8cdf2e) 결정 7에서 알림을 두지 않기로 해 규칙 파일은 겹치지 않고, `observability.md`(단말 빈도 상한 항목)와 `capacity.md`가 이 PR과 다른 절에서 겹친다. 먼저 머지된 쪽 위로 rebase한다. 그 PR의 동시 요청 거절도 429라 이 PR의 오류율 정의(429 제외)와 맞는다. `fix/boot-4-1-2`는 `build.gradle.kts`라 겹치지 않는다.
- rebase(2026-10-23, origin/main 0cb9b93): 그사이 #111(Boot 4.1.2)과 #94(Modulith 2.1.2)가 머지됐다. 두 PR은 이 PR의 경로를 건드리지 않아 충돌이 없었다. #94가 고친 `EventPublicationResubmitter`의 재전송 counter 이름, 배치 500건, 작업 이름(`resubmitAndPurge`), 락 키는 그대로라 결정 6과 생존 규칙의 전제가 바뀌지 않는다. 열린 PR은 없다.

## TDD 검증 기록
업무 동작 변경이 아니라 알림 규칙 변경이다. 규칙은 promtool 시나리오를 먼저 두고 실패를 본 뒤 고친다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 지연 규칙 시나리오 6개(인수 조건 1·2) | promtool 직접 실행. 울어야 하는 세 시나리오(uri별 목표 12분, sync 2초 22분, 빠른 429에 가린 sync 12분)가 `got:[]`로 실패(규칙 없음) | 같은 명령 `SUCCESS` | 결함 15종을 하나씩 넣어 모두 실패 확인: 요청 수 하한 16·24, 요청 수에 429 포함, 생성·checkout·sync 갈래마다 429 포함, 생성 목표 0.18·0.22·0.25초, checkout 0.13·0.17초, sync 1.7·2.5초, `for` 5분 |
| 오류율 규칙 시나리오 13개(인수 조건 3·4) | promtool 직접 실행. 울어야 하는 세 시나리오의 네 평가(61·90·160분, 429 폭주 5분)가 `got:[]`로 실패 | 같은 명령 `SUCCESS` | 결함 24종 가운데 23종 실패 확인. 창 넷(1시간·5분·6시간·30분)마다 분자에 `/v1` 밖 포함, 분자에 409 포함, 분모에 429 포함(12종). 하한 60·80·150·180건, 1시간 하한의 개수에 429 포함(5종). 배수 1시간 15, 5분 30, 6시간 5·7, 30분 15(5종). 놓친 1종(1시간 창 배수만 13)은 같은 쌍의 5분 창이 14.4배로 막아 결과가 같아 고정하지 않았다 |
| 주기 작업 락 timeout 시나리오(인수 조건 5) | promtool 직접 실행. 15분 평가에 주기 작업 락 timeout 둘까지 네 알림이 울려 실패 | 같은 명령 `SUCCESS` | 결함 3종 실패 확인: `unless` 제거, 주기 작업 락의 `error`도 뺌, 업무 락의 `timeout`도 뺌 |
| `AlertRulesTest` 규칙 수 18(인수 조건 7) | `./gradlew test --tests 'com.resilientpos.platform.AlertRulesTest'` 2개 실패: `SUCCESS: 16 rules found` 단언, runbook에 없는 절 `httplatencyabovetarget`·`httperrorbudgetburn` | 단언을 18로, runbook 두 절 추가 뒤 `AlertRulesTest` 2개·`ObservabilityConfigurationTest` 5개 통과 | `./gradlew test` 전체 905개 통과(실패 0) |

## 결과
- 규칙: `HttpLatencyAboveTarget`(warning)과 `HttpErrorBudgetBurn`(critical)을 더해 규칙이 18개다. `LockFailOpen`은 (주기 작업 락, `timeout`) 쌍만 뺀다. 발행 지연·consumer lag 주석은 시작값 판정대로 바꿨다. 다른 시작값은 바꾸지 않았다(결정 7).
- 넣지 않은 알림: 수렴 시간과 재전송 선택 건수(결정 6). 관측성 wiki의 "아직 없는 것"이 그 이유와 다시 볼 조건을 적는다.
- 검증: promtool 시나리오가 `SUCCESS`이고, 규칙에 결함 42종을 하나씩 넣어 41종이 실패했다(TDD 검증 기록). origin/main 0cb9b93 위로 rebase한 뒤 `./gradlew test` 전체 905개가 통과했다(실패 0). `check_pr.py`가 `--worktree`로, 제출 전에는 `--worktree` 없이 통과했다.
- 리뷰: `resilientpos-multi-review`를 Claude·Codex로 돌렸다(Gemini는 CLI가 없어 미실행). 막는 결함은 없었다. 반영한 것은 목표·하한·배수의 경계 시나리오, 창마다의 선택자 판별, `LockFailOpen`의 `unless` 식, 저트래픽 한계 문구, 재전송 제외 근거 정정(35분 차이), 서버 쪽 SLI와 401의 한계, runbook의 진단 조회와 Hikari 문장이다. 반영하지 않은 것은 셋이다. 진단 PromQL의 `application` 필터는 runbook 전체의 관례를 따랐다. 기존 `LockFailOpen` 절의 "함께 울 것이다" 문장은 이 PR이 쓴 줄이 아니다. summary의 `$value`는 시나리오가 값까지 맞춰야 해 넣지 않았다.

### compose 실측(2026-10-23)
- 환경: 공유 compose 스택(`name: resilientpos`)이다. 앱은 다른 worktree가 2026-10-22 15:30에 빌드한 이미지이고, HTTP metric과 label은 main과 같다. 그 이미지에 #108의 미처리 DLT 갱신 작업이 없어 `KafkaDeadLetterMetricNotSucceeding`이 내내 울었고, 이 실측과는 무관하다. lgtm의 Prometheus는 다른 worktree 경로의 규칙 파일을 싣고 있다. 그래서 lgtm을 다시 만들지 않고, 같은 네트워크(`resilientpos_default`)에 이 브랜치의 `alerts.yaml`(4c1a54c)을 싣는 `prom/prometheus:v3.15.0` 컨테이너를 따로 띄웠다. 그 컨테이너가 `app:8080`을 15초마다 scrape하고 15초마다 평가했다. 상태는 그 컨테이너 안에서 `/api/v1/alerts`로 읽었다.
- 부하: `k6 run -e RATE=10 -e STORES=15 -e SYNC_VUS=40 -e HTTP_VUS=20 -e DURATION=55m load-test/s1-baseline.js`(k6 v2.2.0). sync 초당 8건, 생성과 checkout 초당 1건씩이다. 55분을 채우고 07:52:53Z에 끝나 그 뒤로는 트래픽이 없었다. 오류율 알림의 해소는 30분 창에서 마지막 실패가 빠지는 시각으로 정해져 영향이 없다.
- 장애: PostgreSQL 컨테이너를 07:06:07Z에 멈추고 07:41:47Z에 다시 띄웠다. 공유 인프라라 사용자가 직접 실행했다. 다른 세션(#101, Boot 4.1.2)에 미리 확인했다. 정지 동안 커넥션 획득이 3초 뒤 실패해 단말 요청이 모두 500이었다(5분 p95 3.20초).
- 호스트: load average가 12~167로 흔들렸다(다른 세션 작업과 이 브랜치의 `./gradlew test`). 장애 전 07:05Z의 5분 p95는 생성 1.06초, checkout 0.52초였다.

| 시각(UTC) | 일 | 알림 |
|---|---|---|
| 06:57:49~06:58:04 | 오래 쉰 앱의 첫 요청 | 생성·checkout pending, 15초 뒤 풀림 |
| 06:58:19~07:01:51 | 호스트 부하(5분 평균 load 76) | 생성·checkout pending, 10분이 차기 전에 풀림 |
| 07:04:21 | 호스트 부하(load 167) | 생성·checkout pending |
| 07:06:07 | PostgreSQL 정지 | |
| 07:06:21 | 첫 평가 | `HttpErrorBudgetBurn` firing(정지 14초 뒤). sync pending |
| 07:09:04 | 주문 생성이 실패해 checkout 요청이 없어짐(5분 20건 미만) | checkout pending 풀림 |
| 07:14:23 | | 생성 firing(07:04:21 pending부터 10분. 시작은 호스트 부하였다) |
| 07:16:24 | | sync firing(정지 뒤 pending부터 10분) |
| 07:20:00 | | 실패율 1시간 60.3%, 5분 100%, 30분 60.3% |
| 07:41:47 | PostgreSQL 재시작 | |
| 07:42:28~07:45:31 | 재기동 직후 checkout | checkout pending 뒤 풀림 |
| 07:47:03 | 5분 창이 깨끗해짐. 5분 p95 생성·checkout 88ms, sync 316ms | 지연 알림 모두 풀림(재시작 5분 16초 뒤) |
| 08:12:02 | 30분 창에서 마지막 실패(07:41:47)가 빠짐 | `HttpErrorBudgetBurn` 풀림(재시작 30분 15초 뒤) |

- 판정: 두 새 규칙이 실제 장애에서 firing하고 장애를 걷은 뒤 풀렸다(인수 조건 8). 지연 알림은 창 5분 뒤에, 오류율 알림은 30분 창(6시간 쌍) 뒤에 풀렸다. 결정 3의 "큰 장애 뒤에는 30분쯤 더 운다"와 같다.
- 드러난 것: 장애가 없어도 호스트 부하만으로 생성·checkout이 여러 번 pending이 됐고, 그 가운데 10분을 채운 것은 없었다. 장애 전 마지막 pending(07:04:21)은 장애와 이어져 생성 firing이 정지 8분 뒤로 당겨졌다. 앞 단계가 실패하면 뒤 단계 uri가 판정에서 빠지는 것은 한계 절에 더했다.
