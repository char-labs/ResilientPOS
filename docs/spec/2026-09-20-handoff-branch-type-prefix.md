# PR spec: 핸드오프 worktree의 브랜치를 작업 유형 prefix로 만들기

## 목적
AGENTS.md의 개발 계약은 main 기준 새 작업을 `orca worktree create --name 작업이름`으로 넘기라고 규정한다. 그런데
Orca는 앱 설정(`branchPrefix = git-username`) 때문에 브랜치를 `char-yb/작업이름`으로 만든다. git 스킬의 브랜치 명명
표는 `feature/`, `fix/`, `docs/` 같은 작업 유형 prefix를 요구하므로 핸드오프로 만든 브랜치만 규칙에서 벗어난다.
이 PR은 넘기는 세션이 작업 유형 prefix로 브랜치 이름을 바꾸는 절차를 개발 계약에 추가한다.

## 범위
- AGENTS.md "개발 계약"의 `orca worktree create` 항목에 아래 규칙을 한두 문장으로 더한다.
  - 넘기는 세션은 작업 프롬프트에서 작업 유형을 추론해 git 스킬의 브랜치 명명 표(`feature/`, `fix/`, `docs/`,
    `refactor/`, `perf/`, `test/`)에 맞는 prefix를 고른다.
  - `orca worktree create` 직후, 새 세션이 push하기 전에 `git -C worktree경로 branch -m 유형/작업이름`으로 브랜치
    이름을 바꾼다. worktree 경로는 create 결과 JSON의 `result.worktree.path`에 있다.
  - 이미 push되어 PR이 열린 브랜치는 이름을 바꾸지 않는다.
- [개발 규칙](../wiki/development-workflow.md) "개발 시작" 2번 단계에 같은 규칙을 한 문장으로 맞춘다. 이 단계는 작업
  브랜치 생성을 설명하지만 이름 규칙이 없어, wiki만 읽으면 Orca가 붙인 이름을 그대로 쓰게 된다.
- 변경 기록(`docs/wiki/changes.md`, 2026-09-21 삭제)에 이 spec 링크와 요구사항 영향 근거를 남긴다.

## 제외
- Orca 앱 설정(`branchPrefix`)은 바꾸지 않는다. prefix를 없애도 작업 유형 prefix를 붙이려면 이름 변경이 어차피
  필요하다.
- 볼트에 있는 스킬은 수정하지 않는다. git 스킬의 브랜치 명명 표는 이미 기준으로 쓸 수 있다.
- 기존 브랜치의 이름은 바꾸지 않는다. 열린 PR #26과 #27의 `char-yb/` 브랜치는 그대로 둔다. 원격 브랜치 이름을
  바꾸면 열린 PR이 닫힌다.
- 이름 변경을 자동화하는 스크립트나 훅은 만들지 않는다. 명령 한 줄이고 prefix 선택은 넘기는 세션의 판단이다.

## 관련 문서
없음. 이 PR은 장기 스펙 계층을 따르지 않는 개발 절차 문서 갱신이다. 핸드오프 규칙을 처음 추가한 선행 PR spec은
[agents-md-currency](2026-09-20-agents-md-currency.md)이다.

## 변경 경로
- AGENTS.md
- docs/**

## 인수 조건
- AGENTS.md의 핸드오프 항목에 prefix 추론, create 직후 push 전의 `git branch -m`, PR이 열린 브랜치는 제외라는 세
  규칙이 모두 있다.
- AGENTS.md에 추가한 분량이 두 문장을 넘지 않고, 나열한 prefix가 git 스킬의 브랜치 명명 표와 일치한다.
- [개발 규칙](../wiki/development-workflow.md)의 작업 브랜치 생성 단계가 AGENTS.md의 규칙과 어긋나지 않는다.
- 추가된 문장이 모두 한국어 평서문이고 기존 절 구조를 유지한다.

## 검증
- `python3 scripts/check_pr.py --base origin/main --worktree`가 PASS로 끝난다. 제출 전에 `--worktree` 없이 한 번 더
  실행한다.
- `python3 -m unittest discover -s scripts -p 'test_*.py'`가 통과한다.
- 이 spec 파일에 부등호 여는 문자가 없는지 커밋 전에 `grep -c`로 확인해 0을 얻는다.
- 애플리케이션 코드가 바뀌지 않았으므로 `./gradlew test`는 실행하지 않는다. `git diff --stat`으로 변경 파일이
  문서와 AGENTS.md뿐임을 확인한다.
- 규칙 자체는 이 PR의 브랜치로 이미 한 번 실행되었다. 넘긴 세션이 Orca가 만든 브랜치를
  `docs/handoff-branch-type-prefix`로 바꾸었고, 원격 브랜치 이름도 같은지 push할 때 확인한다.

## Wiki 영향
[개발 규칙](../wiki/development-workflow.md)의 "개발 시작" 2번 단계에 브랜치 이름 규칙을 더하고,
변경 기록(`docs/wiki/changes.md`, 2026-09-21 삭제)에 이 spec 링크를 남긴다. 제품 요구사항과 설계는 바뀌지 않는다. 개발 절차 중 핸드오프
worktree의 브랜치 이름을 정하는 단계만 바뀐다.

## 분할 계획
문서 4개, 추가·수정 줄 수는 80줄 미만으로 예상한다. 개발 규칙의 파일·줄 수 한도에서 멀어 분할하지 않는다.
열린 PR #26과 #27이 모두 `docs/wiki/changes.md` 끝에 항목을 추가하므로 나중에 머지되는 쪽에서 충돌이 난다. 제출
직전에 origin/main을 다시 확인하고, 충돌이 나면 두 항목을 모두 남기는 방향으로 rebase 한다. #27이 고치는
`development-workflow.md`의 "PR 본문" 절은 이 PR이 고치는 "개발 시작" 절과 떨어져 있어 충돌하지 않는다.

## TDD 검증 기록
적용하지 않는다. 문서 전용 변경이라 업무 동작이 바뀌지 않고 실패시킬 테스트 대상이 없다. 대신 위 검증 절의 명령과
AGENTS.md·git 스킬 표의 대조로 확인한다.

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.

### 개발 후 (2026-09-20)
- AGENTS.md의 핸드오프 항목에 두 문장을 더했다. 첫 문장이 prefix 추론과 create 직후 push 전의 `git branch -m`을,
  둘째 문장이 PR이 열린 브랜치 제외를 담는다. 나열한 prefix 여섯 개는 git 스킬의 브랜치 명명 표와 같다.
- [개발 규칙](../wiki/development-workflow.md) "개발 시작" 2번 단계에 브랜치 이름 규칙을 더했다. 기존 문장과 모순은
  없었지만 이름 규칙이 아예 없어 wiki만 읽으면 Orca가 붙인 이름을 그대로 쓰게 되므로 맞췄다.
- `python3 scripts/check_pr.py --base origin/main --worktree`와 `--worktree` 없는 실행 모두 "일반 4개/80줄"에
  PASS였다. `python3 -m unittest discover -s scripts -p 'test_*.py'`는 64개가 통과했다.
- 이 spec의 부등호 여는 문자 개수는 0이다. 변경 파일은 AGENTS.md와 `docs/` 아래 문서 3개뿐이라 `./gradlew test`는
  실행하지 않았다.
