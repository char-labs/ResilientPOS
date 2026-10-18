# PR spec: 의존성 방향 가드와 Gradle 분할 조건 기록

## 목적
모듈 경계는 테스트가 막지만 의존성 방향은 아직 아무도 막지 않는다. 단일 소스셋이라 모든 starter가 한 classpath에 있어서 `foundation`에 JPA 애너테이션을 붙여도 빌드가 통과한다. 프레임워크 의존이 0건인 지금 규칙을 고정한다. 함께 Gradle subproject 분할을 미루는 조건을 기록해 결정으로 남긴다.

## 범위
- `foundation`의 프로덕션 코드가 Spring, JPA, Servlet, Jackson, Kafka에 의존하지 않는지 검사하는 ArchUnit 테스트를 추가한다.
- ArchUnit을 테스트 의존성으로 명시 선언한다. 지금은 `spring-modulith-core`를 통해 우연히 classpath에 있을 뿐이다.
- Gradle 단일 프로젝트를 유지하는 근거와 분할 트리거 조건을 wiki에 기록한다.
- 소유 모듈: 없음. 저장소 전체의 구조 규칙이다. 계약: 개발 가이드 2.1 의존성 표의 `foundation` 행.
- 복구·관측성: 해당 없다. 빌드 시점 검사라 실행 경로와 재시도가 없다.

## 제외
- Gradle subproject 분할 자체. 트리거 조건을 충족하면 별도 PR로 한다.
- `버전 카탈로그`와 `build-logic` convention plugin. 분할과 함께 도입한다.
- 도메인 레이어 규칙(`internal/domain`이 Spring에 의존하지 않는다). 해당 패키지가 아직 없어 검사 대상이 0건이다.
  빈 규칙을 미리 두면 활성 여부를 알 수 없으므로 첫 도메인 PR에서 같은 파일에 추가한다.
- 모듈별 허용 의존 목록(`allowedDependencies`). 모듈이 비어 있어 근거 없는 추측이 된다.

## 관련 문서
없음. 원문 근거는 [개발 가이드 1절 레이아웃과 2.1 의존성 governance](../references/03_Development_Guide.md), 선행 PR은 [foundation-value-types](2026-09-15-foundation-value-types.md)다.

## 변경 경로
- build.gradle.kts
- src/test/kotlin/com/resilientpos/**
- docs/spec/2026-09-15-dependency-direction-guard.md
- docs/wiki/current-state.md
- docs/wiki/changes.md

## 인수 조건
1. `foundation`의 프로덕션 클래스가 Spring, JPA, Servlet, Jackson, Kafka 패키지에 의존하면 테스트가 실패한다.
2. 위반을 일부러 넣어 실패를 확인하고 되돌린다. 규칙이 통과만 하는 상태로 두지 않는다.
3. 모듈 선언용 `package-info`의 Modulith 애너테이션은 위반으로 보지 않는다. 코드가 아니라 모듈 메타데이터다.
4. ArchUnit이 `build.gradle.kts`에 명시 선언되고 버전은 현재 해석된 값과 일치한다.
5. wiki에 Gradle 단일 프로젝트 유지 근거와 분할 트리거 조건이 기록된다.
6. `python3 scripts/check_pr.py --base feature/foundation-value-types`가 통과한다.

### Checkpoint와 검증 명령
| Checkpoint | 검증 명령 | 의존 |
|---|---|---|
| archunit-dependency | `./gradlew dependencies --configuration testRuntimeClasspath` | 없음 |
| foundation-guard | `./gradlew test --tests 'com.resilientpos.DependencyDirectionTest'` | archunit-dependency |
| violation-probe | 위반 주입 후 같은 명령이 실패하는지 확인하고 되돌린다 | foundation-guard |

## 검증
```sh
./gradlew test
python3 scripts/check_pr.py --base feature/foundation-value-types --worktree
```
- 정상: 현재 코드에 위반이 없어 통과한다.
- 실패: `foundation`에 JPA 애너테이션을 임시로 붙여 규칙이 실패하는지 확인한다.

## Wiki 영향
- 현재 상태(`docs/wiki/current-state.md`, 볼트): Gradle 구조와 분할 조건, 의존성 가드의 적용 범위를 추가한다.
- 변경 기록(`docs/wiki/changes.md`, 2026-09-21 삭제): 이 spec 링크와 분할을 미룬 근거를 남긴다.

## 분할 계획
예상 규모: 테스트 약 40줄, 빌드 설정 1줄, 문서 약 110줄로 총 150줄 내외다.
초과하면 wiki의 분할 조건 기록을 후속 PR로 옮기고 가드만 남긴다. 후속 순서는 Flyway·PostgreSQL 기반 → order `CreateOrder` 수직 절단이다.

## TDD 검증 기록
업무 동작 변경이 아니라 구조 규칙이므로 도메인 단위 테스트는 없다. 규칙이 실제로 위반을 잡는지 주입으로 확인한다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| `foundation은 프레임워크에 의존하지 않는다` | `./gradlew test --tests 'com.resilientpos.DependencyDirectionTest'` — `foundation`에 JPA 애너테이션을 붙인 임시 클래스를 넣어 실패. `Class com.resilientpos.foundation.FrameworkLeakProbe is annotated with jakarta.persistence.Entity` | 임시 클래스 제거 후 같은 명령 통과 | `./gradlew test` — BUILD SUCCESSFUL, 16 tests |

## 결과
`./gradlew test` BUILD SUCCESSFUL (16 tests). 인수 조건 6개를 모두 만족했다.

개발 중 확인한 것 두 가지를 기록한다.

1. **`package-info`가 규칙에 걸린다**: 예외 없이 규칙을 먼저 돌리니 `foundation`에서 위반 2건이 나왔다. 둘 다
   `package-info`의 Modulith 애너테이션이었다. 모듈 선언은 코드가 아니라 메타데이터이므로 이름으로 제외했다.
   실제 타입에 같은 애너테이션이 붙으면 그대로 잡힌다.
2. **ArchUnit 버전은 이미 해석된 값과 같다**: `spring-modulith-core`가 끌어오던 1.4.2를 그대로 명시 선언했다.
   충돌이 없고, 앞으로 Modulith를 올릴 때 이 선언도 함께 확인해야 한다.

Gradle subproject 분할은 하지 않았다. 가이드 1절의 조건이 하나도 충족되지 않았고, 모듈 7개가 비어 있어 담길 코드보다
빌드 코드가 많아진다. 분할이 주는 효과 중 지금 실제로 필요한 의존성 방향 검사만 가져왔다. 트리거 조건은 현재 상태
문서에 적었다.
