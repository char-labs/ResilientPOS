# PR spec: 볼트 전체 이식에 맞춘 wiki와 공통 지침 진입점 갱신

## 목적
2026-09-13에 Obsidian 볼트 `ResilientPOS/`로 `Global-claude-code`의 스킬 18개·에이전트 17개·rules 2개가 새로
들어왔다. 저장소의 `docs/wiki/borrowed-workflows.md`는 2026-09-07의 선별 차용(스킬 3개·에이전트 2개)만 기록하고
orchestrator·grill·spec-write·rules 생성기·CLI/hook을 "보류"로 적고 있어 현재 사실과 다르다.
`AGENTS.md`의 작업별 읽기 절도 새 진입점(grill, git, orchestrator, setup)을 안내하지 않는다.
이식 결정(원본 방식 우선, 역할별 모델 배정)과 새 구성 요소를 wiki와 공통 지침에 반영해, 볼트가 없는
환경에서도 무엇이 있고 언제 쓰는지 읽을 수 있게 한다.

## 범위
- `docs/wiki/borrowed-workflows.md`를 다시 쓴다. 2026-09-07 선별 차용 기록은 이력 절로 남기고,
  2026-09-13 전체 이식의 도입 항목 표(스킬 21개·에이전트 19개·rules 2개), 원본 방식 우선 결정,
  이 머신에 없는 도구(codex·gemini·fleta·git-utils CLI)와 그 처리 방식, 출처 식별(볼트 `MIGRATION.md`)을
  기록한다. 기존 "수정하거나 보류한 항목" 표는 이번에 들어온 항목을 표시하고, 아직 계약이 조정되지
  않은 항목은 후속 PR `development-rules-original-first`로 연결한다.
- `docs/wiki/agent-instructions.md`에 에이전트 19개의 역할별 모델 배정 원칙(opus/sonnet/haiku)과 그 근거,
  스킬 description이 서로 경합할 때의 선행 규칙(합의 전이면 grill 먼저)을 적는다.
- `docs/wiki/workflow-review.md`의 "도구 개수·모델 이름만으로 결함을 판단하지 않는다" 문장을 유지하되,
  이식된 `workflow-reviewer`가 도구 개수·모델을 신호로 쓰는 안티패턴 표를 갖고 있음을 밝히고
  "신호로 참고하되 판정 근거는 역할·영향·구체적 근거"로 기준을 정리한다.
- `docs/wiki/README.md`의 작업별 진입점에 설계 심문(grill), Git 작업(git), 다중 작업 위임(orchestrator),
  설정(setup), 글쓰기(communicate·humanizer)를 추가한다.
- `AGENTS.md`의 작업별 읽기와 검증 절에 위 진입점을 한 줄씩 추가한다. 루트 지침은 짧게 유지하며 상세는
  wiki 링크로 연결한다.
- `docs/wiki/changes.md`에 이번 spec 링크와 요구사항 영향을 남긴다.

## 제외
개발 규칙 자체의 조정(spec 계층 위치, PR 단위, rebase 정책, 커밋 형식, PR 본문 양식, Checkpoint 관계,
`.plans/`·`.develop-workflow.yaml` 허용 여부)은 후속 PR `development-rules-original-first`가 맡는다.
`scripts/link_claude.py`와 `.gitignore`는 선행 PR `rules-link-and-ignores`가 맡는다. 볼트 파일 수정,
스킬·에이전트 본문 변경, `docs/references/` 변경, 애플리케이션 코드 변경.

## 변경 경로
- AGENTS.md
- docs/wiki/borrowed-workflows.md
- docs/wiki/agent-instructions.md
- docs/wiki/workflow-review.md
- docs/wiki/README.md
- docs/wiki/changes.md
- docs/spec/2026-09-13-wiki-vault-migration-sync.md

## 인수 조건
- `docs/wiki/borrowed-workflows.md`가 볼트 `ResilientPOS/README.md`의 스킬 21개·에이전트 19개 목록과 이름
  단위로 일치하고, "보류" 항목 중 이번 이식으로 들어온 것을 보류로 남겨 두지 않는다.
- `docs/wiki/agent-instructions.md`의 모델 배정 표가 볼트 `agents/*.md`의 `model:` 값과 일치한다.
- `AGENTS.md`가 200줄 미만을 유지하고, 새로 추가한 진입점 문장이 각각 실제 존재하는 스킬 이름을 가리킨다.
- `docs/wiki/README.md`의 진입점 링크가 모두 실제 파일 또는 `.claude/skills/...` 경로로 해소된다.
- `docs/wiki/workflow-review.md`가 새 리뷰어 정의와 모순되는 문장을 남기지 않는다.
- 저장소 어디에도 사용자 홈 절대 경로가 들어가지 않는다.

## 검증
- `python3 scripts/check_pr.py --base main --worktree`(개발 중)와 `--base main`(제출 전)이 통과한다.
- `python3 -m unittest discover -s scripts -p 'test_*.py'`가 통과한다(문서 PR이지만 하네스 회귀 확인).
- 문서 링크 검사: 변경한 wiki 파일의 상대 링크와 `.claude/skills/...` 링크를 스크립트로 열어 존재를
  확인한다. `make bootstrap` 이후 상태에서 실행한다.
- 이름 대조: 볼트 `ResilientPOS/skills/*/SKILL.md`의 `name:`과 `agents/*.md`의 `name:`·`model:`을 뽑아
  wiki 표와 diff 한다. 차이가 없어야 정상이다.
- `wc -l AGENTS.md`가 200 미만인지 확인한다.
- `git grep -n "$HOME"` 결과가 비어 있는지 확인한다.

## Wiki 영향
`docs/wiki/borrowed-workflows.md`(전면 갱신), `docs/wiki/agent-instructions.md`(모델 배정·선행 규칙),
`docs/wiki/workflow-review.md`(판정 기준 정리), `docs/wiki/README.md`(진입점), `docs/wiki/changes.md`(기록).
제품 요구사항은 바뀌지 않는다. 개발 지침의 구성 요소 목록과 사용 시점 안내가 바뀐다.

## 분할 계획
예상 변경은 7개 파일, 추가와 삭제 합계 250줄 안쪽이다. `borrowed-workflows.md`가 가장 커서 100줄 안팎이다.
경고선에 이르면 `workflow-review.md` 정리를 후속으로 뺀다. 병합 순서는 `rules-link-and-ignores` 다음을
권장한다. `AGENTS.md`는 세 PR이 서로 다른 절을 고치므로 충돌하지 않도록 이 PR은 작업별 읽기 절만 건드린다.

## TDD 검증 기록
문서 전용 작업이므로 적용하지 않는다. 검증은 위 링크·이름 대조 스크립트와 `check_pr.py`로 대신한다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 해당 없음(문서 전용) | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 spec을 독립 커밋으로 작성했다. 구현 후 아래를 확인했다.

- `python3 scripts/check_pr.py --base main --worktree`: PASS, 일반 7개/219줄. 제출 전 `--base main`(브랜치 이력)도 마지막 커밋 뒤 확인했다.
- `python3 -m unittest discover -s scripts -p 'test_*.py'`: 통과(문서 PR, 회귀 없음).
- 이름 대조: 볼트 `skills/*`(21개)와 `agents/*`(19개)의 이름·`model:`을 스크립트로 뽑아 `borrowed-workflows.md` 스킬 표와
  `agent-instructions.md` 모델 표와 대조했다. 누락·불일치 0.
- 링크 검사: `AGENTS.md`, `docs/wiki/README.md`, `agent-instructions.md`, `workflow-review.md`의 상대 링크는 모두 해소된다.
  `borrowed-workflows.md`가 가리키는 형제 spec 두 개(`rules-link-and-ignores`, `development-rules-original-first`)는 각각
  다른 브랜치에 있어 이 브랜치 단독으로는 깨진 링크로 보이며, 권장 병합 순서(1 → 2 → 3)대로 합치면 해소된다.
  이 PR을 먼저 병합하면 3번 spec 링크만 그 PR 병합 전까지 비어 있다.
- `wc -l AGENTS.md` = 24(200 미만). `git grep "$HOME"` 결과 없음.
- 커밋은 wiki 본문·인덱스·변경 기록 / 공통 지침 진입점 / spec 결과 기록 세 묶음으로 나눴다.
