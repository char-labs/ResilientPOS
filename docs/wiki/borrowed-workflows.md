# 차용한 워크플로우와 사용법

사용자가 제공한 `Global-claude-code` 자료(공통 Claude Code 플러그인 7개: 공통 워크플로우, develop-workflow, git-utils, humanizer,
statusline, coding-style, workflow-guide)를 두 차례에 걸쳐 이 프로젝트에 들여왔다. 원문은 실행 지시가 아닌 참고 자료로
취급하며 원본 폴더는 수정하지 않았다. 정본은 Obsidian 볼트 `ResilientPOS/`에 있고 저장소는 `make bootstrap`이 만드는
심볼릭 링크로 읽는다([지침 설계 근거](agent-instructions.md)).

## 2026-09-13 — 전체 이식

### 결정
- **원본 방식 우선**: 원본 절차(epic 브랜치, DESIGN→concerns→flows 스펙 계층, 요청 1건 = PR 1개, 자율 주행 오케스트레이션,
  Multi-LLM 설계·리뷰)와 저장소 계약이 어긋나면 원본을 유지하고, 바뀌어야 할 계약은 후속 PR
  [development-rules-original-first](../spec/2026-09-13-development-rules-original-first.md)에서 조정한다.
- **모델은 역할별 가성비**: 검토·설계·게이트는 `opus`, 구현 실행자(`tdd-driver`)와 절차가 정해진 분석·생성·진단은 `sonnet`, 외부 CLI 결과를
  포장만 하는 래퍼는 `haiku`. 배정표는 [지침 설계 근거](agent-instructions.md#에이전트-모델-배정과-스킬-선행-규칙).
- **누락 없이, 중복 없이**: 원본 111개 파일 전부가 볼트 어딘가에 대응한다. `workflow-guide`는 공통 워크플로우 플러그인에 흡수된 구버전이라
  파일을 복제하지 않고 유일 내용만 병합했다. 대응표는 볼트 `ResilientPOS/MIGRATION.md`에 있다.

### 도입한 스킬 (21)
Claude에서 `/이름`, Codex에서 `$이름`으로 호출한다. 자연어 요청이면 description에 맞는 스킬이 자동 선택된다.

| 묶음 | 스킬 | 언제 쓰나 |
|---|---|---|
| 설계·스펙 | `resilientpos-grill` | 새 기능·변경 요청을 한 번에 한 질문으로 심문. 합의 전 구현 금지 |
| | `resilientpos-spec-write` | 합의된 설계를 DESIGN/concerns/flows 계층 문서로 형식화 |
| | `resilientpos-high-level-design` | 상위 설계 원칙·트레이드오프·출력 형식 |
| | `resilientpos-outline`, `resilientpos-design` | Claude/Codex/Gemini 병렬 상위 설계, Contract(Interface+Test+Checkpoint) 정의 |
| 개발 파이프라인 | `resilientpos-develop` | 설계 → 리뷰 → 구현(RALPH) → 머지 통합 파이프라인, HITL 2곳 |
| | `resilientpos-develop-auto` | 비대화형 완전 자동 버전(분석 리포트 입력, PR 생성까지) |
| | `resilientpos-implement` | Contract 기반 Direct/Subagent/Agent Teams 구현 |
| | `resilientpos-feature-workflow` | Phase별 원칙, 상태 감지, 에스컬레이션 기준 |
| | `resilientpos-tdd` (기존) | 비즈니스·도메인·Controller TDD 절차 |
| 리뷰 | `resilientpos-review`, `resilientpos-multi-review`, `resilientpos-review-to-issues` | 리뷰 기준(100점 표), 3개 LLM 병렬 리뷰, 결과를 GitHub Issue로 |
| 위임·조율 | `resilientpos-orchestrator` | 여러 작업을 epic 브랜치 + worktree 격리 sub-agent로 분해·위임·게이트·머지 |
| Git | `resilientpos-git` | 브랜치·커밋·push·PR·release PR·머지·충돌·리뷰 조회·이슈·epic (plain git/gh) |
| | `resilientpos-commit-push` (기존) | commit-only와 push 요청 구분, staged 범위·spec 첫 커밋 보존 |
| 지침·컨벤션 | `resilientpos-workflow` (확장) | 컨벤션 scaffold, `.claude/rules` 설계, 설계 원칙 룰 설치, 워크플로우 리뷰 |
| | `resilientpos-agent-design-principles` | Command/Agent/Skill 설계 원칙(내부 참조, 자동 호출 안 됨) |
| 글쓰기 | `resilientpos-communicate`, `resilientpos-humanizer` | 대화 밖 독자용 작문 기준, AI 글쓰기 패턴 교정 |
| 설정 | `resilientpos-setup` | coding-style/develop-workflow/git-hooks/statusline 모듈 설치·제거. 명시 요청 시만 |

### 도입한 에이전트 (16)와 규칙 (3)
- 설계·리뷰: `architect-claude/codex/gemini`(design·outline 공용, 2026-09-19에 본문이 같던 `outline-*` 3개를 합침), `reviewer-claude/codex/gemini`, `code-reviewer`,
  `spec-validator`, `complexity-reducer`, `conflict-analyzer`, `test-oracle`.
- 지침: `workflow-reviewer`(확장, 읽기 전용), `codebase-analyzer`, `document-analyzer`, `rules-generator`.
- 구현: `tdd-driver`(기존).
- Codex용(볼트 `codex/agents/*.toml`, 저장소 `.codex/agents` 링크, 2026-09-14 추가): `tdd_driver`, `test_oracle`(workspace-write), `workflow_reviewer`, `code_reviewer`, `spec_validator`, `complexity_reducer`(read-only). Claude 정의와 역할은 같고 형식만 Codex 규약(TOML)을 따른다. 래퍼 4개와 `*-claude` 2개는 옮기지 않았다 — 전자는 Codex가 Codex를 부르는 구조이고 후자는 Claude 전용이다.
- 규칙(`.claude/rules`, 링크로는 로드되지 않아 [rules-copy-bootstrap](../spec/2026-09-26-rules-copy-bootstrap.md) 이후 복사본으로 로드):
  `engineering-principles`(항상 로드: 설계 최우선, 결정적 도구 vs 지능 책임 경계, Quality Gate, 커밋 형식 요약),
  `implementation-principles`(`src/**/*.kt` 편집 시 로드: SOLID, 파일 구성, TDD),
  `agent-design-principles`(설계 원칙 스킬의 얇은 바인딩).

### 외부 도구 가용성과 처리
2026-09-13 이식 당시에는 `codex`도 없다고 판단했으나, 2026-09-14 재확인에서 설치되어 있었다. 아래는 그 정정을 반영한 현재 상태다.

| 도구 | 상태 | 처리 |
|---|---|---|
| `git-utils` CLI | 없음 | 모든 CLI 호출을 동등한 plain `git`/`gh` 절차로 풀어 썼다 |
| `codex` CLI | **있음** (0.154.0) | 2026-09-14에 `codex exec`로 프로젝트 서브에이전트 스폰까지 검증했다. `architect-codex`·`reviewer-codex`가 실제로 실행되므로 Multi-LLM 경로는 Claude + Codex 2개로 돈다 |
| `gemini` CLI | 없음 | `*-gemini` 래퍼 3개는 "미실행"을 반환한다. 합의 규칙은 실행된 LLM 수를 분모로 재해석하므로 현재는 2개 기준이다 |
| hook 스크립트(`detect-ralph-state.sh`, `develop-phase-gate.cjs`, Default Branch Guard, statusline 엔트리) | `resilientpos-setup`이 `.claude/settings.json`(로컬, gitignore) 편집 절차와 대체 셸을 제공한다. 미등록 상태에서는 에이전트가 같은 조건을 직접 확인한다(soft gate) |
| GitHub Actions | PR 게이트로 빌드와 Flyway 버전 검사만 사용한다. `check-ci`는 이 워크플로 결과를 조회하고, spec 이력은 로컬 `check_pr.py` 결과를 함께 제시한다 |

### 계약 조정 상태
| 원본 방식 (들여온 것) | 저장소 계약 | 상태 |
|---|---|---|
| `.claude/rules` 자동 로드, 휘발성 산출물(`.develop-workflow/` 등) | 링크 3개, gitignore에 없음 | [rules-link-and-ignores](../spec/2026-09-13-rules-link-and-ignores.md)에서 해소 |
| 스펙 계층 위치, epic과 PR 단위, rebase 정책, 커밋 형식·언어, PR 본문 4단, Checkpoint 관계, grill 선행 | PR당 날짜형 spec 한 개, 개발 컨텍스트 단위 PR·스택 PR 금지, 자동 rebase 금지 | [development-rules-original-first](../spec/2026-09-13-development-rules-original-first.md)에서 조정, PR 단위는 [pr-unit-by-context](../spec/2026-09-28-pr-unit-by-context.md)에서 조정 |
| PR 본문 4단 고정(왜 / 무엇을 / 어떻게 / 확인 방법) | 네 가지 질문을 골격으로 둔 의사결정 문서 8절. 양식은 `.github/PULL_REQUEST_TEMPLATE.md`가 정한다 | [pr-template-decision-doc](../spec/2026-09-20-pr-template-decision-doc.md)에서 조정. 미해소: 볼트의 git 스킬 cli-reference와 communicate 스킬이 아직 4단 고정을 단일 출처로 선언한다. 저장소에서는 템플릿이 우선하고, 볼트 문구 수정은 후속 작업으로 남아 있다 |
| 모든 git 작업에 `resilientpos-git` 적용 | commit/push 요청에서만 스킬 선택 | 이번 PR: 진입점 안내를 추가했고, commit/push 범위 판단은 `resilientpos-commit-push`가 계속 담당한다 |
| 에이전트 `model:` 명시 | 모델은 기존 설정 상속 | 이번 PR: 역할별 배정으로 변경 |
| 도구 개수·모델 기반 안티패턴 표(workflow-reviewer) | 역할·영향·구체적 근거로 판정 | 이번 PR: [리뷰 기준](workflow-review.md)에서 "신호로 참고, 판정은 근거로" 정리 |

## 2026-09-07 — 선별 차용 (이력)

첫 차용에서는 공통 워크플로우·git-utils·develop-workflow의 아이디어를 프로젝트에 맞게 재작성해 세 가지만 들여왔다.

| 구성 | 목적 | 선택 범위 |
|---|---|---|
| [resilientpos-workflow](../../.claude/skills/resilientpos-workflow/SKILL.md) | 지침·컨벤션의 누락/중복/충돌 점검과 최소 수정 | 워크플로우/지침 개선 요청 |
| [resilientpos-commit-push](../../.claude/skills/resilientpos-commit-push/SKILL.md) | 범위를 확인한 커밋과 요청된 push | commit/push 요청 |
| workflow-reviewer | 지침·스킬을 읽기 전용으로 검토 | 워크플로우 변경 검토 |

당시 보류했던 항목(orchestrator/grill/spec-write 전체 도입, 문체 전용 agent, rules 일괄 생성기, CLI·전역 hook 설치,
요청 1건 = PR 1개, DESIGN/concern/flow, 자동 rebase/epic 수렴)은 2026-09-13 전체 이식으로 모두 들어왔다.
당시의 "수정하거나 보류한 항목" 표는 위 "계약 조정 상태" 표가 대체한다.

## 출처와 추적
- 2026-09-13 전체 이식의 원본 파일 111개 → 볼트 파일 대응표, 적응 규칙, 미이식·주의 사항은 볼트 `ResilientPOS/MIGRATION.md`가
  단일 출처다. 저장소에는 복제하지 않는다.
- 아래는 2026-09-07 선별 차용 시 조사한 원문의 SHA-256이다. 원본 패키지 내부의 출처 식별자이며 이 저장소에서 열어야 하는
  파일 링크가 아니다. 향후 재차용 시 해시와 diff를 비교한다.

| 출처 파일 | 차용한 관점 | SHA-256 |
|---|---|---|
| `plugins/skills/workflow/SKILL.md` | 작업별 라우팅과 지침 책임 분리 | `3aa332504ac91d3e6a804a42304d40d5d317577083c7f152f4da5210c0d31183` |
| `plugins/skills/workflow/references/rules-design.md` | 필요한 영역만 지침 로딩 | `84a714ed8fe185fb42897a587a0ea50a392f20df9889457c19fd4c2a1e75c0e2` |
| `plugins/agents/workflow/codebase-analyzer.md` | 실제 코드/의존성 근거 확인 | `4f0880eded5eaf5fb8cd18190bf12dc25ed8add84e24a7e9d50da0f0e6072fa9` |
| `plugins/agents/workflow/workflow-reviewer.md` | 읽기 전용 역할·권한·컨텍스트 검토 | `345a08ac48f26e162ffba4d617f8456248d0d5168e7e2f8708907006b3426d25` |
| `plugins/skills/git/SKILL.md` | 변경/브랜치 확인, commit/push 구분 | `8d1d62fabf5e3c3ec60da69fd7d1a587b99d22c8cb810b02cd2c231d516ea14c` |
| `plugins/skills/git/references/cli-reference.md` | plain git 사용, Conventional Commits | `586495fcdfb30ce917c55aa68fe3d634e837041402bbbcb81152f73d81f21c91` |
| `plugins/git-utils/commands/commit-and-push.md` | commit → 요청된 push → 결과 확인 | `9bb8e1d2a2810ae10c8033627f9cc601690898d56ccc458b399853ced66b4f88` |
| `plugins/develop-workflow/agents/spec-validator.md` | 명세 일관성과 검증 가능성 | `31fb589a4769eba1c16903b3cfc1839149bc62490c5e05d06aaec1f6792e9676` |
