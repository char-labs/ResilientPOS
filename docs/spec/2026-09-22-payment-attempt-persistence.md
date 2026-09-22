# PR spec: t_payment_attempt와 PaymentAttempt의 JPA 매핑

## 목적
`PaymentAttempt`를 저장할 곳을 만든다. 앞선 PR이 "한 주문에 시도가 여럿 달린다"를 코드로 정했는데, 그 관계가 테이블에서도 성립하는지는 테이블 없이 확인할 수 없다. 엔티티와 마이그레이션을 만들고 실제 PostgreSQL에 여러 건을 넣어 확인한다.

## 범위
소유 모듈은 `payment`다.

**무조건적인 복합 unique를 두지 않는다.** `id` 단독 primary key이고 `order_id`는 인덱스 참조다. `(order_id, ...)`에 조건 없이 unique를 걸면 한 주문의 시도 수를 제한해서 1-to-many를 막는다.

**대신 상태를 술어에 넣은 partial unique로 진행 중인 승인만 하나로 묶는다.** 술어는 `kind = 'AUTHORIZATION' and status in ('REQUESTED', 'APPROVED', 'UNKNOWN') and deleted_at is null`이다. 거절된 시도는 `DECLINED`가 되어 술어에서 빠지므로 재시도가 들어가고, 취소·환불은 `kind`가 달라 술어 밖이다. `UNKNOWN`을 넣은 것은 도메인이 이미 "`UNKNOWN`에서 새 승인 요청으로 재시도하지 않는다"를 규칙으로 정했기 때문이고, 이 인덱스가 그 마지막 방어선이다.

**이벤트 전달 계층은 이 방어선을 대신하지 못한다.** Event Publication Registry가 `(이벤트, 리스너)`마다 완료를 기록하지만, 그 기록은 리스너의 업무 커밋과 같은 트랜잭션이 아니다. `@ApplicationModuleListener`가 비동기·`AFTER_COMMIT`·`REQUIRES_NEW`라서 INSERT가 커밋된 뒤에 완료 표시가 찍힌다. 그 사이에 프로세스가 죽으면 승인 행은 있는데 발행은 미완료다. 재전송이 한 번이라도 돌면 두 번째 승인이 들어온다. 두 행은 `id`와 시각만 다르고 나머지가 같아서 **중복 청구와 정당한 재시도를 사후에 구분할 수 없다.** 그래서 DB 제약이 필요하다.

**DB FK를 두지 않는다.** `payment`와 `order`는 서로의 테이블을 읽지 않는 별개 컨텍스트다. `t_order_checkout`이 같은 이유로 FK를 두지 않은 선례가 있다.

**통화를 직접 가진다.** `t_order`에서 읽으면 모듈 경계를 넘는다. 승인 금액을 검증하려면 통화가 금액과 같은 행에 있어야 한다. 컬럼은 `char(3)`이고 `t_order`와 같은 `@JdbcTypeCode(SqlTypes.CHAR)` 매핑을 쓴다.

**두 enum은 `EnumType.STRING`이고 컬럼은 `varchar(32)`다.** ORDINAL로 두면 값을 하나 끼워 넣는 순간 이미 저장된 행의 뜻이 통째로 바뀐다. 길이 32는 `t_order.status`의 선례를 따랐다. Hibernate의 `validate`가 varchar 길이를 대조하지 않으므로, 짧게 잡으면 값을 늘리는 PR이 운영에서야 `value too long`을 만난다.

**행을 지우지 않는다.** `BaseEntity`가 soft delete를 강제하는데, `JpaRepository`를 상속하면 `deleteAllInBatch` 같은 메서드가 공개 빈에 딸려 온다. 그 한 번이 모든 결제 시도를 조용히 조회에서 지운다. 좁힌 `Repository`를 상속해 `save`와 조회만 노출한다. 조회는 `findAllByOrderIdOrderById`로 순서를 이름에 박는다. `id`가 UUID v7이라 정렬이 곧 생성 순서이고, 선례인 `OrderLineJpaRepository`·`OrderCheckoutJpaRepository`도 정렬을 이름에 둔다.

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
- 조회 모델과 인덱스 추가. `(order_id, id)` 하나로 시작하고 실제 조회가 생길 때 다시 본다. soft delete 부분 인덱스는 지금 필요 없다 — 시도를 지우는 코드가 없어 죽은 행이 쌓이지 않고, `order_id`로 좁힌 뒤 `deleted_at is null`을 거르는 비용이 사실상 0이다.
- `currency`에 형식 CHECK 제약. `Currency.getInstance`가 복원에서 걸러 낸다. 수동 DML 말고는 잘못된 값이 들어올 경로가 없다.

## 관련 문서
선행 PR은 [payment-domain-model](2026-09-22-payment-domain-model.md)이다. 정본은 [도메인 모델](../wiki/domain-model.md)이고, 영속화 규약의 선례는 [order-create-order-persistence](2026-09-16-order-create-order-persistence.md)와 [order-request-checkout-persistence](2026-09-21-order-request-checkout-persistence.md)다.

## 변경 경로
- src/main/kotlin/com/resilientpos/foundation/Money.kt
- src/main/kotlin/com/resilientpos/payment/internal/adapter/persistence/**
- src/main/resources/db/migration/V006__payment_payment_attempt.sql
- src/test/kotlin/com/resilientpos/payment/internal/adapter/persistence/**
- src/test/kotlin/com/resilientpos/SchemaMigrationTest.kt
- docs/**

## 인수 조건
1. 한 주문에 결제 시도가 여러 건 저장되고 모두 조회된다. 실제 PostgreSQL에서 확인한다.
2. 저장한 시도를 도메인으로 복원하면 값이 그대로다. KRW가 아닌 통화와 `CANCELLATION`·`UNKNOWN`·참조 있음으로도 확인한다.
3. 0원 시도도 `APPROVED` 상태 그대로 저장되고 복원된다. `providerReference`는 `null`이다.
4. `ddl-auto: validate`가 통과한다. 엔티티와 테이블이 어긋나면 컨텍스트가 뜨지 않는다.
5. `SchemaMigrationTest`의 마이그레이션 목록과 테이블 목록에 새 항목이 반영된다.
6. 같은 checkout에 진행 중인 승인 시도를 두 번 넣으면 거절된다. 거절 뒤 재시도·취소·다른 회차는 나란히 쌓인다.
7. 컬럼 타입과 폭이 값으로 고정된다. `numeric(19,0)`이나 `varchar(8)`로 좁히면 테스트가 깨진다.
8. 기존 테스트가 하나도 깨지지 않는다.

## 스키마 변경 검토
- 필요성: `payment`가 시도를 저장할 곳이 없다. 앞선 PR이 정한 1-to-many가 테이블에서 성립하는지 확인할 수단도 없다. 컬럼은 전부 도메인이 이미 들고 있는 값이다. 하나 예외가 `provider_reference`인데, 지금은 늘 `null`이지만 `PaymentAttempt`의 필드라서 왕복하려면 컬럼이 있어야 한다. 값을 채우는 코드가 없어 컬럼을 뺄 수 있는 `provider`와는 이 점이 다르다.
- 정합성: `id` 단독 primary key다. 무조건적 unique와 DB FK를 두지 않는 이유, 대신 partial unique를 두는 이유는 위 범위에 적었다. 인덱스는 `(order_id, id)` 하나다. 금액은 `numeric(19,4)`로 다른 금액 컬럼과 같고 `Money.MAX_INTEGER_DIGITS = 15`가 그 범위를 지킨다.
- 정본: `currency`는 `t_order.currency`와 **같은 사실의 두 번째 사본**이다. 모듈 경계 때문에 사본을 둔다. 두 값은 서로 다른 트랜잭션에서 쓰이므로 어긋날 수 있고, 어긋나면 **`payment`의 값이 정본이다** — 승인 요청을 보낸 통화가 그것이기 때문이다. 주문의 통화를 바꾸는 명령이 생기면 그 PR이 이 규칙을 다시 본다.
- check-then-act: 있다. "이 checkout에 진행 중인 승인이 있는가"를 읽고 없으면 만드는 경로다. 읽기와 쓰기 사이의 창은 위 partial unique가 닫는다.
- 락과 크기: 빈 테이블에 `create table`과 인덱스 둘이라 잠글 대상이 없다. `CONCURRENTLY`도 필요 없다.
- 이전 버전 호환: 새 테이블이라 롤링 배포에서 이전 버전이 읽거나 쓰지 않는다.
- 이벤트 payload: `amount`·`currency`·`kind`·`checkoutNo`가 `PaymentAttemptCreated`·`PaymentApproved`에 그대로 실린다. 이벤트는 JSON으로 저장되므로 이 컬럼들의 이름·타입을 바꾸는 PR은 이미 저장된 이벤트의 복원도 함께 본다.
- 반영: `ddl-auto: validate`가 엔티티와 테이블을 대조하고, `SchemaMigrationTest`가 마이그레이션·테이블 목록을, `PaymentAttemptSchemaTest`가 컬럼 타입과 폭을 고정한다.
- 대조 기록: reviewer-claude·reviewer-codex, 2026-09-22. 불일치 — (1) 중복 승인 방어선 부재, EPR을 근거로 든 것이 성립하지 않음, (2) `kind`·`status`가 `varchar(20)`으로 선례(32)와 다름, (3) `domain-model.md`·`current-state.md`의 `(orderId, attemptNo)` 서술이 스키마와 불일치, (4) 스키마 변경 검토 질문 5~9 미답, (5) 컬럼 타입·폭을 고정하는 테스트 부재, (6) 조회 정렬 미정의. 전부 이 PR에서 고쳤다.

## 검증
- `./gradlew test` — 전부 통과. Docker가 필요하다.
- `python3 scripts/check_pr.py --base feature/payment-domain-model --worktree` — PASS. 이 PR은 도메인 PR 위에 쌓는다.
- 1-to-many와 중복 승인 차단을 실제 PostgreSQL에서 확인한다. 엔티티와 테이블 정의만 읽고 넘어가지 않는다.

## Wiki 영향
- [현재 상태](../wiki/current-state.md) — `t_payment_attempt`와 그 설계 결정을 적고 테스트 수를 맞춘다. 앞선 PR이 `attemptNo`를 걷어내면서 남은 서술도 함께 지운다.
- [도메인 모델](../wiki/domain-model.md) — `payment`의 핵심 불변식, `attempt` 용어, 멱등성 담당자 세 곳이 `(orderId, attemptNo)`를 가리키고 있다. 그 키가 없어졌으므로 정본을 실제 스키마에 맞춘다.
- 요구사항 변화: 없다. 저장 경로만 생기고 API도 이벤트도 그대로다.

## 분할 계획
8개 파일. 한도 안이다. 도메인 모델과 목표가 달라 앞선 PR에서 떼어 냈다.

## TDD 검증 기록
적용하지 않는다. [spec 템플릿](../spec-template.md)이 Repository 전용 작업을 제외하고, [테스트 전략](../wiki/testing-strategy.md)도 "컴파일 실패는 RED가 아니다"라고 정한다. 이 PR에서 관측할 수 있는 실패는 타입이 없어 컴파일이 안 되는 것뿐이라 그 기준을 만족하지 못한다.

대신 **되돌려서** 각 테스트가 무엇을 지키는지 확인했고 결과를 아래에 적었다.

## 결과
`./gradlew test` 156개 통과다(도메인 PR의 149 + 신규 7).

- **1-to-many와 중복 차단이 함께 성립한다.** 거절된 시도 2건 + 승인 1건 + 취소 1건 + 다른 회차 1건이 같은 주문에 나란히 들어가고, 같은 checkout에 진행 중인 승인을 두 번 넣으면 `DataIntegrityViolationException`이 난다. 상태를 술어에 넣은 덕분에 두 가지가 동시에 만족된다.
- **컬럼 타입과 폭은 왕복 테스트로 고정되지 않는다.** `toDomain()`이 `Money.of`로 정규화하므로 `numeric(19,0)`으로 바꿔도 KRW 정수 금액은 그대로 돌아온다. `validate`도 varchar 길이를 대조하지 않는다. `information_schema.columns`를 읽어 값으로 고정하는 테스트를 따로 뒀다.
- **통화 매핑을 빠뜨리면 컨텍스트가 뜨지 않는다.** `char(3)`에 `@JdbcTypeCode(SqlTypes.CHAR)` 없이 `String`을 매핑하니 `wrong column type encountered in column [currency] … found [bpchar], but expecting [varchar(255)]`로 실패했다. `t_order`의 선례를 따라 고쳤다. 다만 이 선례가 증명하는 것은 **매핑이 검증을 통과한다는 것뿐**이다. 통화로 필터하는 조회가 저장소에 하나도 없어서 `bpchar` 비교 동작은 확인된 바 없고, 그런 조회를 처음 만드는 PR이 봐야 한다.
- **`EnumType.STRING`을 지키는 것은 단언이 아니라 스키마 검증이다.** ORDINAL로 바꾸면 `found [varchar], but expecting [smallint]`로 컨텍스트가 뜨지 않는다. 왕복 테스트는 ORDINAL로 저장해도 같은 값을 돌려주므로 잡지 못한다.
- **앞선 테스트들이 전부 KRW·`AUTHORIZATION`·`providerReference = null`이라** 매핑이 그 값들을 박아 넣어도 통과했다. 실제로 `from()`에서 세 값을 상수로 바꿔 재현했고, USD `10.99`·`CANCELLATION`·`UNKNOWN`·참조 있음으로 오가는 테스트를 더해 막았다.
- **테스트에 `@Transactional`을 붙이지 않는다.** 붙이면 저장 직후 조회가 같은 영속성 컨텍스트의 관리 엔티티를 돌려줘 DB를 한 번도 읽지 않고 통과한다. 격리는 테스트마다 다른 `orderId`로 한다. 이 이유를 테스트 KDoc에 남겼다.
- **`SchemaMigrationTest`가 목록을 값으로 고정**하고 있어 새 테이블을 더할 때 함께 고쳐야 한다. 실제로 실패를 보고 고쳤다.

검토: multi-review를 실행했다(Claude 74점·Codex 실행, Gemini는 CLI가 없어 미실행). 블로킹 두 건을 받았다.

**받았다 — "중복 생성은 이벤트 전달 계층이 막는다"는 근거가 성립하지 않는다.** EPR의 완료 기록은 리스너의 업무 커밋과 같은 트랜잭션이 아니다. 비동기·`AFTER_COMMIT`·`REQUIRES_NEW`라 INSERT 커밋과 완료 표시 사이에 창이 있고, 거기서 죽으면 재전송이 두 번째 승인을 만든다. 정본 `domain-model.md`가 "멱등성은 자연 unique key가 담당한다"고 적어 둔 것과도 정면으로 어긋났다 — 서로를 근거로 삼는 순환이었다. partial unique를 넣어 닫았고, 테이블이 비어 있는 지금이 가장 싼 시점이다.

**받았다 — 정본 wiki가 없어진 것을 선언하고 있다.** `current-state.md`가 존재하지 않는 `INVALID_ATTEMPT_NO`를 적고 있었고, `domain-model.md` 세 곳이 `(orderId, attemptNo)`를 핵심 불변식·용어·멱등성 담당자로 선언하고 있었다. 앞선 PR이 `attemptNo`를 걷어내며 남긴 부채다. 여기서 정리했다.

**받았다 — `varchar(20)`은 선례(32)와 다르고 `validate`가 길이를 대조하지 않는다.** 32로 맞췄다. 컬럼 메타데이터 테스트가 이제 폭까지 고정한다.

**받았다 — `JpaRepository`가 `deleteAllInBatch`를 노출한다.** soft delete가 걸려 있어 한 번이면 모든 결제 시도가 조용히 조회에서 사라진다. 좁힌 `Repository`로 바꿔 `save`와 조회만 남겼다.

**받았다 — 조회 정렬이 미정의다.** 선례 둘이 정렬을 이름에 박는다. `findAllByOrderIdOrderById`로 바꿨다. UUID v7이라 정렬이 곧 생성 순서이고 인덱스도 `(order_id, id)`로 뒀다.

**받았다 — TDD 기록의 RED가 컴파일 실패다.** 테스트 전략이 그것을 RED에서 제외한다. Repository 전용 작업이라 적용하지 않는다고 적고, 대신 되돌려 확인한 결과를 위에 남겼다. 이 테스트가 "Repository 전용 테스트를 만들지 않는다"는 규칙의 예외라는 것도 테스트 KDoc에 적었다.

**받았다 — 스키마 변경 검토가 9개 질문 중 3개만 답했다.** 정본·check-then-act·락·호환·이벤트 payload를 채웠다. 특히 `currency`가 `t_order.currency`의 **두 번째 사본**이라는 사실과, 어긋나면 `payment`의 값이 정본이라는 규칙을 적었다.

**받지 않았다 — Codex가 CRITICAL로 올린 "spec은 ORDINAL이라 적었는데 코드는 STRING"**은 사실이 아니다. spec은 처음부터 `EnumType.STRING`이라고 적었다. 리뷰 프롬프트가 "ORDINAL로 두면 어떻게 되는지 공격해 달라"고 쓴 것을 spec 내용으로 읽은 듯하다.

**받지 않았다 — soft delete 부분 인덱스.** 결제 시도를 지우는 코드가 없어 죽은 행이 쌓이지 않고, `order_id`로 좁힌 뒤 `deleted_at is null`을 거르는 비용이 사실상 0이다. Claude 리뷰도 같은 결론이다.

**받지 않았다 — `SchemaMigrationTest`의 마이그레이션 목록을 classpath에서 유도하자는 제안.** 타당하지만 그 파일은 이 PR의 목표 밖이고, 고치면 모든 마이그레이션 PR의 계약이 바뀐다. 별도 PR이 다룰 일이다.

**다음 PR로 넘겼다 — `provider_reference varchar(255)`의 근거.** 외부 연동 전략이 `paymentKey`를 저장하라고만 하고 길이를 말하지 않는다. 실제 provider를 붙이는 PR이 그 provider의 최대 길이로 다시 본다.
