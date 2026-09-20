# 개발과 PR 규칙

이 프로젝트는 PR별 명세를 먼저 작성하는 SDD(Spec-Driven Development)를 적용한다.
명세 → 구현 → 검증 → wiki 갱신을 하나의 개발 단위로 관리하며, 프로젝트 소개는 [README](../../README.md#개발-방법론)에 둔다.

## 개발 시작
1. 요구사항이 아직 합의되지 않았으면 [grill 스킬](../../.claude/skills/resilientpos-grill/SKILL.md)로 한 번에 한 질문씩 심문해 의도를 맞춘다. 합의 전에는 코드를 쓰지 않는다. 합의된 결정 목록이 spec의 입력이다.
2. 대상 브랜치 최신 상태에서 작업 브랜치를 만든다. 브랜치 이름은 작업 유형 prefix(`feature/`, `fix/`, `docs/`, `refactor/`, `perf/`, `test/`)로 시작한다. Orca 핸드오프로 만든 worktree는 브랜치가 `git사용자명/작업이름`으로 만들어지므로, 넘기는 세션이 create 직후 push 전에 `git -C worktree경로 branch -m 유형/작업이름`으로 바꾼다. 이미 push되어 PR이 열린 브랜치는 바꾸지 않는다. PR 기준 커밋을 확인한다. 새 머신이면 먼저 `make bootstrap`으로 스킬·에이전트·규칙 링크를 만든다.
3. `docs/spec-template.md`를 `docs/spec/yyyy-mm-dd-slug.md`로 복사한다. 날짜는 실제 작성일, slug는 영문 소문자/숫자/하이픈이다.
4. 단일 목표, 제외 범위, 변경 경로, 인수 조건, 검증, wiki 영향, 분할 계획을 작성한다. 관련 장기 스펙 문서가 있으면 "관련 문서"에 링크한다.
5. **다른 파일을 수정하기 전에 spec 파일 하나만 첫 커밋한다.** 개발 중 spec 변경은 후속 커밋에 남긴다.
6. 업무 동작 변경은 [TDD 전략](testing-strategy.md)에 따라 테스트 실패를 먼저 확인하고 최소 구현·리팩터링·회귀 검증을 수행한다. 새로운 요구사항이나 설계 변경이 생기면 먼저 같은 spec에 이유를 추가하고 관련 wiki를 갱신한다.

업무 기능 spec은 소유 모듈, Command/Query/Event, 정합성 불변식, 실패/재시도/복구,
관측성 및 정상/실패 검증도 포함한다. 관련 없는 항목은 이유를 적는다.

## 장기 스펙 계층과 PR spec
PR spec(`docs/spec/yyyy-mm-dd-slug.md`)은 PR 한 개의 계약이다. 여러 PR에 걸쳐 살아 있는 설계는
[spec-write 스킬](../../.claude/skills/resilientpos-spec-write/SKILL.md)의 계층으로 따로 둔다.

| 문서 | 위치 | 역할 |
|---|---|---|
| DESIGN | `docs/spec/DESIGN.md` | 시스템 큰 그림과 라우팅 진입점 |
| concern | `docs/spec/concerns/*.md` | 컴포넌트 하나의 관심사 |
| flow | `docs/spec/flows/*.md` | 시간축 유스케이스 시퀀스 |

- 계층 문서는 날짜형 파일이 아니므로 `check_pr.py`가 PR spec으로 세지 않는다. 대신 해당 PR spec의 변경 경로에
  `docs/spec/DESIGN.md`나 `docs/spec/concerns/**`처럼 선언해야 범위 밖 변경으로 걸리지 않는다.
- PR spec은 "관련 문서" 항목에서 DESIGN/concern/flow를 링크하고, 계층 문서는 `related_paths`로 코드와 PR spec을 가리킨다.
- 계층 문서를 새로 쓰거나 고치는 것도 PR이므로 그 PR의 날짜형 spec은 따로 있어야 한다.
- [design](../../.claude/skills/resilientpos-design/SKILL.md)·[implement](../../.claude/skills/resilientpos-implement/SKILL.md) 스킬의 Contract(Interface + Test)와 Checkpoint는
  별도 파일로 커밋하지 않는다. PR spec의 인수 조건과 분할 계획에 Checkpoint 이름·검증 명령·의존성을 옮겨 적는다.

## 크기와 분할
- 일반 변경: 추가+삭제 600줄 또는 20개 파일에서 경고, 800줄 또는 25개 파일 초과 시 실패.
- 테스트, spec, wiki, 설정도 크기에 포함한다. 바이너리는 일반 PR에서 거절한다.
- 원문 보관을 위한 `docs/references/*.md` 신규 파일만 별도 한도 10개/5000줄을 적용한다.
  기존 원문 변경/삭제는 거절한다. 수정된 해석은 wiki에 작성한다.
- PR 전체 기준 diff로 계산하므로 작은 커밋 여러 개로 한도를 우회할 수 없다.
- 서로 다른 목표, 도메인/API와 무관한 리팩터링, 별도 검증이 가능한 변경은 한도 이하에서도 나눈다.
- 경고가 나오면 남은 범위를 후속 PR로 이동한다. 현재 spec의 분할 계획에 후속 목표/의존성을 적는다.
  한도 초과 시 현재 변경을 줄인다. 한도 변경을 해당 기능 PR에 섞지 않는다.

### epic 브랜치와 PR 단위
[orchestrator 스킬](../../.claude/skills/resilientpos-orchestrator/SKILL.md)과 [git 스킬](../../.claude/skills/resilientpos-git/SKILL.md)은
"사용자 요청 1건 = 외부로 나가는 PR 1개" 원칙으로 동작한다.

```
main
  └─ epic/<name>              ← 요청 1건의 수렴점. PR은 여기서만 만든다
       ├─ worktree 브랜치 A   (sub-agent 격리 작업, epic으로 머지 후 삭제, PR 없음)
       └─ worktree 브랜치 B
```

- 내부 분해 단위(worktree 브랜치, Task)는 PR을 만들지 않는다. PR 권한은 요청 단위 한 곳에만 있다.
- epic 브랜치의 PR도 위 한도를 그대로 받는다. 한도를 넘을 것 같으면 epic 안에서 목표별 stacked PR로 나누고,
  각 PR에 날짜형 spec을 하나씩 둔다. 이때 앞선 PR 브랜치가 다음 PR의 `--base`다.
- 한 사람이 직접 작업하는 작은 변경은 epic 없이 feature 브랜치 하나로 진행해도 된다. epic은 위임·병렬이 있을 때 쓴다.

## Wiki 유지
매 PR `docs/wiki/changes.md`에 spec의 상대 링크와 요구사항 영향/무영향 근거를 남긴다.
변경이 있으면 관련 wiki 본문도 즉시 갱신하고, spec의 Wiki 영향에 페이지 링크와 이유를 기록한다.
요구사항 정리를 시작할 때 wiki에 주제별 페이지와 인덱스 링크를 추가한다. 변경 기록만으로 본문 갱신을 대체하지 않는다.

## 검증과 제출
```sh
# 개발 중: 커밋되지 않은 변경과 untracked 파일까지 확인
python3 scripts/check_pr.py --base <PR의-기준-커밋> --worktree
# 제출 전: 실제 PR 브랜치 이력 확인
python3 scripts/check_pr.py --base <PR의-기준-커밋>
python3 -m unittest discover -s scripts -p 'test_*.py'
```
`--base`는 명시해야 한다. 비교 기준은 해당 ref와 HEAD의 merge-base다.
PR 대상 브랜치의 기준 커밋을 지정해 로컬에서 검사한다. squash/rebase 전에는 spec 첫 커밋을 보존한다.
병합 시 squash는 가능하지만 개발 브랜치를 미리 squash하면 선작성 증거가 없어져 검사에 실패한다.
변경 경로는 spec의 `## 변경 경로` 아래 저장소 상대 경로/glob 목록으로 선언한다.

### Flyway 마이그레이션 버전
마이그레이션 버전은 저장소 전체에서 유일해야 한다. 같은 버전이 둘 이상 머지되면 배포된 애플리케이션이
기동 단계에서 죽는다. Flyway는 버전을 숫자로 비교하므로 `V001`과 `V1`은 같은 버전이다.

`check_pr.py`가 이번 PR의 파일과 기준 브랜치의 파일을 함께 보고 중복을 막는다. 새 마이그레이션 번호는
기준 브랜치의 마지막 번호 다음으로 붙인다.

**이 판정은 검사를 실행한 시점의 기준 브랜치에 대한 것이다.** 병렬 PR 둘이 각각 같은 번호를 잡으면 먼저
머지된 쪽이 반영된 뒤 나중 PR의 예전 통과 결과는 더 이상 유효하지 않다. 그래서 머지 직전에 다음을 다시 실행한다.

```sh
git fetch origin
python3 scripts/check_pr.py --base origin/main
```

기준의 원격이 head에 없으면 로컬 검사가 경고한다. PR 워크플로도 머지 결과 위에서 같은 검사를 다시 돌린다.
다만 브랜치 보호의 최신 상태 요구가 꺼져 있으면 워크플로의 통과 표시 역시 그 실행 시점의 기준에 대한 것이므로,
머지 직전 재실행 습관은 그대로 유지한다.

### PR 워크플로
`.github/workflows/pr-checks.yml`이 main으로 향하는 PR에서 두 단계를 순서대로 돈다.

| 단계 | 하는 일 | 실패하면 |
|---|---|---|
| Gradle build | `./gradlew build`. 통합 테스트가 Testcontainers로 실제 PostgreSQL을 띄운다 | 다음 단계를 돌지 않는다 |
| Flyway version gate | `check_pr.py --base origin/(기준) --migrations-only` | 버전 중복을 머지 전에 막는다 |

워크플로는 버전 중복만 본다. spec 첫 커밋 판정은 머지 결과 위에서 의미가 달라지므로 로컬 검사가 계속 담당한다.
빌드를 앞에 두는 이유는 컴파일도 안 되는 변경에 대해 나머지 게이트를 돌릴 이유가 없기 때문이다.

### rebase와 force-push
- 개발 브랜치를 base 위로 rebase 하는 것은 허용한다. rebase 뒤에도 spec 첫 커밋이 base 이후 첫 커밋이어야 하며,
  spec 커밋을 다른 커밋과 합치거나 순서를 바꾸지 않는다. 충돌 해결은 [git 스킬의 conflict-resolution](../../.claude/skills/resilientpos-git/references/conflict-resolution.md)을 따른다.
- `git push --force`는 쓰지 않는다. `--force-with-lease`는 이미 push된 개발 브랜치를 rebase 한 직후 한 가지 경우에만 쓴다.
- 기본 브랜치(`main`)에는 어떤 형태의 force-push도 하지 않는다. 그 외 이력 재작성이 필요하면 `git revert`나 새 커밋을 먼저 검토한다.
- 에이전트가 사용자 확인 없이 rebase·force-with-lease를 실행하지 않는다. 필요한 명령을 정리해 제안한다.

### 커밋 메시지
- 형식은 `type(scope): description`이다. type은 `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `ci`, `perf`,
  scope는 변경 대상 모듈·디렉터리(`domain`, `api`, `spec`, `wiki`, `scripts`, `skills` 등)다.
- description은 한국어를 기본으로 쓴다(현재 이력과 일치). 마침표 없이 명령형·현재형으로 50자 안쪽에 맞춘다.
- `(#PR번호)`는 squash merge 시 GitHub가 붙이므로 로컬 커밋에 넣지 않는다.
- 커밋 본문에는 관련 spec 경로와 인수 조건을 적는다. 없는 티켓 번호를 만들지 않는다.

### PR 본문
PR 본문은 왜 / 무엇을 / 어떻게 / 확인 방법 4단으로 쓴다([communicate 스킬](../../.claude/skills/resilientpos-communicate/SKILL.md) 기준, 양식은 [git 스킬 cli-reference](../../.claude/skills/resilientpos-git/references/cli-reference.md)).
"확인 방법"에는 spec 링크, `check_pr.py` 결과, 실행한 테스트 명령을 넣는다. PR 워크플로가 빌드와 마이그레이션 버전을
검사하지만 spec 이력·크기·wiki 기록은 로컬 검사만 판정하므로 그 결과를 본문에 남긴다.

### 워크플로우 산출물과 설정 파일
- `.develop-workflow/`, `.review/`, `.review-output/`, `.orchestrator/`, `.plans/`는 스킬이 만드는 휘발성 산출물이며 `.gitignore` 대상이다. 커밋하지 않는다.
- `.develop-workflow.yaml`(develop 계열 스킬의 설정 파일)은 이 저장소에 두지 않고 기본값만 쓴다. 파싱·검증 코드가 없는 설정 파일은
  오타를 조용히 삼키기 때문이다(볼트 `rules/engineering-principles.md`의 책임 경계). 도입하려면 검증 스크립트를 함께 넣는 별도 PR로 한다.

## 로컬 검사와 적용 범위
개발 에이전트는 개발 중과 PR 제출 전에 로컬 검사기를 실행하고 실패 항목을 수정한다.
로컬 검사기는 첫 spec 독립 커밋, 필수 항목, 선언 경로, 크기, wiki 기록을 검사한다.
Flyway가 읽는 마이그레이션을 더하거나 고치거나 지우는 PR이면 spec에 `스키마 변경 검토` 절(제목 수준은 `##`나 `###`)이 있는지도 검사한다. 대상은 `src/*/resources/db/migration` 아래의 `.sql`(확장자 대소문자 무관, 하위 디렉터리와 `R` 접두 포함)과 `db/migration` 패키지의 Java·Kotlin 마이그레이션이다. 절이 없거나, 답 없이 제목만 있거나, placeholder가 남아 있으면 실패한다. 코드 블록 안의 제목과 대조 기록은 인정하지 않는다.
그 절에 내용이 있는 `- 대조 기록:` 줄이 없으면 `--worktree`에서는 경고, 제출 전 이력 검사(`--worktree` 없이)에서는 실패다. 질문과 대조 절차는 [도메인 모델](domain-model.md)의 "스키마 변경 검토" 절에 있다.
검사기는 절과 대조 기록을 빠뜨리는 것만 막는다. 답이 맞는지와 대조가 실제로 있었는지는 PR 리뷰가 본다. `spring.flyway.locations`를 바꾸는 PR과 `db/migration` 패키지 밖의 `JavaMigration` 빈은 경로로 알 수 없어서 검사하지 못한다.
LLM이 파일을 편집한 실제 시각이나 요구사항 해석의 정확성까지 증명할 수는 없다. 이는 지침과 PR 리뷰로 확인한다.
spec 이력·크기·wiki 기록 검사는 명시 실행 방식이라 실행하지 않은 커밋이나 병합을 자동 차단하지 않는다.
빌드와 마이그레이션 버전만 PR 워크플로가 자동으로 막는다. 브랜치 보호 설정은 아직 쓰지 않는다.
하네스 자체 변경은 검사 우회가 없는지 별도로 리뷰한다.

## 지침 점검과 변경 전달
지침/컨벤션 개선에는 [workflow 스킬](../../.claude/skills/resilientpos-workflow/SKILL.md)을 사용한다.
커밋/push 요청에는 [commit-push 스킬](../../.claude/skills/resilientpos-commit-push/SKILL.md)을 사용하며, commit-only 요청을 원격 전송으로 확대하지 않는다.

커밋은 변경 의도별로 나눈다. spec 범위 조정은 구현 전에, wiki 갱신은 wiki 전용 커밋으로 기록한다. 도메인 개발은 요구사항 하나의 구현과 관련 테스트를 검증 가능한 단위로 묶고 커밋 본문에서 실제 spec/인수 조건을 참조한다. 작은 커밋 여러 개로 PR 총량 제한을 우회하지 않는다.
