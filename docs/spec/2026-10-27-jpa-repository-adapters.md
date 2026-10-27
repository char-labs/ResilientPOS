# PR spec: persistence 어댑터의 저장을 Spring Data repository로 옮긴다

## 목적
persistence 어댑터가 `EntityManager`를 주입받아 `persist`·`find`를 직접 부른다. 사용자가 이 저장 경로를 엔티티마다 둔 Spring Data repository(`XxxJpaRepository`)의 `save`·`findById`·파생 쿼리로 바꾸기로 했다(GitHub 이슈 없음, 사용자 요청).

기대 결과: `git grep -n EntityManager src/main`에 남는 것은 조회 실행기 `platform/JdslQueryExecutor`와 문서 주석뿐이다. 도메인 Repository 구현체는 새 행이면 `jpaRepository.save(Entity.from(domain))`, 기존 행이면 `findById`로 찾은 관리 엔티티에 `apply`하고 UPDATE는 dirty checking에 맡긴다.

## 범위
### 결정
**1. 어댑터는 `EntityManager`를 주입받지 않는다(사용자 결정, 2026-10-27).**
- 대상: `FulfillmentPersistenceAdapter`, `OrderPersistenceAdapter`, `OwnerPersistenceAdapter`, `PaymentAttemptPersistenceAdapter`, `ReconciliationCasePersistenceAdapter`, `StorePersistenceAdapter`, `SyncPersistenceAdapter`, `TerminalPersistenceAdapter`, `platform/deadletter`의 `DeadLetterDismisser`·`DeadLetterRedriver`.
- 새 행은 `save`, 여러 행은 `saveAll`이다. 기존 행은 `findById`로 찾는다. `JpaRepository`면 Kotlin 확장 `findByIdOrNull`을 쓰고, 좁힌 인터페이스면 nullable을 돌려주는 `findById`를 선언한다.
- 참고 구현은 Sliicy-Server의 `GroupCoreRepository`와 Sseudam-Server의 `UserCoreRepository`다. 따르는 것은 "도메인 Repository 구현이 JpaRepository에 위임한다"는 모양 하나다. `CoreRepository`라는 이름, 그 저장소들의 `Tx` 감싸기, `deletedAt` 조건은 따르지 않는다. 이 저장소에는 `@SoftDelete`와 자기 `Tx` 규약이 있다.
- 공용 헬퍼는 만들지 않는다. 어댑터마다 호출부 한 줄이면 된다.

**2. `@Version`이 없는 엔티티의 새 행은 `save`가 `merge`로 가서 SELECT가 한 번 더 나간다. 이 비용을 받아들인다(사용자 결정, 2026-10-27).**
- 도메인 ID(UUID v7)를 저장 전에 만들기 때문에, Spring Data는 `@Version`이 없는 엔티티를 기존 행으로 판정한다(`JpaMetamodelEntityInformation.isNew`가 id가 null인지만 본다). 그래서 `persist`가 아니라 `merge`가 불리고, `merge`는 PK로 SELECT한 뒤 INSERT한다.
- 대가: 새 행 하나마다 SELECT가 하나 늘어난다. line 500개짜리 주문이면 SELECT가 500번 더 나간다. 새 주문 하나(line N개)의 저장 문장은 `2 + N`개에서 `2 + 2N`개가 된다. 새 결제 시도는 1개에서 2개, sync 항목 하나(멱등 checkout)는 9개에서 10개가 된다.
- 이 결정은 #36과 [base-entity-explicit-version](2026-09-21-base-entity-explicit-version.md)이 정한 "`@Version` 없는 엔티티의 새 행은 INSERT 한 번"을 뒤집는다. 지난 spec은 고치지 않고, wiki([도메인 모델](../wiki/domain-model.md) §4.2·§4.3)와 기대값 테스트를 새 규약에 맞춘다.
- `OrderEntity`는 `@Version`(`Long?`)이 있어 새 주문은 그대로 `persist`로 간다. 그래서 `OrderCommandFacadeIntegrationTest`의 "신규 저장은 INSERT 한 번이고 version 0" 단정은 `t_order`에 대해 계속 맞고 그대로 둔다. 바뀌는 것은 line과 checkout이다.
- 고르지 않은 것: `Persistable` 구현과 `persist` fragment. 사용자가 고르지 않았다.

**3. `t_sync_watermark`는 `WatermarkEntity`에 `@Version`을 더해 새 행을 `persist`로 보낸다(사용자 결정, 2026-10-27).**
- 저장 경로마다 PK 경합을 확인했다(아래 표). 저장 직전에 만드는 UUID v7 PK는 겹치지 않는다. PK가 아닌 unique(이메일, 매장·단말 코드, `order_id`, `(terminal_id, operation_id)`, DLT source)는 `merge`도 INSERT를 내므로 지금처럼 커밋에서 걸린다.
- 걸린 곳은 단말 ID가 곧 PK인 `t_sync_watermark` 하나다. `SyncCommandFacade.ensureWatermark`는 "같은 단말의 첫 배치가 동시에 둘 오면 진 쪽은 PK 위반을 받고 삼킨다"로 PK 위반을 경합의 마지막 방어선으로 쓴다. `merge`로 바꾸면 다음 일이 생길 수 있다. ① A·B가 같은 단말의 첫 배치로 동시에 들어와 둘 다 "watermark 없음"을 본다. ② A가 행을 넣고 커밋한 뒤 항목을 처리해 순번을 올린다. ③ B의 `merge`가 SELECT로 A의 행을 보고, INSERT 대신 `Watermark.initial`(0)로 UPDATE한다. A가 행을 잠그고 있으면 B의 UPDATE는 기다렸다가 A의 커밋 뒤에 적용된다. ④ watermark가 0으로 돌아간다. 단말이 순번 1을 다시 보내면 NEXT로 판정되는데 그 operation이 이미 기록돼 있어 `CONFLICT`가 되고, 사람이 손대기 전까지 그 단말의 sync가 막힌다(spring-backend-specialist 판정 P0). 테스트는 직렬이라 이 창을 잡지 못한다.
- `version`이 `null`인 새 엔티티는 Spring Data가 새 행으로 판정해 `persist`로 보낸다. 그래서 진 쪽은 지금처럼 PK 위반을 받는다. `EntityManager` 예외도 `Persistable`도 필요 없다. 기존 행 경로(`findById` 뒤 `apply`)는 행 잠금 아래에서 UPDATE하므로 version 충돌이 나지 않는다.
- 대가: `t_sync_watermark.version` 컬럼과 마이그레이션 하나가 생긴다. 낙관적 잠금이 필요해서가 아니라 새 행 판정을 위한 컬럼이다. UPDATE마다 `where version = ?`가 붙는다.
- 고르지 않은 것: 이 한 곳만 `EntityManager.persist`로 남기는 안. 마이그레이션은 없지만 결정 1에 예외가 생긴다.

| 저장 | PK 출처 | merge 때 PK 경합 |
|---|---|---|
| `OrderEntity` | `Order.create`의 `ids.next()` | `@Version`이 있어 `persist`. 변화 없음 |
| `OrderLineEntity`·`OrderCheckoutEntity` | `Order.create`·`requestCheckout`의 `ids.next()` | 없음 |
| `PaymentAttemptEntity` | `PaymentAttempt`의 `ids.next()` | 없음 |
| `FulfillmentEntity` | `Fulfillment.create`의 `ids.next()` | 없음. 같은 주문은 unique `order_id`가 막는다 |
| `ReconciliationCaseEntity` | `ReconciliationCase.open`의 `ids.next()` | 없음 |
| `OwnerEntity`·`StoreEntity`·`TerminalEntity`·`TerminalCredentialEntity` | 각 생성 함수의 `ids.next()` | 없음 |
| `SyncOperationEntity` | `SyncOperation.applied`·`rejected`의 `ids.next()` | 없음. 같은 operation은 unique가 막는다 |
| `WatermarkEntity` | 단말 ID(도메인 값) | 있음 → 결정 3 |
| `DeadLetterRedriveEntity`·`DeadLetterDismissalEntity` | `identifierGenerator.next()` | 없음. 같은 레코드는 source unique가 막는다 |

**4. 볼트 규칙 `domain-modeling-kotlin.md` §5를 새 규약으로 고친다(사용자 결정, 2026-10-27).**
- 이 PR을 시작할 때 볼트 규칙은 "`@Version`이 없는 엔티티는 `Persistable`로 새 행임을 알린 뒤 `save`"였고, 금지 목록에도 "`Persistable` 없이 `save`"가 있었다. 결정 2와 부딪혀 사용자에게 물었고, 사용자는 볼트 규칙을 고쳐 브리프대로 가기로 했다.
- 고친 내용: 새 행도 `save`이고 `merge`의 SELECT를 받아들인다. `Persistable`·`persist` fragment는 쓰지 않는다. 도메인 값이 PK인 엔티티는 `@Version`을 선언한다. 저장한 엔티티를 다시 읽으려면 `save`의 반환값을 쓴다.
- 규칙 정본은 볼트라 저장소에는 커밋하지 않는다. 고친 뒤 `make bootstrap`을 다시 실행했다.

**5. 이름은 그대로 두고, 모든 Spring Data repository는 `JpaRepository`를 상속한다(사용자 결정, 2026-10-27).**
- `XxxPersistenceAdapter`, `XxxJpaRepository` 이름은 그대로다.
- 범위 조정(2026-10-27, 사용자 지시): 처음 브리프는 "`Repository`를 상속한 좁힌 인터페이스는 필요한 메서드만 더해 좁힌 채로 둔다"였다. 개발 중 사용자가 새로 만든 repository와 기존 좁힌 인터페이스(`FulfillmentJpaRepository`, `PaymentAttemptJpaRepository`, `ReconciliationCaseJpaRepository`, `SyncOperationJpaRepository`, `WatermarkJpaRepository`, `DeadLetterDismissalJpaRepository`, `DeadLetterRedriveJpaRepository`)를 모두 `JpaRepository`로 바꾸라고 했다. 어댑터의 PK 조회는 `findByIdOrNull`이다(참고 구현의 모양).
- repository가 없던 `owner`·`store`·`terminal`에는 `OwnerJpaRepository`·`StoreJpaRepository`·`TerminalJpaRepository`·`TerminalCredentialJpaRepository`를 만든다.
- 대가: 좁혀 둔 이유는 "`deleteAllInBatch` 같은 대량 삭제가 공개 빈에 딸려 오고, `BaseEntity`의 soft delete와 만나 한 번에 모든 행을 조회에서 지운다"였다. 결제 원장, 멱등 키(sync 접수 기록, DLT 기록), watermark(지워지면 단말 전부가 순번 1부터 다시 시작)가 여기에 든다. 이제 그 메서드가 열려 있고, 막는 것은 리뷰뿐이다. 이를 막는 구조 테스트는 지금 없다.

**6. 머지된 `MenuPersistenceAdapter`(#121)도 같은 규약으로 옮긴다(사용자 브리프).**
- `MenuEntity`는 `Long?` `@Version`이고 `storeId`(도메인 값)가 PK다. 그래서 `MenuJpaRepository.save`가 `persist`로 가서 생성 경합의 PK 위반이 그대로 방어선이다(결정 3과 같은 이유). `MenuEntity`에는 변경이 없다.
- `replace`의 `entityManager.lock(entity, OPTIMISTIC_FORCE_INCREMENT)`는 Spring Data에 대응이 없다. 이미 읽은 엔티티에 `@Lock` 파생 쿼리로 걸면 version이 오르지 않는다(실측, 메뉴 테스트 3개 실패). 그래서 문서와 version을 엔티티를 올리지 않는 투영(`MenuSnapshot`)으로 먼저 읽어 내용이 같은지 가리고, 같으면 `@Lock(OPTIMISTIC_FORCE_INCREMENT)` 파생 쿼리로, 다르면 `findByIdOrNull`로 엔티티를 한 번만 읽는다.
- 대가: 수정 한 번이 SELECT를 하나 더 낸다(투영 + 엔티티). 두 읽기 사이에 다른 트랜잭션이 커밋하면 읽은 엔티티의 version이 달라 `replace`가 null이다.

**7. 트랜잭션·반환값·제약 위반 시점을 확인한다.**
- 트랜잭션: Spring Data의 `save`는 `@Transactional`(`REQUIRED`)이라 호출자 트랜잭션에 합류하고, 호출자가 없으면 자기 트랜잭션을 연다. 어댑터 KDoc의 계약(호출자 트랜잭션에 합류)은 그대로 성립한다. 다만 `@Transactional`이 없던 어댑터는 지금까지 트랜잭션 없이 부르면 `persist`가 `TransactionRequiredException`으로 실패했는데, 이제는 조용히 따로 커밋된다. 그래서 그 저장 메서드의 호출자가 모두 트랜잭션 안인지 확인한다(검증 절).
- 실제 호출자는 모두 트랜잭션 안이다(spring-backend-specialist 확인). `PaymentAttemptPersistenceAdapter.save`는 `PaymentManagement`의 `Lock.executeWithLock` 안과 `retryAuthorization`의 `Tx.masterTx` 안에서, `update`는 `updateAndPublish`를 거쳐 `Tx.masterTx` 안에서 불린다. `FulfillmentPersistenceAdapter.save`는 `FulfillmentCommandFacade`의 `Tx.masterTx` 안에서 불린다.
- 읽기 전용 트랜잭션: `findById`는 `SimpleJpaRepository`의 클래스 수준 `@Transactional(readOnly = true)`다. 쓰기 메서드 안(바깥 읽기쓰기 트랜잭션)에서 부르면 그 트랜잭션에 참여하기만 하고 flush 모드를 바꾸지 않는다. `FlushMode.MANUAL`과 `setDefaultReadOnly(true)`는 `HibernateJpaDialect.beginTransaction`이 새 트랜잭션을 열 때만 건다(spring-orm 7.0.9 sources 150~177줄).
- 좁힌 인터페이스의 `fun findById(id: UUID): XxxEntity?`는 `SimpleJpaRepository.findById`로 라우팅되고, 돌아온 `Optional`은 `QueryExecutionResultHandler`가 null로 푼다(spring-data-commons 4.1.1 sources 142·236줄). 저장소에 이 선언의 선례가 없어 첫 사용처의 통합 테스트로 확인한다. `PaymentManagement.authorize`가 기대는 "짧은 트랜잭션이 끝나며 EntityManager를 놓는다"([checkout-lock-static-calls](2026-09-28-checkout-lock-static-calls.md))는 `PaymentAttemptRepository.findById`(JDSL 조회, 범위 밖)를 `Tx.masterTx`로 감싼 것이라 바뀌지 않는다.
- 반환값: `merge`는 넘긴 객체가 아니라 새 관리 인스턴스를 돌려주고, 넘긴 객체에는 `createdAt`이 채워지지 않는다. `DeadLetterRedriver`·`DeadLetterDismisser`는 저장한 엔티티로 응답(`createdAt`)을 만들므로, `save`가 돌려준 인스턴스를 쓴다. 다른 어댑터는 저장 뒤 도메인 객체를 돌려주고 넘긴 엔티티를 다시 쓰지 않는다.
- 제약 위반 시점: 할당식 ID라 `merge`도 INSERT를 flush까지 미룬다. PK로 SELECT하는 것은 auto-flush를 일으키지 않는다. 그래서 "제약 위반은 커밋에서 `DataIntegrityViolationException`으로 드러난다"([payment-attempt-from-order-event](2026-09-22-payment-attempt-from-order-event.md), [도메인 모델](../wiki/domain-model.md) §4.2)는 그대로다.

### 업무 기능 항목
업무 기능이 아니라 저장 방식의 리팩터링이다. 소유 모듈, Command/Query/Event, HTTP 계약, 오류 code는 바뀌지 않는다. 정합성 불변식은 결정 3이 지킨다(watermark의 PK 경합 방어 유지). 관측성 변화는 없다. 새 행마다 SELECT가 하나 늘어난다(결정 2).

## 제외
- `platform/JdslQueryExecutor`와 조회 쿼리. 조회는 Kotlin JDSL 규약 그대로다.
- `Persistable`, `persist` fragment(결정 2).
- `CoreRepository` 이름, 참고 구현의 `Tx` 감싸기와 `deletedAt` 조건(결정 1).
- 새 행의 추가 SELECT를 줄이는 일. 줄여야 하면 그때 `@Version`이나 다른 방법을 엔티티별로 다시 고른다.
- 지난 PR spec(#36의 base-entity-explicit-version 등) 수정.

## 한계
- 새 행마다 SELECT가 하나 더 나간다. line 500개 주문이면 500번이다(결정 2). 지금 측정한 용량 기준([용량 기준](../wiki/capacity.md))은 이 SELECT를 포함하지 않는다.
- `@Transactional`이 없던 저장 메서드를 트랜잭션 없이 부르면, 이제는 실패하지 않고 따로 커밋된다(결정 7). 지금 호출자는 모두 트랜잭션 안이지만, 이를 막는 구조 테스트는 없다.
- `t_sync_watermark.version`은 새 행 판정용이고 낙관적 잠금이 지키는 경로는 없다(결정 3).
- 모든 repository가 `JpaRepository`라 `deleteAll`·`deleteAllInBatch` 같은 대량 삭제를 부를 수 있다. soft delete와 만나면 결제 시도·멱등 키·watermark가 통째로 조회에서 사라진다(결정 5). 막는 구조 테스트가 없다.

## 관련 문서
없음

## 변경 경로
- src/main/kotlin/com/resilientpos/fulfillment/internal/adapter/persistence/command/**
- src/main/kotlin/com/resilientpos/order/internal/adapter/persistence/command/**
- src/main/kotlin/com/resilientpos/owner/internal/adapter/persistence/command/**
- src/main/kotlin/com/resilientpos/payment/internal/adapter/persistence/command/**
- src/main/kotlin/com/resilientpos/reconciliation/internal/adapter/persistence/command/**
- src/main/kotlin/com/resilientpos/store/internal/adapter/persistence/command/**
- src/main/kotlin/com/resilientpos/sync/internal/adapter/persistence/command/**
- src/main/kotlin/com/resilientpos/terminal/internal/adapter/persistence/command/**
- src/main/kotlin/com/resilientpos/catalog/internal/adapter/persistence/command/**
- src/main/kotlin/com/resilientpos/platform/deadletter/**
- src/main/resources/db/migration/V025__sync_watermark_version.sql
- src/test/kotlin/com/resilientpos/SchemaMigrationTest.kt
- src/test/kotlin/com/resilientpos/order/internal/application/**
- src/test/kotlin/com/resilientpos/payment/internal/adapter/persistence/command/**
- src/test/kotlin/com/resilientpos/sync/internal/application/**
- src/test/kotlin/com/resilientpos/platform/deadletter/**
- docs/**

## 인수 조건
1. `git grep -n EntityManager src/main`에서 `EntityManager`를 주입받는 클래스는 `JdslQueryExecutor` 하나다.
2. 새 행 문장 수 기대값이 결정 2대로 바뀌고 통과한다.
   - `OrderInsertStatementCountIntegrationTest`: 새 주문은 `1 + 1 + 2N`, 기존 주문에 line을 더하면 5, checkout을 더하면 5.
   - `PaymentAttemptInsertStatementCountIntegrationTest`: 2.
   - `SyncStatementCountIntegrationTest`: 멱등 checkout 10, 재전송 4.
3. 이미 전진한 watermark 행이 있을 때 새 `WatermarkEntity`를 `save`하면 덮어쓰지 않고 `DataIntegrityViolationException`(PK 위반)이다. `WatermarkFirstInsertRaceIntegrationTest`가 고정하고, `@Version`을 빼면 실패한다(실측). 동시 요청 두 개로 창을 여는 테스트는 없다. 위 테스트는 진 쪽이 `findById`에서 null을 본 뒤의 `save` 호출을 만든다.
4. `DeadLetterRedriver`·`DeadLetterDismisser`의 응답 시각이 `save`가 돌려준 엔티티의 `createdAt`이다.
5. `OrderCommandFacadeIntegrationTest`의 "트랜잭션 밖에서 부른 save가 line INSERT에서 실패하면 주문 행도 남지 않는다"는 살아 있는 다른 주문의 line ID를 재사용하지 않고, 지운(soft delete) line의 ID를 재사용해 PK 위반을 낸다. 살아 있는 line의 ID면 `merge`가 그 line을 UPDATE로 가져가 실패하지 않는다. 운영 경로의 line ID는 모두 `ids.next()`라 이 일이 생기지 않는다.
6. 바꾸기 전과 뒤에 `./gradlew test` 전체가 통과한다.

## 스키마 변경 검토
`V025__sync_watermark_version.sql`: `t_sync_watermark`에 `version bigint not null default 0`을 더한다.
1. 도출: 다른 컬럼에서 도출할 수 없다. 값 자체는 쓰이지 않고 "새 행인가"를 Spring Data가 판정하는 표지다(결정 3).
2. 읽는 코드: Spring Data `isNew`(version null이면 새 행)와 Hibernate의 UPDATE `where version = ?`가 읽는다.
3. 나중에 채우기: 상수 기본값 0으로 기존 행이 채워진다. 기존 행은 0이라 기존 행으로 판정된다.
4. 마지막 방어선: 이 컬럼은 방어선이 아니다. "단말당 watermark 하나"의 방어선은 PK `terminal_id`이고, 이 컬럼은 그 방어선이 `merge`로 우회되지 않게 `persist`를 유지한다. soft delete된 행에도 PK가 걸리는 것은 그대로다.
5. 정본: 사본이 아니다.
6. check-then-act: `ensureWatermark`(없으면 넣기)는 PK가, 순번 전진은 행 잠금(`findWatermarkForUpdate`)이 막는다. version 충돌은 행 잠금 아래라 나지 않는다.
7. 락: non-volatile 상수 기본값의 `ADD COLUMN`이라 메타데이터 변경이다. `ACCESS EXCLUSIVE`를 짧게 잡는다. 긴 트랜잭션 뒤에서 sync를 세우지 않게 `set local lock_timeout = '5s'`를 둔다. 행 수는 단말 수다.
8. 이전 버전: 이전 버전은 이 컬럼을 매핑하지 않아 `validate`가 통과하고, INSERT는 기본값 0을 받는다. 이전 버전의 UPDATE는 version을 올리지 않지만, 새 버전의 UPDATE는 행 잠금 아래에서 읽은 version으로 쓰므로 충돌이 없다.
9. 이벤트·캐시: 실리지 않는다.
- 대조 기록: 읽기 전용 리뷰 에이전트(code-reviewer, 구현자와 다른 에이전트), 2026-10-27, 질문 1~9와 SQL·`WatermarkEntity`·`SyncPersistenceAdapter`·`SyncCommandFacade` 대조에서 불일치 없음. 리뷰어가 확인 불가로 남긴 것 셋은 구현자가 닫았다. version이 null인 새 엔티티의 INSERT가 not null 위반 없이 들어가는 것은 실제 Postgres 통합 테스트 통과로, V014의 `lock_timeout` 동일성은 V014 열람으로, `saveWatermark`의 호출자가 `ensureWatermark`와 `judge`뿐인 것은 grep으로 확인했다. 질문 5(정본 여부)는 리뷰어가 읽은 파일에 근거가 없어 판정하지 않았고, 구현자의 답(사본이 아니다)만 남는다.

## 검증
- `python3 scripts/check_pr.py --base origin/main --worktree`(개발 중), `--worktree` 없이 한 번 더(제출 전).
- `./gradlew test` 전체를 바꾸기 전과 뒤에 실행한다.
- 트랜잭션 호출자 확인: `@Transactional`이 없던 저장 메서드(`FulfillmentPersistenceAdapter.save`, `PaymentAttemptPersistenceAdapter.save`·`update`)의 호출자가 모두 `Tx` 블록이나 `@Transactional` 안인지 코드로 확인하고 결과에 적는다.
- `resilientpos-multi-review`(spring-backend-specialist 포함, 정합성 관점).

## Wiki 영향
- [도메인 모델](../wiki/domain-model.md) §4.2: "`@Version` 없는 엔티티의 새 행은 `save`에 넘기지 않고 `EntityManager.persist`" → "새 행도 `save`이고 `merge`의 SELECT를 받아들인다. 도메인 값이 PK인 엔티티는 `@Version`(`Long?`)으로 `persist`를 유지한다".
- [도메인 모델](../wiki/domain-model.md) §4.3: "쓰기 메서드 안의 PK 조회는 `EntityManager.find`" → `findById`. 좁힌 repository 목록에 owner·store·terminal을 더한다.
- [엔티티 지도](../wiki/entity-map.md): watermark의 `@Version`과 저장 규약을 적은 곳.
- 요구사항 변화는 없다. 저장 규약과 SELECT 수만 바뀐다.

## 분할 계획
- 이 PR은 "저장 경로를 Spring Data로 옮긴다"는 개발 컨텍스트 하나다.
- 열린 PR과 겹치는 곳:
  - #122 `feature/order-query`: `OrderEntity.kt`, `docs/wiki/domain-model.md`, `docs/wiki/entity-map.md`, 마이그레이션 V022~V024.
  - #121 `feature/menu-catalog`: 머지됐다(ed46803b). 새 `MenuPersistenceAdapter`가 `EntityManager`를 쓰던 것을 이 PR이 `MenuJpaRepository`로 바꿨다(결정 6).
  - 확인 결과(2026-10-27, rebase 시점, origin/main ed46803b): #121이 머지됐고 열린 PR은 #122뿐이다(CI 진행 중). 마이그레이션은 #121이 V021, #122가 V022~V024를 쓰므로 이 PR은 V025로 옮겼다. #122와 겹치는 경로는 `docs/wiki/domain-model.md`, `docs/wiki/entity-map.md`, `src/test/kotlin/com/resilientpos/SchemaMigrationTest.kt`다. #122가 먼저 머지되면 이 PR이, 이 PR이 먼저면 #122가 `SchemaMigrationTest`의 적용 목록과 wiki를 맞춘다.
  - 먼저 머지된 쪽에 맞춰 rebase한다. 마이그레이션 번호는 나중에 머지되는 쪽이 바꾼다. #121이 먼저 머지되면 이 PR에서 `MenuPersistenceAdapter`도 새 규약으로 바꾸고, 이 PR이 먼저 머지되면 #121이 맞춘다.

## TDD 검증 기록
업무 동작 변경이 아니라 Repository 저장 방식의 리팩터링이다(Repository 전용 작업이라 TDD 스킬 대상에서 빠진다). 동작이 바뀌는 것(SELECT 수)은 테스트 기대값을 먼저 바꿔 RED를 본 뒤 구현한다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 새 행 문장 수(주문·결제 시도·sync) | `./gradlew test --tests '*StatementCount*'`: 기대값을 `merge` 기준으로 바꾸자 5개 실패 | 어댑터를 `save`로 바꾼 뒤 통과 | `./gradlew test --rerun-tasks` 935개 통과 |
| watermark 첫 행 덮어쓰기 방지 | `@Version`을 뺀 상태에서 `WatermarkFirstInsertRaceIntegrationTest` 실패(덮어씀) | `@Version`을 둔 상태에서 통과 | 위와 같음 |

## 결과
- `EntityManager`를 주입받는 클래스는 `JdslQueryExecutor` 하나다. 어댑터 8개와 `DeadLetterDismisser`·`DeadLetterRedriver`가 Spring Data repository의 `save`·`findByIdOrNull`을 쓴다. 모든 repository가 `JpaRepository`다.
- 문장 수 변화(실측): 새 주문 `2 + 2N`, 기존 주문에 line 하나 더하면 +1, checkout 하나 더하면 +1, 새 결제 시도 1 → 2, sync 멱등 checkout 9 → 10.
- `DeadLetterRedriver`·`DeadLetterDismisser`는 `save`가 돌려준 인스턴스로 응답한다. 단위 테스트의 repository mock이 복사본을 돌려줘서 넘긴 객체를 쓰면 실패한다.
- 호출자 트랜잭션: `@Transactional`이 없던 저장 메서드의 호출자는 모두 `Tx`·`Lock` 블록 안이다(spring-backend-specialist가 코드로 확인).
- 확인하지 못한 것: 동시 요청 두 개로 watermark의 `merge` 경합을 재현하지 못했다(결정 3의 시나리오는 코드 읽기와 `@Version` 유무 비교 테스트로 확인). `merge`의 UPDATE에 `created_at`이 실리는지는 보지 않았다.
