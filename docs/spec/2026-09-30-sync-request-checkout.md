# PR spec: 단말의 오프라인 결제 요청 operation을 sync 배치로 받는다

## 목적
단말은 오프라인에서 주문을 만들고 곧바로 결제를 요청한다. 재연결 뒤 sync 배치로 주문은 보낼 수 있지만 결제 요청은 보낼 길이 없다. `POST /v1/orders/{orderId}/checkout`은 서버가 만든 `orderId`를 요구하는데, 단말은 같은 배치의 앞 항목이 적용되기 전에는 그 값을 모른다. 단말이 아는 것은 자기가 만든 `clientOrderId`다([sync-intake](2026-09-30-sync-intake.md) 결정 3과 제외 절 첫 항목).

기대 결과:
- sync 배치 항목이 종류(`kind`)를 가진다. `CREATE_ORDER`와 `REQUEST_CHECKOUT` 둘이다.
- `REQUEST_CHECKOUT` 항목은 `clientOrderId`만 싣고, 서버는 그 단말의 주문을 찾아 checkout을 요청한다. 판정·checkout 확정·`OrderPaymentRequested` 발행 기록·접수 기록·watermark 전진이 한 커밋이다(도메인 모델 §4.6).
- 접수 기록 `t_sync_operation`이 종류를 남긴다.

## 범위
소유 모듈은 `sync`(종류 분기, 접수 기록의 종류)와 `order`(`clientOrderId`로 찾는 checkout 요청 경로)다.

**결정 1: `order`에 `clientOrderId`로 찾는 checkout 요청을 새 커맨드와 메서드로 더한다.**
- 문제: 단말은 오프라인에서 `orderId`를 모른다.
- 제약: `findByIdForUpdate`는 그 트랜잭션에서 주문의 첫 접촉이어야 한다. sync가 `clientOrderId`로 먼저 읽고 `orderId`로 다시 잠그면 이 조건을 어긴다. 기존 `RequestCheckout(orderId)`는 HTTP 계약이라 모양을 바꾸지 않는다.
- 수단: `OrderCommand.RequestCheckoutByClientOrderId(terminalId, clientOrderId)`와 그것을 받는 `OrderCommandFacade.requestCheckout` 오버로드. 결과는 `OrderCommand.RequestCheckout.Result`를 그대로 쓴다. `OrderRepository.findByClientOrderIdForUpdate(terminalId, clientOrderId)`가 키로 잠그고 읽는다(`PESSIMISTIC_WRITE`, 대기 3초). 주문이 없으면 `ORDER_NOT_FOUND`다. 전이·저장·발행·결과 변환은 두 메서드가 private 함수 하나를 함께 쓴다.
- `create`와 달리 합류 전용 메서드를 따로 두지 않는다. `requestCheckout`은 `Lock` 블록이 아니라 `Tx.masterTx`(`REQUIRED`)라서 sync 항목 트랜잭션 안에서 부르면 그대로 합류한다. 트랜잭션 밖에서 불러도 스스로 한 트랜잭션이라 호출 조건 계약이 필요 없다.
- `terminalId`는 sync 경로의 값이므로 다른 단말의 주문은 찾지 못한다.

**결정 2: 요청 항목은 `kind`를 반드시 가진다.** 없거나 모르는 값이면 Jackson이 기존 형식 오류와 같은 경로로 배치를 `400 BAD_REQUEST`로 거절한다. 서버가 종류를 추측하지 않는다. 배포된 단말이 없어 `kind` 없는 지금의 본문을 받아 줄 이유가 없다.

```json
{
  "operations": [
    { "operationId": "0199-a", "sequence": 1, "kind": "CREATE_ORDER",
      "order": { "storeId": "0199-s", "clientOrderId": "0199-c", "currency": "KRW", "lines": [] } },
    { "operationId": "0199-b", "sequence": 2, "kind": "REQUEST_CHECKOUT",
      "clientOrderId": "0199-c" }
  ]
}
```

- `adapter/web`: `SyncOperationRequest`를 `kind`로 갈리는 sealed 계층(`CreateOrder`, `RequestCheckout`)으로 바꾼다.
- `domain`: `sealed interface OperationPayload`가 `kind`와 `hash()`를 가진다. 구현은 기존 `OrderPayload`와 새 `CheckoutPayload(clientOrderId)`다. `SyncOperationKind`(`CREATE_ORDER`·`REQUEST_CHECKOUT`)는 요청의 `kind` 값이자 DB 저장 값이다.
- `SyncCommand.ReceiveBatch.Operation`의 `order`는 `payload`가 된다.
- `SyncCommandFacade`는 항목 트랜잭션 안에서 else 없는 `when`으로 갈라 `createInCallerTransaction` 또는 `requestCheckout`을 부른다. `when`은 payload 타입으로 갈리므로 `OperationPayload` 구현을 더하면 이 `when`이 컴파일되지 않는다. enum 상수나 요청 DTO의 하위 타입만 더하는 것은 컴파일러가 잡지 않는다. 분기가 한 곳이라 전략 클래스로 올리지 않는다.
- `CheckoutPayload.hash()`는 `OrderPayload`와 같은 공식이다. `REQUEST_CHECKOUT`과 `clientOrderId`를 각각 `길이:값;`으로 이은 문자열의 SHA-256 hex다. `OrderPayload`의 공식과 접두어는 그대로라 이미 기록된 hash가 바뀌지 않는다.

**결정 3: `t_sync_operation`에 `kind` 컬럼을 더한다.** 업무 코드가 읽지 않는 쓰기 전용 컬럼이다. 그래도 지금 더하는 이유는 나중에 채울 수 없어서다. 두 종류가 섞인 뒤에는 기존 행의 종류를 다른 컬럼에서 알아낼 수 없다(hash는 단방향이고 `order_id`는 두 종류 모두 가진다). 지금은 모든 행이 `CREATE_ORDER`라 상수 기본값이 정확한 값이다. 답은 아래 스키마 변경 검토 절에 있다.

**판정.** `REQUEST_CHECKOUT` 항목도 기존 판정 표를 그대로 탄다([sync-intake](2026-09-30-sync-intake.md) 범위 절).
- 다음 순번이고 주문이 있으면 `APPLIED`와 `orderId`. 주문이 이미 `CHECKOUT_REQUESTED`나 `PAID`여도 도메인의 멱등 전이라 `APPLIED`다. 같은 주문에 다른 `operationId`로 결제 요청이 두 번 와도 둘 다 `APPLIED`이고 checkout 회차는 하나다.
- 주문이 없으면 `order`가 `ORDER_NOT_FOUND`로 거절하므로 `REJECTED`다. 앞 순번의 `CREATE_ORDER`가 거절됐을 때가 대표적이다. 합계가 금액 상한을 넘는 주문의 `INVALID_MONEY_AMOUNT`도 같은 경로다.
- 재전송은 `DUPLICATE`, 같은 `operationId`에 다른 `clientOrderId`나 다른 종류가 오면 hash가 달라 `CONFLICT`다.
- 주문 행 잠금 대기 초과는 인프라 실패라 항목 결과가 아니고 배치 오류(`409 CONCURRENT_REQUEST`)다.

**잠금.** `REQUEST_CHECKOUT` 항목 트랜잭션은 행 잠금을 둘 기다린다. watermark 행 다음에 주문 행이고 각각 대기 3초다. 순서는 늘 같다. HTTP checkout과 `markPaid`는 주문 행만 잡고 watermark를 건드리지 않으므로 서로를 기다리는 순환이 없다. Redis `Lock`은 이 경로에 없다.

**이벤트.** `OrderPaymentRequested`는 합류한 항목 트랜잭션에서 발행되어 발행 기록이 접수 기록과 같은 커밋에 들어간다. 이벤트 모양·토픽·consumer는 바뀌지 않는다. 결제 시도 생성은 지금처럼 `payment`의 consumer가 잇는다.

**HTTP.** endpoint와 응답 모양은 그대로다. `REQUEST_CHECKOUT` 항목의 응답도 `operationId`·`status`·`orderId`·`errorCode`다.

**계약**: 새 `DomainException` code는 없다. 요청 항목의 `kind`가 필수가 되는 것이 단말 계약의 변화다. `SyncOperationKind`의 이름은 세 곳에 묶여 있다. 단말 요청의 `kind` 값, `t_sync_operation.kind`의 저장 값, `payload_hash` 공식의 접두어다. 앞의 둘은 바꾸면 단말 계약과 데이터 마이그레이션이 필요하고, 셋째는 되돌릴 수 없다. hash가 단방향이라 다시 계산할 수 없어서 이름을 바꾸면 확정된 operation의 재전송이 모두 `CONFLICT`가 된다. 그래서 이름을 바꾸지 않는다. `PayloadHashTest`의 고정 hex가 이것을 잡는다.

**관측성**: 기존 배치 INFO 로그와 `CONFLICT` WARN 로그 그대로다. 더하는 것은 없다.

## 제외
- 응답 항목에 `checkoutNo`·확정 금액을 싣는 것. `DUPLICATE`에도 같은 값을 주려면 접수 기록에 컬럼이 더 필요하고 지금 읽는 단말이 없다. nullable 필드 추가라 나중에 넣어도 계약이 깨지지 않는다.
- 단말이 계산한 합계와 서버 확정 금액의 대조. 합계는 단가 곱 수량의 합뿐이라 어긋날 계산이 없다. 할인·세금이 들어오는 PR이 본다.
- 거절된 결제의 재결제 operation. 입구는 `PaymentManagement.retryAuthorization`뿐이고 단말이 거절 사실을 아는 조회도 아직 없다.
- `kind` 컬럼의 기본값 제거. 새 코드는 늘 값을 적으므로 기본값은 이전 버전 인스턴스가 내려간 뒤에는 쓰이지 않는다. 앱 코드는 `kind` 없이 접수 기록을 만들 수 없으므로 기본값이 남아서 생기는 일은 `kind`를 빠뜨린 수동 SQL이 실패하지 않고 `CREATE_ORDER`로 기록되는 것 하나다. 제거는 이 컬럼을 적는 코드만 배포에 남은 다음 PR의 일이다.
- 종류별 전략 클래스나 핸들러 레지스트리. 분기가 Facade의 `when` 한 곳이다. 같은 분기가 두 곳 이상에 생기면 다시 본다.
- **`ORDER_NOT_FOUND` 거절은 다른 거절과 달리 시점에 따라 달라질 수 있다.** 거절이 기록된 뒤에 같은 `clientOrderId`의 주문이 HTTP로 만들어져도 그 operation은 `REJECTED`로 남고 단말은 이미 버렸다. 단말이 새 `operationId`로 다시 요청해야 한다.
- 롤링 배포 중 이전 버전 인스턴스가 `REQUEST_CHECKOUT` 항목을 받으면 `order` 필드가 없어 배치가 `400`이다. 앞 항목의 커밋은 남고 단말의 재전송이 새 인스턴스에 닿으면 이어진다.
- 셋째 종류를 더하는 롤링 배포에서 옛 인스턴스가 모르는 `kind` 값의 행을 `findOperation`으로 읽으면 enum 복원이 실패해 `CONFLICT`가 아니라 배치 오류가 된다. 그 operationId가 옛 인스턴스에 다시 와야 하는 드문 경로이고 `status`도 같은 성질이다. 셋째 종류를 더하는 PR이 다룬다.
- 단말 인증·소유권 검사, `SyncOperationApplied` 이벤트, metric은 [sync-intake](2026-09-30-sync-intake.md)의 제외 그대로다.

## 관련 문서
없음

## 변경 경로
- src/main/kotlin/com/resilientpos/sync/**
- src/main/kotlin/com/resilientpos/order/OrderCommand.kt
- src/main/kotlin/com/resilientpos/order/OrderCommandFacade.kt
- src/main/kotlin/com/resilientpos/order/internal/domain/OrderRepository.kt
- src/main/kotlin/com/resilientpos/order/internal/adapter/persistence/command/OrderPersistenceAdapter.kt
- src/main/resources/db/migration/V011__sync_operation_kind.sql
- src/test/kotlin/com/resilientpos/**
- docs/wiki/domain-model.md
- docs/wiki/entity-map.md
- docs/spec/2026-09-30-sync-request-checkout.md

## 인수 조건
1. `OrderCommandFacade.requestCheckout(RequestCheckoutByClientOrderId)`가 그 단말의 `OPEN` 주문을 `CHECKOUT_REQUESTED`로 전이하고 `orderId` 경로와 같은 `Result`(`orderId`·`status`·`checkoutNo`·`amount`)를 돌려주며 `t_order_checkout`에 한 행이 생긴다. 다시 부르면 같은 결과이고 행이 늘지 않는다.
2. 그 키의 주문이 없으면 `ORDER_NOT_FOUND`(`NOT_FOUND`)다. 같은 `clientOrderId`라도 다른 `terminalId`면 찾지 못한다.
3. 순번 1 `CREATE_ORDER`, 순번 2 같은 `clientOrderId`의 `REQUEST_CHECKOUT`을 한 배치로 보내면 둘 다 `APPLIED`이고 `orderId`가 같다. 주문은 `CHECKOUT_REQUESTED`, `t_order_checkout` 한 행, `t_sync_operation` 두 행의 `kind`가 각각 `CREATE_ORDER`·`REQUEST_CHECKOUT`이며 watermark는 2다.
4. 같은 배치를 다시 보내면 둘 다 `DUPLICATE`이고 같은 `orderId`이며 checkout 행과 접수 기록이 늘지 않는다.
5. 주문이 없는 `clientOrderId`의 `REQUEST_CHECKOUT`은 `REJECTED`와 `errorCode` `ORDER_NOT_FOUND`이고 순번은 확정된다. 재전송은 같은 `errorCode`의 `DUPLICATE`이고 그 뒤 순번은 정상 적용된다.
6. 확정된 `REQUEST_CHECKOUT`의 `operationId`에 다른 `clientOrderId`가 오거나, `CREATE_ORDER`로 확정된 `operationId`가 `REQUEST_CHECKOUT`으로 오면 `CONFLICT`이고 아무것도 바뀌지 않는다.
7. 항목 트랜잭션은 하나다. checkout을 확정한 뒤 접수 기록 저장이 unique `(terminal_id, sequence)`로 실패하면 주문은 `OPEN`으로 남고 checkout 행과 `OrderPaymentRequested` 발행 기록이 없다.
8. `CheckoutPayload.hash()`는 같은 `clientOrderId`에 같은 값, 다른 `clientOrderId`에 다른 값이고 같은 `clientOrderId`를 가진 `OrderPayload`의 hash와 다르다. `OrderPayload.hash()`의 값은 이 PR 전과 같다(고정 입력의 기대 hex로 확인). Spring 없는 단위 테스트다.
9. HTTP: `kind`가 `REQUEST_CHECKOUT`인 항목이 `SyncCommand`로 옮겨져 Facade에 닿는다. `kind`가 없거나 모르는 값이면 `400 BAD_REQUEST`이고 Facade를 부르지 않는다. REST Docs 요청 필드에 `kind`와 `REQUEST_CHECKOUT`의 `clientOrderId`를 적는다. HTTP부터 PostgreSQL까지 이어지는 통합 테스트 하나가 인수 3을 HTTP로 확인한다.
10. V011이 `kind varchar(32) not null default 'CREATE_ORDER'`를 더하고 `ddl-auto: validate`가 통과한다. `kind`를 적지 않은 INSERT(이전 버전 인스턴스를 흉내 낸 JDBC)가 성공하고 그 행의 `kind`가 `CREATE_ORDER`다. `SchemaMigrationTest`의 버전 목록에 011이 있다.
11. 기존 `CREATE_ORDER` 동작([sync-intake](2026-09-30-sync-intake.md) 인수 1~6·9)이 그대로 통과한다.
12. `./gradlew test` 전체와 `python3 scripts/check_pr.py --base origin/main --worktree`가 통과한다.

### Checkpoint와 검증 명령
| Checkpoint | 검증 명령 | 의존 |
|---|---|---|
| order-checkout-by-key (1, 2) | `./gradlew test --tests 'com.resilientpos.order.*'` | 없음 |
| sync-domain (8) | `./gradlew test --tests 'com.resilientpos.sync.internal.domain.*'` | 없음 |
| sync-facade (3~7, 10, 11) | `./gradlew test --tests 'com.resilientpos.sync.internal.application.*' --tests 'com.resilientpos.SchemaMigrationTest'` | order-checkout-by-key, sync-domain |
| sync-api (9) | `./gradlew test --tests 'com.resilientpos.sync.internal.adapter.web.*'` | sync-facade |
| 전체 (12) | `./gradlew test` · `python3 scripts/check_pr.py --base origin/main --worktree` | 모두 |

## 스키마 변경 검토
`V011__sync_operation_kind.sql`이 `t_sync_operation`에 컬럼 하나를 더한다.

```sql
set local lock_timeout = '5s';

alter table t_sync_operation
    add column kind varchar(32) not null default 'CREATE_ORDER';
```

매핑은 `SyncOperationEntity.kind`(`SyncOperationKind`, `EnumType.STRING`)이고 같은 표의 `status`와 같은 구성이다.

- 질문 1(도출): 도출할 수 없다. `payload_hash`는 종류 접두어를 넣어 만들지만 단방향이고, `order_id`는 두 종류 모두 가지며, `error_code`는 거절된 행에만 있다.
- 질문 2(읽는 코드): 업무 코드는 읽지 않는다. 재전송 판정은 `payload_hash`가, `DUPLICATE` 응답은 `order_id`·`error_code`가 한다. 읽는 것은 사람의 조사(`CONFLICT` 원인, 어떤 operation이 거절됐나)와 테스트다. 읽는 코드가 없는데도 더하는 근거는 질문 3이다.
- 질문 3(나중에 채우기): 이 PR 뒤에는 채울 수 없다. 지금은 모든 행이 `CREATE_ORDER`라 상수 기본값이 정확하다. `REQUEST_CHECKOUT` 행이 하나라도 쌓이면 기존 행의 종류를 알 방법이 없어 나중에 더하는 컬럼은 그 전 행을 "모름"으로 남긴다. 도메인 모델 §4.7의 "추가하지 않는다" 판정은 나중에 채울 수 있는 값이 전제라 여기에 해당하지 않는다.
- 질문 4(마지막 방어선): 아니다. 제약이 없고 불변식을 지키지 않는다. "같은 operation을 두 번 적용하지 않는다"는 기존 unique 둘과 `payload_hash`가 지킨다.
- 질문 5(정본): 종류라는 사실이 `payload_hash`의 접두어와 `kind` 두 곳에 있다. 둘 다 같은 `OperationPayload`에서 나와 같은 INSERT로 쓰이고, 접수 기록은 넣기만 하고 바꾸지 않아 어긋날 경로가 없다. 판정의 정본은 hash이고 `kind`는 사람이 읽는 사본이다.
- 질문 6(check-then-act): `kind`를 읽고 판단하는 경로는 없다. 이 PR이 더하는 check-then-act는 주문 상태를 읽고 checkout을 확정하는 경로이고 주문 행 잠금(`findByClientOrderIdForUpdate`, `PESSIMISTIC_WRITE`, 대기 3초)이 막는다. 그 뒤의 방어선은 기존 unique `(order_id, checkout_no)`와 `t_order`의 `@Version`이다. 한 경로에 행 잠금이 둘(watermark, 주문)이고 순서가 고정이며 Redis `Lock`과 함께 기다리지 않는다.
- 질문 7(락): 상수 기본값이 있는 컬럼 추가라 메타데이터 변경이고 재작성이 없다. `ACCESS EXCLUSIVE`를 잠깐 잡는다. 긴 트랜잭션 뒤에서 기다리며 뒤 쿼리를 세우지 않도록 `set local lock_timeout`을 둔다. 값은 V005·V008과 같은 5초다. 마이그레이션은 배포 중 한 번 돌고 재실행할 수 있어 요청 경로의 행 잠금(3초)보다 길게 잡는다. Flyway가 이 마이그레이션을 한 트랜잭션으로 실행하므로 `set local`이 그 문장에 걸린다. 시간 초과로 실패하면 기동이 멈추고 다시 실행한다. 행 수는 접수한 operation 수이고 배포된 환경은 아직 없다.
- 질문 8(이전 버전): 호환된다. 이전 버전의 엔티티는 `kind`를 매핑하지 않아 `validate`가 통과하고, INSERT가 `kind`를 적지 않아도 기본값이 채운다. 이전 버전은 `CREATE_ORDER`만 만들므로 그 기본값이 옳은 값이다. SELECT는 매핑된 컬럼만 명시해 영향이 없다. 이전 버전 인스턴스는 요청의 `kind`를 모르는 필드로 무시하므로(이 앱의 Jackson은 모르는 필드를 거절하지 않는다. 이전 DTO에 `kind`를 넣은 요청이 200인 것을 관측했다) 새 형식의 `CREATE_ORDER` 항목은 그대로 적용한다. `REQUEST_CHECKOUT` 항목을 받는 경우는 제외 절에 있다.
- 질문 9(payload): 이벤트 payload와 캐시되는 DTO에 실리지 않는다. 요청의 `kind` 값과 같은 이름이 저장되고 같은 이름이 `payload_hash`의 접두어로 들어간다(범위 절의 계약 문단). 요청 DTO의 하위 타입 이름과 enum 이름은 따로 선언돼 있어 컴파일러가 묶지 않는다. 둘이 같다는 것은 HTTP로 보낸 값과 저장된 값을 두 종류 모두 비교하는 `SyncApiIntegrationTest`가 지킨다.
- 대조 기록: code-reviewer(읽기 전용 에이전트), 2026-09-30. V011·V010, SyncOperationEntity, SyncPersistenceAdapter, sync.internal.domain의 SyncOperation·SyncOperationKind·payload 셋, SyncCommandFacade, SyncBatchRequest, OrderCommandFacade, OrderRepository·OrderPersistenceAdapter, platform JdslQueryExecutor, application.yaml을 질문 1~9의 답과 대조했다. 이전 버전 엔티티는 main 체크아웃(7712a45)의 파일로 확인했다. 불일치는 없었다. 누락 6건을 받았다. enum 이름이 hash 접두어로 들어가 이름을 바꾸면 되돌릴 수 없다는 결합, 요청 DTO의 종류 이름과 enum 이름이 따로 선언된 것, `lock_timeout`이 선례(5초)와 다른 3초였던 것, 기본값을 남기는 대가, 모르는 `kind` 값을 가진 행을 옛 인스턴스가 읽으면 복원에 실패한다는 것, `when`이 막는 범위다. DDL의 `lock_timeout`을 5초로 맞추고 `SyncOperationKind` KDoc에 hash 결합을 적었으며 나머지는 이 절과 범위·제외 절의 문장으로 반영했다. 리뷰어는 셸이 없어 테스트와 Flyway를 실행하지 않았다. 실행 확인은 구현 쪽의 `./gradlew test`(V011 적용, `validate`, `kind` 없는 INSERT)가 했다.

## 검증
- 정상: 주문 생성과 결제 요청을 한 배치로 보내면 둘 다 `APPLIED`, 재전송은 둘 다 `DUPLICATE`.
- 실패: 주문 없는 결제 요청은 `REJECTED`와 전진, 다른 내용의 재전송은 `CONFLICT`, `kind` 누락은 400, 접수 기록 저장 실패는 checkout까지 롤백.
- Docker(OrbStack)가 필요한 통합 테스트는 Testcontainers의 PostgreSQL·Redis·Kafka를 쓴다.
- `./gradlew test`, `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전 `--worktree` 없이 한 번 더.

## Wiki 영향
- [domain-model](../wiki/domain-model.md)
  - §4.6: sync가 부르는 `order` 경로에 `requestCheckout(RequestCheckoutByClientOrderId)`를 더하고, 그 항목이 watermark 행과 주문 행을 차례로 잠근다는 것을 적는다.
  - §4.7 사례의 sync 항목에 `kind` 판단(질문 2·3)을 더한다.
- [entity-map](../wiki/entity-map.md): `SyncOperationEntity`의 `kind`, 주문을 바꾸는 곳에 sync의 checkout 요청, 오프라인 흐름 표에 `REQUEST_CHECKOUT` 단계를 더한다.
- current-state는 볼트 파일이라 저장소 커밋 없이 볼트에서 고친다. `sync` 문단의 "`REQUEST_CHECKOUT` operation은 없다"를 바꾸고 `order` 공개 계약에 새 커맨드를 적는다.
- README·`modulith-kafka-keywords`는 무영향이다. 이벤트·토픽·consumer가 바뀌지 않는다.

## 분할 계획
개발 컨텍스트는 하나다. sync의 둘째 operation 종류를 HTTP부터 커밋까지 잇는 일이고, 그것이 요구하는 `order` 진입점과 접수 기록의 종류 컬럼이 딸려 온다. 작업 시작 시점(origin/main 7712a45, #71 머지 뒤)에 열린 PR이 없어 변경 경로가 겹치지 않는다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 1, `OrderRequestCheckoutIntegrationTest`의 `clientOrderId로 requestCheckout하면 orderId 경로와 같은 결과이고 재요청은 행을 늘리지 않는다` | `./gradlew test --tests 'com.resilientpos.order.internal.application.OrderRequestCheckoutIntegrationTest'`. 커맨드·오버로드·포트를 선언하고 어댑터의 잠금 조회가 늘 null을 돌려주는 최소 선언으로 실행. `DomainException: 주문을 찾을 수 없습니다`로 실패 | 같은 명령. 어댑터를 `(terminal_id, client_order_id)` 잠금 조회로 채운 뒤 통과(11개) | `./gradlew test --tests 'com.resilientpos.order.*'` 통과(95개) |
| 인수 2, 같은 클래스의 `clientOrderId의 주문이 없거나 다른 단말의 키면 ORDER_NOT_FOUND다` | RED 미관측. 늘 null을 돌려주는 최소 선언이 기대와 같은 결과를 내 통과했다 | 같은 명령. 실제 키 조회에서도 통과 | 위와 같은 회귀 통과 |
| 인수 8, `PayloadHashTest`의 `CheckoutPayload의 hash는 같은 clientOrderId에 같고 다른 clientOrderId에 다르다`·`CheckoutPayload의 hash는 종류와 clientOrderId를 길이 접두어로 이은 SHA-256 hex다` | `./gradlew test --tests 'com.resilientpos.sync.internal.domain.*'`. `CheckoutPayload.hash()`가 빈 문자열을 돌려주는 최소 선언으로 실행. 둘 다 `AssertionFailedError` | 같은 명령. 공식을 채운 뒤 통과(10개) | 길이 접두어·SHA-256을 `lengthPrefixedSha256` 하나로 모은 뒤 같은 명령 통과 |
| 인수 8, 같은 클래스의 `OrderPayload의 hash는 저장 형식이라 고정 입력의 기대 hex가 바뀌지 않는다`·`같은 clientOrderId를 가진 OrderPayload와 CheckoutPayload의 hash는 다르다`·`payload는 자기 종류를 돌려준다` | RED 미관측(characterization). 기대 hex는 변경 전 공식을 Python `hashlib`으로 따로 계산한 값이고, 변경 전 코드로 이 테스트를 실행하지는 않았다. 변경 diff는 접두어 상수를 `kind.name`으로 바꾸고 이어붙이기를 공용 함수로 옮긴 것뿐이다 | 같은 명령 통과 | 위와 같은 회귀 통과 |
| 인수 3~6, `SyncCommandFacadeIntegrationTest`의 `주문 생성 다음 순번의 결제 요청이 같은 배치로 오면 둘 다 적용되고 주문은 CHECKOUT_REQUESTED다`·`결제 요청까지 적용된 배치를 다시 보내면 모두 중복이고 checkout 행과 접수 기록이 늘지 않는다`·`주문이 없는 clientOrderId의 결제 요청은 ORDER_NOT_FOUND 거절이고 순번은 확정되며 재전송은 같은 거절의 중복이다`·`확정된 결제 요청 operationId에 다른 clientOrderId가 오거나 주문 생성 operationId가 결제 요청으로 오면 충돌이고 아무것도 바뀌지 않는다` | `./gradlew test --tests 'com.resilientpos.sync.*' --tests 'com.resilientpos.SchemaMigrationTest'`. V011·`kind` 매핑은 갖추고 Facade의 결제 요청 분기만 `UNSUPPORTED` 거절을 던지는 최소 선언으로 실행. 4개 모두 `AssertionFailedError`(expected APPLIED but was REJECTED, checkout 행 expected 1 but was 0, expected ORDER_NOT_FOUND but was UNSUPPORTED) | 같은 명령. 분기에서 `requestCheckout`을 부른 뒤 통과(35개) | `./gradlew test` 통과(317개) |
| 인수 7·10, 같은 클래스의 `결제 요청의 접수 기록 저장이 실패하면 checkout 행과 OrderPaymentRequested 발행 기록도 남지 않고 주문은 OPEN이다`, `SchemaMigrationTest`의 버전 목록 011 | RED 미관측. 최소 선언의 거절 기록도 같은 unique에 걸려 같은 예외와 같은 남은 행을 냈다. V011은 테스트보다 먼저 있었다 | 같은 명령 통과. `kind` 없는 JDBC INSERT가 `CREATE_ORDER`로 채워진다 | 위와 같은 회귀 통과 |
| 인수 9, `SyncControllerTest`의 `배치를 커맨드로 옮겨 Facade를 부르고 200과 항목별 결과·watermark를 돌려준다`(두 종류를 싣도록 고침), `SyncApiIntegrationTest`의 `주문 생성 다음 순번의 결제 요청을 HTTP로 보내면 둘 다 APPLIED이고 같은 주문이 CHECKOUT_REQUESTED다` | `./gradlew test --tests 'com.resilientpos.sync.internal.adapter.web.*'`. 테스트만 고치고 요청 DTO는 그대로 둔 채 실행. 둘 다 `AssertionError`(Status expected 200 but was 400). 기존 DTO가 `kind`를 무시하고 `order`를 요구했다 | 같은 명령. 요청 DTO를 `kind`로 갈리는 sealed 계층으로 바꾼 뒤 통과(6개). 스니펫 `sync-receive-batch`의 요청 필드에 `operations[].kind`·`operations[].clientOrderId`가 생겼다 | `./gradlew test --tests 'com.resilientpos.sync.*'` 통과(32개) |
| 인수 9, `SyncControllerTest`의 `kind가 없거나 모르는 값이면 400 BAD_REQUEST이고 Facade를 부르지 않는다` | RED 미관측. 본문에 `order`가 없어 기존 DTO도 400을 냈다 | 같은 명령 통과. 구현 뒤의 400은 Jackson이 하위 타입을 정하지 못해 나는 `HttpMessageNotReadableException`이고(`missing type id property 'kind'`, `Could not resolve type id`) 기존 `ApiControllerAdvice`가 `BAD_REQUEST`로 바꾼다 | 위와 같은 회귀 통과 |

## 결과
- `./gradlew test` 전체 통과(319개), 하네스 테스트 OK(132개), `python3 scripts/check_pr.py --base origin/main --worktree` PASS.
- `order`에 `OrderCommand.RequestCheckoutByClientOrderId`와 `OrderCommandFacade.requestCheckout` 오버로드, `OrderRepository.findByClientOrderIdForUpdate`를 더했다. 두 `requestCheckout`은 전이·저장·발행을 private 함수 하나로 함께 쓴다.
- `sync`에 `SyncOperationKind`, `OperationPayload`(구현 `OrderPayload`·`CheckoutPayload`), 접수 기록의 `kind`, Facade의 종류 분기, `kind`로 갈리는 요청 DTO를 더했다. V011이 `t_sync_operation.kind`를 더한다. `OrderPayload`의 hash 공식과 결과는 그대로다.
- 스키마 대조(code-reviewer)에서 불일치는 없었고 누락 6건을 위 대조 기록대로 반영했다.
