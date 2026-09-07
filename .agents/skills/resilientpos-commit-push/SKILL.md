---
name: resilientpos-commit-push
description: Use when asked to commit or push ResilientPOS changes. Distinguishes commit-only and push requests, preserves spec-first history, and checks the exact branch and staged scope. Not for routine Git reads or automatic publication after development.
---

# ResilientPOS commit-push

공통 워크플로우의 git과 git-utils commit-and-push에서 차용했다. [출처와 변경점](../../../docs/wiki/borrowed-workflows.md).
설치/자동 선택 자체는 push 허가가 아니다. 사용자 요청의 commit/push 범위만 실행한다. 명시적인 이 스킬 실행으로 커밋과 push를 요청했다면 그 권한을 재확인하지 않는다.

## 1. 상태와 범위 확인
- `git status --short`, `git branch --show-current`, `git diff --stat`, `git diff --cached --stat`, 관련 실제 diff로 변경·브랜치·이미 staged된 범위를 확인한다.
- detached HEAD, unresolved conflict, merge/rebase/cherry-pick 진행 상태이면 커밋·push를 진행하지 말고 필요한 복구를 설명한다. 임의 abort/reset하지 않는다.
- 원격 default branch 정보와 프로젝트 규칙으로 보호 브랜치를 확인한다. 로컬 origin/HEAD가 없으면 필요한 원격 조회로 확인하고, 확인 불가면 default를 추측하지 않는다.
- 보호 브랜치에서는 새 작업 브랜치를 사용한다. 이미 검토한 작업 브랜치가 있으면 유지한다. Jira 티켓, remote 이름, PR base를 임의 생성하지 않는다.
- 기존 staged/untracked 변경 중 요청 밖 파일은 보존한다. 관련 없는 staged 변경이나 한 파일의 혼합 변경이 있으면 포함 범위를 해결하기 전 커밋하지 않는다. 자동 stash나 전체 add로 해결하지 않는다.

## 2. 변경 의도별 커밋 분할
- stage 전에 실제 diff와 PR spec으로 `의도/요구사항 → 파일 또는 hunk → 커밋 제목 → 검증`을 매핑하고 간단히 공유한다. 별도 plan 파일은 만들지 않는다.
- 기본 단위는 파일 수가 아니라 독립적으로 설명·검토·되돌릴 수 있는 변경 이유다. 전체 변경을 한 커밋에 담는 것을 기본값으로 삼지 않는다.

| 변경 종류 | 커밋 경계 | 제목 예시 |
|---|---|---|
| 새 PR spec | 항상 첫 독립 커밋 | `docs(spec): define order cancellation scope` |
| 진행 중 spec 범위 조정 | 구현 전에 spec만 커밋 | `docs(spec): clarify cancellation requirements` |
| wiki 지식/요구사항 갱신 | 관련 wiki 본문·인덱스·변경 기록만 묶음 | `docs(wiki): document cancellation rules` |
| 도메인/비즈니스 동작 | 요구사항 하나의 최소 구현과 관련 단위/통합 테스트 | `feat(order): reject cancellation after fulfillment` |
| Controller 계약 | 해당 HTTP 동작과 REST Docs 테스트 | `feat(order-api): document cancellation errors` |
| 스킬/에이전트·설정 | 같은 개발 도구 동작에 필요한 파일만 묶음 | `chore(skills): group commits by change intent` |
| README 소개 | 프로젝트 설명 변경만 분리 | `docs(readme): describe TDD adoption` |

- 도메인 커밋 본문에 실제 spec 경로와 인수 조건 또는 존재하는 요구사항 ID를 적는다. 없는 티켓/ID를 만들지 않는다. 여러 요구사항이 독립적이면 커밋도 나눈다.
- 실패 테스트 작성과 최소 구현은 TDD 순서로 수행하되, 깨진 중간 상태를 억지로 별도 커밋하지 않는다. RED/GREEN 증거는 spec에 남긴다.
- 같은 요구사항의 코드·테스트·필수 설정은 컴파일/검증 가능한 단위로 묶는다. 함께 바뀌어야 하는 계약을 레이어별로 강제 분리하지 않는다.
- 한 파일에 서로 다른 의도가 섞이면 hunk 단위로 분리한다. 안전하게 나눌 수 없는 변경은 결합 이유를 설명한다. 기존 사용자 staged 내용을 임의 재분할하지 않는다.
- wiki는 코드 커밋에서 분리하되 같은 PR에서 동기화한다. spec의 최종 검증 결과는 해당 결과가 확인된 뒤 별도 기록 커밋으로 남길 수 있다.
- 각 묶음의 staged diff를 확인하고 순서대로 커밋한다. 커밋별 대상 검증은 해당 단계에서 하고 전체 PR 검사는 완성된 변경에 수행한다.
- 커밋을 나눠도 PR 총량 한도는 그대로 적용한다. 범위가 크면 후속 PR로 나눈다.

## 3. 커밋 준비
- PR base는 spec/명시 요청/확인된 기존 PR 정보로 정한다. stacked PR이면 직전 작업 브랜치를 기준으로 사용한다. upstream(push 목적지)과 PR base(검토 기준)는 다를 수 있다.
- **새 spec만 선작성하는 커밋**: 확인된 PR 기준 이후 기존 커밋이 없고 staged 파일이 완성된 새 spec 한 개뿐인지 확인한 뒤 독립 커밋한다. 기존 커밋이 있다면 이력을 재작성하지 말고 계약 위반과 작업 분리 필요성을 보고한다. 아직 wiki/구현이 없는 이 단계에 완성 PR 검사를 요구하지 않는다.
- **구현/문서 커밋**: `python3 scripts/check_pr.py --base <확인된-base> --worktree`와 변경에 필요한 검증을 실행한다. 범위 밖 작업 때문에 실패하면 그 작업을 감추지 말고 작업 단위를 분리한다.
- `.env`, 키/인증정보, 생성물 등이 들어가지 않는지 관련 diff를 검토한다. 민감한 값은 출력하지 않는다.
- 요청된 파일/의도한 hunk만 명시적으로 stage하고 staged diff를 다시 확인한다. 사용자가 미리 stage한 hunk의 범위를 무심코 넓히지 않는다.
- 사용자 메시지가 있으면 사용하고, 없으면 변경 결과를 나타내는 `type(scope): description`으로 커밋한다. spec 첫 커밋을 squash/amend하지 않는다.
- 검증 실패 시 hook/서명 설정을 우회하지 않는다. 관련 수정 후 재검증하거나 실제 차단 원인을 보고한다.

## 4. 요청된 경우에만 push
- 작업 트리가 깨끗해도 기존 미전송 커밋이 있는지 확인한다. 변경 파일이 없다는 이유만으로 push 요청을 생략하지 않는다.
- 실제 remote, upstream의 remote 브랜치, push URL/ref를 확인한다. 인증정보가 포함된 URL을 보고서에 노출하지 않는다.
- upstream이 없으면 요청/단일 명확한 remote와 현재 작업 브랜치로 목적지를 정한다. 복수 remote 등 모호함이 남으면 목적지를 질문한다.
- 비교가 필요하면 선택한 remote의 대상 ref만 fetch하고 ahead/behind를 확인한다. 원격에 아직 브랜치가 없으면 새 작업 브랜치 push로 처리한다.
- remote가 앞서거나 분기되었으면 자동 rebase/merge/force-push하지 않는다. 차이와 해결 필요성을 보고한다.
- 목적지를 명시한 일반 push를 실행한다. 예: `git push -u <remote> HEAD:refs/heads/<확인된-작업-브랜치>`; 기존 upstream은 확인한 목적지를 사용한다.
- 실패 원인에 맞게 대응한다. sandbox/network 제한은 도구의 승인 절차를 따르고, 원격 권한 거부나 불확실한 결과는 상태 확인 없이 반복하지 않는다.
- 원격 ref와 로컬 HEAD 일치를 조회해 성공을 확인한다. 확인이 실패하면 전송 결과와 확인 미완료를 구분한다.

PR 생성·병합·배포는 이 스킬의 자동 후속 단계가 아니다. 결과에 커밋 SHA, 검증, 실제 push 목적지/결과 또는 미실행 사유를 남긴다.
