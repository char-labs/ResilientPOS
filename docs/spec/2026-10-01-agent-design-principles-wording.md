# PR spec: agent-design-principles 스킬의 호출 서술을 실제 동작에 맞춘다

## 목적
`docs/wiki/borrowed-workflows.md` 38행 표는 `resilientpos-agent-design-principles`를 "내부 참조, 자동 호출 안 됨"이라고 적는다. 실제 동작과 다르므로 괄호 안 서술을 고친다.

배경(결정 근거):
- 그 스킬의 frontmatter는 `user-invocable: false`다. 이 설정은 `/` 메뉴에서만 스킬을 빼고 description은 컨텍스트에 남기므로 Claude가 자동 호출할 수 있다. 근거는 [Claude Code skills 문서](https://code.claude.com/docs/en/skills)의 호출 제어 표("user-invocable: false ... Description always in context")다.
- `workflow-reviewer` 에이전트가 frontmatter `skills:`로 이 스킬을 사전 로드한다. `disable-model-invocation: true`를 켜면 사전 로드가 막히므로 쓰지 않는다. 근거는 [sub-agents 문서](https://code.claude.com/docs/en/sub-agents)의 "You can't preload skills that set disable-model-invocation: true"다.
- 같은 뜻의 서술이 `docs/wiki/agent-instructions.md` 66행에 이미 있고, 볼트 README도 같은 뜻으로 고쳐져 있다.

## 범위
- `docs/wiki/borrowed-workflows.md` 38행의 괄호를 "내부 참조, `/` 메뉴에는 없고 Claude가 자동 호출할 수 있으며 `workflow-reviewer`가 사전 로드"로 바꾼다.

## 제외
- 38행의 괄호 밖 문구, 같은 파일의 다른 줄, 다른 파일은 건드리지 않는다.
- 스킬 frontmatter와 `workflow-reviewer` 에이전트 정의는 바꾸지 않는다. 동작은 그대로 두고 문서만 맞춘다.
- 볼트 README는 이미 고쳐져 있어 대상이 아니다.

## 관련 문서
없음

## 변경 경로
- docs/wiki/borrowed-workflows.md

## 인수 조건
- `docs/wiki/borrowed-workflows.md`에 "자동 호출 안 됨"이라는 문구가 없다.
- 38행 괄호가 `/` 메뉴 제외, Claude 자동 호출 가능, `workflow-reviewer` 사전 로드 세 가지를 말한다.
- origin/main 대비 `docs/wiki/borrowed-workflows.md`의 diff가 38행 한 줄뿐이다.

## 검증
```sh
grep -n "자동 호출 안 됨" docs/wiki/borrowed-workflows.md
git diff --stat origin/main -- docs/wiki
python3 scripts/check_pr.py --base origin/main --worktree
python3 scripts/check_pr.py --base origin/main
```
- 정상: grep 결과 없음, diff는 `borrowed-workflows.md` 한 줄 추가·한 줄 삭제, `check_pr.py`는 두 모드 모두 PASS.
- 실패 시나리오: 옛 문구가 남으면 grep이 그 줄을 찾는다. 다른 줄을 건드리면 diff 통계가 한 줄을 넘는다.

## Wiki 영향
- [차용 내역](../wiki/borrowed-workflows.md): 스킬 표의 호출 서술 한 줄을 실제 동작에 맞게 고친다. 요구사항과 동작은 바뀌지 않는다.
- [LLM 지침 설계 근거](../wiki/agent-instructions.md): 66행이 이미 같은 뜻을 적고 있어 고치지 않는다.

## 분할 계획
wiki 표의 서술 한 줄을 바로잡는 한 컨텍스트다. 작업 시작 시 origin/main은 7712a45이고, 열린 PR은 #72(`feature/sync-request-checkout`) 하나다. #72는 `docs/wiki/domain-model.md`, `docs/wiki/entity-map.md`와 `src/**`를 바꾸며 `docs/wiki/borrowed-workflows.md`와 겹치지 않는다.

## TDD 검증 기록
문서 한 줄만 바뀌는 작업이라 업무 동작 테스트가 없다. grep과 `check_pr.py`로 확인한다.

## 결과
개발 전 작성. 개발 후 실행 결과를 추가한다.

- `docs/wiki/borrowed-workflows.md` 38행 괄호를 "내부 참조, `/` 메뉴에는 없고 Claude가 자동 호출할 수 있으며 `workflow-reviewer`가 사전 로드"로 바꿨다.
- 고치기 전에 실제 설정을 대조했다. 스킬 frontmatter는 `user-invocable: false`이고 `disable-model-invocation`은 없다. `workflow-reviewer` 에이전트의 `skills:`에 이 스킬이 있다.
- `grep -n "자동 호출 안 됨" docs/wiki/borrowed-workflows.md` 결과 없음. `git diff --stat origin/main -- docs/wiki`는 `borrowed-workflows.md` 한 줄 추가·한 줄 삭제.
- `check_pr.py --base origin/main --worktree` PASS, `check_pr.py --base origin/main` PASS.
