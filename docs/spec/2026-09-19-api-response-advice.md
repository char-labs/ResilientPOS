# PR spec: 공통 API 응답 envelope와 전역 예외 advice

## 목적
첫 REST Controller PR(`order-create-order-api`)보다 먼저 모든 HTTP 응답이 같은 모양을 갖게 한다. 지금은 Controller도 advice도 없어서 예외가 나면 Spring Boot 기본 `/error` JSON이 나간다. 이 PR이 끝나면 성공 응답은 `result=SUCCESS`와 `data`로, 실패 응답은 `result=FAIL`과 `error{errorCode, message}`로 나가고, 처리하지 못한 예외는 내부 메시지를 노출하지 않는 500이 된다. 구조는 ppossilaegi 프로젝트의 `ApiResponse`·`ErrorResponse`·`ErrorType`·`ApiControllerAdvice`·`ApiResponseBodyAdvice`를 옮겨 온 것이다.

## 범위
- `platform` 모듈 기본 패키지(`com.resilientpos.platform`)에 둔다. [개발 가이드 §1](../references/03_Development_Guide.md)이 `platform`을 HTTP adapter·configuration 자리로 정했고, `foundation`은 Spring(`HttpStatus`)에 의존할 수 없다. 기본 패키지라서 다른 모듈의 Controller가 `ApiResponse`를 참조해도 Modulith 경계를 어기지 않는다.
- `ApiResponse.kt`: `ApiResponse`(`result`, `data`, `error`), `ApiResponseResult`(`SUCCESS`, `FAIL`), `ErrorResponse`(`errorCode`, `message`). 원본의 Swagger `@Schema`는 뺀다. springdoc 의존성이 없고 문서 계약은 REST Docs가 맡는다.
- `ErrorType.kt`: advice가 직접 쓰는 HTTP 공통 오류 4개(`INTERNAL_ERROR`, `BAD_REQUEST`, `NOT_FOUND`, `METHOD_NOT_ALLOWED`). 항목은 `HttpStatus`, 사용자 메시지, 로그 레벨(`org.slf4j.event.Level`)을 가진다.
- `ApiControllerAdvice`: `ResponseEntityExceptionHandler`를 상속한다. Spring MVC 표준 예외는 상태 코드를 유지한 채 본문만 envelope로 바꾸고(404 → `NOT_FOUND`, 405 → `METHOD_NOT_ALLOWED`, 그 밖의 4xx → `BAD_REQUEST`, 나머지 → `INTERNAL_ERROR`), 그 밖의 모든 예외는 500 `INTERNAL_ERROR`로 바꾼다. 응답 메시지는 항상 `ErrorType`의 고정 문구이고 예외 메시지는 로그에만 남긴다. `basePackages`를 걸지 않는다. 걸면 handler가 정해지기 전에 나는 404·405에 advice가 적용되지 않는다.
- `ApiResponseBodyAdvice`: `com.resilientpos` 아래 Controller의 응답 본문을 `ApiResponse.success(...)`로 감싼다. 이미 `ApiResponse`면 그대로 둔다. `basePackages` 덕분에 Actuator 응답은 감싸지 않는다.
- 소유 모듈: `platform`. 계약: 응답 JSON 모양(`result`, `data`, `error.errorCode`, `error.message`)과 `ErrorType` 이름 4개. 불변식: 처리하지 못한 예외의 메시지는 응답에 나가지 않는다. 복구: 해당 없음(상태를 바꾸지 않는다). 관측성: 실패마다 `ErrorType.level`로 예외와 `errorCode`를 로그에 남긴다. 구조화 로그 속성은 Controller PR에서 붙인다.

## 제외
- 원본의 `CoreException`과 도메인별 `ErrorType` 항목. 도메인 규칙 위반은 진행 중인 `refactor/foundation-domain-exception` 브랜치(spec `2026-09-19-foundation-domain-exception.md`)의 `DomainException(code, message)`이 맡고 그 spec이 오류 코드 enum을 만들지 않기로 정했다. `DomainException` → 400 handler는 그 PR이 머지된 뒤 `order-create-order-api`에서 추가한다. 이 브랜치에는 그 타입이 없다.
- 원본의 `ErrorKind`와 `ErrorLevel`. `ErrorKind`는 `HttpStatus`와 1:1이고 `ErrorLevel`은 slf4j `Level`과 같아서 기존 타입을 쓴다.
- validation 예외(`ConstraintViolationException`, 필드 오류 메시지 조립). `spring-boot-starter-validation`이 없다. 첫 `@Valid` 요청 DTO와 함께 추가한다.
- 인증·인가 오류(`UNAUTHORIZED`, `FORBIDDEN`), SSE·`ByteArray`·api-docs 예외 처리. 해당 기능이 없다.
- REST Docs. 공개 endpoint가 없고 테스트용 Controller는 문서화 대상이 아니다. 첫 Controller PR에서 오류 응답과 함께 문서화한다.
- `IllegalArgumentException` → 400 매핑. JDK와 라이브러리도 던지는 예외라 버그가 400으로 숨는다(`foundation-domain-exception` spec의 목적 절과 같은 근거).

## 관련 문서
없음. 근거는 [개발 가이드 §1·§8](../references/03_Development_Guide.md)(`platform` 자리, 로그 `errorCode`), [테스트 전략](../wiki/testing-strategy.md)의 Controller 절이다.

## 변경 경로
- src/main/kotlin/com/resilientpos/platform/ApiResponse.kt
- src/main/kotlin/com/resilientpos/platform/ErrorType.kt
- src/main/kotlin/com/resilientpos/platform/ApiControllerAdvice.kt
- src/main/kotlin/com/resilientpos/platform/ApiResponseBodyAdvice.kt
- src/test/kotlin/com/resilientpos/platform/ApiAdviceTest.kt
- docs/spec/2026-09-19-api-response-advice.md
- docs/wiki/current-state.md
- docs/wiki/changes.md

## 인수 조건
`@WebMvcTest` 슬라이스와 테스트 전용 Controller로 검증한다. Docker가 필요 없다.
1. 객체를 돌려주는 endpoint의 응답은 200이고 `result`가 `SUCCESS`, `data`가 그 객체, `error`가 null이다.
2. 본문이 없는 endpoint의 응답은 200이고 `result`가 `SUCCESS`, `data`가 null이다.
3. 처리하지 못한 예외는 500이고 `result`가 `FAIL`, `error.errorCode`가 `INTERNAL_ERROR`이며 `error.message`에 예외 메시지가 들어 있지 않다.
4. 허용하지 않는 HTTP method는 405이고 `error.errorCode`가 `METHOD_NOT_ALLOWED`다.
5. 없는 경로는 404이고 `error.errorCode`가 `NOT_FOUND`다.
6. 타입이 맞지 않는 요청 파라미터는 400이고 `error.errorCode`가 `BAD_REQUEST`다.
7. `ModuleStructureTest`와 `DependencyDirectionTest`가 그대로 통과한다.
8. `python3 scripts/check_pr.py --base main --worktree`가 통과한다.

## 검증
```sh
./gradlew test --tests 'com.resilientpos.platform.ApiAdviceTest'
./gradlew test
python3 scripts/check_pr.py --base main --worktree
```
- 정상: 인수 조건 1·2의 성공 envelope.
- 실패: 인수 조건 3~6의 상태 코드와 `errorCode`, 500 응답에 예외 메시지가 없는지 확인한다.

## Wiki 영향
- [현재 상태](../wiki/current-state.md): `platform` 항목에 응답 envelope와 advice를 추가하고 테스트 수를 갱신한다.
- [변경 기록](../wiki/changes.md): 이 spec 링크와 결정(원본에서 뺀 것과 이유)을 남긴다. 제품 요구사항은 바뀌지 않는다.

## 분할 계획
예상 규모: 구현 약 90줄·4파일, 테스트 약 90줄·1파일, 문서 약 100줄로 총 280줄·8파일. 경고 한도(600줄·20파일) 안이다.
후속은 `order-create-order-api`(`DomainException` handler, validation, REST Docs, 구조화 로그 속성)다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 개발 전에 테스트 의도를 적고 실행 후 관측 결과를 기록 | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
