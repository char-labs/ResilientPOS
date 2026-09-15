# PR spec: Spring Modulith 모듈 골격과 경계 검증

## 목적
도메인 구현을 시작하기 전에 bounded context 7개를 패키지 경계로 선언하고, 경계 위반을 사람이 아니라 테스트가 막는 상태를 만든다. 이후 모든 도메인 PR은 이 경계 위에서만 코드를 추가한다.

## 범위
- Spring Modulith 2.1.1 도입. BOM 한 곳에서 버전을 관리하고 `spring-modulith-starter-core`(구현)와 `spring-modulith-starter-test`(테스트)만 선언한다.
- `com.resilientpos` 아래 application module 7개를 `package-info.java`의 `@ApplicationModule`로 선언한다: `store`, `terminal`, `order`, `payment`, `sync`, `reconciliation`, `fulfillment`.
- `ApplicationModules.of(ResilientPosApplication::class.java).verify()` 아키텍처 테스트를 추가한다.
- 소유 모듈: 없음(전 모듈 공통 구조). 계약/불변식/복구/관측성: 이 PR은 업무 기능이 아니라 구조 선언이므로 도메인 계약과 복구 경로를 만들지 않는다. 이 PR이 강제하는 불변식은 "타 모듈의 `internal` 패키지를 import하면 빌드가 실패한다" 하나다.

## 제외
- 도메인 타입, aggregate, entity, controller, DB 스키마. 각 모듈은 `package-info.java`만 가진 빈 모듈로 시작한다.
- `foundation`, `platform` 패키지. 실제 타입이 생기는 후속 PR에서 만든다.
- Event Publication Registry(`spring-modulith-starter-jpa`). 도메인 이벤트가 처음 생기는 checkout PR에서 도입한다.
- `spring-modulith-starter-insight`, OpenTelemetry, Kafka, Redis, Kotlin JDSL, Fixture Monkey, REST Docs.
- Flyway와 PostgreSQL 프로필 설정.
- `@NamedInterface` 선언. public surface가 실제로 커진 뒤 추가한다.

## 관련 문서
없음. 원문 근거는 [개발 가이드 1.1 Public API rule / 2.4 Architecture test](../references/03_Development_Guide.md)와 [도메인 청사진 2절 bounded context 표](../references/05_Domain_Development_Blueprint.md).

## 변경 경로
- build.gradle.kts
- src/main/java/com/resilientpos/*/package-info.java
- src/test/kotlin/com/resilientpos/**
- docs/spec/2026-09-15-module-skeleton.md
- docs/wiki/current-state.md
- docs/wiki/changes.md

## 인수 조건
1. `./gradlew test`가 통과하고, 아키텍처 테스트가 모듈 7개를 인식한다.
2. `order`에서 `payment.internal`을 import하는 코드를 넣으면 아키텍처 테스트가 실패한다. 제거하면 다시 통과한다.
3. Modulith 의존성은 BOM 1개 + starter 2개를 넘지 않는다. actuator·JPA·insight 관련 선언을 이 PR에서 늘리지 않는다.
4. `python3 scripts/check_pr.py --base main`이 통과한다.

### Checkpoint와 검증 명령
| Checkpoint | 검증 명령 | 의존 |
|---|---|---|
| modulith-dependency | `./gradlew dependencies --configuration runtimeClasspath` | 없음 |
| module-verify | `./gradlew test --tests 'com.resilientpos.ModuleStructureTest'` | modulith-dependency |
| boundary-violation | 위반 코드 주입 후 같은 명령이 실패하는지 수동 1회 확인 | module-verify |

## 검증
```sh
./gradlew test
python3 scripts/check_pr.py --base main --worktree
```
- 정상: 모듈 7개가 인식되고 verify가 통과한다.
- 실패 시나리오: `order` 패키지에 `payment.internal` 참조를 임시로 넣고 `./gradlew test`가 실패하는 것을 확인한 뒤 되돌린다. 실패 메시지와 되돌린 결과를 아래 TDD 표에 적는다.

## Wiki 영향
- [현재 상태](../wiki/current-state.md): "도메인 모듈은 아직 구현되지 않았다", "Spring Modulith는 설계 방향이며 현재 빌드 의존성에는 없다"를 실제 도입 상태로 갱신한다.
- [변경 기록](../wiki/changes.md): 이 spec 링크와 요구사항 영향(제품 요구사항 무변경, 구조 선언만)을 남긴다.

## 분할 계획
예상 규모: build 설정 약 5줄, `package-info.java` 7개 약 60줄, 테스트 약 20줄, 문서 약 130줄로 총 220줄 내외. 경고 한도(600줄) 안이다.
경고가 나오면 문서 갱신을 남기고 `package-info` 선언 일부를 후속 PR로 옮긴다. 후속 PR은 순서대로 foundation 값 타입(Money·UUID v7) → Flyway/PostgreSQL 기반 → order `CreateOrder` 수직 절단이다.

## TDD 검증 기록
업무 동작 변경이 아니라 구조 선언이므로 도메인 단위 테스트는 없다. 대신 아키텍처 테스트가 실제로 위반을 잡는지 위반 코드 주입으로 RED를 관측한다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| `모듈은 다른 모듈의 internal 패키지에 의존하지 않는다` | `./gradlew test` — `order`에 `payment.internal.PaymentInternalProbe` 참조를 주입해 실패. `Violations: Module 'order' depends on non-exposed type com.resilientpos.payment.internal.PaymentInternalProbe within module 'payment'!` | `./gradlew test` — 주입 코드 제거 후 통과 | `./gradlew test` — BUILD SUCCESSFUL, 3 tests |
| `bounded context 7개를 application module로 인식한다` | `./gradlew test` — `fulfillment/package-info.java`를 임시로 빼고 실행해 `AssertionFailedError`로 실패 | `./gradlew test` — 파일 복구 후 통과 | `./gradlew test` — BUILD SUCCESSFUL, 3 tests |

## 결과
`./gradlew test` BUILD SUCCESSFUL (3 tests: contextLoads 1개, 모듈 구조 2개). 인수 조건 4개를 모두 만족했다.

개발 중 조정한 두 가지를 기록한다.

1. **모듈 이름 접근자**: Spring Modulith 2.x에는 `ApplicationModule.getName()`이 없다. `getIdentifier()`가 이름을
   가지므로 테스트는 `it.identifier.toString()`으로 비교한다. 1.x 예제를 그대로 쓰면 컴파일되지 않는다.
2. **Kotlin의 `internal` 패키지**: 위반 주입 실험에서 `package com.resilientpos.payment.internal`이 Kotlin에서
   그대로 컴파일되는 것을 확인했다. 개발 가이드 1.1의 `internal` 하위 패키지 구조를 Kotlin으로 쓸 수 있다.

`@ApplicationModule`에 `allowedDependencies`를 선언하지 않았다. 모듈이 비어 있어 허용 목록이 근거 없는 추측이 되기
때문이다. 모듈 간 의존이 실제로 생기는 PR에서 해당 모듈부터 선언한다.

근거 기록: Spring Modulith 2.1.1의 `spring-modulith-core`가 `spring-boot-autoconfigure 4.1.1`·`spring-data-commons 4.1.1`에 대해 빌드되어 현재 Boot 4.1.1과 세대가 일치한다(Maven Central pom 확인). 1.x는 Boot 3 세대라 사용하지 않는다.
