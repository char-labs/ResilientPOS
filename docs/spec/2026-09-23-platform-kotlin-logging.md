# PR spec: 로거를 kotlin-logging의 lazy 위임 한 가지로 만든다

## 목적
로거를 만드는 방법을 하나로 정한다. 지금은 두 클래스가 `companion object`에 `LoggerFactory.getLogger(X::class.java)`를 적는다. 클래스 이름을 손으로 반복해서, 복사해 온 클래스에서 이름을 안 바꾸면 다른 클래스 이름으로 로그가 찍힌다. 메시지도 `{}` 자리표시자라 인자 순서가 틀려도 컴파일이 잡지 못한다.

기대 결과: 어느 모듈에서든 `companion object { private val log by logger() }` 한 줄로 로거를 얻는다. 로거 이름은 선언한 클래스의 FQCN이다. 메시지는 람다(`log.info { "...$x" }`)로 적어 레벨이 꺼져 있으면 문자열을 만들지 않는다.

## 범위
**의존성.** `io.github.oshai:kotlin-logging-jvm:8.0.4`를 추가한다. Spring Boot BOM이 관리하지 않아 버전을 직접 적는다. slf4j-api와 logback은 Spring Boot starter가 이미 준다.

**helper.** `platform`에 reified 타입 인자 `T`를 받는 inline 확장 함수 `T.logger()`를 둔다. `KLogger`를 담은 `Lazy`를 돌려주므로 `by`로 위임한다.
- `reified`로 받은 **선언 타입**의 이름을 쓴다. 런타임 하위 클래스 이름이 아니므로 `LoggerFactory.getLogger(X::class.java)`와 같은 이름이 나온다.
- companion에서 부르면 `T`가 `X.Companion`이다. `KClass.isCompanion`이면 바깥 클래스 이름을 쓴다. 이름 붙인 companion(`companion object Factory`)도 같다. 클래스 simpleName을 `"Companion"`과 비교하면 이 경우를 놓친다.
- `lazy {}`의 기본 모드(동기화)로 만든다. 첫 접근 한 번만 만들고 그 뒤로는 같은 인스턴스다.

**선언 위치는 companion object다.** 클래스 본문에 두면 인스턴스마다 `Lazy`와 참조가 생긴다. Spring 빈은 싱글턴이라 차이가 없지만, 엔티티처럼 인스턴스가 많은 클래스에서도 같은 규칙을 쓰려면 클래스당 하나여야 한다.

**모듈 위치는 `platform`이다.** `BaseEntity`처럼 모든 모듈이 이미 쓰는 기술 공통 모듈이다. `foundation`은 [도메인 모델](../wiki/domain-model.md)이 "프레임워크 없는 값 타입과 모델링 원시 타입만" 두는 곳으로 정해 두었다.

**기존 호출 두 곳을 옮긴다.** kotlin-logging 8의 `KLogger`는 람다 메시지 API만 제공하고 `{}` 자리표시자 메서드가 없다.
- `OrderPaymentRequestedListener`의 커밋 후 INFO 로그를 람다 메시지로 바꾼다.
- `ApiControllerAdvice`의 WARN 로그를 람다로, 레벨이 오류마다 다른 로그를 `log.at(level) { message; cause }`로 바꾼다.
- `ErrorType.level`의 타입을 `org.slf4j.event.Level`에서 `io.github.oshai.kotlinlogging.Level`로 바꾼다. 값(ERROR·WARN·INFO)은 그대로다.

계약: 로그 문구와 로거 이름(FQCN), 레벨, 예외 원인 첨부는 바뀌지 않는다. API 응답·저장 형식은 건드리지 않는다.

복구: 해당 없음.

관측성: 로그 형식이 그대로임을 기존 `ErrorLogRequestContextTest`가 확인한다. 이 테스트는 logback appender를 `ApiControllerAdvice` FQCN 로거에 붙이므로 로거 이름이 바뀌면 실패한다.

## 제외
- 파일 최상위 `private val log = KotlinLogging.logger {}` 방식. 카카오페이 기술 블로그가 권장하는 형태지만 이번에는 lazy 위임 한 가지로 통일한다.
- `org.slf4j.LoggerFactory` 직접 사용을 막는 ArchUnit 규칙. `logger()`가 inline이라 호출부 바이트코드에 `KotlinLogging` 참조가 남아서 생성 경로 전체를 규칙으로 막을 수 없다. 우회로 한 가지만 막는 규칙은 넣지 않는다.
- 테스트 코드의 logback 직접 사용. `ErrorLogRequestContextTest`는 로그 출력을 가로채려고 logback `Logger`를 다루는 것이라 대상이 아니다.
- 구조화 로그(JSON), correlation id, MDC. [현재 상태](../wiki/current-state.md)에 적힌 알려진 빈틈 그대로다.

## 관련 문서
없음

## 변경 경로
- build.gradle.kts
- src/main/kotlin/com/resilientpos/platform/Logging.kt
- src/main/kotlin/com/resilientpos/platform/ApiControllerAdvice.kt
- src/main/kotlin/com/resilientpos/platform/ErrorType.kt
- src/main/kotlin/com/resilientpos/payment/internal/application/OrderPaymentRequestedListener.kt
- src/test/kotlin/com/resilientpos/platform/LoggingTest.kt
- docs/wiki/current-state.md
- docs/spec/2026-09-23-platform-kotlin-logging.md

## 인수 조건
1. companion에서 `logger()`로 만든 로거의 이름이 바깥 클래스의 FQCN이다.
2. 이름 붙인 companion에서도 바깥 클래스의 FQCN이다.
3. companion이 아닌 곳(object, 클래스 본문)에서는 선언한 타입의 FQCN이다. 하위 클래스 인스턴스에서 접근해도 선언한 클래스 이름이다.
4. 같은 위임 프로퍼티를 여러 번 읽어도 같은 인스턴스다.
5. `src/main`에 `org.slf4j.LoggerFactory` 사용이 남지 않는다.
6. 기존 테스트, 특히 `ErrorLogRequestContextTest` 6개가 그대로 통과한다.

## 검증
- `./gradlew test --tests 'com.resilientpos.platform.LoggingTest'` — 인수 조건 1~4.
- `./gradlew test --tests 'com.resilientpos.platform.*'` — 로그 형식과 원인 첨부가 그대로다.
- `./gradlew test` — 전체 통과. Docker가 필요하다.
- `grep -rn 'LoggerFactory' src/main` — 결과 없음.
- `python3 scripts/check_pr.py --base fe6f889 --worktree` — PASS.

## Wiki 영향
- [현재 상태](../wiki/current-state.md) — 빌드 선언 표에 kotlin-logging을 넣고, `platform` 모듈 설명에 `logger()`와 선언 규칙을 적고, 테스트 수를 갱신한다.
- 요구사항 변화: 없다. 로그 내용과 형식은 그대로이고 로거를 만드는 방법만 바뀐다.

## 분할 계획
8개 파일 / 약 150줄로 예상한다. 한도 안이라 나누지 않는다.

## TDD 검증 기록
helper는 새 동작이라 테스트를 먼저 쓴다. 호출부 이전은 동작이 바뀌지 않는 리팩터링이라 기존 `ErrorLogRequestContextTest`로 회귀를 본다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| companion·이름 붙인 companion·object·하위 클래스에서의 로거 이름, 같은 인스턴스 | 미실행 | 미실행 | 미실행 |
| 오류 로그 형식과 원인 첨부 유지 (`ErrorLogRequestContextTest`) | 해당 없음 — 기존 테스트 | 미실행 | 미실행 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
