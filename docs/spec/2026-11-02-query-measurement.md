# PR spec: 조회를 섞은 부하를 재고 GET 지연 알림과 캐시 판정을 남긴다

## 목적
조회 영역은 Phase 넷이다. 1 주문 조회(#122), 2 상태 가시성과 폴링 계약(`feature/order-status-polling`, 동시 진행), 3 메뉴(#121), 4 측정과 튜닝(이 PR). Phase마다 PR 하나다. GitHub 이슈는 없고 사용자가 정했다.

앞 Phase들은 측정을 이 Phase로 미뤘다. 조회 섞은 k6와 GET 지연 알림, 캐시([order-query](2026-10-26-order-query.md) 제외 절), 메뉴의 Redis·pod 로컬 캐시([menu-catalog](2026-10-26-menu-catalog.md) 결정 6), 새 행마다 늘어난 merge SELECT([jpa-repository-adapters](2026-10-27-jpa-repository-adapters.md) 제외·한계 절)다. 지금 용량 기준([용량 기준](../wiki/capacity.md))은 쓰기만 쟀다.

기대 결과:
- 쓰기 부하 위에 GET을 얹으면 인스턴스 하나가 GET을 몇 RPS까지 견디는지, 무너질 때 병목이 DB인지 앱인지가 수치로 남는다.
- 네 GET 경로의 p95가 원문 목표를 10분 넘게 넘으면 기존 지연 알림이 운다.
- 캐시(메뉴 ETag 외 Redis·로컬 캐시)가 필요한지와 merge SELECT를 줄여야 하는지가 측정 전에 정한 규칙으로 판정된다.
- 측정이 차이를 잡는지(판별력)를 결함 주입으로 보인다.

## 범위
운영 코드는 판정 규칙이 튜닝을 고를 때만 바뀐다. 그때는 이 spec에 범위 조정으로 먼저 적고, 근거와 선택지를 사용자에게 한 번에 하나씩 물은 뒤 바꾼다. 기본으로 바뀌는 것은 k6 스크립트, 시드 SQL, 알림 규칙과 promtool 시나리오, wiki다.

### 결정
**1. 부하 모델: 쓰기를 고정하고 GET을 단계로 올린다(사용자 결정).**
- 쓰기는 #77 바탕 부하 70 RPS(S1 기준 혼합: 80% 판매 1건 sync, 20% HTTP 생성·checkout 쌍)로 고정한다. 외부화 상한(약 100 RPS) 아래라 실행마다 잔여 작업이 쌓이지 않는다.
- GET은 0(쓰기만), 50, 100, 200, 400, 500, 800 RPS 단계로 올린다. 0은 쓰기 간섭을 볼 바탕이고, 500은 견딜 부하(판정 규칙)라 반드시 잰다. 단계마다 2분이고 첫 30초는 버린다(warm-up). 같은 단계를 3회 재서 중앙값으로 판정한다.
- GET 구성은 가중치 40/40/15/5다(사용자 결정). 주문 단건 `GET /v1/orders/{orderId}` 40%, 결제 상태 `GET /v1/orders/{orderId}/payment-attempts` 40%, 단말 메뉴 `GET /v1/menu` 15%, 점주 주문 목록 `GET /owner/v1/stores/{storeId}/orders` 5%다. 단건·결제 상태는 Phase 2 폴링이 부를 경로라 크게 잡았다.
- 단건·결제 상태는 시드 주문에서 고른다. 시드 뒤 매장마다 최근 1일 주문 id와 90일 무작위 id를 파일로 내보내고(`COPY … TO`) k6 init에서 `open()`으로 읽는다. 요청의 90%는 최근 집합, 10%는 무작위 집합이다. 폴링은 최근 주문을 되풀이해 읽고, 90일 균등 무작위면 `t_order` 1.4GB와 line 2,000만 행이 shared_buffers 1GB를 넘어 판정이 id 분포의 산물이 된다. 실행 중 만든 주문은 쓰지 않는다(k6 VU는 메모리를 공유하지 않는다). 결제 상태는 없는 주문에도 200과 빈 목록이라, 시도가 있는 시드 주문은 목록이 비지 않는지 check한다. 메뉴는 매장마다 메뉴를 하나 두고 받은 ETag를 `If-None-Match`로 보내 304 경로를 잰다. 점주 목록은 필터 없는 첫 페이지다(상한 50).
- 새 스크립트 `load-test/s6-read-mix.js`는 S1의 쓰기 시나리오(`lib/baseline.js`)를 그대로 쓰고 GET 시나리오를 더한다. GET 호출은 `lib/api.js`에 더한다.
- 준비(점주·매장·단말·토큰 발급)는 별도 k6 실행으로 한 번만 하고 결과를 scratchpad의 JSON에 남긴다. 토큰은 발급할 때만 받을 수 있어(해시만 저장) 측정마다 새로 만들면 시드와 매장 id가 어긋난다. 측정 실행은 그 파일을 읽는다. GET 시나리오는 단말 소유 분배(`ownedTerminals`)에서 빼고 전체 단말에서 고른다. GET은 순번이 없고, 분배에 넣으면 sync VU 몫이 줄며 몇 단말에 GET이 몰려 429가 생긴다.

**2. 측정 데이터: 일회용 PostgreSQL에 1,000만 행 시드를 넣는다(사용자 결정).**
- 일회용 `postgres:18-alpine` 컨테이너를 compose 네트워크에 띄운다. `shared_preload_libraries=pg_stat_statements`, shared_buffers 1GB로 #122 실측과 같은 조건이다. 공유 compose 스택(`resilientpos`)의 PostgreSQL은 쓰지 않는다.
- 앱과 PostgreSQL에 `cpus`를 고정한다(k6·앱·DB가 한 OrbStack VM의 CPU를 나눠 써서 병목 분류가 흔들린다). 값과 OTLP trace export 여부는 환경 표에 적는다.
- 앱은 `docker/measure.env`의 `POSTGRES_URL`로 그 DB에 붙인다. compose 스택은 다른 worktree와 같이 쓰므로 띄우기 전과 내리기 전에 `docker ps`로 확인하고 남이 띄운 스택은 내리지 않는다.
- k6 setup이 매장 250곳 × 단말 4대를 API로 만든 뒤 `load-test/seed-read-history.sql`이 그 매장들에 주문 1,000만(매장마다 4만, 90일), line 2,000만, 결제 시도를 넣는다. #122 실측과 같은 모양이다. 주문 id는 `occurred_at`에 맞춘 UUIDv7로 만들어 인덱스 지역성을 운영과 맞추고, 시드 뒤 `VACUUM ANALYZE`를 한다. 시드는 준비 JSON의 매장에 한 번만 넣는다.
- 측정 뒤 일회용 컨테이너를 지운다.

**3. 무엇을 재는가.**
- 지연: 판정은 k6 `http_req_duration`의 경로별(`name` 태그) p95·p99다. 원시 표본이라 Tomcat 대기를 포함하고 bucket 보간이 없다. 서버 histogram(`http_server_requests_seconds_bucket`)은 원인 분석과 알림 판별에 쓴다. 메뉴는 status 200과 304를 따로 남긴다.
- 응답: status별 수(5xx, 429), k6 `dropped_iterations`.
- 요청당 커넥션 획득 수: `Δhikaricp_connections_usage_seconds_count ÷ Δhttp_server_requests_seconds_count`. 단말 인증(`Tx.readOnlyTx`)과 조회(`@ReadOnlyTransactional`)가 커넥션을 따로 잡을 수 있다.
- Redis: GET마다 빈도 상한 Lua가 돌고 점주 GET은 세션을 읽는다. Lettuce 명령 p99(`lettuce_seconds_bucket`)를 남긴다. pod를 늘려도 Redis는 그대로다.
- 외부화: 단계마다 실행기 큐 길이와 미완료 발행 기록 수를 남긴다. GET이 커밋을 늦추면 다음 단계로 넘친다.
- 커넥션 풀: `hikaricp_connections_pending`·`active` 최대, 획득 시간(`hikaricp_connections_acquire_seconds`), 쥔 시간(`hikaricp_connections_usage_seconds`)의 `_sum`·`_count`·`_max`.
- DB 쿼리 시간: 측정 DB의 `pg_stat_statements`를 단계 전에 `pg_stat_statements_reset()`하고 단계 뒤 쿼리별 `calls`·`mean_exec_time`·`max_exec_time`·`total_exec_time`을 읽는다(사용자 결정). 앱에 쿼리 시간 metric 의존성은 더하지 않는다.
- PostgreSQL·앱 CPU는 `docker stats --no-stream`을 5초마다 떠서 단계의 중앙값과 최대를 남긴다. DB 대기는 `pg_stat_activity`를 5초마다 떠서 active 세션의 `wait_event_type`을 센다.
- 환경(커밋, k6 버전, 머신, OrbStack CPU·메모리, 이미지)은 load-test README의 명령으로 결과 표 위에 붙인다. 측정은 `caffeinate -dims`로 돌리고, 다른 세션의 Gradle 테스트와 시각이 겹치지 않게 한다.

### 판정 규칙(측정 전에 정한다)
GET 목표는 네 경로 모두 원문 "주문 p95 200ms"다(사용자 결정). 원문 NFR에 GET 목표가 따로 없다. 견뎌야 할 GET 부하는 임시로 500 RPS다(사용자 결정). 원문 피크 500 RPS를 GET에도 적용한 값(단말 1,000대가 2초마다 한 번)이고 Phase 2가 폴링 간격을 정하면 그 부하로 바꾼다.

| 대상 | 지금 | 판정 |
|---|---|---|
| 유효한 실행 | 없음 | (개발 중 범위 조정, 2026-11-02 1회차 뒤 남은 결과를 보기 전에 정함) 실행 창의 외부 CPU(OrbStack과 k6를 뺀 호스트 프로세스 CPU 합, `ps` 5초 표본) 중앙값이 150% 이하인 실행만 판정에 쓴다. 같은 GET 0 단계가 호스트 load average 8.7에서 sync p95 55ms, 약 25에서 142ms였고(macOS `mediaanalysisd` 262%, `mds` 59%), VM 안 steal은 0으로 나와 VM 지표로는 가를 수 없었다. load average는 우리 부하도 세어 높은 단계일수록 커지므로 쓰지 않는다. 유효한 실행이 3회가 안 되는 단계는 호스트가 조용할 때 다시 잰다. 실행 창이 Mac 잠자기(`pmset -g log`의 Sleep부터 다음 Wake까지, DarkWake 포함)와 겹친 실행도 뺀다. 측정 스크립트 전체를 `caffeinate -dims` 아래에서 돌린다 |
| 단계 통과 | 없음 | 조건마다 3회 값의 중앙값을 적용한다. 네 GET의 k6 p95가 200ms 이하, 쓰기 k6 p95가 원문 목표(생성 200ms, checkout 150ms, sync 2초) 이하, 5xx 0, 429 0, `dropped_iterations` 0, `hikaricp_connections_pending`이 0보다 큰 scrape가 연속 4번(1분) 이상 없음. 집계는 단계 시작 30초 뒤부터 끝까지다. dropped가 있는데 서버·k6 p95가 목표 안이면 k6 VU 부족으로 보고 VU를 늘려 다시 잰다. GET 상한은 처음 실패한 단계 바로 아래 단계다(그 위 단계가 통과해도 상한으로 보지 않는다). 쓰기 p95가 GET 0 단계의 2배 이상 나빠진 단계는 판정 근거에 적는다(sync 2초는 판매 1건 배치에 느슨하다) |
| 처음 실패한 원인 | 없음 | 실패 조건이 GET이면 아래 병목 분류로 간다. 쓰기만 실패하면 GET이 쓰기를 밀어낸 것으로 보고 같은 분류를 적용한다 |
| 병목 분류 | 없음 | 처음 실패한 단계에서 순서대로 본다. ① PostgreSQL CPU 중앙값이 고정한 `cpus`의 80% 이상이거나 `pg_stat_activity` active 세션 가운데 IO·LWLock 대기가 절반을 넘으면 DB 쪽. ② 아니고 앱 CPU 중앙값이 80% 이상이면 앱 쪽. ③ 둘 다 아니고 pending 조건이 실패했으면 풀 쪽(Hikari 행). ④ 그 밖은 분류하지 않고 수치를 사용자에게 보인다. `pg_stat_statements`는 분류가 아니라 DB 쪽일 때 어느 SQL인지 가리는 데 쓴다(인증·line 조회는 GET과 쓰기가 같은 문장이라 GET만 떼어 셀 수 없다) |
| 분류 불가일 때(④) | 없음 | (개발 중 범위 조정, 사용자 결정) GET 400~500의 붕괴가 ④였다. 무너진 실행마다 커넥션 획득 최대가 2초를 넘었지만 15초 scrape의 pending은 0이었고 앱·DB CPU는 고정한 4코어의 절반 아래였다. 원인을 가리려고 앱 `/actuator/prometheus`를 1초마다 읽어 pending·active·실행기 큐를 남기고, GET 500을 풀 10과 풀 20(`docker/measure.env`, 커밋 없음)에서 3회씩 잰다. 풀 20에서 GET 500이 단계 통과 조건을 채우면 풀 쪽으로 분류하고, 풀 값을 바꿀지는 쓰기 지연 영향과 함께 사용자에게 묻는다. 채우지 못하면 1초 표본과 함께 다시 보인다 |
| Redis·로컬 캐시 | 없음 | 500 단계를 통과하면 넣지 않는다. 통과하지 못하고 앱 쪽이면 캐시가 아니라 pod 수로 나눈다(읽기는 외부화처럼 DB 커밋에 묶이지 않는다. Redis 명령 지연이 원인이면 pod로 풀리지 않으므로 수치를 사용자에게 보인다). DB 쪽이면 그 SQL의 인덱스·쿼리 모양을 먼저 보고, 그래도 모자라면 캐시를 후보로 근거와 선택지를 사용자에게 묻는다. 후보가 되면 캐시 키는 인증 주체의 매장 id를 포함하고, 인증·소유 확인(단말 토큰, `requireOwned`) 결과는 캐시하지 않는다 |
| 메뉴 ETag/304 | 304여도 PK 행 하나를 읽는다 | GET 상한 단계에서 메뉴 304 p95가 200ms 이하이고 메뉴 SQL의 `total_exec_time`이 모든 SQL 합의 15%(메뉴 요청 비율) 이하이면 ETag로 충분하다. 하나라도 어기면 메뉴 캐시를 후보로 근거와 선택지를 묻는다. 통과한 단계가 없으면 50 단계에서 판정한다 |
| 쿼리 모양·인덱스 | #122·#121 그대로 | GET 쿼리의 `mean_exec_time`이 50ms([order-query](2026-10-26-order-query.md) 인덱스 판정 기준)를 넘으면 그 SQL을 `EXPLAIN (ANALYZE, BUFFERS)`로 보고 후보를 사용자에게 묻는다 |
| Hikari `maximum-pool-size` | 10 | #77 규칙 그대로. 목표 부하에서 pending이 0보다 큰 상태가 1분 넘게 이어지고 PostgreSQL CPU에 여유(70% 미만)가 있으면 pending이 사라지는 가장 작은 값을 사용자에게 묻는다 |
| 새 행 merge SELECT | 새 행마다 SELECT 하나 | 결정 4의 A/B에서 지금 빌드가 원문 목표(생성 200ms, 500 op 배치 2초)를 넘고 SELECT를 없앤 빌드가 넘지 않을 때만 줄일 근거로 본다. 그때 줄이는 방법은 근거와 선택지를 사용자에게 묻는다. 아니면 그대로 두고 차이를 남긴다 |

단말 인증(토큰 조회)은 판정과 무관하게 캐시하지 않는다. 폐기한 토큰이 캐시 수명만큼 통과하게 된다.

**4. merge SELECT 비용은 A/B로 잰다(사용자 결정).**
- B 빌드는 커밋하지 않는 임시 빌드다. 새 주문·line·checkout 행만 SELECT 없이 INSERT하게 바꾼다(persist로 되돌린 모양). watermark처럼 PK 위반이 경합 방어선인 행은 바꾸지 않는다. 코드는 scratchpad에 두고 브랜치에 남기지 않으며, diff 원문을 결과 절에 붙인다.
- 두 빌드를 같은 일회용 DB(시드 뒤)에서 잰다. 순서는 ABBA이고 실행마다 앱을 다시 띄우고 `VACUUM ANALYZE`·`CHECKPOINT` 뒤 `pg_stat_statements_reset()`을 한다(뒤에 잰 빌드가 커진 `event_publication`과 autovacuum을 떠안지 않게). HTTP 주문 생성은 line 1·10·50·100·500개(500은 `MAX_LINES`)로 단독 20 RPS 2분, sync는 S3 단독 배치(단말 1대, 500 op)와 단말 100대 동시 재연결(`BATCH=50 TIMEOUT=30s`)이다. 같은 조건 3회 중앙값이다.
- 요청당 SELECT 수는 `pg_stat_statements`의 PK SELECT `calls`를 테이블별로 센다. line 100개 주문에서 A가 B보다 SELECT를 약 100번 더 내야 측정이 배선된 것이다.
- JDBC 배치(`hibernate.jdbc.batch_size`)는 꺼져 있고 두 빌드 모두 INSERT를 한 건씩 보낸다. 이 A/B는 SELECT만 잰다.

**5. GET 지연 알림은 기존 `HttpLatencyAboveTarget`에 네 GET 경로를 더한다.**
- 목표는 네 경로 모두 200ms다. 창 5분, 429 제외, `for: 10m`, 5분에 20건 미만이면 판정하지 않는 규칙은 그대로다. 분위수 갈래와 표본 하한 모두 갈래마다 `method="GET"` 또는 `method="POST"`를 적고, 표본 하한은 `sum by (uri, method)`로 둔다. GET 매핑은 HEAD도 받아 같은 uri에 HEAD series가 생길 수 있다.
- 304(메뉴)는 처리한 요청이라 분모에 넣는다. 한계: 304가 대부분이면 200(전체 메뉴)만 느려져도 울지 않는다. 401·404 같은 빠른 4xx도 p95를 낮춘다(POST와 같다). 점주 목록은 `/owner` 경로지만 같은 규칙에 두고, 운영 표본이 적어 20건 하한으로 판정이 자주 빈다. 세 한계를 runbook에 적는다.
- 알림을 따로 두지 않는다. 조치(과부하인지, DB인지, 배포인지 가르기)가 같다. runbook 절에 GET이면 볼 쿼리(`pg_stat_statements` 운영 대응 없음, Hikari 쥔 시간)를 더한다.
- promtool 시나리오: GET 경로가 200ms를 10분 넘게 넘으면 그 uri로 울고, 9분이면 울지 않는다. GET이 5분 20건 미만이면 다른 uri의 POST가 20건 이상이어도 GET 갈래는 울지 않는다. HEAD series가 섞여도 GET만 본다. 304가 95%이고 200만 느린 메뉴는 울지 않는다(한계를 고정한다). 점주 목록이 느리면 `/owner/v1/stores/{storeId}/orders`로 운다.

**6. 판별력은 결함 주입으로 보인다.**
- 측정: 일회용 DB에서 점주 목록 인덱스 둘(`ix_order_store_occurred`, `ix_order_store_status_occurred`)을 지우고 GET 100 단계를 다시 잰다. 점주 목록 p95가 200ms를 넘고 그 SQL의 `mean_exec_time`이 커지며, 다른 세 GET과 쓰기 p95는 목표 안이어야 측정이 그 경로만 가려낸 것이다. line 인덱스(`ix_order_line_order`)는 checkout의 잠금 조회와 주문 저장도 써서 전면 장애가 되므로 쓰지 않는다. #122 단독 실측으로 인덱스 없는 목록은 240ms라 부하 중 값은 다를 수 있다. 200ms를 넘지 않으면 매장당 이력을 늘리는 대신 그 사실을 적고 사용자에게 묻는다. 재고 나서 인덱스를 다시 만든다.
- 알림: lgtm은 다시 만들지 않는다. 별도 Prometheus 컨테이너가 `alerts.yaml`을 싣고 측정 앱을 scrape하게 띄워, 결함 주입 구간(창 5분과 `for` 10분을 채우게 15분)에 `HttpLatencyAboveTarget{uri="/owner/v1/stores/{storeId}/orders"}`만 pending을 거쳐 firing이 되고 다른 uri와 정상 구간에는 비어 있는 것을 `/api/v1/alerts`로 본다. 조회는 컨테이너 안에서 curl로 한다(로컬 3000 포트는 다른 프로세스가 쓴다).

### Phase 2와의 관계
폴링 간격과 조회 bucket은 Phase 2가 정한다. 이 PR은 지금 있는 GET만으로 시나리오·판정 규칙·알림을 만든다. Phase 2가 먼저 머지되면 rebase해 폴링 부하를 시나리오에 넣고(견딜 GET 부하를 500 RPS에서 폴링 부하로 바꿔) 다시 잰다. 이 PR이 먼저 머지되면 폴링 부하는 Phase 2 PR이나 후속에서 이 시나리오에 더한다.

### 업무 기능 항목
업무 기능이 아니다. 소유 모듈, Command/Query/Event, HTTP 계약, 오류 code, 불변식은 바뀌지 않는다. 튜닝이 정해지면 범위 조정에 영향을 적는다. 관측성은 기존 지연 알림의 대상 경로가 넷 늘어난다.

## 제외
- 새 조회 API, 폴링 계약 자체, 응답 필드 추가(Phase 2), 메뉴 항목별 CRUD.
- rate limiter와 시연 단말 `index.html`. Phase 2가 고친다.
- 앱의 쿼리 시간 metric(결정 3). 운영에서 쿼리 지연은 HTTP p95와 Hikari 시간으로 간접적으로 본다.
- 측정 없는 튜닝. 판정 규칙이 고르지 않은 인덱스·쿼리·풀 변경은 하지 않는다.
- JDBC 배치 INSERT의 효과(결정 4).
- 단말 인증 캐시.
- 폴링 부하(위 Phase 2와의 관계).

## 관련 문서
없음. 측정 방법과 판정 규칙의 선례는 [k6-capacity-measurement](2026-10-21-k6-capacity-measurement.md), 알림 임계의 선례는 [slo-alert-baseline](2026-10-23-slo-alert-baseline.md), rate limit 값은 [inbound-rate-limit](2026-10-21-inbound-rate-limit.md)이다.

## 변경 경로
- load-test/**
- docker/prometheus/**
- src/test/kotlin/com/resilientpos/platform/AlertRulesTest.kt
- docs/wiki/capacity.md
- docs/wiki/runbook.md
- docs/wiki/observability.md
- docs/spec/2026-11-02-query-measurement.md

## 인수 조건
1. `load-test/s6-read-mix.js`가 쓰기 70 RPS 위에 GET을 40/40/15/5로 얹고, `GET_RATE`로 단계를 정한다. 메뉴 요청의 대부분이 304이고, 단건·결제 상태가 404 없이 200이며 시도가 있는 주문의 결제 상태 목록이 비지 않는다(k6 check). 준비 실행이 매장·단말·토큰을 파일에 남기고 측정 실행이 그 파일을 읽는다.
2. `load-test/seed-read-history.sql`이 setup이 만든 매장에 주문 1,000만·line 2,000만을 넣고, load-test README에 일회용 DB를 띄우고 시드하고 앱을 붙이는 명령이 있다.
3. 결과 절에 GET 0~800 단계의 3회 원시 수치(경로별 k6 p95·p99, 메뉴 200·304, 쓰기 p95, status별 수, pending·active 최대, 요청당 커넥션 획득 수, 획득·쥔 시간, Lettuce p99, 실행기 큐·미완료 발행 기록, `pg_stat_statements` 상위 쿼리, `pg_stat_activity` 대기, CPU 중앙값·최대)와 환경, 판정 규칙 표의 항목마다 판정과 근거 수치가 있다.
4. merge SELECT A/B(ABBA)의 line 개수별·배치별 지연 중앙값과 테이블별 요청당 SELECT 수, B 빌드 diff가 있고 판정이 있다.
5. 결함 주입(점주 목록 인덱스 삭제)에서 점주 목록 p95만 200ms를 넘고, 별도 Prometheus에서 `HttpLatencyAboveTarget`이 그 uri로만 firing이 되며 정상 구간에는 없다.
6. `HttpLatencyAboveTarget`이 네 GET 경로를 200ms로 보고, promtool 시나리오(결정 5)가 통과한다.
7. [용량 기준](../wiki/capacity.md)에 GET 상한과 병목, 캐시·merge SELECT 판정과 다시 볼 조건이 있고, runbook의 지연 알림 절이 GET을 다룬다.

## 검증
- `python3 scripts/check_pr.py --base origin/main --worktree`(개발 중), 제출 전 `--worktree` 없이.
- 알림 규칙: `promtool test rules docker/prometheus/alerts.test.yaml`(promtool이 로컬에 없으면 `prom/prometheus` 이미지 안에서), `./gradlew test --tests 'com.resilientpos.platform.AlertRulesTest'`.
- 전체: `./gradlew test`.
- 측정은 결정 1~6의 조건과 명령을 결과 절에 남긴다. 측정 스크립트(k6, 시드)는 실제 실행으로 확인한다.

## Wiki 영향
- [용량 기준](../wiki/capacity.md): 트래픽 가정에 GET 구성과 임시 500 RPS, 인스턴스 하나의 용량에 GET 상한, 채택한 값에 캐시·merge SELECT 판정, HTTP 지연 기준선에 GET.
- [runbook](../wiki/runbook.md): `HttpLatencyAboveTarget` 절에 GET 경로와 볼 값.
- [관측성](../wiki/observability.md): 지연 알림 대상 경로가 바뀌면 그 문장.
- 요구사항(원문 NFR) 변화는 없다. GET 목표는 원문 "주문 p95 200ms"를 조회에 적용한 것이다.

### 측정 설계 검토
spring-backend-specialist가 커밋 전에 검토했다(P0 없음). 반영한 것: 500 단계와 상한 정의(처음 실패 바로 아래), 병목 분류를 자원 기준(CPU 고정, `pg_stat_activity` 대기)으로 바꿈(쿼리 시간 합과 풀 전체 쥔 시간은 모집단이 달랐다), 판정 지연을 k6 원시 표본으로, 429 0 조건, 주문 id 분포(최근 90%·무작위 10%)와 준비 파일, 단계 통과의 집계 창·중앙값 적용·dropped 해석, GET 0 바탕 단계, 메뉴 200·304 분리와 어길 때의 분기, 알림 method 명시·HEAD·한계 셋과 promtool 시나리오, 결함 주입을 line 인덱스에서 점주 목록 인덱스로(line 인덱스는 checkout 잠금 조회도 써서 전면 장애), A/B의 범위·ABBA·500 line, 요청당 커넥션 획득 수, Lettuce, 외부화 넘침, 캐시 키 제약, UUIDv7 시드와 `VACUUM ANALYZE`. 측정 첫 실행에서 확인할 것: 기본 bucket의 200ms 근처 경계, HEAD series, GET과 쓰기의 line 조회 queryid.

## 분할 계획
- 이 PR은 조회 측정 컨텍스트 하나다. 폴링 부하는 Phase 2 쪽이다.
- 2026-11-02 확인: origin/main fc36227, 열린 PR 없음. `feature/order-status-polling`(Phase 2)과 `docs/wiki-stale-cleanup`은 로컬 브랜치이고 push 전이다. 겹칠 수 있는 곳은 `docs/wiki/capacity.md`·`runbook.md`(wiki-stale-cleanup이 낡은 문장을 고친다)와 `docker/prometheus/alerts.yaml`(Phase 2가 조회 bucket 알림을 더하면)이다. push·PR 생성·머지 직전에 다시 확인하고 먼저 머지된 쪽에 맞춰 rebase한다.

## TDD 검증 기록
운영 동작이 바뀌지 않아 적용하지 않는다. 알림 규칙은 promtool 시나리오로, 측정 스크립트는 실행으로 확인한다. 판정 규칙이 튜닝을 골라 운영 코드가 바뀌면 그 동작에 TDD를 적용하고 이 표를 채운다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 해당 없음 | 해당 없음 | 해당 없음 | 해당 없음 |

## 결과
2026-11-02 토큰 한도로 측정을 여기서 마쳤다. 아래는 그때까지 나온 것만 적는다. 안정적인 GET 상한은 얻지 못했고, merge SELECT A/B와 결함 주입은 실행하지 못했다.

### 환경
M3 Pro 호스트, OrbStack VM(11 vCPU, 16GiB). 앱과 일회용 PostgreSQL 18 컨테이너를 `cpus 4`로 고정했다. 풀 10(connection-timeout 3s), Tomcat 200 스레드. 시드는 주문 1,048만·line 약 2,000만·결제 시도 약 990만, 250매장. 쓰기 70 RPS 고정에 GET 0/50/100/200/400/500/800 RPS, 단계마다 2분이고 앞 30초는 버렸다. k6 v2.2.0.

### 인수 조건 충족
| 조건 | 상태 |
|---|---|
| 1 S6 스크립트와 준비 실행 | 충족. 대부분 304, 단건·결제 상태 200 check 통과 |
| 2 시드와 README | 충족. 일회용 DB 명령은 `load-test/README.md` |
| 3 GET 단계 3회 수치와 판정 | 부분 충족. 아래 표. 단계마다 유효한 실행 3회를 채웠지만 판정 항목 일부는 근거가 약하다 |
| 4 merge SELECT A/B | 미실행. 후속 작업이다. B 빌드 패치는 만들어 `git apply --check`까지만 확인했다 |
| 5 결함 주입 | 미실행. 후속 작업이다. 알림 규칙 자체는 promtool 시나리오로만 확인했다 |
| 6 알림이 네 GET을 본다 | 충족. promtool 통과, 임계·하한·HEAD 포함 결함 3개를 주입해 각각 실패를 확인했다 |
| 7 capacity·runbook | 부분 충족. runbook은 GET을 다룬다. capacity에는 아래 결론만 적었다 |

### GET 단계별 값(유효한 실행만, 외부 CPU 150% 이하)
각 단계 유효한 실행 3회의 k6 p95(ms). GET 열은 네 GET 중 가장 큰 값이고 0 RPS 단계는 GET이 없다.

| GET RPS | GET p95 3회 | 중앙값 | 생성·checkout·sync p95 중앙값 | 앱·DB CPU 중앙값(400% 기준) |
|---|---|---|---|---|
| 0 | - | - | 55 / 37 / 81 | 60 / 43 |
| 50 | 33, 330, 169 | 169 | 199 / 165 / 333 | 82 / 54 |
| 100 | 20, 38, 274 | 38 | 69 / 49 / 112 | 96 / 69 |
| 200 | 30, 89, 1600 | 89 | 129 / 81 / 210 | 103 / 90 |
| 400 | 448, 64, 329 | 329 | 414 / 239 / 615 | 142 / 88 |
| 500 | 479, 260, 456 | 456 | 585 / 425 / 769 | 154 / 100 |
| 800 | 529, 514, 409 | 514 | 731 / 555 / 930 | 193 / 114 |

### 판정
- **상한**: 정하지 못했다. 같은 단계에서 3회 p95가 30ms부터 1,600ms까지 흩어져 중앙값이 단계 사이에서 단조롭지 않다(50 RPS에서도 330ms). 단계 통과 기준(p95 200ms 이하)을 3회 모두 만족한 단계는 100 RPS뿐이므로 확인된 값은 **GET 100 RPS까지**이고, 그 위는 측정 틀이 흔들려 판정하지 않는다. 임시 기준 500 RPS는 입증되지 않았다.
- **병목 분류**: ④ 미분류. DB CPU와 앱 CPU는 모두 80%(400% 중 320%) 아래다. 지연은 짧은 정체로 나타난다. GET 500의 Tomcat 스레드 200개 중 198개가 유휴인 덤프가 있었고, 연결 대기(`hikaricp_connections_pending`)는 정체 순간에 180까지 오른다. 이때 `pg_stat_activity`는 활성 백엔드 2개뿐이고 나머지는 클라이언트 응답 대기였다.
- **풀 10 대 20(GET 500, 유효 3회씩)**: p95 중앙값 179ms 대 317ms. 풀을 늘려도 낫지 않았다. 풀 값은 바꾸지 않는다.
- **CFS 스로틀링 가설**: 앱 컨테이너 `cpus 4`가 100ms 주기로 멈추는지 보려고 4개와 8개를 비교했다. 4개 제한에서 스로틀 76회(7.7초)와 20회(1.2초), 8개에서 0회였고 유효한 실행은 제한마다 하나뿐이라(4개 226ms, 8개 79ms) 결론을 내지 못했다. 8개 제한의 무효 실행(외부 CPU 259%)에서도 연결 대기가 187까지 올랐다. 후속 작업이 이 가설을 반복 측정으로 가린다.
- **캐시**: 도입하지 않는다. `pg_stat_statements`에서 모든 쿼리의 평균이 1ms 아래다(주문 단건 0.415ms, line 0.132ms, 결제 시도 0.248ms). 50ms 규칙에 걸린 쿼리가 없어 EXPLAIN은 하지 않았다. DB가 병목이라는 근거가 없으므로 Redis 캐시를 정당화할 수치가 없다. 단말 인증 결과는 어떤 경우에도 캐시하지 않는다.
- **메뉴 ETag**: 메뉴 SQL은 GET 400 한 실행에서 총 쿼리 시간의 0.5%(7,201회, 평균 0.029ms)로 15% 기준 아래다. 304 p95는 같은 실행의 다른 GET과 같은 정체 구간 값이라 304 자체가 느리다는 근거는 없다. ETag/304만으로 충분하다고 본다. 304 p95 200ms 기준은 상한을 정하지 못해 입증이 아니라 관찰이다.
- **알림**: `HttpLatencyAboveTarget`이 네 GET 경로를 200ms로 본다. 실제 지연을 만들어 firing을 확인하지는 못했다.

### 측정 중 알게 된 것
- 호스트 부하(macOS `mediaanalysisd` 등)와 Mac 잠자기가 실행을 흔들었다. 외부 CPU 150% 초과 실행과 잠자기 구간과 겹친 실행을 뺐다(g0-r5 등).
- OrbStack이 15시 29분에 한 번 내려가 스로틀 비교 마지막 회차가 깨졌다.
- GET 매핑은 `method="HEAD"` 시계열도 만든다. 알림은 GET만 본다.
- compose의 `environment`가 `env_file`보다 앞서므로 측정 DB는 `SPRING_DATASOURCE_URL`로 바꾼다.

### 후속 작업
1. 앱 컨테이너 CPU 제한을 풀거나 올려(스로틀 0) 같은 단계를 3회 이상 다시 재서 상한을 정한다.
2. merge SELECT A/B(B 빌드 패치 포함)와 점주 목록 인덱스 결함 주입을 실행한다.
3. Phase 2가 머지되면 폴링 부하를 같은 틀에 얹는다.

