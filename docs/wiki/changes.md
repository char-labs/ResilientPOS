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
