# PR spec: 하네스 설정으로 세션 토큰을 줄인다

## 목적
이 저장소의 Claude Code 세션은 토큰 대부분을 Opus 메인 세션의 매 호출 문맥에 쓴다. 2026-11-02~03 세 세션(조회 Phase 2·4, 문서 정리)의 transcript를 보면 메인 Opus 340호출의 cache read가 7,550만으로 서브에이전트 전체의 약 5배다. 호출당 문맥은 중앙값 약 20만, 최대 36만이다. 첫 호출 문맥은 약 68k이고, `.kt`를 처음 읽을 때 한 번에 35~52k가 는다. 사용자 설정이 Opus 5.5의 compact 기준을 400k로 두고 있어서 세 세션 모두 한 번도 compact하지 않았다. hook 출력은 세션당 수천 토큰이라 원인이 아니다.

이 PR은 저장소에 체크인하는 `.claude/settings.json`으로 이 저장소의 세션만 바꾼다. 사용자가 직접 요청했고(2026-11-03) GitHub 이슈는 없다.

## 범위
요구사항은 사용자와 합의했다. `resilientpos-grill`은 아래 재진술을 확인하는 데만 썼다.

**`.claude/settings.json`(새 파일, 체크인)**
- `claudeMdExcludes`로 전역 `~/.claude/rules/ecc/kotlin/*.md` 다섯 파일을 뺀다. 이 다섯 파일은 `paths: **/*.kt`로 실리는 Android·KMP 지침(ViewModel, Room, Ktor, ProGuard, WebView, Turbine)이라 이 Spring 백엔드에 맞지 않고, 저장소 Kotlin rules 네 개(볼트 복사본)와 겹친다. 패턴은 `**/.claude/rules/ecc/kotlin/**`이다. 홈 경로를 적지 않으므로 다른 기여자 머신에도 그대로 맞고, 그 경로가 없는 머신에서는 아무것도 빼지 않는다. 저장소 `.claude/rules` 아래에는 `ecc/`가 없어서 저장소 rules는 빠지지 않는다. 저장소 rules 네 개는 그대로 둔다(2026-10-01 실측에서 줄일 곳이 없었다).
- `enabledPlugins`로 이 저장소 transcript에서 사용이 0회였던 플러그인을 끈다: `claw-hwp@claw-hwp`, `taste-skill@taste-skill`, `ui-ux-pro-max@ui-ux-pro-max-skill`, `impeccable@impeccable`, `kordoc@synced`. 공식 문서상 project 설정이 user 설정보다 우선하므로 user의 `true`를 덮는다. synced 플러그인도 끌 수 있다. 사용자 설정의 `data@synced: false`가 `claude plugin list`에 disabled로 나온다. `orca-agent-view@inline`은 Orca 연동이고 요청 목록에 없어서 건드리지 않는다. eli5·humanize-korean·superpowers·ponytail·warp·security-guidance·LSP·i-have-adhd도 건드리지 않는다.
- `autoCompactWindow`를 `300000`으로 둔다. 근거와 대가는 아래 "자동 compact 기준" 절에 적는다.
- advisor(Sonnet 메인 + Opus advisor)는 사용자가 A/B로 실측하고 있다. 결과가 채택이면 이 PR에 `advisorModel` 등을 더하고, 기각이면 제외 절에 실측 근거를 적는다. 결과가 오기 전에는 PR을 만들지 않는다.

**`.gitignore`**
- `.claude/` 전체 무시를 `.claude/*`와 `!.claude/settings.json`으로 바꾼다. 볼트 링크(`skills`·`agents`), 복사본 `rules`, `settings.local.json`, 백업 파일은 계속 무시한다.

**wiki**
- [LLM 지침 설계 근거](../wiki/agent-instructions.md)의 모델 배정 근거 문단(60행)은 advisor 도구 문서를 근거로 들지만, 실제로는 advisor를 켠 적이 없다. 그래서 지금 사실로 고친다. Claude Code에서는 `/advisor`·`advisorModel`·`--advisor`로 advisor를 켤 수 있고 서브에이전트가 이를 물려받는다([advisor 문서](https://code.claude.com/docs/en/advisor.md)). 여기에 이 PR의 결정을 더한다.
- 같은 문서에 "하네스 설정" 절을 두어 `.claude/settings.json`의 위치, 키마다의 이유, 개인 예외(`.claude/settings.local.json`) 방법을 적는다. 지침 로딩과 모델 배정을 다루는 문서라 개발 절차 문서(`development-workflow.md`)보다 이 문서가 맞다.
- [차용한 스킬과 사용법](../wiki/borrowed-workflows.md) 61행은 `.claude/settings.json`을 "로컬, gitignore"라고 적는다. 이 PR 뒤에는 틀린 말이라서 고친다.

### 자동 compact 기준
공식 문서에서 확인한 사실(Claude Code 2.1.312, 2026-11-03):
- [`autoCompactWindow`](https://code.claude.com/docs/en/settings-reference.md#autocompactwindow)는 "문맥이 얼마나 차면 자동 compact할지"를 토큰 수로 정한다. 범위는 100000~1000000이고 모델의 context window로 잘린다. 어느 settings 파일에나 둘 수 있다. `--autocompact`가 한 세션 동안 이 키보다 우선하고, `CLAUDE_CODE_AUTO_COMPACT_WINDOW`는 그 둘보다 우선한다.
- [`CLAUDE_CODE_AUTO_COMPACT_WINDOW`](https://code.claude.com/docs/en/env-vars.md)는 같은 창을 env로 정한다. 단위는 토큰이고, 접미사 없는 숫자는 그대로 토큰 수다(`200`은 200토큰). [model-config](https://code.claude.com/docs/en/model-config.md#set-the-auto-compact-window)는 이 변수를 "스크립트와 클라우드 환경"용으로 안내한다.
- [`CLAUDE_AUTOCOMPACT_PCT_OVERRIDE`](https://code.claude.com/docs/en/env-vars.md)는 auto-compact 창의 몇 퍼센트(1~100)에서 compact할지를 정한다. 기본 퍼센트보다 큰 값은 무시된다. 그 기본 퍼센트는 문서에 없다.
- Opus 4.7 이후처럼 1M window를 쓰는 모델은 기본적으로 약 967K에서 compact한다. 사용자 설정 `modelSettings`가 `claude-opus-5-5`와 `claude-sonnet-5-5`에 `autoCompactWindow: 400000`을 두고 있어서, 지금 세션은 400k에서 compact한다.
- [compact 뒤에 남는 것](https://code.claude.com/docs/en/context-window.md#what-survives-compaction): 루트 CLAUDE.md(`@AGENTS.md`)와 `paths:` 없는 rules는 디스크에서 다시 싣는다. 호출한 스킬 본문은 스킬당 5,000토큰·전체 25,000토큰까지 다시 싣는다. 대화 중의 결정과 `paths:` rules(`.kt` rules)는 요약에 흡수된다.

선택:
- **env 대신 settings 키를 쓴다.** `CLAUDE_CODE_AUTO_COMPACT_WINDOW`는 `--autocompact`보다 우선하므로, 체크인하면 기여자가 한 세션만 기준을 바꿀 길이 막힌다. `autoCompactWindow`는 `--autocompact`와 `.claude/settings.local.json`으로 덮을 수 있다. `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE`는 기본 퍼센트가 문서에 없어서 값을 토큰으로 계산할 수 없으므로 쓰지 않는다.
- **값은 300000이다.** 세 세션의 메인 Opus 호출별 문맥(input+cache read+cache creation)으로 시뮬레이션했다. compact 뒤 문맥은 첫 호출 문맥에 요약 15k를 더한 값으로 두고, 이후 증가분은 원래대로 더했다. compact 호출 한 번은 그 시점 문맥을 한 번 더 읽는 것으로 셌다. compact가 잃은 맥락을 다시 읽는 비용은 넣지 않았으므로 절감률은 상한이다.

| 세션(호출 수, 원래 누적) | 200k | 250k | 300k | 400k(지금) |
|---|---|---|---|---|
| order-status-polling(123, 27.1M) | 2회, -70% | 1회, -52% | 1회, -53% | 0회 |
| query-measurement(159, 40.2M) | 2회, -53% | 1회, -48% | 1회, -44% | 0회 |
| wiki-stale-cleanup(58, 9.8M) | 1회, -20% | 0회 | 0회 | 0회 |

  300k에서는 긴 구현 세션이 한 번만 compact하면서 누적 입력의 절반 가까이를 덜 읽고, 짧은 문서 세션은 compact하지 않는다. 200k는 절감이 조금 더 크지만 문서 세션까지 compact하고 긴 세션은 두 번 compact한다. 250k와 300k는 compact 횟수가 같고 절감도 비슷하다. 그래서 grill·spec 작성이 끝난 뒤 구현 중에만 compact가 오도록 더 큰 300k를 고른다. 세 세션 모두 첫 문맥이 68k 근처이고 grill·spec 합의는 세션 앞부분에서 끝난다.
- **대가.** compact 뒤에는 대화 중의 결정이 요약으로 줄고, `.kt` rules는 `.kt`를 다시 읽을 때까지 문맥에서 빠진다. 합의는 spec 파일이 디스크에 남기므로 되살릴 수 있다. spec 작성 전 grill 도중에 300k를 넘기는 세션이라면 대화만으로 남은 합의를 잃을 수 있다. 이런 세션은 `claude --autocompact 400k`로 시작하거나 `.claude/settings.local.json`에 `autoCompactWindow`를 둔다.

## 제외
- 사용자 전역 파일(`~/.claude/settings.json`, `~/.claude/rules`)과 Obsidian 볼트. 볼트 `resilientpos-setup` 스킬은 `.claude/settings.json`을 gitignore된 개인 파일로 보고 hook·statusLine을 쓴다. 이 PR 뒤에는 그 개인 설정을 `.claude/settings.local.json`에 써야 하며, 사용자가 볼트에서 고친다. 메인 체크아웃에는 `.claude/settings.json`이 없어서 setup이 이미 쓴 개인 설정은 없다.
- 저장소 `.claude/rules` 네 개의 내용·`paths:` 축소.
- `orca-agent-view@inline`과 사용자가 판단할 플러그인들.
- 애플리케이션 코드·하네스 스크립트 변경.

## 관련 문서
없음

## 변경 경로
- .claude/settings.json
- .gitignore
- docs/wiki/agent-instructions.md
- docs/wiki/borrowed-workflows.md

## 인수 조건
- `.claude/settings.json`이 git에 추적되고 `.claude/skills`·`.claude/agents`·`.claude/rules`·`.claude/settings.local.json`은 계속 무시된다(`git check-ignore`).
- 같은 worktree, 같은 프롬프트의 headless 측정에서 `.kt` 읽기 뒤 문맥이 설정 전보다 줄고, 시작 문맥이 끈 플러그인 몫만큼 준다.
- 설정 뒤 세션에서 전역 `ecc/kotlin` 다섯 파일은 실리지 않고 저장소 rules 네 개는 `.kt` 읽기 때 계속 실린다.
- 설정 뒤 init 이벤트에서 끈 다섯 플러그인이 빠지고 건드리지 않은 플러그인은 남는다.
- 이 저장소 세션의 compact 기준이 300k가 된다. 사용자 `modelSettings`의 400k가 이기지 않는다.
- wiki가 지금 사실(advisor를 켜는 방법과 상속, 하네스 설정의 위치와 이유)과 맞는다.
- `python3 scripts/check_pr.py --base origin/main --worktree`가 통과하고, 제출 전 `--worktree` 없는 실행도 통과한다.

## 검증
측정 조건은 이 worktree(origin/main d891a4a8), Claude Code 2.1.312, Opus 5.5 기본 모델이다. 각 프롬프트를 설정 전후 두 번씩 돌리고 `--output-format stream-json --verbose`의 호출별 usage(input+cache read+cache creation)와 init 이벤트를 비교한다.
- 시작: `claude -p "OK라고만 답해"`
- `.kt`: `claude -p "src/main/kotlin/com/resilientpos/foundation/DomainException.kt 를 읽고 OK라고만 답해" --allowedTools Read`
- 권한 우회 옵션은 쓰지 않는다.
- 문서만 바뀌고 하네스 스크립트·애플리케이션 코드를 바꾸지 않으므로 `./gradlew test`와 하네스 unittest는 돌리지 않는다.

## Wiki 영향
- [LLM 지침 설계 근거](../wiki/agent-instructions.md): 모델 배정 근거 문단을 고치고 하네스 설정 절을 더한다.
- [차용한 스킬과 사용법](../wiki/borrowed-workflows.md): `.claude/settings.json`이 체크인 파일이 되었음을 반영한다.
- 요구사항 영향 없음: 애플리케이션 동작·도메인·API는 바뀌지 않는다. 에이전트 세션이 싣는 지침과 플러그인, compact 시점만 바뀐다.

## 분할 계획
하네스 설정 한 컨텍스트다. 작업 시작 시(2026-11-03) 열린 PR이 없고 브랜치는 origin/main(d891a4a8)과 같았다.

## TDD 검증 기록
설정·문서 작업이라 적용하지 않는다. 검증은 headless 실측으로 한다.

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.

### 설정 전 측정(2026-11-03)
| 측정 | 1회 | 2회 |
|---|---|---|
| 시작 문맥(첫 호출) | 56,182 | 56,182 |
| `.kt` 프롬프트 첫 호출 | 55,480 | 55,480 |
| `.kt` 읽은 뒤 호출 | 92,669 | 91,503 |
| `.kt` 프롬프트 modelUsage 합계 | 148,149 | 146,983 |

init 이벤트의 플러그인에 `claw-hwp`·`kordoc`이 있고, 스킬에 `claw-hwp:hwp`·`kordoc:kordoc`이 있다. `taste-skill`·`ui-ux-pro-max`·`impeccable`은 이미 빠져 있다. 메인 체크아웃의 `.claude/settings.local.json`(worktree도 이 파일을 쓴다)이 이 셋을 이미 끄고 있기 때문이다. 그래서 이 머신에서 이 셋을 project 설정으로 끄는 효과는 없고, 이 파일이 없는 머신과 기여자에게만 효과가 있다.
