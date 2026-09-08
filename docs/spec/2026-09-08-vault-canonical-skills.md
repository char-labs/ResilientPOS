# PR spec: 스킬과 에이전트 정본을 Obsidian vault로 옮기고 부트스트랩으로 연결

## 목적
LLM 스킬과 에이전트 정본이 저장소에 있어 세 가지 문제가 겹친다. 지침 변경이 제품 PR diff를
오염시키고, 기기 간 공유가 git clone에 묶이며, Obsidian의 링크와 검색으로 읽고 편집할 수 없다.
정본을 Obsidian vault의 `ResilientPOS/`로 옮기고, 저장소의 `.claude/skills`와 `.claude/agents`를
부트스트랩 스크립트가 만드는 심볼릭 링크로 연결한다. 정본이 곧 Claude Code의 탐색 경로가
되므로 저장소에 중계용 stub을 두지 않는다. vault는 Obsidian Sync로 기기 간에 전달된다.

## 범위
- `.agents/skills/` 정본 3개와 `.claude/agents/` 정의 2개의 본문을 Obsidian vault의
  `ResilientPOS/skills/`와 `ResilientPOS/agents/`로 옮기고 저장소에서 제거한다.
- `scripts/link_claude.py`를 추가한다. 실행 시점에 홈 경로를 풀어 저장소의 `.claude/skills`,
  `.claude/agents`, `.codex/agents`를 vault로 심볼릭 링크한다. 볼트가 없으면 안내만 출력하고
  정상 종료한다. 볼트 경로는 환경 변수 `RESILIENTPOS_VAULT`로 재정의할 수 있다. 여러 번 실행해도
  결과가 같다.
- `Makefile`을 추가해 `make bootstrap`으로 링크를 만든다. clone 직후의 진입점을 한 곳으로 모으고,
  기존에 문서로만 있던 빌드·테스트·검사 명령도 같은 자리에 노출한다. 타깃마다 `## ` 주석으로
  설명을 달아 `make help`가 그 목록을 그대로 출력한다.
- `scripts/make-completion.zsh`를 추가한다. `## ` 주석이 달린 타깃만 자동완성하고 그 컨벤션이 없는
  프로젝트에서는 기본 동작으로 폴백한다. 사용자가 `~/.zshrc`에서 직접 source하는 선택 사항이다.
- README에 명령 표와 자동완성 안내를 추가한다.
- `.gitignore`에 `.agents/`, `.claude/`, `.codex/`를 넣어 세 경로를 통째로 추적에서 제외한다.
  링크는 로컬에만 존재하고 git에는 들어가지 않는다.
- `.codex/agents/*.toml`의 정본을 vault `ResilientPOS/codex/agents/`로 옮긴다. 저장소의
  `.codex/agents`는 그 디렉터리를 가리키는 링크가 된다.
- `AGENTS.md`와 wiki의 스킬 참조 경로는 `.claude/skills/...`로 유지한다. 링크를 통해 해소된다.
- 정본 안의 상대 링크 3개를 저장소 기준 경로 표기로 바꾼다. vault에서는 그 깊이가 해소되지 않고,
  마크다운 링크로 두면 잘못된 위치를 가리키기 때문이다.
- vault 레이아웃을 Claude Code 플러그인 규약과 호환되게 잡는다. 후속 PR에서 매니페스트 한 개만
  얹으면 파일 이동 없이 플러그인으로 전환할 수 있다.
- vault에 `ResilientPOS/README.md` 진입점을 두어 스킬과 에이전트 목록, 저장소 위치를 안내한다.

## 제외
플러그인 매니페스트 작성과 마켓플레이스 등록, 세션 시작 시 자동 설치, Codex 에이전트 재배선,
`docs/references`와 `docs/spec`과 `docs/wiki`의 이전, `scripts/check_pr.py`의 동작 변경,
git 이력 재작성, 애플리케이션 기능 변경.

## 변경 경로
- AGENTS.md
- Makefile
- README.md
- .gitignore
- .agents/**
- .claude/**
- .codex/**
- scripts/**
- docs/**

## 인수 조건
- git이 `.agents`, `.claude`, `.codex` 경로의 파일을 하나도 추적하지 않는다.
- `.agents`는 디스크에서도 사라진다. `.claude`와 `.codex`는 로컬 링크로만 존재한다.
- `make bootstrap`이 세 링크를 만들고, 저장소를 clone만 한 상태에서는 세 경로가 없다.
- `make help`가 정의된 모든 타깃과 설명을 출력하고, README의 명령 표와 내용이 일치한다.
- vault로 옮긴 SKILL.md 3개와 에이전트 정의 2개가 이동 전과 바이트 단위로 같다. 단, 상대 링크
  3개를 저장소 기준 표기로 바꾼 줄만 다르다.
- 에이전트가 따라가는 활성 참조에 `.agents/skills` 문자열이 남지 않는다. 대상은 `AGENTS.md`,
  `.codex/agents/*.toml`, `docs/wiki/**`이다. 과거 spec은 그 PR이 한 일의 기록이므로 고치지 않고,
  이번 spec 자신의 서술도 대상이 아니다.
- 커밋되는 파일 어디에도 사용자 홈 절대 경로가 들어가지 않는다. 부트스트랩 스크립트의 기본값도
  실행 시점에 홈을 푸는 형태로 둔다.
- `scripts/link_claude.py`를 두 번 실행해도 오류 없이 같은 결과가 되고, 볼트가 없으면 종료 코드 0으로
  안내만 출력한다.
- 링크 생성 후 `.claude/skills`의 스킬 3개, `.claude/agents`의 에이전트 2개, `.codex/agents`의
  toml 2개가 vault의 실제 파일로 해소된다.
- 사용자의 기존 전역 스킬과 에이전트를 건드리지 않는다.
- 스킬 이름과 description frontmatter가 이동 전후로 같아 도구의 탐색 결과가 바뀌지 않는다.
- 스킬 본문이 참조하는 `docs/wiki` 문서 3개가 저장소 루트 기준으로 실제 파일로 존재한다.

## 검증
- `python3 -m unittest discover -s scripts -p 'test_*.py'`로 하네스와 새 스크립트의 회귀를 확인한다.
  통과가 정상이고 실패하면 되돌린다.
- `python3 scripts/check_pr.py --base main --worktree`를 실행한다. 심볼릭 링크 오류와 범위 밖 변경
  오류가 없어야 한다. 링크를 gitignore에서 빼면 심볼릭 링크 오류가 나야 하며, 이를 실패 시나리오로
  한 번 확인한다.
- `python3 scripts/link_claude.py`를 두 번 연달아 실행해 멱등성을 확인하고, 볼트 경로를 없는 곳으로
  재정의해 종료 코드 0과 안내 출력을 확인한다.
- `git ls-files`에 `.claude/skills`와 `.claude/agents`와 `.agents`가 없음을 확인한다.
- `git grep -n '.agents/skills'` 결과가 `AGENTS.md`, `.codex/`, `docs/wiki/`에서 비어 있음을
  확인한다. 과거 spec과 이번 spec에만 남아 있어야 한다.
- 추적 대상 파일에 사용자 홈 디렉터리 절대 경로가 없는지 `git grep -n` 으로 확인한다. 이 spec
  자신을 제외한 결과가 비어야 정상이다.
- 링크를 통해 스킬 본문과 그 본문이 가리키는 `docs/wiki` 문서까지 실제로 열리는지 파일 존재로
  확인한다.
- 저장소에서 새 Claude 세션을 열어 스킬 3개와 에이전트 2개가 이동 전과 같은 이름과 description으로
  탐색되는지 확인한다. 심볼릭 링크 디렉터리를 탐색하지 못하면 이 방식이 실패한 것이므로 정본을
  경로 참조 방식으로 되돌린다.
- 서브에이전트에 vault 경로만 주고 TDD 스킬을 해석시켜 `docs/wiki/testing-strategy.md`까지 도달하는지
  확인한다.

## Wiki 영향
`docs/wiki/agent-instructions.md`에 정본 위치와 부트스트랩 실행 방법을 기록한다.
`docs/wiki/current-state.md`의 하네스 구성 설명을 vault와 링크 기준으로 갱신한다.
`docs/wiki/development-workflow.md`에 새 머신에서 부트스트랩을 먼저 실행한다는 절차를 더한다.
`docs/wiki/changes.md`에 이번 spec 링크와 요구사항 영향을 남긴다. 제품 요구사항은 바뀌지 않으며
개발 지침의 보관 위치와 연결 방식만 바뀐다.

## 분할 계획
예상 변경은 문서와 설정 위주로 18개 파일, 추가와 삭제 합계 400줄 안쪽이다. 경고 기준인 20개 파일
또는 600줄에 이르면 wiki 갱신을 후속 PR로 옮기고 이동과 부트스트랩만 이번 PR에 남긴다.
플러그인 전환은 처음부터 이번 범위 밖이며 별도 spec으로 시작한다.

## TDD 검증 기록
`scripts/link_claude.py`에 적용한다. 링크 생성, 멱등 재실행, 볼트 없음 처리, 환경 변수 재정의를
기존 unittest 하네스에 테스트로 먼저 추가하고 구현한다. 나머지 문서 이동은 업무 동작 변경이
아니므로 적용하지 않는다.

RED와 GREEN은 모두 `python3 -m unittest discover -s scripts -p 'test_link_claude.py'`로 실행했고,
회귀는 `python3 -m unittest discover -s scripts -p 'test_*.py'`로 확인했다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| test_links_every_configured_directory | 위 RED 명령, `ModuleNotFoundError: link_claude` | 위 GREEN 명령, ok | 위 회귀 명령, 22개 OK |
| test_links_cover_claude_and_codex | 링크 목록에 `.codex/agents` 없음 | ok | 22개 OK |
| test_running_twice_keeps_the_same_links | 같은 이유로 수집 실패 | ok | 22개 OK |
| test_skips_when_vault_is_missing | 같은 이유로 수집 실패 | ok | 22개 OK |
| test_skips_a_directory_the_vault_does_not_have | 같은 이유로 수집 실패 | ok | 22개 OK |
| test_environment_variable_overrides_the_default_vault | 같은 이유로 수집 실패 | ok | 22개 OK |
| test_refuses_to_replace_a_real_directory | 같은 이유로 수집 실패 | ok | 22개 OK |

설계가 두 번 바뀌면서 테스트도 두 번 다시 썼다. 각 회차 모두 테스트를 먼저 실패시키고 구현했다.

## 변경 이력
- 구현 중 `.agents/skills` 참조가 `docs/wiki` 5개 파일과 과거 spec 2개에도 있음을 확인했다. 과거
  spec은 그 PR이 한 일의 기록이므로 고치지 않기로 하고, 참조 제거 대상을 에이전트가 실제로
  따라가는 활성 경로로 좁혔다. 인수 조건과 검증을 그에 맞춰 수정했다.
- 사용자 요청으로 `.claude`와 `.codex`를 저장소 디스크에서도 제거하기로 했다. 스킬과 에이전트는
  사용자 레벨 `~/.claude`에서 vault를 가리킨다. 프로젝트 한정 적용을 잃고 모든 프로젝트에서
  로딩되지만, 저장소에 개발 도구 흔적을 남기지 않는 쪽을 택했다. Codex 전용 에이전트는 배선 없이
  vault에 보관만 한다.
- 사용자 레벨 등록은 프로젝트 한정 적용을 잃는다는 문제가 있어 철회했다. 대신 저장소의
  `.claude`와 `.codex`를 통째로 gitignore하고 `make bootstrap`이 링크를 재생성하는 방식으로 바꿨다.
  git에는 들어가지 않으면서 스킬이 이 저장소에서만 적용된다. 참고한 선례는 사용자가 제시한 다른
  저장소의 동일 구성이다.
- 최종적으로 파일 수가 하드 상한 25개에 도달했다. 더 줄이려면 Makefile과 자동완성, README 명령
  표를 후속 PR로 빼야 하는데 그러면 `make bootstrap`을 참조하는 문서를 함께 되돌려야 한다.
  사용자와 협의해 이번 PR을 이대로 마무리하고 분할은 별도로 검토하기로 했다.
- 분할 계획은 경고선 도달 시 wiki 갱신을 후속 PR로 옮긴다고 적었으나, 실제 wiki 변경의 대부분이
  끊어진 스킬 링크의 수정이라 미루면 문서가 깨진다. `docs/wiki/changes.md`도 하네스가 이번 PR에서
  요구한다. 미룰 수 있는 부분은 설명 추가 2개 파일뿐이라 실익이 없어 이번 PR에 유지했다. 파일 수
  경고는 남지만 하드 한도인 25개 파일과 800줄에는 이르지 않았다.

## 결과
개발 전 spec을 독립 커밋으로 작성했다. 구현 후 아래를 확인했다.

- `make harness`로 22개가 통과한다. 부트스트랩 신규 7개를 포함하며 기존 15개에 회귀가 없다.
- `python3 scripts/check_pr.py --base main --worktree`가 PASS다. 분할 경고 1건은 아래에 판단을 남겼다.
- 저장소가 `.agents`, `.claude`, `.codex` 세 경로의 파일을 하나도 추적하지 않는다. `.agents`는
  디스크에서도 사라졌고 나머지 둘은 링크로만 존재한다.
- `make bootstrap`이 링크 3개를 만들고 두 번 실행해도 결과가 같다. `make help`가 정의된 타깃 6개를
  설명과 함께 출력하며 README의 명령 표와 일치한다.
- 링크를 통해 스킬 3개, 에이전트 2개, Codex toml 2개가 모두 열린다.
- `AGENTS.md`, `.codex/`, `docs/wiki/`에 `.agents/skills` 참조가 남지 않았다. 과거 spec 2개는 기록으로 보존했다.
- 추적 대상 파일에 사용자 홈 절대 경로가 없다. 이 spec의 서술만 예외다.
- 정본 이동 전후 바이트 대조 결과, 의도한 4줄 외에는 차이가 없다. 상대 링크 3줄과 에이전트 참조 1줄이다.
- 부트스트랩을 두 번 실행해도 링크가 같고, 볼트가 없을 때 종료 코드 0으로 건너뛴다.
- 실제 Claude 세션이 심볼릭 링크를 통해 스킬 3개와 에이전트 2개를 모두 탐색했다. 이 방식의 유일한
  미검증 전제였고, 실패했다면 경로 참조 방식으로 되돌릴 예정이었다.
- 음성 대조로 `.gitignore`에서 링크 경로를 빼면 `심볼릭 링크는 지원하지 않습니다` 오류가 난다.
  검사를 통과하는 이유가 추적 제외임을 확인했다.
- 컨텍스트 연결 검증: 볼트의 SKILL.md 경로 하나만 제공한 읽기 전용 에이전트가 저장소 루트 기준으로
  `AGENTS.md`와 `docs/wiki/testing-strategy.md`를 열고 본문을 인용했다. 해소되지 않은 참조는 특정
  파일이 아닌 "해당 PR spec"뿐이며, 이는 작업 중인 PR에 따라 달라지는 서술이라 경로 실패가 아니다.

최종 PR 규모는 일반 25개 파일/667줄이다. 하드 상한 25개 파일과 정확히 같아 검사는 통과하지만
여유가 없다. 설계가 두 번 바뀌며 분할 계획의 예상치를 넘었고, 되돌리면 문서 정합이 깨지는 변경만
남아 이번 PR에 유지했다. 이 PR에는 더 이상 파일을 추가하지 않는다. Makefile과 자동완성, README
명령 표를 후속 PR로 분리하는 안은 별도로 검토한다.

플러그인 전환은 이번 범위에 넣지 않았다. 사전 조사에서 플러그인 매니페스트의 `skills` 항목이
플러그인 루트 밖을 가리킬 수 없음을 확인했고, 루트를 볼트에 두면 매니페스트가 숨김 경로라
동기화되지 않는다. 볼트 레이아웃은 플러그인 규약과 호환되게 두었으므로 후속 spec에서 파일을
옮기지 않고 다룰 수 있다.
