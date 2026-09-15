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
| 테스트 | Kotlin Test/JUnit 5, Spring Boot 테스트 starter; contextLoads 1개와 모듈 구조 테스트 2개 |
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
- `foundation`과 `platform` 패키지는 아직 만들지 않았다. 실제 타입이 생기는 후속 PR에서 추가한다.
- 제공된 요구사항 문서 7개는 [원문 목록](../references/README.md)에 보관했다. 요구사항 재정리와 원문 간 정합성 검토는 아직 수행하지 않았다.
- 애플리케이션 작업에는 [기존 설계 원칙](../../README.md#engineering-principles)을 참고하되 도입 완료 여부는 코드로 확인한다.
- 개발 기준은 [개발 규칙](development-workflow.md)이다. 공통 LLM 지침과 로컬 검사로 운영한다.

## 테스트 개발 방식
비즈니스·도메인·Controller는 [TDD 전략](testing-strategy.md)을 따른다. Repository 자체 테스트는 대상에서 제외한다.
TDD 스킬과 Claude/Codex 에이전트를 구성했다. 정본은 Obsidian 볼트에 있고 저장소는 `.claude`와 `.codex` 심볼릭 링크로 연결하며 둘 다 추적하지 않는다. 링크 생성은 `make bootstrap`이 담당한다. Fixture Monkey와 REST Docs는 채택한 테스트 도구이며 아직 빌드 의존성에는 추가하지 않았다.
