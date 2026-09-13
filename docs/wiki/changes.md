# Wiki 변경 기록

각 PR에 항목을 추가한다. 이전 기록은 유지한다.

## 2026-09-07 — 개발 하네스
- Spec: [development-harness](../spec/2026-09-07-development-harness.md)
- 요구사항 영향: 개발 절차에 spec 선작성, PR 크기 제한, wiki 동기화를 추가했다. 제품 요구사항은 변경하지 않았다.
- 갱신: [개발 규칙](development-workflow.md), [현재 상태](current-state.md), [진입점](README.md).
- 첨부 자료는 실행 지침으로 취급하지 않고 원문 보관했다.
- 요구사항 영향: 추가 요청에 따라 GitHub Actions workflow를 제거하고 공통 LLM 지침과 로컬 검사로 운영하도록 개발 규칙과 현재 상태를 갱신했다.
- 요구사항 영향: 공식 Claude/Codex 문서를 근거로 공통 지침을 정리하고 작업별 wiki 읽기로 변경했다. 현재 기술 스택과 미도입 기술을 구분했으며, [지침 설계 근거](agent-instructions.md)에 출처와 적용 판단을 기록했다.
- 요구사항 영향: README를 SDD 적용 방식·현재 기술 스택·설계 및 검증 방법론 중심으로 정리했다. 상세 실행 절차는 wiki에 유지하며, 제품 요구사항과 의존성은 변경하지 않았다.

## 2026-09-07 — TDD 스킬과 에이전트
- Spec: [tdd-agent](../spec/2026-09-07-tdd-agent.md)
- 요구사항 영향: 비즈니스·도메인·Controller 개발에 단위/통합 TDD를 적용하고 Repository 자체 테스트는 제외한다. REST Docs와 Fixture Monkey를 채택하며 의존성 설치는 최초 기능 PR에서 수행한다.
- 갱신: [테스트 전략](testing-strategy.md), [개발 규칙](development-workflow.md), [현재 상태](current-state.md), spec 템플릿과 README.

## 2026-09-07 — fleta 워크플로우 차용
- Spec: [fleta-workflow-adoption](../spec/2026-09-07-fleta-workflow-adoption.md)
- 요구사항 영향: workflow 점검, commit/push 범위 분리, 읽기 전용 지침 리뷰를 추가했다. 기존 SDD/TDD와 작은 PR 원칙은 유지한다.
- 갱신: [차용 내역](borrowed-workflows.md), [리뷰 기준](workflow-review.md), [개발 규칙](development-workflow.md), 공통 지침과 도구별 진입점.
- 요구사항 영향: 다른 PC에서의 개발을 위해 차용 문서의 개인 절대 경로를 제거했다. 출처 식별자는 패키지 내부 경로와 해시로 유지하며 실행 참조는 저장소 내부로 한정한다.
- 요구사항 영향: commit-push가 변경 의도별로 spec·wiki·개발 도구를 분리하고, 도메인 개발은 요구사항별 구현과 테스트를 묶도록 보완했다.

## 2026-09-08 — 스킬 정본을 볼트로 이전
- Spec: [vault-canonical-skills](../spec/2026-09-08-vault-canonical-skills.md)
- 요구사항 영향: 제품 요구사항은 변경하지 않았다. 스킬·에이전트 정본의 보관 위치를 Obsidian 볼트로 옮기고 저장소는 심볼릭 링크로 연결하도록 개발 환경 구성만 바꿨다. 새 머신에서는 clone 후 `make bootstrap`을 실행해야 스킬이 붙는다.
- 요구사항 영향: `.agents`, `.claude`, `.codex` 세 경로를 통째로 추적에서 제외했다. 지침 변경이 제품 PR diff에 나타나지 않으면서, 링크가 저장소 안에 있어 스킬은 이 저장소에서만 적용된다. Codex 에이전트 toml도 볼트로 옮기고 `.codex/agents`를 링크로 연결한다.
- 요구사항 영향: 개발 명령 진입점을 `Makefile`로 모으고 README에 명령 표와 자동완성 안내를 추가했다. 기존 명령의 동작은 바뀌지 않았다.
- 갱신: [지침 설계 근거](agent-instructions.md), [현재 상태](current-state.md), [개발 규칙](development-workflow.md), 공통 지침, README, Makefile.

## 2026-09-13 — 볼트 rules 링크와 워크플로우 산출물 추적 제외
- Spec: [rules-link-and-ignores](../spec/2026-09-13-rules-link-and-ignores.md)
- 요구사항 영향: 제품 요구사항은 변경하지 않았다. `make bootstrap`이 `.claude/rules`도 볼트 `rules/`로 링크해 항상 적용되는 규칙 파일이 자동 로드되고, 이식된 워크플로우 스킬의 휘발성 산출물 경로(`.develop-workflow/`, `.review/`, `.review-output/`, `.orchestrator/`, `.plans/`)를 추적에서 제외했다.
- 갱신: [지침 설계 근거](agent-instructions.md), 공통 지침, `.gitignore`, `scripts/link_claude.py`와 테스트.
## 2026-09-13 — 볼트 전체 이식에 맞춘 wiki·공통 지침 진입점 갱신
- Spec: [wiki-vault-migration-sync](../spec/2026-09-13-wiki-vault-migration-sync.md)
- 요구사항 영향: 제품 요구사항은 변경하지 않았다. `Global-claude-code` 공통 노하우 전체(스킬 21개·에이전트 19개·rules 2개)가 볼트에 들어온 사실과 원본 방식 우선 결정, 역할별 모델 배정(opus/sonnet/haiku), 스킬 선행 규칙(합의 전이면 grill 먼저)을 wiki와 공통 지침에 반영했다. 2026-09-07의 보류 항목은 모두 도입되었고 개발 규칙 조정은 후속 spec으로 연결했다.
- 갱신: [차용 내역](borrowed-workflows.md) 전면, [지침 설계 근거](agent-instructions.md), [리뷰 기준](workflow-review.md), [wiki 인덱스](README.md), 공통 지침.
## 2026-09-13 — 원본 워크플로우 우선 개발 규칙 조정
- Spec: [development-rules-original-first](../spec/2026-09-13-development-rules-original-first.md)
- 요구사항 영향: 제품 요구사항은 변경하지 않았다. 개발 절차 규칙을 이식된 원본 워크플로우에 맞췄다: grill 선행, 장기 스펙 계층(`docs/spec/DESIGN.md`·`concerns/`·`flows/`) 위치, epic 브랜치와 "요청 1건 = PR 1개"(크기 한도는 유지, 초과 시 stacked PR), rebase 허용과 force-push 금지, 커밋 형식(`type(scope): 한국어 description`), PR 본문 4단, Contract/Checkpoint는 spec 안에만 기록, `.develop-workflow.yaml` 미도입.
- 갱신: [개발 규칙](development-workflow.md), [spec 템플릿](../spec-template.md)(관련 문서·Checkpoint 선택 항목), 공통 지침, `scripts/test_check_pr.py`(비날짜형 spec 파일 회귀 테스트).
