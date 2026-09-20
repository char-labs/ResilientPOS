# PR spec: 같은 주문 생성 키에 다른 payload가 오면 409 IDEMPOTENCY_CONFLICT

## 목적
같은 `(terminalId, clientOrderId)`로 내용이 다른 `CreateOrder` 요청이 오면 지금은 `OrderCommandFacade`가 기존 결과와 201을 그대로 돌려준다. 단말은 자신이 보낸 주문이 만들어졌다고 믿지만 서버에는 다른 내용의 주문이 있다. 이 PR이 끝나면 payload가 같은 재시도만 기존 결과를 받고, payload가 다르면 `DomainException(IDEMPOTENCY_CONFLICT, kind=CONFLICT)`이 나가 409로 응답한다. 요구사항은 [order-create-order-api](2026-09-20-order-create-order-api.md)의 제외 절과 [foundation-domain-exception](2026-09-19-foundation-domain-exception.md)의 제외 절에서 이 PR로 미룬 것이다.

## 범위
소유 모듈은 `order`다. 공개 계약 `CreateOrderCommand`·`CreateOrderResult`와 HTTP 요청·응답 모양은 바꾸지 않는다. 바뀌는 계약은 `POST /v1/orders`에 409 `IDEMPOTENCY_CONFLICT` 응답이 생기는 것 하나다.

### 범위 조정 (2026-09-20, PR #26 리뷰): payload hash 저장을 직접 비교로 교체
처음 spec은 최초 요청의 SHA-256 hash를 `t_order.payload_hash`(`V003`)에 저장해 비교했다. PR 리뷰에서 "컬럼 없이도 검증할 수 있다"는 지적이 나왔고 다음 이유로 직접 비교로 바꾼다.
- hash 대상 필드(`storeId`, `currency`, line의 `productCode`·`productName`·`unitPrice`·`quantity`)는 이미 `t_order`와 `t_order_line`에 전부 저장되어 있다. hash는 같은 정보의 중복 저장이고, 같은지 다른지만 알려줄 뿐 어느 필드가 다른지도 알려주지 못한다.
- hash를 저장한 근거는 "`Order`는 `OPEN`에서 line이 바뀌므로 현재 상태가 최초 요청과 달라진다"였다. 하지만 line을 바꾸는 명령은 아직 없다. 그 명령이 생기기 전까지는 저장된 상태가 최초 요청과 같으므로, 그 명령을 추가하는 PR에서 현재 상태로 최초 요청 기록을 채울 수 있다. "나중에는 backfill할 수 없다"는 처음 판단은 틀렸다.
- 직접 비교는 마이그레이션, 엔티티 필드, canonical 문자열과 구분자 처리, V003 이전 행을 비교 없이 통과시키는 예외를 모두 없앤다. 기존 행도 똑같이 검증된다.
- 같은 실수를 반복하지 않도록 새 컬럼·테이블 추가 전 확인 세 가지(기존 데이터로 도출·비교할 수 있는가, 지금 읽는 코드가 있는가, 나중에 채울 수 있는가)와 판정 기준을 [도메인 모델](../wiki/domain-model.md)의 "이벤트 전달과 저장 형식" 절과 [AGENTS.md](../../AGENTS.md)에 추가한다. 지침 변경을 기능 PR에 넣는 것은 이 PR이 그 규칙의 근거 사례이고 사용자가 같은 PR에 반영하도록 요청했기 때문이다.

### 범위 조정 2 (2026-09-20): 스키마 변경 검토를 정합성·락 관점으로 확장
위 규칙은 불필요한 컬럼을 막는 쪽만 다뤘다. 이후 Redis와 Kafka가 들어오고 정합성과 락을 염두에 두고 개발할 예정이라서 반대 방향의 실수와 반영 단계의 실수도 같은 검토에서 걸러야 한다. unique 제약·`@Version`·멱등 키처럼 정합성을 지키는 컬럼을 "Redis에 있다", "이벤트로 알 수 있다"는 이유로 빼는 실수와, 마이그레이션이 테이블 쓰기를 막거나 이전 버전 애플리케이션의 기동·INSERT를 깨는 실수다.
- [도메인 모델](../wiki/domain-model.md)에 "스키마 변경 검토" 절을 새로 두고 첫 범위 조정에서 "이벤트 전달과 저장 형식" 절에 넣은 규칙을 옮긴다. 검토는 필요성, 정합성, 반영 세 관점의 질문이다. 기존 원칙(README의 데이터 정본 원칙, 청사진 6.2절·7.1절, 개발 가이드 2.5절)을 새로 정하지 않고 스키마 변경 시점의 질문으로 모은다.
- 마이그레이션이 있는 PR은 spec에 `### 스키마 변경 검토` 절을 두고 질문에 답한다. 답은 구현자가 아닌 리뷰어(에이전트 작업이면 읽기 전용의 다른 에이전트)가 실제 DDL·엔티티·코드와 대조한다. 이 PR에서 `payload_hash`는 구현자가 스스로 승인해서 통과했다.
- `check_pr.py`가 이 절의 존재를 검사하게 하는 일은 하네스 변경이라서 이 PR에 넣지 않는다. 이 PR이 머지되어 절 이름이 main에 생긴 뒤의 후속 PR이다.

### 스키마 변경 검토
이 PR의 최종 변경에는 마이그레이션이 없다. 검토했다가 뺀 `t_order.payload_hash`의 답을 사례로 남긴다.
- 필요성: 기존 데이터로 같은 판단을 할 수 있다(`t_order`·`t_order_line`에 대상 필드가 전부 있다). line 변경 명령이 생기는 시점에 현재 상태로 채울 수 있다. 그래서 추가하지 않는다.
- 정합성: 마지막 방어선은 `t_order`의 unique `(terminal_id, client_order_id)`이고 이 PR이 건드리지 않는다. 충돌 판정 경로는 조회한 뒤 비교하고 쓰지 않으므로 check-then-act 경합이 없다. 생성 경로의 경합(둘 다 조회에서 찾지 못함)은 unique 제약이 막고 주문이 둘 생기지 않는다. 이때 진 쪽이 기존 결과 대신 오류를 받는 것은 제외 절의 알려진 빈틈이다. Redis가 들어와도 중복 요청 완화 힌트일 뿐이고 이 비교는 PostgreSQL에서 한다.
- 다시 볼 조건의 정합성 근거: `OrderPersistenceAdapter`는 주문과 line을 다른 쿼리로 읽는다. line을 바꾸는 명령이 생기면 READ COMMITTED에서 재시도 요청이 변경 전 주문과 변경 후 line을 함께 읽을 수 있다. 최초 요청 기록을 `t_order` 한 행에 두면 이 문제가 없다. 그 PR이 최초 요청 기록을 도입해야 하는 이유가 하나 더 있는 셈이다.
- 반영: 해당 없음.

### 결정 1. 비교 대상과 같은 payload의 기준
- 대상: `storeId`, `currency`, 각 line의 `productCode`·`productName`·`unitPrice`(금액과 통화)·`quantity`.
- `terminalId`와 `clientOrderId`는 비교하지 않는다. 조회 키 자체라서 기존 주문을 찾았다면 이미 같다.
- 기준은 HTTP 본문의 바이트가 아니라 `CreateOrderCommand`의 값이다. JSON 필드 순서·공백과 `"4500"`·`"4500.0"` 같은 표기 차이는 DTO와 `Money.of`를 거치면서 이미 사라진다. `sync`가 나중에 같은 Facade를 호출할 때도 같은 판정을 받는다.
- 금액은 `Money`의 값 동등성으로 비교한다. `Money`가 통화의 표준 소수 자릿수를 canonical scale로 강제하고 `OrderLineEntity.toDomain`도 같은 scale로 복원하므로 같은 금액은 항상 같은 값이다. line 단가의 통화도 `Money`에 들어 있어서 함께 비교된다.
- line 순서는 무시한다. 저장된 line과 command의 line을 각각 `(productCode, productName, unitPrice, quantity)` 값의 개수 묶음으로 만들어 비교한다. 같은 line이 두 번 있는 주문과 한 번 있는 주문은 다른 payload다. 순서는 표시 순서일 뿐이며 판매한 상품과 합계를 바꾸지 않는다. 오판정의 비용도 비대칭이다. 순서만 다른 재시도를 충돌로 판정하면 단말의 재시도가 영원히 409로 막히고, 같은 요청으로 판정하면 내용이 같은 기존 주문을 돌려줄 뿐이다.
- `productCode`·`productName`은 받은 그대로 비교한다. trim, 대소문자, 유니코드 정규화를 하지 않는다.

### 결정 2. 판정 위치와 저장
- 판정은 `order/internal/domain`의 `Order.verifySamePayload(command)`가 맡고 다르면 `DomainException(IDEMPOTENCY_CONFLICT, kind=CONFLICT)`을 던진다. "같은 키의 요청이 최초 요청과 같은가"는 업무 규칙이고 [청사진 1절](../references/05_Domain_Development_Blueprint.md)은 Facade에 aggregate rule 구현을 금지한다. `OrderCommandFacade`는 기존 주문을 찾았을 때 결과를 돌려주기 전에 `verifySamePayload`를 호출하는 한 줄만 늘어난다.
- 새 컬럼과 마이그레이션은 없다. 저장된 주문의 현재 상태와 비교한다.
- 다시 볼 조건: line이나 `storeId`·`currency`를 바꾸는 명령이 생기는 PR. 그 시점부터 현재 상태가 최초 요청과 달라지므로, 그 PR이 최초 요청 기록(예: payload hash 컬럼)을 도입하고 기존 행은 그때의 현재 상태로 채운다. [개발 가이드 3.3절](../references/03_Development_Guide.md)의 request payload hash는 그때의 선택지다. 코드에는 `ponytail:` 주석으로 이 조건을 남긴다.
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
- 최초 요청 기록(payload hash), 공용 idempotency record 테이블. 위 "다시 볼 조건"이 생길 때 다룬다.
- 인접 코드 리팩터링.

## 관련 문서
없음. 선행 PR은 [order-create-order-api](2026-09-20-order-create-order-api.md), [foundation-domain-exception](2026-09-19-foundation-domain-exception.md), [order-create-order-persistence](2026-09-16-order-create-order-persistence.md)다. persistence spec은 payload 불일치 판정을 `sync`의 operation hash 비교에 맡긴다고 적었지만, #24에서 `POST /v1/orders`가 `sync`를 거치지 않는 공개 경로가 되었으므로 Facade가 직접 막는다. 근거 문서는 [시스템 설계 3.1절](../references/02_System_Design.md)의 "동일 key에 다른 payload면 409 IDEMPOTENCY_CONFLICT"다. 앞선 두 spec이 이 PR을 "payload hash 비교"라고 불렀지만 합의한 요구사항은 다른 payload의 409 거절이고 hash는 수단이었다.

## 변경 경로
- src/main/kotlin/com/resilientpos/order/**
- src/test/kotlin/com/resilientpos/order/**
- docs/spec/2026-09-20-order-idempotency-conflict.md
- docs/wiki/current-state.md
- docs/wiki/domain-model.md
- docs/wiki/changes.md
- AGENTS.md

## 인수 조건
도메인(1~2)은 Spring 없는 `OrderTest`, Facade(3~4)는 실제 PostgreSQL을 쓰는 `OrderCommandFacadeIntegrationTest`, 웹 슬라이스(5)는 `OrderControllerTest`, HTTP 통합(6)은 `OrderApiIntegrationTest`로 검증한다.
1. `verifySamePayload`는 같은 command, line 순서만 다른 command, 금액 표기만 다른 command(USD `"45"`와 `"45.00"`)에 던지지 않는다.
2. `verifySamePayload`는 `storeId`, `currency`, line의 `productCode`·`productName`·`unitPrice`·`quantity`, line 개수 중 하나라도 다른 command에 `DomainException`(code `IDEMPOTENCY_CONFLICT`, kind `CONFLICT`)을 던진다. 같은 line이 두 번 있는 command와 한 번 있는 command도 다른 payload다.
3. 같은 키에 수량이 다른 command를 보내면 Facade가 `IDEMPOTENCY_CONFLICT`·`CONFLICT`를 던진다. `t_order` 행은 하나이고, 저장된 주문의 line은 최초 요청 그대로이며, `OrderCreated`는 한 번만 발행된다.
4. 같은 키에 line 순서만 바꾼 command를 보내면 기존 결과(`orderId` 동일)를 돌려준다. 같은 command 재시도도 기존 결과다(기존 테스트 유지. DB에서 복원한 금액이 command의 금액과 같은 값인지도 이 테스트가 확인한다).
5. Facade가 `DomainException(IDEMPOTENCY_CONFLICT, kind=CONFLICT)`을 던지면 409이고 `result`가 `FAIL`, `data`가 null, `error.errorCode`가 `IDEMPOTENCY_CONFLICT`다. 요청·응답 필드가 `relaxed` 없이 문서화되고 `build/generated-snippets/orders-create-idempotency-conflict`에 스니펫이 생긴다.
6. 같은 `storeId`·`terminalId`·`clientOrderId`로 수량만 다른 본문을 이어서 보내면 첫 응답은 201, 두 번째 응답은 409 `IDEMPOTENCY_CONFLICT`이고 `t_order`에 행이 하나다. 같은 본문 재시도는 계속 201이다(기존 테스트 유지).
7. 마이그레이션은 `V001`·`V002` 그대로이고 `SchemaMigrationTest`, `ModuleStructureTest`, `DependencyDirectionTest`가 변경 없이 통과한다.
8. [도메인 모델](../wiki/domain-model.md)에 "스키마 변경 검토" 절이 있고 필요성·정합성·반영 세 관점의 질문, spec에 답을 적는 규칙, 구현자가 아닌 리뷰어의 대조 규칙을 담는다. [AGENTS.md](../../AGENTS.md)가 한 줄로 이 절을 가리킨다. 지침 변경은 `workflow-reviewer`의 읽기 전용 검토를 거친다.
9. `python3 scripts/check_pr.py --base b412ccf`가 통과한다.

### Checkpoint와 검증 명령
| Checkpoint | 검증 명령 | 의존 |
|---|---|---|
| domain-compare (1~2) | `./gradlew test --tests 'com.resilientpos.order.internal.domain.OrderTest'` | 없음 |
| facade-conflict (3~4, 7) | `./gradlew test --tests 'com.resilientpos.order.internal.application.OrderCommandFacadeIntegrationTest' --tests 'com.resilientpos.SchemaMigrationTest'` | domain-compare |
| web-409 (5~6) | `./gradlew test --tests 'com.resilientpos.order.internal.adapter.web.*'` | facade-conflict |

## 검증
```sh
./gradlew test
ls build/generated-snippets
python3 scripts/check_pr.py --base b412ccf --worktree
```
- 정상: payload가 같은 재시도와 line 순서만 다른 재시도가 201과 같은 `orderId`를 받는다.
- 실패: payload가 다른 요청이 409 `IDEMPOTENCY_CONFLICT`를 받고 기존 주문이 바뀌지 않는다.

## Wiki 영향
- [현재 상태](../wiki/current-state.md): 알려진 빈틈에서 "같은 키에 다른 payload" 항목을 지우고 직접 비교 판정, `CONFLICT`의 첫 사용처, 다시 볼 조건, 테스트 수를 반영한다.
- [도메인 모델](../wiki/domain-model.md): "스키마 변경 검토" 절을 새로 둔다. aggregate 경계와 용어는 바뀌지 않는다.
- [AGENTS.md](../../AGENTS.md): 같은 규칙을 한 줄로 적고 도메인 모델을 가리킨다.
- [변경 기록](../wiki/changes.md): 이 spec 링크, 결정 근거, 범위 조정을 남긴다.

## 분할 계획
예상 규모: 구현 약 20줄, 테스트 약 170줄, 문서 약 170줄로 총 360줄·10파일 내외. 경고 한도(600줄·20파일) 안이다. 초과하면 HTTP 통합(인수 조건 6)을 후속 PR로 옮긴다. 후속 순서는 빈 상품 코드 규칙, `RequestCheckout`이다. 별도로 마이그레이션이 있는 PR의 spec에 `### 스키마 변경 검토` 절이 있는지 `check_pr.py`가 검사하게 하는 하네스 PR이 이 PR의 머지 뒤에 이어진다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 2, `OrderTest`의 `verifySamePayload는 필드가 다르면 IDEMPOTENCY_CONFLICT를 던지고 같으면 던지지 않는다`(`storeId`·`currency`·`productCode`·`productName`·`unitPrice`·`quantity`·line 개수·같은 line 중복 variant) | `./gradlew test --tests 'com.resilientpos.order.internal.domain.OrderTest'`. hash 코드와 V003을 지우고 `verifySamePayload`의 본문을 비운 상태에서 7개 중 1개 실패. 예외가 나오지 않아 AssertionFailedError | 같은 명령. `storeId`·`currency` 동등성과 line 값의 `groupingBy.eachCount()` 비교를 구현한 뒤 7개 전부 통과 | 같은 명령 7개 통과 유지 |
| 인수 1, `OrderTest`의 `verifySamePayload는 같은 command와 line 순서·금액 표기만 다른 command에 던지지 않는다` | 빈 본문도 예외를 던지지 않으므로 RED를 만들 수 없다. characterization. 구현 뒤에도 통과하는 것으로 확인했다 | 위와 같은 명령 7개 통과 | 위와 같음 |
| 인수 3, `OrderCommandFacadeIntegrationTest`의 `같은 키에 수량이 다른 command는 IDEMPOTENCY_CONFLICT를 던지고 기존 행과 line을 바꾸지 않는다` | `./gradlew test --tests 'com.resilientpos.order.internal.application.OrderCommandFacadeIntegrationTest' --tests 'com.resilientpos.SchemaMigrationTest'`. `verifySamePayload`의 본문을 비운 상태에서 12개 중 1개 실패. 예외가 나오지 않아 `assertFailsWith`가 AssertionFailedError. 도메인 구현을 끝낸 뒤 이 단계의 RED를 빠뜨린 것을 알고 본문을 다시 비워서 관측했다 | 구현을 되돌린 뒤 같은 명령 12개 전부 통과 | `OrderTest` 7개 통과 유지 |
| 인수 4·7, 같은 클래스의 `같은 키에 line 순서만 바꾼 command는 기존 결과를 돌려준다`, `같은 clientOrderId 재시도는 기존 결과를 돌려주고 행을 늘리지 않는다`(DB에서 복원한 단가가 command의 단가와 같은 값인지 assertion 추가), `SchemaMigrationTest`의 `001`·`002` 기록 | "던지지 않는다" 쪽이라서 빈 본문으로도 통과한다. characterization. 직접 비교에서는 DB 복원 금액의 scale이 다르면 이 두 테스트가 충돌로 실패하므로 구현 뒤의 통과가 의미를 가진다 | 위와 같은 명령 12개 통과 | 위와 같음 |
| 인수 5, `OrderControllerTest`의 `Facade가 IDEMPOTENCY_CONFLICT를 던지면 409이고 도메인 오류 코드를 담는다`와 REST Docs `orders-create-idempotency-conflict` | 미실행. `CONFLICT`의 409 매핑이 #23에 이미 있어서 작성하자마자 통과했다. characterization. 범위 조정으로 바뀌지 않은 테스트다 | `./gradlew test --tests 'com.resilientpos.order.internal.adapter.web.*'`. `OrderControllerTest` 8개 통과, 스니펫 디렉터리 생성 확인 | 범위 조정 뒤 같은 명령 8개 통과 |
| 인수 6, `OrderApiIntegrationTest`의 `같은 storeId terminalId clientOrderId로 수량만 다른 요청을 이어서 보내면 두 번째는 409 IDEMPOTENCY_CONFLICT다` | 구현이 끝난 뒤에 작성해서 처음부터 통과했다. Facade의 `verifySamePayload` 호출을 임시로 지우고 실행해 3개 중 이 테스트 1개가 상태 코드 assertion에서 AssertionError로 실패하는 것을 확인했다. 범위 조정으로 바뀌지 않은 테스트다 | 호출을 되돌린 뒤 같은 명령 3개 통과 | 범위 조정 뒤 `./gradlew clean test` 55개 통과, 실패 0 |

범위 조정 전 기록: hash 방식은 `OrderTest` 4개와 `OrderCommandFacadeIntegrationTest` 4개로 RED(최소 선언 상태에서 도메인 3개 실패, Facade 호출이 없는 상태에서 충돌 테스트 1개 실패)와 GREEN을 확인했고 `./gradlew clean test` 59개가 통과했다(커밋 `3d41a1b`·`6637d04`). 이 중 hash 형식, 구분자 경계, hash 저장, null hash 테스트 4개는 범위 조정으로 없어졌다.

## 결과
`Order`에 `verifySamePayload`를 추가했고 `OrderCommandFacade`는 기존 주문을 찾은 분기에 호출 한 줄이 늘었다. 기준 커밋 대비 프로덕션 변경은 이 두 파일뿐이다. `OrderEntity`와 마이그레이션은 기준 커밋과 같고 새 컬럼, 새 파일, 새 의존성이 없다. `platform`, `CreateOrderCommand`, Controller, DTO는 고치지 않았다.

처음 구현은 최초 요청의 SHA-256 hash를 `t_order.payload_hash`(`V003`)에 저장했다(커밋 `3d41a1b`). PR #26 리뷰에서 컬럼 없이도 검증할 수 있다는 지적을 받아 저장된 주문과의 직접 비교로 바꿨다. 비교는 `storeId`·`currency`의 동등성과 line 값 `(productCode, productName, unitPrice, quantity)`의 개수 묶음 비교다. canonical 문자열, 구분자 처리, V003 이전 행을 비교 없이 통과시키는 예외가 함께 없어졌고 기존 행도 똑같이 검증된다. `Order.verifySamePayload` 안에 `ponytail:` 주석으로 다시 볼 조건을 남겼다.

직접 비교는 DB에서 복원한 금액이 command의 금액과 같은 값이어야 성립한다. `OrderLineEntity.toDomain`이 `NUMERIC(19,4)`를 통화의 표준 scale로 되돌리므로 같은 값이 되고, 재시도 통합 테스트에 단가 동등성 assertion을 추가해 고정했다.

`OrderApiIntegrationTest`의 `requestJson`은 호출할 때마다 `storeId`·`terminalId`를 새로 만들었는데, 두 요청이 같은 키를 쓰도록 기본값이 있는 파라미터로 바꿨다. 기존 테스트 2개의 동작은 그대로다.

지침: 새 컬럼·테이블 추가 전 확인 세 가지와 판정 기준을 도메인 모델의 "이벤트 전달과 저장 형식" 절에 두고 AGENTS.md에 한 줄로 가리켰다. `workflow-reviewer`의 읽기 전용 검토에서 첫 문구의 판정 문장이 "기존 데이터로 판단할 수 있거나 나중에 채울 수 있으면 추가하지 않는다"여서 기본값으로 채울 수 있는 상태 컬럼까지 막는다는 지적을 받았다. "나중에 채울 수 있는 값인데 기존 데이터로 지금 판단할 수 있거나 지금 읽는 코드가 없으면 추가하지 않는다"로 고치고, 상태·멱등 키·판매 시점 snapshot은 추가 대상임을 적었다. 확인 (1)의 대상을 컬럼·테이블로 넓히고 머지 뒤의 제거 비용을 한 문장 더했다. 개발 규칙의 Flyway 절에 포인터를 하나 더 두라는 권장은 반영하지 않았다. AGENTS.md가 매 세션 로드되어서 같은 안내를 세 곳에 두는 비용이 더 크다.

`./gradlew clean test`는 55개 테스트가 모두 통과했다(실패 0, Docker·Testcontainers PostgreSQL 사용). 기존 49개에 `OrderTest` 2개, `OrderCommandFacadeIntegrationTest` 2개, `OrderControllerTest` 1개, `OrderApiIntegrationTest` 1개가 늘었다. `build/generated-snippets`에 `orders-create-idempotency-conflict`가 생긴다.
