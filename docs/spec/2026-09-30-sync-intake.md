# PR spec: 단말의 오프라인 주문 생성 operation을 배치로 받아 항목마다 한 트랜잭션으로 적용한다

## 목적
단말이 오프라인에서 만든 주문을 서버에 수렴시키는 경로가 없다. `sync` 모듈은 비어 있고, 주문은 `POST /v1/orders` 한 건씩만 받는다. 단말이 재연결 뒤 여러 operation을 한 번에 보내면 서버가 항목마다 수락·중복·충돌·누락을 판정하고 watermark를 돌려줘야 단말이 큐를 비울 수 있다(기획 PRD 3.2·PR-02, 개발 가이드 §4.3, 청사진 §3.2).

기대 결과:
- `POST /v1/terminals/{terminalId}/sync`가 `CREATE_ORDER` operation 배치를 받아 항목별 결과와 `lastAckedSequence`를 돌려준다.
- 항목 하나가 로컬 트랜잭션 하나다. 판정, 주문 생성(`order` Facade 합류 호출), 접수 기록, watermark 전진이 한 커밋이다(도메인 모델 §4.6).
- 같은 배치를 다시 보내면 새 주문 없이 `DUPLICATE`를 받고, 순번이 비면 그 뒤 항목은 적용되지 않는다.
- `OrderCommandFacade.create`와 `PaymentManagement.createAttempt`가 쓰는 `Lock` 블록은 바깥 트랜잭션 안에서 부르면 거절한다([shared-distributed-lock](2026-09-28-shared-distributed-lock.md) 제외 절의 M-1b).

## 범위
소유 모듈은 `sync`(새 코드), `order`(합류 진입점 하나), `platform`(`Tx.requireCaller`·`requireNoCaller`와 `Lock`의 거절 한 줄)이다.

**결정 1: `order.create`는 락 없는 합류 경로로 부른다.** `OrderCommandFacade`에 `createInCallerTransaction(command)`를 더한다. 지금의 `create`는 `Lock.executeWithLock` 블록이 이 메서드를 감싸는 모양이 된다. 새 메서드는 호출자 트랜잭션에 합류(`REQUIRED`)하고 첫 줄의 `Tx.requireCaller`가 활성 트랜잭션이 없으면 거절한다. 어느 쪽이 잠그는지가 호출부 이름과 첫 줄에서 읽힌다.
- 문제: `create`가 `REQUIRES_NEW`로 스스로 커밋해 §4.6의 "항목 하나 = 커밋 하나"가 깨진다.
- 제약: sync는 단말별 `t_sync_watermark` 행을 잠그고 항목을 처리한다. 순번 판정과 전진이 같은 잠금 아래서 직렬화돼야 하기 때문이다. "한 경로에서 기다리는 수단은 하나"(도메인 모델 §4.7 질문 6) 규칙상 그 안에서 Redis 락을 또 기다릴 수 없다.
- 수단: sync 경로의 줄 세우기는 watermark 행 잠금이 맡는다. 같은 단말의 두 배치는 항목 단위로 직렬화되고 뒤 배치는 `DUPLICATE`를 받는다. HTTP `create`와 sync가 같은 `(terminalId, clientOrderId)`로 겹치는 경합은 unique `(terminal_id, client_order_id)`가 막는다. 진 쪽이 sync 항목이면 그 항목 트랜잭션이 롤백되고 배치가 오류 응답을 받으며, 재전송 때 `create`의 멱등 조회가 기존 주문을 돌려준다.
- `PaymentManagement.createAttempt`는 sync의 동기 경로에 없다. 단말의 결제 요청은 `order.requestCheckout`이고 시도 생성은 `OrderPaymentRequested` 이벤트가 잇는다. 이 PR은 결제 쪽 코드를 바꾸지 않는다.

**결정 2: `Lock.executeWithLock` 첫 줄에 바깥 트랜잭션 거절을 넣는다.** `executeWithLockNoTx`와 같은 한 줄이다. 트랜잭션 존재를 묻는 Spring API(`TransactionSynchronizationManager`)는 `platform.Tx`에만 두고 호출부는 `Tx.requireCaller(이름)`·`Tx.requireNoCaller(이름)`로 읽는다(개발 중 사용자 요청). `executeWithLockNoTx`의 기존 직접 호출도 같은 헬퍼로 옮긴다. 지금 호출처 둘(HTTP `create`, Kafka 리스너의 `createAttempt`)은 모두 트랜잭션 밖이라 깨지지 않는다. `REQUIRES_NEW`와 `Tx.newTx`는 그대로 둔다. 바깥이 없으니 `REQUIRED`와 같지만 지우는 일은 이 PR의 목표가 아니다.

**결정 3: 이 PR의 operation 종류는 `CREATE_ORDER` 하나다.** 종류 필드와 컬럼을 두지 않는다. `REQUEST_CHECKOUT`은 단말이 아는 `clientOrderId`로 checkout을 요청하는 `order` 공개 계약이 먼저 필요해서 다음 PR이 종류를 하나 늘리며 함께 정한다.

**sync: 공개 계약 없음.** 다른 모듈이 sync를 부르지 않으므로 Facade·커맨드·결과는 `sync.internal`에 둔다(fulfillment와 같은 선례).
- `SyncCommandFacade.receiveBatch(SyncCommand.ReceiveBatch(terminalId, operations))`. `Operation(operationId: UUID, sequence: Long, order: OrderCommand.Create)`. 결과 `ReceiveBatch.Result(lastAckedSequence, items)`, `Item(operationId, status, orderId?, errorCode?)`.
- 배치 상한 500개. 넘으면 저장소를 건드리기 전에 `DomainException(SYNC_BATCH_TOO_LARGE, INVALID)`로 배치 전체를 거절한다. 항목의 line 개수는 `order`가 거절한다.
- 항목은 `sequence` 오름차순으로 처리한다. 배치를 시작할 때 watermark 행이 없으면(새 단말) 짧은 트랜잭션으로 `lastAckedSequence = 0` 행을 먼저 넣는다. 같은 단말의 첫 배치가 동시에 둘 오면 진 쪽의 PK 위반은 "이미 있다"는 뜻이라 삼킨다. 그다음 항목마다 `Tx.masterTx` 하나이며 그 안에서 watermark 행을 잠그고(`findForUpdate`, 대기 3초) 판정한다. 한 번 `GAP`이면 뒤 항목은 트랜잭션 없이 `GAP`으로 채운다(오름차순이라 모두 gap이다). `receiveBatch` 첫 줄은 `Tx.requireNoCaller`다 — 바깥 트랜잭션에 합류하면 항목별 커밋이 깨진다.
- 판정은 `Watermark` 도메인 객체가 한다.
  - `sequence == lastAckedSequence + 1` → 다음 항목이다. payload를 `order` 명령으로 바꾸고(`Money.of`가 여기서 돈다) `createInCallerTransaction`을 부르고 접수 기록(`APPLIED`, `orderId`)을 넣고 watermark를 전진시킨다. 한 커밋이다.
  - `sequence` 가 `lastAckedSequence` 이하 → 이미 확정한 순번이다. `(terminalId, operationId)` 접수 기록을 찾는다. 있고 payload hash와 기록된 순번이 모두 같으면 `DUPLICATE`(기록된 `orderId`·`errorCode`를 그대로 실어 준다). hash나 순번이 다르거나, 기록이 없으면(다른 operation이 그 순번을 썼다) `CONFLICT`. 아무것도 쓰지 않는다.
  - `sequence == lastAckedSequence + 1` 인데 그 `operationId`가 이미 기록돼 있으면(단말이 operationId를 새 순번에 다시 썼다) `CONFLICT`. 그냥 넣으면 unique 위반이 재전송마다 같은 배치 오류로 되풀이되어 그 단말이 영구히 막히고 로그로만 드러난다. 리뷰(대조 기록)에서 나와 반영했다.
  - `sequence` 가 `lastAckedSequence + 2` 이상 → `GAP`. 아무것도 쓰지 않는다. 뒤 항목도 자연히 `GAP`이다. 단말은 응답의 `lastAckedSequence` 다음부터 다시 보낸다. 별도 `BLOCKED_BY_GAP`·누락 구간 필드는 두지 않는다.
- **payload 변환(`INVALID_MONEY_SCALE`·`INVALID_MONEY_AMOUNT`·`UNSUPPORTED_CURRENCY`)이나 주문 생성이 도메인 규칙으로 거절되면(`DomainException`) 항목은 `REJECTED`다.** 그래서 Controller는 `Money`를 만들지 않고 raw 값을 `OrderPayload`로 넘기며 변환은 항목 트랜잭션 안이다. Controller에서 바꾸면 그 거절이 배치 전체의 400이 되어 단말이 같은 봉투를 영원히 다시 보낸다(리뷰에서 나와 반영). 그 항목 트랜잭션은 롤백돼 주문 행이 남지 않는다. Facade가 같은 판정 경로를 두 번째 짧은 트랜잭션으로 다시 돌려(watermark를 다시 잠그고 순번을 다시 확인한다) 접수 기록을 `REJECTED`+`errorCode`로 넣고 watermark를 전진시킨다. 거절은 결정적이라 두 트랜잭션 사이에 죽어도 재전송이 같은 결과를 낸다. 단말은 그 항목을 큐에서 버린다. `IDEMPOTENCY_CONFLICT`(같은 `clientOrderId`에 다른 내용)도 `order`가 던지는 `DomainException`이므로 `REJECTED`다. sync 수준의 판정(순번·operationId)만 `DUPLICATE`·`GAP`·`CONFLICT`를 쓴다.
- **인프라 실패(잠금 대기 초과, DB 장애, unique 위반)는 항목 결과가 아니다.** 그 항목 트랜잭션은 롤백되고 기록도 전진도 없다. 예외를 그대로 올려 배치 전체가 오류 응답(`409 CONCURRENT_REQUEST` 또는 `500`)을 받는다. 이미 커밋된 앞 항목은 남고 재전송 때 `DUPLICATE`로 돌아온다. 항목별 `RETRY` 상태를 두지 않는다.
- payload hash는 `OrderPayload.hash()`다. 종류 이름 `CREATE_ORDER`, `storeId`, `clientOrderId`, `currency`, line마다 `productCode`·`productName`·raw `unitPrice.toPlainString()`·`quantity`를 각각 `길이:값;`으로 이은 문자열의 SHA-256 hex다. 길이 접두어라 값 안의 구분자가 경계를 섞지 않고, 종류 접두어라 둘째 종류가 생겨도 기존 hash가 그대로다. `Money`나 `toString`에 기대지 않는다 — 표현이 바뀌어도 이미 기록된 hash가 어긋나지 않게 하려는 것이다. 이 공식은 `payload_hash`의 저장 형식이라 형식 버전 없이 바꾸지 않는다. line 순서가 다르면 다른 hash라 `CONFLICT`다. `order`의 `verifySamePayload`보다 엄격하지만 단말은 저장한 봉투를 그대로 다시 보낸다.
- 응답의 `orderId`는 `create`의 결과에서 온다. 단말이 온라인 복구 뒤 `POST /v1/orders/{orderId}/checkout`을 부를 수 있어야 하므로 `t_sync_operation.order_id`에 남겨 `DUPLICATE`에도 같은 값을 준다.
- 배치가 끝나면 처리 결과 개수(항목 수, APPLIED·DUPLICATE·REJECTED·GAP·CONFLICT)와 `terminalId`를 INFO 한 줄로 남긴다. `CONFLICT`는 항목마다 WARN(`terminalId`·`operationId`·`sequence`)이다. 단말 상태가 서버와 갈라졌다는 신호라서다.

**엔티티 지도(범위 조정, PR 열린 뒤 추가).** 사용자 요청으로 JPA 엔티티 7개(`OrderEntity`·`OrderLineEntity`·`OrderCheckoutEntity`·`PaymentAttemptEntity`·`FulfillmentEntity`·`WatermarkEntity`·`SyncOperationEntity`)의 역할, 참조 관계(JPA 연관관계 매핑이 없어 ID 컬럼으로만 잇는다), 단말·운영자 UX 흐름별 생성·전이 순서를 `docs/wiki/entity-map.md`에 적고 각 엔티티 KDoc에 역할·관계·흐름 문단을 더한다. 코드 동작은 바꾸지 않는다. 컨텍스트가 sync intake와 다르지만 사용자가 이 PR에 얹기로 정했다.

**HTTP.** `sync.internal.adapter.web.SyncController`, `POST /v1/terminals/{terminalId}/sync`.
- 요청 본문: `operations` 배열. 항목은 `operationId`(UUID), `sequence`(정수), `order`(`storeId`·`clientOrderId`·`currency`·`lines[]`, `POST /v1/orders` 본문에서 `terminalId`를 뺀 모양). `terminalId`는 경로에서만 온다.
- 응답: 200과 `data{lastAckedSequence, items[{operationId, status, orderId, errorCode}]}`. `orderId`·`errorCode`는 해당 없으면 null이다. 빈 `operations`도 200이고 그때 응답은 watermark 조회다.
- 상태 코드는 배치 처리의 결과다. 항목 판정은 항목 `status`에만 있고 부분 성공을 HTTP 상태로 숨기지 않는다. 500개 초과는 `400 SYNC_BATCH_TOO_LARGE`, 형식 오류는 Jackson이 `400 BAD_REQUEST`로 거절한다.
- 단말 인증과 매장·단말 소유권 검사는 없다. checkout API와 같은 알려진 빈틈이다. 이 API도 그 전에는 외부에 노출하지 않는다.

**계약**: 새 `DomainException` code `SYNC_BATCH_TOO_LARGE`(`INVALID`, 400) 하나. 응답 항목 상태 `SyncItemStatus`(`APPLIED`·`DUPLICATE`·`REJECTED`·`GAP`·`CONFLICT`)는 단말 계약이고, DB `t_sync_operation.status`에 저장되는 것은 별도 enum `SyncOperationStatus`(`APPLIED`·`REJECTED`)다. 앞은 바꾸면 단말 계약이, 뒤는 바꾸면 데이터 마이그레이션이 필요하다. 이벤트·토픽·`listener_id`·기존 HTTP 응답 모양은 바뀌지 않는다. `OrderEvent.Created`는 합류한 트랜잭션에서 지금처럼 발행된다(받는 쪽이 없어 기록되지 않는다).

**실패와 복구**: 위 판정 표가 곧 복구 경로다. 재전송은 언제나 안전하다. 확정된 순번은 `DUPLICATE`, 확정되지 않은 순번은 다시 판정된다.

**관측성**: 위 배치 INFO 로그와 `CONFLICT` WARN 로그. metric은 더하지 않는다.

## 제외
- `REQUEST_CHECKOUT` operation. `order`에 `clientOrderId` 기반 checkout 요청 경로를 더하는 다음 PR이 종류 필드(`kind`, 상수 기본값 `CREATE_ORDER`으로 기존 행을 채운다)와 함께 정한다.
- 단말 인증·소유권 검사. `terminal` 모듈에 aggregate가 없다.
- `SyncOperationApplied` 이벤트. 받는 쪽(`reconciliation`·감사)이 없어 발행해도 기록되지 않는다. 소비자가 생기는 PR이 이벤트와 payload를 정한다.
- `GET /v1/terminals/{id}/sync-status`. 빈 배치가 watermark를 돌려준다.
- `sync_gap_total`·수렴 시간 metric. 수집기와 알림 규칙이 아직 없다.
- 항목별 `RETRY`·`BLOCKED_BY_GAP` 상태, 누락 구간 필드. `lastAckedSequence` 하나로 단말이 재전송 범위를 안다.
- chunk 단위 커밋. 항목 단위가 가장 작은 단위이고 가이드 §4.3이 허용한다.
- `Tx.newTx`·`REQUIRES_NEW` 제거. 결정 2로 바깥이 없어져 `REQUIRED`와 같아졌지만 이 PR의 목표가 아니다.
- **REJECTED 두 번째 트랜잭션 사이의 창.** 주문 생성이 거절된 뒤 접수 기록 전에 죽으면 기록이 없다. 재전송이 같은 거절을 다시 받으므로 복구 절차가 없다.
- 응답에서 `DUPLICATE`가 원래 판정(`APPLIED`였는지 `REJECTED`였는지)을 상태로 구분하지 않는다. `orderId`가 있으면 적용, `errorCode`가 있으면 거절이다.
- `error_code varchar(64)`보다 긴 `DomainException.code`가 생기면 `REJECTED` 기록이 "value too long"으로 결정적으로 실패해 그 단말의 배치가 계속 오류다. 지금 가장 긴 code는 30자이고 code는 상수라 PR 리뷰에서 길이를 본다. 다른 모듈의 code 이름이 이 표에 저장되고 `DUPLICATE` 응답으로 다시 나가므로, `order`가 code 이름을 바꾸면 옛 이름이 계속 나간다.
- 컬럼 이름 `sequence`는 SQL 예약어이지만 PostgreSQL에서 non-reserved라 그대로 둔다. 용어집의 단어를 따랐다.
- `sequence`가 0 이하인 항목은 400이 아니라 `CONFLICT`다(확정된 순번 이하인데 기록이 없다). 단말이 순번을 1부터 매기는 것이 계약이다.
- 요청 본문은 Jackson 상한 1 MiB 이하여야 한다. line이 많은 주문 500개는 넘을 수 있고 그때는 `400 BAD_REQUEST`라 단말이 배치를 나눠야 한다. REST Docs에 적었다. 별도 오류 코드는 두지 않았다.
- 잠금 대기 초과·같은 단말 배치의 경합을 HTTP까지 잇는 테스트(409)는 없다. Facade 통합 테스트가 두 스레드의 같은 배치로 직렬화를 확인한다.

## 관련 문서
없음

## 변경 경로
- src/main/kotlin/com/resilientpos/sync/**
- src/main/kotlin/com/resilientpos/order/OrderCommandFacade.kt
- src/main/kotlin/com/resilientpos/platform/Lock.kt
- src/main/kotlin/com/resilientpos/platform/Tx.kt
- src/main/resources/db/migration/V010__sync_intake.sql
- src/test/kotlin/com/resilientpos/**
- docs/wiki/domain-model.md
- docs/spec/2026-09-30-sync-intake.md
- docs/wiki/domain-model.md
- docs/wiki/entity-map.md
- docs/wiki/README.md
- src/main/kotlin/com/resilientpos/order/internal/adapter/persistence/command/*Entity.kt
- src/main/kotlin/com/resilientpos/payment/internal/adapter/persistence/command/PaymentAttemptEntity.kt
- src/main/kotlin/com/resilientpos/fulfillment/internal/adapter/persistence/command/FulfillmentEntity.kt

## 인수 조건
1. 순번 1부터 이어지는 `CREATE_ORDER` 배치를 보내면 모든 항목이 `APPLIED`이고 `orderId`를 가지며, `t_order`에 항목 수만큼 행이 있고 `t_sync_watermark.last_acked_sequence`가 마지막 순번이다. 응답의 `lastAckedSequence`가 같다.
2. 같은 배치를 다시 보내면 모든 항목이 `DUPLICATE`이고 `orderId`가 첫 응답과 같으며, `t_order`·`t_sync_operation` 행 수가 늘지 않는다.
3. 이미 확정된 `operationId`에 다른 내용(line 하나의 수량 변경)을 보내면 `CONFLICT`이고, 확정된 순번을 모르는 `operationId`로 보내도 `CONFLICT`이며, 이미 기록된 `operationId`를 다른 순번(확정된 순번이든 다음 순번이든)으로 보내도 `CONFLICT`다. 어느 경우도 watermark와 행 수가 바뀌지 않는다.
4. 순번 1, 2, 4를 보내면 1·2는 `APPLIED`, 4는 `GAP`이고 `lastAckedSequence`는 2다. 이어서 3, 4를 보내면 둘 다 `APPLIED`다.
5. `order`가 거절하는 항목(빈 상품명)은 `REJECTED`와 `errorCode`(`BLANK_PRODUCT_NAME`)를 받고 `t_order`에 행이 없으며 watermark는 그 순번으로 전진한다. 같은 항목을 다시 보내면 `DUPLICATE`와 같은 `errorCode`다. 그 뒤 순번은 정상 적용된다. 금액 규칙 위반(KRW `4500.5`)은 `INVALID_MONEY_SCALE`로, 같은 `clientOrderId`의 다른 내용은 `IDEMPOTENCY_CONFLICT`로 같은 `REJECTED` 경로다.
6. 항목 트랜잭션은 하나다. `createInCallerTransaction`이 주문을 넣은 뒤 접수 기록 저장이 unique `(terminal_id, sequence)`로 실패하면 주문 행도 남지 않는다. 같은 단말의 같은 배치가 두 스레드에서 동시에 와도 주문·기록은 항목 수만큼이고 항목은 `APPLIED` 아니면 `DUPLICATE`다.
7. `createInCallerTransaction`을 트랜잭션 밖에서 부르면 저장소를 건드리기 전에 `IllegalStateException`으로 실패한다.
8. `Lock.executeWithLock`을 바깥 트랜잭션 안에서 부르면 잠그기 전에 `IllegalStateException`으로 실패한다. #68 인수 조건 2의 테스트를 이것으로 바꾼다. HTTP `create`와 Kafka `createAttempt`는 지금처럼 동작한다.
9. 501개 배치는 `400 SYNC_BATCH_TOO_LARGE`이고 저장소를 건드리지 않는다.
10. `POST /v1/terminals/{terminalId}/sync`가 `SyncCommand.ReceiveBatch`로 옮겨 Facade를 부르고 200과 `data{lastAckedSequence, items}`를 돌려준다. 성공 응답과 `SYNC_BATCH_TOO_LARGE` 응답을 REST Docs로 문서화한다. HTTP부터 PostgreSQL까지 이어지는 통합 테스트 하나가 인수 1·2를 HTTP로 확인한다.
11. `Watermark`의 판정(다음·확정됨·gap)과 전진은 Spring 없는 단위 테스트로 고정한다.
12. `./gradlew test` 전체와 `python3 scripts/check_pr.py --base origin/main --worktree`가 통과한다.
13. `docs/wiki/entity-map.md`가 엔티티 7개의 역할·참조 관계·UX 흐름을 담고 wiki 인덱스와 도메인 모델 §2에서 링크되며, 각 엔티티 KDoc 첫 문단에 역할·관계·흐름이 있다. 문서·주석만이라 테스트는 없고 컴파일과 `check_pr.py`로 확인한다.

### Checkpoint와 검증 명령
| Checkpoint | 검증 명령 | 의존 |
|---|---|---|
| lock-check (8) | `./gradlew test --tests 'com.resilientpos.platform.Lock*'` | 없음 |
| order-join (7) | `./gradlew test --tests 'com.resilientpos.order.*'` | 없음 |
| sync-domain (11) | `./gradlew test --tests 'com.resilientpos.sync.internal.domain.*'` | 없음 |
| sync-facade (1~6, 9) | `./gradlew test --tests 'com.resilientpos.sync.internal.application.*'` | order-join, sync-domain |
| sync-api (10) | `./gradlew test --tests 'com.resilientpos.sync.internal.adapter.web.*'` | sync-facade |
| 전체 (12) | `./gradlew test` · `python3 scripts/check_pr.py --base origin/main --worktree` | 모두 |

## 스키마 변경 검토
`V010__sync_intake.sql`이 테이블 둘을 만든다.

```sql
create table t_sync_watermark (
    terminal_id uuid primary key,
    last_acked_sequence bigint not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz
);

create table t_sync_operation (
    id uuid primary key,
    terminal_id uuid not null,
    operation_id uuid not null,
    sequence bigint not null,
    payload_hash varchar(64) not null,
    status varchar(32) not null,
    order_id uuid,
    error_code varchar(64),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz,
    constraint ux_sync_operation_terminal_operation unique (terminal_id, operation_id),
    constraint ux_sync_operation_terminal_sequence unique (terminal_id, sequence)
);
```

매핑은 `t_fulfillment`와 같은 구성이다(`UUID`↔`uuid`, `Long`↔`bigint`, enum은 `EnumType.STRING`, `Instant`↔`timestamptz`, `deleted_at`은 `BaseEntity`의 `@SoftDelete`). `ddl-auto: validate`가 통합 테스트의 컨텍스트 기동에서 대조한다.

- 질문 0(대체 수단): Spring Modulith가 제공하는 것은 나가는 이벤트의 Event Publication Registry(리스너별 기록·완료 추적)와 externalization이고, 들어오는 명령의 inbox·멱등 소비·순번 추적은 없다. Registry는 업무 키 unique와 잠글 행이 없고 응답에 실을 판정도 만들지 않아 이 두 표를 대신하지 못한다. 도메인 모델 §4.3의 "processed-event 보조 테이블을 만들지 않는다"는 내부 이벤트 소비 이야기이고, §4.7 사례가 sync 접수 기록을 예외로 허용했다.
- 질문 1(도출): watermark는 `t_sync_operation`의 `max(sequence)`와 같지만 그 집계로 대신하지 않는다. 항목마다 잠글 행이 필요하고(질문 6) 집계는 잠글 수 없다. 접수 사실·payload hash·판정은 다른 어디에도 없다. `order_id`는 `t_order`에 `(terminal_id, client_order_id)`로 있지만 sync는 `order`의 테이블을 읽지 않는다(모듈 경계). `t_sync_operation.id`는 `(terminal_id, operation_id)`로 대신할 수 있는 서버 식별자이지만 모든 표가 UUID `id`를 갖는 선례(볼트 rules §3·§5, 복합 `@IdClass`를 두지 않는다)를 따른다.
- 질문 2(읽는 코드): `last_acked_sequence`는 항목 판정(잠금 조회)과 배치 끝의 응답용 조회(잠금 없는 `readOnlyTx`, 동시 배치의 전진분이 섞여도 단조 증가라 해가 없다)가 읽는다. `payload_hash`와 `sequence`는 `DUPLICATE`/`CONFLICT` 판정이 읽는다(같은 operation이 다른 순번으로 오면 `CONFLICT`). `status`·`order_id`·`error_code`는 `DUPLICATE` 응답이 읽는다. operation 종류 컬럼은 종류가 하나뿐이라 두지 않는다.
- 질문 3(나중에 채우기): 종류 컬럼은 상수 기본값(`CREATE_ORDER`)으로 기존 행을 채울 수 있다. `REQUEST_CHECKOUT`을 더하는 PR이 붙인다. 행 수가 operation 수를 따라가므로 그때 `lock_timeout`을 둔 메타데이터 변경으로 더한다.
- 질문 4(마지막 방어선): unique `(terminal_id, operation_id)`가 "같은 operation을 두 번 적용하지 않는다"의, unique `(terminal_id, sequence)`가 "순번 하나에 기록 하나"의 마지막 방어선이다. 뒤의 것은 평소 watermark 행 잠금이 지키고 잠금 밖에서 들어온 행(새 단말 경합, 수동 수정)을 막으며 순번 조사용 인덱스를 겸한다. 둘 다 멱등 키이므로 soft delete된 행에도 걸리는 전체 unique로 둔다. 접수 기록과 watermark를 지우는 코드는 없고 JPA repository는 좁힌 `Repository`라 delete 메서드가 없다. 지우는 명령이 생기면 `findOperation`과 watermark 조회가 삭제된 행도 보게 해야 한다 — 지금 조회는 `@SoftDelete`가 붙이는 `deleted_at is null`로 삭제 행을 못 본다. 못 보면 재전송이 `CONFLICT`가 되고, 삭제된 watermark 행은 새 단말로 시작하다가 PK에 걸린다. `last_acked_sequence`는 "gap을 건너뛰어 전진하지 않는다"의 상태이고, 행 잠금 아래에서만 바뀐다. `payload_hash`는 재전송 판정의 근거다(도메인 모델 §4.7 사례).
- 질문 5(정본): `order_id`는 `t_order.id`를 가리키는 참조이고 사본이 아니다. DB FK는 두지 않는다(`t_payment_attempt`·`t_fulfillment` 선례). `order_id`로 읽는 조회가 없어 인덱스도 두지 않는다. 조회가 생기면 더한다. `terminal_id`는 `terminal` 모듈에 테이블이 없어 참조 대상이 없다. `error_code`는 다른 모듈의 `DomainException.code` 이름의 사본이다(제외 절).
- 질문 6(check-then-act): 항목마다 watermark를 읽고 판단한 뒤 접수 기록을 넣고 watermark를 쓴다. 경합은 `t_sync_watermark` 행 잠금(`PESSIMISTIC_WRITE`, PostgreSQL `for no key update`, 대기 3초)이 막는다. 같은 단말의 배치 둘은 항목 단위로 직렬화된다. 잠금 없이 도착한 경로(행이 아직 없는 새 단말의 동시 첫 배치)는 PK와 unique가 막고 진 쪽은 배치 오류를 받는다(제외 절). `@Version`은 두지 않는다. 행 잠금이 있고 잠금 없이 쓰는 경로가 없다. 한 경로에서 기다리는 명시적 잠금은 이 행 잠금 하나다. `createInCallerTransaction` 안에는 다른 잠금이 없다. 다만 INSERT의 unique·PK 검사는 같은 키를 넣고 아직 커밋하지 않은 다른 트랜잭션을 기다린다(HTTP `create`와 겹친 `t_order`, 새 단말 경합의 watermark PK). 그 대기는 잠금 조회의 3초 밖이고 상한은 트랜잭션 30초다.
- 질문 7(락): 빈 테이블 `create table`이라 잠글 대상이 없다.
- 질문 8(이전 버전): 새 테이블이라 이전 버전은 읽지도 쓰지도 않는다.
- 질문 9(payload): 이벤트 payload와 캐시되는 DTO에 실리지 않는다. 응답 항목 상태 `SyncItemStatus`는 단말 계약이고, DB에 저장되는 `SyncOperationStatus`(`APPLIED`·`REJECTED`)는 바꾸면 데이터 마이그레이션이 필요하다. payload hash 공식도 저장 형식이다(범위 절).
- 대조 기록: code-reviewer(읽기 전용 에이전트), 2026-09-30. V010__sync_intake.sql, WatermarkEntity·SyncOperationEntity·SyncPersistenceAdapter·WatermarkJpaRepository·SyncOperationJpaRepository, sync.internal.domain 전체, SyncCommandFacade, platform BaseEntity·JdslQueryExecutor·Tx, OrderCommandFacade.createInCallerTransaction을 질문 0~9의 답과 대조했다. DDL은 spec과 같고 잠금 종류·`@Version` 부재·FK 부재·soft delete 매핑은 일치했다. 불일치 2건(응답 상태와 DB 저장 상태가 다른 enum인데 한 문장으로 적었다, unique·PK INSERT 대기가 행 잠금 밖의 두 번째 대기다)과 누락 10건(soft delete 행을 못 보는 멱등·watermark 조회, `(terminal_id, sequence)` unique 여부, 순번을 무시하던 `DUPLICATE`, 재사용된 operationId의 영구 배치 오류, `id` 컬럼 근거, `order_id` 인덱스 부재 근거, hash 구분자 모호성과 공식의 저장 형식 여부, `error_code` 길이와 모듈 간 code 계약, 응답용 watermark 읽기 경로)을 받았다. 코드로 고친 것: hash에 길이 접두어, `DUPLICATE` 판정에 순번 포함, 다음 순번의 기록된 operationId를 `CONFLICT`로, `(terminal_id, sequence)` unique 추가, 어댑터의 `apply` 분기 가독성. 나머지는 이 절과 제외 절에 답을 적었다.

## 검증
- 정상: 순서대로 온 배치가 모두 `APPLIED`, 재전송이 모두 `DUPLICATE`, 빈 배치가 watermark를 돌려준다.
- 실패: gap 뒤 항목은 `GAP`, 다른 내용의 재전송은 `CONFLICT`, 도메인 거절은 `REJECTED`와 전진, 501개는 400, 인프라 실패는 배치 오류와 롤백.
- Docker(OrbStack)가 필요한 통합 테스트는 Testcontainers의 PostgreSQL·Redis·Kafka를 쓴다.
- `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전 `--worktree` 없이 한 번 더.

## Wiki 영향
- [entity-map](../wiki/entity-map.md)을 새로 둔다. wiki [README](../wiki/README.md)의 작업별 진입점과 [domain-model](../wiki/domain-model.md) §2 "aggregate 안의 구성"에서 링크한다.
- [domain-model](../wiki/domain-model.md)
  - §1 엔티티 예시와 §2 표·"aggregate 안의 구성"의 옛 이름 `TerminalSyncState`·`SyncManagement`를 `Watermark`·`SyncCommandFacade`(공개 Facade 없음)로 바꾼다.
  - §3 용어집의 `gap / duplicate / conflict` 줄에 `rejected`(소유 컨텍스트가 도메인 규칙으로 거절한 operation, 순번은 확정된다)를 더한다.
  - §4.6의 "sync intake PR이 먼저 정한다"를 결정 1·2로 바꾼다. sync는 `createInCallerTransaction`을 부르고, `Lock` 블록은 바깥 트랜잭션을 거절한다.
  - §4.7 사례에 `t_sync_watermark`·`t_sync_operation`의 판단(질문 1·4·6)을 더한다.
- current-state는 볼트 파일이라 저장소 커밋 없이 볼트에서 고친다. `sync` 모듈이 비어 있다는 문장, `Lock` 블록의 바깥 트랜잭션 거절, sync intake API를 적는다.
- README·`modulith-kafka-keywords`는 무영향이다. Kafka·이벤트 계약이 바뀌지 않는다.

## 분할 계획
개발 컨텍스트는 하나다. sync intake의 첫 operation 종류를 끝까지(HTTP부터 커밋까지) 잇는 일이고, 그것을 가능하게 하는 `order` 합류 진입점과 `Lock` check가 딸려 온다. 작업 시작 시점(origin/main 7358f92, #70 머지 뒤)에 열린 PR이 없어 변경 경로가 겹치지 않는다. `REQUEST_CHECKOUT`은 `order` 공개 계약 변경이 따로 필요해 다른 컨텍스트로 뺐다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 8, `LockIntegrationTest`의 `바깥 트랜잭션 안에서 부르면 잠그기 전에 거절한다`(#68 인수 2 테스트를 대체) | `./gradlew test --tests 'com.resilientpos.platform.LockIntegrationTest' --tests 'com.resilientpos.order.OrderCommandFacadeCallerTransactionTest'`. 거절 없이 성공해 `AssertionFailedError`("Expected IllegalStateException ... completed successfully") | 같은 명령. `Lock.executeWithLock` 첫 줄에 거절을 넣은 뒤 통과 | 거절을 `Tx.requireNoCaller`로 옮긴 뒤 `./gradlew test --tests 'com.resilientpos.platform.Lock*' --tests 'com.resilientpos.order.OrderCommandFacade*'` 통과 |
| 인수 7, `OrderCommandFacadeCallerTransactionTest`의 `트랜잭션 밖에서 부르면 저장소를 건드리기 전에 거절한다` | 같은 명령. 검사 없는 최소 선언으로 실행. 저장소 mock까지 진행해 `NullPointerException`(saved가 null)으로 `AssertionFailedError` | 같은 명령. 첫 줄에 거절을 넣은 뒤 통과 | 위와 같은 회귀 통과. `./gradlew test --tests 'com.resilientpos.order.*'`에서 `OrderRequestCheckoutIntegrationTest`의 잠금 대기 테스트가 한 번 `Transaction timeout expired`로 실패했으나(Docker 첫 기동 직후 5분 20초 실행) 단독 재실행에서 통과했다. 변경과 무관한 경로다 |
| 인수 11, `WatermarkTest` 5개 | `./gradlew test --tests 'com.resilientpos.sync.*'`. 판정이 늘 `NEXT`, 전진이 `this`인 최소 선언으로 실행. 4개가 `AssertionFailedError`(expected GAP/ACKED but was NEXT, expected 3 but was 2) | 같은 명령. `judge`·`advance`를 채운 뒤 통과 | `./gradlew test --tests 'com.resilientpos.sync.*'` 통과 |
| 인수 1~6·9, `SyncCommandFacadeIntegrationTest` 7개 | `./gradlew test --tests 'com.resilientpos.sync.*' --tests 'com.resilientpos.SchemaMigrationTest'`. 영속화·마이그레이션은 갖추고 Facade만 모든 항목을 `GAP`으로 돌려주는 최소 선언으로 실행. 7개 모두 `AssertionFailedError`(expected APPLIED/DUPLICATE/CONFLICT/REJECTED but was GAP, 예외 기대 실패). 컨텍스트는 떠서 V010과 `validate`는 통과했다 | 같은 명령. 판정·적용·기록·전진과 상한을 채운 뒤 7개 통과. `SchemaMigrationTest`는 테이블·버전 목록에 010을 더한 뒤 통과 | `./gradlew test --tests 'com.resilientpos.sync.*'` 통과(16개) |
| 인수 6(항목 트랜잭션 하나), `접수 기록 저장이 실패하면 그 항목의 주문 행도 남지 않는다` | 위와 같은 RED. 처음에는 같은 `operationId`를 다른 순번으로 두 번 보내 unique `(terminal_id, operation_id)`에 걸리게 했다. 최소 선언은 예외를 내지 않아 실패 | `DataIntegrityViolationException`이 나고 둘째 주문 행이 0건, watermark가 1로 통과 | 리뷰 반영으로 그 시나리오가 `CONFLICT`가 되어, 순번 2의 접수 기록을 잠금 밖에서 미리 넣고 새 unique `(terminal_id, sequence)`에 걸리게 바꿨다. `./gradlew test --tests 'com.resilientpos.sync.*'` 통과 |
| 인수 5·6(리뷰 반영), `SyncCommandFacadeIntegrationTest`의 `금액 규칙에 어긋나는 항목은 배치 오류가 아니라 거절이고 다음 항목은 적용된다`·`같은 clientOrderId의 주문이 다른 내용으로 이미 있으면 항목은 IDEMPOTENCY_CONFLICT 거절이고 순번은 확정된다` | RED 미관측. 수정 전에는 `Money.of`가 Controller에서 돌아 Facade 테스트로 재현할 수 없었고, payload 타입을 바꾸는 수정과 테스트를 함께 썼다. 결함은 Claude 리뷰가 호출 경로로 확인했다 | `./gradlew test --tests 'com.resilientpos.sync.*'`. `OrderPayload`를 항목 트랜잭션 안에서 명령으로 바꾼 뒤 통과 | 같은 명령 통과(20개) |
| 인수 6(리뷰 반영), `같은 단말의 같은 배치가 동시에 오면 항목 단위로 직렬화되어 주문과 기록이 항목 수만큼만 생긴다` | 같은 명령. 새 단말의 두 스레드가 모두 watermark 행이 없어 둘 다 항목 1을 적용하다 `t_order` unique 위반(`DataIntegrityViolationException`)으로 실패. 제외 절에 적어 두었던 경합이 그대로 재현됐다 | 배치 시작 때 watermark 행을 짧은 트랜잭션으로 먼저 넣고 진 쪽의 PK 위반을 삼킨 뒤 통과. 첫 시도는 `DuplicateKeyException`을 잡아 실패했고(커밋 시점 위반은 `DataIntegrityViolationException`으로 번역된다) 부모 타입으로 바꿔 통과 | 같은 명령 통과(20개) |
| 인수 3(리뷰 반영), `SyncCommandFacadeIntegrationTest`의 `이미 기록된 operation이 다른 순번으로 오면 확정된 순번이든 다음 순번이든 충돌이고 아무것도 바뀌지 않는다` | `./gradlew test --tests 'com.resilientpos.sync.internal.application.SyncCommandFacadeIntegrationTest'`. 수정 전 `SyncCommandFacade`·`SyncOperation`으로 되돌려 실행. 다음 순번 항목이 unique 위반으로 `DataIntegrityViolationException`을 던져 실패 | 같은 명령. 다음 순번의 기록된 operationId를 `CONFLICT`로, `matches`에 순번을 넣은 뒤 통과 | `./gradlew test --tests 'com.resilientpos.sync.*' --tests 'com.resilientpos.SchemaMigrationTest'` 통과(22개) |
| 인수 10, `SyncControllerTest` 3개·`SyncApiIntegrationTest` 1개 | `./gradlew test --tests 'com.resilientpos.sync.internal.adapter.web.*'`. Facade를 부르지 않고 빈 응답을 돌려주는 최소 Controller로 실행. 4개 모두 실패(lastAckedSequence expected 1/7 but was 0, status expected 400 but was 200, expected APPLIED but was 빈 문자열) | 같은 명령. 커맨드 변환과 Facade 호출을 채운 뒤 통과. 스니펫 `sync-receive-batch`·`sync-receive-batch-too-large`가 `build/generated-snippets`에 생겼다 | `./gradlew test --tests 'com.resilientpos.sync.*'` 통과(16개) |

## 결과
- `./gradlew test` 전체 통과(307개), `python3 scripts/check_pr.py --base origin/main`을 `--worktree` 있이·없이 모두 PASS, 하네스 테스트 OK.
- `platform`에 `Tx.requireCaller`·`Tx.requireNoCaller`를 더하고 `Lock`의 두 블록이 바깥 트랜잭션을 거절한다. 트랜잭션 존재를 묻는 Spring API는 `Tx`에만 있다(개발 중 사용자 요청).
- `order`에 `OrderCommandFacade.createInCallerTransaction`(락 없는 합류 경로)을 더했다. `create`는 그것을 `Lock` 블록으로 감싼다.
- `sync`에 `Watermark`·`SyncOperation`·`OrderPayload`(도메인), `SyncCommandFacade`·`SyncCommand`·`SyncItemStatus`(application), JPA 영속화, `POST /v1/terminals/{terminalId}/sync`와 REST Docs 스니펫 둘, V010(`t_sync_watermark`·`t_sync_operation`)을 더했다.
- 제출 전 리뷰(스키마 대조 code-reviewer, 코드 리뷰 Claude 78점·Codex 72점, Gemini는 CLI 없음)를 반영했다. 셋이 함께 지적한 것: 같은 operationId가 새 순번으로 오면 unique 위반이 재전송마다 되풀이돼 단말이 영구히 막힌다 → `CONFLICT`로 판정. Claude만 지적한 Critical: `Money.of` 거절이 Controller에서 나 배치 전체가 400이 되어 같은 봉투를 영원히 다시 보낸다 → payload 변환을 항목 트랜잭션 안으로 옮겨 `REJECTED`. 그 밖에 hash 길이·종류 접두어, 순번을 포함한 `DUPLICATE` 판정, `(terminal_id, sequence)` unique, 새 단말 첫 배치 경합의 watermark 선삽입, GAP 뒤 빈 트랜잭션 생략, `receiveBatch`의 `Tx.requireNoCaller`, Lock 거절 테스트의 판별력, wiki 옛 이름, REST Docs의 본문 상한·정렬 설명을 반영했다. 미룬 지적은 제외 절에 적었다.
