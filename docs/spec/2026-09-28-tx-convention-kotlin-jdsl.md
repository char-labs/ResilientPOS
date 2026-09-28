# PR spec: 트랜잭션 선언을 Tx로 모으고 조회를 Kotlin JDSL로 옮긴다

## 목적
조회 API와 정적 페이지를 만들기 전에 읽기 트랜잭션 규칙과 조회 구현 방식을 정해 둔다. 지금은 쓰기 경계가 메서드 `@Transactional`로만 선언되고, 한 메서드 안에서 트랜잭션을 나누려면 보조 빈(`PaymentAttemptTransitions`)을 따로 둬야 한다. 읽기 전용 트랜잭션 규칙은 없고, 조회는 Spring Data 파생 쿼리와 기본 `findById`가 숨은 읽기 전용 트랜잭션을 여는 동작에 기대고 있다.

기대 결과
- 쓰기 경계는 `Tx.masterTx { }`, 읽기 전용은 `Tx.readOnlyTx { }` 또는 `@ReadOnlyTransactional`(`readOnly = true`, `SUPPORTS`)로 선언한다. 비즈니스 코드는 `@Transactional(readOnly = true)`를 직접 쓰지 않는다.
- 모든 조회가 Kotlin JDSL로 만든 JPQL이다. 비관적 잠금 조회의 의미(`PESSIMISTIC_WRITE`, 대기 3초)는 그대로다.
- 업무 동작, 이벤트, 오류 code, 스키마는 바뀌지 않는다.

## 범위
소유 모듈은 `platform`(트랜잭션·조회 도구), `order`·`payment`·`fulfillment`(Facade와 persistence adapter)다.

사용자 결정
1. 읽기 전용 트랜잭션은 `@Transactional(readOnly = true, propagation = Propagation.SUPPORTS)`로 통일하고 메타 애너테이션 `@ReadOnlyTransactional`로 제공한다.
2. `Tx` 전역 object를 둔다. `Tx.masterTx { }`·`Tx.readOnlyTx { }`는 `private lateinit var` 러너에 위임한다. 러너는 `@Component class TxRunner`의 `@Transactional fun runTx`와 `@ReadOnlyTransactional fun runReadOnly`다. `@Configuration`의 `InitializingBean` 빈이 `Tx.initialize(txRunner)`를 부른다. 한 JVM에 ApplicationContext가 여럿 뜨면 마지막으로 초기화된 컨텍스트의 러너가 전역을 덮는다는 위험을 알고 받아들인다. 선제 방어는 넣지 않고 전체 테스트 결과를 보고 판단한다.
3. 자주 읽는 aggregate의 `findById`는 `@ReadOnlyTransactional`로 선언한다(`Order`, `PaymentAttempt`). 조회는 QueryDSL이 아니라 Kotlin JDSL이다. 기존 파생 쿼리와 `findById`·`findByIdOrNull`도 옮긴다.
4. `NOT_SUPPORTED`는 "외부 호출을 트랜잭션 밖에서 하고 결과는 따로 커밋한다"는 목적일 때만 쓴다. `authorize`·`retryAuthorization`은 그대로다. 읽기 전용 목적이면 `SUPPORTS`다.
5. 쓰기 경로의 트랜잭션은 빼지 않는다. Modulith 발행(트랜잭션이 없으면 `@TransactionalEventListener`가 건너뛴다), checkout 잠금의 `registerSynchronization`, 여러 문장 저장이 트랜잭션을 필요로 한다. "단건 수정은 트랜잭션 없이" 규칙은 두지 않는다.

트랜잭션 컨벤션
- `platform`에 `ReadOnlyTransactional`, `Tx`, `TxRunner`를 두고 `PlatformConfiguration`에 `Tx.initialize`를 부르는 `InitializingBean` 빈을 둔다.
- `OrderCommandFacade`(3곳), `PaymentManagement.createAttempt`, `FulfillmentCommandFacade.createFulfillment`의 `@Transactional` 본문을 `Tx.masterTx { }`로 옮긴다. `create`의 line 개수 검사는 트랜잭션을 열기 전에 둔다.
- `PaymentAttemptTransitions`를 지우고 두 메서드를 `PaymentManagement` 안의 `Tx.masterTx` 블록으로 흡수한다. `Tx`는 프록시를 거친 `TxRunner`를 부르므로 같은 클래스 안이어도 트랜잭션이 열린다. `authorize`·`retryAuthorization`은 `NOT_SUPPORTED`를 유지한다.
- `OrderPersistenceAdapter.save`의 안전망 `@Transactional`은 어댑터라 그대로 둔다.
- 모듈 publisher의 타입별 `publish`를 메서드 참조로 부르는 형태(`transition.events.forEach(eventPublisher::publish)`)는 그대로 둔다. IntelliJ가 발행 지점을 잇는 근거다.
- `TransactionDeclarationTest`에 규칙을 더한다. 메서드에 `@Transactional(readOnly = true)`를 직접 붙이지 않는다. 읽기 전용은 `@ReadOnlyTransactional`만 쓴다.

Kotlin JDSL
- `com.linecorp.kotlin-jdsl`의 `jpql-dsl`·`jpql-render` 3.9.0을 더한다(maven-metadata의 release가 3.9.0).
- `platform`에 `JdslQueryExecutor`를 둔다. JDSL로 만든 JPQL을 `JpqlRenderer`로 렌더링해 `EntityManager.createQuery`에 올리고 `findAll`·`findFirst`·`findFirstForUpdate`를 준다. `findFirstForUpdate`는 `LockModeType.PESSIMISTIC_WRITE`와 힌트 `jakarta.persistence.lock.timeout = 3000`을 건다. 실행기는 트랜잭션을 선언하지 않고 호출자의 것을 따른다.
- 옮길 조회: `OrderJpaRepository.findByTerminalIdAndClientOrderId`·`findWithLockById`, `OrderLineJpaRepository.findAllByOrderIdOrderByLineNo`, `OrderCheckoutJpaRepository.findAllByOrderIdOrderByCheckoutNo`, `PaymentAttemptJpaRepository`의 `findById`·`findAllByOrderIdOrderById`·`findFirstByOrderIdAndCheckoutNoAndKindOrderById`·`findFirstByOrderIdAndCheckoutNoAndKindOrderByIdDesc`·`findWithLockById`, `FulfillmentJpaRepository.findByOrderId`, `OrderPersistenceAdapter.findById`의 `findByIdOrNull`.
- 쓰기 메서드 안에서 관리 엔티티를 찾는 PK 조회(`OrderPersistenceAdapter.save`의 `findByIdOrNull`, `PaymentAttemptPersistenceAdapter.update`의 `findById`)는 `EntityManager.find`로 바꾼다. 둘 다 Spring Data 내부에서 이미 `EntityManager.find`였다. JPQL로 바꾸면 같은 트랜잭션에서 잠금 조회로 읽은 엔티티도 SELECT를 다시 보내므로 영속성 컨텍스트를 먼저 보는 `find`를 유지한다.
- 조회 메서드가 사라져 쓰임이 없어지는 `OrderCheckoutJpaRepository`·`PaymentAttemptJpaRepository`·`FulfillmentJpaRepository`는 지운다. 대량 삭제 메서드가 공개 빈에 딸려 오지 않게 하려던 이유는 repository 자체가 없어져 그대로 지켜진다. 쓰기에 쓰는 `OrderJpaRepository`(`save`)와 `OrderLineJpaRepository`(`deleteAll`)는 남긴다.
- 호환 근거(2026-09-28 확인)
  - [GitHub README](https://github.com/line/kotlin-jdsl#requirements): 사용자 요구 사항은 Java 8 이상, Kotlin 1.7 이상이다. 이 저장소는 Java 25, Kotlin 2.4.10이다. `jpql-render` 3.9.0 pom은 Kotlin stdlib 1.9.22를 runtime으로 선언한다. Kotlin은 이전 버전으로 컴파일된 라이브러리를 읽으므로 2.4.10에서 쓸 수 있다.
  - [Spring supports 문서](https://kotlin-jdsl.gitbook.io/docs/jpql-with-kotlin-jdsl/spring-supports): Spring Boot 4용 모듈은 `spring-data-jpa-boot4-support`이고(pom이 `spring-boot-dependencies` 4.0.0을 import한다), Spring Data JPA 4에서 `EntityManager` 빈이 자동 등록되지 않으니 `SharedEntityManagerCreator`로 직접 등록하라고 안내한다. 이 PR은 지원 모듈을 쓰지 않는다(검토한 대안). 실행기는 이미 adapter들이 주입받아 쓰는 `EntityManager` 빈을 그대로 받는다.
  - DSL·렌더러는 JPA 구현에 묶이지 않고 JPQL 문자열과 파라미터를 만든다. 실행은 Hibernate 7(Boot 4.1.1 관리)의 `EntityManager.createQuery`다. 렌더링한 파라미터를 그대로 바인딩한다. 지원 모듈의 `JpqlEntityManagerUtils.setParams`는 쿼리에 없는 이름을 건너뛰지만, 여기서는 그런 이름이 오면 `setParameter`가 실패해 드러나게 둔다.
- `PaymentAttempt.findById`를 `@ReadOnlyTransactional`로 옮긴 뒤 `ProbingGateway`의 자원 미결속 검사가 빨강이 되면 `authorize`의 첫 조회를 짧은 트랜잭션(`Tx.masterTx`)으로 감싼다. 검사는 약하게 바꾸지 않는다.

문서
- `docs/wiki/domain-model.md` §4.3의 트랜잭션 규칙 문단을 최종 규칙으로 고치고, §4.2의 Spring Data repository 서술을 조회 방식에 맞춘다.
- `docs/wiki/current-state.md`에 Kotlin JDSL 의존성과 바뀐 `authorize` 트랜잭션 서술을 반영한다.
- 볼트 rules `domain-modeling-kotlin.md`의 트랜잭션 절을 같은 규칙으로 고치고 `make bootstrap`을 다시 돌린다(저장소 커밋 대상 아님).

## 제외
- 조회 API·정적 페이지 자체. 이 PR은 규칙과 도구만 둔다.
- `Tx` 전역 러너 덮어쓰기에 대한 운영 코드 쪽 방어. 운영은 컨텍스트가 하나다. 테스트 쪽 조치는 결과 절에 있다.
- 조회 결과를 DTO로 바로 받는 projection, 페이지네이션. 쓰는 조회가 생길 때 더한다.
- `OrderJpaRepository`·`OrderLineJpaRepository`의 쓰기(`save`, `deleteAll`)를 `EntityManager`로 옮기는 것. 조회가 아니다.

## 검토한 대안
- **Spring Data 지원 모듈 `spring-data-jpa-boot4-support`의 `KotlinJdslJpqlExecutor`를 repository에 붙이기**: 소스(3.9.0)를 보면 구현 `KotlinJdslJpqlExecutorImpl`이 클래스 레벨 `@Transactional(readOnly = true)`(전파 `REQUIRED`)라 읽기 전용은 `SUPPORTS`라는 결정과 어긋난다. 인터페이스가 `update`·`delete` 대량 변경 메서드를 함께 열어 결제 원장·fulfillment repository가 `JpaRepository`를 상속하지 않던 이유를 되돌린다. 잠금은 호출된 repository 메서드의 `CrudMethodMetadata`로만 걸리는데 `findAll`을 부르면 그 메서드에는 `@Lock`이 없다. 그래서 DSL과 렌더러만 쓰고 실행은 `EntityManager`에 직접 올린다.
- **잠금 조회만 `EntityManager`, 나머지는 executor**: 조회 경로가 둘이 되고 위의 트랜잭션·대량 변경 문제가 그대로 남는다.
- **`Tx`에 진짜 읽기 전용 트랜잭션(`REQUIRED`, `readOnly = true`)을 하나 더 두기**: 쓰는 곳이 `authorize`의 첫 조회 하나라 `masterTx`로 충분하다. 더 필요해지면 그때 더한다.

## 관련 문서
없음

## 변경 경로
- build.gradle.kts
- src/main/kotlin/com/resilientpos/platform/**
- src/main/kotlin/com/resilientpos/order/**
- src/main/kotlin/com/resilientpos/payment/**
- src/main/kotlin/com/resilientpos/fulfillment/**
- src/test/kotlin/com/resilientpos/**
- docs/spec/2026-09-28-tx-convention-kotlin-jdsl.md
- src/test/resources/META-INF/spring.factories
- docs/wiki/domain-model.md
- README.md

## 인수 조건
1. main 코드에 `@Transactional(readOnly = true)`를 직접 붙인 메서드가 없고 `TransactionDeclarationTest`가 이를 막는다. 위반을 넣으면 실패한다.
2. main 코드의 `@Transactional`은 `TxRunner.runTx`, `OrderPersistenceAdapter.save`, `authorize`·`retryAuthorization`의 `NOT_SUPPORTED`, `@ReadOnlyTransactional` 정의뿐이다.
3. `PaymentAttemptTransitions`가 없고, 기존 통합 테스트가 그대로 초록이다: 바깥 트랜잭션을 롤백해도 적용이 커밋됨, 갱신 뒤 발행 순서, 잠금 재조회, provider 호출 중 DB 자원 미결속.
4. main 코드에 Spring Data 파생 조회 메서드와 `findByIdOrNull`이 없다. 조회는 `JdslQueryExecutor`를 거친다.
5. 잠금 조회는 PostgreSQL에서 `for no key update`이고 대기 3초 초과 시 `PessimisticLockingFailureException` 계열로 실패한다. 기존 잠금 테스트가 통과한다.
6. 컨텍스트 없이 `Tx`를 부르면 어떻게 실패하는지 확인해 결과 절에 적는다.

## 검증
- `./gradlew test` 전체. Docker(OrbStack)가 필요하다.
- 인수 조건 2·4는 `grep`으로 확인한다.
- `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전 `--worktree` 없이 한 번 더.
- 실패 시나리오: 다중 컨텍스트에서 `Tx`가 다른 컨텍스트의 러너를 가리키면 `EntityManager.persist`가 트랜잭션 없음으로 실패하거나 발행이 건너뛰어진다. 전체 테스트에서 나타나면 원인과 증상을 결과 절에 적고 사용자에게 보고한다.

## Wiki 영향
- [도메인 모델](../wiki/domain-model.md) §4.3 트랜잭션 규칙: `Tx.masterTx`·`readOnlyTx`와 `@ReadOnlyTransactional`, `SUPPORTS`의 한계(단독 호출이면 트랜잭션이 없고, 적용되는 읽기 전용 효과와 적용되지 않는 효과는 결과 절의 실측), 한 시점이 필요한 조회의 방법, `NOT_SUPPORTED` 허용 조건, 쓰기 경로에 트랜잭션이 필요한 이유, `findById` 선언 기준, 조회는 Kotlin JDSL. §4.2는 자식 엔티티의 조회를 adapter가 JDSL로 한다는 것으로 고친다.
- [현재 상태](../wiki/current-state.md): 의존성 표에 Kotlin JDSL, "현재 빌드 의존성에는 없다" 문장, `authorize` 트랜잭션 서술. 이 문서는 Obsidian 볼트로 가는 링크(gitignore 대상)라 볼트에서 고치고 저장소 커밋에는 들어가지 않는다.
- 요구사항 변화는 없다. 업무 동작이 같다.

## 분할 계획
한 개발 컨텍스트(트랜잭션 선언과 조회 구현 규칙)라 나누지 않는다. 작업 시작 시점(origin/main 424caff)에 열린 PR은 없어 변경 경로 겹침이 없다.

## TDD 검증 기록
동작은 바뀌지 않는 리팩터링이다. 새 규칙(ArchUnit)과 자원 미결속 검사처럼 깨질 수 있는 지점만 RED를 먼저 본다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 메서드에 읽기 전용 트랜잭션을 직접 선언하지 않는다(`TransactionDeclarationTest`) | `./gradlew test --tests 'com.resilientpos.TransactionDeclarationTest'`, `createFulfillment`에 임시로 `@Transactional(readOnly = true)`를 붙여 위반 1건으로 실패 | 같은 명령, 임시 위반을 되돌려 통과 | `./gradlew test`, 269개 통과 |
| 컨텍스트 없이 Facade를 부르면 Tx 미초기화로 실패한다(`PaymentManagementTest`, `FulfillmentCommandFacadeTest`) | 세 단위 테스트를 걸러 실행, Facade 본문을 `Tx.masterTx`로 옮긴 뒤 12개 중 11개와 1개 중 1개가 `kotlin.UninitializedPropertyAccessException: lateinit property runner has not been initialized`로 실패. line 상한 테스트는 트랜잭션 전에 거절해 통과 | 같은 명령, 테스트가 프록시 없는 `TxRunner()`를 넣어 통과 | `./gradlew test`, 269개 중 3개 실패(아래 결과 절의 전역 러너 덮어쓰기). 테스트 실행 리스너를 더한 뒤 269개 통과 |
| provider를 부르는 동안 DB 자원을 쥐지 않는다, 트랜잭션 안에서 승인을 요청해도 바깥 트랜잭션과 따로 적용한다(`PaymentAuthorizationIntegrationTest`) | `./gradlew test --tests 'com.resilientpos.payment.internal.application.PaymentAuthorizationIntegrationTest'`, `findById`를 `@ReadOnlyTransactional`로 옮기고 `authorize`가 그대로 부르자 두 테스트가 자원 결속 `expected false but was true`로 실패 | 같은 명령, 첫 조회를 `Tx.masterTx`로 감싸 8개 통과 | `./gradlew test`, 269개 통과 |
| 잠금을 3초 넘게 기다린 조회는 Spring의 잠금 실패 예외로 끝난다(`PaymentAttemptPersistenceIntegrationTest`) | `./gradlew test --tests 'com.resilientpos.payment.internal.adapter.persistence.command.PaymentAttemptPersistenceIntegrationTest'`, 실행기가 `@Component`라 `jakarta.persistence.PessimisticLockException`이 번역 없이 올라와 실패 | 같은 명령, 실행기를 `@Repository`로 바꿔 7개 통과 | `./gradlew test`, 전체 통과(아래 결과 절) |
| 잠금 조회·버전 충돌·발행 순서·문장 수 기존 통합 테스트 | 해당 없음(기존 테스트가 그대로 지키는 동작) | `./gradlew test`, 통과 | `./gradlew test`, 269개 통과 |

## 결과
개발 전 spec을 독립 커밋으로 작성했다. 구현 후 아래를 확인했다.

- `platform/Tx.kt`에 `ReadOnlyTransactional`·`Tx`·`TxRunner`를 두고 `PlatformConfiguration.txInitializer`(`InitializingBean`)가 러너를 넣는다. Facade 다섯 곳의 `@Transactional`을 `Tx.masterTx` 블록으로 옮겼고 `create`의 line 개수 검사는 블록 앞에 있다. `PaymentAttemptTransitions`를 지우고 `applyOutcome`·`retry`·`updateAndPublish`를 `PaymentManagement`의 private 메서드로 흡수했다. 발행은 그대로 `transition.events.forEach(eventPublisher::publish)`와 `updateAndPublish(..., eventPublisher::publish)`다.
- main의 `@Transactional`은 `TxRunner.runTx`, `OrderPersistenceAdapter.save`, `authorize`·`retryAuthorization`의 `NOT_SUPPORTED`, `ReadOnlyTransactional` 정의뿐이다(`grep`으로 확인, 인수 조건 2).
- 컨텍스트 없이 `Tx`를 부르면 `kotlin.UninitializedPropertyAccessException: lateinit property runner has not been initialized`로 실패한다(인수 조건 6).
- **전역 러너 덮어쓰기가 실제로 났다.** 증상: 전체 테스트에서 `FulfillmentFlowIntegrationTest`가 `InvalidDataAccessApiUsageException: No active transaction`(주문 잠금 조회)으로, `OrderPaidListenerIntegrationTest` 2개가 "20초 안에 fulfillment가 생긴다" 시간 초과(리스너 쪽 `TransactionRequiredException: No EntityManager with actual transaction available ... 'persist'`)로 실패했다. 원인: 컨텍스트 없는 `FulfillmentCommandFacadeTest`가 프록시 없는 `TxRunner()`를 전역에 넣었고, 바로 뒤의 통합 테스트는 캐시된 컨텍스트를 다시 써서 기동 때의 초기화가 돌지 않았다. 그래서 `Tx.masterTx`가 트랜잭션 없이 블록을 불렀다. 컨텍스트 사이의 덮어쓰기는 나타나지 않았다. `spring.test.context.cache.maxSize=1`이라 컨텍스트가 바뀔 때마다 새로 떠서 다시 초기화된다. 사용자 결정(발생하면 그때 수정)대로 운영 코드는 그대로 두고 테스트 전용 `TxRebindingTestExecutionListener`(`META-INF/spring.factories` 등록)가 Spring 테스트마다 그 컨텍스트의 `TxRunner`로 다시 묶게 했다. 러너가 없는 웹 슬라이스 컨텍스트는 건드리지 않는다. 이후 269개 통과.
- 조회는 모두 `JdslQueryExecutor`를 거친다. main에 Spring Data 파생 조회 메서드와 `findByIdOrNull`이 없다(`grep`, 인수 조건 4). `OrderCheckoutJpaRepository`·`PaymentAttemptJpaRepository`·`FulfillmentJpaRepository`를 지웠고 JPA repository가 없는 이유(대량 삭제 메서드)는 adapter KDoc으로 옮겼다. `OrderJpaRepository`·`OrderLineJpaRepository`는 조회 메서드 없이 `save`·`deleteAll`에만 쓴다. 반복되는 `select(entity(X)).from(entity(X))`는 `platform`의 확장 `Jpql.selectFrom`으로 줄였다(다른 저장소의 JDSL support 클래스를 참고했다).
- 잠금: 요청 checkout 통합 테스트를 Hibernate SQL 로그로 한 번 돌려 `select ... from t_order ... where oe1_0.id=? and oe1_0.deleted_at is null fetch first ? rows only for no key update`(결제 시도도 같은 모양)와 조회 앞뒤의 `lock_timeout = 3000`·`lock_timeout = 0`(`set_config('lock_timeout', ..., true)`)을 확인했다. "잠금을 3초 넘게 기다린 요청은 잠금 실패로 끝난다" 테스트가 `PessimisticLockingFailureException`으로 통과한다(인수 조건 5).
- `PaymentAttempt.findById`를 `SUPPORTS`로 옮기자 `NOT_SUPPORTED` 구간에서 EntityManager가 provider 호출 동안 묶였다(RED). 검사를 바꾸지 않고 `authorize`의 첫 조회를 `Tx.masterTx`로 감쌌다. `Tx`에 진짜 읽기 전용 트랜잭션을 더하지 않은 이유는 검토한 대안 절에 있다.
- `PaymentAttemptPersistenceIntegrationTest`는 지운 repository 대신 adapter의 `save`를 테스트 트랜잭션에서 부르고 JDSL로 다시 읽는다. unique 위반은 커밋에서 `DataIntegrityViolationException`으로 나온다.
- 볼트 rules `domain-modeling-kotlin.md`(§4 발행, §5 repository·조회, §6 트랜잭션 소유)를 같은 규칙으로 고치고 `make bootstrap`을 다시 돌렸다.
- `SUPPORTS` 단독 호출의 읽기 전용 효과를 임시 테스트로 실측했다(커밋하지 않음). `Tx.readOnlyTx` 단독: 실제 트랜잭션 없음, 동기화 활성, `isCurrentTransactionReadOnly() = true`, Hibernate `FlushMode.MANUAL`, 세션 기본 read-only `false`. `Tx.masterTx` 안의 `readOnlyTx`: 합류해서 읽기 전용 표시 `false`, `FlushMode.AUTO`. 그래서 wiki에는 "readOnly 힌트가 적용되지 않는다" 대신 걸리는 것(읽기 전용 표시, flush 생략)과 걸리지 않는 것(트랜잭션, 세션·커넥션 read-only)을 나눠 적었다.
- 한 시점이 필요한 조회를 "`masterTx`로 감싼다"고 적지 않았다. PostgreSQL 기본 격리(READ COMMITTED, `application.yaml`에 격리 설정 없음)에서는 트랜잭션 안에서도 문장마다 스냅샷을 새로 잡는다. 한 쿼리로 읽거나 루트를 잠그고 읽는다고 적었다.
- multi-review: Claude 80점, Codex 76점, Gemini는 CLI가 없어 미실행. 두 리뷰가 모두 지적한 실행기의 예외 번역(HIGH)과 spec 제외 절을 반영했다. Codex만 지적한 것 중 README의 "Kotlin JDSL은 의존성에 없다"·"JDSL은 DTO projection read" 두 문장을 고쳤고 `findFirstForUpdate`에 `setMaxResults(1)`을 걸었다. Claude만 지적한 것 중 반영: 실행기의 예외 번역(HIGH, 위 TDD 행), 한 시점 조회 문구, readOnly 효과 실측, `TransactionDeclarationTest`가 막는 범위를 wiki에 정확히 적기, 제외 절 정리, `findById` 문구, `findFirst`·`findFirstForUpdate`를 PK·unique 조건에만 쓴다는 KDoc. 반영하지 않음: `Tx.runner`의 `@Volatile`(운영은 기동 중 한 번 넣고 요청·consumer 스레드가 그 뒤에 시작해 가시성이 보장된다. 테스트의 다른 스레드 문제는 가시성이 아니라 어느 러너를 보느냐라 남은 위험으로 둔다), `TxRunner.runTx`의 `rollbackFor = Exception`(checked 예외에서 커밋하는 것은 기존 `@Transactional`과 같은 동작이라 이 PR의 범위 밖이다), `@Transactional` 허용 목록 ArchUnit 규칙(클래스 이름을 규칙에 박게 되어 wiki 문장을 테스트가 막는 두 규칙으로 좁혔다), 지연 초기화 대비, `lock_timeout`을 0으로 되돌리는 Hibernate 동작(기존과 같다), `selectFrom`의 `inline reified`(JDSL `entity`가 reified라 필요하다).
- 남은 위험: 캐시된 컨텍스트의 Kafka consumer 스레드가 컨텍스트 없는 단위 테스트(`Tx.initialize(TxRunner())`) 도중에 Facade를 부르면 트랜잭션 없이 돌 수 있다. 전체 테스트에서 나타나지 않았다. 사용자 결정대로 나타나면 그때 정한다.
- 최소 해법으로 다시 줄였다: 실행기의 파라미터 이름 필터를 빼고(어긋나면 조용히 넘기지 않고 실패한다), 잠금 힌트 이름은 Hibernate의 `SpecHints.HINT_SPEC_LOCK_TIMEOUT`을 쓰고, Kotlin 기본값인 `@Retention(RUNTIME)`을 뺐다.
- 간헐 실패 1건: 정리 뒤 첫 전체 실행에서 `OrderInsertStatementCountIntegrationTest`의 "기존 주문에 line을 바꾸고 더해 저장하면 새 line은 SELECT 없이 INSERT 한 번이다"가 SELECT 4 기대에 6으로 실패했다. 단독 실행과 다음 전체 실행은 통과했다. 이 테스트가 세는 Hibernate 통계는 SessionFactory 전체 값이라, 앞 테스트가 남긴 Kafka consumer의 조회가 측정 구간에 섞이면 늘어난다. 이 PR의 변경과 무관한 측정 방식의 문제로 보고 여기서 고치지 않는다.
- `./gradlew test`: 270개 통과, 실패 0.
