# PR spec: 같은 주문 생성 키에 다른 payload가 오면 409 IDEMPOTENCY_CONFLICT

## 목적
같은 `(terminalId, clientOrderId)`로 내용이 다른 `CreateOrder` 요청이 오면 지금은 `OrderCommandFacade`가 기존 결과와 201을 그대로 돌려준다. 단말은 자신이 보낸 주문이 만들어졌다고 믿지만 서버에는 다른 내용의 주문이 있다. 이 PR이 끝나면 payload가 같은 재시도만 기존 결과를 받고, payload가 다르면 `DomainException(IDEMPOTENCY_CONFLICT, kind=CONFLICT)`이 나가 409로 응답한다. 요구사항은 [order-create-order-api](2026-09-20-order-create-order-api.md)의 제외 절과 [foundation-domain-exception](2026-09-19-foundation-domain-exception.md)의 제외 절에서 이 PR로 미룬 것이다.

## 범위
소유 모듈은 `order`다. 공개 계약 `CreateOrderCommand`·`CreateOrderResult`와 HTTP 요청·응답 모양은 바꾸지 않는다. 바뀌는 계약은 `POST /v1/orders`에 409 `IDEMPOTENCY_CONFLICT` 응답이 생기는 것 하나다.

### 결정 1. hash 대상 필드와 canonical 표현
- 대상: `storeId`, `currency`, 각 line의 `productCode`·`productName`·`unitPrice`(금액과 통화 코드)·`quantity`.
- `terminalId`와 `clientOrderId`는 넣지 않는다. 조회 키 자체라서 기존 주문을 찾았다면 이미 같다.
- 기준은 HTTP 본문의 바이트가 아니라 `CreateOrderCommand`의 값이다. JSON 필드 순서·공백과 `"4500"`·`"4500.0"` 같은 표기 차이는 DTO와 `Money.of`를 거치면서 이미 사라진다. `sync`가 나중에 같은 Facade를 호출할 때도 같은 판정을 받는다.
- 금액은 `Money.amount.toPlainString()`으로 적는다. `Money`가 통화의 표준 소수 자릿수를 canonical scale로 강제하므로 같은 금액은 항상 같은 문자열이 된다. `toString()`은 지수 표기가 나올 수 있어서 쓰지 않는다.
- 통화는 ISO 4217 코드 문자열이다. line 단가의 통화 코드도 line마다 넣는다. 같은 키의 두 번째 요청은 `Order.create`의 `CURRENCY_MISMATCH` 검증을 거치지 않으므로, 주문 통화만 넣으면 line 통화가 다른 요청을 같은 payload로 판정하게 된다.
- line 순서는 무시한다. line마다 canonical 문자열을 만든 뒤 정렬해서 잇는다. 같은 line이 두 번 있으면 두 번 다 들어간다. 순서는 표시 순서일 뿐이며 판매한 상품과 합계를 바꾸지 않는다. 오판정의 비용도 비대칭이다. 순서만 다른 재시도를 충돌로 판정하면 단말의 재시도가 영원히 409로 막히고, 같은 요청으로 판정하면 내용이 같은 기존 주문을 돌려줄 뿐이다.
- `productCode`·`productName`은 받은 그대로 비교한다. trim, 대소문자, 유니코드 정규화를 하지 않는다. 재시도는 같은 payload를 다시 보내는 것이고, 문자열을 임의로 같게 취급하면 다른 상품명 snapshot을 같은 요청으로 판정할 수 있다.
- 필드는 `길이:값` 형태로 이어 붙인다. 구분자만 쓰면 `productCode`가 `AB`이고 `productName`이 `C`인 line과, `A`와 `BC`인 line이 같은 문자열이 된다.
- 알고리즘은 JDK `MessageDigest`의 SHA-256이고 결과는 소문자 hex 64자다. 보안 용도가 아니라 동등성 비교 용도이며, 새 의존성이 필요 없다.
- canonical 표현에 버전 표시는 넣지 않는다. `CreateOrderCommand`에 필드가 늘어 표현이 바뀌는 PR에서 버전 컬럼을 추가하고 기존 행을 1로 채우면 된다. 지금 넣어도 그 버전을 해석하는 코드가 없다.

### 결정 2. 계산 위치와 저장 위치
- 저장된 `Order`의 현재 상태와 직접 비교하지 않고 최초 요청의 hash를 저장한다. `Order`는 `OPEN` 상태에서 line과 금액이 바뀌는 aggregate라서([도메인 모델](../wiki/domain-model.md) 2절), line 변경 명령이 생기는 순간 현재 상태는 최초 요청과 달라지고 정상 재시도가 충돌로 판정된다. hash는 최초 요청의 변하지 않는 기록이다. [개발 가이드 3.3절](../references/03_Development_Guide.md)의 payment idempotency record도 request payload hash를 저장한다.
- 계산과 판정은 `order/internal/domain`의 `Order`가 맡는다. `Order.create`가 command에서 `payloadHash`를 계산해 aggregate에 담고, `order.verifySamePayload(command)`가 같은 계산으로 비교해 다르면 `DomainException`을 던진다. "같은 키의 요청이 최초 요청과 같은가"는 업무 규칙이고 [청사진 1절](../references/05_Domain_Development_Blueprint.md)은 Facade에 aggregate rule 구현을 금지한다. `OrderCommandFacade`는 기존 주문을 찾았을 때 결과를 돌려주기 전에 `verifySamePayload`를 호출하는 한 줄만 늘어난다.
- 저장 위치는 `t_order.payload_hash varchar(64)`이고 `V003__order_payload_hash.sql`이 추가한다. 멱등 키의 unique 제약이 이미 `t_order`에 있고 주문과 1:1이라서 별도 테이블은 join과 쓰기만 늘린다. [청사진 6.2절](../references/05_Domain_Development_Blueprint.md)의 공용 `idempotency_record(scope, key)`는 payment가 필요로 할 때 검토한다.
- 컬럼은 nullable이고 기존 행은 backfill하지 않는다. V003 이전에 저장된 행의 hash는 null이다. `Order.payloadHash`도 nullable이며, null이면 `verifySamePayload`가 통과시켜 지금처럼 기존 결과를 돌려준다. 최초 요청을 알 수 없는 주문을 충돌로 판정하면 정상 재시도가 막힌다. SQL로 같은 canonical 표현을 다시 만들어 backfill하는 방법은 Kotlin 구현과 어긋나기 쉽고, 운영 배포가 아직 없어서 대상 행은 개발 DB에만 있다.
- `platform`은 고치지 않는다. `CONFLICT`의 409 매핑은 #23의 `ApiControllerAdvice`에 이미 있다.

### 그 밖의 항목
- 불변식: 같은 `(terminalId, clientOrderId)`에 대해 주문은 하나이고, 그 주문은 최초 payload의 것이다. 충돌 요청은 기존 주문과 line을 바꾸지 않고 `OrderCreated`를 발행하지 않는다.
- 실패와 재시도: 409는 재시도해도 같은 결과다. 단말은 새 `clientOrderId`로 다시 보내거나 기존 주문을 확인해야 한다. payload가 같은 재시도는 계속 201과 같은 본문이다.
- 관측성: `ApiControllerAdvice`가 `errorCode=IDEMPOTENCY_CONFLICT` 로그를 남기고 Actuator `http.server.requests`가 409를 센다.

## 제외
- 빈 `productCode`·`productName` 거절 규칙과 `RequestCheckout`. 각각 다음 PR이다.
- 같은 키의 두 요청이 동시에 도착하는 경합. 둘 다 조회에서 기존 주문을 찾지 못하면 unique 제약이 한쪽을 거절한다. 이 거절을 기존 결과 반환으로 바꾸는 일은 이 PR의 목표가 아니다.
- 409 응답에 기존 `orderId`를 담는 것. 오류 응답 모양(`errorCode`·`message`)을 바꾸는 일이라서 필요해지면 `platform` PR로 한다.
- idempotency hit·conflict 전용 metric. 업무 metric 기반이 아직 없다.
- 공용 idempotency record 테이블, hash 만료 정책, canonical 표현의 버전 관리.
- 인접 코드 리팩터링.

## 관련 문서
없음. 선행 PR은 [order-create-order-api](2026-09-20-order-create-order-api.md), [foundation-domain-exception](2026-09-19-foundation-domain-exception.md), [order-create-order-persistence](2026-09-16-order-create-order-persistence.md)다. persistence spec은 payload 불일치 판정을 `sync`의 operation hash 비교에 맡긴다고 적었지만, #24에서 `POST /v1/orders`가 `sync`를 거치지 않는 공개 경로가 되었으므로 Facade가 직접 막는다. 근거 문서는 [시스템 설계 3.1절](../references/02_System_Design.md)의 "동일 key에 다른 payload면 409 IDEMPOTENCY_CONFLICT"다.

## 변경 경로
- src/main/resources/db/migration/V003__order_payload_hash.sql
- src/main/kotlin/com/resilientpos/order/**
- src/test/kotlin/com/resilientpos/order/**
- src/test/kotlin/com/resilientpos/SchemaMigrationTest.kt
- docs/spec/2026-09-20-order-idempotency-conflict.md
- docs/wiki/current-state.md
- docs/wiki/changes.md

## 인수 조건
도메인(1~3)은 Spring 없는 `OrderTest`, Facade(4~7)는 실제 PostgreSQL을 쓰는 `OrderCommandFacadeIntegrationTest`, 웹 슬라이스(8)는 `OrderControllerTest`, HTTP 통합(9)은 `OrderApiIntegrationTest`로 검증한다.
1. `Order.create`가 만든 주문의 `payloadHash`는 소문자 hex 64자다. line 순서만 다른 command와, 금액 표기만 다른 command(USD `"45"`와 `"45.00"`)에서 만든 주문은 `payloadHash`가 같다.
2. `verifySamePayload`는 `storeId`, `currency`, line의 `productCode`·`productName`·`unitPrice`·`quantity`, line 개수 중 하나라도 다른 command에 `DomainException`(code `IDEMPOTENCY_CONFLICT`, kind `CONFLICT`)을 던진다. `productCode` `AB`·`productName` `C`인 line과 `A`·`BC`인 line도 다른 payload다. 같은 command에는 던지지 않는다.
3. `payloadHash`가 null인 주문(`Order.restore`로 재구성)의 `verifySamePayload`는 어떤 command에도 던지지 않는다.
4. 같은 키에 수량이 다른 command를 보내면 Facade가 `IDEMPOTENCY_CONFLICT`·`CONFLICT`를 던진다. `t_order` 행은 하나이고, 저장된 주문의 line은 최초 요청 그대로이며, `OrderCreated`는 한 번만 발행된다.
5. 같은 키에 line 순서만 바꾼 command를 보내면 기존 결과(`orderId` 동일)를 돌려준다.
6. 주문을 만들면 `t_order.payload_hash`에 64자 값이 저장된다.
7. `payload_hash`를 null로 바꾼 기존 행은 같은 키에 다른 payload가 와도 기존 결과를 돌려준다.
8. Facade가 `DomainException(IDEMPOTENCY_CONFLICT, kind=CONFLICT)`을 던지면 409이고 `result`가 `FAIL`, `data`가 null, `error.errorCode`가 `IDEMPOTENCY_CONFLICT`다. 요청·응답 필드가 `relaxed` 없이 문서화되고 `build/generated-snippets/orders-create-idempotency-conflict`에 스니펫이 생긴다.
9. 같은 `storeId`·`terminalId`·`clientOrderId`로 수량만 다른 본문을 이어서 보내면 첫 응답은 201, 두 번째 응답은 409 `IDEMPOTENCY_CONFLICT`이고 `t_order`에 행이 하나다. 같은 본문 재시도는 계속 201이다(기존 테스트 유지).
10. `SchemaMigrationTest`가 `003` 성공 기록을 확인한다. `ModuleStructureTest`와 `DependencyDirectionTest`가 그대로 통과한다.
11. `python3 scripts/check_pr.py --base b412ccf`가 통과한다.

### Checkpoint와 검증 명령
| Checkpoint | 검증 명령 | 의존 |
|---|---|---|
| domain-hash (1~3) | `./gradlew test --tests 'com.resilientpos.order.internal.domain.OrderTest'` | 없음 |
| facade-conflict (4~7, 10) | `./gradlew test --tests 'com.resilientpos.order.internal.application.OrderCommandFacadeIntegrationTest' --tests 'com.resilientpos.SchemaMigrationTest'` | domain-hash |
| web-409 (8~9) | `./gradlew test --tests 'com.resilientpos.order.internal.adapter.web.*'` | facade-conflict |

## 검증
```sh
./gradlew test
ls build/generated-snippets
python3 scripts/check_pr.py --base b412ccf --worktree
```
- 정상: payload가 같은 재시도와 line 순서만 다른 재시도가 201과 같은 `orderId`를 받는다.
- 실패: payload가 다른 요청이 409 `IDEMPOTENCY_CONFLICT`를 받고 기존 주문이 바뀌지 않는다.

## Wiki 영향
- [현재 상태](../wiki/current-state.md): 알려진 빈틈에서 "같은 키에 다른 payload" 항목을 지우고 payload hash 판정, `V003`, `CONFLICT`의 첫 사용처, 테스트 수를 반영한다.
- [변경 기록](../wiki/changes.md): 이 spec 링크와 결정 근거를 남긴다.
- [도메인 모델](../wiki/domain-model.md)은 고치지 않는다. aggregate 경계와 용어가 바뀌지 않고, `payloadHash`는 `Order` 루트의 속성 하나다.

## 분할 계획
예상 규모: 구현과 마이그레이션 약 60줄, 테스트 약 200줄, 문서 약 150줄로 총 410줄·10파일 내외. 경고 한도(600줄·20파일) 안이다. 초과하면 HTTP 통합(인수 조건 9)을 후속 PR로 옮긴다. 후속 순서는 빈 상품 코드 규칙, `RequestCheckout`이다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 1~3, `OrderTest`의 payload hash와 `verifySamePayload` | 미실행 | 미실행 | 미실행 |
| 인수 4~7·10, `OrderCommandFacadeIntegrationTest`의 충돌·순서 무시·hash 저장·null hash, `SchemaMigrationTest` | 미실행 | 미실행 | 미실행 |
| 인수 8, `OrderControllerTest`의 409와 REST Docs `orders-create-idempotency-conflict` | 미실행 | 미실행 | 미실행 |
| 인수 9, `OrderApiIntegrationTest`의 201 뒤 409 | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
