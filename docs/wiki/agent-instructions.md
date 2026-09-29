# LLM 지침 설계 근거

2026-09-07 공식 문서 조사. 지침을 수정할 때 읽는 참고 페이지이며 시작 시 일괄 로딩하지 않는다.

## 공식 문서에서 확인한 사항
- Codex는 사용자 전역과 프로젝트 경로의 지침을 조합한다. 같은 디렉터리에서는 `AGENTS.override.md`를 먼저 선택하고, 더 가까운 디렉터리의 지침이 앞선 지침보다 우선한다. 기본 합산 한도는 32 KiB다. [OpenAI: AGENTS.md](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
- Claude는 간결하고 구체적인 지침을 권장하며 CLAUDE.md를 200줄 미만으로 유지하는 것을 목표로 제시한다. `@path` import는 시작 시 본문을 로딩하므로 분리만으로 컨텍스트가 줄지 않는다. [Anthropic: Memory](https://code.claude.com/docs/en/memory)
- 매번 필요한 명령·프로젝트 규칙만 공통 지침에 남기고, 상세 API 설명·언어 기본 상식·긴 설명은 빼거나 링크로 연결한다. [Anthropic: Best practices](https://code.claude.com/docs/en/best-practices)

## 이 저장소에 적용한 판단
- 사용자 홈의 전역 설정과 저장소 루트 지침을 구분한다. 이번 변경은 저장소 내부에만 적용한다.
- 공통 정본은 루트 `AGENTS.md` 하나다. 루트 `CLAUDE.md`는 `@AGENTS.md` import 한 줄만 두고 다른 내용을 적지 않는다. Claude Code가 `CLAUDE.md` 없이 `AGENTS.md`를 대신 읽는 동작은 버전과 `/config`의 Project instructions 설정에 따라 달라진다. 2.1.284에서 `claude -p`로는 실렸지만 같은 버전의 다른 세션에서는 실리지 않아 worktree 규칙을 놓친 사고가 있었고, 원인은 재현하지 못했다. 그래서 문서화된 `CLAUDE.md` import로 로딩을 보장한다([claude-md-import-agents](../spec/2026-09-29-claude-md-import-agents.md)). Gemini는 루트 `GEMINI.md`가 같은 파일을 가리킨다.
- 루트에는 사용자가 요청한 현재 스택, spec/wiki 계약, 작업별 읽기 조건과 검증 명령만 둔다. 상세 개발 절차와 조사 내용은 wiki에 둔다.
- 의존성 버전은 빌드 파일을 기준으로 관리한다. 계획만 있는 기술을 이미 사용 중인 기술로 적지 않는다.
- 전체 wiki를 import하거나 항상 읽게 하지 않는다. Markdown 링크는 자동 import가 아니므로 읽어야 하는 시점을 함께 적는다.
- 아직 작은 단일 프로젝트이므로 디렉터리별 지침 파일을 미리 늘리지 않는다. 실제 영역별 규칙이 생기면 하위 디렉터리의 `AGENTS.md`로 범위를 좁힌다. Codex와 Claude Code가 모두 그 위치의 지침으로 읽는다.
- 두 도구의 로딩/우선순위를 동일하다고 가정하지 않는다. 특히 Codex는 실행 디렉터리 기준 탐색을 확인하고, Claude 지침은 서로 충돌하지 않게 작성한다.
- spec 선작성·컨텍스트 단위 PR·wiki 갱신은 사용자 요청에 따른 프로젝트 규칙이다. 공식 문서에서 강제하는 수치나 절차가 아니다.

## 스킬·에이전트 정본의 위치
정본은 Obsidian 볼트의 `ResilientPOS/` 아래 `skills`, `agents`, `rules`, `codex/agents`에 두고 저장소의
`.claude/skills`, `.claude/agents`, `.codex/agents`를 심볼릭 링크로 연결한다.
볼트 `rules/*.md`는 Claude가 항상 적용하는 규칙(설계 원칙 바인딩, 엔지니어링 원칙)이다. 2026-09-13에 `.claude/rules`
링크로 추가했지만, 2026-09-26에 `claude -p`로 확인해 보니 Claude Code는 디렉터리 링크도, 실제 디렉터리 안의 파일 링크도
규칙으로 싣지 않고 실제 파일만 싣는다. 그래서 `.claude/rules`는 볼트 `rules/`를 미러링한 복사본이다
([rules-copy-bootstrap](../spec/2026-09-26-rules-copy-bootstrap.md)). 복사본을 고치면 다음 bootstrap이 덮어쓰므로
규칙은 볼트에서 고치고 `make bootstrap`을 다시 실행해야 반영된다. 지침 변경이 제품 PR
diff를 오염시키지 않고, 볼트 동기화로 기기 간에 공유되며, Obsidian에서 읽고 편집할 수 있다.
링크가 저장소 안에 있으므로 스킬은 이 저장소에서 작업할 때만 적용된다.
링크와 복사본은 `make bootstrap`이 부르는 `scripts/link_claude.py`가 실행 시점에 홈 경로를 풀어 만든다.
커밋되는 파일에 기기별 절대 경로가 들어가지 않고, 볼트가 없으면 안내만 남기고 정상 종료한다.
네 경로는 `.gitignore` 대상(`.claude/` 통째)이며, 로컬 검사는 추적하지 않는 경로를 제외하므로 심볼릭 링크 오류가
발생하지 않는다. 링크를 gitignore에서 빼면 검사가 즉시 실패한다.

## Codex 에이전트
볼트 `codex/agents/*.toml` 6개(`tdd_driver`, `test_oracle`, `workflow_reviewer`, `code_reviewer`, `spec_validator`,
`complexity_reducer`)를 두고 저장소 `.codex/agents` 링크로 연결한다. Codex는 프로젝트 스코프 `.codex/agents`를
심볼릭 링크를 통해 그대로 읽는다 — 2026-09-14에 `codex exec`로 `workflow_reviewer`를 실제 스폰해 AGENTS.md를
읽히고 결과를 받아 확인했다.

Claude 정의(`agents/*.md`)와 역할은 같고 형식만 다르다. Codex TOML의 필수 필드는 `name`, `description`,
`developer_instructions`이고 선택 필드로 `model`, `model_reasoning_effort`, `sandbox_mode`를 쓴다. Claude의 모델
배정은 Codex에서 추론 노력으로 옮긴다 — `opus` 역할은 `high`, `sonnet` 역할은 `medium`이며 모델은 모두
`gpt-5.3-codex`다. 읽기 전용 검토자는 `sandbox_mode = "read-only"`, 구현·테스트 실행이 필요한 `tdd_driver`와
`test_oracle`만 `workspace-write`다.

스킬은 `[[skills.config]]`로 물리지 않는다. `developer_instructions` 안에서 저장소 경로(`.claude/skills/...`,
`docs/wiki/testing-strategy.md`)를 읽게 해 스킬 정본이 하나로 유지된다.

## 에이전트 모델 배정과 스킬 선행 규칙
2026-09-13 전체 이식 시 사용자가 정한 기준이다. 원본 플러그인은 대부분 `model: opus` 고정이었으나 역할별 가성비로 바꿨다.
정본은 볼트 `agents/*.md`의 `model:`이고, `resilientpos-orchestrator`가 dispatch 할 때는 "집행 위임 tier ≤ 메인 tier" 상한이 우선한다.

| 등급 | 기준 | 에이전트 |
|---|---|---|
| `opus` | 판단 오류의 대가가 큰 검토·설계·게이트 | `architect-claude`, `reviewer-claude`, `code-reviewer`, `spec-validator`, `workflow-reviewer`, `complexity-reducer`, `conflict-analyzer`, `spring-backend-specialist` |
| `sonnet` | 구현 실행자, 절차가 정해진 분석·생성·진단 | `tdd-driver`, `test-oracle`, `codebase-analyzer`, `document-analyzer`, `rules-generator` |
| `haiku` | 외부 CLI 결과를 포장만 하는 래퍼 | `architect-codex/gemini`, `reviewer-codex/gemini` |

배정의 근거는 [Anthropic 어드바이저 도구 문서](https://platform.claude.com/docs/ko/agents-and-tools/tool-use/advisor-tool)의 측정이다. 값싼 실행자 모델에 상위 어드바이저 모델을 붙이면 상위 모델 단독과 비슷한 품질을 같거나 낮은 비용으로 내고, 실행자 역량이 어드바이저에 가까울수록 이점이 줄어든다. 이 저장소의 오케스트레이션은 구현 앞뒤에 `opus` 게이트(설계 검증 `spec-validator`·`architect-*`, 사후 검토 `code-reviewer`·QA)를 두므로 그 게이트가 어드바이저 역할이고, 구현 실행자 `tdd-driver`는 `sonnet`이다(2026-09-13, 처음 `opus`로 정했던 것을 문서 근거로 조정). 게이트 없이 `tdd-driver`를 단독 호출하는 되돌리기 비용이 큰 변경이면 호출자가 dispatch 시 `model`을 `opus`로 올린다. 볼트 `resilientpos-orchestrator`의 자문 경로도 같은 문서를 원형으로 삼아 단발 자문 허용, 권고 80단어 상한, 메인 tier 조건부 시작/종료 자문으로 맞췄다.

스킬 description이 같은 요청에 경합할 때의 선행 규칙:
- 요구사항·설계가 아직 합의되지 않았으면 `resilientpos-grill`이 먼저다. `resilientpos-develop`·`resilientpos-orchestrator`는 합의 뒤에 진입한다.
- 커밋/push 요청은 `resilientpos-git`이 절차를, `resilientpos-commit-push`가 범위 판단(commit-only vs push, staged 범위, spec 첫 커밋 보존)을 맡아 함께 로드된다.
- 지침·스킬 파일 검토는 `workflow-reviewer`, 구현 코드 검토는 `code-reviewer`·`resilientpos-review`다. 백엔드 운영 리스크(정합성, 멱등성, 응답 속도, 보안, AOP 리팩토링 여지 등)는 `spring-backend-specialist`가 더 깊게 점검하지만 사용자가 명시적으로 요청할 때만 쓰고, 그런 요청이 없으면 `code-reviewer`가 "잠재적 문제" 항목에서 계속 본다.
- `resilientpos-setup`과 `resilientpos-develop-auto`는 사용자가 명시 호출할 때만 실행한다. 두 스킬은 `disable-model-invocation: true`라 description이 세션 컨텍스트에 올라가지 않는다(`user-invocable: false`는 `/` 메뉴만 숨기고 description은 계속 로드된다).

## 유지와 확인
지침 변경 시 중복·충돌·폐기된 명령을 제거하고 관련 wiki를 갱신한다. 사용자 전역 지침을 프로젝트 규칙으로 복제하지 않는다.
새 Codex 세션에서 활성 지침을 요약하게 하고, Claude에서는 `/context`로 Memory files의 로딩을 확인할 수 있다. [OpenAI 검증 안내](https://learn.chatgpt.com/docs/agent-configuration/agents-md#verify-your-setup), [Claude 로딩 확인](https://code.claude.com/docs/en/memory#set-up-a-project-claudemd)
이번 검증은 파일/import 경로·링크·빌드 선언 일치와 로컬 하네스 검사다. 별도 Claude/Codex 세션의 실제 로딩 검증은 수행하지 않았다.
