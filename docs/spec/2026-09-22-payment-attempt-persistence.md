# PR spec: t_payment_attempt와 PaymentAttempt의 JPA 매핑

## 목적
`PaymentAttempt`를 저장할 곳을 만든다. 앞선 PR이 "한 주문에 시도가 여럿 달린다"를 코드로 정했는데, 그 관계가 테이블에서도 성립하는지는 테이블 없이 확인할 수 없다. 엔티티와 마이그레이션을 만들고 실제 PostgreSQL에 여러 건을 넣어 확인한다.

## 범위
소유 모듈은 `payment`다.

**복합 자연 키를 두지 않는다.** `id` 단독 primary key이고 `order_id`는 인덱스만 둔 참조다. `(order_id, ...)` 형태의 unique 제약은 한 주문의 시도 수를 제한하는 쪽으로 작동해서 1-to-many를 막는다. 승인 뒤 취소, 거절 뒤 재시도가 모두 같은 `order_id`에 달려야 한다.

같은 이벤트가 두 번 전달돼 시도가 두 번 만들어지는 것은 이 키가 막을 일이 아니다. Event Publication Registry가 `(이벤트, 리스너)`마다 완료를 기록한다.

**DB FK를 두지 않는다.** `payment`와 `order`는 서로의 테이블을 읽지 않는 별개 컨텍스트다. `t_order_checkout`이 같은 이유로 FK를 두지 않은 선례가 있다.

**통화를 직접 가진다.** `t_order`에서 읽으면 모듈 경계를 넘는다. 승인 금액을 검증하려면 통화가 금액과 같은 행에 있어야 한다. 컬럼은 `char(3)`이고 `t_order`와 같은 `@JdbcTypeCode(SqlTypes.CHAR)` 매핑을 쓴다.

**두 enum은 `EnumType.STRING`이다.** JPA 기본값인 ORDINAL로 두면 값을 하나 끼워 넣는 순간 이미 저장된 행의 뜻이 통째로 바뀐다.

연관관계 매핑을 쓰지 않는다. `order` 쪽 엔티티들과 같은 방식으로 `orderId` 컬럼만 가진 독립 엔티티다.

계약: 새 `DomainException` code가 없다. 응답으로 나가는 것도 없다. `PaymentAttempt.restore`가 늘어나는데 생성 불변식을 다시 묻지 않는 복원 경로이고 `Order.restore`와 같은 계약이다.

복구: 이 PR은 저장 경로만 만들고 부르는 쪽이 없어 실패 시나리오가 생기지 않는다.

관측성: 없음. 트랜잭션도 외부 호출도 없다.

## 제외
- `PaymentAttemptRepository` 포트와 adapter, Facade. 부르는 쪽과 같은 PR에 둔다.
- `OrderPaymentRequested` 리스너와 그에 딸린 재시도·풀·종료 정책.
- `PaymentGateway` 포트와 `FakePaymentGateway`.
- provider idempotency 키의 저장 위치. 실제 provider 호출이 생기는 PR이 정한다.
- 어떤 PG를 썼는지 남기는 `provider` 컬럼. 구현이 하나뿐이라 지금은 값이 늘 같다. 행이 없는 동안은 nullable 컬럼 추가가 백필 없는 마이그레이션이다.
- 조회 모델과 인덱스 추가. `order_id` 하나로 시작하고 실제 조회가 생길 때 다시 본다.

## 관련 문서
선행 PR은 [payment-domain-model](2026-09-22-payment-domain-model.md)이다. 정본은 [도메인 모델](../wiki/domain-model.md)이고, 영속화 규약의 선례는 [order-create-order-persistence](2026-09-16-order-create-order-persistence.md)와 [order-request-checkout-persistence](2026-09-21-order-request-checkout-persistence.md)다.

## 변경 경로
- src/main/kotlin/com/resilientpos/payment/internal/adapter/persistence/**
- src/main/resources/db/migration/V006__payment_payment_attempt.sql
- src/test/kotlin/com/resilientpos/payment/internal/adapter/persistence/**
- src/test/kotlin/com/resilientpos/SchemaMigrationTest.kt
- docs/**

## 인수 조건
1. 한 주문에 결제 시도가 여러 건 저장되고 모두 조회된다. 실제 PostgreSQL에서 확인한다.
2. 저장한 시도를 도메인으로 복원하면 모든 필드가 그대로다.
3. 0원 시도도 `APPROVED` 상태 그대로 저장되고 복원된다. `providerReference`는 `null`이다.
4. `ddl-auto: validate`가 통과한다. 엔티티와 테이블이 어긋나면 컨텍스트가 뜨지 않는다.
5. `SchemaMigrationTest`의 마이그레이션 목록과 테이블 목록에 새 항목이 반영된다.
6. 기존 테스트가 하나도 깨지지 않는다.

## 스키마 변경 검토
- 필요성: `payment`가 시도를 저장할 곳이 없다. 앞선 PR이 정한 1-to-many가 테이블에서 성립하는지 확인할 수단도 없다.
- 정합성: `id` 단독 primary key다. unique 제약과 DB FK를 두지 않는 이유는 위 범위에 적었다. `order_id` 인덱스 하나를 둔다. 한 주문의 시도를 모두 읽는 것이 기본 조회다. 금액은 `numeric(19,4)`로 다른 금액 컬럼과 같고 `Money.MAX_INTEGER_DIGITS`가 그 범위를 지킨다.
- 반영: `ddl-auto: validate`가 엔티티와 테이블을 대조하고 `SchemaMigrationTest`가 목록을 고정한다.
- 대조 기록: 미기재. 이 저장소에 구현자 아닌 리뷰어가 없어 multi-review 에이전트가 대신 본다. 결과는 결과 절에 적는다.

## 검증
- `./gradlew test` — 전부 통과. Docker가 필요하다.
- `python3 scripts/check_pr.py --base feature/payment-domain-model --worktree` — PASS. 이 PR은 도메인 PR 위에 쌓는다.
- 1-to-many는 실제 PostgreSQL에 세 건을 넣어 확인한다. 엔티티와 테이블 정의만 읽고 넘어가지 않는다.

## Wiki 영향
- [현재 상태](../wiki/current-state.md) — `t_payment_attempt`와 그 설계 결정을 적고 테스트 수를 맞춘다.
- 요구사항 변화: 없다. 저장 경로만 생기고 API도 이벤트도 그대로다.

## 분할 계획
5개 파일 안팎 / 약 220줄. 한도 안이다. 도메인 모델과 목표가 달라 앞선 PR에서 떼어 냈다.

## TDD 검증 기록

통합 테스트를 먼저 넣어 RED를 봤다(`Unresolved reference 'PaymentAttemptJpaRepository'`). 그다음 엔티티·리포지토리·마이그레이션을 넣었다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 한 주문에 결제 시도가 여러 건 저장된다 | `./gradlew compileTestKotlin` — 엔티티·리포지토리 없음 | `./gradlew test` — 통과 | `./gradlew test` — 통과 |
| 저장한 시도를 도메인으로 그대로 복원한다 | 같은 이유 | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| 0원 시도도 승인 상태 그대로 저장되고 복원된다 | 같은 이유 | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| 마이그레이션·테이블 목록이 실제와 맞는다 | `SchemaMigrationTest` FAILED — 목록에 `006`과 `t_payment_attempt`가 없음 | 목록에 더한 뒤 통과 | `./gradlew test` — 통과 |

## 결과
`./gradlew test` 152개 통과다(도메인 PR의 149 + 신규 3). 한 주문에 결제 행 3건이 실제 PostgreSQL에 저장되고 모두 조회되는 것을 확인했다. 복합 자연 키를 두지 않기로 한 결정이 테이블에서도 성립한다.

**통화 매핑을 빠뜨리면 컨텍스트가 뜨지 않는다.** `char(3)` 컬럼에 `@JdbcTypeCode(SqlTypes.CHAR)` 없이 `String`을 매핑하니 `ddl-auto: validate`가 `varchar(255)`를 기대해 `Schema validation: wrong column type encountered in column [currency]`로 실패했다. `t_order`의 선례를 따라 고쳤다. 스키마 검증이 이 종류를 잡아 준다는 것을 실제로 확인한 셈이다.

`SchemaMigrationTest`가 마이그레이션 목록과 테이블 목록을 값으로 고정하고 있어서 새 테이블을 더할 때 반드시 함께 고쳐야 한다. 이 PR에서 실제로 실패를 보고 고쳤다.
