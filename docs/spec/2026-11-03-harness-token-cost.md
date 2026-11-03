# PR spec: 하네스 설정으로 세션 토큰을 줄인다

## 목적
이 저장소의 Claude Code 세션은 토큰 대부분을 Opus 메인 세션의 매 호출 문맥에 쓴다. 2026-11-02~03 세 세션(조회 Phase 2·4, 문서 정리)의 transcript를 보면 메인 Opus 340호출의 cache read가 7,550만으로 서브에이전트 전체의 약 5배다. 호출당 문맥은 중앙값 약 20만, 최대 36만이다. 첫 호출 문맥은 약 68k이고, `.kt`를 처음 읽을 때 한 번에 35~52k가 는다. 사용자 설정이 Opus 5.5의 compact 기준을 400k로 두고 있어서 세 세션 모두 한 번도 compact하지 않았다. hook 출력은 세션당 수천 토큰이라 원인이 아니다.

이 PR은 저장소에 체크인하는 `.claude/settings.json`으로 이 저장소의 세션만 바꾼다. 실린 문맥을 줄이고, 메인 세션을 Sonnet + Opus advisor로 바꿔 단가를 낮춘다. 사용자가 직접 요청했고(2026-11-03) GitHub 이슈는 없다.

## 범위
요구사항은 사용자와 합의했다. `resilientpos-grill`은 아래 재진술을 확인하는 데만 썼다.

**`.claude/settings.json`(새 파일, 체크인)**
- `claudeMdExcludes`로 전역 `~/.claude/rules/ecc/kotlin/*.md` 다섯 파일을 뺀다. 이 다섯 파일은 `paths: **/*.kt`로 실리는 Android·KMP 지침(ViewModel, Room, Ktor, ProGuard, WebView, Turbine)이라 이 Spring 백엔드에 맞지 않고, 저장소 Kotlin rules 네 개(볼트 복사본)와 겹친다. 패턴은 `**/.claude/rules/ecc/kotlin/**`이다. 홈 경로를 적지 않으므로 다른 기여자 머신에도 그대로 맞고, 그 경로가 없는 머신에서는 아무것도 빼지 않는다. 저장소 `.claude/rules` 아래에는 `ecc/`가 없어서 저장소 rules는 빠지지 않는다. 저장소 Kotlin rules 네 개는 그대로 둔다(2026-10-01 실측에서 줄일 곳이 없었다).
- `enabledPlugins`로 이 저장소 transcript에서 사용이 0회였던 플러그인을 끈다: `claw-hwp@claw-hwp`, `taste-skill@taste-skill`, `ui-ux-pro-max@ui-ux-pro-max-skill`, `impeccable@impeccable`, `kordoc@synced`. 공식 문서상 project 설정이 user 설정보다 우선하므로 user의 `true`를 덮는다. synced 플러그인도 끌 수 있다. 사용자 설정의 `data@synced: false`가 `claude plugin list`에 disabled로 나온다. `orca-agent-view@inline`은 Orca 연동이고 요청 목록에 없어서 건드리지 않는다. eli5·humanize-korean·superpowers·ponytail·warp·security-guidance·LSP·i-have-adhd도 건드리지 않는다.
- `autoCompactWindow`를 `300000`으로 둔다. 근거와 대가는 아래 "자동 compact 기준" 절에 적는다.
- `model: "sonnet"`과 `advisorModel: "opus"`로 메인 세션을 Sonnet으로, advisor를 Opus로 둔다. 근거와 대가는 아래 "advisor 결정" 절에 적는다.

**`.gitignore`**
- `.claude/` 전체 무시를 `/.claude/*`, `!/.claude/settings.json`, `*/**/.claude/`로 바꾼다. 볼트 링크(`skills`·`agents`), 복사본 `rules`, `settings.local.json`, 백업 파일은 계속 무시한다. `.claude/*`처럼 가운데에 `/`가 있는 패턴은 루트에만 걸리므로, 하위 디렉터리의 `.claude/`는 `*/**/.claude/`로 계속 무시한다. 같은 절 제목에 체크인 예외를 적는다.

**`scripts/link_claude.py`**
- docstring의 "`.claude`와 `.codex`는 `.gitignore` 대상"을 체크인 예외에 맞춰 고친다. 동작은 바꾸지 않는다.

**wiki**
- [LLM 지침 설계 근거](../wiki/agent-instructions.md)의 "에이전트 모델 배정과 스킬 선행 규칙" 절 배정 근거 문단은 advisor 도구 문서를 근거로 들지만, 실제로는 advisor를 켠 적이 없다. 그래서 지금 사실로 고친다. Claude Code에서는 `/advisor`·`advisorModel`·`--advisor`로 advisor를 켤 수 있고 서브에이전트가 이를 물려받는다([advisor 문서](https://code.claude.com/docs/en/advisor.md)). 여기에 이 PR의 결정을 더한다.
- 같은 문서에 "하네스 설정" 절을 두어 `.claude/settings.json`의 위치, 키마다 하는 일, 개인 예외(`.claude/settings.local.json`) 방법, 기존 개인 `.claude/settings.json`의 이전 방법을 적는다. 수치와 선택 근거는 이 spec에만 두고 wiki는 링크한다. "스킬·에이전트 정본의 위치" 절의 "`.claude/` 통째" 문구도 고친다. 지침 로딩과 모델 배정을 다루는 문서라 개발 절차 문서(`development-workflow.md`)보다 이 문서가 맞다.
- [차용한 스킬과 사용법](../wiki/borrowed-workflows.md)의 hook 스크립트 행은 `.claude/settings.json`을 "로컬, gitignore"라고 적는다. 이 PR 뒤에는 틀린 말이라서 고친다.
- [LLM 지침 설계 근거](../wiki/agent-instructions.md) 모델 배정 절의 orchestrator tier 상한 문장에 리뷰·게이트 예외를 더한다.

### 자동 compact 기준
공식 문서에서 확인한 사실(Claude Code 2.1.312, 2026-11-03):
- [`autoCompactWindow`](https://code.claude.com/docs/en/settings-reference.md#autocompactwindow)는 "문맥이 얼마나 차면 자동 compact할지"를 토큰 수로 정한다. 범위는 100000~1000000이고 모델의 context window로 잘린다. 어느 settings 파일에나 둘 수 있다. `--autocompact`가 한 세션 동안 이 키보다 우선하고, `CLAUDE_CODE_AUTO_COMPACT_WINDOW`는 그 둘보다 우선한다.
- [`CLAUDE_CODE_AUTO_COMPACT_WINDOW`](https://code.claude.com/docs/en/env-vars.md)는 같은 창을 env로 정한다. 단위는 토큰이고, 접미사 없는 숫자는 그대로 토큰 수다(`200`은 200토큰). [model-config](https://code.claude.com/docs/en/model-config.md#set-the-auto-compact-window)는 이 변수를 "스크립트와 클라우드 환경"용으로 안내한다.
- [`CLAUDE_AUTOCOMPACT_PCT_OVERRIDE`](https://code.claude.com/docs/en/env-vars.md)는 auto-compact 창의 몇 퍼센트(1~100)에서 compact할지를 정한다. 기본 퍼센트보다 큰 값은 무시된다. 그 기본 퍼센트는 문서에 없다.
- Opus 4.7 이후처럼 1M window를 쓰는 모델은 기본적으로 약 967K에서 compact한다. 사용자 설정 `modelSettings`가 `claude-opus-5-5`와 `claude-sonnet-5-5`에 `autoCompactWindow: 400000`을 두고 있어서, 지금 세션은 400k에서 compact한다.
- [compact 뒤에 남는 것](https://code.claude.com/docs/en/context-window.md#what-survives-compaction): 루트 CLAUDE.md(`@AGENTS.md`)와 `paths:` 없는 rules는 디스크에서 다시 싣는다. 호출한 스킬 본문은 스킬당 5,000토큰·전체 25,000토큰까지, 최근 파일 일부도 다시 싣는다. 대화 중의 결정과 `paths:` rules(`.kt` rules)는 요약에 흡수된다.
- 세션 중 `/autocompact`는 user `modelSettings`에 저장되며, 더 높은 scope가 창을 정하면 저장만 되고 세션에는 그 scope의 창이 남는다. 그래서 이 저장소에서 `/autocompact`로는 기준을 바꿀 수 없다.
- 서브에이전트는 세션의 compact 창을 물려받고, frontmatter `autoCompactWindow`로 더 낮출 수만 있다([sub-agents](https://code.claude.com/docs/en/sub-agents.md#subagent-auto-compact-window)). 300k를 넘는 서브에이전트는 드물어 영향이 작다.
- `claudeMdExcludes` 배열은 settings 계층 사이에서 합쳐진다([memory](https://code.claude.com/docs/en/memory.md#exclude-specific-claude-md-files)). 그래서 local 설정으로 project의 제외를 되돌릴 수 없다.

선택:
- **env 대신 settings 키를 쓴다.** `CLAUDE_CODE_AUTO_COMPACT_WINDOW`는 `--autocompact`보다 우선하므로, 체크인하면 기여자가 한 세션만 기준을 바꿀 길이 막힌다. `autoCompactWindow`는 `--autocompact`와 `.claude/settings.local.json`으로 덮을 수 있다. `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE`는 기본 퍼센트가 문서에 없어서 값을 토큰으로 계산할 수 없으므로 쓰지 않는다.
- **값은 300000이다.** 세 세션의 메인 Opus 호출별 문맥(input+cache read+cache creation)으로 시뮬레이션했다. compact 뒤 문맥은 첫 호출 문맥에 요약 15k를 더한 값으로 두고, 이후 증가분은 원래대로 더했다. compact 호출 한 번은 그 시점 문맥을 한 번 더 읽는 것으로 셌다. 요약 15k는 가정이다. 재주입(스킬 본문·최근 파일)을 넣은 보수 가정(첫 문맥 + 65k)도 함께 냈다. compact가 잃은 맥락을 다시 읽는 비용은 넣지 않았다. 수치는 입력 토큰 수 기준이며 요금 기준이 아니다. compact는 cache read(낮은 단가)를 줄이는 대신 요약 출력과 새 prefix의 cache 쓰기를 더한다.

| 세션(호출 수, 원래 누적) | 300k 첫 도달 호출 | 200k | 250k | 300k | 400k(지금) |
|---|---|---|---|---|---|
| order-status-polling(123, 27.1M) | 57번째 | 2회, -70%(보수 -48%) | 1회, -52%(-36%) | 1회, -53%(-40%) | 0회 |
| query-measurement(159, 40.2M) | 76번째 | 2회, -53%(보수 3회 -44%) | 1회, -48%(-34%) | 1회, -44%(-34%) | 0회 |
| wiki-stale-cleanup(58, 9.8M) | 도달 안 함 | 1회, -20%(-10%) | 0회 | 0회 | 0회 |

  300k에서는 긴 구현 세션이 한 번만 compact하면서 누적 입력을 34~53% 덜 읽고, 짧은 문서 세션은 compact하지 않는다. 200k는 절감이 조금 더 크지만 문서 세션까지 compact하고 긴 세션은 두 번 compact한다. 250k와 300k는 compact 횟수가 같고 절감도 비슷하다. 그래서 compact가 덜 일찍 오는 300k를 고른다. 두 긴 세션은 호출의 절반쯤(57/123, 76/159)에서 처음 300k에 닿는다. 그 시점이 spec 커밋보다 뒤인지는 세지 않았다. 세션 중반이라는 것만 확인했다.
- **대가.** compact 뒤에는 대화 중의 결정이 요약으로 줄고, `.kt` rules는 `.kt`를 다시 읽을 때까지 문맥에서 빠진다. 합의는 spec 파일이 디스크에 남기므로 되살릴 수 있다. spec 작성 전 grill 도중에 300k를 넘기는 세션이라면 대화만으로 남은 합의를 잃을 수 있다. 이런 세션은 `claude --autocompact 400k`로 시작하거나 `.claude/settings.local.json`에 `autoCompactWindow`를 둔다. user 설정이 없는 기여자에게는 기본값(약 967K)에서 300k로 바뀌는 것이라 변화가 더 크다.

### advisor 결정
사용자가 A/B 실측(2026-11-03, 오케스트레이터 세션이 수행) 뒤 Sonnet 메인 + Opus advisor 채택을 결정했다. 수치는 그 보고를 그대로 옮긴다.

| 항목 | A: Opus 단독 | B: Sonnet 메인 + `advisorModel` opus |
|---|---|---|
| 호출 | 50 | 39 |
| cache read | 980만 | 623만 |
| cache write | 23.7만 | 18.9만 |
| output | 4.9만 | 4.7만 |
| 시간 | 약 18분 | 약 17분 |
| 테스트 | 926건 통과 | 926건 통과 |
| `check_pr.py` | PASS | PASS |

- 대상: #117을 base 5e5721a3에서 같은 spec으로 두 세션이 다시 구현했다. B는 worktree `settings.local.json`으로 advisor를 켰다. transcript에 advisor 도구가 available로 실렸지만 B는 한 번도 부르지 않았다.
- 단가: smoke test 기준으로 Sonnet 단가는 Opus의 약 절반이고, 비용은 약 1/3로 추정한다. 공식 가격표로는 확인하지 않았다. smoke test에서 advisor 자문 1회는 Opus가 문맥 약 43k를 캐시 없이 읽었다.
- 품질: `spring-backend-specialist` 블라인드 리뷰에서 둘 다 P0·P1이 없었다. Opus 쪽이 약간 낫다. Sonnet 쪽에만 P2 세 건과 k6 burst submetric 누락이 있었다.
  - 빈도 limiter 예외를 `RuntimeException`만 잡아 자리가 샐 수 있다.
  - 비동기 재진입을 실제 asyncDispatch 없이 검증했다.
  - GET pattern 가드가 메서드 조건이 빈 매핑을 거르지 않는다.

공식 문서에서 확인한 사실(Claude Code 2.1.312):
- [`model`](https://code.claude.com/docs/en/settings-reference.md#model)은 어느 settings 파일에나 둘 수 있다. project 설정의 `model`은 user 설정과 `/model`로 저장한 기본값보다 우선하고 실행마다 다시 적용된다([model-config](https://code.claude.com/docs/en/model-config.md)). `--model`과 `ANTHROPIC_MODEL`은 이 키보다 우선한다.
- [`advisorModel`](https://code.claude.com/docs/en/settings-reference.md#advisormodel)은 어느 settings 파일에나 둘 수 있고 `opus` 같은 별칭을 받는다. Sonnet 5.5 메인에는 Opus 5 이후가 advisor로 받아들여진다. 서브에이전트는 설정된 advisor를 물려받아 자기 모델과의 조합을 따로 검사한다([advisor](https://code.claude.com/docs/en/advisor.md#choose-an-advisor-model)). `CLAUDE_CODE_DISABLE_ADVISOR_TOOL`이 켜져 있으면 이 키로 다시 켤 수 없다.
- 서브에이전트 모델은 호출 때 넘긴 `model`, 정의의 `model` frontmatter, `CLAUDE_CODE_SUBAGENT_MODEL` 순으로 정해진다([sub-agents](https://code.claude.com/docs/en/sub-agents.md#choose-a-model)). 메인 모델을 바꿔도 frontmatter가 `opus`인 에이전트는 호출 때 `model`을 넘기지 않으면 Opus로 돈다.

대가와 전제:
- Sonnet이 놓치는 P2급 결함은 PR 전 Opus 게이트가 잡는다고 전제한다. 이번 A/B에서 Sonnet 쪽 P2 세 건을 찾은 것은 명시 호출한 `spring-backend-specialist`였다. 그런데 이 리뷰어는 요청할 때만 쓰게 되어 있어서, 평소 PR 전에는 `resilientpos-multi-review`의 `reviewer-claude` 하나만 Opus로 돌았다. 사용자 결정(2026-11-03, "토큰을 아끼면서 효과를 최대로")에 따라 볼트 multi-review에 조건부 백엔드 리뷰를 넣었다.
  - `src/main`(마이그레이션·설정 포함)이나 `build.gradle.kts`가 바뀐 코드 리뷰(PR 전·Phase 4)에서만 `spring-backend-specialist`를 함께 띄운다. 설계 리뷰와 문서·테스트만 바뀐 PR에서는 띄우지 않는다.
  - develop 흐름은 구현을 PR 직전에 커밋하므로, 판정과 diff는 커밋하지 않은 작업 트리와 추적되지 않은 새 파일까지 base와 비교한다. base는 요청 값, 없으면 `git merge-base origin/main HEAD`이고, 둘 다 안 되면 "미실행: base 미정"으로 남긴다.
  - MainAgent는 diff를 `.review/backend.diff`로 저장만 하고 읽지 않는다. 테스트는 본문 없이 바뀐 경로만 덧붙인다. 리뷰어에게는 경로·base·spec과 `범위: diff`만 넘긴다. `model`은 넘기지 않아 frontmatter `opus`로 돈다.
  - `범위: diff`이면 리뷰어가 사실 수집(빌드 파일, 마이그레이션 대조표, wiki 전체)을 diff가 닿는 것으로 좁히고, P0~P2만 자세히 보고한다. DTO·엔티티만 바뀌어도 대응 마이그레이션은 Grep으로 찾아 대조한다.
  - 재리뷰에서는 앞선 리뷰가 P0·P1을 냈고 수정이 `src/main`·빌드에 닿을 때만 다시 띄운다.
  - 대가: 그런 PR마다 Opus 리뷰가 한 번 더 든다. 사실 수집을 좁혀서 diff 밖 스키마 제약 위반은 놓칠 수 있고, 테스트는 경로만 받으므로 리뷰어가 읽지 않은 테스트의 결함은 놓칠 수 있다. 이 장치가 P2를 찾는 비율은 아직 재지 않았다.
  - orchestrator 경유 게이트는 `code-reviewer`(frontmatter `opus`)다.
- 메인 세션의 판단(grill 심문, 설계, 리뷰 결과 채택)도 Sonnet이 한다. advisor는 Claude가 부를 때만 돌고, B에서는 한 번도 불리지 않았다.
- advisor를 부르면 그 호출은 Opus가 문맥을 캐시 없이 읽으므로 한 번에 수만 토큰이 든다.
- Opus를 쓰려는 세션은 `claude --model opus`로 시작하거나 `.claude/settings.local.json`에 `model`을 둔다. 세션 중 `/model`·`/advisor`는 user 설정에 저장되어 project 값에 막힌다. advisor만 끄려면 local 설정 `env`에 `CLAUDE_CODE_DISABLE_ADVISOR_TOOL=1`을 둔다.

한계:
- 재현은 1건이다. spec에 원래 결과 절이 들어 있어 실제보다 쉬운 과제였고, grill 같은 대화 단계는 측정하지 않았다.
- 볼트 수정(아래 제외 절)은 저장소 diff 밖이라 PR 리뷰로 대조할 수 없다. 볼트를 동기화하지 않은 머신에는 orchestrator 게이트 예외와 multi-review의 백엔드 리뷰가 없다.
- 서브에이전트 모델 순서는 2.1.251 이후, `CLAUDE_CODE_SUBAGENT_MODEL_FORCE`가 꺼져 있을 때의 동작이다. 그 이전 버전이나 FORCE를 켠 환경에서는 사용자 env `CLAUDE_CODE_SUBAGENT_MODEL=sonnet`이 게이트까지 Sonnet으로 바꾼다.
- advisor는 Anthropic API에서만 쓸 수 있고 feature flag 조회가 필요하다. `DISABLE_TELEMETRY`처럼 조회를 끄는 환경에서는 advisor 없는 Sonnet 단독이 된다. 서브에이전트도 advisor를 물려받으므로, 서브에이전트가 자문을 부르면 비용 추정 밖의 Opus 호출이 생긴다.

## 제외
- 사용자 전역 파일(`~/.claude/settings.json`, `~/.claude/rules`).
- Obsidian 볼트는 이 PR diff 밖이며, 사용자 요청으로 이 세션에서 아래를 고쳤다(`workflow-reviewer` 검토 후 반영). 볼트 스킬 여러 곳이 `.claude/settings.json`을 gitignore된 개인 파일로 안내한다: `resilientpos-setup`(`SKILL.md`, `references/statusline.md`·`git-hooks.md`·`develop-workflow.md`·`fleta.md`), `resilientpos-git`(`SKILL.md`, `references/cli-reference.md`), `resilientpos-workflow`(`SKILL.md`, `README.md`), `resilientpos-implement`(`SKILL.md`), `resilientpos-orchestrator`(`references/worktree-lifecycle.md`). 개인 hook·statusLine을 쓰는 대상을 `.claude/settings.local.json`으로 바꾸고, 공유 `.claude/settings.json`에는 hook·statusLine을 두지 않는다고 적었다.
  - orchestrator `SKILL.md §역할 기준 원칙`에 리뷰·게이트 예외를 넣고 `model-routing.md`·`delegation-patterns.md`·`autonomous-driving.md`·`merge-coordinator.md`와 볼트 `README.md`를 맞췄다. 게이트는 tier 상한을 받지 않고 최상위 일반 tier로 돈다. frontmatter가 그 tier인 에이전트는 `model`을 넘기지 않고, 그 밖은 명시하며, 외부 CLI 래퍼는 예외 밖이다. "자문 경로 비활성" 블록은 Claude Code advisor 도구와 별개라고 적었다.
  - multi-review에 위 조건부 백엔드 리뷰를 넣고 `spring-backend-specialist` 정의에 `범위: diff` 모드를 더했다. `workflow-reviewer`가 FAIL로 지적한 세 가지(커밋 전 구현을 보지 못함, base 결정 방법 없음, Kotlin 외 백엔드 변경 누락)와 경고(테스트 경로, DTO만 바뀐 경우의 마이그레이션 대조, 재리뷰 조건, develop-auto 재리뷰 담당, `.review/` gitignore 서술)를 반영했다.
- 메인 체크아웃에는 `.claude/settings.json`이 없어서 setup이 이미 쓴 개인 설정은 없다. 다른 checkout에 추적되지 않는 `.claude/settings.json`이 있으면 이 PR을 pull할 때 git이 덮어쓰기를 거절하므로, 먼저 `settings.local.json`으로 옮긴다.
- 저장소 Kotlin rules 네 개의 내용·`paths:` 축소.
- `orca-agent-view@inline`과 사용자가 판단할 플러그인들.
- 애플리케이션 코드 변경, 하네스 스크립트 동작 변경.

## 관련 문서
없음

## 변경 경로
- .claude/settings.json
- .gitignore
- scripts/link_claude.py
- docs/wiki/agent-instructions.md
- docs/wiki/borrowed-workflows.md

## 인수 조건
- `.claude/settings.json`이 git에 추적되고 `.claude/skills`·`.claude/agents`·`.claude/rules`·`.claude/settings.local.json`은 계속 무시되고, 하위 디렉터리의 `.claude/`(예: `src/main/.claude/x`)도 무시된다(`git check-ignore`).
- 같은 worktree, 같은 프롬프트의 headless 측정에서 `.kt` 읽기로 늘어나는 문맥이 설정 전보다 준다.
- 설정 뒤 세션에서 전역 `ecc/kotlin` 다섯 파일은 실리지 않고 저장소 Kotlin rules 네 개는 `.kt` 읽기 때 계속 실린다.
- 설정 뒤 init 이벤트에서 끈 다섯 플러그인이 빠지고 건드리지 않은 플러그인은 남는다.
- 이 저장소 세션의 메인 모델이 Sonnet 5.5가 되고 Opus 5.5 advisor가 첨부된다.
- 이 저장소 세션의 compact 기준이 300k가 된다. 사용자 `modelSettings`의 400k가 이기지 않는다.
- wiki가 지금 사실(advisor를 켜는 방법과 상속, 하네스 설정의 위치와 이유)과 맞는다.
- `python3 scripts/check_pr.py --base origin/main --worktree`가 통과하고, 제출 전 `--worktree` 없는 실행도 통과한다.

## 검증
측정 조건은 이 worktree(origin/main d891a4a8), Claude Code 2.1.312다. 메인 모델은 advisor 설정 전 Opus 5.5, 설정 후 Sonnet 5.5다. 각 프롬프트를 설정 전후 두 번씩 돌리고 `--output-format stream-json --verbose`의 호출별 usage(input+cache read+cache creation)와 init 이벤트를 비교한다.
- 시작: `claude -p "OK라고만 답해"`
- `.kt`: `claude -p "src/main/kotlin/com/resilientpos/foundation/DomainException.kt 를 읽고 OK라고만 답해" --allowedTools Read`
- 권한 우회 옵션은 쓰지 않는다.
- `scripts/link_claude.py`의 docstring을 고치므로 `python3 -m unittest discover -s scripts -p 'test_*.py'`를 돌린다. 애플리케이션 코드를 바꾸지 않으므로 `./gradlew test`는 돌리지 않는다.

## Wiki 영향
- [LLM 지침 설계 근거](../wiki/agent-instructions.md): 모델 배정 근거 문단을 고치고 하네스 설정 절을 더한다.
- [차용한 스킬과 사용법](../wiki/borrowed-workflows.md): `.claude/settings.json`이 체크인 파일이 되었음을 반영한다.
- [LLM 지침 설계 근거](../wiki/agent-instructions.md) 모델 배정 절: orchestrator tier 상한의 리뷰·게이트 예외와 `spring-backend-specialist`의 조건부 사용을 적는다.
- 요구사항 영향 없음: 애플리케이션 동작·도메인·API는 바뀌지 않는다. 에이전트 세션이 싣는 지침과 플러그인, compact 시점만 바뀐다.

## 분할 계획
하네스 설정 한 컨텍스트다. 작업 시작 시(2026-11-03) 열린 PR이 없고 브랜치는 origin/main(d891a4a8)과 같았다.

## TDD 검증 기록
설정·문서 작업이라 적용하지 않는다. 검증은 headless 실측으로 한다.

## 결과

### 설정 전 측정(2026-11-03)
| 측정 | 1회 | 2회 |
|---|---|---|
| 시작 문맥(첫 호출) | 56,182 | 56,182 |
| `.kt` 프롬프트 첫 호출 | 55,480 | 55,480 |
| `.kt` 읽은 뒤 호출 | 92,669 | 91,503 |
| `.kt` 프롬프트 modelUsage 합계 | 148,149 | 146,983 |

init 이벤트의 플러그인에 `claw-hwp`·`kordoc`이 있고, 스킬에 `claw-hwp:hwp`·`kordoc:kordoc`이 있다. `taste-skill`·`ui-ux-pro-max`·`impeccable`은 이미 빠져 있다. 메인 체크아웃의 `.claude/settings.local.json`(worktree도 이 파일을 쓴다)이 이 셋을 이미 끄고 있기 때문이다. worktree에서는 메인 체크아웃 루트의 local 파일을 쓴다([settings](https://code.claude.com/docs/en/settings.md#where-claude-code-keeps-the-local-file-in-a-git-repository)). 그래서 이 머신에서 이 셋을 project 설정으로 끄는 효과는 없고, 이 파일이 없는 머신과 기여자에게만 효과가 있다.

### 설정 후 측정(2026-11-03)
| 측정 | 설정 전(1회/2회) | 설정 후(1회/2회) | 차이 |
|---|---|---|---|
| 시작 문맥(첫 호출) | 56,182 / 56,182 | 55,945 / 55,945 | -237 |
| `.kt` 프롬프트 첫 호출 | 55,480 / 55,480 | 55,980 / 55,980 | +500 |
| `.kt` 읽은 뒤 호출 | 92,669 / 91,503 | 86,587 / 86,587 | -4.9k~-6.1k |
| `.kt` 읽기로 는 문맥(둘째 호출 - 첫 호출) | 37,189 / 36,023 | 30,607 / 30,607 | -5.4k~-6.6k |
| `.kt` 프롬프트 modelUsage 합계 | 148,149 / 146,983 | 142,567 / 142,567 | -4.4k~-5.6k |

- 시작 문맥 -237은 같은 측정의 `.kt` 첫 호출 +500(원인 미확인)보다 작아서 끈 플러그인의 효과로 판정하지 않는다. 플러그인 효과는 init 이벤트로 판정한다. `.kt` 효과는 같은 실행 안의 차이(둘째 호출 - 첫 호출)로 본다.
- init 이벤트: 설정 뒤 플러그인 목록에서 `claw-hwp`·`kordoc`이 빠지고 나머지 16개는 그대로다. 스킬 목록에 `claw-hwp:hwp`·`kordoc:kordoc`이 없다.
- 실린 rules: `.kt`를 읽힌 뒤 문맥에 실린 파일 경로를 모델에게 나열시켰다. 설정이 없으면 `~/.claude/rules/ecc/kotlin/` 다섯 파일과 저장소 Kotlin rules가 실린다. 설정이 있으면 `ecc/kotlin`은 없고, 저장소 `engineering-principles`·`implementation-principles`·`effective-java-for-kotlin`·`kotlin-idioms-and-design-patterns`·`domain-modeling-kotlin`은 그대로 실린다. 전역 `ecc/common`도 그대로다.
- compact 기준: `claude -p "/context"`의 분모가 설정이 없으면 `400k`, 있으면 `300k`다. project의 최상위 `autoCompactWindow`가 사용자 `modelSettings`의 모델별 값을 이긴다.
- 다른 머신에 없는 키: `--settings`로 없는 플러그인 키(`nonexistent-plugin@nowhere: false`)와 없는 경로의 `claudeMdExcludes`를 주어도 경고 없이 정상 응답했다.
- `.gitignore`: `git check-ignore`로 `.claude/skills`·`.claude/agents`·`.claude/rules/*`·`.claude/settings.local.json`·`.claude/settings.json.bak-*`가 계속 무시되고 `.claude/settings.json`만 추적됨을 확인했다. 첫 구현(`.claude/*`)은 `src/main/.claude/x`를 무시하지 못했다(리뷰 지적). `*/**/.claude/`를 더한 뒤 무시됨을 확인했다.

### advisor 설정 후 측정(2026-11-03)
- 같은 두 프롬프트에서 `modelUsage`의 모델이 `claude-sonnet-5-5`이고 init 이벤트의 `model`도 `claude-sonnet-5-5`다. user 설정의 `model: opus`를 project 설정이 덮었다.
- 그 세션의 transcript에 `{"type":"advisor_tool","available":true,"model":"claude-opus-5-5","toolChange":"add"}` 첨부와 advisor 도구 안내가 실렸다. 두 프롬프트 모두 advisor를 부르지 않았다.
- 시작 문맥은 57,333이다(Opus 설정 후 55,945). advisor 도구 안내 몫으로 보며 따로 분리하지는 않았다. `.kt` 프롬프트는 57,368 → 87,975(+30,607)로 `ecc/kotlin` 제외 효과가 그대로다.
- Sonnet 메인에서 `claude -p "/context"`의 분모는 `300k`로, compact 기준이 그대로다.
- `--settings`로 `env`에 `CLAUDE_CODE_DISABLE_ADVISOR_TOOL=1`을 주면 transcript에 `advisor_tool` 첨부가 없다(기본 실행 1건, 끈 실행 0건).
