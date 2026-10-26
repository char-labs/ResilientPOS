# PR spec: 주문 단건 조회와 점주 주문 목록을 연다

## 목적
주문 상태를 읽는 API가 없다. `OrderController`에는 POST 둘뿐이라 단말은 checkout 직후 결제 시도 조회가 빈 목록일 때 "전달 중"인지 "주문이 없음"인지를 가를 수 없고, 결제 시도는 `APPROVED`인데 주문은 금액 불일치 DLT로 `CHECKOUT_REQUESTED`에 남은 갈라짐도 볼 수 없다. 점주는 자기 매장의 주문을 볼 길이 없다. 단말이 주문 하나의 상태·version·line·checkout을 읽고, 점주가 매장 주문을 업무 발생 시각 순으로 기간·상태로 걸러 페이지 단위로 읽게 한다.

조회 영역은 Phase 넷으로 나뉜다. 1 주문 조회(이 PR), 2 상태 가시성과 폴링 계약, 3 메뉴(`feature/menu-catalog`에서 동시 진행), 4 측정과 튜닝. Phase마다 PR 하나다. GitHub 이슈는 없고 사용자가 직접 정했다.

## 범위
소유 모듈은 `order`다. `sync`는 항목의 `createdAt`을 주문 생성 커맨드에 넘기는 데까지만 바뀐다. `payment`·`store`·`terminal`·`contract`는 바꾸지 않는다. 주문과 결제 상태는 클라이언트가 두 API(이 PR의 주문 조회, 기존 결제 시도 조회)를 각각 불러 맞춘다. `order`와 `payment`가 서로 의존하지 않는 구조(README 모듈과 의존)를 지킨다.

**주문 발생 시각 `t_order.occurred_at`.**
- 주문이 실제로 팔린 시각이다. 서버가 받은 시각 `created_at`과 다르다. 오프라인 포스가 23:50에 판 주문을 00:10에 sync하면 `occurred_at`은 23:50, `created_at`은 00:10이다.
- 출처: sync `CREATE_ORDER` 항목의 선택 필드 `createdAt`(단말이 operation을 만든 시각), HTTP `POST /v1/orders`는 서버 시각이다. `OrderCommand.Create`에 단말이 알려 준 시각 `occurredAt`(nullable)을 더하고 HTTP는 null을 넘긴다.
- 규칙(`Order.create`가 소유): 단말 시각이 없으면 서버 시각(`now`)이다. 단말 시각이 `now`보다 늦으면 `now`로 낮춘다(발생이 수신보다 늦을 수 없다, 단말 시계가 앞섬). `now`보다 7일 넘게 이르면 `now`를 쓴다(초기화된 시계 같은 고장 값). 그 사이의 오차는 서버가 알아낼 수 없다(제외).
- sync는 항목마다 판정·적용 전에 트랜잭션 밖에서 `createdAt`을 한 번 파싱하고, 그 값을 `toCommand(terminalId, occurredAt)`와 수렴 시간 기록이 함께 쓴다. 거절 경로의 재판정이 다시 파싱하지 않는다. 형식이 틀리면 서버 시각을 쓰고, invalid counter(`sync_created_at_invalid_total`)는 지금처럼 처음 확정된(`APPLIED`·`REJECTED`) 항목에서 한 번만 센다. 배치·항목을 거절하지 않는다. `createdAt`은 `OrderPayload`에 넣지 않는다.
- `occurredAt`은 마이크로초로 자른다(`truncatedTo(MICROS)`). 메모리 값과 저장 값을 같게 둔다. `Order.occurredAt`(판매 시각)은 `DomainEvent.occurredAt`(이벤트 시각)과 다르다고 KDoc에 적는다. `OrderEntity.apply`는 이 값을 쓰지 않는다(바뀌지 않는다).
- payload hash와 주문 멱등 대조(`verifySamePayload`)에 넣지 않는다. 시각만 다른 재전송은 지금처럼 `DUPLICATE`이고 같은 `clientOrderId`의 HTTP 재요청은 기존 주문을 받는다. 첫 값이 남는다.

**단말 단건 조회: `GET /v1/orders/{orderId}`.**
- `/v1/**` 단말 인증 인터셉터 뒤에 있다. 조회는 인증된 단말의 매장 기준이다(checkout의 `(id, store_id)` 잠금, 결제 시도 조회와 같다). 키오스크가 만든 주문을 포스가 checkout하므로 자기 단말로 좁히지 않는다.
- 다른 매장의 주문과 없는 주문은 같은 `404 ORDER_NOT_FOUND`(기존 code)다. 다른 매장 주문의 존재를 드러내지 않는다.
- 응답: `orderId`, `clientOrderId`, `terminalId`, `status`, `version`, `currency`, `total`(line 합계, 통화 기준 decimal string), `occurredAt`, `lines`(`productCode`, `productName`, `unitPrice`, `quantity`), `checkouts`(`checkoutNo`, `amount`). `version`은 aggregate version으로, 단말이 바뀌었는지 비교하는 값이다.
- 일관성: 주문 행과 checkout을 left join 한 쿼리로 먼저 읽고, 주문이 없으면 line을 읽지 않고 404다. 행 수는 회차 수다. line은 그다음에 따로 읽는다. line은 주문 생성 때만 쓰이고 바꾸는 명령이 없어서 다른 커밋을 읽을 일이 없다. PostgreSQL 기본 격리 수준이 READ COMMITTED라 `Tx.readOnlyTx`로 묶어도 쿼리마다 새 스냅샷을 읽으므로 트랜잭션으로 묶지 않는다. line을 바꾸는 명령이 생기면 이 조회를 다시 본다(KDoc).

**점주 주문 목록: `GET /owner/v1/stores/{storeId}/orders`.**
- `/owner/**` 점주 세션 뒤에 있다. 먼저 `StoreManagement.requireOwned`로 매장 소유를 확인한다(남의 매장과 없는 매장은 `404 STORE_NOT_FOUND`, 기존 선례).
- 파라미터(모두 선택): `from`·`to`(ISO-8601 offset 시각, `@DateTimeFormat(iso = DATE_TIME)`, `occurred_at` 반열림 `[from, to)`, `from`이 `to`보다 같거나 늦으면 빈 목록. query string에서 offset의 `+`는 `%2B`로 보내야 하고 REST Docs 예시는 `Z`와 `%2B09:00`을 함께 싣는다), `status`(`OPEN`·`CHECKOUT_REQUESTED`·`PAID` 중 하나), `size`(기본 20, 1~50), `cursor`.
- 정렬은 `occurred_at desc, id desc` 하나로 고정이다. 정렬 파라미터를 받지 않는다. 다음 페이지는 `(occurred_at, id)` keyset이고 offset을 쓰지 않는다. keyset 술어는 "`occurred_at`이 cursor 시각 이하" 그리고 "`occurred_at`이 cursor 시각보다 이르거나 `id`가 cursor id보다 작다"로 낸다. 앞의 중복 상한이 인덱스의 범위 조건이 되고 뒤는 같은 시각의 동률만 거른다. OR만으로 펼치면 Filter가 되어 페이지가 깊을수록 앞 행을 다시 읽는다(실측 30ms, 24,223행 버림).
- cursor는 마지막 항목의 `occurred_at`(마이크로초)과 `id`를 담은 base64url 문자열이다. 클라이언트에게는 불투명하다. 해석할 수 없는 cursor(Base64, 구조, UUID, 시각 범위의 모든 실패), 범위 밖 `size`, 모르는 `status`, 형식이 틀린 시각은 `400 BAD_REQUEST`(기존 code)다. cursor와 `size`의 실패는 `ResponseStatusException(HttpStatus.BAD_REQUEST)`로 던져 `ResponseEntityExceptionHandler`가 `BAD_REQUEST`로 바꾼다. 해석 실패가 `IllegalArgumentException`으로 새면 500이 되어 오류 예산을 태운다.
- 응답: `items`(`orderId`, `terminalId`, `status`, `currency`, `total`, `occurredAt`)와 `nextCursor`(다음 페이지가 없으면 null). `size + 1`건을 읽어 다음 페이지 여부를 정한다.
- `total`은 컬럼이 아니라 쿼리에서 계산한다. 목록은 `t_order`를 `limit size+1`로 자르고 `total`은 select 절의 상관 subquery `(select sum(l.unit_price * l.quantity) from t_order_line l where l.order_id = o.id)`로 그 페이지의 행에만 계산한다. 쿼리는 하나이고 주문당 쿼리를 내지 않으며 line 자체는 싣지 않는다. line join과 group by 뒤에 order by·limit을 거는 모양은 쓰지 않는다. PostgreSQL이 group by를 PK 하나로 줄여 정렬 인덱스를 못 쓰고 매장 이력 전체를 집계한 뒤 자른다(실측 첫 페이지 1,135ms, 인덱스 A가 있어도). Kotlin JDSL이 select 절 subquery를 렌더하지 못하면 페이지의 id로 `where order_id in (...) group by order_id`를 두 번째 쿼리로 낸다(주문 수와 무관하게 고정 2개, line이 불변이라 두 문장의 커밋이 달라도 틀리지 않는다).
- 늦게 sync된 주문은 첫 페이지가 아니라 발생 시각 자리에 나타난다. 새 주문 갱신은 첫 페이지를 다시 읽는다. UUID v7 id로 "새 주문만" 읽지 않는다(늦게 커밋된 행을 놓친다).
- 기간에 상한을 두지 않는다. 위 쿼리 모양과 두 인덱스에서 한 요청이 읽는 행은 keyset과 `size`가 묶는다(실측 모든 경우 1ms 아래).

**읽기 구조.** README CQRS 규약과 결제 시도 조회 선례를 따른다. 공개 타입 `OrderQuery`(`GetOrder`, `FindOrders`와 그 안의 `Criteria`), `OrderDetailView`, `OrderSummaryView`, `OrderQueryFacade`가 모듈 루트에 생긴다. 조회 구현은 `order/internal/adapter/persistence/query`의 `OrderQueries`(Kotlin JDSL, `@ReadOnlyTransactional`, port 없음, aggregate를 거치지 않음)다. `OrderRepository`는 쓰지 않는다(목록에 다시 쓰면 주문당 쿼리 3개다). 목록 페이지 결과 타입과 cursor 인코딩은 `order` 안에 둔다. main과 열린 PR에 같은 공용 타입이 없고(`PageResult`·cursor 검색 결과 0건) 사용처가 하나라 공용으로 만들지 않는다. Phase 3이 목록이나 cursor를 만들면 겹치므로 PR 본문에 적는다.

**인덱스.** `t_order`에는 매장 인덱스가 없다. 인덱스 A `(store_id, occurred_at, id)`와 상태 필터용 인덱스 B `(store_id, status, occurred_at, id)`를 V009·V013처럼 각각 `create index concurrently` 단독 마이그레이션으로 둔다(`transactional-lock=false`는 이미 설정돼 있다). 오름차순 인덱스의 역방향 스캔이 `occurred_at desc, id desc`를 낸다. B는 합의한 판정 기준(가장 큰 매장의 90일 중 1건뿐인 상태를 A로 읽을 때 캐시가 찬 상태로 50ms 초과)을 넘어 더한다. 상태 없는 조회는 B로 시각순을 낼 수 없어 A가 계속 필요하다.

실측(2026-10-26, 일회용 `postgres:18-alpine` 컨테이너, shared_buffers 1GB, 주문 1,000만 = 250매장 × 4만 행 × 90일, line 2,000만, 매장 1에 `CHECKOUT_REQUESTED` 1건, `OPEN` 1%, 같은 쿼리 두 번째 실행 기준, 손으로 쓴 SQL). 공유 compose 스택(`resilientpos`)의 PostgreSQL은 쓰지 않았다.

| 쿼리(매장 1, size 21) | 인덱스 없음 | A | A + B |
|---|---|---|---|
| line join + group by + order by + limit, 첫 페이지 | 3,228ms, Parallel Seq Scan | 1,135ms, 4만 행 HashAggregate 뒤 top-N | 쓰지 않음 |
| 상관 subquery 합계, 첫 페이지 | 240ms | 0.25ms, 151 buffers | 0.22ms |
| 같은 모양, 깊은 페이지, OR 술어 | 측정 안 함 | 30ms, Filter로 24,223행 버림 | 34ms |
| 같은 모양, 깊은 페이지, 중복 상한 술어 | 측정 안 함 | 0.75ms, 151 buffers, Index Cond에 상한 | 측정 안 함 |
| 하루 기간 + `PAID` | 247ms | 0.26ms | 0.23ms(B) |
| 1건뿐인 상태, 기간 없음(최악) | 207ms | 76ms, 4만 행 Bitmap Heap Scan | 0.03ms(B) |
| 1% 상태(`OPEN`), 기간 없음 | 209ms | 0.27ms | 0.12ms(B) |

크기는 `t_order` 1,420MB, A 562MB, B 645MB다. 0건인 상태는 A로는 매장 이력 전체를 읽고 이력에 비례해 커지지만(90일 시드는 1년치의 4분의 1), B에서는 이력과 무관하다. 구현 뒤 Hibernate가 렌더한 SQL을 `PREPARE`해 `plan_cache_mode = force_generic_plan`에서 네 경우(필터 없는 첫 페이지, 깊은 페이지, 0건 상태, 1건 상태)를 다시 재고 결과 절에 남긴다(pgjdbc가 다섯 번째 실행부터 generic plan을 쓴다).

계약: 공개 타입 `OrderQuery`, `OrderDetailView`, `OrderSummaryView`, `OrderQueryFacade`가 생기고 `OrderCommand.Create`에 nullable `occurredAt`이 더해진다. 새 오류 code와 enum 이름은 없다. 기존 `ORDER_NOT_FOUND`가 처음 GET 응답으로 나간다. 이벤트·`event_publication`은 바뀌지 않는다(`OrderCreated`는 발행 기록이 없고 payload에 시각을 더하지 않는다). `order`가 `owner`의 `OwnerId`에 처음 의존한다(식별자 의존, 점선). `order`의 점주 Controller가 `StoreManagement.requireOwned`를 동기 호출한다(상태 변경 없음, 순환 없음).

불변식: 단말은 자기 매장의 주문만, 점주는 자기 매장의 주문만 읽는다. 단건 조회의 상태와 checkout은 같은 커밋이다. `occurred_at`은 수신 시각보다 늦지 않고 7일 넘게 이르지 않다. 첫 값이 바뀌지 않는다.

복구: 조회는 상태를 바꾸지 않아 다시 부르면 된다. 발생 시각이 틀리게 들어간 주문은 고치는 명령이 없다(제외).

관측성: 조회는 로그를 남기지 않는다. 기존 advice가 404를 INFO로 남긴다. 새 GET 두 개는 기존 HTTP metric(`http_server_requests`)과 오류 예산에 든다. GET 지연 알림은 Phase 4다.

## 제외
- 단말 주문 목록. 지금 부르는 클라이언트가 없고, 생기면 탈취 단말이 매장 이력을 읽는 범위와 기간 상한을 함께 정한다.
- 점주 주문 상세(line). 목록 요약만 둔다. 점주 화면이 생기면 더한다.
- 단말 필터, 여러 상태 필터, 정렬 선택. 여러 상태는 어느 인덱스로도 시각순 출력이 되지 않는다.
- `updatedAt` 응답 필드와 폴링 계약, 결제 시도 응답 필드 추가, 대조 case 목록 잘림 표시. Phase 2다.
- 조회 전용 빈도 bucket, GET 동시 상한, 목록 쿼리만의 짧은 timeout. 새 단말 GET은 쓰기와 같은 단말·매장 bucket을 소비한다. 폴링 간격과 bucket 분리는 Phase 2가 함께 정한다. 한 요청의 일을 쿼리 모양(keyset·상한 50·인덱스)으로 묶고 최악 경로를 실측한다. 남는 장치는 기존 쿼리 상한 10초다. 조회 섞은 k6와 GET 지연 알림, 캐시는 Phase 4다.
- 시연 단말이 새 GET을 부르는 것. Phase 2다.
- 허용 범위 안의 단말 시계 오차 판별과 발생 시각 정정 명령. 서버는 단말 시계를 검증할 근거가 없다.
- 단말 시각을 수신 시각으로 바꾼 사실의 counter(`reason=future|stale`). `Order.create` 결과에 표시를 더해야 해서 이번에는 두지 않는다. 시각 이상이 정산 문제로 보고되면 더한다.
- `occurred_at`의 하루 경계(매장 시간대). 시간대는 store 설정이 없어 클라이언트가 `from`·`to`를 offset으로 보낸다.

## 관련 문서
없음. 정본은 [도메인 모델](../wiki/domain-model.md) §2·§4.2·§4.7, [README Engineering Principles](../../README.md#engineering-principles)의 CQRS 규약, [청사진 §4·§5](../references/05_Domain_Development_Blueprint.md)다. 선례는 [payment-status-query](2026-10-06-payment-status-query.md)다.

## 변경 경로
- src/main/kotlin/com/resilientpos/order/**
- src/main/kotlin/com/resilientpos/sync/**
- src/main/resources/db/migration/**
- src/test/kotlin/com/resilientpos/order/**
- src/test/kotlin/com/resilientpos/sync/**
- src/test/resources/**
- docs/wiki/domain-model.md
- docs/wiki/entity-map.md
- docs/spec/2026-10-26-order-query.md

## 인수 조건
1. `GET /v1/orders/{orderId}`는 인증된 단말 매장의 주문을 `200`과 `orderId`·`clientOrderId`·`terminalId`·`status`·`version`·`currency`·`total`·`occurredAt`·`lines`·`checkouts`로 돌려준다. checkout한 주문은 `CHECKOUT_REQUESTED`와 1회차 금액이 함께 나온다. checkout 전 `OPEN` 주문은 `200`과 `checkouts: []`다.
2. 다른 매장의 주문과 없는 주문은 모두 `404 ORDER_NOT_FOUND`다.
3. 단건 조회는 주문 행과 checkout을 한 쿼리로 읽고 line을 한 쿼리로 읽는다(Hibernate statistics의 prepareStatementCount가 2, 선례 `OrderInsertStatementCountIntegrationTest`).
4. `GET /owner/v1/stores/{storeId}/orders`는 그 매장 주문을 `occurred_at desc, id desc`로 `size`건씩 `items`와 `nextCursor`로 돌려준다. `nextCursor`로 끝까지 넘기면 모든 주문이 중복·누락 없이 한 번씩 나오고 마지막 페이지의 `nextCursor`는 null이다. 같은 `occurred_at`의 주문도 `id`로 갈린다.
5. `from`·`to`는 `[from, to)`로, `status`는 그 상태만 거른다. 각 항목의 `total`은 line 단가 × 수량의 합이다.
6. 남의 매장과 없는 매장은 `404 STORE_NOT_FOUND`이고 다른 매장 주문이 섞이지 않는다. 세션이 없으면 `401`이다.
7. 해석할 수 없는 cursor(Base64가 아님, 구조가 틀림, UUID가 아님, 시각 범위 밖 각각), `size` 0·51, 모르는 `status`, 형식이 틀린 `from`은 `400 BAD_REQUEST`다. `+09:00`을 `%2B09:00`으로 인코딩한 URI가 통과한다.
8. sync `CREATE_ORDER` 항목의 `createdAt`이 주문의 `occurredAt`이 된다. 수신 시각보다 늦으면 수신 시각, 7일 넘게 이르거나 없거나 형식이 틀리면 수신 시각이다. HTTP 생성은 수신 시각이다. 시각만 다른 재전송은 `DUPLICATE`이고 첫 `occurredAt`이 남는다.
9. 마이그레이션이 기존 행의 `occurred_at`을 `created_at`으로 채우고 NOT NULL로 만들며, 인덱스 A와 B는 각각 CONCURRENTLY 단독 마이그레이션이다. Hibernate가 렌더한 SQL의 generic plan 실측이 결과 절에 있다.
10. 두 endpoint의 성공·주요 실패 응답이 REST Docs 스니펫으로 생성된다.
11. `./gradlew test` 전체와 `python3 scripts/check_pr.py --base origin/main`이 통과한다.

### Checkpoint와 검증 명령
| Checkpoint | 검증 명령 | 의존 |
|---|---|---|
| 발생 시각 (8·9 일부) | `./gradlew test --tests 'com.resilientpos.order.internal.domain.*' --tests 'com.resilientpos.sync.*'` | 없음 |
| 조회 (1~5) | `./gradlew test --tests 'com.resilientpos.order.*Query*'` | 발생 시각 |
| Controller (2·6·7·10) | `./gradlew test --tests 'com.resilientpos.order.internal.adapter.web.*'` | 조회 |
| 인덱스 실측 (9) | 일회용 PostgreSQL 컨테이너 시드 + `EXPLAIN (ANALYZE, BUFFERS)` | 조회 |
| 전체 (11) | `./gradlew test` · `python3 scripts/check_pr.py --base origin/main` | 모두 |

## 스키마 변경 검토
대상: `t_order.occurred_at timestamptz` 추가(기존 행은 `created_at`으로 채우고 NOT NULL), 인덱스 A `(store_id, occurred_at, id)`, 인덱스 B `(store_id, status, occurred_at, id)`.

1. 도출: `occurred_at`은 저장된 값에서 도출할 수 없다. `created_at`은 서버 수신 시각이고 sync 항목 `createdAt`은 저장하지 않는다(`t_sync_operation`에도 없다). 인덱스는 도출 대상이 아니다.
2. 읽는 코드: 점주 목록이 `occurred_at`으로 정렬·기간 필터하고 단건·목록 응답이 싣는다. 인덱스 A는 상태 없는 목록이, B는 상태 필터 목록이 탄다. B는 드문 상태에서 A로 76ms(판정 기준 50ms 초과)라 더했다. 대가로 상태 전이(checkout, `PAID`)가 B를 갱신해 HOT update가 되지 못하고 645MB를 쓴다(1,000만 행 기준).
3. 나중에 채울 수 있는가: 없다. 첫 배포 뒤 sync로 들어온 주문의 단말 시각은 버려져 다시 채울 수 없다. 그래서 읽는 코드와 함께 지금 둔다. 지금은 배포 환경이 없어 잃은 값이 없고, 기존 행(개발 데이터)은 `created_at`으로 채운다.
4. 불변식의 방어선: 아니다. unique·멱등 키·상태가 아니다. 멱등 판정(payload hash, `verifySamePayload`)에 넣지 않는다.
5. 정본: 업무 발생 시각의 정본은 이 컬럼 하나다. 온라인 HTTP 주문은 `created_at`과 거의 같은 값을 갖지만 뜻이 다르다(발생과 수신). 같은 INSERT로 쓰이고 바뀌지 않는다.
6. check-then-act: 없다. 쓰는 곳은 주문 생성 INSERT 하나이고 읽는 곳은 조회뿐이다. 단건 조회는 주문 행과 checkout을 한 쿼리로 읽어 서로 다른 커밋을 함께 읽지 않는다. line은 생성 뒤 바뀌지 않는다.
7. 락: 컬럼 추가는 nullable이라 메타데이터 변경, `update`는 전체 행을 다시 쓰고, `set not null`은 `ACCESS EXCLUSIVE`로 전체를 스캔한다. 배포 환경이 없고 개발 데이터만 있어 한 마이그레이션에 둔다. 배포된 큰 테이블이었다면 nullable 추가 → 배치 backfill → `NOT VALID` check → `VALIDATE` 순서다. 마이그레이션에 `SET LOCAL lock_timeout`을 둔다. 인덱스는 `create index concurrently` 단독 마이그레이션이고 `if not exists`를 쓰지 않는다. 실패 시 INVALID 인덱스와 `flyway_schema_history` 실패 행 정리는 domain-model §4.7 질문 7 절차다. 위반 행: `occurred_at`은 같은 마이그레이션이 모두 채우므로 NOT NULL 위반 행이 없다.
8. 이전 버전 호환: 기본값 없는 NOT NULL이라 이전 버전의 INSERT가 실패한다. 배포 환경이 없어 expand 순서를 밟지 않는다. 배포된 뒤 같은 변경이라면 nullable로 먼저 내보냈어야 한다.
9. 이벤트·캐시: `OrderEvent.Created`는 발행 기록이 없고 payload에 시각을 더하지 않는다. 캐시가 없다.
- 대조 기록: 미작성. PR 제출 전 구현자가 아닌 리뷰어가 마이그레이션·엔티티·조회 코드와 대조해 채운다.

## 검증
- 정상: 단건 조회의 각 필드, checkout 뒤 상태·회차, 목록의 정렬·keyset 끝까지 넘김·기간·상태 필터·합계, sync 시각의 경계(미래, 7일 초과, 없음, 형식 오류).
- 실패: 다른 매장 단건 404, 남의 매장 목록 404, 인증 없음 401, 잘못된 cursor·size·status·시각 400.
- 통합 테스트는 Testcontainers의 PostgreSQL·Redis·Kafka를 쓴다(Docker는 OrbStack).
- 동률 시각 테스트의 id는 `IdentifierGenerator`(UUID v7)로 만들고 기대 순서를 Kotlin 정렬로 만들지 않는다. `UUID.compareTo`는 상위 64비트를 부호 있는 수로 비교해 PostgreSQL과 다를 수 있다.
- 불변식의 수신 시각은 `Order.create`에 넘긴 `now`다. `created_at`은 주입 Clock과 무관한 JVM 시각이라 테스트에서 컬럼끼리 비교하지 않는다.
- 동작을 지워 테스트가 빨개지는지 본다: 단건 조회의 매장 조건, 목록의 매장 조건, keyset의 `id` 동률 조건, 시각 상한·하한 규칙.
- 인덱스 실측은 공유 compose 스택이 아닌 일회용 컨테이너에서 하고 컨테이너를 지운다.

## Wiki 영향
- [도메인 모델](../wiki/domain-model.md): §2 `order` 행의 공개 Facade에 `OrderQueryFacade`를 더한다. §4.2의 "여러 쿼리를 한 시점에 읽어야 하면 Facade가 `Tx.readOnlyTx`로 묶는다"를 고친다(READ COMMITTED에서는 쿼리마다 스냅샷이라 한 쿼리로 읽는다). §4.7 사례에 `t_order.occurred_at`과 인덱스를 더한다. 같은 절의 sync `createdAt` 사례(컬럼을 두지 않는다)를 고친다. §3 용어집에 `occurred_at`(발생 시각)을 더한다. §4.6의 상태를 바꾸지 않는 동기 호출 목록에 `order`·`reconciliation` 점주 Controller의 `requireOwned`를 더한다. `feature/menu-catalog`와 다른 절을 고친다.
- [엔티티 지도](../wiki/entity-map.md): `t_order`의 시각과 조회 경로를 더하고 :144의 "결제 조회 API는 아직 없다"처럼 조회 영역의 낡은 문장을 고친다.
- 현재 상태(`docs/wiki/current-state.md`, 볼트)는 볼트에서 갱신한다(두 GET, 발생 시각).
- Kafka·Modulith 이벤트 작업이 아니라 Modulith·Kafka 키워드는 바꾸지 않는다.
- 요구사항 변화: 단말이 주문 상태를, 점주가 매장 주문 목록을 읽는다. 주문에 업무 발생 시각이 생긴다. 정합성 요구는 그대로다.

## 분할 계획
개발 컨텍스트는 하나다. 주문을 읽는 길을 열고, 목록의 기준 시각인 발생 시각을 함께 둔다. 단말 목록, 폴링 계약, 측정·튜닝은 다른 Phase다. 작업 시작 시점(origin/main bb14c86)에 열린 PR이 없다. `feature/menu-catalog`(Phase 3)는 커밋 전이고 새 catalog 모듈, 마이그레이션, 시연 단말 `index.html`, `domain-model.md`, 단말 GET 경로를 건드릴 예정이다. 마이그레이션 번호(지금 마지막 V020)는 push 전에 origin/main과 그 브랜치를 확인하고 나중에 머지하는 쪽이 바꾼다. `domain-model.md`는 서로 다른 절을 고친다. push·PR 생성·머지 직전에 경로 겹침을 다시 확인한다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 주문 생성이 단말 시각을 규칙대로 발생 시각으로 정한다 (인수 8, 9 일부). `OrderTest`의 `단말 시각이 없으면 발생 시각은 수신 시각이다`, `단말 시각이 수신 시각보다 늦으면 수신 시각으로 낮춘다`, `단말 시각이 수신 시각과 같거나 이르면 그대로 쓴다`, `단말 시각이 7일 정각 전이면 그대로 쓰고 1마이크로초라도 넘게 이르면 수신 시각이다`, `발생 시각은 마이크로초로 자른다` | `./gradlew test --tests 'com.resilientpos.order.internal.domain.OrderTest'` 38건 중 3건 실패. 최소 선언(`Order.occurredAt`을 항상 now로 둠) 뒤 assertion 실패: 같거나 이르면 그대로, 7일 경계, 마이크로초 절삭이 now와 달라서 실패. 미래·없음 두 건은 스텁이 now라 통과 | 같은 명령, BUILD SUCCESSFUL (OrderTest 전부 통과) | `./gradlew test --tests 'com.resilientpos.order.*' --tests 'com.resilientpos.sync.*' --tests '*ModuleStructureTest' --tests 'com.resilientpos.FixtureFactoriesTest'` BUILD SUCCESSFUL, 235건 실패 0. 마이그레이션 V021은 통합 테스트 Flyway 적용으로 확인 |
| sync 항목 `createdAt`이 주문 발생 시각이 되고 재전송은 첫 값을 지킨다 (인수 8). `SyncCommandFacadeIntegrationTest`의 `CREATE_ORDER 항목의 createdAt이 주문의 발생 시각이 된다`, `createdAt만 바꿔 재전송하면 DUPLICATE이고 발생 시각은 첫 값이다`, `형식이 틀린 createdAt의 주문은 APPLIED이고 발생 시각은 수신 시각이다` | `./gradlew test --tests 'com.resilientpos.sync.internal.application.SyncCommandFacadeIntegrationTest'` 56건 중 2건 실패. 3시간 전 createdAt이 발생 시각이 되지 않고 수신 시각이 저장됨(expected 03:45 but was 06:45), 재전송 첫 값도 같은 이유로 실패. 형식 오류 테스트는 `Order.create`의 규칙(없으면 수신 시각)이라 구현 전에도 통과했고, 형식 오류에 60초 전 값을 넘기게 바꾼 동작 변경으로 실패함을 확인한 뒤 원복 | 같은 명령, 56건 전부 통과. 항목마다 트랜잭션 밖에서 `createdAt`을 한 번 파싱해 `toCommand(terminalId, occurredAt)`와 수렴 시간 기록이 함께 쓰고, `OrderCommand.Create.occurredAt`의 기본값을 없애 호출부가 명시 | `./gradlew test --tests 'com.resilientpos.sync.*' --tests 'com.resilientpos.order.*' --tests '*ModuleStructureTest' --tests 'com.resilientpos.FixtureFactoriesTest'` BUILD SUCCESSFUL, 238건 실패 0 |
| 단건 조회가 매장 기준으로 상태·version·line·checkout을 읽는다 (인수 1~3). `OrderQueryFacadeIntegrationTest`의 `checkout한 주문은 상태와 version과 1회차 금액과 line과 합계와 발생 시각을 돌려준다`, `checkout 전 OPEN 주문은 checkout 없이 정상으로 돌려준다`, `다른 매장의 주문과 없는 주문은 같은 ORDER_NOT_FOUND다`, `단건 조회는 주문과 checkout 한 쿼리와 line 한 쿼리로 두 문장이다` | `./gradlew test --tests '*OrderQueryFacadeIntegrationTest'` 4건 중 3건 실패. 최소 선언(`OrderQueries.getOrder`가 항상 null)이라 나머지 셋이 ORDER_NOT_FOUND 예외로 실패했다. 매장 불일치·없는 주문 테스트는 스텁이 항상 not found라 통과했고, 구현 뒤 매장 조건을 지우면 `Expected an exception ... but was completed successfully`로 실패함을 확인한 뒤 원복 | 같은 명령, 4건 전부 통과. 주문 행과 checkout을 left join 한 쿼리 하나, line 한 쿼리(prepareStatementCount 2). Hibernate가 `@SoftDelete` 조건을 ON 절에 붙여(`left join t_order_checkout ... on deleted_at is null and order_id=...`) checkout 없는 OPEN 주문이 사라지지 않는다. 전역 통계라 checkout이 띄운 비동기 문장이 섞여 한 번 3이 나왔다. 문장 수 테스트는 비동기 처리가 없는 OPEN 주문으로 잰다(checkout을 따로 읽는 구현이면 행이 없어도 3문장이라 판별력은 같다) | `./gradlew test --tests 'com.resilientpos.order.*' --tests '*ModuleStructureTest' --tests '*TransactionDeclaration*' --tests '*EntityVisibility*' --tests '*DependencyDirection*' --tests '*ConstraintTranslation*'` BUILD SUCCESSFUL, 149건 실패 0 |
| 목록이 발생 시각 keyset으로 기간·상태를 걸러 합계와 함께 읽는다 (인수 4·5) | 미실행 | 미실행 | 미실행 |
| 두 endpoint의 매핑·인증·소유 확인·400 변환과 REST Docs (인수 2·6·7·10) | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
