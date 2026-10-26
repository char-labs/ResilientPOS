# PR spec: 시연 단말의 sync 재전송을 Retry-After와 jitter에 맞춘다

## 목적
시연 단말(`src/main/resources/static/terminal/index.html`)이 #87 재시도 규약을 글자대로 따르지 않는다. 규약은 `error.errorCode`가 `RATE_LIMITED`이면 `Retry-After`초에 0~1초 jitter를 더한 뒤 같은 요청을 다시 보내는 것이다([inbound-rate-limit](2026-10-21-inbound-rate-limit.md) 결정 5·6, [terminal-concurrency-limit](2026-10-23-terminal-concurrency-limit.md) 결정 6). jitter는 매장 단말이 함께 끊겼다 돌아올 때 같은 초에 몰리지 않게 하려는 것이다. 지금 `postSync`는 실패를 `HTTP 상태` 문자열로만 던져 `Retry-After`가 사라지고, `flush`는 실패하면 무조건 5초 뒤 jitter 없이 다시 보낸다. [slo-alert-followups](2026-10-23-slo-alert-followups.md) 결정 7은 5초가 `Retry-After`(1초)보다 길어 규약 안이라고 봤지만, 단말이 `Retry-After`를 읽지 못해 둔 대용이었다. terminal-concurrency-limit 제외 절이 "시연 단말의 `Retry-After` 처리"를 남겼다.

기대 결과: 시연 단말이 `RATE_LIMITED`이면 `Retry-After`초 + 0~1초 뒤, 그 밖의 실패면 5초 + 0~1초 뒤 같은 배치를 다시 보낸다. 기다릴 시간 계산은 selftest가 경계값으로 고정하고, 실제 재전송 시각은 가짜 서버로 잰다.

## 범위
### 결정
**1. `RATE_LIMITED`이면 `Retry-After`초에 0~1초 jitter를 더한 뒤 다시 보낸다(사용자 결정).**
- 조건은 HTTP 429가 아니라 응답 본문의 `error.errorCode == RATE_LIMITED`다(inbound-rate-limit 결정 5, [아키텍처](../wiki/architecture.md) 요청 흐름). 나중에 handler 안의 거절이 429로 나가도 같은 요청을 다시 보내지 않게 하려는 규칙이다.
- 지금 서버 값(빈도 상한·동시 상한 모두 1초)이면 1~2초 뒤다.
- 치르는 것: 앞 배치가 서버에서 아직 도는 동안(30초 timeout 뒤의 재전송) 1~2초마다 다시 오고 거절마다 인증 조회를 한 번 치른다. terminal-concurrency-limit 결정 5가 받아들인 비용이다.
- 1,800 op backlog(배치 50으로 요청 36개)는 단말 bucket capacity 10개 뒤 refill(초당 2개)로 빠진다. 남은 26개에 약 13초다(계산, 미측정).
- 버린 것: 5초와 `Retry-After` 중 큰 값에 jitter를 더하는 안. 규약이 허락한 시각보다 늘 4초쯤 늦고, 1,800 op 배출 시간은 비슷하다(5초마다 bucket이 10개로 다시 차서 약 15초, 계산).

**2. 그 밖의 실패는 5초에 0~1초 jitter를 더한 뒤 다시 보낸다(사용자 결정).**
- 네트워크 오류, 30초 timeout, 5xx, 401, `RATE_LIMITED`가 아닌 4xx가 여기에 든다. 결정 7의 "실패 뒤 5초"는 "5~6초"가 된다.
- 매장 네트워크가 끊겨 단말이 함께 실패하는 경우가 이 경로다. jitter가 없으면 같은 순간에 실패한 단말들이 5초마다 같은 박자로 재시도하다 네트워크가 돌아오면 같은 초에 들어온다.
- 버린 것: 5초 고정. 함께 끊긴 단말들의 박자가 그대로 남는다.

**3. `Retry-After`가 이상하면 결정 2로 다루고, 큰 값은 서버 값을 따른다(사용자 결정).**
- `Retry-After`는 RFC 9110 delta-seconds(숫자만) 가운데 1 이상만 따른다. 0과 HTTP-date는 RFC상 유효하지만 서버가 보내지 않는다(`RateLimitedException`은 1 이상만 받는다). 그래서 `RATE_LIMITED`인데 `Retry-After`가 없거나 0, HTTP-date, 소수, 음수면 5초에 jitter를 더한다.
- 큰 값은 자르지 않는다. `Retry-After`는 같은 요청을 다시 보내도 되는 가장 이른 시각이라 줄이면 규약을 어긴다. 상한은 서버 설정 검증(capacity 1 이상, 빈 bucket이 하루 안에 참)이 하루(86,400초)로 묶는다. 화면은 기다릴 시간을 "N초 뒤 다시 시도합니다"로 보인다.
- 치르는 것: 설정을 잘못 넣으면 단말이 그만큼 멈춰 있다.
- 버린 것: 60초(G-03 창)에서 자르는 안. 단말이 1분 넘게 멈추지 않지만, 서버가 허락하기 전에 보내고 거절마다 인증 조회를 치른다.

**4. 기다리는 동안 모든 전송 계기를 막는다(사용자 결정).**
- 5초 주기, 새 주문, 연결 복구(`online`과 시연 막대의 네트워크 연결), "지금 동기화" 버튼 모두 catch가 건 재시도 timer(`U.retryTimer`)가 걸려 있는 동안에는 보내지 않고, timer가 끝나면 그 timer가 보낸다. 그때 오프라인이면 보내지 않고 연결 복구나 5초 주기가 보낸다.
- 범위 조정(2026-10-26, 실측 반영): 처음에는 지금 구조대로 벽시계 시각(`U.retryAt`)을 `Date.now()`와 비교해 막았다. 그런데 timer는 단조 시계로 움직인다. 진단에서 timer 40번 중 2번이 `Date.now()`가 `retryAt`과 같은 순간에 불렸다. 벽시계가 1ms만 늦어져도(NTP 조정, 시계 변경) timer가 문에 걸리고 재전송이 다음 5초 주기까지 밀린다. 실측 네 번 중 두 번에서 한 요청씩 122ms·232ms 늦었는데 원인은 가르지 못했다. 문을 "재시도 timer가 걸려 있는가"로 바꿔 시계 비교를 없앤다. 전송은 timer가 걸려 있지 않을 때만 시작하고 요청은 한 번에 하나라(`U.flushing`) timer는 늘 하나뿐이다.
- `setTimeout`은 대기를 32비트 정수로 바꿔, 2^31−1ms(약 24.8일)를 넘으면 예정보다 일찍(대개 바로) 부른다. 숫자가 아주 긴 `Retry-After`는 `Infinity`가 되어 바로 불린다. 그대로 두면 깨진 응답 하나로 단말이 왕복 시간마다 다시 보낸다. 범위 조정(2026-10-26, 재리뷰 반영): `retryDelay`가 대기를 2^31−1ms에서 멈춘다. 서버 값(하루 이하)은 그대로 따르므로 결정 3과 부딪히지 않는다. 그보다 큰 값은 서버가 보내지 않는다.
- 치르는 것: 연결 복구 직후의 전송도 timer 뒤로 밀린다(네트워크 오류면 5~6초, `RATE_LIMITED`면 `Retry-After`만큼).
- `setInterval(flush, 5000)`은 GAP 재전송과 백업으로 남는다. GAP은 200 응답이라 실패 경로를 타지 않으므로, 실패가 없는 동안의 GAP 재전송 주기(5초, `SyncGapPersistent` 창의 근거)는 바뀌지 않는다. 실패 뒤 timer를 기다리는 동안은 GAP 재전송도 기다린다(전에도 `retryAt`까지 기다렸다).
- 치르는 것: 기다리는 동안 "지금 동기화"를 눌러도 아무 일도 없다(지금의 5초도 같다).
- 버린 것: 사람의 조작(버튼, 새 주문)은 문을 넘게 하는 안. 서버가 허락하기 전에 보낸다.

**5. 계산은 난수를 주입받는 순수 함수 하나다.**
- `retryDelay(error, random)`이 기다릴 ms를 돌려준다. `random`은 0 이상 1 미만을 돌려주는 함수이고 페이지는 `Math.random`을 넘긴다. selftest가 경계값을 고정한다.
- `postSync`는 응답이 실패면 본문의 `error.errorCode`와 `Retry-After` 헤더를 오류에 싣는다. 본문이 JSON이 아니면(ingress의 HTML 등) code가 없어 결정 2다. 오류 메시지는 지금처럼 `HTTP 상태`다.

**6. S3는 바꾸지 않고 계약 문장을 고친다(사용자 결정).**
- `load-test/s3-sync-batch.js`는 실패 종류와 관계없이 jitter 없이 5초 뒤 다시 보낸다. 서버의 `Retry-After`(지금 1초)보다 늦게 보내 하한은 지키지만 jitter는 없다. [용량 기준](../wiki/capacity.md)의 "시연 단말과 S3가 이 계약을 따른다"를 시연 단말과 S3로 나눠 적는다.
- 범위 조정(2026-10-26, 리뷰 반영): S3의 동작은 그대로 두고, 이 PR 뒤로 틀린 주석("실패한 배치는 시연 단말처럼 5초 뒤")만 고친다. 주석도 이 결정의 "문서"에 든다고 본다.
- 버린 것: S3도 맞추는 안. 앞 배치가 도는 동안의 429 5번이 약 7초 안에 차서 "수렴 못 함"으로 세어지므로 실패 한도를 다시 정하고 S3를 다시 재야 한다. 지금까지의 S3 결과와도 비교할 수 없게 된다.

**7. 배치 50과 timeout 30초는 그대로다(slo-alert-followups 결정 7).**

### 업무 기능 항목
업무 기능이 아니다. 시연 페이지(정적 파일)의 전송 동작만 바뀐다. 서버 계약(HTTP 오류 code, 응답 모양, 이벤트, 토픽, DB 스키마)은 바뀌지 않는다. 소유 모듈, Command/Query/Event, 정합성 불변식은 해당 없다. 재전송은 같은 배치라 sync의 기존 멱등(`operationId`, `lastAckedSequence`)이 그대로 지킨다. 관측은 서버의 `inbound_rate_limited_total{scope}`가 그대로 센다.

## 제외
- 시연 단말의 토큰 인증([terminal-demo-ui](2026-10-05-terminal-demo-ui.md) 제외 절). 그래서 실서버에서는 limiter 앞에서 401을 받아 실제 429를 보지 못한다. 재전송은 가짜 서버로 확인한다.
- S3의 재시도 변경(결정 6).
- 연결 복구(`online`) 직후 첫 전송의 jitter. 매장 단말이 함께 돌아오는 순간은 매장 bucket(capacity 40, 단말 4대 × 10)이 받는다.
- 기다리는 동안 "지금 동기화" 버튼을 끄거나 남은 시간을 세는 표시.
- 본문 없는 edge(ingress)의 429. code가 없어 결정 2로 다룬다.

## 한계
- selftest는 순수 함수 `retryDelay`만 고정한다. `postSync`·`flush`의 배선과 실제 재전송 간격은 이 PR에서 최종 코드를 가짜 서버로 두 번(skew 0, 50) 잰 것이고, 다시 돌리는 자동 검사는 없다. 이 페이지의 전송 경로를 고치는 PR은 같은 방법(검증 절)으로 다시 잰다.
- 본문을 읽는 도중의 timeout과 JSON이 아닌 실패 본문은 코드로만 확인했다(둘 다 code가 없어 결정 2). 실측의 31초 지연은 응답 헤더 전이다.
- 재시도 timer는 탭 메모리에 있다. 새로고침이나 단말 초기화 직후, 같은 단말의 다른 탭은 `Retry-After` 전에 보낼 수 있다.
- timer가 오프라인일 때 불리면 보내지 않고, 화면에는 "N초 뒤 다시 시도합니다"가 연결이 돌아와 다음 전송을 할 때까지 남는다(예전의 "5초마다" 문구도 같았다). 재전송은 연결 복구나 5초 주기가 이어받아 멈추지 않는다.
- 결정 1의 backlog 배출 시간과 재전송 비용은 계산이고 측정하지 않았다.

## 관련 문서
없음

## 변경 경로
- docs/spec/2026-10-26-terminal-demo-retry-after.md
- src/main/resources/static/terminal/index.html
- docs/wiki/capacity.md
- load-test/s3-sync-batch.js

## 인수 조건
1. `retryDelay`가 다음 값을 돌려주고 selftest가 이를 확인해 "selftest 통과"다. random은 0과 0.9999 두 값으로 본다.
   - `RATE_LIMITED`와 `Retry-After` "1": 1000과 2000
   - `RATE_LIMITED`와 "3600": 3600000과 3601000
   - `RATE_LIMITED`와 `Retry-After` 없음, 빈 문자열, "0", "1.5", "-1", HTTP-date: 5000과 6000
   - code가 없는 오류(네트워크 오류, timeout, `Retry-After` "1"만 있는 오류)와 다른 code(`UNAUTHENTICATED`)에 `Retry-After` "1": 5000과 6000
   - `RATE_LIMITED`와 `Retry-After` "4294968"(2^32ms를 넘음), 400자리 숫자(`Infinity`): 2^31−1(2147483647)
2. 가짜 서버로 잰 같은 배치의 재전송 간격(서버가 연달아 받은 시각의 차)이 대기 범위의 하한 이상이고 상한 + 0.15초(timer 지연과 로컬 왕복의 여유) 이하다. 대기 범위는 `RATE_LIMITED`와 `Retry-After` 1이면 1.0~2.0초, 3이면 3.0~4.0초다. 500, 응답 없이 끊긴 연결, `RATE_LIMITED`가 아닌 429, HTTP-date `Retry-After`는 5.0~6.0초, 30초 timeout은 timeout 뒤 5.0~6.0초(도착 간격 35.0~36.0초)다. 재전송은 페이지의 재시도 timer가 보낸다. timer가 걸려 있는 동안 0.1초마다 `flush`를 불러도 요청이 서버에 닿지 않고, 서버 도착은 timer의 예정 시각보다 이르지 않다. 벽시계가 대기마다 50ms씩 늦어져도 같다. 재전송의 operation이 첫 전송과 같다. 간격의 최소·최대와 예정 시각 대비 도착의 늦음(최소·중앙·최대)을 결과 절에 남긴다. 상한 + 0.15초를 넘는 간격이 있으면 timer가 늦게 불린 것인지 진단 기록으로 가른다.
3. 실패하면 화면 문구가 기다릴 시간을 보인다("서버 전송 실패(HTTP 429). 1.4초 뒤 다시 시도합니다" 꼴).
4. [용량 기준](../wiki/capacity.md)의 단말 sync 계약 문장과 `resilientpos.rate-limit.terminal.capacity` 행의 1,800 op 서술이 결정 1·2·6대로 바뀐다.
5. `./gradlew test` 전체가 통과한다(시연 페이지를 서빙하는 `DemoPageExposureTest` 포함).

## 검증
- selftest(#118과 같은 방법, 헤드리스 Chrome으로 `index.html?selftest`): `"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --enable-logging=stderr --v=0 --dump-dom "file://$PWD/src/main/resources/static/terminal/index.html?selftest"`의 제목이 "selftest 통과"이고 stderr 콘솔에 `selftest 실패`가 없다.
- 재전송 실측: Python 표준 라이브러리 가짜 서버가 `src/main/resources/static`을 서빙하고, `POST /v1/terminals/{terminalId}/sync`에 정해 둔 순서로 응답하며 요청마다 도착 시각과 operation을 남긴다. 서빙하는 `index.html` 끝에 스크립트를 덧붙여 헤드리스 Chrome으로 연다(`?mock` 없이 http로 열어 `postSync`를 탄다). 스크립트는 주문 하나를 넣고, 재시도 timer가 걸려 있는 동안에만 0.1초마다 `flush`를 불러 문을 확인한다. 그 뒤에는 부르지 않으므로 재전송은 페이지가 보낸다. `setTimeout`을 감싸 재시도 timer의 예정 시각(벽시계 + 대기)과 불린 시각을 콘솔에 남기고, `U.syncError`가 바뀔 때마다 남긴다. 간격은 서버가 같은 배치를 연달아 받은 시각의 차이고, 하한 이상·상한 + 0.15초 이하를 통과로 본다. 늦음은 서버 도착 시각(epoch ms) − 예정 시각이고 0 이상이어야 한다. `--skew 50`이면 재시도 timer를 걸 때마다 페이지의 `Date.now()`를 50ms씩 늦춰 벽시계가 늦어지는 경우를 본다. 예정 시각과 timer 호출 시각은 늦추지 않은 원래 `Date.now`로 잡는다. 순서는 `RATE_LIMITED`·`Retry-After` 1 열 번, 3 한 번, 500 다섯 번, 응답 없이 끊긴 연결, 31초 지연(30초 timeout), code가 다른 429, HTTP-date `Retry-After`, 그리고 200이다. 스크립트는 scratchpad에 두고 커밋하지 않는다. 고치기 전 HEAD에서 먼저 돌려 지금 동작(모두 약 5초, jitter 없음)을 본다.
- `./gradlew test`
- `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전에는 `--worktree` 없이 한 번 더.
- compose 스택은 쓰지 않는다. 가짜 서버로 충분하다.

## Wiki 영향
- [용량 기준](../wiki/capacity.md): 트래픽 가정 아래의 단말 sync 계약 문장을 결정 1·2·6대로 고친다. `resilientpos.rate-limit.terminal.capacity` 행의 "5초 뒤 bucket이 다시 찬 채로 이어 보낸다"를 시연 단말(1~2초 뒤 refill만큼)과 S3(5초 뒤)로 나눈다.
- [아키텍처](../wiki/architecture.md): 요청 흐름이 이미 일반 규약(code에 묶음, `Retry-After`에 0~1초 jitter)을 적고 있어 무영향.
- [관측성](../wiki/observability.md), [runbook](../wiki/runbook.md)의 `SyncGapPersistent` 절("단말 재전송 주기(5초)"), `docker/prometheus/alerts.yaml`의 주석("단말 재전송(5초 주기)"): 실패가 없는 동안의 GAP 재전송 주기는 바뀌지 않아 무영향(결정 4). 실패 뒤 대기 중에 GAP 재전송이 미뤄지는 것은 전과 같고, 창 5분에 재전송이 수십 번 드는 근거는 그대로다.
- 볼트 current-state: 일반 규약("`RATE_LIMITED`를 받으면 `Retry-After` 뒤에 같은 요청을 그대로 다시 보낸다")만 적고 시연 단말의 재시도는 적지 않아 무영향.
- 요구사항 영향: 업무 요구사항은 바뀌지 않는다. 시연 단말이 이미 정한 재시도 규약을 따르게 할 뿐이다.

## 분할 계획
- 개발 컨텍스트는 "시연 단말의 sync 재전송 규약" 하나다.
- 따로 둔 것: 시연 단말 토큰 인증, S3 재시도(제외).
- 겹침 확인(2026-10-26, origin/main ee8020e): 열린 PR이 없다. 진행 중인 `feature/dependency-vulnerability-scan`은 `.github/workflows`와 `docs/wiki/development-workflow.md`를 고쳐 이 PR의 경로와 겹치지 않는다. push·PR 생성·머지 직전에 다시 확인하고 먼저 머지된 쪽 위로 rebase한다.
- 다시 확인(2026-10-26): #119(`feature/dependency-vulnerability-scan`)가 `79c75b9`로 머지됐고 열린 PR은 이 PR뿐이다. #119가 고친 경로(`.github/workflows/pr-checks.yml`, `README.md`, `build.gradle.kts`, `docs/wiki/development-workflow.md`, 그 spec)는 이 PR과 겹치지 않아 충돌 없이 rebase했다. Kotlin을 2.4.21로 올린 빌드 위라 `./gradlew test`를 다시 돌린다.
- 공용 헬퍼: main과 열린 PR에 시연 페이지에서 쓸 jitter나 `Retry-After` 해석 헬퍼가 없다. `load-test/lib/api.js`(k6)와 `RedisDistributedLock`(Kotlin)의 jitter는 다른 런타임이라 쓸 수 없다.

## TDD 검증 기록
업무 동작 변경은 아니지만 재전송 시각은 깨지면 조용히 틀리는 로직이라 실패를 먼저 본다. selftest 경계값을 먼저 두고 실패를 본 뒤 구현한다. 배선은 고치기 전 HEAD에서 가짜 서버로 먼저 잰다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 기다릴 시간 계산(인수 조건 1), selftest의 `retryDelay` 경계값 | 검증 절의 헤드리스 Chrome selftest. 제목이 기본 제목 그대로이고 콘솔에 `Uncaught ReferenceError: retryDelay is not defined` | 같은 명령. 제목 "selftest 통과" | 리팩터링 없음. 결함 주입 8종(jitter 제거, `Retry-After` 0 허용, code 무시, 소수 허용, 반올림 제거, 기본 5초를 1초로, code가 없으면 `RATE_LIMITED`로, `RATE_LIMITED`의 5초 경로만 jitter 제거) 모두 제목 "selftest 실패". 뒤의 둘은 리뷰가 짚은 빈틈이라 처음 selftest(`94d3991`)는 통과시켰고, 사례(code 없이 `Retry-After`만 있는 오류, 이상한 값의 random 0.9999)를 더한 뒤 잡았다 |
| 재전송 배선과 간격(인수 조건 2·3), 가짜 서버 실측 | 고치기 전 HEAD(`9e0c5f9`)에서 짧은 순서(`RATE_LIMITED` 세 번, 500, 끊긴 연결, 다른 code의 429, 날짜 형식, 200). `RATE_LIMITED`·`Retry-After` 1 세 번이 모두 5.002~5.008초 뒤라 1.0~2.0초 기대에 실패. 나머지도 5.003~5.008초로 jitter가 없었다 | 전체 순서 21요청 PASS(결과 절). 화면 문구도 같은 실행에서 확인 | 리팩터링 없음 |
| 벽시계가 늦어질 때의 재전송(결정 4 범위 조정), 가짜 서버 실측 `--skew 50` | 벽시계 문(`e8ff0c6`)에서 `--quick --skew 50`. timer는 1~6ms 늦게 제때 불렸지만 문에 걸려 재전송이 4.908~10.003초 뒤라 간격 7개 모두 실패(예정 대비 3.3~5.0초 늦음) | 재시도 timer 문으로 같은 명령 PASS(예정 대비 3.5~9.7ms). 전체 순서 `--skew 50`도 PASS | 리팩터링 없음 |
| 대기 상한(결정 4 범위 조정, 인수 조건 1), selftest의 "4294968"·400자리 사례 | 사례를 먼저 더하고 selftest. 제목 "selftest 실패", 콘솔 `selftest 실패: 재전송 대기` | 상한을 넣고 같은 명령. 제목 "selftest 통과". 짧은 순서 실측도 PASS(예정 대비 2.3~10.3ms) | 리팩터링 없음 |

## 결과
- 시연 단말: `postSync`가 실패 응답의 `error.errorCode`와 `Retry-After`를 오류에 싣는다. `flush`는 `retryDelay(e, Math.random)` ms짜리 재시도 timer(`U.retryTimer`)를 걸고, timer가 걸려 있는 동안 모든 전송 계기를 막는다. 화면 문구는 기다릴 시간을 보이고, `setInterval` 5초의 주석을 GAP 재전송과 놓친 전송으로 고쳤다.
- 범위 조정 셋은 구현 전에 이 spec에 먼저 적었다. S3 주석(결정 6), 재시도 timer 문과 대기 상한(결정 4)이다.
- selftest: 헤드리스 Chrome에서 "selftest 통과". 결함 주입 8종 모두 실패(TDD 검증 기록).
- 검증: rebase 전 origin/main ee8020e 위에서 `./gradlew test` 전체 934개가 세 번 통과했다(실패 0, `DemoPageHiddenByDefaultTest`·`DemoPageOpenInDemoProfileTest` 포함). 첫 구현, 재시도 timer 문, 대기 상한 커밋에서다. #119(`79c75b9`, Kotlin 2.4.21) 위로 rebase한 뒤 다시 돌려 934개가 통과했다(실패 0). `check_pr.py --base origin/main --worktree` PASS.
- multi-review(Claude 87점, Codex는 CRITICAL·HIGH 없이 MEDIUM 1·LOW 1, Gemini는 CLI가 없어 미실행)에서 반영한 것:
  - selftest 빈틈 둘: 이상한 `Retry-After`를 random 0으로만 봄, code 없이 `Retry-After`만 있는 오류를 보지 않음(Codex·Claude).
  - 인수 조건 2의 측정 허용치와 timeout의 도착 간격 표기(Codex·Claude).
  - 실측이 재전송을 페이지 timer가 보냈는지 가르지 못하던 것(Claude). 도구가 대기 중에만 `flush`를 부르고 timer의 예정·호출 시각을 남기게 바꿨다. 그 실측에서 벽시계 문의 약점이 드러나 결정 4를 범위 조정했다.
  - 문구: capacity.md의 "그 밖의 실패" 범위와 jitter가 두 경로에 다 붙는다는 것, RFC delta-seconds 표현, S3의 "규약 안", catch 주석, S3 주석(Claude).
  - 한계 추가: 탭 메모리의 재시도 timer, 본문 읽기 중 timeout(Claude·Codex).

### 재전송 실측(2026-10-26)
검증 절의 가짜 서버와 헤드리스 Chrome(macOS, 로컬 루프백)으로 최종 코드를 전체 순서로 두 번 쟀다. skew 0과 `--skew 50`(재시도 timer를 걸 때마다 페이지의 벽시계가 50ms씩 늦어짐)이다. 간격은 서버가 같은 배치를 연달아 받은 시각의 차이다.

| 응답 | 횟수 | 대기 범위 | skew 0 최소·최대 | skew 50 최소·최대 |
|---|---|---|---|---|
| 429 `RATE_LIMITED`, `Retry-After: 1` | 10 | 1.0~2.0초 | 1.059·1.901초 | 1.180·1.883초 |
| 429 `RATE_LIMITED`, `Retry-After: 3` | 1 | 3.0~4.0초 | 3.475초 | 3.978초 |
| 500 | 5 | 5.0~6.0초 | 5.207·5.896초 | 5.109·5.894초 |
| 응답 없이 끊긴 연결 | 1 | 5.0~6.0초 | 5.664초 | 5.331초 |
| 31초 지연(30초 timeout) | 1 | 도착 간격 35.0~36.0초 | 35.220초 | 35.577초 |
| 429, code `OTHER`, `Retry-After: 1` | 1 | 5.0~6.0초 | 5.709초 | 5.738초 |
| 429 `RATE_LIMITED`, `Retry-After`가 HTTP-date | 1 | 5.0~6.0초 | 5.474초 | 5.916초 |

- 예정 시각 대비 서버 도착은 skew 0에서 2.4~10.6ms(중앙 6.0ms), skew 50에서 2.8~25.8ms(중앙 7.4ms)다. timer가 불린 늦음은 skew 0에서 1~9ms(중앙 3ms), skew 50에서 0~6ms(중앙 3ms)다. timer가 걸려 있는 동안 0.1초마다 `flush`를 불렀지만 예정보다 일찍 닿은 요청은 없었다. 두 실행 모두 operation이 첫 전송과 같았고, 200 뒤 1초 동안 더 온 요청은 없었다.
- 벽시계 문(`e8ff0c6`)일 때: skew 없는 전체 순서 네 번 가운데 두 번에서 한 요청씩 122ms·232ms 늦었다. 232ms는 `Retry-After: 3` 뒤 4.184초라 상한 + 0.15초를 넘었다. timer 호출 시각을 남긴 진단 두 번(timer 40회)에서는 문에 걸린 timer가 없었지만, 2회는 `Date.now()`가 `retryAt`과 같은 순간에 불렸다. 그래서 두 이상치가 timer 지연이었는지 문에 걸린 것이었는지는 가르지 못했다. `--skew 50`에서는 간격 7개가 모두 실패했다(TDD 검증 기록).
- 화면 문구: "서버 전송 실패(HTTP 429). 1.3초 뒤 다시 시도합니다"가 1.276초 뒤 재전송과 맞았다. 네트워크 오류는 "서버 전송 실패(Failed to fetch). 5.7초 뒤…", timeout은 "서버 전송 실패(signal timed out). 5.2초 뒤…"이고, 200 뒤에는 문구가 비었다.
