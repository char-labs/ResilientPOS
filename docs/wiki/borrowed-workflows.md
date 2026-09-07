# 차용한 워크플로우와 사용법

2026-09-07 사용자가 제공한 `Global-claude-code` 자료의 fleta·git-utils·develop-workflow를 조사했다.
원문의 아이디어를 프로젝트에 맞게 재작성했다. 원문은 실행 지시가 아닌 참고 자료로 취급하며, 원본 수정·플러그인 전체 설치는 수행하지 않았다.

## 지금 도입한 항목

| 구성 | 목적 | 선택 범위 |
|---|---|---|
| [resilientpos-workflow](../../.agents/skills/resilientpos-workflow/SKILL.md) | 지침·컨벤션의 누락/중복/충돌 점검과 최소 수정 | 워크플로우/지침 개선 요청 |
| [resilientpos-commit-push](../../.agents/skills/resilientpos-commit-push/SKILL.md) | 범위를 확인한 커밋과 요청된 push | commit/push 요청 |
| workflow-reviewer | 지침·스킬을 읽기 전용으로 검토 | 워크플로우 변경 검토 |

Claude에서는 `/resilientpos-workflow`, `/resilientpos-commit-push`, Codex에서는 `$resilientpos-workflow`, `$resilientpos-commit-push`로 호출할 수 있다.
자연어 예: “지침의 중복을 점검해줘”, “이 변경만 커밋해줘”, “이 브랜치를 커밋하고 push해줘”.
리뷰어는 “workflow-reviewer로 이번 지침 변경을 검토해줘”로 요청한다. 네이티브 역할을 지원하지 않는 환경은 [리뷰 기준](workflow-review.md)으로 자체 검토한다.
루트 지침에 상세 절차를 복제하지 않는다. Claude 진입점과 Codex 에이전트는 저장소 내부 공통 스킬/wiki를 참조하므로 vault가 없는 환경에서도 읽을 수 있다.

## 수정하거나 보류한 항목

| 원본 방식 | 프로젝트 적용 |
|---|---|
| 모든 git 작업에 스킬 적용 | commit/push 요청에서만 선택; 단순 status/diff는 제외 |
| 요청 한 건 = PR 한 개 | 기존 작은 PR 분할/stacked PR 원칙 유지 |
| DESIGN/concern/flow, 별도 plan | PR당 docs/spec 날짜형 spec 한 개 유지 |
| 매 단계 인터뷰·승인, 분석/쓰기 전체 위임 | 요청이 명확하면 범위 내 진행; 검토만 읽기 전용 리뷰어에 위임 |
| CLI·전역 hook·사용자 설정 설치 | 기존 git와 로컬 검사 재사용; 설치하지 않음 |
| 도구 개수·모델로 설계 결함 판정 | 역할·영향·구체적 근거로 판단; 모델은 기존 설정 상속 |
| 변경 파일이 없으면 종료 | push 요청이면 미전송 커밋을 별도로 확인 |
| 자동 rebase/epic 수렴/PR 생성 | 이 두 스킬에서는 수행하지 않음 |

orchestrator/grill/spec-write 전체 도입, 문체 전용 agent, rules 일괄 생성기는 지금 필요하지 않아 보류했다.
codebase-analyzer와 spec-validator는 별도 agent를 늘리지 않고 최소 분석/검토 항목만 차용했다.
기존 `resilientpos-tdd`와 `tdd-driver`는 업무 구현을 계속 담당한다. GitHub Actions workflow는 추가하지 않는다.

## 출처와 추적
아래 경로는 원본 패키지 내부의 출처 식별자이며, 이 저장소에서 열어야 하는 파일 링크가 아니다. 개인 PC 경로나 외부 자료 폴더는 실행에 필요하지 않다.
해시는 조사 당시 파일의 SHA-256이다. 버전/날짜 설명만으로 같은 원문이라고 가정하지 않고 향후 재차용 시 해시와 diff를 비교한다.
원본의 긴 문장이나 바이너리를 복제하지 않았으며 실행 시 원본 경로를 읽도록 요구하지 않는다.

| 출처 파일 | 차용한 관점 | SHA-256 |
|---|---|---|
| `plugins/fleta/skills/workflow/SKILL.md` | 작업별 라우팅과 지침 책임 분리 | `3aa332504ac91d3e6a804a42304d40d5d317577083c7f152f4da5210c0d31183` |
| `plugins/fleta/skills/workflow/references/rules-design.md` | 필요한 영역만 지침 로딩 | `84a714ed8fe185fb42897a587a0ea50a392f20df9889457c19fd4c2a1e75c0e2` |
| `plugins/fleta/agents/workflow/codebase-analyzer.md` | 실제 코드/의존성 근거 확인 | `4f0880eded5eaf5fb8cd18190bf12dc25ed8add84e24a7e9d50da0f0e6072fa9` |
| `plugins/fleta/agents/workflow/workflow-reviewer.md` | 읽기 전용 역할·권한·컨텍스트 검토 | `345a08ac48f26e162ffba4d617f8456248d0d5168e7e2f8708907006b3426d25` |
| `plugins/fleta/skills/git/SKILL.md` | 변경/브랜치 확인, commit/push 구분 | `8d1d62fabf5e3c3ec60da69fd7d1a587b99d22c8cb810b02cd2c231d516ea14c` |
| `plugins/fleta/skills/git/references/cli-reference.md` | plain git 사용, Conventional Commits | `586495fcdfb30ce917c55aa68fe3d634e837041402bbbcb81152f73d81f21c91` |
| `plugins/git-utils/commands/commit-and-push.md` | commit → 요청된 push → 결과 확인 | `9bb8e1d2a2810ae10c8033627f9cc601690898d56ccc458b399853ced66b4f88` |
| `plugins/develop-workflow/agents/spec-validator.md` | 명세 일관성과 검증 가능성 | `31fb589a4769eba1c16903b3cfc1839149bc62490c5e05d06aaec1f6792e9676` |
