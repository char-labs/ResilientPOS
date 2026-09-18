# PR spec: CreateOrder 영속화와 Facade 구현

## 목적
[order-domain-model](2026-09-16-order-domain-model.md)이 만든 `Order` aggregate와 `CreateOrder` 계약을 실제로 저장하고 결과를 돌려주는 use case로 완성한다. `OrderCommandFacade.create`가 트랜잭션 안에서 주문을 PostgreSQL에 저장하고 `OrderCreated`를 발행하며, 같은 `(terminalId, clientOrderId)` 재시도에는 기존 결과를 돌려준다. 이 PR이 끝나면 HTTP 없이도 주문 생성 경로가 DB까지 닿는다. 앞 PR 브랜치 `feature/order-domain-model` 위의 stacked PR로 시작했고, 그 PR이 머지된 뒤 base가 `main`이 됐다(결과 절).

## 범위
- `order/internal/domain/OrderRepository`: `findById(OrderId): Order?`, `findByClientOrderId(TerminalId, ClientOrderId): Order?`, `save(Order): Order`. 시그니처에 aggregate와 ID 값 타입만 쓴다.
- `order/internal/application/DefaultOrderCommandFacade`: `@Transactional`의 유일한 소유자. 순서는 기존 주문 조회 → 있으면 기존 `CreateOrderResult` 반환 → 없으면 `Order.create` → `repository.save` → `Transition.events`를 `ApplicationEventPublisher`로 발행 → `CreateOrderResult` 변환. Service는 두지 않는다.
- `order/internal/adapter/persistence/command`: `OrderJpaEntity`(`@Version` 포함), `OrderLine`을 `@ElementCollection`·`@OrderColumn`으로 매핑하는 `OrderLineJpaValue`, Spring Data `OrderJpaRepository`, `OrderRepository` 구현 `OrderPersistenceAdapter`. 변환 `from(aggregate)`, `apply(aggregate)`, `toDomain()`은 엔티티의 `companion`과 메서드가 소유한다. 금액은 `NUMERIC(19,4)` 컬럼에서 통화 표준 자릿수로 되돌려 `Money`를 만든다.
- Flyway `V002__order_create_order.sql`: `t_order`(id uuid PK, store_id uuid, terminal_id uuid, client_order_id uuid, currency char(3), status varchar(32), version bigint, unique `(terminal_id, client_order_id)`)와 `t_order_line`(order_id uuid FK → t_order, line_no int, product_code, product_name, unit_price numeric(19,4), quantity int, PK `(order_id, line_no)`). 합계 컬럼은 두지 않는다. 파생 값이다.
- `platform` 패키지 신설과 `@ApplicationModule` 선언. `IdentifierGenerator`(UUID v7)와 `Clock`(UTC) 빈을 등록하는 `PlatformConfiguration`. [개발 가이드 §1](../references/03_Development_Guide.md)이 `platform`을 adapter·configuration 자리로 정했다. `ModuleStructureTest`의 모듈 목록에 `platform`을 추가한다.
- Fixture Monkey 도입: `fixture-monkey-starter-kotlin` 최신 버전(1.2.3)을 `testImplementation`에 추가하고 `src/test/kotlin/com/resilientpos/order/OrderFixtures.kt`에 `CreateOrderCommand` 빌더를 둔다. 판단에 중요한 값(통화, line 단가·수량, ID)은 고정한다.
- `SchemaMigrationTest`의 기대값을 갱신한다. 적용 버전은 `001`, `002`이고 업무 테이블 `t_order`, `t_order_line`이 존재한다. 이 테스트의 의도("애플리케이션이 테이블을 만들지 않는다")는 유지하며, 테이블 목록이 마이그레이션이 만든 것과 정확히 같은지로 바꾼다.
- 소유 모듈: `order`(Facade·adapter·마이그레이션), `platform`(공용 빈). 계약: `OrderCommandFacade.create`. 불변식: `(terminal_id, client_order_id)` DB unique가 중복 생성의 마지막 방어선이다. 복구: 재시도는 기존 결과 반환, 동시 경쟁은 unique 위반 예외로 실패하고 호출자가 재시도하면 기존 결과를 받는다. 관측성: 로그 속성·메트릭은 추가하지 않는다. Controller PR에서 `orderId`·`terminalId`·`storeId` 로그 속성을 붙인다.

## 제외
- REST Controller와 REST Docs. 다음 PR `order-create-order-api`.
- Event Publication Registry(`starter-jpa`, `event_publication` 테이블). 이 PR의 발행은 plain Spring 이벤트다. 리스너가 없어 EPR이 저장할 publication도 없다. 첫 `@ApplicationModuleListener` PR에서 넣는다.
- payload 불일치 판정(같은 `clientOrderId`에 다른 내용). `sync`의 operation hash 비교가 맡는다. 이 Facade는 키가 같으면 기존 결과를 돌려준다.
- 주문 시각(단말의 `occurredAt`) 컬럼. `CreateOrderCommand`에 시각이 없고 앞 PR에서 정하지 않았다. `sync` intake가 envelope의 `occurredAt`을 넘겨야 하므로 sync PR에서 Command와 컬럼을 함께 추가한다.
- `OrderQueryFacade`, 조회 projection, Kotlin JDSL.
- 상태 전이(`apply`가 다루는 변경 경로). `apply(aggregate)`는 규칙대로 두되 이 PR에서 실행되는 경로는 신규 저장 `from`만이다.

## 관련 문서
DESIGN 계층은 없다. 정본은 [도메인 모델 §4.2·§4.3](../wiki/domain-model.md)과 볼트 규칙 `domain-modeling-kotlin.md` §5·§6이다. 원문 근거는 [도메인 청사진 §6.1·§6.2](../references/05_Domain_Development_Blueprint.md), [개발 가이드 §1·§3.1·§3.2](../references/03_Development_Guide.md), [기획 PRD 3.2](../references/01_Product_Planning_PRD.md)(exact retry는 이전 결과 반환). 선행 PR은 [order-domain-model](2026-09-16-order-domain-model.md), [postgres-flyway-baseline](2026-09-15-postgres-flyway-baseline.md).

## 변경 경로
- build.gradle.kts
- src/main/java/com/resilientpos/platform/package-info.java
- src/main/kotlin/com/resilientpos/platform/PlatformConfiguration.kt
- src/main/kotlin/com/resilientpos/platform/BaseEntity.kt
- src/main/kotlin/com/resilientpos/order/internal/domain/OrderRepository.kt
- src/main/kotlin/com/resilientpos/order/internal/domain/Order.kt
- src/main/kotlin/com/resilientpos/order/internal/domain/OrderLine.kt
- src/main/kotlin/com/resilientpos/order/OrderId.kt
- src/main/kotlin/com/resilientpos/order/OrderCommandFacade.kt
- src/main/kotlin/com/resilientpos/order/internal/application/**
- src/main/kotlin/com/resilientpos/order/internal/adapter/persistence/command/**
- src/main/resources/db/migration/V002__order_create_order.sql
- src/test/kotlin/com/resilientpos/order/**
- src/test/kotlin/com/resilientpos/SchemaMigrationTest.kt
- src/test/kotlin/com/resilientpos/ModuleStructureTest.kt
- docs/spec/2026-09-16-order-create-order-persistence.md
- docs/wiki/current-state.md
- docs/wiki/domain-model.md
- docs/wiki/changes.md

## 인수 조건
1. 유효한 `CreateOrderCommand`로 `OrderCommandFacade.create`를 부르면 `OPEN` 상태의 `CreateOrderResult`가 나오고, `t_order` 1행과 line 수만큼의 `t_order_line` 행이 저장되며, `OrderRepository.findById`로 읽은 aggregate의 line·통화·합계가 명령과 같다.
2. 같은 `(terminalId, clientOrderId)`로 다시 부르면 새 행을 만들지 않고 첫 호출과 같은 `orderId`·`status`를 돌려준다. `t_order` 행 수는 1이다.
3. 첫 호출은 `OrderCreated` 이벤트를 정확히 한 번 발행하고(`@RecordApplicationEvents`로 관측) 재시도 호출은 발행하지 않는다.
4. `Order.create`가 거절하는 명령(예: 빈 line)은 Facade에서도 같은 `IllegalArgumentException`으로 거절되고 `t_order`에 행이 남지 않는다.
5. Facade를 거치지 않고 같은 `(terminal_id, client_order_id)`를 두 번 저장하면 DB unique 제약이 거절한다.
6. `SchemaMigrationTest`: `flyway_schema_history`에 `001`·`002`가 성공으로 기록되고 `public` 스키마의 테이블은 `flyway_schema_history`, `t_order`, `t_order_line`뿐이다.
7. `ModuleStructureTest`가 모듈 9개(`platform` 포함)를 인식하고 경계 검증이 통과한다. `DependencyDirectionTest`의 도메인 레이어 규칙이 `OrderRepository`(인터페이스, 도메인 쪽)를 포함해 통과한다.
8. Fixture Monkey로 만든 `CreateOrderCommand`가 실제 `Order.create`를 통과한다. reflection으로 불변식을 우회한 객체를 정상 사례로 쓰지 않는다.
9. `python3 scripts/check_pr.py --base main --worktree`가 통과한다.

### Checkpoint와 검증 명령
| Checkpoint | 검증 명령 | 의존 |
|---|---|---|
| platform-config | `./gradlew test --tests 'com.resilientpos.ModuleStructureTest'` | 없음 |
| order-persistence | `./gradlew test --tests 'com.resilientpos.order.*'` | platform-config |
| schema | `./gradlew test --tests 'com.resilientpos.SchemaMigrationTest'` | order-persistence |

## 검증
```sh
./gradlew test
python3 scripts/check_pr.py --base main --worktree
```
Docker가 필요하다. 통합 테스트는 Testcontainers의 PostgreSQL 18에서 돈다.
- 정상: 생성 → 저장 → 이벤트 1회, 재시도 → 같은 결과·이벤트 0회.
- 실패: 도메인 거절은 행을 남기지 않고, unique 위반은 DB가 거절한다. JPA `validate`가 마이그레이션과 엔티티 불일치를 기동 시점에 잡는다.

## Wiki 영향
- [현재 상태](../wiki/current-state.md): 모듈 9개(`platform` 추가), `order`에 Facade 구현·JPA adapter·`V002`가 있음, Fixture Monkey 의존성 추가, 테스트 수 갱신. "`platform` 패키지는 아직 만들지 않았다" 문장을 지운다.
- [테스트 전략](../wiki/testing-strategy.md)의 "Fixture Monkey 의존성은 아직 없다" 문장은 이 PR 뒤 사실과 다르다. 그 절은 설치 PR의 기록이라 본문은 고치지 않고 현재 상태 페이지가 정본이다.
- [변경 기록](../wiki/changes.md): 이 spec 링크와 결정을 남긴다. 제품 요구사항은 바뀌지 않는다.

## 분할 계획
예상 규모: 구현 약 260줄(Repository 15, Facade 45, JPA 130, platform 25, 마이그레이션 30, build 5, package-info 10), 테스트 약 150줄(통합 90, fixture 30, 기존 테스트 수정 30), 문서 약 170줄로 총 580줄·17파일 내외. 경고 한도(600줄·20파일) 근처다.
초과하면 Fixture Monkey 도입(build 의존성, `OrderFixtures.kt`)을 Controller PR로 옮기고 이 PR은 수기 fixture를 쓴다. 그래도 넘으면 `platform` 신설을 빼고 `IdentifierGenerator`·`Clock` 빈을 `order/internal/application` 안에 두되 후속 PR에서 `platform`으로 옮긴다.
후속 순서는 `order-create-order-api`(REST Controller, REST Docs) → `RequestCheckout`이다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| `주문을 저장하고 OPEN 결과를 돌려준다` 외 통합 테스트 5개 | `./gradlew test --tests 'com.resilientpos.order.internal.application.OrderCommandFacadeIntegrationTest'` — `NoSuchBeanDefinitionException`(`OrderCommandFacade` 구현 빈 없음). 구현 중 중간 실패 2건: `SchemaManagementException: wrong column type ... found [bpchar], expecting [varchar]`(currency `char(3)`), `ApplicationEvents can only be injected into test and lifecycle methods`(생성자 주입 시도) | `@JdbcTypeCode(SqlTypes.CHAR)`와 테스트 메서드 파라미터 주입으로 해결 후 같은 명령 5개 통과 | `./gradlew test --tests 'com.resilientpos.order.*'` 통과 |
| `테이블은 마이그레이션이 만든 것뿐이다` / `baseline 마이그레이션이 성공으로 기록된다` | `V002` 추가 뒤 기존 기대값으로 `./gradlew test --tests 'com.resilientpos.SchemaMigrationTest'` — 2건 `AssertionFailedError`(`[001 to true]`·`[flyway_schema_history]` 기대, 실제는 `002` 적용과 `t_order`·`t_order_line` 존재) | 기대값 갱신 후 같은 명령 통과 | `./gradlew test --rerun` — 30 tests, 실패 0 |
| `bounded context 7개와 foundation과 platform을 application module로 인식한다` | `platform` 패키지 추가 뒤 `./gradlew test --tests 'com.resilientpos.ModuleStructureTest'` — `expected: [foundation, ..., payment, reconciliation, ...] but was: [..., payment, platform, reconciliation, ...]` | 기대 목록에 `platform` 추가 후 같은 명령 통과 | `./gradlew test` 통과 |

## 결과
`./gradlew test` BUILD SUCCESSFUL (30 tests, 앞 PR 25 + `OrderCommandFacadeIntegrationTest` 5). 인수 조건 1~9를 만족했다.

- **범위 조정**: `Order`에 `restore(id, storeId, terminalId, clientOrderId, currency, status, lines)` 팩토리를 추가했다. 생성자가 private이라 adapter의 `toDomain()`이 저장된 주문을 재구성할 길이 없었다. `restore`는 생성 불변식을 다시 묻지 않는다(최초 `create`가 검증했고 DB가 그 상태를 보존한다). `OrderLine`은 실제 생성자로 만들어 line 불변식은 그대로 검사된다. 변경 경로에 `Order.kt`를 추가했다.
- `currency char(3)` 컬럼은 Hibernate 기본 매핑(varchar)과 `validate`에서 충돌해 `@JdbcTypeCode(SqlTypes.CHAR)`를 붙였다. 스키마 정의를 바꾸지 않고 엔티티를 맞춘 것이다.
- `ApplicationEvents`는 Spring이 테스트 메서드 파라미터로만 주입을 허용해 그 테스트에서만 파라미터로 받았다.
- Fixture Monkey 1.2.3의 Kotlin API(`giveMeKotlinBuilder`, `setExp`)가 Kotlin 2.4·JDK 25에서 그대로 동작했다. deprecation 경고는 없다. `Money`는 `init` 불변식 때문에 무작위 생성 대상이 아니라 line과 통화를 고정값으로 넣는다.
- 통합 테스트 5개가 RED를 한 원인(`NoSuchBeanDefinitionException`)으로 함께 실패한 뒤 함께 GREEN이 됐다. Facade 구현 하나가 다섯 동작을 동시에 만족시키는 구조라 동작별 RED를 따로 관측하지 못했다. 재시도 반환과 이벤트 1회 발행은 구현의 분기 하나씩에 대응하므로 각 테스트가 그 분기를 고정한다.
- `SchemaMigrationTest`의 테스트 이름을 `애플리케이션이 업무 테이블을 만들지 않는다`에서 `테이블은 마이그레이션이 만든 것뿐이다`로 바꿨다. 검사 내용(테이블 목록이 마이그레이션 산출과 정확히 같다)은 같고 이름이 새 기대값을 더 정확히 말한다.

### 범위 조정 - 기준 브랜치 변경 (개발 후)
[order-domain-model](2026-09-16-order-domain-model.md) PR이 squash 머지되어 base 브랜치 `feature/order-domain-model`이 삭제됐고 이 PR의 base가 `main`으로 재지정됐다. squash는 앞 PR의 커밋 6개를 하나로 합치므로 이 브랜치에 남은 원본 커밋 6개가 같은 변경을 두 번 적용해 충돌이 났다.

`git rebase --onto origin/main 36c69f7`로 이 PR 고유 커밋 5개만 새 `main` 위로 옮겨 해결했다. 충돌 편집은 없었다. rebase 전후 트리가 같은 것을 `git diff`로 확인했고 spec 첫 커밋은 그대로 첫 커밋이다. 인수 조건 9와 검증 명령의 기준을 `main`으로 고쳤다. 위 결과의 `--base feature/order-domain-model` 검사는 rebase 전 기록이며, rebase 뒤 `--base main`으로 다시 PASS(일반 18개/585줄)와 `./gradlew test` 30개 통과를 확인했다.

### 정정 - Fixture Monkey 버전 (개발 후)
처음 1.1.11로 넣었는데 당시 최신은 1.2.3이었다. `search.maven.org`의 검색 API가 `latestVersion`을 1.1.11로 응답했고 그 값을 그대로 썼다. 권위 있는 출처는 `https://repo1.maven.org/maven2/.../maven-metadata.xml`의 `release` 태그이며, 거기에는 버전 96개와 `release` 1.2.3이 있다. 앞으로 의존성 최신 버전은 이 메타데이터로 확인한다.

1.2.3으로 올린 뒤 `OrderFixtures`의 코드 변경 없이 `./gradlew test` 30개가 통과했고 `compileTestKotlin`에 deprecation 경고가 없다. `testRuntimeClasspath`에서 전이 의존성까지 1.2.3으로 해결되는 것을 확인했다.

### 범위 조정 - 리뷰 반영: Facade 클래스화, `BaseEntity`, line 자식 엔티티 (개발 후)
PR 리뷰를 세 차례 반영한다. 변경 경로에 `order/OrderCommandFacade.kt`, `platform/BaseEntity.kt`, `docs/wiki/domain-model.md`를 추가했다. 아래는 범위·인수 조건의 앞선 서술(`DefaultOrderCommandFacade`, `OrderJpaEntity`, `OrderLineJpaValue`, `@ElementCollection`)을 대체한다.

- **Facade는 인터페이스 없이 구체 클래스다**: 앞 PR의 `OrderCommandFacade` 인터페이스를 지우고 `DefaultOrderCommandFacade`를 모듈 루트의 `class OrderCommandFacade`로 옮긴다. 구현이 하나뿐인 인터페이스였고 `Default` 접두가 그 비용이었다. Spring Modulith는 모듈 기본 패키지의 타입을 공개로 보므로 호출자는 그대로 `com.resilientpos.order.OrderCommandFacade`를 주입받는다. `internal/application` 패키지는 비게 되어 없어진다.
- **엔티티 이름**: `OrderJpaEntity`를 `OrderEntity`로 바꾼다. JPA 엔티티 이름은 `OrderEntity`처럼 aggregate 이름 + `Entity`이고 `Jpa`를 넣지 않는다. Spring Data repository는 도메인 port `OrderRepository`와 단순 이름이 겹치므로 `OrderJpaRepository`를 유지한다.
- **`platform.BaseEntity`는 Hibernate 기능으로 만든다**: `@CreationTimestamp`·`@UpdateTimestamp`(`SourceType.DB`)가 `created_at`·`updated_at`을 채우고 `@SoftDelete(strategy = TIMESTAMP, columnName = "deleted_at")`가 삭제를 `deleted_at` 기록으로 바꾸며 모든 조회에서 삭제된 행을 거른다. JPA callback과 `delete()`·`restore()` 같은 직접 구현은 두지 않는다. 시각의 출처는 DB `current_timestamp` 하나라서 한 INSERT의 `created_at`과 `updated_at`이 같다. Hibernate 7.4.5의 `EntityBinder`가 `@MappedSuperclass`의 `@SoftDelete`를 상속 엔티티에 적용하는 것을 소스로 확인했다. `@SoftDelete`는 `@Incubating` API다. `V002`의 `t_order`에 세 컬럼을 추가한다(`V002`는 아직 `main`에 없어 직접 고친다). `foundation`은 `jakarta`에 의존할 수 없어 자리는 `platform`이다.
- **참고 코드에서 가져오지 않은 것**: `id`(`Long` IDENTITY)는 도메인 ID가 UUID v7이고 `@GeneratedValue`를 쓰지 않는 규칙(볼트 규칙 §3) 때문에 각 엔티티에 남긴다. `ActiveStatus status`는 `deleted_at`과 같은 사실을 두 컬럼에 적어 어긋날 수 있고 `OrderEntity.status`와 컬럼 이름이 충돌하므로 두지 않는다. `LocalDateTime` 대신 `Instant`(`timestamptz`)를 쓴다.
- **JPA 연관관계 매핑을 쓰지 않는다**: `@OneToMany`·`@ManyToOne`·`@OneToOne`·`@ManyToMany`와 `@ElementCollection`을 쓰지 않는다. aggregate 안의 자식도 부모 ID 컬럼만 가진 독립 엔티티와 독립 Spring Data repository로 두고, 조립과 변경 반영은 persistence adapter가 한다. 매핑이 fetch 전략·cascade·flush 순서를 숨기지 않고, 읽는 쿼리가 코드에 그대로 보인다. 원칙은 [도메인 모델 §4.2](../wiki/domain-model.md)와 볼트 규칙 `domain-modeling-kotlin.md` §5에 적는다.
- **`OrderLine`에 도메인 ID `OrderLineId`(UUID v7)를 준다**: 독립 엔티티는 자기 키가 필요하다. 주문 안의 위치(`line_no`)는 중간 line을 지우면 뒤 line이 당겨져 다른 상품을 가리키므로 식별자가 될 수 없다. `Order.create`가 이미 받는 `IdentifierGenerator`로 line마다 만든다(순서: 주문 ID → line ID들 → 이벤트 ID). `OrderLineId`는 ID 값 타입이라 모듈 루트에 둔다. 앞 PR의 `OrderLine`·`Order.create`·`OrderTest`를 고친다.
- **`t_order_line`**: `id uuid primary key`를 추가하고 `(order_id, line_no)`는 PK에서 내려 조회용 index로 둔다. unique로 두면 순서를 바꾸는 UPDATE가 중간에 충돌한다. `line_no`는 정렬용이다. DB FK(`order_id → t_order(id)`)도 두지 않는다. 연관관계 매핑과 같은 이유이고, 무결성은 Facade 트랜잭션 안에서 주문과 line을 함께 저장하는 adapter가 지킨다. 고아 line이 생길 수 있는 경로는 `OrderLineJpaRepository`를 adapter 밖에서 쓰는 경우뿐이라 그 repository는 `OrderPersistenceAdapter`만 쓴다. `V002`는 아직 `main`에 없어 직접 고친다.
- **`OrderLineEntity`와 `OrderLineJpaRepository`**: line은 자기 ID가 있어 `BaseEntity`를 상속한다. 빠진 line은 soft delete로 `deleted_at`이 남는다. `OrderPersistenceAdapter.save`가 line ID로 기존 행과 맞춰 같은 ID는 제자리 갱신, 새 ID는 INSERT, 없어진 ID는 삭제한다. 조회는 주문 1회 + line 1회(`findAllByOrderIdOrderByLineNo`)다. `EAGER`와 `@EntityGraph`는 없다.
- **`@Version`을 `BaseEntity`로 옮긴다**: ID를 저장 전에 만드는 엔티티는 모두 아래 `merge` 문제를 겪으므로 `version: Long?`를 공통 컬럼으로 둔다. line INSERT마다 붙던 존재 확인 SELECT가 없어진다.
- **알려진 한계**: line만 바뀌고 루트 컬럼이 그대로면 루트 `version`이 오르지 않는다. line을 수정하는 첫 use case PR에서 루트 버전을 강제로 올린다.
- **`@Version`을 `Long?`로 바꾼다(인수 조건 10이 드러낸 결함)**: `version: Long = 0`(primitive)이면 Spring Data `save`가 ID가 이미 채워진 새 엔티티를 기존 행으로 판단해 `persist` 대신 `merge`를 부른다. `merge`는 빈 컬렉션으로 INSERT한 뒤 line을 복사하므로 컬렉션 변경으로 `version`을 0→1로 올리는 UPDATE가 따라붙었고, 그래서 생성 직후인데 `updated_at`이 `created_at`보다 늦었다. wrapper 타입이면 `version == null`로 신규를 판정해 `persist`로 간다. 주문 생성마다 SELECT 1회와 UPDATE 1회가 줄어든다.
- **인수 조건 10 추가**: `OrderCommandFacade.create`로 저장한 `t_order` 행은 `created_at`과 `updated_at`이 같은 값으로 채워지고 `deleted_at`은 null이며 `version`은 0이다.
- **인수 조건 11 추가**: 저장된 주문을 line이 바뀐 aggregate(값 변경과 추가, 그다음 중간 line 제거)로 다시 `OrderRepository.save`하면 `findById`가 같은 line 목록을 ID까지 같게 돌려주고, 제거된 line 행은 `deleted_at`이 채워진 채 남는다.
- **인수 조건 12 추가**: `Order.create`가 만든 line은 `IdentifierGenerator`가 준 ID를 순서대로 가진다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| `신규 저장은 INSERT 한 번이고 생성 시각과 수정 시각이 같으며 삭제 시각은 비어 있다` | `./gradlew test --tests 'com.resilientpos.order.internal.application.OrderCommandFacadeIntegrationTest'` — `BadSqlGrammarException`(`created_at` 컬럼 없음). `BaseEntity`·`V002` 반영 뒤 두 번째 RED: `AssertionFailedError: expected …56.890431 but was …56.89063`(`updated_at`이 늦음, `version` 1) | `version: Long?`로 바꾼 뒤 같은 명령 6개 통과, `version` 0 | `./gradlew test` — 31 tests, 실패 0 |
| `line이 바뀐 주문을 다시 저장하면 같은 ID는 갱신하고 새 ID는 추가하고 없어진 ID는 soft delete한다`, `유효한 명령으로 OPEN 주문과 OrderCreated를 만든다`(line ID 단언 추가) | `./gradlew compileTestKotlin` — `Unresolved reference 'OrderLineId'`, `Unresolved reference 'id'` | `OrderLineId`·독립 `OrderLineEntity`·`OrderLineJpaRepository`·adapter 조립 구현 뒤 `./gradlew test` 32개 통과 | `SPRING_JPA_SHOW_SQL=true`로 SQL 확인: line INSERT 앞의 존재 확인 SELECT 없음, 제거는 `update t_order_line set deleted_at=…`, 모든 조회에 `deleted_at is null`. `src/main`에 연관관계 애너테이션 없음(`grep`) |
