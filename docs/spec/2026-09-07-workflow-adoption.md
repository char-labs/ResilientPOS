# PR spec: 공통 워크플로우 기반 개발 흐름과 commit-push 도입

## 목적
사용자의 Obsidian Global-claude-code에서 당장 필요한 스킬·에이전트만 차용하여 ResilientPOS의 SDD/TDD와 Git 작업에 적용한다.

## 범위
- 공통 워크플로우 플러그인의 workflow/git, git-utils commit-and-push, workflow-reviewer와 codebase-analyzer를 조사한다.
- 워크플로우/컨벤션 점검과 commit-push 스킬을 공통 .agents/skills에 두고 Claude 진입점을 연결한다.
- Claude/Codex 읽기 전용 workflow-reviewer를 추가한다. 기존 tdd-driver를 재사용한다.
- 출처 패키지 내부 상대 경로와 SHA-256, 차용/수정/보류 이유를 wiki에 기록한다. 실행은 Obsidian 원본 경로에 의존하지 않는다.

## 제외
워크플로우 CLI/전체 플러그인 설치, 전역 설정/hook 변경, GitHub workflow, PR 생성/병합, 별도 plan이나 DESIGN/concern/flow 문서 계층, 업무 코드 변경.

## 변경 경로
- .agents/skills/resilientpos-workflow/**
- .agents/skills/resilientpos-commit-push/**
- .claude/skills/resilientpos-workflow/**
- .claude/skills/resilientpos-commit-push/**
- .claude/agents/workflow-reviewer.md
- .codex/agents/workflow-reviewer.toml
- AGENTS.md
- docs/**

## 인수 조건
- 두 스킬은 관련 요청에서만 선택되며 모든 Git 조회나 모든 개발 작업에 강제로 로딩되지 않는다.
- workflow는 실제 Kotlin/Gradle 선언과 기존 지침을 근거로 범위를 좁힌다. 스택 추측, 전체 wiki 로딩, 불필요한 규칙 생성은 피한다.
- commit과 push의 요청 범위를 구분한다. commit-only면 push하지 않고, 명시 push 요청이면 같은 요청 내 재확인을 반복하지 않는다.
- spec 독립 첫 커밋을 보존하고, 기존 로컬 PR 크기/범위/wiki 검사를 사용한다.
- 기본 브랜치, detached HEAD, 충돌/진행 중 작업, 관련 없는 staged 변경을 확인한다. 전체 add/자동 stash/강제 push/자동 rebase는 하지 않는다.
- uncommitted 변경이 없더라도 미전송 커밋이 있으면 push 요청을 처리한다. PR 생성은 별도 요청 범위다.
- 리뷰어는 읽기 전용이며 기존 SDD/TDD와 스킬 간 충돌, 실제 참조 경로를 점검한다.

## 검증
공식 skill validator, Claude agent validator, Codex TOML 파싱, 참조 링크, 출처 해시, 로컬 PR 검사와 독립 시나리오 리뷰를 수행한다. 스킬 자체 검증은 원격 작업 없이 수행한다. 사용자의 후속 commit-push 요청에 따라 검증 후 현재 작업 브랜치를 전송한다.

## Wiki 영향
차용 내역/사용법 페이지와 리뷰 기준을 추가하고 wiki 인덱스, 변경 기록, 개발 규칙을 연결한다. 사용자 전역 지침과 원본 Obsidian 자료는 수정하지 않는다.

## 분할 계획
기준 chore/tdd-agent의 7007f54 위에 별도 브랜치로 분리한다. 두 스킬·리뷰어 한 역할로 한정하며 18개 파일/450줄 이내를 예상한다. 자율 orchestration, 충돌 해결, 이슈/PR 리뷰 자동화는 필요 시 후속 PR로 다룬다.

## 결과
- spec 독립 첫 커밋 1d4b12b, 기준 7007f54. 문서·설정 변경이므로 업무 TDD는 적용하지 않았다.
- 공통 스킬 두 개와 Claude 진입점 두 개의 quick_validate, Claude agent validator, Codex read-only TOML 필수 필드/문법 검사 통과.
- 저장소 상대 링크, 공통 스킬 참조, Obsidian 원문 해시 8개 일치 확인.
- 독립 리뷰에서 commit-only/staged 범위, clean tree의 미전송 커밋, stacked PR base/upstream 구분, spec-only 첫 커밋 시나리오를 확인했다.
- spec 선작성 조건을 “PR 기준 이후 기존 커밋 없음”으로 보완하고 읽기 전용 리뷰어에 diff를 전달하도록 수정한 뒤 공통 스킬 검사를 재실행했다.
- 로컬 PR 검사와 git diff --check 통과. 실제 remote push/PR 생성과 새 세션의 네이티브 자동 탐색은 실행하지 않았다.

## 변경 이력
- 다른 PC에서도 사용할 수 있도록 개인 PC의 원본 절대 경로를 문서에서 제거한다. 출처는 패키지명·패키지 내부 경로·해시로만 추적하고, 실제 읽기 참조는 저장소 내부 상대 링크를 사용한다.
- 변경 컨텍스트에 따라 spec 갱신·스킬 수정·wiki 갱신을 별도 커밋으로 나누고, 도메인 개발은 요구사항별 구현과 관련 테스트를 함께 묶도록 커밋 기준을 보완한다. 후속 명시 요청으로 현재 브랜치 push를 범위에 포함한다.
