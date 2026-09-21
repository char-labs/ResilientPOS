# PR spec: 오류 로그가 어떤 요청이었는지 말하게 한다

## 목적
오류 로그에 `errorCode`와 예외 메시지만 있고 **어떤 요청이었는지가 없다.** 운영에서 `errorCode=ORDER_NOT_FOUND : 주문을 찾을 수 없습니다` 한 줄을 봐도 어떤 endpoint의 어떤 주문인지 알 수 없어서, 한 건을 추적하려면 결국 클라이언트 로그를 받아야 한다. 로그 한 줄만으로 요청을 특정할 수 있게 한다.

## 범위
소유 모듈은 `platform`이다. 오류를 로그로 남기는 자리가 `ApiControllerAdvice` 한 곳뿐이다.

**무엇을 더하나.** HTTP 메서드와 요청 URI다. URI가 경로 변수를 포함하므로 `POST /v1/orders/{orderId}/checkout`의 주문 식별자는 URI만으로 드러난다. 별도로 `orderId`를 뽑아 붙이지 않는다 — advice는 어떤 endpoint를 다루는지 모르고, 알게 만들면 endpoint가 늘 때마다 advice를 고쳐야 한다.

**쿼리 문자열은 남기지 않는다.** 지금 쿼리 파라미터를 쓰는 endpoint가 없고, 나중에 생겼을 때 개인정보가 로그로 흘러 들어가는 경로가 된다. 남길 필요가 생기면 그때 무엇을 남길지 정한다.

**세 자리 모두에 적용한다.** `handleDomain`, `failBody`(예상치 못한 예외와 동시성 충돌), 그리고 Spring MVC 표준 예외를 다루는 `handleExceptionInternal`이다. 지금 `handleExceptionInternal`은 `failBody`를 거치므로 자동으로 따라온다.

**요청 정보를 어떻게 얻나.** `handleDomain`·`handleUnexpected`·`handleConcurrency`는 예외만 받는다. 핸들러 시그니처에 `HttpServletRequest`를 더한다. Spring MVC가 `@ExceptionHandler` 메서드에 주입해 주는 표준 인자라서 새 장치가 필요 없다.

계약: 응답 본문은 바뀌지 않는다. 새 오류 코드도 없다. 로그 형식만 바뀐다.

복구: 해당 없음. 로그 내용만 바뀐다.

관측성: 이 PR이 바로 그 항목이다. 바뀐 형식은 `method=POST uri=/v1/orders errorCode=... : ...`이다.

## 제외
- 요청 상관관계 ID(correlation id)와 MDC. 필터와 응답 헤더까지 묶이는 별도 결정이라 이 PR에서 하지 않는다. 지금 없어도 메서드와 URI만으로 한 건을 특정할 수 있다.
- 접근 로그(요청마다 한 줄). 오류가 아닌 요청까지 남기는 것은 다른 목표다.
- 구조화 로그(JSON) 전환과 로그 수집기 연동.
- 요청 본문·헤더를 로그에 남기는 것. 개인정보가 섞인다.
- 클라이언트 IP. 프록시 뒤에서는 헤더를 신뢰해야 하고 그 판단은 배포 형태가 정해진 뒤에 한다.

## 관련 문서
없음

## 변경 경로
- src/main/kotlin/com/resilientpos/platform/ApiControllerAdvice.kt
- src/test/kotlin/com/resilientpos/platform/**
- docs/**

## 인수 조건
1. 도메인 규칙 위반 로그에 메서드와 URI가 있다.
2. 예상치 못한 예외 로그에도 메서드와 URI가 있고 예외 원인(스택)이 그대로 남는다.
3. Spring MVC 표준 예외(404·405·잘못된 본문) 로그에도 있다.
4. 쿼리 문자열은 남지 않는다.
5. 응답 본문은 바뀌지 않는다. 기존 테스트가 하나도 깨지지 않는다.

## 검증
- `./gradlew test` — 전부 통과.
- `python3 scripts/check_pr.py --base fix/request-resource-limits --worktree` — PASS. 이 PR은 앞선 자원 상한 PR 위에 쌓는다.
- 로그 내용은 로그를 가로채 확인한다. 형식을 눈으로 보고 넘어가면 다음 변경이 조용히 지운다.

## Wiki 영향
- [현재 상태](../wiki/current-state.md) — 오류 로그에 무엇이 남는지 적는다.
- [변경 이력](../wiki/changes.md) — 항목 추가.
- 요구사항 변화: 없다. 응답과 스키마가 그대로다.

## 분할 계획
예상 1개 파일 + 테스트. 한도에 한참 못 미친다.

## TDD 검증 기록

RED 명령은 모두 `./gradlew test --tests '…ErrorLogRequestContextTest'`이고, 실패 이유는 로그에 `method=`가 없다는 것이다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 도메인 규칙 위반 로그에 메서드와 URI가 남는다 | FAILED — `메서드가 없다: errorCode=EMPTY_ORDER : …` | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| 경로 변수를 쓰는 요청은 URI만으로 대상이 드러난다 | FAILED — 같은 이유 | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| 예상치 못한 예외 로그에도 메서드와 URI가 남고 원인이 보존된다 | FAILED — 같은 이유 | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| Spring MVC 표준 예외 로그에도 남는다 | FAILED — 같은 이유 | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| 쿼리 문자열은 로그에 남지 않는다 | FAILED — 같은 이유 | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| 성공 응답은 오류 로그를 남기지 않는다 | 처음부터 통과 | 같은 명령 — 통과 | `./gradlew test` — 통과 |

## 결과
`./gradlew test` 136개 통과다(직전 130 + 신규 6).

로그 형식은 `method=POST uri=/v1/orders errorCode=BAD_REQUEST : …`이다. 세 자리 모두에 적용된다. `handleExceptionInternal`은 `failBody`를 거치므로 표준 예외도 자동으로 따라왔다.

`handleExceptionInternal`만 다른 방식으로 요청을 얻는다. 시그니처가 `HttpServletRequest`가 아니라 `WebRequest`이기 때문이다. MVC는 늘 `ServletWebRequest`를 넘기지만 타입으로는 단정할 수 없고, 단언으로 깨면 **예외를 처리하는 자리에서 새 예외가 난다.** 그래서 형식은 유지한 채 값만 비우는 갈래를 뒀다. 일어나지 않을 경로지만, 여기서만은 비용보다 안전이 앞선다.

성공 응답이 오류 로그를 남기지 않는지도 함께 고정했다. 요청 정보를 더하면서 로그를 부르는 자리를 옮겼기 때문에, 조용히 늘어나지 않았는지 확인할 자리가 필요했다.
