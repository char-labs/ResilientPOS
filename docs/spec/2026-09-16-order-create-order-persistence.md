# PR spec: CreateOrder 영속화와 Facade 구현

## 목적
[order-domain-model](2026-09-16-order-domain-model.md)이 만든 `Order` aggregate와 `CreateOrder` 계약을 실제로 저장하고 결과를 돌려주는 use case로 완성한다. `OrderCommandFacade.create`가 트랜잭션 안에서 주문을 PostgreSQL에 저장하고 `OrderCreated`를 발행하며, 같은 `(terminalId, clientOrderId)` 재시도에는 기존 결과를 돌려준다. 이 PR이 끝나면 HTTP 없이도 주문 생성 경로가 DB까지 닿는다. 앞 PR 브랜치 `feature/order-domain-model` 위의 stacked PR이다.

## 범위
- `order/internal/domain/OrderRepository`: `findById(OrderId): Order?`, `findByClientOrderId(TerminalId, ClientOrderId): Order?`, `save(Order): Order`. 시그니처에 aggregate와 ID 값 타입만 쓴다.
- `order/internal/application/DefaultOrderCommandFacade`: `@Transactional`의 유일한 소유자. 순서는 기존 주문 조회 → 있으면 기존 `CreateOrderResult` 반환 → 없으면 `Order.create` → `repository.save` → `Transition.events`를 `ApplicationEventPublisher`로 발행 → `CreateOrderResult` 변환. Service는 두지 않는다.
- `order/internal/adapter/persistence/command`: `OrderJpaEntity`(`@Version` 포함), `OrderLine`을 `@ElementCollection`·`@OrderColumn`으로 매핑하는 `OrderLineJpaValue`, Spring Data `OrderJpaRepository`, `OrderRepository` 구현 `OrderPersistenceAdapter`. 변환 `from(aggregate)`, `apply(aggregate)`, `toDomain()`은 엔티티의 `companion`과 메서드가 소유한다. 금액은 `NUMERIC(19,4)` 컬럼에서 통화 표준 자릿수로 되돌려 `Money`를 만든다.
- Flyway `V002__order_create_order.sql`: `t_order`(id uuid PK, store_id uuid, terminal_id uuid, client_order_id uuid, currency char(3), status varchar(32), version bigint, unique `(terminal_id, client_order_id)`)와 `t_order_line`(order_id uuid FK → t_order, line_no int, product_code, product_name, unit_price numeric(19,4), quantity int, PK `(order_id, line_no)`). 합계 컬럼은 두지 않는다. 파생 값이다.
- `platform` 패키지 신설과 `@ApplicationModule` 선언. `IdentifierGenerator`(UUID v7)와 `Clock`(UTC) 빈을 등록하는 `PlatformConfiguration`. [개발 가이드 §1](../references/03_Development_Guide.md)이 `platform`을 adapter·configuration 자리로 정했다. `ModuleStructureTest`의 모듈 목록에 `platform`을 추가한다.
- Fixture Monkey 도입: `fixture-monkey-starter-kotlin` 최신 버전(작성 시점 1.1.11)을 `testImplementation`에 추가하고 `src/test/kotlin/com/resilientpos/order/OrderFixtures.kt`에 `CreateOrderCommand` 빌더를 둔다. 판단에 중요한 값(통화, line 단가·수량, ID)은 고정한다.
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
- src/main/kotlin/com/resilientpos/order/internal/domain/OrderRepository.kt
- src/main/kotlin/com/resilientpos/order/internal/application/**
- src/main/kotlin/com/resilientpos/order/internal/adapter/persistence/command/**
- src/main/resources/db/migration/V002__order_create_order.sql
- src/test/kotlin/com/resilientpos/order/**
- src/test/kotlin/com/resilientpos/SchemaMigrationTest.kt
- src/test/kotlin/com/resilientpos/ModuleStructureTest.kt
- docs/spec/2026-09-16-order-create-order-persistence.md
- docs/wiki/current-state.md
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
9. `python3 scripts/check_pr.py --base feature/order-domain-model --worktree`가 통과한다.

### Checkpoint와 검증 명령
| Checkpoint | 검증 명령 | 의존 |
|---|---|---|
| platform-config | `./gradlew test --tests 'com.resilientpos.ModuleStructureTest'` | 없음 |
| order-persistence | `./gradlew test --tests 'com.resilientpos.order.*'` | platform-config |
| schema | `./gradlew test --tests 'com.resilientpos.SchemaMigrationTest'` | order-persistence |

## 검증
```sh
./gradlew test
python3 scripts/check_pr.py --base feature/order-domain-model --worktree
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
| 주문을 저장하고 OPEN 결과를 돌려준다 | 미실행 | 미실행 | 미실행 |
| 같은 clientOrderId 재시도는 기존 결과를 돌려주고 행을 늘리지 않는다 | 미실행 | 미실행 | 미실행 |
| OrderCreated는 첫 호출에만 한 번 발행된다 | 미실행 | 미실행 | 미실행 |
| 도메인이 거절한 명령은 행을 남기지 않는다 | 미실행 | 미실행 | 미실행 |
| 같은 terminal_id와 client_order_id는 DB unique가 거절한다 | 미실행 | 미실행 | 미실행 |
| 마이그레이션 002가 t_order와 t_order_line을 만든다 | 미실행 | 미실행 | 미실행 |
| platform을 포함한 모듈 9개를 인식한다 | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
