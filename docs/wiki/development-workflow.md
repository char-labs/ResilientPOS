# 개발과 PR 규칙

이 프로젝트는 PR별 명세를 먼저 작성하는 SDD(Spec-Driven Development)를 적용한다.
명세 → 구현 → 검증 → wiki 갱신을 하나의 개발 단위로 관리하며, 프로젝트 소개는 [README](../../README.md#개발-방법론)에 둔다.

## 개발 시작
1. 요구사항이 아직 합의되지 않았으면 grill 스킬(`.claude/skills/resilientpos-grill/SKILL.md`)로 한 번에 한 질문씩 심문해 의도를 맞춘다. 합의 전에는 코드를 쓰지 않는다. 합의된 결정 목록이 spec의 입력이다.
2. [충돌 방지](#충돌-방지-가장-먼저-지킨다) 확인을 거쳐 대상 브랜치 최신 상태에서 작업 브랜치를 만든다. 브랜치 이름은 작업 유형 prefix(`feature/`, `fix/`, `docs/`, `refactor/`, `perf/`, `test/`)로 시작한다. Orca 핸드오프로 만든 worktree는 브랜치가 `git사용자명/작업이름`으로 만들어지므로, 넘기는 세션이 create 직후 push 전에 `git -C worktree경로 branch -m 유형/작업이름`으로 바꾼다. 이미 push되어 PR이 열린 브랜치는 바꾸지 않는다. PR 기준 커밋을 확인한다. 새 머신이나 새 worktree면 에이전트 세션을 시작하기 전에 `make -C worktree경로 bootstrap`으로 스킬·에이전트 링크와 규칙 복사본을 만든다. 규칙은 세션 시작 때 실리므로 세션이 먼저 뜨면 그 세션에는 실리지 않는다.
3. `docs/spec-template.md`를 `docs/spec/yyyy-mm-dd-slug.md`로 복사한다. 날짜는 실제 작성일, slug는 영문 소문자/숫자/하이픈이다.
4. 단일 목표, 제외 범위, 변경 경로, 인수 조건, 검증, wiki 영향, 분할 계획을 작성한다. 관련 장기 스펙 문서가 있으면 "관련 문서"에 링크한다.
5. **다른 파일을 수정하기 전에 spec 파일 하나만 첫 커밋한다.** 개발 중 spec 변경은 후속 커밋에 남긴다.
6. 업무 동작 변경은 [TDD 전략](testing-strategy.md)에 따라 테스트 실패를 먼저 확인하고 최소 구현·리팩터링·회귀 검증을 수행한다. 새로운 요구사항이나 설계 변경이 생기면 먼저 같은 spec에 이유를 추가하고 관련 wiki를 갱신한다.

업무 기능 spec은 소유 모듈, Command/Query/Event, 정합성 불변식, 실패/재시도/복구,
관측성 및 정상/실패 검증도 포함한다. 관련 없는 항목은 이유를 적는다.

## 장기 스펙 계층과 PR spec
PR spec(`docs/spec/yyyy-mm-dd-slug.md`)은 PR 한 개의 계약이다. 여러 PR에 걸쳐 살아 있는 설계는
spec-write 스킬(`.claude/skills/resilientpos-spec-write/SKILL.md`)의 계층으로 따로 둔다.

| 문서 | 위치 | 역할 |
|---|---|---|
| DESIGN | `docs/spec/DESIGN.md` | 시스템 큰 그림과 라우팅 진입점 |
| concern | `docs/spec/concerns/*.md` | 컴포넌트 하나의 관심사 |
| flow | `docs/spec/flows/*.md` | 시간축 유스케이스 시퀀스 |

- 계층 문서는 날짜형 파일이 아니므로 `check_pr.py`가 PR spec으로 세지 않는다. 대신 해당 PR spec의 변경 경로에
  `docs/spec/DESIGN.md`나 `docs/spec/concerns/**`처럼 선언해야 범위 밖 변경으로 걸리지 않는다.
- PR spec은 "관련 문서" 항목에서 DESIGN/concern/flow를 링크하고, 계층 문서는 `related_paths`로 코드와 PR spec을 가리킨다.
- 계층 문서를 새로 쓰거나 고치는 것도 PR이므로 그 PR의 날짜형 spec은 따로 있어야 한다.
- design(`.claude/skills/resilientpos-design/SKILL.md`)·implement(`.claude/skills/resilientpos-implement/SKILL.md`) 스킬의 Contract(Interface + Test)와 Checkpoint는
  별도 파일로 커밋하지 않는다. PR spec의 인수 조건과 분할 계획에 Checkpoint 이름·검증 명령·의존성을 옮겨 적는다.

## PR 단위와 충돌 방지
- **PR 하나는 개발 컨텍스트 하나다.** 한 도입·변경 목표와 그에 딸린 테스트·spec·wiki·설정을 한 PR에 담는다.
  도입 때문에 양이 커지는 것은 받아들인다.
- **크기로 나누지 않는다.** `check_pr.py`가 출력하는 일반 변경의 파일 수·줄 수는 참고 수치이고 판정하지 않는다.
- **서로 다른 컨텍스트는 작아도 나눈다.** 목표와 무관한 리팩터링이나 다른 기능의 정리는 따로 낸다.
- **스택 PR(다른 PR 브랜치를 base로 삼는 PR)을 만들지 않는다.** 앞 작업에 의존하면 앞 PR이 머지되길 기다렸다
  `main`에서 시작하거나, 같은 컨텍스트면 한 PR로 합친다. 앞 PR이 squash 머지되면 뒤 PR의 base가 사라져
  `rebase --onto`와 충돌 해결이 따라온다.
- 바이너리는 일반 PR에서 거절한다. 원문 보관(`docs/references/**`의 신규 Markdown)에는 10개/5000줄 한도가 있다.
  기존 원문 변경/삭제는 거절한다. 수정된 해석은 wiki에 작성한다.

### 충돌 방지: 가장 먼저 지킨다
**작업 시작, push, PR 생성, 머지 직전**에 매번 확인한다.

```sh
git fetch origin
git log --oneline HEAD..origin/main                         # main에 새로 들어온 커밋
gh pr list --state open --json number,headRefName,files     # 열린 PR과 변경 파일
```

1. **origin/main이 앞서 있으면** 곧바로 rebase하고 전체 검증(`./gradlew test`, 하네스 테스트, `check_pr.py`)을 다시 돌린다.
   다른 PR이 main에 먼저 들어온 때도 같다. 에이전트는 아래 "rebase와 force-push" 절에 따라 명령을 제안하고 확인을 받는다.
2. **열린 PR과 변경 경로가 겹치는지 본다.** 각 PR spec의 "변경 경로" 절과 `gh pr list`의 파일 목록을 대조한다.
   `docs/spec/**`는 뺀다 — PR마다 자기 spec을 그 아래 쓰므로 넣으면 모든 PR이 겹친다고 나온다.
   `docs/wiki/**`는 같은 페이지의 같은 절을 건드릴 때만 겹침으로 센다.
   **경로가 안 겹친다고 독립이 보장되지는 않는다** — 다른 파일에 있는 호출자와 제공자 사이의 의존은 잡지 못한다.
   열린 PR 없이 지금 `main`에서 구현하고 검증할 수 있는지를 함께 본다.
3. **겹치면 기다리거나 합친다.** 같은 컨텍스트면 한 spec 아래 한 PR로 합치고, 다른 컨텍스트면 앞 PR이 머지된 뒤
   `main`에서 시작한다.
4. **공용 헬퍼·규칙을 새로 만들기 전에** `main`과 열린 PR에 같은 것이 생겼는지 찾는다
   (`git grep 이름 origin/main`, `gh pr diff 번호`). 있으면 그것을 쓰거나 그 PR이 머지되길 기다린다.

### 다음 작업을 고를 때
열린 PR이 있는 상태에서 다음 작업을 시작할 때 이 순서로 고른다.

1. **열린 PR이 편집하는 경로를 피한다.** 겹치지 않으면 `main`에서 브랜치를 따고 독립 PR로 낸다.
2. **겹치면 앞 PR이 머지되길 기다린다.** 기다렸다 `main`에서 시작하면 rebase 비용이 0이다.
3. **기다릴 수 없고 같은 컨텍스트면 한 PR로 합친다.** 다른 컨텍스트면 기다린다. 스택으로 풀지 않는다.

### epic 브랜치와 PR 단위
orchestrator 스킬(`.claude/skills/resilientpos-orchestrator/SKILL.md`)과 git 스킬(`.claude/skills/resilientpos-git/SKILL.md`)은
"개발 컨텍스트 1개 = 외부로 나가는 PR 1개" 원칙으로 동작한다. 요청 하나가 한 컨텍스트면 크기와 상관없이 PR 하나다.

```
main
  └─ epic/<name>              ← 컨텍스트 1개의 수렴점. PR은 여기서만 만든다
       ├─ worktree 브랜치 A   (sub-agent 격리 작업, epic으로 머지 후 삭제, PR 없음)
       └─ worktree 브랜치 B
```

- 내부 분해 단위(worktree 브랜치, Task)는 PR을 만들지 않는다. PR 권한은 컨텍스트 단위 브랜치 한 곳에만 있다.
- epic 브랜치의 PR도 위 "PR 단위와 충돌 방지"를 그대로 따른다. 요청 안에 서로 다른 컨텍스트가 섞였으면
  컨텍스트마다 epic(또는 feature) 브랜치를 `main`에서 따로 따고 날짜형 spec을 하나씩 둔다.
  뒤 컨텍스트가 앞 컨텍스트에 의존하면 앞 PR이 머지된 뒤 `main`에서 딴다. 다른 PR 브랜치를 `--base`로 삼지 않는다.
- 한 사람이 직접 작업하는 작은 변경은 epic 없이 feature 브랜치 하나로 진행해도 된다. epic은 위임·병렬이 있을 때 쓴다.

## Wiki 유지
변경이 있으면 관련 wiki 본문을 즉시 갱신하고, spec의 Wiki 영향에 페이지 링크와 이유를 기록한다.
요구사항 영향/무영향 근거도 그 spec 한 곳에만 남긴다. 별도의 변경 기록 파일은 두지 않는다.
요구사항 정리를 시작할 때 wiki에 주제별 페이지와 인덱스 링크를 추가한다. spec 기록만으로 본문 갱신을 대체하지 않는다.

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
**별표 하나는 한 경로 조각 안에서만 매칭하고 여러 조각을 넘으려면 별표 둘을 쓴다.** `docs/**`는 `docs` 아래 전부이고 `scripts/*.py`는 `scripts` 바로 아래의 `.py`뿐이다. 별표 하나가 구분자를 넘던 때는 `- *` 한 줄로 범위 검사 전체가 무력해졌다([pr-harness-gaps](../spec/2026-09-21-pr-harness-gaps.md)).
**`*`, `**`처럼 리터럴이 하나도 없는 패턴은 범위를 선언하지 않으므로 거절한다.** 문자 클래스(`[0-9]` 같은 것)는 지원하지 않고 리터럴로 본다. `**`는 디렉터리 하나 이상에 대응하므로 `docs/**/x.md`는 `docs/x.md`를 매칭하지 않는다.
**같은 이름의 `##` 절이 둘이면 거절한다.** 리뷰어가 읽는 절과 검사기가 쓰는 절이 달라지기 때문이다. 이름의 앞뒤 공백은 무시한다. 코드 블록 안의 제목은 절로 세지 않으며, 닫는 표시가 없으면 여는 표시부터 문서 끝까지 코드 블록으로 본다. 절의 위치만 코드 블록 밖에서 찾고 본문은 원문 그대로 보므로, 명령만 적은 `검증` 절도 빈 절이 아니다.

### Flyway 마이그레이션 버전
마이그레이션 버전은 저장소 전체에서 유일해야 한다. 같은 버전이 둘 이상 머지되면 배포된 애플리케이션이
기동 단계에서 죽는다. Flyway는 버전을 숫자로 비교하므로 `V001`과 `V1`은 같은 버전이다.
버전 검사의 대상은 스키마 변경 검토를 요구하는 경로와 같다. `src/*/resources/db/migration` 아래의 `.sql`(확장자 대소문자 무관, 하위 디렉터리 포함)과 `db/migration` 패키지의 Java·Kotlin 마이그레이션이며, 저장소 최상위가 아닌 `src/`(멀티모듈)도 대상이다. 버전이 없는 반복 마이그레이션(`R__`)은 대상이 아니다. 두 판정이 갈라져 있던 때는 대문자 확장자·하위 디렉터리·Kotlin 마이그레이션으로 버전 중복을 빠져나갈 수 있었다([pr-harness-gaps](../spec/2026-09-21-pr-harness-gaps.md)).
마이그레이션 파일명만 바꾸는 변경은 중복으로 잡지 않는다. 이 PR이 지운 경로를 판정에서 빼기 때문이다. **아직 어느 환경에도 적용되지 않은 마이그레이션에 한한다.** 적용된 뒤에 파일명을 바꾸면 Flyway의 `validate`가 기동을 막으므로 이름을 되돌리거나 새 버전으로 더한다.

`check_pr.py`가 이번 PR의 파일과 기준 브랜치의 파일을 함께 보고 중복을 막는다. 새 마이그레이션 번호는
기준 브랜치의 마지막 번호 다음으로 붙인다.

**이 판정은 검사를 실행한 시점의 기준 브랜치에 대한 것이다.** 병렬 PR 둘이 각각 같은 번호를 잡으면 먼저
머지된 쪽이 반영된 뒤 나중 PR의 예전 통과 결과는 더 이상 유효하지 않다. 그래서 머지 직전에 다음을 다시 실행한다.

```sh
git fetch origin
python3 scripts/check_pr.py --base origin/main
```

기준의 원격이 head에 없으면 로컬 검사가 경고한다. PR 워크플로도 머지 결과 위에서 같은 검사를 다시 돌린다.
이 저장소는 브랜치 보호를 켤 수 없어서(아래 "로컬 검사와 적용 범위") 머지 전 최신 상태를 요구할 수단이 없다. 워크플로의 통과 표시는 그 실행 시점의 기준에 대한 것이므로,
머지 직전 재실행 습관은 그대로 유지한다.

### PR 워크플로
`.github/workflows/pr-checks.yml`이 main으로 향하는 PR에서 두 job을 함께 돌린다. main push에서는 Gradle build만 돈다.

| job | 하는 일 | 실패하면 |
|---|---|---|
| Gradle build | `./gradlew build`. 통합 테스트가 Testcontainers로 PostgreSQL·Redis·Kafka를 띄운다 | PR에 실패로 표시된다 |
| Flyway version gate | `check_pr.py --base origin/(기준) --migrations-only` | 버전 중복을 머지 전에 막는다 |

워크플로는 버전 중복만 본다. spec 첫 커밋 판정은 머지 결과 위에서 의미가 달라지므로 로컬 검사가 계속 담당한다.
Flyway gate는 빌드 산출물을 쓰지 않으므로 빌드를 기다리지 않는다. 빌드가 실패해도 버전 중복 결과는 따로 나온다.

Flyway gate 병렬 외에 검사 시간을 줄이는 장치가 둘 있다([ci-build-time](../spec/2026-10-22-ci-build-time.md)).
- test는 fork 2개로 나눠 돈다(`build.gradle.kts`의 `maxParallelForks`). fork마다 컨테이너를 따로 띄워 DB와 consumer group을 같이 쓰지 않는다. 고정값이라 로컬과 CI가 같은 fork 수로 돈다. 클래스를 fork에 나누는 순서는 환경마다 다를 수 있다.
- `gradle.properties`가 Gradle build cache를 켠다. setup-gradle은 main push job에서만 캐시를 저장하고 PR은 main의 마지막 캐시를 읽기만 한다. 그래서 입력이 main과 같은 task만 `FROM-CACHE`로 건너뛴다. 문서만 고친 PR은 compile과 test를 건너뛰고, 소스를 고친 PR은 다시 돈다. runbook이나 알림 규칙(`docker/prometheus`)은 test 입력이라 고치면 test 전체가 다시 돈다. PR을 같은 커밋으로 다시 돌려도 PR 실행은 캐시를 저장하지 않으므로 처음 실행과 같다.
- test도 캐시하므로 입력에 없는 변화(태그가 움직이는 컨테이너 이미지, 간헐적 실패)는 test를 건너뛴 실행에서 잡지 못한다. 의심되면 `./gradlew test --rerun`으로 다시 돌린다. 테스트가 classpath 밖 파일을 읽으면 `build.gradle.kts`의 Test `inputs`에, `build` 아래에 파일을 남기면 `outputs`에 더한다. output으로 두지 않은 파일은 test가 캐시에서 올 때 생기지 않는다.

### rebase와 force-push
- 개발 브랜치를 base 위로 rebase 하는 것은 허용한다. rebase 뒤에도 spec 첫 커밋이 base 이후 첫 커밋이어야 하며,
  spec 커밋을 다른 커밋과 합치거나 순서를 바꾸지 않는다. 충돌 해결은 git 스킬의 conflict-resolution(`.claude/skills/resilientpos-git/references/conflict-resolution.md`)을 따른다.
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
PR 본문은 의사결정 문서로 쓴다. 양식은 [PR 템플릿](../../.github/PULL_REQUEST_TEMPLATE.md)이 정하고,
작성 가이드는 템플릿 안의 HTML 주석에 있다. git 스킬은 PR을 만들기 전에 템플릿을 먼저 읽고 그 절 구조를 따른다.
볼트의 git 스킬 cli-reference와 communicate 스킬은 아직 "PR 본문 4단 고정"을 단일 출처로 선언하고 있어 이 절과 충돌한다.
이 저장소에서는 템플릿이 그 4단 고정을 대체한다. 두 지침이 다르면 템플릿을 따른다.

| 절 | 담는 것 |
|---|---|
| 왜 해야 하나요? | 한두 문장과 spec 경로·첫 커밋 SHA 한 줄. spec의 목적·범위를 다시 옮겨 적지 않는다 |
| 무엇을, 어떻게 해결했나요? | 실제로 바뀐 것과 고른 접근 |
| 한계와 트레이드오프 | 해결하지 못하는 것, 감수한 위험, 후속 PR로 넘긴 범위 |
| 기존 기능에 미치는 영향 | 이미 동작하던 기능·절차가 달라지는 지점 |
| Edge case와 실패 시나리오 | 경계 입력과 실패했을 때의 동작 |
| 검토한 대안과 선택 이유 | 버린 선택지와 그 이유 |
| 리뷰 포인트 | `파일/영역`, `Risk`, `봐야 할 것` 표와 사람의 리뷰가 필요한지 여부 |
| 확인 방법 | 실행한 명령과 결과, 범위·검증·wiki 체크박스 |

- communicate 스킬(`.claude/skills/resilientpos-communicate/SKILL.md`)의 네 가지 질문(왜 / 무엇을 / 어떻게 / 확인 방법)은
  새 템플릿의 골격으로 그대로 남는다. 첫 두 절과 마지막 절이 그 질문이고, 가운데 다섯 절은 "어떻게"에 묻혀 빠지기 쉬운
  판단(한계, 영향, 실패 시나리오, 버린 대안, 리뷰 지점)을 따로 꺼낸 것이다.
- 해당 사항이 없는 절은 지우지 않고 `해당 없음: 근거` 한 줄로 채운다. 문서 전용 PR도 같은 양식을 쓴다.
- 각 절은 diff와 PR spec을 분석해서 채우고, 실행하지 않은 검증은 "미실행"으로 적는다.
- Risk는 🔴/🟡/🟢 세 단계로 판정한다. 기준 목록은 [PR 템플릿](../../.github/PULL_REQUEST_TEMPLATE.md)의 주석이 정본이고
  여기에 복제하지 않는다. 🔴에는 diff로 확인하는 사실 기준이 있어서, 해당하는 파일이 diff에 있으면 작성자의 판단과 무관하게
  🔴다. 사실 기준을 둔 이유는 두 가지다. 작성자가 🔴을 고르면 자기 일이 늘어나므로, 판단에만 맡기면 🔴이 선택되지 않는다.
  그리고 통합 테스트는 매번 빈 DB로 시작하고 datasource를 주입받으므로, 저장된 이름·응답에 나가는 상수·운영 설정의 변경은
  같은 PR에서 테스트가 함께 바뀌어 통과한다.
- 리뷰 포인트에 🔴 항목이 있으면 제출 전에 multi-review 스킬(`.claude/skills/resilientpos-multi-review/SKILL.md`)을
  실행하고 결과를 "확인 방법"의 `multi-review:` 줄에 기록한다. 🔴 항목이 없으면 "미실행(🔴 없음)"이라고 적는다.
  AI 리뷰 봇은 두지 않는다. 작성자가 한 명이고 기록된 리뷰가 없어서 봇이 줄여 줄
  대기 시간이 없고, 로컬 multi-review가 같은 역할을 한다.
- 이 절의 규칙(절 유지, Risk 판정, 🔴일 때의 multi-review)을 자동으로 강제하는 장치는 없다. `check_pr.py`는 PR 본문을
  읽지 않고, 기록된 리뷰도 없으므로 절 누락을 리뷰가 잡아 준다고 전제하지 않는다. 작성자가 제출 전에 직접 점검한다.
  "사람의 리뷰가 필요하다"에 체크했다면 머지를 보류하고 사용자에게 알린다.
- "확인 방법"에는 `check_pr.py` 결과와 실행한 테스트 명령을 넣는다. PR 워크플로가 빌드와 마이그레이션 버전을
  검사하지만 spec 이력은 로컬 검사만 판정하므로 그 결과를 본문에 남긴다.

### 워크플로우 산출물과 설정 파일
- `.develop-workflow/`, `.review/`, `.review-output/`, `.orchestrator/`, `.plans/`는 스킬이 만드는 휘발성 산출물이며 `.gitignore` 대상이다. 커밋하지 않는다.
- `.develop-workflow.yaml`(develop 계열 스킬의 설정 파일)은 이 저장소에 두지 않고 기본값만 쓴다. 파싱·검증 코드가 없는 설정 파일은
  오타를 조용히 삼키기 때문이다(볼트 `rules/engineering-principles.md`의 책임 경계). 도입하려면 검증 스크립트를 함께 넣는 별도 PR로 한다.

## 로컬 검사와 적용 범위
개발 에이전트는 개발 중과 PR 제출 전에 로컬 검사기를 실행하고 실패 항목을 수정한다.
로컬 검사기는 첫 spec 독립 커밋, 필수 항목, 선언 경로를 검사한다. 크기는 통계로만 출력한다.
Flyway가 읽는 마이그레이션을 더하거나 고치거나 지우는 PR이면 spec에 `스키마 변경 검토` 절(제목 수준은 `##`나 `###`)이 있는지도 검사한다. 대상은 `src/*/resources/db/migration` 아래의 `.sql`(확장자 대소문자 무관, 하위 디렉터리와 `R` 접두 포함)과 `db/migration` 패키지의 Java·Kotlin 마이그레이션이다. 절이 없거나, 답 없이 제목만 있거나, placeholder가 남아 있으면 실패한다. 코드 블록 안의 제목과 대조 기록은 인정하지 않는다.
그 절에 내용이 있는 `- 대조 기록:` 줄이 없으면 `--worktree`에서는 경고, 제출 전 이력 검사(`--worktree` 없이)에서는 실패다. 질문과 대조 절차는 [도메인 모델](domain-model.md)의 "스키마 변경 검토" 절에 있다.
검사기는 절과 대조 기록을 빠뜨리는 것만 막는다. 답이 맞는지와 대조가 실제로 있었는지는 PR 리뷰가 본다. `spring.flyway.locations`를 바꾸는 PR과 `db/migration` 패키지 밖의 `JavaMigration` 빈은 경로로 알 수 없어서 검사하지 못한다.
LLM이 파일을 편집한 실제 시각이나 요구사항 해석의 정확성까지 증명할 수는 없다. 이는 지침과 PR 리뷰로 확인한다.
spec 이력 검사는 명시 실행 방식이라 실행하지 않은 커밋이나 병합을 자동 차단하지 않는다.
빌드와 마이그레이션 버전만 PR 워크플로가 검사한다.
**자동으로 머지를 막는 장치는 없다.** 브랜치 보호는 쓰지 않는 것이 아니라 이 저장소의 플랜에서 켤 수 없다(API가 `403 Upgrade to GitHub Pro`를 돌려준다). 그래서 필수 검사(required status check)를 걸 수 없고, 검사가 실패했거나 아예 돌지 않은 PR도 머지 버튼이 살아 있다. 워크플로의 통과 표시는 게이트가 아니라 신호이며, 머지 전에 사람이 확인한다([pr-harness-gaps](../spec/2026-09-21-pr-harness-gaps.md)).
하네스 자체 변경은 검사 우회가 없는지 별도로 리뷰한다.

## 지침 점검과 변경 전달
지침/컨벤션 개선에는 workflow 스킬(`.claude/skills/resilientpos-workflow/SKILL.md`)을 사용한다.
커밋/push 요청에는 commit-push 스킬(`.claude/skills/resilientpos-commit-push/SKILL.md`)을 사용하며, commit-only 요청을 원격 전송으로 확대하지 않는다.

커밋은 변경 의도별로 나눈다. spec 범위 조정은 구현 전에, wiki 갱신은 wiki 전용 커밋으로 기록한다. 도메인 개발은 요구사항 하나의 구현과 관련 테스트를 검증 가능한 단위로 묶고 커밋 본문에서 실제 spec/인수 조건을 참조한다.
