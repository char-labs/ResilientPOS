# PR spec: 주문·결제 상태 폴링 계약과 조회 빈도 bucket을 둔다

## 목적
단말은 checkout 뒤 결제 결과를 알려면 다시 물어야 하는데, 언제 얼마나 자주 묻고 언제 멈추는지 정한 계약이 없다. 게다가 새 단말 GET(주문 단건, 결제 시도, 메뉴)은 쓰기와 같은 단말·매장 bucket을 써서 폴링이 sync와 checkout을 429로 막을 수 있다. 예를 들어 단말 bucket(초당 2개)에서 대기 주문 두 건을 2초마다 물으면 초당 1개를 쓰고, 그때 오프라인에서 돌아와 backlog를 보내면 쓸 몫이 초당 1개뿐이다. 점주 대조 case 목록은 최신순 100건에서 잘리는데 잘렸다는 신호가 없다. 시연 단말은 결제 결과를 보여 주지 못한다.

기대 결과:
- 단말이 따를 폴링 계약(부를 API, 상태별 간격, 멈추는 조건, 실패 시 대기)이 정해지고, Phase 4가 이 값을 부하 모델에 넣는다.
- `/v1/**`의 GET·HEAD는 따로 둔 조회 bucket만 쓰고 쓰기 bucket을 쓰지 않는다. 폴링이 쓰기를 429로 막지 않는다.
- 점주 대조 case 목록이 잘렸는지 응답으로 안다.
- 시연 포스가 주문 현황 화면에 결제 상태를 보인다.

조회 영역은 Phase 넷이다. 1 주문 조회(#122 머지), 2 상태 가시성과 폴링 계약(이 PR), 3 메뉴(#121 머지), 4 측정과 튜닝(`perf/query-measurement`, 동시 진행). Phase마다 PR 하나다. GitHub 이슈는 없고 사용자가 직접 정했다.

## 범위
소유 모듈은 `terminal`(조회 bucket)과 `reconciliation`(잘림 표시)이다. `order`·`payment`는 바꾸지 않는다. 주문 상태와 결제 상태를 합치는 곳은 클라이언트이고, 두 API를 각각 부른다(README 모듈과 의존: `order`와 `payment`는 서로 의존하지 않는다). 시연 단말(`static/terminal/index.html`)이 계약의 첫 클라이언트다.

**결정 1: 폴링은 결제 시도 조회 위주로 하고, 주문 조회는 확인할 때만 부른다(사용자 결정).**
- 문제: 결제 결과는 결제 시도에만 있고(거절 뒤에도 주문은 `CHECKOUT_REQUESTED`), PAID와 "주문 없음"은 주문에만 있다.
- 수단: 폴링 한 번에 `GET /v1/orders/{orderId}/payment-attempts` 하나를 부르고 `kind`가 `AUTHORIZATION`인 항목 중 목록의 마지막(서버가 생성 순으로 준다)의 `status`로 판단한다. 응답에 `id`가 없어 목록 순서를 쓴다. 취소 시도(`CANCELLATION`)의 `APPROVED`를 결제 승인으로 읽지 않게 한정한다(`PaymentApprovedListener`도 같은 기준으로 거른다). `GET /v1/orders/{orderId}`는 다음 두 경우에만 부른다.
  - 마지막 시도가 `APPROVED`이면 주문이 `PAID`가 됐는지 확인한다.
  - 시도 목록이 비어 있거나 `REQUESTED`인 채로 대기 한도를 넘으면 `ORDER_NOT_FOUND`(주문 없음)와 그 밖(전달이 늦음)을 가른다. 결제 시도 조회는 없는 주문과 아직 시도가 없는 주문 모두 빈 목록이라 그것만으로는 가를 수 없다([payment-status-query](2026-10-06-payment-status-query.md)).
- 버린 것: 매번 두 API를 다 부르는 안. 분기는 단순하지만 요청이 2배이고 주문 조회는 문장이 2개라 DB 부하가 3배다.

**결정 2: 간격과 멈추는 조건은 상태별 고정값이다(사용자 결정).**

간격은 "빠른 구간 2초 + 0~1초 jitter"와 "느린 구간 30초 + 0~1초 jitter" 두 가지다. 한도는 서버가 정상으로 늦어질 수 있는 시간에 맞췄다. 주문의 PAID 반영 consumer는 경합이면 1초부터 2배씩 5회, 최대 약 31초 재시도하고(`resilientpos.kafka.retry`, `KafkaConfiguration`의 `DefaultErrorHandler`), 외부화 전송이 끊긴 발행 기록은 `stale-after` 5분 + 재전송 주기 1분 뒤에 다시 나간다. 그래서 빠른 구간은 35초(재시도 31초를 덮는다), 느린 구간은 7분(재전송 5~6분을 덮는다)까지다.

| 마지막으로 본 상태 | 다음 요청 | 멈추는 조건과 화면 |
|---|---|---|
| 시도 없음, `REQUESTED` | 결제 시도 조회. 폴링 시작 뒤 35초까지 빠른 구간, 7분까지 느린 구간 | 7분에 닿으면 주문 조회 한 번. `error.errorCode`가 `ORDER_NOT_FOUND`면 "주문 없음", 그 밖이면 "전달 지연"으로 멈춘다(사용자 결정) |
| `UNKNOWN` | 결제 시도 조회, 느린 구간 | 폴링 시작 뒤 65분(서버 조회 60회 약 1시간 + 여유)에 닿으면 "대조 대기"로 멈춘다. 그 뒤로는 점주가 대조 case를 해소할 때까지 바뀌지 않는다 |
| `DECLINED` | 없음 | 바로 "거절"로 멈춘다. 재결제 뒤에는 새 시도로 처음부터 다시 시작한다 |
| `APPROVED` | 화면은 바로 "결제 승인"이다(돈은 승인됐다). 주문 조회. `APPROVED`를 처음 본 뒤 35초까지 빠른 구간, 7분까지 느린 구간 | `PAID`면 "결제 완료"로 멈춘다. 7분에 닿으면 "반영 지연"으로 멈춘다(사용자 결정). 진짜 갈라짐(금액 불일치 DLT)은 서버의 DLT 알림이 잡는다 |

- 시작: 단말이 `orderId`를 안 때다. sync로 보낸 주문은 `REQUEST_CHECKOUT` 항목 ack의 `orderId`, HTTP checkout은 응답이다. `orderId`가 없으면 폴링하지 않는다. 첫 요청은 1초 + 0~1초 jitter 뒤에 보낸다(결제 시도는 커밋 뒤 Kafka 한 홉을 거쳐 생기므로 바로 물으면 거의 항상 빈 목록이다).
- 동시에 폴링하는 주문 수(사용자 결정): 단말 하나에서 빠른 구간에 있는 주문은 최대 3건, 느린 구간에 있는 주문은 최대 6건이다. 자리가 없는 주문은 대기열에서 기다리고, 자리가 나면 가장 최근 주문부터 시작한다(사용자 결정, 지금 창구의 결제가 backlog 뒤로 밀리지 않게). 한도(35초·7분·65분)는 그 주문의 폴링이 실제로 시작된 때부터 잰다. 빠른 구간 3건과 느린 구간 6건을 합쳐도 초당 최대 3 × 0.5 + 6 ÷ 30 = 1.7요청이라 조회 refill 2 안에 든다. 느린 구간 자리를 따로 두는 것은 `UNKNOWN` 주문 몇 건이 65분 동안 자리를 쥐어 새 주문을 막지 않게 하려는 것이다.
- 실패: 응답의 `error.errorCode`가 `RATE_LIMITED`이고 `Retry-After`가 1 이상의 정수면 그 초에 0~1초 jitter를 더해 기다린다. 그 밖(네트워크 오류, timeout, 5xx, 본문이나 code가 없음)은 5초에 0~1초 jitter다. 같은 요청을 다시 보낸다. 요청 timeout은 10초다(메뉴와 같다). 재전송 규약을 HTTP 상태가 아니라 code에 묶는 것은 [inbound-rate-limit](2026-10-21-inbound-rate-limit.md) 결정 5, 대기 계산은 [terminal-demo-retry-after](2026-10-26-terminal-demo-retry-after.md)와 같다. "주문 없음"도 HTTP 404가 아니라 `ORDER_NOT_FOUND` code로 판정한다. 본문 없는 404(ingress, 경로 오류)를 주문 없음으로 확정하지 않게 하려는 것이다. 실패한 시간도 한도에 든다. 한도에서 하는 주문 조회가 실패하면 같은 규칙으로 다시 보내고 3번 실패하면 그 행의 지연 표시("전달 지연"·"반영 지연")로 멈춘다.
- 끝난 상태로 남기는 것은 "결제 완료"와 "거절"뿐이다. "전달 지연"·"반영 지연"·"대조 대기"·"주문 없음"은 단말을 다시 열면 처음부터 다시 묻는다. 늦은 수렴(늦게 온 `PAID`, 대조 해소)을 사람 손 없이 다시 볼 수 있게 하려는 것이다.
- 바뀌었는지는 주문의 `status`와 `version`, 시도의 `status`로 본다. 경과 시간은 단말의 단조 시계로 잰다. 응답 필드를 더하지 않는다(결정 5).
- 부하 모델(Phase 4가 쓰는 값, 계산이며 측정 전이다).
  - 정상 결제 1건(승인과 PAID 반영이 몇 초 안): 결제 시도 조회 1~2번과 주문 조회 1번, 약 3요청이다. 피크(단말당 판매 초당 0.5건)에서 단말당 조회는 약 초당 1.5개다.
  - 오래 걸리는 주문 1건: 빠른 구간 35초에 약 14요청(평균 2.5초 간격), 느린 구간에 30.5초마다 1요청이다. 7분이면 약 26요청, `UNKNOWN` 65분이면 약 140요청이다. `APPROVED`를 빠른 구간 끝에 처음 보면 그 뒤로 주문 조회(2문장)가 같은 모양으로 이어지므로 한 주문의 폴링은 최대 약 14분이다.
  - 단말 하나의 상한: 동시 자리로 초당 1.7요청이다. backlog N건의 결제 상태가 다 보이는 데는 약 N × 3 ÷ 1.5초가 걸린다(900건이면 약 30분, 정상 결제 기준).
  - 모두 조회 bucket을 쓰고 쓰기 bucket을 쓰지 않는다. Phase 4는 폴링을 켠 피크에서 `/v1` RPS와 Hikari pending, 결제부터 PAID까지의 p99를 재고 한도·간격·조회 bucket 값을 고친다.
- 버린 것: 1→2→4…최대 30초의 지수 backoff. 상태를 보지 않아 단순하지만 `UNKNOWN`과 `REQUESTED`를 같게 다루고, 첫 몇 초에 요청이 몰려 부하 모델이 시간에 따라 달라진다. 처음 합의한 60초·30초 한도는 설계 검토에서 버렸다. Kafka 재시도(31초)와 외부화 재전송(5~6분) 같은 정상 지연을 "확인 필요"로 끝내 계산원이 승인된 결제를 다시 받을 수 있었고(이중 결제), backlog에서 429를 기다리다 한도에 닿은 주문을 잘못 분류했다.
- 치르는 것: 오래 걸리는 주문 하나가 7분(`UNKNOWN`은 65분) 동안 요청을 낸다. backlog의 결제 상태는 천천히 보인다. 계약은 단말 화면의 규약이라 서버가 강제하지 않는다. 계약을 어긴 단말은 조회 bucket이 묶는다.

**결정 3: GET·HEAD는 조회 bucket을 따로 쓴다(사용자 결정).**
- 수단: `TerminalAuthenticationInterceptor`가 인증 뒤 GET·HEAD면 `rateLimiter.acquireRead(terminal)`을, 그 밖이면 지금의 `rateLimiter.acquire(terminal)`을 부른다. 조회 bucket도 단말과 매장 둘이고 같은 Lua 스크립트(`ACQUIRE`)를 키와 값만 바꿔 부른다. 키는 `rate-limit:{storeId}:read:terminal:terminalId`와 `rate-limit:{storeId}:read:store`다. 같은 hash tag라 같은 slot이다. 쓰기 키는 그대로다(배포 중 bucket이 초기화되지 않는다).
- GET·HEAD 판별은 동시 상한이 쓰는 `READ_METHODS`와 같은 집합이다. 대상 GET은 `GET /v1/orders/{orderId}`, `GET /v1/orders/{orderId}/payment-attempts`, `GET /v1/menu`다.
- 설정은 `resilientpos.rate-limit.read.terminal.capacity`·`refill-per-second`, `resilientpos.rate-limit.read.store.capacity`·`refill-per-second`다. 처음 값은 쓰기와 같은 단말 10·초당 2, 매장 40·초당 8이다. 단말 refill 2는 결정 2의 단말 상한(동시 자리로 초당 1.7)과 5분마다의 메뉴 조회를 받는다. 피크 정상 흐름(단말당 약 초당 1.5)은 refill의 75%다. 시연 단말이나 k6에서 이 값을 잰 적이 없어 Phase 4가 실측으로 고친다. 검증 규칙(capacity 1 이상 2^53 이하, refill은 0보다 큰 유한값, 가득 차는 시간 하루 이하)은 쓰기 값과 같고 기동에서 막는다.
- 기동 검사(결정 4 of inbound-rate-limit)는 쓰기 값으로 한 번만 돌린다. 스크립트와 권한·slot 규칙이 키 접두어 `rate-limit:`까지 같고 값의 검증은 생성에서 끝나서, 조회 값으로 한 번 더 돌려도 새로 잡는 실패가 없다.
- fail-open, 1초 창과 탐침, 로그 간격은 쓰기와 같은 상태를 나눠 쓴다. Redis는 하나라 창도 하나다. 나누면 탐침만 두 배가 된다.
- 구현: `acquire` 본문(창·탐침·결과 해석·scope)을 복사하지 않는다. 키 목록·ARGV·scope 목록을 받는 private 함수 하나를 `acquire`와 `acquireRead`가 함께 부른다. 거절 bucket 번호를 scope로 바꾸는 표가 묶음마다 다르다. 조회 키도 쓰기 키와 같은 함수(접두어 인자)로 만들어, 기동 검사를 쓰기 값으로 한 번만 해도 키 형식까지 덮는다. limiter를 인터셉터만 부른다는 구조 테스트에 `acquireRead`를 더한다.
- 관측: 거절 counter `inbound_rate_limited_total`의 `scope`에 `read_terminal`·`read_store`를 더하고 기동할 때 0으로 등록한다. 새 tag를 두지 않으므로 기존 매장 거절 알림(`scope="store"`)은 쓰기 거절만 본다. 조회 거절은 판매를 막지 않아 알림을 두지 않는다. 거절 로그도 scope별 10초 한 줄이다. 응답은 지금과 같은 429 `RATE_LIMITED`와 `Retry-After`다(새 오류 code 없음).
- 버린 것: bucket을 하나로 두고 간격만 정하는 안. 코드는 그대로지만 대기 주문이 쌓이거나 backlog를 보내는 동안 폴링이 쓰기를 막는 일이 남는다. 매장 bucket만 따로 두는 안. 조회 폭주 단말 하나가 같은 매장 정상 단말의 폴링을 막는다(inbound-rate-limit 결정 1과 같은 이유).
- 치르는 것: 설정 값이 넷 늘고 `/v1` 요청 하나의 Redis 왕복은 그대로 하나다. 단말 하나가 내는 총 요청은 쓰기와 조회 bucket 합만큼이라 이전보다 최대 2배다. 조회는 행 잠금을 잡지 않아 커넥션을 쥔 대기가 없다.

**결정 4: GET에는 동시 상한을 두지 않는다(사용자 결정).** 지금처럼 GET·HEAD는 `TerminalInFlightLimiter` 자리를 잡지 않는다. 한꺼번에 보낼 수 있는 GET은 조회 bucket capacity(10)가 묶고, 세 GET은 모두 인덱스로 끝나는 ms 단위 읽기라(주문 단건 2문장, 결제 시도 1문장, 메뉴 1문장) 커넥션을 쥐고 기다리지 않는다. 남는 장치는 쿼리 상한 10초다. 느린 GET이 실측되면 Phase 4가 다시 본다.

**결정 5: 주문·결제 시도 응답에 필드를 더하지 않는다(사용자 결정).** `updatedAt`과 결제 시도 필드 추가는 폴링에 필요하지 않다. "아직"과 "없음"은 결정 1의 주문 조회로, 경과 시간은 단말 시계로, 변경은 `status`·`version`으로 본다. `t_order`·`t_payment_attempt`에 `updated_at`이 이미 있어 화면이 서버 기준 경과 시간(예: `UNKNOWN` 40분째)을 보여야 할 때 스키마 변경 없이 더한다.

**결정 6: 점주 대조 case 목록의 `data`를 `{items, truncated}`로 바꾼다(사용자 결정).**
- `GET /owner/v1/stores/{storeId}/reconciliation-cases`의 `data`가 배열에서 `items`(지금 항목 그대로)와 `truncated`(boolean)를 가진 객체가 된다. 점주 주문 목록(`{items, nextCursor}`)과 모양을 맞춘다. 이 API를 부르는 클라이언트가 아직 없어 지금 바꾸는 값이 가장 싸다.
- 조회가 `REVIEWABLE_LIMIT + 1`(101)건을 읽어 101건이면 앞 100건과 `truncated: true`, 아니면 `false`다. 정렬(`id` 내림차순)과 인덱스는 그대로다. 결과 타입은 주문 목록 선례처럼 조회 안의 `ReconciliationQuery.FindReviewable.Result(items, truncated)`다. 공용 페이지 타입은 만들지 않는다.
- 버린 것: 응답 헤더(`X-Truncated`). 본문 계약은 그대로지만 클라이언트가 헤더를 읽어야 하고 저장소에 상태를 헤더로 알리는 선례가 Retry-After·ETag뿐이다.
- 페이지는 여전히 없다. 잘림이 실제로 보이면 `nextCursor`를 같은 객체에 더한다.
- 치르는 것: 최신순이라 잘리면 가장 오래 기다린 case가 보이지 않는다. `truncated`는 그 사실을 알릴 뿐 보이게 하지는 않는다. 정렬은 [payment-reconciliation-case](2026-10-20-payment-reconciliation-case.md)의 사용자 결정 그대로다.

**결정 7: 시연 포스는 결제 상태를 보이기만 한다(사용자 결정).**
- 포스가 ack로 `orderId`를 받은 주문(`SYNCED`)을 결정 1·2대로 폴링하고 주문 현황 화면에 결제 상태(결제 확인 중 / 결제 승인 / 결제 완료 / 거절 / 결과 확인 중(`UNKNOWN`) / 대조 대기 / 전달 지연 / 반영 지연 / 주문 없음)를 보인다. 재결제 버튼은 두지 않는다. 모의 서버(`?mock`)의 ack는 `orderId`가 없어 폴링하지 않는다.
- 다음 요청 판단은 순수 함수 하나(지금 단계·경과 시간·응답 → 다음 행동과 대기)로 두고 `?selftest`가 표의 칸마다 확인한다. 동시 자리(빠른 3·느린 6)와 최근 주문 우선은 그 함수를 부르는 대기열이 지킨다. "결제 완료"와 "거절"만 주문과 함께 저장해 새로고침 뒤 다시 묻지 않는다. 나머지는 새로고침 뒤 처음부터 다시 잰다.
- 실패 대기는 sync의 `retryDelay`를 그대로 쓴다.
- 시연 단말은 아직 토큰이 없어 실서버에서는 401이다. 401은 "그 밖의 실패"라 5초마다 다시 묻고 한도에서 지연 표시로 멈춘다. 동작은 가짜 서버로 확인한다(검증 절).

계약: 공개 HTTP 계약 하나가 바뀐다. 대조 case 목록의 `data`가 배열에서 `{items, truncated}`로 바뀐다. `ReconciliationQueryFacade.findReviewable`의 반환 타입이 `ReconciliationQuery.FindReviewable.Result`가 된다. 새 오류 code·enum 이름·이벤트·스키마는 없다. 조회 bucket 설정 넷이 더해진다. 폴링 계약은 클라이언트 규약이고 서버 코드가 아니다.

불변식: GET·HEAD는 쓰기 bucket을 쓰지 않고 쓰기는 조회 bucket을 쓰지 않는다. 한 요청은 두 bucket 묶음 중 하나만 지난다. 대조 case 목록의 `truncated`는 미해소 case가 100건보다 많을 때만 참이다.

복구: 조회는 상태를 바꾸지 않아 다시 부르면 된다. 조회 bucket 거절은 `Retry-After`가 지나면 풀린다. Redis 장애에서는 쓰기와 같이 제한 없이 통과한다.

관측성: 결정 3의 counter scope 둘과 거절 로그. 새 알림은 없다.

## 제외
- 캐시, 조회 섞은 k6, GET 지연 알림, 조회 bucket 값의 실측. Phase 4다.
- GET 동시 상한. 결정 4.
- 주문·결제 시도 응답의 `updatedAt`과 그 밖의 필드. 결정 5. 서버 기준 경과 시간을 보여야 할 화면이 생기면 더한다.
- 시연 단말의 재결제 버튼과 토큰 연동. 결정 7.
- 대조 case 목록의 페이지. 결정 6.
- 서버가 폴링 간격을 알려 주는 것(응답 헤더나 필드로 다음 간격을 내리는 것). 간격이 상태에 따라 고정이라 클라이언트가 안다. Phase 4 측정에서 서버가 간격을 늦춰야 할 근거가 나오면 다시 본다.
- 단말 주문 목록, 점주 주문 상세, 메뉴 항목별 CRUD.
- push(SSE·WebSocket). 폴링으로 충분한지는 Phase 4가 본다.
- 점주 주문 목록 cursor를 매장·필터에 묶는 것([order-query](2026-10-26-order-query.md) 결과 절이 이 Phase로 넘김). 폴링 계약은 단말의 단건 조회만 쓰고 점주 목록을 폴링하지 않는다. 남의 cursor를 써도 누출은 없고 위치만 바뀐다는 그 spec의 판단이 그대로다. 점주 화면이 목록을 폴링하게 되면 그때 묶는다.
- 목록 쿼리만의 짧은 timeout(order-query 제외 절). 목록은 keyset과 상한 50으로 묶였고 실측이 모두 1ms 아래다. 측정은 Phase 4다.
- 조회 bucket 매장 거절(`read_store`)의 알림. 판매를 막지 않는다. 대신 runbook에 확인 쿼리를 둔다.

## 관련 문서
없음. 정본은 [README Engineering Principles](../../README.md#engineering-principles)의 모듈 경계와 CQRS 규약, [도메인 모델](../wiki/domain-model.md)이다. 앞 PR은 [order-query](2026-10-26-order-query.md)(이 PR로 넘긴 일은 그 제외 절), [payment-status-query](2026-10-06-payment-status-query.md), [inbound-rate-limit](2026-10-21-inbound-rate-limit.md), [terminal-concurrency-limit](2026-10-23-terminal-concurrency-limit.md), [terminal-demo-retry-after](2026-10-26-terminal-demo-retry-after.md), [menu-catalog](2026-10-26-menu-catalog.md), [payment-reconciliation-case](2026-10-20-payment-reconciliation-case.md)다.

## 변경 경로
- src/main/kotlin/com/resilientpos/terminal/**
- src/main/kotlin/com/resilientpos/reconciliation/**
- src/main/resources/application.yaml
- src/main/resources/static/terminal/index.html
- src/test/kotlin/com/resilientpos/terminal/**
- src/test/kotlin/com/resilientpos/reconciliation/**
- src/test/resources/**
- docs/wiki/architecture.md
- docs/wiki/capacity.md
- docs/wiki/observability.md
- docs/wiki/runbook.md
- docs/spec/2026-11-02-order-status-polling.md

## 인수 조건
1. 인증된 GET·HEAD는 조회 bucket만, 그 밖의 요청은 쓰기 bucket만 차감한다. 쓰기 bucket을 다 쓴 단말의 GET은 통과하고, 조회 bucket을 다 쓴 단말의 POST는 통과한다.
2. 조회 bucket도 단말과 매장 둘이고 한쪽이 거절하면 어느 쪽도 차감하지 않는다. 거절은 429 `RATE_LIMITED`와 `Retry-After`다. 조회 키 둘은 같은 Redis Cluster slot이다.
3. 조회 bucket 거절은 `inbound_rate_limited_total`의 `scope`가 `read_terminal`·`read_store`로 오르고, 두 시계열은 기동할 때 0으로 있다. 쓰기 거절의 scope는 그대로다.
4. 조회 bucket 설정이 검증 규칙을 어기면 기동하지 못하고, 메시지에 어느 설정인지 나온다.
5. Redis 장애에서 조회 요청도 제한 없이 통과한다.
6. 대조 case 목록은 미해소 case가 100건이면 100건과 `truncated: false`, 101건이면 최신 100건과 `truncated: true`를 돌려준다. REST Docs 스니펫에 `items`와 `truncated`가 나온다.
7. 시연 포스의 `?selftest`가 결정 2 표의 칸마다 다음 행동과 대기를 확인하고 통과한다(빠른·느린 구간 경계 35초·7분·65분, `CANCELLATION` 항목 무시, `ORDER_NOT_FOUND` code와 본문 없는 404의 구분, 동시 자리와 최근 주문 우선). 가짜 서버로 연 화면에서 결제 승인·결제 완료·거절·반영 지연이 보이고, `RATE_LIMITED` 응답 뒤 `Retry-After`초만큼 기다려 다시 묻는다.
8. `./gradlew test` 전체와 `python3 scripts/check_pr.py --base origin/main`이 통과한다.

### Checkpoint와 검증 명령
| Checkpoint | 검증 명령 | 의존 |
|---|---|---|
| 조회 bucket (1~5) | `./gradlew test --tests 'com.resilientpos.terminal.*'` | 없음 |
| 대조 잘림 (6) | `./gradlew test --tests 'com.resilientpos.reconciliation.*'` | 없음 |
| 시연 단말 (7) | `?selftest` 헤드리스 Chrome, 가짜 서버 | 없음 |
| 전체 (8) | `./gradlew test` · `python3 scripts/check_pr.py --base origin/main` | 모두 |

## 검증
- 정상: GET과 POST가 서로의 bucket을 비우지 않는다. 조회 bucket capacity만큼 연달아 통과하고 다음 GET이 거절된다. 100건·101건 경계의 `truncated`.
- 실패: 조회 bucket 거절의 429·`Retry-After`·scope, 잘못된 조회 설정의 기동 실패, Redis 장애의 통과.
- 동작을 지워 테스트가 빨개지는지 본다: 인터셉터의 GET 분기(모두 쓰기 bucket으로), 조회 키의 hash tag, 101건 읽기(100건만 읽기).
- 시연 단말: #120·#121과 같은 방식이다. Python 표준 라이브러리 가짜 서버가 `src/main/resources/static`을 서빙하고 sync ack에 `orderId`를 실은 뒤 결제 시도 조회·주문 조회에 정해 둔 순서로 답한다(빈 목록 → `APPROVED` → 주문 `PAID`, `DECLINED`, `APPROVED` 뒤 계속 `CHECKOUT_REQUESTED`, `RATE_LIMITED`와 `Retry-After: 3`). 헤드리스 Chrome으로 `?mock` 없이 열어 화면 표시와 요청 시각 간격을 남긴다. 결함(429 대기 무시, 끝난 상태에서 계속 묻기)을 넣어 판별되는지 확인한다.
- 통합 테스트는 Testcontainers의 PostgreSQL·Redis·Kafka를 쓴다(Docker는 OrbStack).

## Wiki 영향
- [아키텍처](../wiki/architecture.md) §3.2: 결제 결과를 단말이 아는 길(폴링 계약 요약과 이 spec 링크)을 bullet로 더한다. §1의 인증·상한 단계에 조회 bucket을 더한다. `docs/wiki-stale-cleanup`이 고치는 절(§3.3 앞뒤, §5·§6, reconciliation 절)과 겹치지 않게 한다.
- [용량 기준](../wiki/capacity.md) "단말·매장 rate limit 권장값": 조회 bucket 네 값과 "실측 전 처음 값" 표시, 폴링 부하 모델(결정 2)을 더한다.
- [관측성](../wiki/observability.md) "단말 빈도 상한": scope `read_terminal`·`read_store`를 더하고 "셋 다 기동할 때 0으로"를 다섯으로 고친다.
- [runbook](../wiki/runbook.md): 조회 bucket 매장 거절이 이어질 때(매장 전체의 결제 표시가 멈춤) 확인할 쿼리와 먼저 볼 것을 더한다.
- 현재 상태(`docs/wiki/current-state.md`, 볼트)는 볼트에서 갱신한다(조회 bucket, 폴링 계약, 대조 목록 모양).
- Kafka·Modulith 이벤트 작업이 아니라 Modulith·Kafka 키워드는 바꾸지 않는다.
- 요구사항 변화: 단말이 결제 결과를 정해진 간격으로 묻고 정해진 조건에서 멈춘다. 조회와 쓰기의 빈도 상한이 나뉜다. 점주 대조 목록이 잘림을 알린다. 정합성 요구는 그대로다.

## 분할 계획
개발 컨텍스트는 하나다. 단말이 상태를 다시 묻는 계약과 그 계약이 쓰기를 막지 않게 하는 bucket 분리, 그 계약의 첫 클라이언트(시연 단말)를 함께 둔다. 대조 목록 잘림 표시는 Phase 1이 넘긴 상태 가시성 항목이라 함께 둔다. 측정과 값 조정은 Phase 4다.

작업 시작 시점은 origin/main fc36227이다. spec 첫 커밋 때 열린 PR은 #124(`docs/wiki-stale-cleanup`) 하나이고 `perf/query-measurement`는 아직 push되지 않았다. #124는 README와 `docs/wiki/architecture.md`·`domain-model.md` 등을 고친다. 이 PR은 `architecture.md`의 다른 절만 고치고 `domain-model.md`·README는 고치지 않는다. `perf/query-measurement`는 load-test·알림 규칙·측정 문서를 고친다. 이 PR은 `alerts.yaml`을 고치지 않는다(결정 3). `capacity.md`가 겹칠 수 있다. `load-test/s4-slow-provider.js`는 결제 시도를 0.5초 간격으로 묻고 429를 빈 목록으로 다뤄, 조회 bucket 아래에서는 429가 "미승인"으로 섞인다. Phase 4에 계약 간격과 429 처리(Retry-After)를 맞추라고 PR 본문에 적는다. 마이그레이션은 없다. push·PR 생성·머지 직전에 경로 겹침을 다시 확인하고 먼저 머지된 쪽에 맞춰 rebase한다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 조회 bucket 분리 (인수 1~5). 통합 `RedisTerminalRateLimiterIntegrationTest`의 `쓰기 bucket을 다 쓴 단말의 조회는 통과하고 조회 bucket을 다 쓴 단말의 쓰기는 통과한다`, `조회 bucket도 단말과 매장 둘이고 한쪽이 거절하면 어느 쪽도 차감하지 않는다`, `조회 키 둘은 같은 Cluster slot에 있고 쓰기 키를 건드리지 않으며 TTL이 걸린다`. 장애 `RedisTerminalRateLimiterOutageIntegrationTest`의 `Redis가 내려가면 조회도 command timeout을 기다리지 않고 통과한다`. 단위 `RedisTerminalRateLimiterTest`의 네 scope 0 등록, `조회 거절은 read scope로 counter를 올리고 쓰기 scope는 건드리지 않는다`, `조회 설정이 규칙을 어기면 만들지 못하고 메시지에 어느 설정인지 나온다`, `조회도 Redis 오류면 통과시키고 쓰기와 같은 창을 나눠 쓴다`, 구조 테스트 `acquire와 acquireRead는 인증 인터셉터만 부른다`. 인터셉터 `TerminalAuthenticationInterceptorTest`의 `GET과 HEAD는 자리를 잡지 않고 인증과 조회 빈도 상한만 한다`, `POST는 쓰기 빈도 상한만 부르고 조회 상한은 부르지 않는다` | `./gradlew test --tests 'com.resilientpos.terminal.*'` 136건 중 9건 실패. 최소 선언(생성자에 조회 값 4개, `acquireRead`가 `acquire`를 그대로 부름)이라 assertion 실패: 쓰기 소진 뒤 조회가 `scope=terminal`로 거절, 조회 키 TTL -2(키 없음), counter scope 목록이 쓰기 둘뿐, 조회 설정 오류로도 생성됨, 인터셉터 GET에서 `acquireRead` Wanted but not invoked, 429 기대에 200. 장애 통과와 창 공유 두 건은 스텁이 같은 경로라 처음부터 통과(characterization) | 같은 명령 통과. 본문(창·탐침·결과 해석·scope·로그·counter)을 private `takeToken`(키 접두어, ARGV, scope 목록) 하나로 옮기고 `acquire`·`acquireRead`가 함께 부른다. 키는 `keys(prefix, …)` 하나이고 쓰기 접두어는 빈 문자열(기존 키 그대로), 조회는 `read:`. scope 다섯 중 `in_flight`는 `TerminalInFlightLimiter`가 등록하므로 limiter 단위 테스트는 넷을 본다. 동작 제거 확인: 인터셉터 GET 분기를 지우면 3건(GET·HEAD, 429, 구조 테스트의 양성 대조) 실패, `:store` 키의 hash tag를 지우면 4건(조회·쓰기 slot, 조회·쓰기의 한쪽 거절 무차감) 실패, 원복 | `./gradlew test --tests 'com.resilientpos.terminal.*' --tests '*ModuleStructureTest' --tests '*Architecture*'` BUILD SUCCESSFUL, 141건 실패 0 |
| 대조 case 목록의 잘림 표시와 REST Docs (인수 6). 통합 `ReconciliationCaseOwnerApiIntegrationTest`의 `미해소 case가 상한과 같으면 전부와 truncated false이고 상한을 넘으면 최신 상한 건과 truncated true이며 가장 오래된 case가 빠진다`(신규), `목록은 자기 매장의 MANUAL_REVIEW case만 최신순으로 …`(본문 모양을 고침). Controller `ReconciliationCaseOwnerControllerTest`의 `목록은 200과 미해소 case를 최신순 그대로 돌려주고 금액은 통화 기준 문자열이다`(REST Docs `reconciliation-cases-list`, 필드 `data.items[]`·`data.truncated`) | `./gradlew test --tests 'com.resilientpos.reconciliation.*'` 53건 중 3건 실패. 최소 선언(`FindReviewable.Result`, Facade가 100건을 읽어 `truncated=false`로 감쌈)이라 assertion 실패: 101건에서 `truncated` 기대 true에 false, 두 목록 테스트는 응답이 아직 배열이라 `data.items` 경로 없음 | 같은 명령 통과. 쿼리가 상한보다 한 건 더(101건) 읽고 Facade가 넘었으면 앞 100건과 `truncated=true`를 돌려준다. 응답은 `ReconciliationCasePageResponse.from(result)`. 동작 제거 확인: 쿼리를 상한 그대로 읽게 바꾸면 101건 통합 테스트 1건만 실패, 원복 | `./gradlew test --tests 'com.resilientpos.reconciliation.*' --tests '*ModuleStructureTest' --tests '*Architecture*' --tests '*TransactionDeclaration*'` BUILD SUCCESSFUL, 60건 실패 0 |
| 시연 포스의 폴링 판단과 표시 (인수 7). `?selftest`의 결제 상태 폴링 12개 사례(빈 목록·`REQUESTED`의 2초·35초 경계·30초, 7분의 주문 조회와 주문 없음·전달 지연, `ORDER_NOT_FOUND` code와 본문 없는 404의 구분, 한도 주문 조회 3회 실패, 거절, `CANCELLATION` 무시, 승인 뒤 35초·7분·PAID·확인 단계 밖 404, `UNKNOWN` 30초·65분, 재전송 대기, 자리와 최근 주문 우선)와 가짜 서버 배선 실측 | 순수 함수(`pollStep`·`pollResult`·`pollPick`)를 쓰기 전 selftest 사례를 먼저 둬 제목이 "selftest 실패"(정의되지 않은 함수)임을 보지 않았다. 대신 구현 뒤 결함 5종을 넣어 판별력을 봤다: 빠른 구간 30초, 취소 시도 거름 제거, 자리를 구간 구분 없이 세기는 "selftest 실패". 확인 단계 밖의 404로 멈추는 결함은 처음 사례가 통과시켜 승인 단계의 404 사례를 더했고, 그 뒤 "selftest 실패" | 헤드리스 Chrome `index.html?selftest&mock` 제목 "selftest 통과". 가짜 서버(표준 라이브러리, 주문 A~E, 한도를 3초·8초로 줄임) 실측: 첫 요청은 최근 주문 E·D·C 셋이 ack 뒤 1~2초에 나가고 B·A는 빠른 자리가 날 때까지 기다림, D의 `RATE_LIMITED`·`Retry-After: 3` 뒤 재요청 3.25초, E는 `REQUESTED` 2.25초 뒤 `APPROVED`와 주문 조회로 결제 완료, B 거절(저장), C 승인 뒤 PAID가 오지 않아 8초에 반영 지연(저장 안 함), 화면 결제 상태 칸이 A의 결제 확인 중 → 결제 승인 → 결제 완료로 바뀜 | 리팩터링 없음 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 더한다.

**설계 검토(spec 첫 커밋 전, spring-backend-specialist).** P0 없음. 반영한 것:
- P1: `APPROVED` 뒤 30초 한도가 PAID 반영 consumer의 재시도 상한(약 31초)과 외부화 재전송(5~6분)보다 짧아, 정상 지연을 "확인 필요"로 끝내고 계산원이 승인된 결제를 다시 받을 수 있었다. 화면은 `APPROVED`에서 바로 "결제 승인"으로 바꾸고 한도를 빠른 35초·느린 7분으로 늘렸으며 한도의 결과를 "반영 지연"(끝난 상태 아님)으로 바꿨다(사용자 결정, 결정 2).
- P1: 한 단말의 동시 폴링 수에 상한이 없어 30분 backlog(900건)에서 대부분이 429를 기다리다 60초 한도에 닿아 거절·`UNKNOWN` 주문까지 "확인 필요"로 잘못 분류됐다. 빠른 구간 3건·느린 구간 6건의 자리, 최근 주문 우선, 실제 시작부터 재는 한도를 두고 빈 목록·`REQUESTED` 단계도 35초·7분으로 맞췄다(사용자 결정, 결정 2). 느린 구간 자리는 `UNKNOWN` 몇 건이 새 주문을 막지 않게 내가 더했다.
- P2: "refill 2가 대기 주문 5건을 받는다"는 사용률 100%였다. 부하 모델을 정상 결제 약 3요청, 단말 상한 1.7/s로 다시 썼고 첫 요청을 ack 뒤 1초 + jitter로 늦췄다. `UNKNOWN` 폴링에 끝이 없던 것은 65분 뒤 "대조 대기"로 멈춘다. 끝난 상태로 남기는 것은 결제 완료와 거절뿐이다.
- P3: 주문 없음을 `ORDER_NOT_FOUND` code로 판정, 마지막 시도를 `AUTHORIZATION` 항목 목록의 마지막으로 한정, 요청 timeout 10초, limiter 본문 공유와 키 함수 공유·구조 테스트, 관측성 wiki의 "다섯"과 runbook, order-query가 넘긴 두 항목의 제외, k6 s4의 429 처리를 Phase 4에 알리기, 대조 목록 잘림의 대가.
- P4: GET 동시 수가 DB 지연에 비례하는 것은 Phase 4 측정 항목으로 넘긴다. 모의 sync의 `orderId: null`은 폴링하지 않는다.

문제없다고 본 관점: GET·HEAD 판별, 조회 키의 slot과 ACL, Lua 재사용, fail-open 창 공유, 기동 검사 1회(키 함수 공유 조건), 기존 매장 알림 의미 유지, 동시 상한 순서, 매장 경계(키와 두 GET의 매장 조건이 인증 주체에서 온다, 대조 목록의 `requireOwned`가 먼저다), 101건 읽기의 일관성, 보안(허용량 두 배는 bucket 둘이 묶고 탈취 단말은 자기 매장만 읽는다).
