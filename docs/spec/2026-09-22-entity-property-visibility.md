# PR spec: JPA 엔티티의 생성자와 프로퍼티를 밖에서 쓸 수 없게 좁힌다

## 목적
볼트 규칙 `domain-modeling-kotlin.md` 5절은 "엔티티는 `from(aggregate)`로 값을 모두 받아서만 만든다", "필드 변경은 `apply(aggregate)` 안에서만 일어난다"고 규정한다. 그런데 엔티티 4개의 생성자와 모든 프로퍼티가 public `var`라 컴파일러가 아무것도 막지 않는다. `OrderEntity.from(order).status = OrderStatus.CHECKOUT_REQUESTED`도, `OrderEntity(...)` 직접 호출도 지금 그냥 컴파일된다. 규칙이 주석으로만 살아 있다.

기대 결과: 생성자는 `private`이고 밖에서 보이는 생성 경로는 `companion object`의 `from`뿐이다. `apply`가 바꾸지 않는 필드는 `val`, `apply`가 바꾸는 필드만 `var`에 `private set`이다. 위 두 줄은 컴파일 오류가 된다. 런타임 동작은 바뀌지 않는다.

이 PR은 [카카오페이 DDD 사례](https://tech.kakaopay.com/post/backend-domain-driven-design/)의 구현 코드 규칙 5개를 이 저장소와 대조한 결과에서 나왔다. 4개(bounded context = Gradle 모듈, JPA Entity와 Repository에 Kotlin `internal` 키워드, 가변 aggregate의 `var internal set`, Biz-component 패키지)는 [도메인 모델](../wiki/domain-model.md) 8절 표에 기각 근거와 함께 이미 기록돼 있고 그 결정은 유지한다. 사례와 목적이 같은데 이 저장소에서만 비어 있던 자리가 엔티티 가시성 하나다. 사례는 그 목적을 Kotlin `internal`로 이뤘고, 이 저장소는 같은 이유로 `internal`을 쓰지 않으므로 `private` 생성자와 `private set`으로 이룬다.

## 범위
대상은 `platform.BaseEntity`를 상속하는 엔티티 4개다. 네 파일 모두에 공통으로 적용하는 것은 셋이다.

- primary constructor를 `private`으로 바꾸고 밖에서 보이는 생성 경로는 `companion object`의 `from`만 남긴다.
- 생성자 프로퍼티에는 setter 가시성 수식어를 붙일 수 없으므로, 생성자는 값만 받고 프로퍼티는 클래스 본문에서 선언한다.
- 매핑 애너테이션(`@Id`, `@Column`, `@Enumerated`, `@JdbcTypeCode`, `@Version`)은 본문 프로퍼티 선언으로 그대로 옮긴다. 컬럼 이름·타입·순서는 바뀌지 않는다.

엔티티별로 `val`과 `var ... private set`의 구분은 이렇다.

| 엔티티 | `val` | `var ... private set` |
|---|---|---|
| `OrderEntity` | `id` | `storeId`, `terminalId`, `clientOrderId`, `currency`, `status` |
| `OrderLineEntity` | `id`, `orderId` | `lineNo`, `productCode`, `productName`, `unitPrice`, `quantity` |
| `OrderCheckoutEntity` | 전 필드 | 없음 |
| `PaymentAttemptEntity` | 전 필드 | 없음 |

`var` 쪽은 각 엔티티의 `apply`가 실제로 대입하는 필드 그대로다. `OrderCheckoutEntity`와 `PaymentAttemptEntity`에는 `apply`가 없다. checkout은 확정 뒤에 바뀌지 않고, 결제 시도는 지금 기록만 하기 때문이다.

`OrderEntity.version`은 지금의 `@Version var version: Long? = null`과 `protected set`을 그대로 둔다. Hibernate가 쓰는 필드이고 5절의 기존 규칙이다.

### 먼저 확인할 것
위 표에는 검증되지 않은 가정이 하나 있다. Kotlin `val`은 JVM에서 final 인스턴스 필드이고, 이 저장소의 엔티티는 필드 접근 전략이다. Hibernate가 DB에서 읽은 행을 엔티티에 채울 때 final 필드에 reflection으로 쓸 수 있는지를 실행으로 확인하지 않았다. 못 쓰면 조회 경로가 런타임에서 깨진다. `kotlin("plugin.jpa")`가 만드는 인자 없는 생성자는 프로퍼티 초기화식을 실행하지 않으므로, Hibernate가 필드에 직접 쓰지 못하면 값이 채워지지 않는다.

그래서 구현 순서를 이렇게 잡는다.

1. 엔티티 하나만 먼저 바꾼다. `OrderCheckoutEntity`가 가장 작고 `apply`가 없어 전 필드가 `val` 후보다. 기존 통합 테스트 `OrderCommandFacadeIntegrationTest`와 `OrderRequestCheckoutIntegrationTest`를 돌려 조회 경로가 동작하는지 본다.
2. 동작하면 위 표대로 `val`과 `var ... private set` 혼합으로 나머지 세 개를 간다.
3. 동작하지 않으면 네 엔티티의 전 필드를 `var ... private set`으로 통일하고, 볼트 `.claude/rules/domain-modeling-kotlin.md` 5절의 해당 불릿과 예시 코드를 실제로 동작하는 형태로 고친다. 지침이 현실과 어긋나면 고치는 쪽은 지침이다.

어느 쪽이든 관측한 결과를 결과 절에 적는다. 2번과 3번은 밖에서 프로퍼티를 바꿀 수 없다는 목적을 똑같이 이루고, `val`이 추가로 주는 것은 엔티티 안에서의 재대입까지 막는 것뿐이다.

### 범위 조정 1 (2026-09-22, `private set`이 아니라 `protected set`이다)
위 표와 볼트 지침은 `apply`가 바꾸는 필드를 `var ... private set`으로 적었다. 실제로 쓰고 컴파일하니 Kotlin이 거절한다. `build.gradle.kts`의 `allOpen`이 `jakarta.persistence.Entity`에 걸려 있어 엔티티의 멤버가 전부 `open`이고, Kotlin은 open 프로퍼티에 `private set`을 금지한다("Private setters for open properties are prohibited", `OrderEntity` 5곳과 `OrderLineEntity` 5곳). `allOpen`은 Hibernate가 엔티티를 상속해 프록시를 만들 수 있게 하려고 둔 설정이라 걷어낼 대상이 아니다.

- 결정: `apply`가 바꾸는 필드는 `var ... protected set`이다. `val`인 필드는 setter가 없어 이 제약과 무관하므로 그대로 둔다. 목적은 그대로 이룬다. `protected`는 클래스 밖에서 보이지 않고, 실제 하위 타입은 Hibernate가 실행 중에 만드는 프록시뿐이다.
- 이 저장소에는 이미 같은 형태가 둘 있다. `BaseEntity`의 `createdAt`·`updatedAt`과 `OrderEntity`의 `@Version version`이 `protected set`이다. 5절이 `@Version`에 대해서만 적어 둔 규칙이 사실은 이 저장소의 모든 엔티티 필드에 적용되는 제약이었다.
- 버린 안: 프로퍼티를 명시적으로 `final`로 선언해 `private set`을 되살리는 것. 컴파일은 통과하지만 `allOpen`을 둔 이유를 매핑 프로퍼티에서만 무효로 만든다. 가시성을 한 단계 좁히자고 프록시 전제를 흔들 이유가 없다.
- 볼트 `.claude/rules/domain-modeling-kotlin.md` 5절의 해당 불릿과 예시 코드, 7절의 한 줄을 `protected set`으로 고친다. 지침이 현실과 어긋나면 고치는 쪽은 지침이라는 범위의 3번 판단을 그대로 적용한다. 수정 전 사본을 `~/Documents`에 다시 남기고 결과 절에 적는다.
- 인수 조건 2와 8의 판정도 `protected set`으로 읽는다.

### 호출부
지금 엔티티 밖에서 setter를 쓰는 곳이 없다. 대입은 `OrderLineEntity.apply`와 `OrderEntity.apply` 안뿐이고, 생성은 네 곳 모두 `from`을 거친다(`OrderPersistenceAdapter`, `PaymentAttemptPersistenceAdapter`, 테스트 한 곳의 `PaymentAttemptEntity.from`). 그래서 호출부가 깨지지 않는다. 동작은 바뀌지 않는 순수 가시성 축소다.

소유 모듈은 `order`와 `payment`다. Command, Query, 도메인 이벤트, HTTP 계약, 저장 형식은 바뀌지 않는다. 정합성 불변식·복구·관측성도 바뀌지 않는다.

## 제외
- `OrderLine`·`Checkout` 같은 자식 도메인 객체를 `data class`에서 `private constructor` + 팩토리를 가진 `class`로 바꾸는 일. `copy`로 루트를 거치지 않은 자식을 만들 수 있다는 갭은 실재하지만 사용자가 2026-09-22 이번 범위에서 뺐다. 필요하면 후속 PR이다.
- 스키마 변경. 매핑 애너테이션의 위치만 옮기고 컬럼·제약·인덱스는 그대로라 마이그레이션이 없다. 그래서 "스키마 변경 검토" 절을 두지 않는다.
- Kotlin `internal` 키워드 도입. [도메인 모델](../wiki/domain-model.md) 8절의 기존 결정대로 쓰지 않는다. 단일 Gradle 프로젝트에서 `internal`은 전체에 보여 경계가 되지 않는다.
- `BaseEntity`의 `createdAt`·`updatedAt`. 이미 `lateinit var ... protected set`이고 Hibernate가 채우는 필드라 건드리지 않는다.
- aggregate와 value object의 가시성. 5절이 아닌 2·3절의 대상이고 이미 `private constructor` + 팩토리다.
- 새 테스트. 동작이 바뀌지 않는 리팩터링이다. 근거는 TDD 검증 기록 절에 적는다.

## 관련 문서
없음. 장기 스펙 계층을 따르지 않는 규칙 적용이다. 이 PR이 적용하는 규칙의 정본은 볼트 `.claude/rules/domain-modeling-kotlin.md` 5절이고, 저장소 쪽 근거는 [도메인 모델](../wiki/domain-model.md) 8절이다.

## 변경 경로
- src/main/kotlin/com/resilientpos/order/internal/adapter/persistence/command/OrderEntity.kt
- src/main/kotlin/com/resilientpos/order/internal/adapter/persistence/command/OrderLineEntity.kt
- src/main/kotlin/com/resilientpos/order/internal/adapter/persistence/command/OrderCheckoutEntity.kt
- src/main/kotlin/com/resilientpos/payment/internal/adapter/persistence/command/PaymentAttemptEntity.kt
- docs/wiki/domain-model.md

## 인수 조건
1. 엔티티 4개의 primary constructor가 `private`이다. 밖에서 보이는 생성 경로는 각 `companion object`의 `from`뿐이다.
2. 엔티티 4개에 밖에서 쓸 수 있는 setter가 없다. `apply`가 바꾸는 필드는 `private set`, 나머지는 `val`이거나 (1번 확인이 실패하면) 같은 `private set`이다.
3. 엔티티 밖에서 프로퍼티에 대입하거나 생성자를 직접 부르는 코드는 컴파일되지 않는다. 임시 코드로 실제 컴파일 실패를 관측하고 결과 절에 적는다.
4. 매핑은 그대로다. `SchemaMigrationTest`와 JPA `validate`가 통과하고 컬럼 이름·타입이 바뀌지 않는다.
5. `OrderEntity`의 `@Version var version`은 `protected set`으로 남아 있고 `OrderVersionConflictIntegrationTest`가 고치지 않은 채 통과한다.
6. `./gradlew test` 전체가 고친 테스트 없이 통과한다. 조회 경로(`toDomain`)가 동작하는 것이 이 조건의 핵심이다.
7. [도메인 모델](../wiki/domain-model.md) 8절 표의 `internal` 가시성 행이 이번 결정을 서술한다. 가시성을 좁힌다는 목적은 사례와 같고 수단만 다르다는 점이 드러난다.
8. 볼트 규칙과 코드가 일치한다. 1번 확인 결과가 지침과 다르면 지침을 고치고 수정 전 사본을 남긴다.

## 검증
- `./gradlew test --tests 'com.resilientpos.order.internal.application.OrderCommandFacadeIntegrationTest' --tests 'com.resilientpos.order.internal.application.OrderRequestCheckoutIntegrationTest'`: 범위의 1번 확인. `OrderCheckoutEntity`만 바꾼 상태에서 조회 경로를 본다.
- `./gradlew test`: 인수 조건 4·5·6. Testcontainers가 PostgreSQL을 띄우므로 Docker가 필요하다.
- 임시 코드로 인수 조건 3을 확인한다. 엔티티 밖에서 `status`에 대입하는 줄과 생성자를 직접 부르는 줄을 각각 넣고 컴파일이 실패하는 것을 본 뒤 지운다. 커밋하지 않는다.
- `python3 scripts/check_pr.py --base f3fcb11 --worktree`를 개발 중에, 제출 전에는 `--worktree` 없이 한 번 더 실행한다.
- 실패 시나리오: Hibernate가 final 필드에 쓰지 못하면 1번 확인에서 `OrderCheckoutEntity`의 필드가 null로 남아 `toDomain`이 실패한다. 그 경우 범위의 3번 경로로 간다. 애너테이션을 본문으로 옮기면서 놓치면 매핑이 달라져 JPA `validate`가 기동에서 실패한다.
- 🔴 PR이다. JPA 매핑 애너테이션이 있는 파일 4개를 고친다(PR 템플릿의 사실 기준). 제출 전에 resilientpos-multi-review를 실행한다. 마이그레이션이 없어 스키마 변경 검토와 대조 기록은 대상이 아니다.

## Wiki 영향
- [도메인 모델](../wiki/domain-model.md) 8절 표의 "Kotlin `internal` 가시성으로 JPA 엔티티를 숨김" 행. 지금은 `internal`을 쓰지 않는다는 기각만 적혀 있어서, 사례가 그 키워드로 이루려던 목적(엔티티를 밖에서 만들거나 바꾸지 못하게)을 이 저장소가 어떻게 이루는지가 빠져 있다. `private` 생성자와 `private set`이 그 수단이라는 것을 행에 적는다. `internal`을 쓰지 않는 기존 근거는 그대로 둔다.
- 볼트 `.claude/rules/domain-modeling-kotlin.md` 5절의 가시성 불릿·예시 코드와 7절 "하지 않는 것"의 두 줄. 이 PR을 시작하기 전에 이미 반영돼 있다. 볼트는 저장소 밖이고 git으로 관리되지 않으므로 수정 전 사본을 `~/Documents/domain-modeling-kotlin.2026-09-21.before-entity-visibility.md`에 남겼다. 범위의 3번 경로로 가면 지침을 한 번 더 고치고 사본도 다시 남긴 뒤 결과 절에 적는다.
- `docs/wiki/changes.md`는 없다. [drop-changes-log](2026-09-21-drop-changes-log.md)가 사본 기록이라는 이유로 삭제했고 기록 요구를 PR spec 한 곳으로 모았다. 그래서 변경 기록·볼트 수정 기록·요구사항 판정은 이 spec에만 남긴다.
- [현재 상태](../wiki/current-state.md)는 갱신 대상이 아니다. 테스트 개수와 모듈 선언 상태가 바뀌지 않는다.
- 요구사항 영향: 없다. 제품 요구사항, API 계약, 저장 형식, 도메인 이벤트가 모두 그대로다. 바뀌는 것은 엔티티 작성 규칙을 컴파일러가 강제하는지 여부뿐이고, 생성·변경 경로는 이미 `from`과 `apply`였다.

## 분할 계획
예상은 파일 5개, 200줄 안쪽이다. 경고 한도(900줄)와 상한(1400줄)에 닿지 않는다. 자식 도메인 객체의 `copy` 갭은 제외 절에 적은 대로 후속 PR 후보이고 이번에 섞지 않는다.

## TDD 검증 기록
적용하지 않는다. 업무 동작이 바뀌지 않는 가시성 축소다. 새 분기·계산·상태 전이가 없어 실패할 수 있는 새 동작이 없고, 규칙 위반은 테스트가 아니라 컴파일러가 잡는다. 대신 두 가지를 실행으로 확인하고 결과 절에 적는다. 하나는 범위 1번의 `val` 확인이고, 다른 하나는 인수 조건 3의 컴파일 실패 관측이다. 기존 테스트 전체가 고치지 않은 채 통과하는 것이 동작 무변화의 근거다.

## 결과
- 범위 1번(`val` 확인): 통과했다. `OrderCheckoutEntity`만 `private constructor` + 전 필드 `val`로 바꾼 상태에서 `OrderCommandFacadeIntegrationTest` 11개와 `OrderRequestCheckoutIntegrationTest` 9개가 모두 통과했다. 판정의 근거가 된 것은 `checkout을 요청한 주문을 다시 읽으면 checkouts가 그대로 복원된다`다. 이 테스트는 커밋된 주문을 새 트랜잭션에서 `repository.findById`로 다시 읽고 `checkoutNo`·`amount`·`id`를 DB 행과 대조하므로, Hibernate가 DB에서 읽은 값을 Kotlin `val`(JVM final) 인스턴스 필드에 채운다는 것을 직접 보인다. 필드 접근 전략에서 final 필드에 쓰지 못할 것이라는 가정은 틀렸다. `kotlin("plugin.jpa")`가 만드는 인자 없는 생성자가 초기화식을 실행하지 않아도 값이 채워진다. 그래서 2번 경로로 갔고 볼트 지침의 `val` 규정은 고칠 이유가 없었다.
- 범위 조정 1(`protected set`): 표대로 나머지 세 엔티티까지 쓰고 컴파일하니 Kotlin이 `private set` 10곳(`OrderEntity` 5, `OrderLineEntity` 5)을 "Private setters for open properties are prohibited"로 거절했다. 원인은 `build.gradle.kts`의 `allOpen`이 `jakarta.persistence.Entity`에 걸려 있는 것이다. `protected set`으로 바꿔 해결했다. 사후에 보면 이 제약은 이미 저장소에 드러나 있었다. `BaseEntity`의 두 시각과 `OrderEntity`의 `@Version version`이 전부터 `protected set`이었고, 5절이 `@Version`에 한정해 적어 둔 규칙이 사실은 모든 엔티티 필드에 걸리는 제약이었다.
- 인수 조건 1·2: 엔티티 4개의 primary constructor가 `private`이고 밖에서 보이는 생성 경로는 `from`뿐이다. `OrderEntity`는 `id`가 `val`, `apply`가 대입하는 5개가 `protected set`이다. `OrderLineEntity`는 `id`·`orderId`가 `val`, `apply`가 대입하는 5개가 `protected set`이다. `OrderCheckoutEntity`와 `PaymentAttemptEntity`는 전 필드가 `val`이다.
- 인수 조건 3: 임시 파일 하나로 관측했다. 엔티티와 같은 패키지에 둔 코드에서도 `entity.status = OrderStatus.CHECKOUT_REQUESTED`는 "Cannot access 'status': it is protected", `OrderCheckoutEntity(...)` 직접 호출은 "Cannot access 'constructor...': it is private"로 컴파일이 실패한다. Kotlin에는 package-private이 없어서 같은 패키지라는 사실이 가시성을 열어 주지 않는다. 임시 파일은 관측 뒤 지웠고 커밋하지 않았다.
- 인수 조건 4·5·6: `./gradlew test` 167개가 고친 테스트 없이 통과한다. 매핑은 그대로라 JPA `validate`와 `SchemaMigrationTest`가 통과하고, `OrderVersionConflictIntegrationTest`와 문장 수 테스트 두 개도 그대로다. 테스트 파일은 한 줄도 바꾸지 않았고 운영 코드도 엔티티 4개 밖에서는 바뀌지 않았다. 이것이 동작 무변화의 근거다.
- 인수 조건 7: [도메인 모델](../wiki/domain-model.md) 8절 표의 `internal` 행을 고쳤다. 목적(엔티티를 밖에서 만들거나 바꾸지 못하게)은 사례와 같고 수단이 모듈 경계와 엔티티 가시성 둘로 갈린다는 것, `private set`을 쓸 수 없는 이유를 적었다. `internal`을 쓰지 않는 기존 근거는 그대로 뒀다.
- 인수 조건 8: 볼트 `.claude/rules/domain-modeling-kotlin.md`의 세 자리를 `protected set`으로 고쳤다. 5절의 가시성 불릿(이유 문장 추가), 5절 예시 코드의 `OrderEntity.status`, 7절 "하지 않는 것"의 마지막 줄이다. 수정 전 사본은 `~/Documents/domain-modeling-kotlin.2026-09-22.before-protected-set.md`다. 이 PR을 시작하기 전에 남긴 사본 `~/Documents/domain-modeling-kotlin.2026-09-21.before-entity-visibility.md`는 5절 불릿과 7절 두 줄이 추가되기 전 상태이고 그대로 둔다.
- 변경 기록: `docs/wiki/changes.md`에 남기라는 지시가 있었으나 그 파일은 [drop-changes-log](2026-09-21-drop-changes-log.md)가 삭제했다. 사본 기록을 PR spec 한 곳으로 모은 결정이라 되살리지 않고 이 spec에 남겼다.
- multi-review: 미실행. 제출 전에 실행한다.
