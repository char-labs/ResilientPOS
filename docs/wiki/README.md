# ResilientPOS 공통 LLM wiki

일반 Markdown과 상대 링크로 구성한 저장소 내 지식 진입점이다. 특정 플러그인이나 메모리 기능이 필요하지 않다.
자동 지침 탐색을 지원하지 않는 LLM에는 `AGENTS.md를 읽고 작업에 필요한 wiki를 찾아줘`라고 요청한다.
`docs/wiki/current-state.md`, `docs/wiki/modulith-kafka-keywords.md`와 `.claude/skills/` 아래 스킬은 Obsidian 볼트에 있고 `make bootstrap`이 이 경로에 링크를 만든다. 저장소에 커밋하지 않아 GitHub에서는 열리지 않으므로 아래에서는 링크 대신 저장소 루트 기준 경로로 적는다.

## 작업별 진입점
- 공통 계약: [AGENTS.md](../../AGENTS.md). 기술 스택 현황: 현재 상태(`docs/wiki/current-state.md`, 볼트).
- 개발 시작/PR 제출: [개발 규칙](development-workflow.md)과 해당 PR의 `docs/spec/yyyy-mm-dd-slug.md`.
- 애플리케이션/빌드 수정: 현재 상태(`docs/wiki/current-state.md`, 볼트). 필요한 도메인 원문만 [원문 목록](../references/README.md)에서 찾는다.
- 이전 결정 추적: `docs/spec/`에서 날짜와 slug로 관련 spec을 찾아 읽는다.
- 비즈니스·도메인·Controller 개발: [TDD 테스트 전략](testing-strategy.md)과 공통 스킬(`.claude/skills/resilientpos-tdd/SKILL.md`).
- metric·로그·trace 노출, 알림 규칙과 LGTM 연결 전제: [관측성](observability.md).
- 알림이 울렸을 때 따라가는 절차: [runbook](runbook.md).
- 락 시간·Redis 타임아웃·커넥션 풀·sync 배치 상한·consumer 설정 같은 용량 값의 근거, 트래픽 가정, Redisson이 필요해지는 시점, 부하 측정 다시 하기: [용량 기준](capacity.md).
- Kafka·Spring Kafka·Modulith 이벤트 작업: Modulith·Kafka 키워드(`docs/wiki/modulith-kafka-keywords.md`, 볼트). 맨 앞 "갱신 규칙"대로 작업을 끝낼 때마다 갱신한다.
- 요청 처리 단계·대표 흐름 시퀀스·재시도와 DLT·이벤트 스토밍 결과·모듈 의존 그래프: [아키텍처](architecture.md). 모듈 의존을 바꾸면 이 페이지와 README의 그래프를 함께 고친다.
- 도메인 용어·bounded context·유비쿼터스 언어 용어집: [도메인 모델](domain-model.md). 업무 spec을 쓰거나 새 타입·테이블·이벤트 이름을 만들 때 먼저 읽는다.
- 외부로 나가는 이벤트의 필드를 바꾸거나 새 이벤트를 내보낼 때, DLT 재투입 전: [이벤트 계약](event-contract.md). 변경 종류별 호환 규칙, `schemaVersion`과 가드, golden fixture.
- JPA 엔티티 13개의 역할·참조 관계·단말/점주/운영자 흐름별 생성·전이 순서: [엔티티 지도](entity-map.md). 테이블을 더하거나 흐름을 바꿀 때 함께 고친다.
- 새 기능·변경 요청의 설계 합의: grill 스킬(`.claude/skills/resilientpos-grill/SKILL.md`). 합의 뒤 장기 스펙은 spec-write 스킬(`.claude/skills/resilientpos-spec-write/SKILL.md`).
- 설계 → 리뷰 → 구현 → 머지 파이프라인: develop 스킬(`.claude/skills/resilientpos-develop/SKILL.md`). 여러 작업의 분해·위임·머지: orchestrator 스킬(`.claude/skills/resilientpos-orchestrator/SKILL.md`).
- Git 작업(브랜치·커밋·push·PR·충돌·리뷰 조회): git 스킬(`.claude/skills/resilientpos-git/SKILL.md`). commit/push 범위 판단은 commit-push 스킬(`.claude/skills/resilientpos-commit-push/SKILL.md`)과 함께.
- 워크플로우 점검·차용 이력·전체 스킬/에이전트 목록: [차용한 스킬과 사용법](borrowed-workflows.md).
- 대화 밖 독자를 위한 글(wiki·PR 본문·보고): communicate 스킬(`.claude/skills/resilientpos-communicate/SKILL.md`). AI 글쓰기 티 제거: humanizer 스킬(`.claude/skills/resilientpos-humanizer/SKILL.md`).
- Claude Code 부속 설정(hook·statusline) 설치·제거: setup 스킬(`.claude/skills/resilientpos-setup/SKILL.md`). 명시 요청 시만.
- LLM 지침 수정·에이전트 모델 배정: [공식 문서 조사와 적용 근거](agent-instructions.md).

## 문서 역할
- `docs/wiki/`: 현재 합의된 지식. 변경 시 해당 페이지를 고치고 그 근거는 해당 PR spec에 남긴다.
- `docs/spec/yyyy-mm-dd-slug.md`: PR별 의도·범위·검증·변경 이력. [spec 템플릿](../spec-template.md)으로 시작한다.
- `docs/references/`: 제공받은 원문. 요구사항 체계화는 후속 작업이다.

전체 wiki·spec 이력·원문을 매 작업마다 일괄 읽지 않는다. 충돌이나 미확정 사항은 해당 spec에 근거와 함께 기록한다.
