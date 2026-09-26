# PR spec: bootstrap이 볼트 rules를 링크 대신 실제 파일로 복사한다

## 목적
2026-09-26에 `claude -p`로 실측한 결과, Claude Code는 `.claude/rules`가 디렉터리 심볼릭 링크일 때도, 실제 디렉터리 안에
파일 단위 심볼릭 링크를 둘 때도 규칙을 싣지 않고 실제 파일일 때만 싣는다. 같은 방식의 `.claude/skills` 링크는 정상 동작한다.
그래서 [rules-link-and-ignores](2026-09-13-rules-link-and-ignores.md) 이후 볼트 `ResilientPOS/rules/*.md` 6개가 한 번도
로드되지 않았다. 또 새로 만든 worktree에는 bootstrap이 실행되지 않아 `.claude` 자체가 없다.
`make bootstrap`이 `.claude/rules`만 볼트에서 실제 파일로 복사하게 바꾸고, 새 worktree에서 bootstrap을 먼저 실행하는 규칙을 둔다.

## 범위
- `scripts/link_claude.py`에서 `.claude/rules`를 `LINKS`에서 빼고 복사 대상으로 옮긴다. 복사는 미러링이다.
  기존 `.claude/rules`를 지우고 볼트 `rules/`를 통째로 다시 복사하므로 로컬 수정은 덮어쓰고, 볼트에서 지운 파일은 함께 사라진다.
- 기존 `.claude/rules`가 심볼릭 링크이면 링크 자체만 `unlink`한다. 링크를 따라가 삭제하지 않으므로 볼트 원본은 남는다.
- 볼트에 `rules/`가 없으면 지금처럼 건너뛴다. 이때 기존 복사본은 건드리지 않는다.
- skills·agents·`.codex/agents`·`docs/wiki/current-state.md` 링크는 그대로 둔다.
- 볼트를 고친 뒤에는 bootstrap을 다시 실행해야 rules가 반영된다는 점을 README, `docs/wiki/agent-instructions.md`, 스크립트 docstring에 적는다.
- `AGENTS.md`와 `docs/wiki/development-workflow.md`에, 새 worktree를 만들면 에이전트 세션을 시작하기 전에 `make -C worktree경로 bootstrap`을
  실행한다고 적는다. worktree를 만드는 도구(Orca, cmux, `git worktree` 등)에 의존하지 않는 규칙으로 쓴다. 세션이 이미 시작된 뒤에
  실행하면 그 세션에는 rules가 실리지 않을 수 있다는 한계를 함께 적는다.

## 제외
- 볼트 `rules/*.md` 내용 변경, 볼트 스킬(orchestrator 등)의 핸드오프 절차 수정.
- 특정 worktree 도구의 setup hook 설정. 저장소 밖 설정이므로 이번 PR에서 다루지 않는다.
- 복사본 로컬 수정 감지(manifest·해시 비교)와 백업. 복사본은 bootstrap이 소유하는 생성물로 취급한다.
- skills·agents 링크 방식 변경.

## 관련 문서
없음.

## 변경 경로
- scripts/link_claude.py
- scripts/test_link_claude.py
- Makefile
- README.md
- AGENTS.md
- docs/wiki/agent-instructions.md
- docs/wiki/development-workflow.md
- docs/wiki/borrowed-workflows.md
- docs/spec/2026-09-26-rules-copy-bootstrap.md

## 인수 조건
1. `make bootstrap` 뒤 `.claude/rules`는 심볼릭 링크가 아닌 실제 디렉터리이고, 안의 파일도 모두 실제 파일이며 볼트 `rules/*.md`와 내용이 같다.
2. 기존 `.claude/rules`가 볼트를 가리키는 링크이면 bootstrap이 링크를 복사본으로 바꾸고, 볼트 `rules/` 원본 파일은 그대로 남는다.
3. 복사본을 로컬에서 고친 뒤 다시 실행하면 볼트 내용으로 돌아온다. 볼트에서 지운 파일은 복사본에서도 사라진다.
4. 볼트에 `rules/`가 없으면 `.claude/rules`를 만들지도 지우지도 않고 종료 코드 0으로 끝난다.
5. 나머지 네 경로는 지금처럼 볼트를 가리키는 링크이고, 두 번 실행해도 결과가 같다.
6. 실제 `claude -p --model haiku` 세션에서 볼트 rules 내용이 컨텍스트에 실린다.

## 검증
- RED: 테스트를 먼저 고친 뒤 `python3 -m unittest discover -s scripts -p 'test_link_claude.py'`가 rules가 링크로 만들어져 실패하는지 확인한다.
- GREEN·회귀: `python3 -m unittest discover -s scripts -p 'test_*.py'`가 통과한다.
- 실측: 이 worktree에서 `make bootstrap`을 실행한 뒤 `claude -p --model haiku`로 `engineering-principles` 규칙의 특정 문구를 컨텍스트에서 인용하게 해서 실렸는지 확인한다.
- `python3 scripts/check_pr.py --base origin/main --worktree`와 제출 전 `--worktree` 없는 실행이 PASS다.

## Wiki 영향
- [지침 설계 근거](../wiki/agent-instructions.md): rules는 링크가 아닌 복사본이고, 볼트 수정 후 bootstrap을 다시 실행해야 한다는 사실과 실측 근거를 적는다.
- [개발 규칙](../wiki/development-workflow.md): 새 worktree에서 세션 시작 전 bootstrap 실행을 넣는다.
- [차용 내역](../wiki/borrowed-workflows.md): "rules-link-and-ignores 이후 자동 로드"라는 틀린 서술을 이번 spec으로 바로잡는다.
- 요구사항 변화: 없다. 개발 환경 구성만 바뀐다.

## 분할 계획
9개 파일, 추가와 삭제 합계 150줄 안쪽이다. 경고선(20개 파일/600줄)에 이르지 않는다.

## TDD 검증 기록
업무 동작 변경이 아니다. 하네스 스크립트 동작은 unittest로 RED→GREEN을 거친다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| test_copies_rules_as_real_files, test_replaces_rules_symlink_without_touching_vault, test_mirrors_vault_over_local_edits_and_stale_files, test_skips_rules_when_vault_lacks_it | `python3 -m unittest discover -s scripts -p 'test_link_claude.py'`, `COPIES`가 없어 import 실패 | 같은 명령, 통과 | `python3 -m unittest discover -s scripts -p 'test_*.py'`, 136개 OK |

## 결과
개발 전 spec을 독립 커밋으로 작성했다. 구현 후 아래를 확인했다.

- `link_claude.py`는 `.claude/rules`를 `LINKS`에서 빼고 `COPIES`로 옮겼다. `copy_one`은 기존 경로가 링크면 링크만 지우고, 실제 디렉터리면 지운 뒤 `shutil.copytree`로 볼트를 복사한다.
- 하네스 136개 통과.
- 이 worktree에서 `make bootstrap`을 두 번 실행해 두 번 모두 `linked 4/4, copied 1/1`을 확인했다. `.claude/rules`와 안의 6개 파일은 실제 파일이다.
- 실측: 도구를 막은 `claude -p --model haiku`에게 engineering-principles의 첫 `##` 제목을 컨텍스트에서만 인용하게 했다. 복사본일 때는 `## 설계 최우선 원칙 (Design First)`를 인용했다.
  대조군으로 `.claude/rules`를 볼트 디렉터리 링크로 되돌리자 `NOT_LOADED`로 답했다.
- 그 링크 상태에서 bootstrap을 다시 실행하자 `.claude/rules`가 실제 디렉터리로 바뀌었고, 볼트 `rules/`의 파일 6개는 그대로 남았다.
- `python3 scripts/check_pr.py --base origin/main --worktree`: PASS, 일반 9개/175줄.
