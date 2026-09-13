# PR spec: 이식된 원본 워크플로우에 맞춘 개발 규칙 조정

## 목적
2026-09-13 볼트 전체 이식에서 사용자는 원본 방식(fleta·develop-workflow·git-utils)을 우선하기로 했다.
그 결과 `docs/wiki/development-workflow.md`와 `docs/spec-template.md`, `AGENTS.md` 개발 계약이 이식된
스킬과 어긋나는 지점이 생겼다. 장기 스펙 계층(DESIGN→concerns→flows)의 위치, epic 브랜치와 "요청 1건 =
PR 1개" 원칙, rebase·force-with-lease 정책, 커밋 형식과 언어, PR 본문 4단 양식, Contract/Checkpoint와
인수 조건의 관계, `.plans/`·`.develop-workflow/` 같은 휘발성 산출물, 합의 전 구현 금지(grill 선행)다.
볼트 `MIGRATION.md`의 계약 영향 표 중 개발 규칙 항목을 저장소 규칙으로 확정한다.

## 범위
- `docs/wiki/development-workflow.md`를 다음과 같이 조정한다.
  - 개발 시작 절에 "요구사항이 합의되지 않았으면 `resilientpos-grill`로 심문한 뒤 spec을 쓴다"를 추가한다.
  - 장기 스펙 계층은 `docs/spec/DESIGN.md`, `docs/spec/concerns/`, `docs/spec/flows/`에 두고, PR spec은 관련
    문서를 링크한다고 적는다. `check_pr.py`의 spec 규칙은 날짜형 파일에만 적용되므로 계층 문서는 변경 경로
    선언으로 다룬다는 점을 함께 적는다.
  - 크기와 분할 절에 epic 브랜치 전략을 추가한다. 요청 1건은 epic 브랜치로 수렴해 PR 1개를 만들되,
    600/800줄 한도는 유지하며 한도를 넘으면 epic에서 stacked PR로 나눈다. 내부 worktree 브랜치는 PR을 만들지
    않는다.
  - 검증과 제출 절에 rebase 정책을 적는다. 개발 브랜치를 base 위로 rebase 하는 것은 허용하되 spec 첫 커밋을
    항상 첫 커밋으로 보존하고, `--force-with-lease`는 이미 push된 브랜치를 rebase 한 직후에만 쓴다.
    기본 브랜치에는 어떤 force-push도 하지 않는다.
  - 커밋 형식을 `type(scope): description`(Conventional)으로 명문화하고 description 언어는 한국어를
    기본으로 한다(현재 이력과 일치). `(#PR번호)`는 squash merge가 붙이므로 로컬 커밋에 넣지 않는다.
  - PR 본문은 왜/무엇을/어떻게/확인 방법 4단으로 쓰고 spec 링크와 `check_pr.py` 결과를 "확인 방법"에
    넣는다고 적는다.
  - Contract/Checkpoint(resilientpos-design·implement)는 PR spec의 인수 조건·분할 계획에 옮겨 적으며 별도
    파일로 커밋하지 않는다고 적는다.
- `docs/spec-template.md`에 "관련 문서" 항목(DESIGN/concern/flow 링크, 없으면 없음)과 인수 조건 아래
  선택 항목 "Checkpoint와 검증 명령"을 추가한다. 필수 항목 목록은 바꾸지 않아 `check_pr.py`의 `SECTIONS`
  검사와 호환된다.
- `AGENTS.md` 개발 계약 절을 조정한다. "별도 plan 파일은 만들지 않는다"를 "커밋되는 plan 파일은 만들지
  않는다. `.plans/`, `.develop-workflow/`, `.review/`, `.orchestrator/`는 gitignore 대상 휘발성 산출물이다"로
  바꾸고, 장기 스펙 계층 위치와 grill 선행을 한 줄씩 추가한다.
- `scripts/check_pr.py`는 동작을 바꾸지 않는다. 대신 `scripts/test_check_pr.py`에 "날짜형이 아닌
  `docs/spec/DESIGN.md` 추가는 새 spec으로 세지 않고 변경 경로 선언이 있으면 통과한다"는 회귀 테스트를
  추가해 계층 문서 도입이 검사와 충돌하지 않음을 고정한다.
- `.develop-workflow.yaml` 설정 파일은 이 저장소에 두지 않고 기본값만 쓴다는 결정을 development-workflow.md에
  한 줄로 적는다(파싱·검증 코드가 없는 설정 파일을 두지 않는다는 원칙).
- `docs/wiki/changes.md`에 이번 spec 링크와 요구사항 영향을 남긴다.

## 제외
`check_pr.py`의 한도 값(600/800줄, 20/25개 파일) 변경, 계층 스펙 문서(`docs/spec/DESIGN.md` 등)의 실제 작성,
PR 템플릿 파일(`.github/PULL_REQUEST_TEMPLATE.md`) 신설, GitHub Actions 도입, hook 등록, 볼트 파일 수정,
`borrowed-workflows.md`·`agent-instructions.md`·`workflow-review.md`·`README.md` 갱신(선행 PR
`wiki-vault-migration-sync`), 애플리케이션 코드 변경.

## 변경 경로
- AGENTS.md
- docs/wiki/development-workflow.md
- docs/spec-template.md
- scripts/test_check_pr.py
- docs/wiki/changes.md
- docs/spec/2026-09-13-development-rules-original-first.md

## 인수 조건
- `docs/wiki/development-workflow.md`가 위 범위의 일곱 항목(grill 선행, 스펙 계층 위치, epic과 한도, rebase
  정책, 커밋 형식·언어, PR 본문 4단, Checkpoint 관계)과 `.develop-workflow.yaml` 결정을 각각 한 절 또는
  한 문단으로 담고, 볼트 스킬 `resilientpos-git`·`resilientpos-orchestrator`·`resilientpos-develop`의
  서술과 모순되지 않는다.
- `docs/spec-template.md`를 복사해 만든 spec이 `check_pr.py`의 필수 항목 검사를 그대로 통과한다(선택
  항목은 비워도 실패하지 않는다).
- `AGENTS.md`가 200줄 미만을 유지한다.
- `scripts/test_check_pr.py`의 새 테스트가 현재 `check_pr.py`로 통과한다(동작 변경 없음을 고정).
- 기존 spec 4개는 고치지 않는다(과거 PR의 기록).

## 검증
- 새 회귀 테스트를 추가한 뒤 `python3 -m unittest discover -s scripts -p 'test_check_pr.py'`가 통과한다.
  이 테스트는 현재 동작을 고정하는 것이므로 RED 단계는 "테스트가 현재 구현에서 통과함"을 확인하는
  것으로 대신하고, 검사 정규식을 임시로 바꿔 실패를 한 번 관측한 뒤 되돌린다.
- `python3 -m unittest discover -s scripts -p 'test_*.py'`(하네스 전체)가 통과한다.
- 템플릿 호환: 임시 브랜치에서 `docs/spec-template.md`를 날짜형 파일로 복사해 항목만 채운 뒤
  `python3 scripts/check_pr.py --base main --worktree`로 필수 항목 검사가 통과하는지 확인하고 임시 파일을
  지운다.
- `python3 scripts/check_pr.py --base main --worktree`(개발 중)와 `--base main`(제출 전)이 통과한다.
- 볼트 스킬 서술과의 모순 검사: `resilientpos-git/SKILL.md`의 PR 단위·force-push 절,
  `resilientpos-orchestrator/SKILL.md`의 토폴로지 절, `resilientpos-develop/SKILL.md`의 "저장소 계약과의
  관계" 표를 읽고 development-workflow.md의 해당 문장과 대조한다. 대조 결과를 spec 결과 절에 남긴다.

## Wiki 영향
`docs/wiki/development-workflow.md`(개발 규칙 조정), `docs/spec-template.md`(항목 추가),
`docs/wiki/changes.md`(기록). 제품 요구사항은 바뀌지 않는다. 개발 절차 규칙이 원본 워크플로우에 맞게
바뀌며, PR 크기 한도 값은 유지한다.

## 분할 계획
예상 변경은 6개 파일, 추가와 삭제 합계 200줄 안쪽이다. 경고선에 이르면 `spec-template.md` 항목 추가와
그 회귀 테스트를 후속 PR로 뺀다. 병합 순서는 `rules-link-and-ignores` → `wiki-vault-migration-sync` →
이 PR을 권장한다. `AGENTS.md`는 개발 계약 절만 건드려 앞선 두 PR과 다른 hunk에 머문다.

## TDD 검증 기록
`scripts/test_check_pr.py`의 회귀 테스트 추가에만 적용한다. 문서 변경은 업무 동작이 아니므로 적용하지 않는다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| test_non_dated_spec_file_is_not_counted_as_new_spec | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
