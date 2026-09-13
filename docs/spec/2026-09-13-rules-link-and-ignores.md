# PR spec: 볼트 rules 링크와 워크플로우 산출물 추적 제외

## 목적
2026-09-13에 Obsidian 볼트 `ResilientPOS/`에 공통 노하우를 전부 이식하면서 `rules/` 폴더가 새로 생겼다.
저장소의 `scripts/link_claude.py`는 `skills`·`agents`·`codex/agents` 세 경로만 링크하므로 Claude Code가
`rules/`를 자동 로드하지 못한다. 또 이식된 스킬들이 `.develop-workflow/`, `.review/`, `.review-output/`,
`.orchestrator/`, `.plans/`에 휘발성 산출물을 쓰는데 `.gitignore`에 없어 실수로 커밋될 수 있다.
링크 목록에 `.claude/rules`를 추가하고 산출물 경로를 추적에서 제외한다. 볼트 `MIGRATION.md`의
계약 영향 표 중 "rules 링크"와 "휘발성 산출물" 행을 해소하는 PR이다.

## 범위
- `scripts/link_claude.py`의 `LINKS`에 `('.claude/rules', 'rules')`를 추가한다. 볼트에 `rules/`가 없으면
  기존 규칙대로 건너뛴다. 멱등성과 실제 디렉터리 보호 동작은 그대로다.
- `scripts/test_link_claude.py`에 rules 링크가 생성되는 테스트와 볼트에 rules가 없을 때 건너뛰는
  테스트를 먼저 추가하고 구현한다.
- `.gitignore`의 LLM 개발 도구 절에 `.develop-workflow/`, `.review/`, `.review-output/`, `.orchestrator/`,
  `.plans/`를 추가한다. `.claude/`는 이미 통째로 제외되어 있어 rules 링크도 추적되지 않는다.
- `AGENTS.md`의 개발 계약 절에서 심볼릭 링크 목록에 `.claude/rules`를 넣는다.
- `docs/wiki/agent-instructions.md`의 "스킬·에이전트 정본의 위치" 절에 rules 링크와 자동 로드 사실을
  한 줄 추가한다. `docs/wiki/changes.md`에 이번 spec 링크와 요구사항 영향을 남긴다.
- `Makefile`의 bootstrap 설명 주석이 세 링크를 나열하면 네 개로 고친다.

## 제외
볼트 `rules/*.md`의 내용 변경, `AGENTS.md`의 작업별 읽기 절과 개발 규칙 조정(각각 후속 PR
`wiki-vault-migration-sync`, `development-rules-original-first`), `check_pr.py` 동작 변경, hook 등록,
Codex 에이전트 배선, 애플리케이션 코드 변경.

## 변경 경로
- scripts/link_claude.py
- scripts/test_link_claude.py
- .gitignore
- AGENTS.md
- Makefile
- docs/wiki/agent-instructions.md
- docs/wiki/changes.md
- docs/spec/2026-09-13-rules-link-and-ignores.md

## 인수 조건
- `make bootstrap` 실행 후 `.claude/rules`가 볼트 `ResilientPOS/rules`로 해소되는 심볼릭 링크로 존재하고,
  그 아래 `engineering-principles.md`와 `agent-design-principles.md`가 열린다.
- 볼트에 `rules/`가 없는 환경에서는 링크를 만들지 않고 안내만 출력하며 종료 코드가 0이다.
- 두 번 연속 실행해도 네 링크의 대상이 같다.
- `git check-ignore .develop-workflow/state.json .review/x.md .review-output/x.json .orchestrator/e/decisions/log.md .plans/epic-x.md`가
  다섯 경로를 모두 무시 대상으로 판정한다.
- `git ls-files`에 `.claude/rules` 아래 파일이 나오지 않는다.
- `AGENTS.md`와 `docs/wiki/agent-instructions.md`가 링크 네 개를 일관되게 서술한다.

## 검증
- RED: 새 테스트 두 개를 추가한 뒤 `python3 -m unittest discover -s scripts -p 'test_link_claude.py'`가
  rules 링크 부재로 실패하는지 확인한다.
- GREEN: `LINKS`를 고친 뒤 같은 명령이 통과한다.
- 회귀: `python3 -m unittest discover -s scripts -p 'test_*.py'`(하네스 전체)가 통과한다.
- `python3 scripts/link_claude.py`를 두 번 실행하고 `readlink .claude/rules`가 볼트 경로인지 확인한다.
  `RESILIENTPOS_VAULT=/없는/경로 python3 scripts/link_claude.py`의 종료 코드가 0인지 확인한다.
- `git check-ignore -v`로 다섯 산출물 경로의 무시 규칙을 확인한다.
- 개발 중과 제출 전에 `python3 scripts/check_pr.py --base main --worktree`와 `--base main`을 실행한다.
  이 PR의 `.gitignore` 변경은 링크 경로를 건드리지 않으므로 심볼릭 링크 오류가 나지 않아야 한다.

## Wiki 영향
`docs/wiki/agent-instructions.md`에 rules 정본 위치와 링크를 기록한다. `docs/wiki/changes.md`에 spec 링크와
요구사항 영향을 남긴다. 제품 요구사항은 바뀌지 않으며 개발 환경 링크와 추적 제외 규칙만 바뀐다.
`development-workflow.md`와 `borrowed-workflows.md`는 후속 PR에서 다룬다.

## 분할 계획
예상 변경은 8개 파일, 추가와 삭제 합계 120줄 안쪽이다. 경고선(20개 파일/600줄)에 이를 여지가 없다.
wiki 계약 갱신과 개발 규칙 조정은 처음부터 별도 spec으로 분리했고, 병합 순서는 이 PR을 먼저 두는 것을
권장한다(후속 두 PR이 `.claude/rules` 링크의 존재를 전제로 서술한다).

## TDD 검증 기록
`scripts/link_claude.py`의 rules 링크 추가에 적용한다. 나머지 `.gitignore`·문서 변경은 업무 동작이 아니므로
적용하지 않는다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| test_links_rules_directory | 미실행 | 미실행 | 미실행 |
| test_skips_rules_when_vault_lacks_it | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
