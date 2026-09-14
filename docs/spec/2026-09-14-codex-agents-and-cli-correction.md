# PR spec: Codex 에이전트 도입과 외부 CLI 가용성 사실 정정

## 목적
두 가지를 함께 해소한다. 첫째, 볼트 `codex/agents/`가 비어 있어 Codex에서 쓸 수 있는 프로젝트 에이전트가 없었다.
2026-09-07 spec 두 개가 `tdd-driver.toml`·`workflow-reviewer.toml`을 만들었다고 기록했으나 2026-09-08 볼트 이전
과정에서 유실되었다. 둘째, 2026-09-13 볼트 이식 당시 `codex` CLI를 "없음"으로 판단해 wiki에 그렇게 적었는데,
2026-09-14 재확인 결과 `codex` 0.154.0이 설치되어 있고 실제 서브에이전트 스폰까지 동작한다. `gemini`만 없다.
볼트에는 Codex 에이전트 6개를 만들고 관련 서술을 이미 정정했으므로, 저장소 wiki를 그 사실과 일치시킨다.

## 범위
- `docs/wiki/borrowed-workflows.md`의 "이 머신에 없는 도구와 처리" 표에서 `codex` 행을 분리한다. `codex`는 설치되어
  있고(0.154.0) 실제 스폰을 검증했다는 사실, `gemini`만 미설치라는 사실, 래퍼 에이전트의 `command -v` 확인 로직은
  머신 이식성을 위해 그대로 둔다는 점을 적는다. 합의 규칙이 현재 2개 LLM(Claude + Codex) 기준으로 읽힌다는 것도 적는다.
- 같은 문서의 "도입한 에이전트와 규칙" 절에 Codex 에이전트 6개(`tdd_driver`, `test_oracle`, `workflow_reviewer`,
  `code_reviewer`, `spec_validator`, `complexity_reducer`)와 그 위치(볼트 `codex/agents/`, 저장소 `.codex/agents` 링크)를 더한다.
- `docs/wiki/agent-instructions.md`의 "스킬·에이전트 정본의 위치" 절에 Codex 에이전트가 실재하며 프로젝트 스코프
  `.codex/agents`를 심볼릭 링크를 통해 로드한다는 검증 사실을 한 문단 추가한다. Claude의 `model:`과 Codex의
  `model_reasoning_effort` 대응(high/medium)도 적는다.
- `docs/wiki/changes.md`에 이번 spec 링크와 요구사항 영향을 남긴다.

## 제외
볼트 파일 변경(이미 반영 완료), Codex 에이전트 정의 내용 변경, `gemini` CLI 설치, `.codex/skills` 링크 추가,
`scripts/link_claude.py`·`check_pr.py` 변경, 래퍼 에이전트의 `command -v` 로직 제거, 애플리케이션 코드 변경.

## 변경 경로
- docs/wiki/borrowed-workflows.md
- docs/wiki/agent-instructions.md
- docs/wiki/changes.md
- docs/spec/2026-09-14-codex-agents-and-cli-correction.md

## 인수 조건
- `docs/wiki/`에 "`codex` CLI가 없다"는 취지의 서술이 남지 않는다. `gemini` 미설치 서술은 사실이므로 유지된다.
- `borrowed-workflows.md`의 Codex 에이전트 6개 이름이 볼트 `codex/agents/*.toml`의 `name` 필드와 정확히 일치한다.
- `agent-instructions.md`가 Codex 로드 검증 사실과 effort 대응(high/medium)을 담는다.
- 래퍼 에이전트의 가용성 확인 로직을 제거하라는 서술이 wiki에 들어가지 않는다(다른 머신에서는 없을 수 있다).
- `AGENTS.md`는 변경하지 않는다.

## 검증
- 이름 대조: 볼트 `codex/agents/*.toml`의 `name` 값을 뽑아 `borrowed-workflows.md`의 목록과 대조한다. 불일치 0이어야 한다.
- CLI 사실 확인: `command -v codex && codex --version`이 0.154.0을 출력하고, `command -v gemini`가 실패하는 것을 확인한다.
- 잔존 검사: `grep -rn 'codex.*없' docs/wiki`가 gemini 관련 문장 외에는 비어야 한다.
- `python3 scripts/check_pr.py --base main --worktree`(개발 중)와 `--base main`(제출 전)이 통과한다.
- `python3 -m unittest discover -s scripts -p 'test_*.py'`가 통과한다.
- 변경한 wiki의 상대 링크가 모두 해소되는지 확인한다.

## Wiki 영향
`docs/wiki/borrowed-workflows.md`(도구 가용성 표·에이전트 목록), `docs/wiki/agent-instructions.md`(Codex 로드 검증과
effort 대응), `docs/wiki/changes.md`(기록). 제품 요구사항은 바뀌지 않는다. 사용 가능한 개발 도구의 사실과 Codex
에이전트 구성이 바뀐다.

## 분할 계획
예상 변경은 4개 파일, 추가와 삭제 합계 70줄 안쪽이다. 경고선에 이를 여지가 없다.

## TDD 검증 기록
문서 전용 작업이므로 적용하지 않는다. 검증은 이름 대조와 CLI 실측, `check_pr.py`로 대신한다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 해당 없음(문서 전용) | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 spec을 독립 커밋으로 작성했다. 구현 후 아래를 확인했다.

- 이름 대조: 볼트 `codex/agents/*.toml` 6개의 `name`이 모두 `borrowed-workflows.md`에 등재되어 있다(불일치 0).
- CLI 실측: `codex --version` → `codex-cli 0.154.0`, `command -v gemini` 실패. 표의 서술과 일치한다.
- 잔존 검사: `docs/wiki/`에 남은 "codex ... 없다" 문장은 정정 사실을 설명하는 한 줄뿐이다.
- 변경한 wiki 3개 파일의 상대 링크가 모두 해소된다.
- `python3 scripts/check_pr.py --base main --worktree`: PASS, 일반 4개/97줄. 제출 전 `--base main`도 마지막 커밋 뒤 확인했다.
- `python3 -m unittest discover -s scripts -p 'test_*.py'`: OK.

같은 날 볼트에 반영한 변경(참고): `codex/agents/*.toml` 6개 신규, `README.md`에 Codex 에이전트 절과 연결 표 갱신,
`MIGRATION.md`·`skills/resilientpos-{design,outline,multi-review,feature-workflow,develop-auto}`·
`skills/resilientpos-review/references/prompt-template.md`의 CLI 부재 서술 정정, `agents/{architect,outline,reviewer}-codex.md`의
현재 상태 각주와 `codex exec --sandbox read-only --skip-git-repo-check` 플래그 보강.

검증 과정에서 첫 스폰 시도가 `unknown agent_type`으로 실패했으나, 임시 프로젝트로 심볼릭 링크 구조를 격리 검증해
링크가 원인이 아님을 확인했고 재시도에서 정상 동작했다. 일시적 문제로 판단한다.
