# PR spec: AGENTS.md를 현재 빌드 상태에 맞게 갱신

## 목적
AGENTS.md는 매 세션 로드되는 인덱스 문서인데 "현재 기술 스택" 요약이 실제 빌드와 어긋나 있다. H2는 제거되어
PostgreSQL 전용이 되었고 Spring Modulith 2.1.1이 도입되었는데도 요약은 반대로 적혀 있다. 최근 PR에서 확립한 코드
규약과 반복해서 마주친 검증 함정도 기록되어 있지 않아 세션마다 다시 발견하게 된다. 이 PR은 요약을 사실에 맞추고
반복 비용이 큰 규약·함정 네 가지를 한 줄씩 남긴다.

## 범위
- "현재 기술 스택" 절을 `build.gradle.kts`와 [현재 상태](../wiki/current-state.md)에 맞춘다. PostgreSQL 전용과
  Flyway 스키마 소유·JPA validate를 적고, Spring Modulith 2.1.1 도입을 반영한다. 테스트 도구로 Spring REST Docs,
  Testcontainers PostgreSQL, ArchUnit, Fixture Monkey를 요약에 넣는다. Redis와 Kafka가 아직 없다는 문장은 유지한다.
- "비즈니스·도메인·Controller 개발" 자리에 코드 규약 두 가지를 한 줄씩 더한다. 도메인 규칙 위반은 `require`가 아니라
  `foundation.DomainException(code, message, kind)`으로 던진다. Controller는 DTO만 돌려주고 응답 envelope와 오류
  변환은 `platform`의 advice 두 개가 맡는다.
- 검증 명령에 전제 조건과 함정을 더한다. `./gradlew test`는 Testcontainers 때문에 Docker가 필요하다.
  `scripts/check_pr.py`는 spec의 필수 절에 부등호 여는 문자가 있으면 그 절을 미작성으로 판정한다.
- squash merge된 로컬 브랜치는 `git branch -d`가 삭제를 거절한다는 점과 확인 방법을 git 지침 줄에 한 줄로 더한다.

## 제외
- 애플리케이션 코드와 빌드 스크립트는 바꾸지 않는다. 요약을 코드에 맞추는 작업이지 그 반대가 아니다.
- 요청한 네 가지 밖의 문장은 손대지 않는다. 문체 교정과 절 재배치도 하지 않는다.
- [지침 설계 근거](../wiki/agent-instructions.md)는 갱신하지 않는다. 그 문서는 AGENTS.md의 배치와 우선순위 근거를
  다루고 기술 스택 사실이나 코드 규약을 담지 않으므로 이번 변경의 영향을 받지 않는다.
- 위키 본문(`current-state.md`)은 이미 사실을 정확히 담고 있어 수정 대상이 아니다.

## 관련 문서
없음. 이 PR은 장기 스펙 계층을 따르지 않는 문서 갱신이다. 근거가 되는 선행 PR spec은
[api-response-advice](2026-09-19-api-response-advice.md), [foundation-domain-exception](2026-09-19-foundation-domain-exception.md),
[order-create-order-api](2026-09-20-order-create-order-api.md), [postgres-flyway-baseline](2026-09-15-postgres-flyway-baseline.md)이다.

## 변경 경로
- AGENTS.md
- docs/**

## 인수 조건
- AGENTS.md의 기술 스택 요약에 H2와 "Spring Modulith는 아직 의존성에 없다"가 남아 있지 않다.
- 요약에 적힌 의존성이 모두 이 브랜치 기준 `build.gradle.kts`에 실제로 선언되어 있다.
- 도메인 예외 규약과 Controller 규약이 `foundation/DomainException.kt`,
  `platform/ApiResponseBodyAdvice.kt`, `platform/ApiControllerAdvice.kt`, `order`의 `OrderController.kt`와 일치한다.
- 검증 명령 줄에 Docker 전제와 check_pr.py의 미작성 판정 함정이 한 줄씩 있다.
- git 지침 줄에 squash merge 후 로컬 브랜치 삭제 절차가 한 줄 있다.
- 추가된 문장이 모두 한국어 평서문이고 기존 절 구조를 유지한다.

## 검증
- `python3 scripts/check_pr.py --base origin/main --worktree`가 PASS로 끝난다. spec 첫 커밋 독립, 변경 경로 일치,
  wiki 변경 기록의 spec 링크와 요구사항 영향 설명을 함께 본다.
- 이 spec 파일에 부등호 여는 문자가 없는지 커밋 전에 `grep -c`로 확인해 0을 얻는다. 개수가 0이 아니면 검사가
  해당 절을 미작성으로 판정해 실패한다.
- AGENTS.md가 가리키는 상대 경로 링크가 모두 실제 파일을 가리키는지 확인한다.
- 애플리케이션 코드가 바뀌지 않았으므로 `./gradlew test`는 실행하지 않는다. `git diff --stat`으로 변경 파일이
  문서와 AGENTS.md뿐임을 확인한다.

## Wiki 영향
[변경 기록](../wiki/changes.md)에 이 spec 링크와 요구사항 무영향 근거를 남긴다. 제품 요구사항과 설계는 바뀌지 않는다.
이번 변경은 이미 머지된 PR들이 만든 사실을 인덱스 문서에 옮겨 적는 것이고, 새 결정을 내리지 않는다.
[현재 상태](../wiki/current-state.md)는 이미 같은 사실을 담고 있어 갱신하지 않는다.

## 분할 계획
문서 3개, 추가·수정 줄 수는 100줄 미만으로 예상한다. 개발 규칙의 파일·줄 수 한도에서 멀어 분할하지 않는다.
한도 경고가 나오면 기술 스택 요약 수정과 규약·함정 추가를 별도 PR로 나눈다.

## TDD 검증 기록
적용하지 않는다. 문서 전용 변경이라 업무 동작이 바뀌지 않고 실패시킬 테스트 대상이 없다. 대신 요약에 적는 모든
사실을 `build.gradle.kts`와 해당 Kotlin 파일을 직접 읽어 대조하고, 위 검증 절의 명령으로 확인한다.

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
