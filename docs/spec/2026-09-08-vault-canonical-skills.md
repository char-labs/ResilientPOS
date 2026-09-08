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
- `scripts/link_claude.py`를 추가한다. 실행 시점에 홈 경로를 풀어 `.claude/skills`와
  `.claude/agents`를 vault로 심볼릭 링크한다. 볼트가 없으면 안내만 출력하고 정상 종료한다.
  볼트 경로는 환경 변수 `RESILIENTPOS_VAULT`로 재정의할 수 있다. 여러 번 실행해도 결과가 같다.
- `.gitignore`에 `.claude/skills`, `.claude/agents`, `.agents/`를 추가해 링크와 정본을 추적하지
  않는다. 하네스는 추적하지 않는 경로를 검사 대상에서 제외하므로 심볼릭 링크 오류가 나지 않는다.
- 저장소에 남는 `.codex/agents/*.toml`과 `AGENTS.md`의 본문 참조를 `.agents/skills/...`에서
  `.claude/skills/...`로 바꾼다. 심볼릭 링크를 통해 해소되므로 커밋되는 문서에 절대 경로를 남기지
  않는다.
- 정본 안의 상대 링크 3개를 저장소 기준 경로 표기로 바꾼다. vault에서는 그 깊이가 해소되지 않고,
  마크다운 링크로 두면 잘못된 위치를 가리키기 때문이다.
- vault 레이아웃을 Claude Code 플러그인 규약과 호환되게 잡는다. 후속 PR에서 매니페스트 한 개만
  얹으면 파일 이동 없이 플러그인으로 전환할 수 있다.
- vault에 `ResilientPOS/README.md` 진입점을 두어 스킬과 에이전트 목록, 저장소 위치를 안내한다.

## 제외
플러그인 매니페스트 작성과 마켓플레이스 등록, 세션 시작 시 자동 설치, `docs/references`와
`docs/spec`과 `docs/wiki`의 이전, `scripts/check_pr.py`의 동작 변경, git 이력 재작성,
애플리케이션 기능 변경, 원격 PR 생성 및 병합.

## 변경 경로
- AGENTS.md
- .gitignore
- .agents/**
- .claude/**
- .codex/**
- scripts/**
- docs/**

## 인수 조건
- 저장소가 `.agents/`를 추적하지 않고, `.claude/skills`와 `.claude/agents`도 추적하지 않는다.
- vault로 옮긴 SKILL.md 3개와 에이전트 정의 2개가 이동 전과 바이트 단위로 같다. 단, 상대 링크
  3개를 저장소 기준 표기로 바꾼 줄만 다르다.
- 저장소의 추적 대상 파일 어디에도 `.agents/skills` 문자열이 남지 않는다.
- 커밋되는 파일 어디에도 사용자 홈 절대 경로가 들어가지 않는다. 부트스트랩 스크립트의 기본값도
  실행 시점에 홈을 푸는 형태로 둔다.
- `scripts/link_claude.py`를 두 번 실행해도 오류 없이 같은 결과가 되고, 볼트가 없으면 종료 코드 0으로
  안내만 출력한다.
- 링크 생성 후 `.claude/skills`와 `.claude/agents`가 vault의 실제 디렉터리로 해소된다.
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
- `grep -rn '.agents/skills' -- .` 결과가 추적 대상 파일에서 비어 있음을 확인한다.
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

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 볼트가 있으면 두 링크를 만든다 | 미실행 | 미실행 | 미실행 |
| 두 번 실행해도 결과가 같다 | 미실행 | 미실행 | 미실행 |
| 볼트가 없으면 종료 코드 0으로 건너뛴다 | 미실행 | 미실행 | 미실행 |
| 환경 변수로 볼트 경로를 재정의한다 | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성. 구현 후 검증 결과와 변경 이유를 추가한다.
