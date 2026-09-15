# 현재 상태

## 실제 빌드 선언 (2026-09-15 확인)

| 항목 | 저장소에 선언된 상태 |
|---|---|
| 언어/JVM | Kotlin 2.4.10, Java toolchain 25 |
| 프레임워크 | Spring Boot 4.1.1, dependency-management plugin 1.1.7 |
| 빌드 | Gradle Wrapper 9.7.1, Kotlin DSL, 단일 root project |
| 서버/데이터 | Spring MVC, Spring Data JPA, Actuator, Jackson Kotlin |
| DB | H2와 PostgreSQL runtime 드라이버, H2 console 의존성 |
| 모듈 경계 | Spring Modulith 2.1.1 (BOM + `starter-core`, 테스트는 `starter-test`) |
| 식별자 | `uuid-creator` 6.1.1 (UUID v7) |
| 구조 검증 | Spring Modulith 경계 검증, ArchUnit 1.4.2 의존성 방향 가드 |
| 테스트 | Kotlin Test/JUnit 5, Spring Boot 테스트 starter; 총 16개 (contextLoads 1, 모듈 구조 2, 의존성 방향 1, Money 8, 식별자 4) |
| 로컬 하네스 | Python 3 표준 라이브러리 |

근거: [build.gradle.kts](../../build.gradle.kts), [Gradle Wrapper](../../gradle/wrapper/gradle-wrapper.properties), [애플리케이션 설정](../../src/main/resources/application.yaml).
현재 설정에는 애플리케이션 이름만 있다. PostgreSQL 연결 설정이나 운영 DB 사용이 완료됐다고 해석하지 않는다.
이 표는 빌드 선언을 확인한 결과이며, 의존성 다운로드·앱 실행 검증 결과를 의미하지 않는다.

## 계획과 구현 구분
- `com.resilientpos`에 애플리케이션 진입점이 있다. bounded context 7개(`store`, `terminal`, `order`, `payment`,
  `sync`, `reconciliation`, `fulfillment`)는 `package-info.java`의 `@ApplicationModule`로 선언만 했고 타입은 아직 없다.
  `ModuleStructureTest`가 모듈 인식과 경계 위반을 검증한다. 위반 주입 시 `Module 'order' depends on non-exposed type ...`
  메시지로 실패하는 것을 확인했다.
- Spring Modulith는 2.1.1로 도입했다. `spring-modulith-core` 2.1.1이 `spring-boot-autoconfigure` 4.1.1에 대해
  빌드되어 현재 Boot 버전과 세대가 맞는다. Event Publication Registry(`starter-jpa`)와 `starter-insight`는 아직 없다.
- Kafka, Redis, Kotlin JDSL, OpenTelemetry는 참고 문서의 설계 방향이며 현재 빌드 의존성에는 없다.
- `foundation` 모듈에 `Money`와 `IdentifierGenerator`/`UuidV7IdentifierGenerator`가 있다. 프레임워크 의존성은 없다.
  `Money`는 통화의 표준 소수 자릿수를 canonical scale로 쓰고, scale 초과 입력과 통화 불일치 연산을 거절한다.
  나눗셈·퍼센트 할인·세금 계산은 없다. 반올림 정책은 매장 규칙이므로 `store` 정책과 checkout에서 다룬다.
- `platform` 패키지는 아직 만들지 않았다. 실제 adapter가 생기는 후속 PR에서 추가한다.
- 제공된 요구사항 문서 7개는 [원문 목록](../references/README.md)에 보관했다. 요구사항 재정리와 원문 간 정합성 검토는 아직 수행하지 않았다.
- 애플리케이션 작업에는 [기존 설계 원칙](../../README.md#engineering-principles)을 참고하되 도입 완료 여부는 코드로 확인한다.
- 개발 기준은 [개발 규칙](development-workflow.md)이다. 공통 LLM 지침과 로컬 검사로 운영한다.

## 테스트 개발 방식
비즈니스·도메인·Controller는 [TDD 전략](testing-strategy.md)을 따른다. Repository 자체 테스트는 대상에서 제외한다.
TDD 스킬과 Claude/Codex 에이전트를 구성했다. 정본은 Obsidian 볼트에 있고 저장소는 `.claude`와 `.codex` 심볼릭 링크로 연결하며 둘 다 추적하지 않는다. 링크 생성은 `make bootstrap`이 담당한다. Fixture Monkey와 REST Docs는 채택한 테스트 도구이며 아직 빌드 의존성에는 추가하지 않았다.

## Gradle 프로젝트 구조와 분할 조건
단일 Gradle 프로젝트를 유지한다. [개발 가이드 1절](../references/03_Development_Guide.md)은 패키지 경계가 안정되고 독립
build/test/dependency 또는 외부 contract artifact가 실제로 필요할 때만 subproject를 추가하라고 정한다. 현재 모듈 7개는
비어 있고 전체 테스트가 하나의 태스크로 끝난다. 지금 나누면 담길 코드보다 빌드 코드가 많아진다.

아래 중 하나라도 충족하면 별도 PR로 분할을 검토한다.
- `pos-client`처럼 별도 실행체가 생겨 domain이나 contract 코드를 공유해야 한다.
- `contracts/`의 OpenAPI·AsyncAPI 아티팩트를 외부에 배포해야 한다.
- 한 모듈에만 필요한 의존성(Kafka, Redis adapter 등)이 전체 classpath로 새는 것이 실제 문제가 된다.
- 빌드·테스트 시간이 길어져 모듈 단위 증분 빌드가 필요하다.

분할을 미뤄도 경계가 무너지지 않는다. Modulith 검증이 모듈 간 internal 참조와 순환을, ArchUnit 가드가 프레임워크
의존을 막는다. 그래서 나중 분할은 패키지 이름이 바뀌지 않는 파일 이동이다.

## 의존성 방향 가드
`DependencyDirectionTest`가 `foundation`의 프로덕션 코드에서 Spring, JPA, Servlet, Jackson, Kafka 의존을 막는다.
`package-info`의 Modulith 애너테이션은 코드가 아니라 모듈 메타데이터라 예외로 둔다. 같은 애너테이션이 실제 타입에
붙으면 위반으로 잡힌다.

도메인 레이어 규칙(`internal/domain`이 Spring이나 repository에 의존하지 않는다)은 아직 없다. 해당 패키지가 생기지 않아
검사 대상이 0건이고, 빈 규칙은 활성 여부를 알 수 없다. 첫 도메인 PR에서 같은 파일에 추가한다.
