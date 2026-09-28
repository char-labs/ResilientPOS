# PR spec: PR 단위를 개발 컨텍스트로 정하고 충돌 방지를 최우선 규칙으로 둔다

## 목적
PR을 코드 줄 수로 나누던 규칙을 없애고, 한 도입·변경 목표와 그에 딸린 테스트·문서·설정을 한 PR로 묶는 컨텍스트 단위로 바꾼다. 스택 PR을 금지하고, 열린 PR·main과의 충돌을 미리 막는 확인 절차를 규칙 맨 앞에 둔다.

배경(결정 근거):
- `scripts/check_pr.py`는 일반 변경 추가+삭제 900줄에서 경고, 1400줄 초과에서 실패했다. `docs/wiki/development-workflow.md` "크기와 분할" 절도 같은 규칙과 "합쳐서 실패 한도를 넘을 때만 스택을 만든다"를 적고 있었다.
- 2026-09-28 PR #64(트랜잭션 전파·이벤트 발행 정리)에 확장 함수 정리를 넣자 1400줄을 넘어, 그 정리를 #64 위의 스택 PR #65로 나눴다. 그 사이 #62(Kafka)와 #64가 main에 squash merge되어 #65가 CONFLICTING이 됐고, 같은 공용 테스트 헬퍼(`awaitUntil`)가 #62와 #65에서 따로 생겨 합쳐야 했다.
- 크기 한도는 리뷰 부담을 줄이려던 것인데, 실제로는 한 컨텍스트를 억지로 쪼개 스택을 만들고 충돌과 중복 구현을 낳았다.

사용자 결정:
1. PR 단위는 코드 규모가 아니라 개발 컨텍스트 단위다. 도입 때문에 양이 커지는 것은 받아들인다.
2. 충돌이 나지 않게 하는 것이 최우선이다.

## 범위
- `check_pr.py`: 일반 변경 줄 수로 경고·실패하지 않는다. 크기는 통계 줄(`일반 N개/M줄; 원문 ...`)에만 참고 수치로 남긴다. 원문 보관 한도, 바이너리 거절 등 크기 외 검사는 그대로다.
- `test_check_pr.py`: 크기 경계 테스트와 wiki 한도 대조 테스트를 "아무리 커도 크기로는 오류·경고가 없다"는 테스트로 바꾼다.
- `docs/wiki/development-workflow.md`: "크기와 분할" 절, "다음 작업을 고를 때", "epic 브랜치와 PR 단위"를 컨텍스트 단위·스택 금지·충돌 방지 절차로 고친다. wiki에는 최종 규칙만 적는다.
- `AGENTS.md`: 개발 계약의 PR 단위 문장을 컨텍스트 단위·충돌 방지 최우선으로 한 줄 고치고 상세는 wiki로 링크한다.
- 크기 한도를 전제한 다른 문서: `.github/PULL_REQUEST_TEMPLATE.md` 체크 항목, `.github/workflows/pr-checks.yml` 주석, `docs/wiki/agent-instructions.md`의 "PR 크기", `docs/spec-template.md` 분할 계획 안내, `README.md`의 "PR 분할 기준" 표현, `docs/wiki/borrowed-workflows.md` 계약 조정 표의 "작은 PR 분할".
- 저장소 밖(커밋 대상 아님): 볼트 스킬 `resilientpos-git`의 PR 단위 원칙·계약 영향 주의, `resilientpos-commit-push`의 PR 총량 한도, orchestrator·develop 스킬의 한도 언급을 같은 규칙으로 고친다.

## 제외
- 과거 PR spec(`docs/spec/2026-*.md`)은 역사 기록이라 고치지 않는다.
- spec 필수 절 "분할 계획"의 이름과 `SECTIONS`는 바꾸지 않는다. 절 내용 안내만 컨텍스트 기준으로 고친다.
- 열린 PR과의 경로 겹침을 자동으로 계산하는 도구는 만들지 않는다. 절차는 `git fetch`·`gh pr list`·spec "변경 경로" 대조로 사람이 확인한다.
- 애플리케이션 코드와 통합 테스트는 건드리지 않는다.

## 관련 문서
없음

## 변경 경로
- scripts/check_pr.py
- scripts/test_check_pr.py
- docs/wiki/development-workflow.md
- docs/wiki/borrowed-workflows.md
- docs/wiki/agent-instructions.md
- docs/spec-template.md
- AGENTS.md
- README.md
- .github/PULL_REQUEST_TEMPLATE.md
- .github/workflows/pr-checks.yml

## 인수 조건
- 일반 변경이 수천 줄이어도 `check(...)`가 크기 때문에 오류·경고를 내지 않고, 통계 줄에는 줄 수가 계속 나온다.
- `check_pr.py`에 `WARN_LINES`·`FAIL_LINES`와 "분할하세요" 문구가 없다.
- 원문 보관 한도(10개 파일/5000줄)와 바이너리 거절 테스트는 그대로 통과한다.
- wiki "개발과 PR 규칙"이 PR 단위 = 개발 컨텍스트, 스택 PR 금지, 작업 시작·push·PR 생성·머지 직전 충돌 확인, 다른 PR 머지 후 즉시 rebase·전체 재검증, 공용 헬퍼·규칙 중복 확인을 적는다. 900/1400줄 한도와 stacked PR 허용 문장이 없다.
- `grep -rn "1400\|900줄\|실패 한도\|경고선\|stacked" AGENTS.md README.md docs/wiki .github docs/spec-template.md`가 아무것도 찾지 않는다.
- 볼트 스킬에서 900/1400줄 한도와 스택 허용 문장이 사라진다.

## 검증
```sh
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/check_pr.py --base origin/main --worktree
python3 scripts/check_pr.py --base origin/main
grep -rn "1400\|900줄\|실패 한도\|경고선\|stacked" AGENTS.md README.md docs/wiki .github docs/spec-template.md
```
- 정상: 하네스 테스트 전부 통과, 두 `check_pr.py` 실행이 PASS, grep 결과 없음.
- 실패 시나리오: 크기 판정을 되살리면 새 크기 테스트가 오류·경고 목록이 비지 않아 실패한다.

## Wiki 영향
- [개발과 PR 규칙](../wiki/development-workflow.md): "크기와 분할"을 "PR 단위와 충돌 방지"로 바꾸고, 다음 작업 고르기·epic 절을 새 규칙에 맞춘다. PR 단위 요구사항이 바뀌므로 본문을 고친다.
- [차용 내역](../wiki/borrowed-workflows.md): 계약 조정 표의 저장소 계약 "작은 PR 분할"을 컨텍스트 단위로 고친다.

## 분할 계획
PR 단위 규칙 하나를 바꾸는 한 컨텍스트다. 하네스·wiki·지침·템플릿이 같은 규칙을 말해야 하므로 나누지 않는다. 예상 변경은 10개 파일 안팎, 200줄 안쪽이다. 열린 PR #65(`src/**`, `build.gradle.kts`, `docs/wiki/domain-model.md`, 자기 spec)와 경로가 겹치지 않는다.

## TDD 검증 기록
하네스 동작(크기 판정 제거)만 테스트 대상이다. 문서 변경은 grep과 `check_pr.py`로 확인한다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 큰 변경도 크기로는 오류·경고가 없다 (`test_size_is_not_judged`) | `python3 -m unittest discover -s scripts -p 'test_*.py'` 실패: 5000줄 변경에 "PR 상한 1400줄 초과" 오류(하위 클래스 상속으로 5회) | 같은 명령 132개 통과 | 같은 명령 132개 통과 |

## 결과
개발 전 작성. 개발 후 실행 결과를 추가한다.

- 하네스: `WARN_LINES`·`FAIL_LINES`와 크기 경고·실패를 지웠다. 통계 줄 `일반 N개/M줄; 원문 ...`은 그대로다. 크기 경계 테스트와 wiki 한도 대조 테스트를 `test_size_is_not_judged` 하나로 바꾸고, 그 때문에 안 쓰이게 된 `REPO_ROOT`를 지웠다.
- wiki: "크기와 분할"을 "PR 단위와 충돌 방지"로 바꾸고 "충돌 방지: 가장 먼저 지킨다" 하위 절을 두었다. "다음 작업을 고를 때"는 스택 선택지를 빼고 기다리기·합치기만 남겼다. epic 절은 컨텍스트마다 `main` 기준 PR로 나누고 다른 PR 브랜치를 base로 삼지 않게 고쳤다. "개발 시작" 2단계가 충돌 방지 절을 링크한다.
- 저장소 밖 볼트: git·commit-push·orchestrator(SKILL, merge-coordinator)·develop(SKILL, phase-3)·review(SKILL, review-criteria)·agent-design-principles·workflow 스킬, spec-validator·workflow-reviewer 에이전트, engineering-principles 규칙의 크기 한도·스택 문장을 같은 규칙으로 고쳤다. 규칙을 고쳐 `make bootstrap`을 다시 실행했다.
- workflow-reviewer 검토(WARN)를 반영했다: README "범위가 커지면 후속 PR", wiki의 "PR 총량 제한"과 "검사기가 크기를 검사한다"는 문장, `agent-instructions.md`·`pr-checks.yml`·차용 내역의 "크기" 표현, 볼트의 MIGRATION 대응표·spec-validator·engineering-principles·merge-coordinator·agent-monitor·autonomous-driving·phase-4-merge·cli-reference의 "크기" 검사 표현과 조사 오류를 고쳤다. 변경 경로에 `docs/wiki/agent-instructions.md`, `.github/workflows/pr-checks.yml`을 더했다.
- 하네스 테스트 132개 통과. `check_pr.py --base origin/main --worktree` PASS. 인수 조건의 grep은 결과 없음.
