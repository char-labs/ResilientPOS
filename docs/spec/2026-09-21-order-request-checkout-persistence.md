# PR spec: RequestCheckout의 Facade와 t_order_checkout 영속화

## 목적
`Order.requestCheckout`은 도메인에만 있어서 checkout을 요청하고 저장할 방법이 없다. `OrderCommandFacade.requestCheckout`과 `t_order_checkout`을 더해 전이를 한 트랜잭션으로 저장하고 `OrderPaymentRequested`를 발행한다. `RequestCheckout` 세 PR의 두 번째이고 [앞 PR의 spec](2026-09-21-order-request-checkout-domain.md) 분할 계획이 넘긴 다섯 항목을 여기서 정한다.

## 범위
- 소유 모듈: `order`.
- 계약(모듈 루트): `RequestCheckoutCommand(orderId)`와 `RequestCheckoutResult(orderId, status, checkoutNo, amount)`. `OrderCommandFacade.requestCheckout(command)`가 공개 진입점이다.
- Facade 흐름: `@Transactional` 안에서 주문을 잠금 조회하고, 없으면 `DomainException(ORDER_NOT_FOUND, kind=NOT_FOUND)`을 던진다. `order.requestCheckout(ids, now)`의 결과를 저장하고 이벤트를 발행(plain Spring 이벤트)한 뒤 마지막 회차의 `Checkout`으로 결과를 만든다. 재요청이면 도메인이 같은 주문과 빈 이벤트를 돌려주므로 같은 경로가 기존 결과를 돌려주고 이벤트를 다시 내지 않는다. `NOT_FOUND`의 첫 사용처다.
- 도메인 변경: `Checkout`이 `CheckoutId`(UUID v7, 모듈 루트)를 갖는다. `requestCheckout`은 식별자를 checkout, 이벤트 순서로 만든다.
- 영속화: `V003__order_request_checkout.sql`이 `t_order_checkout`을 만든다. `OrderCheckoutEntity`와 `OrderCheckoutJpaRepository`를 더하고 `OrderPersistenceAdapter`가 저장과 복원에서 checkout을 조립한다. checkout은 확정 뒤에 바뀌지 않으므로 어댑터는 없는 ID만 INSERT하고 갱신·삭제하지 않는다. `OrderEntity.toDomain()`의 빈 목록 주석을 지운다.
- `OrderRepository` port에 `findByIdForUpdate(id)`를 더한다. JPA 구현은 `PESSIMISTIC_WRITE`(PostgreSQL `select for update`)다.
- 정합성 불변식: 상태 전이(`t_order.status`)와 checkout 행이 한 커밋이다. 한 주문에 같은 회차는 하나다(unique `(order_id, checkout_no)`). 거절된 요청은 아무 행도 바꾸지 않는다.
- 실패·재시도·복구: 순차 재시도는 기존 결과를 받는다. 동시 요청은 주문 행 잠금이 직렬화해서 뒤 요청이 앞 커밋을 기다린 뒤 `CHECKOUT_REQUESTED`를 읽고 기존 결과를 받는다. 이벤트 전달 실패의 복구는 Event Publication Registry의 몫이고 이 PR에는 없다.
- 관측성: 새 로그 속성은 없다. `ORDER_NOT_FOUND`는 기존 `DomainException` 경로로 남는다.
- `POST /v1/orders` 재시도의 `status`: 코드는 이미 저장된 주문의 현재 상태를 돌려준다. checkout을 요청한 주문이면 `CHECKOUT_REQUESTED`다. 이것을 계약으로 삼고 테스트로 고정한다. 단말에 실제와 다른 `OPEN`을 돌려주지 않는다.

### 합의한 결정 (2026-09-21 grill)
- 키: `@EmbeddedId`와 복합 PK를 쓰지 않는다. `Checkout`이 `OrderLine`처럼 도메인 ID를 갖고 테이블 PK는 `id` 하나다. 볼트 규칙 "자식도 도메인 ID를 가진다"를 그대로 따른다. 회차 중복은 unique `(order_id, checkout_no)`가 막는다.
- 동시 요청: 주문 행 잠금으로 직렬화한다. 예외를 재시도 가능한 409로 바꾸는 안은 unique 위반을 일반 매핑해야 해서 버렸다. Facade가 새 트랜잭션에서 다시 읽는 안은 `sync`가 바깥 트랜잭션을 소유하는 구조([도메인 모델](../wiki/domain-model.md) 4.6절)에서 이미 rollback-only가 되어 동작하지 않아 버렸다. 잠금은 그 구조에서도 그대로 동작한다.
- 통화 컬럼을 두지 않는다. `t_order_line`처럼 `t_order.currency`에서 읽는다.

### 범위 조정 (2026-09-21, 제출 전 multi-review와 스키마 대조 반영)
마이그레이션, JPA 엔티티, `@Transactional`이 있어 PR 템플릿 기준 🔴이다. multi-review(Claude, Codex 실행. Gemini는 CLI가 없어 미실행)와 구현자가 아닌 `code-reviewer`의 스키마 대조를 돌렸다. 셋 다 막는 결함은 없다고 판정했다. 구현 동작은 바뀌지 않는다.
- 같은 트랜잭션에서 주문을 먼저 일반 조회한 뒤 `findByIdForUpdate`를 부르는 경로를 두 리뷰어가 함께 짚었다. `sync`가 바깥 트랜잭션을 소유하면 생길 수 있다. 임시 테스트로 확인했다(커밋하지 않았다). 그 사이에 다른 트랜잭션이 주문을 바꿨으면 Hibernate 7.4.5가 낡은 값을 돌려주지 않고 `ObjectOptimisticLockingFailureException`("Query result contains conflicting version of entity already held in persistence context")을 던졌고 checkout 행은 하나였다. 조용히 틀리지 않고 실패한다. port의 KDoc에 "이 조회를 그 트랜잭션에서 이 주문의 첫 접촉으로 둔다"를 적는다.
- 동시성 테스트가 1초 타임아웃으로 "기다린다"를 판정해서, 뒤 요청이 아직 시작하지 않았어도 통과할 수 있었다. `pg_stat_activity`에서 잠금 대기 세션을 확인한 뒤에 앞 트랜잭션을 풀도록 바꾸고 latch의 시간 초과를 실패로 처리한다.
- 새 checkout 행이 INSERT 한 번(`version` 0)인지와 소수 자리 통화(USD)의 `numeric(19,4)` 왕복 복원을 테스트로 고정한다.
- 스키마 변경 검토의 답 2에 `order_id`를 읽는 곳을, 답 6에 잠금 없는 읽기의 성질과 line만 바꾸는 저장의 version, unique 위반의 관측을 더했다.
- 잠금 대기 시간 제한은 두 리뷰어가 모두 다시 보라고 했다. 대기하는 요청이 커넥션을 쥐고 있어서 한 주문의 경합이 풀 전체로 번질 수 있고, 잠금은 Facade 메서드가 아니라 바깥 트랜잭션이 끝날 때까지 유지되며, `publishEvent`의 동기 리스너도 그 안에서 돈다. endpoint가 열리는 다음 PR의 결정 항목으로 분할 계획에 옮겼다.

## 스키마 변경 검토
[도메인 모델](../wiki/domain-model.md) 4.7절 질문의 답이다.

1. 도출 가능성: `amount`는 지금은 line 합계와 같지만 "시간이 지나면 바뀌는 사실의 고정"이다. `ChangeOrderLine`과 결제 실패 뒤 재-checkout이 생기면 회차마다 금액이 달라지고 line에서 도출할 수 없다. `checkout_no`는 결제 시도 회차라는 업무 사실이다. `id`는 도메인 ID다. 통화는 `t_order.currency`에서 도출되므로 두지 않았다.
2. 읽는 코드: `OrderCheckoutEntity.toDomain`이 `id`·`checkout_no`·`amount`를 읽어 `Order.checkouts`를 복원한다. `order_id`는 `findAllByOrderIdOrderByCheckoutNo`의 조회 조건이다. 도메인은 `checkouts.size`로 다음 회차를 정하고 Facade는 마지막 회차로 결과를 만든다. `version`·`created_at`·`updated_at`·`deleted_at`은 `BaseEntity` 공통 컬럼이다. checkout은 갱신·삭제하지 않으므로 `version`은 0에 머물고 `deleted_at`은 비어 있다. 모든 엔티티가 `BaseEntity`를 상속한다는 규칙을 따른 결과다.
3. 나중에 채울 수 있는가: 없다. 회차별 확정 금액은 확정 시점에만 알 수 있다.
4. 마지막 방어선: `amount`는 판매 시점 확정 금액이고 unique `(order_id, checkout_no)`는 "한 주문에 같은 회차는 하나"의 방어선이다. 잠금이 빠지거나 다른 경로가 생겨도 이 제약이 중복 회차를 막는다. checkout은 삭제하지 않으므로 soft delete된 행도 제약에 계속 걸리는 것이 맞고 partial unique로 두지 않는다.
5. 정본: 확정 금액의 정본은 `t_order_checkout.amount` 하나다. `OrderPaymentRequested.amount`는 같은 트랜잭션에서 같은 `Checkout`으로 만든 사본이고 `payment`가 attempt에 기록할 값이다. 어긋났을 때의 복구(승인 금액과 checkout 대조)는 `MarkOrderPaid` PR이 맡는다.
6. check-then-act: Facade가 `status`를 읽고 전이를 정한다. 경합은 주문 행의 `select for update`가 막는다. 잠금이 없어도 루트의 `status`가 바뀌므로 `@Version`이, 그리고 unique `(order_id, checkout_no)`가 두 번째 커밋을 거절한다. Hibernate가 INSERT를 UPDATE보다 먼저 flush하므로 실제로 먼저 걸리는 것은 unique 제약이다. 어댑터는 주문, line, checkout을 세 쿼리로 읽는다. 쓰기 경로는 주문 행을 먼저 잠그고 나머지를 읽으므로 같은 주문의 다른 커밋이 끼어들지 않는다. 잠금 없는 읽기(`findById`, `findByClientOrderId`)는 READ COMMITTED에서 세 쿼리가 서로 다른 커밋을 읽을 수 있어서 `OPEN`인데 `checkouts`가 하나인 주문이 복원될 수 있다. 전이 판단에는 `findByIdForUpdate`만 쓰고, 주문 생성 재시도 경로는 `checkouts`를 읽지 않으므로 허용한다. line만 바꾸는 저장은 `t_order`를 UPDATE하지 않아 루트 `@Version`이 오르지 않는다. `ChangeOrderLine`을 넣는 PR이 같은 잠금 조회를 쓰거나 version 증가를 함께 정한다. 잠금이 우회돼 unique 제약에 걸리면 `DataIntegrityViolationException`이고 HTTP에서는 500이다. `CreateOrder`의 같은 키 동시 요청과 같은 성질의 알려진 빈틈이다.
7. 락과 크기: 새 테이블 `CREATE TABLE`이라 기존 테이블에 락을 잡지 않는다. unique 제약의 인덱스가 `order_id`로 시작하므로 주문별 조회용 인덱스를 따로 두지 않는다.
8. 이전 버전 호환: 이전 버전은 `t_order_checkout`을 모르고 `JPA validate`는 매핑된 엔티티만 검사하므로 기동과 쓰기에 영향이 없다. 이전 버전은 `CHECKOUT_REQUESTED`를 이미 읽을 수 있다(#31에서 enum 상수를 먼저 배포했다).
9. 이벤트·캐시: `amount`와 `checkout_no`가 `OrderPaymentRequested`에 실린다. payload는 #31에서 정했고 이 PR은 바꾸지 않는다. 직렬화 형태와 하위 호환은 Event Publication Registry를 넣는 PR이 확인한다. 캐시는 없다.

## 제외
- `POST` endpoint, 요청·응답 DTO, REST Docs의 `data.status` 설명. 다음 PR `order-request-checkout-api`.
- Event Publication Registry. `payment` PR 전에 도입한다.
- 잠금 대기 시간 제한. PostgreSQL 기본값은 무제한 대기다. 트랜잭션이 짧고 같은 주문에 한정된 경합이라 지금은 두지 않는다. provider 호출처럼 긴 작업을 주문 트랜잭션 안에서 하지 않는다는 기존 원칙이 전제다.
- `CreateOrder`의 같은 키 동시 요청 빈틈. 주문 행이 아직 없어서 행 잠금으로 풀 수 없는 별개의 문제다.
- 요청한 단말이 주문의 단말·매장과 같은지 확인하는 인가. 단말 인증이 아직 없다.

## 관련 문서
없음.

## 변경 경로
- src/main/resources/db/migration/V003__order_request_checkout.sql
- src/main/kotlin/com/resilientpos/order/OrderId.kt
- src/main/kotlin/com/resilientpos/order/RequestCheckoutCommand.kt
- src/main/kotlin/com/resilientpos/order/OrderCommandFacade.kt
- src/main/kotlin/com/resilientpos/order/internal/domain/Order.kt
- src/main/kotlin/com/resilientpos/order/internal/domain/Checkout.kt
- src/main/kotlin/com/resilientpos/order/internal/domain/OrderRepository.kt
- src/main/kotlin/com/resilientpos/order/internal/adapter/persistence/command/**
- src/test/kotlin/com/resilientpos/order/internal/domain/OrderTest.kt
- src/test/kotlin/com/resilientpos/order/internal/application/**
- src/test/kotlin/com/resilientpos/SchemaMigrationTest.kt
- docs/spec/2026-09-21-order-request-checkout-persistence.md
- docs/wiki/current-state.md
- docs/wiki/domain-model.md
- docs/wiki/changes.md

## 인수 조건
1. `OPEN` 주문에 `facade.requestCheckout`을 부르면 결과가 `CHECKOUT_REQUESTED`, `checkoutNo` 1, `amount`는 주문 합계다. `t_order.status`가 `CHECKOUT_REQUESTED`이고 `t_order_checkout`에 그 주문의 행이 하나이며 `OrderPaymentRequested`가 한 번 발행된다.
2. 그 주문을 `repository.findById`로 다시 읽으면 `checkouts`가 저장 전과 같은 값(`id`·`checkoutNo`·`amount`)으로 복원된다.
3. 같은 주문에 다시 `requestCheckout`을 부르면 1과 같은 결과를 받고 `t_order_checkout`의 행이 늘지 않으며 `OrderPaymentRequested`가 다시 발행되지 않는다.
4. 없는 `orderId`면 `DomainException`의 `code`가 `ORDER_NOT_FOUND`, `kind`가 `NOT_FOUND`다.
5. 한 트랜잭션이 `requestCheckout`을 부르고 아직 커밋하지 않은 동안 다른 스레드의 `requestCheckout`은 끝나지 않고 기다린다. 앞 트랜잭션이 커밋하면 뒤 요청이 같은 결과(`checkoutNo` 1, 같은 `amount`)를 받고 `t_order_checkout`의 행은 하나다.
6. checkout을 요청한 주문에 같은 키와 같은 payload로 `facade.create`를 다시 부르면 같은 `orderId`와 `CHECKOUT_REQUESTED`를 돌려준다.
7. 도메인: `requestCheckout`이 만든 `Checkout`의 `id`는 식별자 생성기의 첫 값이고 이벤트의 `eventId`는 둘째 값이다.
8. 기존 테스트 64개가 그대로 통과한다(7에 맞춰 고친 도메인 테스트 포함).

## 검증
- 도메인: `./gradlew test --tests 'com.resilientpos.order.internal.domain.OrderTest'`
- Facade 통합: `./gradlew test --tests 'com.resilientpos.order.internal.application.OrderRequestCheckoutIntegrationTest'` (Docker 필요)
- 전체 회귀: `./gradlew test`
- 하네스: `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전 `--worktree` 없이 한 번 더.
- 정상 시나리오: 인수 조건 1·2·6. 재시도·동시성: 3·5. 실패: 4.

## Wiki 영향
- [현재 상태](../wiki/current-state.md): "도메인에만 있다"는 문단을 Facade·`t_order_checkout`·잠금 조회가 있고 endpoint만 없다는 내용으로 고친다. 주문 생성 재시도가 현재 `status`를 돌려준다는 점, 마이그레이션 `V003`, 테스트 수를 반영한다.
- [도메인 모델](../wiki/domain-model.md): aggregate 구성 표의 checkout 설명에서 "키는 영속화 PR이 정한다"를 `CheckoutId`와 unique 제약으로 바꾼다. 4.7절 사례에 `t_order_checkout`의 판단(통화 컬럼 없음, unique가 방어선, 잠금)을 더한다.
- [변경 기록](../wiki/changes.md): 이 spec 링크와 결정을 적는다.

## 분할 계획
예상 규모는 구현 약 150줄, 테스트 약 170줄, 문서 약 170줄로 총 490줄·16파일 내외다. 경고 한도(600줄·20파일) 안이다. 초과하면 동시성 테스트(인수 조건 5)와 잠금 조회를 후속 PR로 옮기지 않고 wiki의 도메인 모델 갱신을 줄인다. 잠금은 이 PR의 정합성 답이라 나누지 않는다. 후속은 `order-request-checkout-api`(endpoint, 요청·응답 DTO, REST Docs, `ORDER_NOT_FOUND`의 404 문서화, `data.status` 설명)다.

다음 PR이 정할 것:
- 잠금 대기 시간 제한. 후보는 Hikari `connection-init-sql`의 `set lock_timeout`이나 JDBC URL 옵션이고, 초과했을 때의 응답(재시도 가능한 오류 코드)을 함께 정한다. `idle_in_transaction_session_timeout`도 같이 본다.
- 인가가 없다는 점을 [현재 상태](../wiki/current-state.md)의 알려진 빈틈에 적는다. endpoint가 열리면 `orderId`만 알아도 다른 매장의 주문을 전이시킬 수 있다.

## TDD 검증 기록

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 조건 7: 도메인 테스트의 식별자 순서 | 미실행 | 미실행 | 미실행 |
| 인수 조건 1: `requestCheckout은 CHECKOUT_REQUESTED와 checkout 행을 저장하고 OrderPaymentRequested를 한 번 발행한다` | 미실행 | 미실행 | 미실행 |
| 인수 조건 2: `checkout을 요청한 주문을 다시 읽으면 checkouts가 그대로 복원된다` | 미실행 | 미실행 | 미실행 |
| 인수 조건 3: `같은 주문의 requestCheckout 재요청은 기존 결과를 돌려주고 행과 이벤트를 늘리지 않는다` | 미실행 | 미실행 | 미실행 |
| 인수 조건 4: `없는 주문의 requestCheckout은 ORDER_NOT_FOUND다` | 미실행 | 미실행 | 미실행 |
| 인수 조건 5: `커밋 전인 requestCheckout과 겹친 요청은 기다렸다가 같은 결과를 받는다` | 미실행 | 미실행 | 미실행 |
| 인수 조건 6: `checkout을 요청한 주문의 생성 재시도는 CHECKOUT_REQUESTED를 돌려준다` | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
