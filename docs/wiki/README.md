# ResilientPOS 공통 LLM wiki

일반 Markdown과 상대 링크로 구성한 저장소 내 지식 진입점이다. 특정 플러그인이나 메모리 기능이 필요하지 않다.
자동 지침 탐색을 지원하지 않는 LLM에는 `AGENTS.md를 읽고 작업에 필요한 wiki를 찾아줘`라고 요청한다.

## 작업별 진입점
- 공통 계약/기술 스택: [AGENTS.md](../../AGENTS.md).
- 개발 시작/PR 제출: [개발 규칙](development-workflow.md)과 해당 PR의 `docs/spec/yyyy-mm-dd-slug.md`.
- 애플리케이션/빌드 수정: [현재 상태](current-state.md). 필요한 도메인 원문만 [원문 목록](../references/README.md)에서 찾는다.
- 이전 결정 추적: [변경 기록](changes.md)에서 관련 spec을 찾아 읽는다.
- LLM 지침 수정: [공식 문서 조사와 적용 근거](agent-instructions.md).

## 문서 역할
- `docs/wiki/`: 현재 합의된 지식. 변경 시 해당 페이지를 고치고 변경 기록에서 spec과 연결한다.
- `docs/spec/yyyy-mm-dd-slug.md`: PR별 의도·범위·검증·변경 이력. [spec 템플릿](../spec-template.md)으로 시작한다.
- `docs/references/`: 제공받은 원문. 요구사항 체계화는 후속 작업이다.

전체 wiki·spec 이력·원문을 매 작업마다 일괄 읽지 않는다. 충돌이나 미확정 사항은 해당 spec에 근거와 함께 기록한다.
