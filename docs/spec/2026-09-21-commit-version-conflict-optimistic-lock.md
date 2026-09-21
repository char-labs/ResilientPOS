# PR spec: 커밋 시점의 버전 충돌을 낙관적 잠금 예외로 되돌리기

## 목적
행 잠금 없이 `@Version`에 기대는 쓰기가 커밋 시점에 버전 충돌을 만나면 낙관적 잠금 예외가 아니라 `JpaSystemException`이 난다. 이 타입은 Spring의 `ConcurrencyFailureException` 계열이 아니라서 HTTP에서 `500 INTERNAL_ERROR`가 되고, 단말은 다시 보내도 되는 오류인지 알 수 없다. 커밋 시점의 버전 충돌이 `OptimisticLockingFailureException` 계열로 나오게 고치고 통합 테스트로 고정한다.

## 범위
- 소유 모듈: `platform`(`BaseEntity`). Command·Query·Event와 스키마는 바뀌지 않는다.
- 원인(2026-09-21, 재현 테스트와 Hibernate 7.4.5 소스로 확인): `BaseEntity`의 `@CreationTimestamp`·`@UpdateTimestamp`가 `SourceType.DB`라서 두 컬럼이 실행 시점 generated 값이 된다. generated 값이 있는 엔티티는 `GeneratedValuesHelper.getGeneratedValuesDelegate`가 delegate를 붙이고(PostgreSQL에서는 `GetGeneratedKeysDelegate`, JDBC 드라이버가 `RETURNING`을 덧붙인다), `MutationExecutorSingleNonBatched`와 `MutationExecutorStandard`는 delegate가 있으면 `performMutation`만 부르고 갱신 행 수를 보는 `OperationResultChecker`를 거치지 않는다. 버전이 맞지 않아 0행이 갱신되면 `StaleObjectStateException` 대신 `GeneratedValuesHelper`가 "The database returned no natively generated values"라는 `HibernateException`을 던지고 Spring이 `JpaSystemException`으로 바꾼다.
- 알려진 이슈인지: Hibernate JIRA(`project = HHH`)를 오류 문구와 `UpdateTimestamp`·optimistic·generated·returning 조합으로 검색했고 같은 증상의 이슈를 찾지 못했다. hibernate-orm `main`의 `GeneratedValuesHelper`도 같은 코드다. 버전을 올려서 고쳐지는 문제가 아니다. `hibernate.jdbc.use_get_generated_keys=false`로 꺼도 PostgreSQL dialect가 `supportsUpdateReturning`이라 `InsertReturningDelegate`가 대신 붙어 같은 경로다. 설정으로 피할 수 없다.
- 고친 방식: `BaseEntity`의 두 시각을 `SourceType.VM`으로 바꾼다. 시각을 Hibernate가 JVM에서 만들어 파라미터로 바인딩하므로 generated 값 delegate가 붙지 않고, UPDATE가 0행이면 기존 검사가 `StaleObjectStateException`을 던진다. Spring은 이것을 `ObjectOptimisticLockingFailureException`(`OptimisticLockingFailureException` 계열)으로 바꾼다.
- 정합성 불변식: 버전 충돌이 난 커밋은 지금도 거절된다. 이 PR은 거절의 예외 타입만 바꾼다. 먼저 커밋한 쪽의 갱신만 남는다는 것을 테스트가 함께 확인한다.
- 달라지는 성질:
  - `created_at`·`updated_at`의 출처가 DB `current_timestamp`(트랜잭션 시작 시각)에서 애플리케이션 시각(flush 시각)이 된다. 도메인 이벤트의 `occurredAt`은 이미 `platform`의 `Clock`(UTC)에서 오므로 시각 출처가 애플리케이션 하나로 모인다. 인스턴스가 여럿이면 두 컬럼은 인스턴스 시계의 오차만큼 어긋날 수 있다. 두 컬럼은 감사용이고 지금 코드에서 읽는 곳이 없다. 순서 판정은 `version`과 sync의 sequence가 맡는다.
  - 한 INSERT의 `created_at`과 `updated_at`이 더는 같지 않다. Hibernate가 두 값을 따로 만들어서 마이크로초 단위로 어긋난다. "한 번도 갱신되지 않은 행"은 `version = 0`으로 판정한다.
  - generated 값 delegate가 없어지므로 `BaseEntity` 엔티티의 INSERT·UPDATE에서 `RETURNING`이 빠진다. delegate가 있는 엔티티는 JDBC batch 대상에서 빠지는데 그 제약도 함께 없어진다. batch 설정은 이 PR에서 켜지 않는다.
- 실패·재시도·복구: 커밋 시점 버전 충돌은 트랜잭션이 롤백된 상태로 `OptimisticLockingFailureException`이 호출자에게 나간다. HTTP 매핑은 PR #33(`feature/order-request-checkout-api`)의 `ApiControllerAdvice.handleConcurrency`가 `ConcurrencyFailureException` 계열을 `409 CONCURRENT_REQUEST`로 바꾸고, 그 PR의 `ApiAdviceTest`가 `OptimisticLockingFailureException`의 409를 고정한다. 이 PR은 영속 계층이 그 계열의 예외를 내는 것까지 고정한다. 두 PR이 모두 머지되면 커밋 시점 버전 충돌이 409가 된다.
- 관측성: 예외 타입이 바뀌므로 #33 머지 뒤에는 ERROR 로그의 `INTERNAL_ERROR`가 아니라 WARN 로그의 `errorCode=CONCURRENT_REQUEST`로 남는다. 이 PR에서 더하는 로그는 없다.

### 검토한 선택지
- `SourceType.VM`(채택): 두 줄 변경이고 마이그레이션이 없다. Hibernate의 표준 경로(행 수 검사)를 그대로 쓴다. 잃는 것은 위 "달라지는 성질"의 DB 시각과 한 INSERT의 두 시각 일치다. 둘 다 지금 기대는 코드가 없다.
- DB 시각 유지, 컬럼 default와 트리거: 테이블마다 트리거가 필요하고 앞으로 생기는 모든 테이블이 같은 짐을 진다. 엔티티의 `createdAt`·`updatedAt`은 저장 뒤에도 채워지지 않는다(다시 읽게 하면 같은 delegate가 돌아온다). 마이그레이션과 스키마 변경 검토가 필요하다. 감사 컬럼의 시각 출처를 지키려고 치르기에는 크다.
- DB 시각 유지, 트랜잭션마다 `select current_timestamp`를 한 번 읽어 쓰는 custom generator: PostgreSQL의 `current_timestamp`는 트랜잭션 안에서 고정이라 지금과 같은 값을 만들 수 있다. 그 대신 Hibernate SPI에 기대는 generator와 트랜잭션 단위 캐시를 직접 만들어야 하고 쓰기 트랜잭션마다 왕복이 하나 는다. 읽는 곳이 없는 컬럼에 새 패턴을 들이는 비용이 맞지 않는다.
- Hibernate의 generated 값 처리 옵션: 위 "알려진 이슈인지"에 적은 대로 설정으로는 피할 수 없다.
- advice에서 `JpaSystemException`을 메시지로 식별해 409로 바꾸기: Hibernate의 문구에 기대므로 버전이 바뀌면 조용히 500으로 돌아간다. 이 문구는 generated 값을 읽지 못한 모든 경우에 나오므로 버전 충돌만 뜻하지도 않는다. 버렸다.

## 제외
- HTTP 매핑(`409 CONCURRENT_REQUEST`)과 그 테스트. PR #33의 범위다.
- JDBC batch 설정(`hibernate.jdbc.batch_size`). 가능해졌을 뿐이고 켜는 것은 측정이 있는 별도 PR이다.
- Hibernate에 이슈를 올리는 일. 저장소 밖의 일이라 사용자에게 제안만 한다.
- 잠금 없이 `@Version`에 기대는 새 쓰기 경로(`ChangeOrderLine` 등). 이 PR은 그 경로가 생겼을 때 예외가 맞게 나오도록 바닥만 고친다.
- 애플리케이션 락(분산 락). Redis를 들일 때 다룬다.

## 관련 문서
없음. 선행 spec은 [order-create-order-persistence](2026-09-16-order-create-order-persistence.md)(`BaseEntity`를 `SourceType.DB`로 만든 결정), [order-request-checkout-persistence](2026-09-21-order-request-checkout-persistence.md)다. 결함을 관측한 기록은 PR #33의 spec `2026-09-21-order-request-checkout-api.md`의 "범위 조정" 절에 있다.

## 변경 경로
- src/main/kotlin/com/resilientpos/platform/BaseEntity.kt
- src/test/kotlin/com/resilientpos/order/internal/application/**
- docs/spec/2026-09-21-commit-version-conflict-optimistic-lock.md
- docs/wiki/current-state.md
- docs/wiki/domain-model.md
- docs/wiki/changes.md

## 인수 조건
1. 통합 테스트: 한 트랜잭션이 잠금 없이 주문을 읽은 뒤 다른 트랜잭션이 같은 주문의 `requestCheckout`을 커밋하고, 앞 트랜잭션이 읽어 둔 버전으로 루트만 갱신해 커밋하면 `OptimisticLockingFailureException` 계열의 예외로 끝난다. `t_order.version`은 1이고 먼저 커밋한 갱신만 남는다.
2. 신규 저장은 INSERT 한 번이다(`version`이 0). `created_at`과 `updated_at`이 채워지고 `updated_at`이 `created_at`보다 앞서지 않으며 `deleted_at`은 비어 있다.
3. `requestCheckout`으로 `t_order`가 갱신되면 `created_at`은 그대로이고 `updated_at`은 `created_at`보다 뒤다.
4. 나머지 기존 테스트가 그대로 통과한다.

## 검증
- 대상 테스트: `./gradlew test --tests 'com.resilientpos.order.internal.application.*'`
- 전체 회귀: `./gradlew test` (Docker 필요)
- 하네스: `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전 `--worktree` 없이 한 번 더.
- 정상 시나리오: 인수 조건 2·3. 실패 시나리오: 인수 조건 1.
- `BaseEntity`는 `@MappedSuperclass` 파일이라 PR 템플릿의 사실 기준으로 🔴이다. 제출 전에 multi-review를 돌린다.

## Wiki 영향
- [현재 상태](../wiki/current-state.md): `platform` 문단에 시각 출처와 커밋 시점 버전 충돌의 예외 타입을 적고 테스트 수를 고친다. PR #33이 먼저 머지되면 그 PR이 더한 "커밋 시점의 버전 충돌은 이 매핑에 닿지 않는다 ... 알려진 빈틈이다" 서술을 이 PR에서 고친다. 이 PR이 먼저 머지되면 #33이 그 서술을 뺀다.
- [도메인 모델](../wiki/domain-model.md) 4.2절: `BaseEntity` 서술의 "DB 시각"을 애플리케이션 시각으로 고치고 이유를 적는다.
- [변경 기록](../wiki/changes.md): 이 spec 링크와 결정, 볼트 규칙 수정 기록을 적는다.
- 볼트 `rules/domain-modeling-kotlin.md` 5절의 `BaseEntity` 서술("DB 시각")도 고친다. 볼트는 저장소 밖이고 git으로 관리되지 않으므로 수정 전 사본을 `~/Documents`에 남긴다.

## 분할 계획
예상 규모는 구현 2줄과 KDoc, 테스트 약 90줄, 문서 약 120줄로 총 220줄·6파일 내외다. 경고 한도(600줄·20파일) 안이다. 후속 PR은 없다.

## TDD 검증 기록

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 조건 1: 커밋 시점 버전 충돌은 낙관적 잠금 예외다 | 미실행 | 미실행 | 미실행 |
| 인수 조건 2: 신규 저장의 시각 컬럼 | 미실행 | 미실행 | 미실행 |
| 인수 조건 3: 갱신의 시각 컬럼 | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
