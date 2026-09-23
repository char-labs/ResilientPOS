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

관측성: 로그가 그대로임을 `ErrorLogRequestContextTest`가 확인한다. 이 테스트는 logback appender를 `ApiControllerAdvice` FQCN 로거에 붙이므로 로거 이름이 바뀌면 실패한다. 레벨과 호출 위치(caller data) 단언을 더한다.

## 제외
- 파일 최상위 `private val log = KotlinLogging.logger {}` 방식. 카카오페이 기술 블로그가 권장하는 형태지만 이번에는 lazy 위임 한 가지로 통일한다.
- 로거 생성 경로를 막는 ArchUnit 규칙. `LoggerFactory.getLogger` 호출은 막을 수 있지만, `KotlinLogging.logger(String)` 직접 호출은 `logger()`의 inline 전개와 바이트코드가 같아 구분되지 않는다. 경로 전체를 막지 못하는 규칙은 이번에 넣지 않는다.
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
- src/test/kotlin/com/resilientpos/platform/ErrorLogRequestContextTest.kt
- docs/wiki/current-state.md
- docs/spec/2026-09-23-platform-kotlin-logging.md

## 인수 조건
1. companion에서 `logger()`로 만든 로거의 이름이 바깥 클래스의 FQCN이다.
2. 이름 붙인 companion에서도 바깥 클래스의 FQCN이다.
3. companion이 아닌 곳(object, 클래스 본문)에서는 선언한 타입의 FQCN이다. 하위 클래스 인스턴스에서 접근해도 선언한 클래스 이름이다.
4. 같은 위임 프로퍼티를 여러 번 읽어도 같은 인스턴스다.
5. `src/main`에 `org.slf4j.LoggerFactory` 사용이 남지 않는다.
6. 기존 테스트, 특히 `ErrorLogRequestContextTest` 6개가 그대로 통과한다.
7. advice의 오류 로그가 경로마다 기대 레벨(ERROR·WARN)로 남고, 호출 위치가 `ApiControllerAdvice`다.

## 검증
- `./gradlew test --tests 'com.resilientpos.platform.LoggingTest'` — 인수 조건 1~4.
- `./gradlew test --tests 'com.resilientpos.platform.*'` — 로그 형식과 원인 첨부가 그대로다.
- `./gradlew test` — 전체 통과. Docker가 필요하다.
- `grep -rn 'org.slf4j' src/main` — 결과 없음. `LoggerFactory`로 찾으면 `Logging.kt`의 KDoc 한 줄이 나온다.
- `python3 scripts/check_pr.py --base fe6f889 --worktree` — PASS.

## Wiki 영향
- [현재 상태](../wiki/current-state.md) — 빌드 선언 표에 kotlin-logging을 넣고, `platform` 모듈 설명에 `logger()`와 선언 규칙을 적고, 테스트 수를 갱신한다.
- 요구사항 변화: 없다. 로그 내용과 형식은 그대로이고 로거를 만드는 방법만 바뀐다.

## 분할 계획
9개 파일 / 230줄이다. 한도 안이라 나누지 않는다.

## TDD 검증 기록
helper는 새 동작이라 테스트를 먼저 쓴다. 호출부 이전은 동작이 바뀌지 않는 리팩터링이라 기존 `ErrorLogRequestContextTest`로 회귀를 본다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| companion·이름 붙인 companion·object·하위 클래스에서의 로거 이름, 같은 인스턴스 (`LoggingTest` 5개) | `./gradlew test --tests 'com.resilientpos.platform.LoggingTest'` — `logger`가 없어 컴파일 실패 | 같은 명령 — 5개 통과 | companion 분기를 지우는 변형에서 2개 실패, 되돌려 5개 통과 |
| 오류 로그 형식과 원인 첨부 유지 (`ErrorLogRequestContextTest`) | 해당 없음 — 기존 테스트 | 호출부 이전 뒤 `./gradlew test` — 6개 통과 | 같음 |
| 오류 로그의 호출 위치가 advice다 (`ErrorLogRequestContextTest`에 caller data 단언 추가) | `log.at(level)` 그대로 두고 `./gradlew test --tests 'com.resilientpos.platform.ErrorLogRequestContextTest'` — `at` 경로 3개 실패, 호출 위치가 `KLogger$DefaultImpls.at$default` | `marker = null`을 적은 뒤 같은 명령 — 6개 통과 | `./gradlew test` 전체 통과 |

## 결과
`./gradlew test` 174개가 전부 통과한다. `src/main`에 `LoggerFactory` 사용이 남지 않았다. kotlin-logging 8.0.4는 slf4j-api 2.0.17을 요구하지만 Spring Boot가 관리하는 2.0.18로 올라가 해결되고 logback-classic 1.5.38 위에서 돈다.

- **레퍼런스를 대조해 형태를 정했다.** 카카오페이 기술 블로그(tech.kakaopay.com/post/efficient-logging-with-kotlin)는 람다 메시지를 권장하고 로거를 인스턴스 필드로 두지 말라고 한다. Team-SSEUDAM/Sseudam-Server의 `LoggerExtension.kt`는 같은 reified lazy helper를 companion에서 쓴다. 두 가지를 합쳐 companion에 한 번 만드는 형태로 정했다.
- **companion 판정을 이름이 아니라 `KClass.isCompanion`으로 했다.** Sseudam 구현은 simpleName이 `Companion`인지 보는데, 이름 붙인 companion에서는 `X$Factory`로 찍힌다. `LoggingTest`의 이름 붙인 companion 케이스가 이것을 고정한다. `kotlin-reflect`는 이미 의존성에 있다.
- **lazy는 기본 모드(동기화)로 두었다.** Sseudam은 `LazyThreadSafetyMode.NONE`을 쓴다. 싱글턴 빈의 로거는 여러 스레드가 처음 읽을 수 있어서, 스레드 안전성을 따로 따지지 않아도 되는 기본 모드를 골랐다. 잠금은 첫 초기화에만 걸리고 그 뒤로는 volatile 읽기 한 번이다.
- **변형으로 테스트가 companion 분기를 지키는지 봤다.** 분기를 지워 선언 타입 이름을 그대로 쓰게 하면 companion 두 케이스가 실패한다.
- **로그 내용은 그대로다.** 문구의 요청 정보·`errorCode`, 예외 원인 첨부, 로거 이름, 레벨, 호출 위치를 `ErrorLogRequestContextTest` 6개가 확인한다. 이 테스트는 `ApiControllerAdvice` FQCN 로거에 appender를 붙이므로 이름이 달라지면 로그를 못 받아 실패한다. 레벨과 호출 위치 단언은 multi-review 지적으로 더했다.
- **`log.at(level)`은 호출 위치를 잃었다.** multi-review에서 Codex가 짚었고 실측으로 확인했다. marker를 생략하면 Kotlin이 기본 인자 브리지 `KLogger$DefaultImpls.at$default`를 만들고, logback은 kotlin-logging 경계 바로 다음 프레임인 이 브리지를 호출 위치로 잡는다. `log.warn { }`은 영향이 없다. `marker = null`을 적어 브리지를 거치지 않게 했다. 호출 위치는 로그를 남기는 순간의 스택에서 계산되므로 테스트 appender가 `append` 안에서 미리 읽는다.
- **lazy는 요청한 형태라서 택했다.** companion 초기화도 JVM 클래스 초기화라 이미 늦고 스레드 안전해서, `KLogger`를 바로 돌려줘도 효과는 같다. 차이는 초기화 실패가 기동이 아니라 첫 로그에서 드러난다는 점이다. `KotlinLogging.logger(String)`과 `isCompanion`은 kotlin-reflect가 있는 한 실패하지 않는다.
- **wiki 테스트 수를 실제 값으로 맞췄다.** 앞 PR의 `EntityVisibilityTest` 2개가 집계에서 빠져 있어 이번에 늘어난 5개와 함께 반영했다.
- multi-review: Claude 86점, Codex 88점, Gemini 미실행(CLI 없음). 반영하지 않은 지적은 PR 본문에 적는다.
- 규모: 9개 파일 / 230줄.
