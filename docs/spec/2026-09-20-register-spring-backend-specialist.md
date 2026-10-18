# PR spec: 새 에이전트 spring-backend-specialist를 wiki에 등록

## 목적
볼트에 읽기 전용 리뷰 에이전트 `spring-backend-specialist`를 새로 추가했다(볼트 `ResilientPOS/agents/spring-backend-specialist.md`,
저장소에서는 `.claude/agents` 링크로 읽힌다). 이 에이전트는 Kotlin/Spring Boot 백엔드 변경을 운영 장애·데이터 손상·보안
사고의 관점에서 열세 개 렌즈로 점검해 P0~P4로 보고한다. frontmatter는 `model: opus`, `tools: Read, Glob, Grep`이다.

그런데 wiki의 모델 배정표에는 이 에이전트가 없고, 구현 코드 검토를 `code-reviewer`에 배정한 선행 규칙은 새 에이전트와
경합할 때 어느 쪽을 고를지 정하지 않는다. `workflow-reviewer`가 에이전트 정의를 검토하면서 이 두 가지를 저장소 쪽
작업으로 남겼다. 이 PR은 두 지적을 처리하고, 에이전트를 추가한 사실과 결정을 변경 기록에 남긴다.

## 범위
- [지침 설계 근거](../wiki/agent-instructions.md)의 에이전트 모델 배정표에서 `opus` 행의 에이전트 목록 끝에
  `spring-backend-specialist`를 추가한다. 배정 근거는 그 행의 기준("판단 오류의 대가가 큰 검토·설계·게이트")에 그대로
  해당하므로 기준 문구는 바꾸지 않는다.
- 같은 문서의 선행 규칙 중 "지침·스킬 파일 검토는 `workflow-reviewer`, 구현 코드 검토는 `code-reviewer`·`resilientpos-review`다."
  항목에 한 절을 더한다. 백엔드 운영 리스크(정합성, 멱등성, 응답 속도, 보안, AOP 리팩토링 여지 등)의 점검은
  `spring-backend-specialist`가 맡고 사용자가 명시적으로 요청할 때만 쓴다는 내용이다. 이 문장만 읽고 `code-reviewer`와
  경합할 때 어느 쪽을 고를지 정할 수 있어야 한다.
- 볼트 `ResilientPOS/agents/code-reviewer.md`의 "역할 겹침:" 문단에 `spring-backend-specialist`는 운영 리스크를 깊게 보는
  별도 에이전트라는 한 구절을 더한다. 리뷰 에이전트들은 서로를 열거해 역할 경계를 양방향으로 고정하는 것이 관례이고,
  새 에이전트의 정의는 이미 `code-reviewer`를 열거한다. 볼트는 git으로 관리되지 않으므로 수정 전에 그 파일의 사본을
  `~/Documents/`에 남기고, 수정한 사실과 문구를 이 spec의 결과 절과 변경 기록에 적는다.
- 변경 기록(`docs/wiki/changes.md`, 2026-09-21 삭제)에 이 spec 링크, 요구사항 영향 근거, 아래 세 결정을 남긴다.
  - 렌즈는 웹 조사(Spring·Hibernate·PostgreSQL·OWASP 공식 문서, Stripe·토스페이먼츠·Airbnb·카카오페이 기술 블로그 등)에
    근거한다.
  - 읽기 전용을 본문 서술이 아니라 도구 목록으로 보장하려고 Bash를 뺐다.
  - 자동 발동 경로가 없어서 description을 명시 호출 전용으로 좁혔다.

### 검토 후 범위 조정 (2026-09-20)
wiki 커밋 뒤에 받은 `workflow-reviewer`의 검토에서 네 가지 지적이 나왔고, 아래처럼 범위를 조정한다.
- 볼트 `ResilientPOS/README.md`의 "에이전트" 표에 `spring-backend-specialist` 한 행을 더한다. 그 표는 볼트에 있는 에이전트의
  현재 목록인데 새 에이전트가 빠져 있어 wiki의 배정표와 어긋난다. 처음의 grep은 저장소만 대상으로 했기 때문에 이 표를
  보지 못했다. `code-reviewer.md`와 같은 방식으로 수정 전 사본을 `~/Documents/`에 남긴다.
- 선행 규칙에 더한 문장의 "`spring-backend-specialist`가 맡되"를 고친다. 정합성·멱등성·응답 속도·보안은 `code-reviewer`의
  "잠재적 문제" 항목이 계속 보는 대상이고, 새 에이전트의 정의도 두 에이전트가 함께 보는 것을 전제로 중복 보고를 줄이는
  규칙을 둔다. "맡는다"는 점검의 소유가 옮겨 간 것으로 읽히므로, 새 에이전트는 같은 대상을 더 깊게 보고 명시 요청이
  없으면 `code-reviewer`가 계속 본다는 뜻이 드러나게 쓴다.
- 볼트 `reviewer-claude.md`의 "역할 겹침:" 문단은 고치지 않는다. `reviewer-claude`는 multi-review 안에서만 호출되는 내부용
  에이전트라서 명시 호출 전용인 새 에이전트와 description이 경합하는 경로가 없다. 이 PR은 multi-review에 새 에이전트의
  호출 경로를 추가하지 않으며, 그 경로를 추가하는 PR이 생기면 그때 양방향 열거를 맞춘다.
- `AGENTS.md`는 고치지 않는다. 30번째 줄은 "전체 스킬·에이전트 목록과 모델 배정"이 차용 내역과 지침 설계 근거에 있다고
  두 문서를 함께 가리킨다. 지침 설계 근거의 배정표가 새 에이전트를 포함하므로 이 문장은 여전히 참이다.

### borrowed-workflows.md를 고치지 않는 판단
[차용한 워크플로우](../wiki/borrowed-workflows.md)의 "도입한 에이전트 (16)와 규칙 (3)" 절은 "2026-09-13 — 전체 이식" 절
아래에 있는 이식 이력이다. 새 에이전트는 이식분이 아니라 이 프로젝트에서 새로 만든 것이므로 그 절의 숫자와 목록은
그대로 둔다. 서술이 틀려지는 곳이 있는지는 `AGENTS.md`, `README.md`, `docs/wiki`, `.github`에서 에이전트 개수를 세는
표현과 "모든 에이전트", "리뷰 에이전트", `code-reviewer`를 grep으로 찾아 확인했다. 에이전트 총수를 현재 사실로 단정하는
문장은 없었다. `code-reviewer`를 언급하는 곳은 배정표와 선행 규칙(이 PR이 고친다), 이식 목록(이력),
[도메인 모델](../wiki/domain-model.md)의 스키마 대조 절차("`code-reviewer`처럼 읽기 전용 도구만 가진 다른 에이전트")뿐이고,
마지막 문장은 예시라서 새 에이전트가 생겨도 참이다. 그래서 `borrowed-workflows.md`는 고치지 않는다.

## 제외
- 에이전트 정의 본문(`spring-backend-specialist.md`)은 고치지 않는다.
- PR 템플릿이나 multi-review 절차에 이 에이전트의 호출 경로를 추가하지 않는다.
- Codex용 미러(`codex/agents/*.toml`)는 만들지 않는다. [지침 설계 근거](../wiki/agent-instructions.md)의 "Codex 에이전트"
  절은 TOML 6개를 이름으로 열거하므로 새 에이전트가 생겨도 틀려지지 않는다.
- 시험 실행에서 나온 코드 결함(입력 길이 검증 부재, 단말 발생 시각 계약, 멱등 경합 복구 등)은 이 PR의 범위가 아니다.

## 관련 문서
없음. 이 PR은 장기 스펙 계층을 따르지 않는 개발 도구 문서 갱신이다. 모델 배정표를 처음 만든 선행 PR spec은
[advisor-model-policy](2026-09-13-advisor-model-policy.md)이다.

## 변경 경로
- docs/**

볼트 `ResilientPOS/agents/code-reviewer.md`와 `ResilientPOS/README.md`는 저장소 밖에 있어 diff에 나타나지 않는다.

## 인수 조건
- 배정표의 `opus` 행 목록이 `spring-backend-specialist`로 끝나고, 그 행의 기준 문구와 다른 행은 바뀌지 않았다.
- 선행 규칙의 해당 항목만 읽고 아래 두 가지를 판정할 수 있다. 구현 코드 검토 요청의 기본값은 여전히 `code-reviewer`·
  `resilientpos-review`다. `spring-backend-specialist`는 사용자가 명시적으로 요청할 때만 쓰며 점검 대상은 백엔드 운영
  리스크다.
- 볼트 `code-reviewer.md`의 "역할 겹침:" 문단이 `spring-backend-specialist`를 열거하고, 수정 전 사본이 `~/Documents/`에
  있다. 그 문단 밖의 내용은 바뀌지 않았다.
- 볼트 `README.md`의 "에이전트" 표에 `spring-backend-specialist` 행이 있고 모델이 `opus`다. 수정 전 사본이 `~/Documents/`에
  있고 그 행 밖의 내용은 바뀌지 않았다.
- 변경 기록 항목에 spec 링크, 요구사항 무영향 근거, 세 결정, 볼트 수정 사실과 문구가 있다.
- `borrowed-workflows.md`와 에이전트 정의 본문은 바뀌지 않았다.
- 추가된 문장이 모두 한국어 평서문이고 기존 절 구조를 유지한다.

## 검증
- `python3 scripts/check_pr.py --base origin/main --worktree`가 PASS로 끝난다. 제출 전에 `--worktree` 없이 한 번 더
  실행한다.
- `python3 -m unittest discover -s scripts -p 'test_*.py'`가 통과한다.
- 이 spec 파일에 부등호 여는 문자가 없는지 커밋 전에 `grep -c`로 확인해 0을 얻는다.
- wiki에서 추가하거나 수정한 링크가 실제 경로와 일치하는지 확인한다.
- 볼트 파일은 수정 전 사본과 `diff`로 비교해 "역할 겹침:" 문단의 한 줄만 바뀌었음을 확인한다.
- 애플리케이션 코드가 바뀌지 않았으므로 `./gradlew test`는 실행하지 않는다. `git diff --stat`으로 변경 파일이 `docs/`
  아래 문서뿐임을 확인한다.
- 수정이 끝나면 `workflow-reviewer`에게 이 spec, 기준 ref, 수정한 wiki 파일을 넘겨 읽기 전용으로 검토받고 결과를 결과
  절에 기록한다.

## Wiki 영향
[지침 설계 근거](../wiki/agent-instructions.md)의 배정표와 선행 규칙을 고치고 변경 기록(`docs/wiki/changes.md`, 2026-09-21 삭제)에 이 spec
링크를 남긴다. 제품 요구사항과 설계는 바뀌지 않는다. 개발 도구인 리뷰 에이전트가 하나 늘었을 뿐이고, 이 에이전트는
읽기 전용이며 자동으로 발동하는 경로가 없다.

## 분할 계획
문서 3개(spec, 지침 설계 근거, 변경 기록), 추가·수정 줄 수는 120줄 미만으로 예상한다. 개발 규칙의 파일·줄 수 한도에서
멀어 분할하지 않는다. 열린 PR #27이 `docs/wiki/changes.md`, `docs/wiki/borrowed-workflows.md`,
`docs/wiki/development-workflow.md`를 고치고 있다. 이 PR과 겹치는 파일은 `changes.md` 하나이고, 어느 쪽이 먼저 머지되든
파일 끝에서 충돌이 난다. 제출 직전에 `git fetch`로 origin/main과 #27의 상태를 다시 확인하고, #27이 먼저 머지되었으면
사용자 확인을 받아 두 항목을 모두 남기는 방향으로 rebase 한다. rebase할 때에도 이 spec 커밋을 첫 커밋으로 유지한다.

## TDD 검증 기록
적용하지 않는다. 문서 전용 변경이라 업무 동작이 바뀌지 않고 실패시킬 테스트 대상이 없다. 대신 위 검증 절의 명령과
`workflow-reviewer`의 읽기 전용 검토로 확인한다.

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.

### 개발 후 (2026-09-20)
- [지침 설계 근거](../wiki/agent-instructions.md)의 배정표에서 `opus` 행 목록 끝에 `spring-backend-specialist`를 더했다. 기준
  문구와 다른 행은 그대로다. 선행 규칙에는 다음 문장을 더했다. "백엔드 운영 리스크(정합성, 멱등성, 응답 속도, 보안, AOP
  리팩토링 여지 등)는 `spring-backend-specialist`가 더 깊게 점검하지만 사용자가 명시적으로 요청할 때만 쓰고, 그런 요청이
  없으면 `code-reviewer`가 "잠재적 문제" 항목에서 계속 본다."
- 볼트 `ResilientPOS/agents/code-reviewer.md`의 "역할 겹침:" 문단에 다음 문장을 더했다. "`spring-backend-specialist`는 사용자가
  명시적으로 요청할 때만 쓰는 별도 에이전트로, 백엔드 운영 리스크(정합성, 멱등성, 응답 속도, 보안 등)를 깊게 봅니다."
  수정 전 사본(`~/Documents/code-reviewer.md.2026-09-20.before-spring-backend-specialist.bak`)과 `diff`로 비교해 14번째 줄만
  바뀐 것을 확인했다.
- 볼트 `ResilientPOS/README.md`의 "에이전트" 표에서 `conflict-analyzer` 행 다음에 한 행을 더했다. 역할 칸의 문구는
  "백엔드 운영 리스크(정합성·멱등성·응답 속도·보안 등)를 열세 개 렌즈로 점검하는 읽기 전용 리뷰. 사용자가 명시적으로
  요청할 때만 사용"이고 모델 칸은 `opus`다. 수정 전 사본(`~/Documents/vault-README.md.2026-09-20.before-spring-backend-specialist.bak`)과
  `diff`로 비교해 한 줄 추가뿐임을 확인했다.
- 변경 기록(`docs/wiki/changes.md`, 2026-09-21 삭제)에 항목을 더했다. `borrowed-workflows.md`, `AGENTS.md`, 에이전트 정의 본문은 바뀌지 않았다.

### workflow-reviewer 검토 (2026-09-20)
spec, 기준 ref, 수정한 wiki 파일, 볼트의 두 에이전트 정의를 넘겨 읽기 전용으로 검토받았다. 판정은 WARN이고 지적은 네
건이었다. 처리는 범위 절의 "검토 후 범위 조정"에 적은 대로다.
- 높음, 반영: 볼트 README의 에이전트 표에 새 에이전트가 없었다.
- 중간, 반영: 선행 규칙의 처음 문구 "`spring-backend-specialist`가 맡되"가 점검의 소유가 옮겨 간 것으로 읽혔다.
- 낮음, 고치지 않음: 볼트 `reviewer-claude.md`에는 새 에이전트를 가리키는 반대 방향 열거가 없다.
- 낮음, 고치지 않음: `AGENTS.md` 30번째 줄이 차용 내역을 전체 에이전트 목록의 위치로 가리킨다.
- 문제없다고 확인된 것: 배정표 앵커와 추가한 링크의 대상 경로, 볼트 사본의 존재와 14번째 줄만 바뀐 사실, spec 첫
  커밋, TDD를 적용하지 않는 이유의 기재.
- 검토자가 확인하지 못한 것: 검토자에게 셸 도구가 없어서 `git diff`, `check_pr.py`, 단위 테스트는 실행하지 못했다. 이
  세 가지는 아래처럼 작성자가 실행했다.

### 기준 변경과 rebase
spec을 쓸 때의 기준은 origin/main `ee16971`이었고 PR #27은 열려 있었다. 제출 전에 `git fetch`로 다시 확인하니 #27이
머지되어 origin/main이 `8264730`이 되어 있었다. 사용자의 승인을 받아 rebase 했다. 충돌은 `docs/wiki/changes.md`의 파일
끝에서만 났고, #27의 항목을 먼저 두고 이 PR의 항목을 그 뒤에 두어 둘 다 남겼다. `git diff origin/main`에서 `changes.md`의
삭제 줄이 0개인 것으로 #27의 항목이 보존되었음을 확인했다. 이 spec의 커밋은 rebase 뒤에도 첫 커밋이다. 분할 계획
절의 "#27이 열려 있다"는 서술은 작성 시점의 사실이다. #27로 main의 PR 템플릿이 바뀌었으므로 PR 본문은 새 템플릿을
따른다.

### 검증 결과
- `python3 scripts/check_pr.py --base origin/main --worktree`와 `--worktree` 없는 실행 모두 PASS였다. rebase와 검토 지적
  반영 뒤에는 "일반 3개/129줄", 이 결과 절을 기록한 뒤의 마지막 실행에서는 "일반 3개/173줄"이었다.
- `python3 -m unittest discover -s scripts -p 'test_*.py'`는 96개가 통과했다.
- 이 spec의 부등호 여는 문자 개수는 커밋마다 0이었다.
- 추가한 링크의 대상(`agent-instructions.md`와 그 앵커 "에이전트 모델 배정과 스킬 선행 규칙", `borrowed-workflows.md`,
  `domain-model.md`, `2026-09-13-advisor-model-policy.md`, 이 spec)이 모두 실제 경로에 있다.
- `git diff --stat origin/main`의 변경 파일은 `docs/` 아래 문서 3개뿐이라 `./gradlew test`는 실행하지 않았다.
