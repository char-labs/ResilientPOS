# PR spec: BaseEntity에서 @Version을 걷어내고 필요한 엔티티만 직접 선언하기

## 목적
`platform.BaseEntity`가 모든 엔티티에 `@Version version`을 준다. 낙관적 잠금이 의미 있는 곳은 aggregate 루트인 `t_order`뿐인데 `t_order_line`과 `t_order_checkout`에도 `version` 컬럼이 있다. `t_order_line`은 루트를 통해서만 바뀌고, `t_order_checkout`은 넣기만 해서 값이 0에 머문다.

공통 기반에 넣어 두면 "왜 모든 테이블에 version이 있는가"를 다른 개발자에게 매번 설명해야 하고, 필요 없는 곳에도 있다는 사실이 소통 비용이 된다. 필요한 엔티티에만 명시적으로 붙어 있으면 그 선언이 의도를 말해 준다(2026-09-21 사용자 결정).

기대 결과: `BaseEntity`는 감사 시각과 soft delete만 준다. `@Version`은 `OrderEntity`가 직접 선언한다. `t_order_line.version`과 `t_order_checkout.version` 컬럼은 없어진다. 새 행은 전과 같이 추가 SELECT 없이 INSERT 한 번으로 들어간다.

## 범위
- `BaseEntity`에서 `@Version version`을 뺀다. 기반 클래스를 버전 있는 것과 없는 것 둘로 나누지 않는다.
- `OrderEntity`가 `@Version var version: Long?`을 직접 선언한다. nullable인 이유(Spring Data의 새 엔티티 판별)는 그 필드의 KDoc으로 옮긴다. `t_order`의 컬럼과 동작은 그대로다.
- `V004__order_drop_child_version.sql`이 `t_order_line.version`과 `t_order_checkout.version`을 삭제한다.
- 새 엔티티 판별. 이 저장소는 도메인 ID(UUID v7)를 저장 전에 만든다. Spring Data의 `save`·`saveAll`은 ID가 채워져 있으면 기존 행으로 보고 `merge`해서 SELECT가 한 번 더 나간다. 지금은 nullable `@Version`이 null이면 새 엔티티로 판단해 `persist`로 간다. `@Version`이 없어지는 두 엔티티에는 다른 방법이 필요하다. 아래 세 후보 가운데 첫째를 고른다.

| 후보 | 내용 | 판단 |
|---|---|---|
| 어댑터가 직접 persist | `OrderPersistenceAdapter`가 새 자식 행을 `EntityManager.persist`로 넣는다. 기존 행은 같은 트랜잭션에서 조회한 관리 엔티티라 `apply`만 하면 dirty checking이 UPDATE한다 | 선택. 어댑터는 이미 기존 행을 조회해 ID로 새 것과 구분하고 있어서 새 정보가 필요 없다. 엔티티는 바뀌지 않는다. "새 행은 INSERT"라는 의도가 호출부에 그대로 보이고 Spring Data의 판별 규칙에 기대지 않는다 |
| 엔티티가 Persistable 구현 | 엔티티가 새 엔티티 여부를 Spring Data에 직접 알려 준다 | 기각. 새 것인지 기억하는 transient 상태와 그것을 뒤집는 JPA callback(`@PostLoad`·`@PostPersist`)이 엔티티마다 필요하다. JPA callback을 직접 쓰지 않는다는 기존 규칙과 어긋난다. Kotlin에서는 인터페이스의 `getId`와 `id` 프로퍼티의 getter가 JVM 시그니처에서 충돌해 필드 이름도 바꿔야 한다. 어댑터가 이미 아는 사실을 엔티티에 한 번 더 들이는 셈이다 |
| merge의 추가 SELECT를 받아들임 | 코드를 그대로 둔다 | 기각. 새 자식 행마다 SELECT가 하나씩 더 나간다. "신규 저장은 INSERT 한 번"이라는 기존 테스트의 의도와 어긋난다 |

- 버전별 동작을 기억으로 단정하지 않는다. `@Version`을 뺀 뒤 `saveAll`이 실제로 SELECT를 더 내는지, persist로 바꾸면 없어지는지를 Hibernate 통계의 JDBC 문장 수(`prepareStatementCount`)로 측정하는 통합 테스트로 확인하고 결과 절에 적는다. 측정이 위 표의 전제와 다르면 구현 전에 이 spec에 범위 조정을 먼저 적는다.
- ~~선택한 방식의 결과로 트랜잭션 밖에서 `OrderRepository.save`를 부르면 새 자식 행에서 예외가 난다.~~ **범위 조정 1로 대체한다.** 실행해 보니 예외로 끝나는 것이 아니라 line 없는 주문 행이 남았고, 어댑터의 `save`가 트랜잭션을 보장하도록 바꿨다. 운영 경로가 Facade의 트랜잭션 안에서 부른다는 것은 그대로다.
- `OrderRequestCheckoutIntegrationTest`의 checkout 행 version 0 단언은 컬럼이 없어지므로 없앤다. 그 단언이 간접 증거로 삼던 "INSERT 한 번"은 새 `OrderInsertStatementCountIntegrationTest`가 문장 수로 직접 검사한다. `t_order`의 version 0 단언(`OrderCommandFacadeIntegrationTest`)과 `OrderVersionConflictIntegrationTest`는 그대로 통과해야 한다.
- 소유 모듈은 `platform`(`BaseEntity`)과 `order`(엔티티, 어댑터, 마이그레이션)다. Command, Query, Event, HTTP 계약은 바뀌지 않는다. 정합성 불변식의 변화는 스키마 변경 검토의 답 4와 6에 적는다. 실패·재시도·복구와 관측성은 바뀌지 않는다.

### 기준 브랜치
PR #34(`fix/commit-version-conflict-optimistic-lock`)가 같은 `BaseEntity.kt`의 KDoc과 시각 생성 방식, 같은 통합 테스트, 같은 wiki 절을 고치고 있고 아직 열려 있다(2026-09-21 확인. main과의 충돌은 `docs/wiki/changes.md`와 `docs/wiki/current-state.md` 두 문서뿐이다). 이 PR은 그 브랜치 위에 stacked PR로 올린다. #34가 머지될 때까지 `check_pr.py`의 `--base`는 `origin/fix/commit-version-conflict-optimistic-lock`이다. #34가 머지되면 사용자 확인을 받아 `origin/main` 위로 rebase하고 `--base origin/main`으로 다시 검사한다. #34의 브랜치는 #33 이전에서 갈라졌으므로 #33의 테스트까지 포함한 검증은 `origin/main`과 임시로 합친 결과에서 따로 돌린다.

### 범위 조정 1 (2026-09-21, 어댑터의 save가 트랜잭션을 보장한다)
위 범위는 "트랜잭션 밖에서 `save`를 부르면 새 자식 행에서 예외가 난다"까지만 적었다. 임시 테스트로 실행해 보니 결과가 그보다 나빴다. 주문 행은 `OrderJpaRepository.save`가 자기 트랜잭션으로 이미 커밋한 뒤라서, `persist`가 `InvalidDataAccessApiUsageException`(트랜잭션 없음)으로 실패하면 line이 하나도 없는 주문 행이 남는다. line 없는 주문은 도메인이 `EMPTY_ORDER`로 거절하는 상태다. 전에는 주문과 line이 따로 커밋되기는 해도 둘 다 저장됐으므로 이 PR이 새로 만드는 위험이고 여기서 막는다.
- 결정: `OrderPersistenceAdapter.save`에 `@Transactional`(기본 전파 `REQUIRED`)을 선언한다. Facade의 트랜잭션이 있으면 합류하고, 없으면 `save` 하나가 한 트랜잭션이 되어 주문과 자식 행이 함께 커밋되거나 함께 롤백된다. Facade가 트랜잭션을 소유한다는 원칙은 그대로이고 이 선언은 직접 호출에 대한 안전망이다.
- 버린 안: `MANDATORY`. 트랜잭션 없는 호출을 아무것도 쓰기 전에 거절하므로 역시 안전하지만, 지금까지 동작하던 직접 호출(`같은 terminal_id와 client_order_id는 DB unique가 거절한다` 테스트)을 금지로 바꿀 이유가 없다. 주석으로만 남기는 안은 위 결과 때문에 버렸다.
- 인수 조건 10·11을 본 목록에 더했다. 둘 다 `OrderCommandFacadeIntegrationTest`에 더하므로 변경 경로에 그 파일을 더한다.
- 제외 절의 "전파 규칙을 선언하는 것" 항목은 이 조정으로 대체한다.

## 제외
- `t_order`의 `@Version`과 `version` 컬럼. 유지한다. `RequestCheckout`은 주문 행 잠금을 쓰지만 unique 제약과 `@Version`이 마지막 방어선이라는 기존 결정은 그대로다.
- 루트 `OrderEntity`의 저장 경로. 지금처럼 `OrderJpaRepository.save`를 쓴다. nullable `@Version`이 있어서 Spring Data가 새 엔티티를 판별한다.
- line만 바꾸는 저장이 루트의 version을 올리지 않는다는 알려진 빈틈. `ChangeOrderLine`을 넣는 PR이 같은 잠금 조회를 쓰거나 version 증가를 함께 정한다는 기존 계획 그대로다.
- Event Publication Registry의 `event_publication`. 라이브러리 엔티티라 `BaseEntity`와 무관하다.
- JDBC batch 설정과 Event Publication Registry. 둘 다 `OrderInsertStatementCountIntegrationTest`의 기대값을 바꾼다. batch를 켜면 같은 SQL의 INSERT 여러 개가 문장 하나로 세어져 기대값이 줄고, EPR이 들어오면 같은 트랜잭션에 `event_publication` INSERT가 늘어난다. 둘 중 하나를 도입하는 PR이 이 테스트의 기대값을 함께 고친다. 그때 실패 메시지의 "merge로 빠진 것이다"도 맞지 않으므로 같이 본다.

## 관련 문서
없음.

## 변경 경로
- src/main/kotlin/com/resilientpos/platform/BaseEntity.kt
- src/main/kotlin/com/resilientpos/order/internal/adapter/persistence/command/OrderEntity.kt
- src/main/kotlin/com/resilientpos/order/internal/adapter/persistence/command/OrderPersistenceAdapter.kt
- src/main/kotlin/com/resilientpos/order/internal/domain/OrderRepository.kt
- src/main/resources/db/migration/V004__order_drop_child_version.sql
- src/test/kotlin/com/resilientpos/SchemaMigrationTest.kt
- src/test/kotlin/com/resilientpos/order/internal/application/OrderInsertStatementCountIntegrationTest.kt
- src/test/kotlin/com/resilientpos/order/internal/application/OrderCommandFacadeIntegrationTest.kt
- src/test/kotlin/com/resilientpos/order/internal/application/OrderRequestCheckoutIntegrationTest.kt
- docs/wiki/domain-model.md
- docs/wiki/current-state.md
- docs/wiki/changes.md

## 인수 조건
1. `BaseEntity`에 `@Version`과 `version`이 없고 `@MappedSuperclass` 기반 클래스는 하나다.
2. `OrderEntity`가 `@Version var version: Long?`을 직접 선언한다. `OrderVersionConflictIntegrationTest`와 `OrderCommandFacadeIntegrationTest`의 "신규 저장은 INSERT 한 번" 테스트가 고치지 않은 채로 통과한다.
3. line 두 개짜리 새 주문을 저장하는 트랜잭션의 JDBC 문장 수는 주문 조회 1, 주문 INSERT 1, line INSERT 2의 합이다. 새 line에 대한 SELECT가 없다.
4. 기존 주문에 line 하나를 바꾸고 하나를 더해 저장하는 트랜잭션에서 새 line은 SELECT 없이 INSERT 한 번이다.
5. checkout을 더한 주문을 저장하는 구간의 JDBC 문장 수는 line 조회 1, checkout 조회 1, checkout INSERT 1, 주문 UPDATE 1의 합이다. 새 checkout에 대한 SELECT가 없다.
6. 조건 3부터 5의 테스트는 `@Version`만 빼고 어댑터를 고치지 않은 상태에서 실패한다. 측정이 merge 경로를 실제로 잡아낸다는 증거로 TDD 검증 기록에 남긴다.
7. `V004`가 적용되고, `public` 스키마에서 `version` 컬럼을 가진 업무 테이블은 `t_order`뿐이다.
8. `./gradlew test` 전체가 통과한다. `origin/main`과 임시로 합친 결과에서도 전체가 통과한다.
9. wiki 세 문서와 볼트 규칙이 새 결정을 서술한다. `docs/wiki/current-state.md`의 테스트 개수 표도 이 PR이 더한 테스트를 반영한다.
10. (범위 조정 1) 트랜잭션 밖에서 부른 `save`는 주문과 line을 함께 저장한다.
11. (범위 조정 1) 트랜잭션 밖에서 부른 `save`가 line INSERT에서 실패하면 주문 행도 남지 않는다.

## 스키마 변경 검토
[도메인 모델](../wiki/domain-model.md) 4.7절 질문의 답이다. 컬럼을 빼는 변경이라 필요성 질문은 "이 컬럼이 없어도 되는가"로 읽는다.

1. 도출 가능성: 두 컬럼은 Hibernate가 낙관적 잠금에 쓰는 카운터다. 업무 사실이 아니어서 다른 컬럼에서 도출할 대상이 없고, 없애도 잃는 업무 정보가 없다.
2. 읽는 코드: 업무 코드는 두 컬럼을 읽지 않는다. `t_order_line.version`은 Hibernate가 line을 UPDATE할 때 where 조건과 증가에만 썼다. `t_order_checkout.version`은 checkout을 갱신·삭제하지 않으므로 INSERT 때 0이 들어간 뒤로 쓰이지 않았다. 테스트 한 곳(`OrderRequestCheckoutIntegrationTest`의 version 0 단언)이 "INSERT 한 번"의 간접 증거로 읽었고, 그 단언을 없애는 대신 새 `OrderInsertStatementCountIntegrationTest`가 문장 수로 직접 검사한다.
3. 나중에 채울 수 있는가: 있다. 낙관적 잠금은 현재 값만 비교하므로 과거 값에 의미가 없다. 다시 필요해지면 `bigint not null default 0`으로 더하고 그 엔티티에 `@Version`을 선언하면 된다.
4. 마지막 방어선인가: 아니다. `t_order_checkout`의 방어선은 unique `(order_id, checkout_no)`이고 그대로다. `t_order_line.version`이 막던 것은 같은 line 행을 두 트랜잭션이 동시에 고치는 경우 하나였다. 이 PR 뒤에는 그 경우 뒤에 커밋한 쪽이 조용히 이긴다. 받아들이는 근거는 둘이다. 지금 기존 주문의 line을 고치는 명령이 없다(어댑터의 기능은 있고 통합 테스트만 쓴다). 그리고 행 단위 version은 aggregate 불변식을 지키지 못한다. 두 트랜잭션이 서로 다른 line을 고치면 둘 다 통과해서 합계와 확정 금액이 어긋날 수 있다. aggregate의 방어선은 루트에 있어야 하고(`t_order`의 행 잠금과 `@Version`), line 변경 명령을 넣는 PR이 그 잠금 조회를 쓰거나 루트 version 증가를 정한다. `t_order`의 `@Version`, unique `(terminal_id, client_order_id)`는 건드리지 않는다. soft delete와 unique의 관계도 바뀌지 않는다. 얻는 것도 있다. `merge`는 다른 주문에 속한 line ID를 만나면 그 행을 읽어 `order_id`까지 덮어쓰지만 `persist`는 PK 위반으로 실패한다. 지금 그 입력을 만드는 명령이 없어서 이 PR이 막은 버그는 아니고, line 변경 명령이 생길 때의 출발점이 더 안전해진 것이다.
5. 정본: 해당 없음. 사본이나 중복 저장이 생기지 않는다.
6. check-then-act: `RequestCheckout`은 `status`를 읽고 전이를 정한다. 경합은 주문 행 잠금이 막고 그 뒤에 루트의 `@Version`과 unique `(order_id, checkout_no)`가 있다. 세 가지 모두 그대로다. 자식 행의 version은 이 경로의 방어선이었던 적이 없다. checkout은 INSERT뿐이고 line은 이 경로에서 바뀌지 않는다. "자식 행만 바뀌면 루트의 version이 오르지 않는다"는 4.7절의 주의는 계속 유효하고, 자식에 version이 없어졌으므로 더 분명해진다. 한 aggregate를 여러 쿼리로 읽는 경로에 대한 답은 [order-request-checkout-persistence](2026-09-21-order-request-checkout-persistence.md)의 답 6과 같고 이 PR이 바꾸지 않는다. 쓰기 경로는 `findByIdForUpdate`가 주문 행을 먼저 잠그므로 line·checkout 조회가 다른 커밋을 섞어 읽지 않는다. 잠금 없는 `findById`는 READ COMMITTED에서 세 쿼리가 서로 다른 커밋을 볼 수 있지만 그 결과로 전이를 판단하지 않는다. 자식 행의 version은 이 경로의 방어선이었던 적이 없다.
7. 락과 크기: `ALTER TABLE ... DROP COLUMN`은 `ACCESS EXCLUSIVE`를 잡지만 카탈로그에서 컬럼을 숨길 뿐 테이블을 재작성하지 않는다. 배포된 환경이 없어서 대상은 개발자 로컬 DB와 매번 빈 DB로 시작하는 테스트뿐이다. 그래도 4.7절의 지침대로 마이그레이션에 `set local lock_timeout`을 둔다.
8. 이전 버전 호환: 컬럼 삭제는 이전 버전의 기동과 쓰기를 즉시 깨뜨리므로 원칙은 "그 컬럼을 쓰는 코드가 배포에서 빠진 다음 PR에서"다. 이번에는 한 PR로 한다. 보호할 이전 버전 인스턴스가 없기 때문이다. 근거는 둘이다. 사용자가 2026-09-21 이 작업을 지시하면서 아직 배포된 환경이 없다고 밝혔고, [현재 상태](../wiki/current-state.md)에도 "운영 배포 설정과 커넥션 풀 튜닝은 아직 없다"고 적혀 있다. 뒤의 문장만으로는 실행 중인 인스턴스가 없다는 것까지 증명되지 않으므로 앞의 사실이 실제 근거다. 지금 이 스키마를 쓰는 곳은 개발자 로컬 DB와 매번 빈 DB로 시작하는 테스트뿐이고, 로컬 DB에서는 `V004`가 순방향으로 적용된다. 나누는 쪽의 비용은 이렇다. 엔티티에서 `version`을 뺀 코드는 `version bigint not null` 컬럼에 INSERT할 수 없다. 그래서 첫 PR에 default를 더하거나 not null을 푸는 마이그레이션이 하나 필요하고 둘째 PR이 컬럼을 지운다. 지킬 대상이 없는데 마이그레이션 둘과 PR 둘을 쓰게 된다. 이 판단은 배포 환경이 없는 지금에만 성립한다. 배포 환경이 생긴 뒤의 컬럼 삭제는 4.7절 질문 8의 순서를 따른다.
9. 이벤트·캐시: 두 컬럼은 도메인 이벤트 payload와 DTO에 실리지 않는다. 캐시는 없다.

마이그레이션 번호: 기준 브랜치의 마지막은 `V003`이라 `V004`다(2026-09-21 `git fetch` 뒤 확인). Event Publication Registry PR의 spec도 `V004`를 예고했고 그 브랜치에는 아직 마이그레이션 파일이 없다. 먼저 머지되는 쪽이 `V004`를 갖고 나머지가 다음 번호로 바꾼다. 중복은 `check_pr.py`와 PR 워크플로의 버전 게이트가 막는다. 머지 직전에 다시 확인한다.
- 대조 기록: code-reviewer 에이전트(구현자 아님), 2026-09-21, 불일치 항목: 없음. 답 1~9와 JPA `validate` 관점을 DDL·엔티티·컬럼을 읽고 쓰는 코드와 대조했다. 리뷰어가 확인하지 못한 것 둘: 읽기 전용 도구라 git 이력이 필요한 마이그레이션 번호 서술(구현자가 `git fetch` 뒤 확인했다)과, 제거된 매핑의 과거 런타임 동작(soft delete가 version을 올렸는지). 뒤의 것은 확인된 범위로 답 2의 서술을 좁혔다. 리뷰어가 지적한 서술 차이(문장 수 검사의 실제 위치)와 테스트 견고성(version 컬럼 조회의 정렬)은 반영했다.

## 검증
- `./gradlew test --tests 'com.resilientpos.order.internal.application.OrderInsertStatementCountIntegrationTest'`: 인수 조건 3부터 6.
- `./gradlew test --tests 'com.resilientpos.SchemaMigrationTest'`: 인수 조건 7.
- `./gradlew test`: 전체. Docker가 필요하다.
- `origin/main`과 임시로 합친 작업 트리에서 `./gradlew test`: #33의 Controller·API 테스트까지 포함한 확인. 합친 결과는 커밋하거나 push하지 않는다.
- `python3 scripts/check_pr.py --base origin/fix/commit-version-conflict-optimistic-lock --worktree`(개발 중)와 `--worktree` 없는 제출 전 검사.
- 실패 시나리오: `@Version`만 빼고 어댑터를 그대로 두면 문장 수 테스트가 실패해야 한다. 마이그레이션 없이 엔티티만 바꾸면 line INSERT가 not null 위반으로 실패한다.
- 🔴 PR이다(마이그레이션, JPA 매핑 파일). 제출 전에 resilientpos-multi-review를 실행하고, 구현자가 아닌 리뷰어가 스키마 변경 검토의 답을 DDL·엔티티·코드와 대조해 대조 기록을 남긴다.

## Wiki 영향
- [도메인 모델](../wiki/domain-model.md) 4.2절: `BaseEntity`가 주는 것에서 `@Version`을 빼고, 낙관적 잠금이 필요한 엔티티만 직접 선언한다는 규칙과 그 이유, `@Version` 없는 엔티티의 새 행은 어댑터가 persist한다는 규칙을 적는다. 4.7절 질문 6의 서술은 자식 행에 version이 없다는 사실에 맞춘다.
- [현재 상태](../wiki/current-state.md): `platform`의 `BaseEntity` 공통 컬럼 서술에서 `version`을 빼고 상속하는 엔티티 목록을 맞춘다.
- [변경 기록](../wiki/changes.md): 결정과 사용자가 밝힌 이유, 새 엔티티 판별 방식의 선택, 한 PR로 컬럼을 지운 근거를 적는다.
- 볼트 `rules/domain-modeling-kotlin.md` 5절의 `BaseEntity` 서술. 볼트는 저장소 밖이고 git으로 관리되지 않으므로 수정 전 사본을 `~/Documents`에 남기고 변경 기록에 적는다.
- 요구사항 영향: 제품 요구사항은 바뀌지 않는다. 저장 형식(두 테이블의 컬럼)과 엔티티 작성 규칙이 바뀐다.

## 분할 계획
예상은 파일 11개, 300줄 안쪽이다. 경고 한도(20개, 600줄)에 닿지 않는다. 컬럼 삭제를 별도 PR로 나누지 않는 이유는 스키마 변경 검토의 답 8에 있다. 후속 PR은 없다.

## TDD 검증 기록
명령의 공통 앞부분은 `./gradlew test --tests`다. 문장 수 테스트 세 개는 `@Version`이 아직 있는 코드에서 먼저 통과시켜 기준선으로 커밋했다. RED는 `BaseEntity`에서 `@Version`을 빼고 `V004`를 더한 뒤 어댑터의 `saveAll`을 그대로 둔 상태다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 새 주문 저장은 주문 조회 한 번과 주문과 line의 INSERT뿐이다 | `'*OrderInsertStatementCountIntegrationTest'` 기대 4, 실제 6. 새 line 둘에 SELECT가 하나씩 붙었다 | 어댑터가 `persist`. 같은 명령 통과, 4 | `./gradlew test` 80개 통과 |
| 기존 주문에 line을 바꾸고 더해 저장하면 새 line은 SELECT 없이 INSERT 한 번이다 | 같은 명령. 기대 4, 실제 5 | 같은 명령 통과, 4 | 위와 같음 |
| checkout을 더한 주문을 저장하면 새 checkout은 SELECT 없이 INSERT 한 번이다 | 같은 명령. 기대 4, 실제 5 | 같은 명령 통과, 4 | 위와 같음 |
| baseline 마이그레이션이 성공으로 기록된다, 낙관적 잠금 version 컬럼은 t_order에만 있다 | `'com.resilientpos.SchemaMigrationTest'` 2개 실패. `V004`가 없고 세 테이블 모두 `version`이 있다 | `V004` 추가 뒤 4개 통과 | 위와 같음 |
| 트랜잭션 밖에서 부른 save도 주문과 line을 함께 저장한다 | `'*OrderCommandFacadeIntegrationTest'` `InvalidDataAccessApiUsageException`(트랜잭션 없는 persist) | 어댑터 `save`에 `@Transactional`. 11개 통과 | 위와 같음 |
| 트랜잭션 밖에서 부른 save가 line INSERT에서 실패하면 주문 행도 남지 않는다 | 같은 명령. 기대한 `DataIntegrityViolationException` 대신 `InvalidDataAccessApiUsageException` | 같은 명령 통과. 주문 행 0, line 행 0 | 위와 같음 |

## 결과
- 인수 조건 1·2: `BaseEntity`에 `version`이 없고 `OrderEntity`가 `@Version var version: Long?`을 선언한다. `OrderVersionConflictIntegrationTest`와 "신규 저장은 INSERT 한 번" 테스트는 고치지 않고 통과했다.
- 인수 조건 3~6: 범위의 표가 전제한 동작이 Spring Boot 4.1.1(Hibernate 7.4.5)에서 그대로 관측됐다. `@Version` 없는 새 엔티티를 `saveAll`에 넘기면 새 행마다 SELECT가 정확히 하나 더 나가고(4에서 6, 4에서 5, 4에서 5) `persist`로 바꾸면 4로 돌아온다. Hibernate 통계는 설정 없이 실행 중에 `isStatisticsEnabled`로 켜고 끌 수 있어서 테스트 컨텍스트를 따로 만들지 않았다.
- 인수 조건 7: `V004` 적용 뒤 `version` 컬럼을 가진 업무 테이블은 `t_order`뿐이다.
- 인수 조건 8: `./gradlew test` 80개 통과. `origin/main`(c317a1b, #33 포함)과 임시 worktree에서 합친 결과는 89개 통과. 합칠 때의 충돌은 `docs/wiki/changes.md`와 `docs/wiki/current-state.md` 두 문서뿐이었고 그 worktree는 커밋 없이 지웠다.
- 인수 조건 10·11(범위 조정 1): 트랜잭션 밖 `save`가 line 없는 주문 행을 남기는 것을 임시 테스트로 관측하고(주문 행 1, line 행 0) `@Transactional`로 막았다. 기존의 unique 위반 테스트는 트랜잭션 밖에서 `save`를 부르는 그대로 `DataIntegrityViolationException`을 받는다.
- 인수 조건 9: wiki 세 문서와 볼트 `rules/domain-modeling-kotlin.md` 5절을 고쳤다. 볼트의 수정 전 사본은 `~/Documents/domain-modeling-kotlin.2026-09-21.before-explicit-version.md`다.
- multi-review: Claude 89점, Codex 90점(`codex exec` 직접 실행), Gemini는 CLI가 없어 미실행. 둘 다 막는 코드 결함은 없다고 판정했고 Codex가 막는 항목으로 든 것은 제출 전 대조 기록 미기재였다. `reviewer-codex` 에이전트가 Codex 출력 없이 먼저 돌아온 결과가 한 번 있었는데, 출력 파일이 없어 점수로 쓰지 않고 CLI를 직접 실행해 받은 결과만 기록했다.
- 리뷰 반영: (1) `current-state.md`의 테스트 개수 표가 74개에서 멈춰 있어 80개와 내역으로 고쳤다. (2) 어댑터 `save`의 트랜잭션 계약을 port `OrderRepository.save`의 KDoc에 적었다. `findByIdForUpdate`에만 계약이 있어 비대칭이었다. (3) 4.7절 질문 6의 "한 aggregate를 여러 쿼리로 읽는 경로" 항목이 답 6에서 빠져 있어 채웠다. (4) `persist`가 `merge`보다 안전해지는 점(다른 주문의 line ID를 만나면 덮어쓰지 않고 실패한다)을 답 4에 더했다. (5) JDBC batch와 Event Publication Registry가 문장 수 기대값을 바꾼다는 것을 제외 절에 적었다. (6) Hibernate 통계를 끌 때 이전 설정을 복원하고, 전역 누적값이라는 성질과 병렬 실행 시의 대응을 테스트 KDoc에 적었다. (7) `SchemaMigrationTest`의 version 컬럼 조회에 정렬을 줬다. (8) `OrderEntity`의 `@Version` KDoc에 루트 행이 실제 UPDATE될 때만 오른다는 제한을 적었다. (9) spec 범위의 트랜잭션 서술에 범위 조정 1로 대체됐음을 표시하고 인수 조건 10·11을 본 목록에 합쳤다. (10) 답 8의 배포 근거를 사용자가 밝힌 사실로 바꿨다. wiki 문장만으로는 실행 중인 인스턴스가 없다는 것까지 증명되지 않는다.
- 확인: 리뷰가 조건부 위험으로 든 flush 순서를 SQL 로그로 확인했다. 호출 순서와 무관하게 새 자식 행의 INSERT가 soft delete UPDATE보다 먼저 나간다. 지금은 `t_order_line`에 PK 말고 unique가 없어 무해하고, partial unique를 더할 때 볼 사실이라 어댑터 KDoc에 적었다.
- 반영하지 않은 것: 문장 수 측정을 `StatementInspector`로 바꾸는 제안(두 리뷰 모두 지금은 불안정하지 않다고 봤고 병렬 실행 설정이 없다. 바꾸면 이 PR의 범위가 넓어진다), 4.7절 §7에 `CONCURRENTLY`와 `SET LOCAL`의 관계를 더하는 제안(이 PR이 그 조합을 만들지 않는다), soft delete된 ID의 재삽입 계약(이 PR의 회귀가 아니고 도달 경로가 없다. `ChangeOrderLine` PR의 선행 조건이다).
- 고치지 않은 것: `OrderLineEntity`, `OrderCheckoutEntity`, 두 Spring Data repository. `saveAll`을 부르는 곳은 없어졌지만 repository는 조회와 삭제에 계속 쓴다.
