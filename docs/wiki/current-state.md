# 현재 상태

## 실제 빌드 선언 (2026-09-07 확인)

| 항목 | 저장소에 선언된 상태 |
|---|---|
| 언어/JVM | Kotlin 2.4.10, Java toolchain 25 |
| 프레임워크 | Spring Boot 4.1.1, dependency-management plugin 1.1.7 |
| 빌드 | Gradle Wrapper 9.7.1, Kotlin DSL, 단일 root project |
| 서버/데이터 | Spring MVC, Spring Data JPA, Actuator, Jackson Kotlin |
| DB | H2와 PostgreSQL runtime 드라이버, H2 console 의존성 |
| 테스트 | Kotlin Test/JUnit 5, Spring Boot 테스트 starter; 현재 contextLoads 테스트 한 개 |
| 로컬 하네스 | Python 3 표준 라이브러리 |

근거: [build.gradle.kts](../../build.gradle.kts), [Gradle Wrapper](../../gradle/wrapper/gradle-wrapper.properties), [애플리케이션 설정](../../src/main/resources/application.yaml).
현재 설정에는 애플리케이션 이름만 있다. PostgreSQL 연결 설정이나 운영 DB 사용이 완료됐다고 해석하지 않는다.
이 표는 빌드 선언을 확인한 결과이며, 의존성 다운로드·앱 실행 검증 결과를 의미하지 않는다.

## 계획과 구현 구분
- `com.resilientpos`에 애플리케이션 진입점이 있다. 도메인 모듈은 아직 구현되지 않았다.
- Spring Modulith, Kafka, Redis, Kotlin JDSL, OpenTelemetry는 참고 문서의 설계 방향이며 현재 빌드 의존성에는 없다.
- 제공된 요구사항 문서 7개는 [원문 목록](../references/README.md)에 보관했다. 요구사항 재정리와 원문 간 정합성 검토는 아직 수행하지 않았다.
- 애플리케이션 작업에는 [기존 설계 원칙](../../README.md#engineering-principles)을 참고하되 도입 완료 여부는 코드로 확인한다.
- 개발 기준은 [개발 규칙](development-workflow.md)이다. 공통 LLM 지침과 로컬 검사로 운영한다.
