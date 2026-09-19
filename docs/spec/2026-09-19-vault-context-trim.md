# PR spec: 볼트 지침 중복 제거와 세션 컨텍스트 축소

## 목적
Obsidian 볼트 `ResilientPOS/`의 스킬·에이전트·rules가 매 세션 컨텍스트에 싣는 분량을 줄이고 중복을 없앤다.
볼트 파일은 저장소 밖(gitignore된 링크 대상)이라 이 PR에서는 그 사실을 반영하는 wiki만 고친다.
근거는 Claude Code 공식 문서다. `paths:` 없는 rule과 스킬·에이전트 description은 항상 로드되고,
`disable-model-invocation: true` 스킬은 description이 컨텍스트에서 빠지며, `user-invocable: false`는 `/` 메뉴만 숨긴다.

## 범위
볼트에서 이미 수행한 변경(2026-09-19, 백업 `~/Documents/ResilientPOS-vault-backup-20260919`):
- `rules/engineering-principles.md`(항상 로드)에서 SOLID·파일 구성·TDD를 `rules/implementation-principles.md`(`paths: src/**/*.kt`)로 옮기고,
  AGENTS.md·git 스킬과 겹치던 검증 명령·커밋 규칙·CI/CD 절을 요약으로 줄였다. 10.0KB → 4.8KB.
- `resilientpos-setup`·`resilientpos-develop-auto`의 `user-invocable: true`(기본값이라 효과 없음)를 `disable-model-invocation: true`로 바꾸고,
  같은 오해를 적어 둔 `agent-design-principles` 스킬·rule, `workflow-reviewer`를 고쳤다.
- `orchestrator`·`communicate`·`git` 스킬 description을 1052/1009/600자에서 571/345/399자로 줄였다.
- `resilientpos-develop-auto` 본문을 663줄에서 64줄로 줄였다. `resilientpos-develop`의 references를 정본으로 두고 비대화형 차이만 남겼다.
- 본문이 같던 `outline-claude/codex/gemini`를 삭제하고 `resilientpos-outline`이 `architect-*`를 호출하게 했다.
- 윤문 스킬은 저장소 산출물을 대상으로 하는 `resilientpos-humanizer`만 남기고 전역 `im-not-ai` 링크(스킬 3·에이전트 4)를 해제했다.
- 이식 원본 `Global-claude-code/`를 `ResilientPOS/archive/`로 옮겨 이 프로젝트 볼트 안에만 보관한다.

저장소 변경: `docs/wiki/agent-instructions.md`, `docs/wiki/borrowed-workflows.md`의 에이전트 목록·rules 설명 갱신, `docs/wiki/changes.md` 기록.

## 제외
애플리케이션 코드, `scripts/`, `AGENTS.md`, Codex 에이전트(`codex/agents/*.toml`, 유지), 플러그인·MCP 설정 정리.

## 관련 문서
없음

## 변경 경로
- docs/spec/2026-09-19-vault-context-trim.md
- docs/wiki/agent-instructions.md
- docs/wiki/borrowed-workflows.md
- docs/wiki/changes.md

## 인수 조건
- wiki에 `outline-*` 에이전트가 남아 있지 않고, 에이전트 수가 16으로 적혀 있다.
- wiki가 `engineering-principles`(항상 로드)와 `implementation-principles`(`src/**/*.kt`)의 분리를 서술한다.
- `python3 scripts/check_pr.py --base main --worktree`가 통과한다.

## 검증
- `grep -rn "outline-\(claude\|codex\|gemini\)" docs/wiki`가 결과 없음.
- `python3 scripts/check_pr.py --base main --worktree`.

## Wiki 영향
`agent-instructions.md`(모델 배정 표, 명시 호출 스킬 설명), `borrowed-workflows.md`(도입 에이전트·규칙 목록, codex 래퍼 서술), `changes.md`.
제품 요구사항은 바뀌지 않는다.

## 분할 계획
문서 4개, 50줄 이하. 분할하지 않는다.

## TDD 검증 기록
문서 전용 작업이라 적용하지 않는다.

## 결과
개발 전 작성.
