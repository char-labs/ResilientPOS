# LLM 지침 설계 근거

2026-09-07 공식 문서 조사. 지침을 수정할 때 읽는 참고 페이지이며 시작 시 일괄 로딩하지 않는다.

## 공식 문서에서 확인한 사항
- Codex는 사용자 전역과 프로젝트 경로의 지침을 조합한다. 같은 디렉터리에서는 `AGENTS.override.md`를 먼저 선택하고, 더 가까운 디렉터리의 지침이 앞선 지침보다 우선한다. 기본 합산 한도는 32 KiB다. [OpenAI: AGENTS.md](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
- Claude는 간결하고 구체적인 지침을 권장하며 CLAUDE.md를 200줄 미만으로 유지하는 것을 목표로 제시한다. `@path` import는 시작 시 본문을 로딩하므로 분리만으로 컨텍스트가 줄지 않는다. [Anthropic: Memory](https://code.claude.com/docs/en/memory)
- 매번 필요한 명령·프로젝트 규칙만 공통 지침에 남기고, 상세 API 설명·언어 기본 상식·긴 설명은 빼거나 링크로 연결한다. [Anthropic: Best practices](https://code.claude.com/docs/en/best-practices)

## 이 저장소에 적용한 판단
- 사용자 홈의 전역 설정과 저장소 루트 지침을 구분한다. 이번 변경은 저장소 내부에만 적용한다.
- 공통 정본은 루트 `AGENTS.md`다. Claude는 `CLAUDE.md`에서 `@AGENTS.md`로 import하고 Gemini는 같은 파일을 명시적으로 읽는다.
- 루트에는 사용자가 요청한 현재 스택, spec/wiki 계약, 작업별 읽기 조건과 검증 명령만 둔다. 상세 개발 절차와 조사 내용은 wiki에 둔다.
- 의존성 버전은 빌드 파일을 기준으로 관리한다. 계획만 있는 기술을 이미 사용 중인 기술로 적지 않는다.
- 전체 wiki를 import하거나 항상 읽게 하지 않는다. Markdown 링크는 자동 import가 아니므로 읽어야 하는 시점을 함께 적는다.
- 아직 작은 단일 프로젝트이므로 디렉터리별 지침 파일을 미리 늘리지 않는다. 실제 영역별 규칙이 생기면 Codex의 하위 AGENTS.md와 Claude의 하위 CLAUDE.md 또는 paths 규칙으로 범위를 좁힌다.
- 두 도구의 로딩/우선순위를 동일하다고 가정하지 않는다. 특히 Codex는 실행 디렉터리 기준 탐색을 확인하고, Claude 지침은 서로 충돌하지 않게 작성한다.
- spec 선작성·PR 크기·wiki 갱신은 사용자 요청에 따른 프로젝트 규칙이다. 공식 문서에서 강제하는 수치나 절차가 아니다.

## 스킬·에이전트 정본의 위치
정본은 Obsidian 볼트의 `ResilientPOS/skills`와 `ResilientPOS/agents`에 두고 저장소의 `.claude/skills`,
`.claude/agents`를 심볼릭 링크로 연결한다. 지침 변경이 제품 PR diff를 오염시키지 않고, 볼트 동기화로
기기 간에 공유되며, Obsidian에서 읽고 편집할 수 있다. 링크는 `scripts/link_claude.py`가 실행 시점에
홈 경로를 풀어 만들므로 커밋되는 파일에 기기별 절대 경로가 들어가지 않는다.
Codex는 이 방식을 쓰지 않고 `.codex/agents/*.toml`을 그대로 추적한다. TOML은 Codex가 파싱하는
설정이라 볼트로 옮길 수 없고, 본문은 링크된 `.claude/skills` 경로를 가리킨다.
로컬 검사는 추적하지 않는 경로를 검사 대상에서 제외하므로 링크가 심볼릭 링크 오류를 만들지 않는다.

## 유지와 확인
지침 변경 시 중복·충돌·폐기된 명령을 제거하고 관련 wiki를 갱신한다. 사용자 전역 지침을 프로젝트 규칙으로 복제하지 않는다.
새 Codex 세션에서 활성 지침을 요약하게 하고, Claude에서는 `/context`로 Memory files의 로딩을 확인할 수 있다. [OpenAI 검증 안내](https://learn.chatgpt.com/docs/agent-configuration/agents-md#verify-your-setup), [Claude 로딩 확인](https://code.claude.com/docs/en/memory#set-up-a-project-claudemd)
이번 검증은 파일/import 경로·링크·빌드 선언 일치와 로컬 하네스 검사다. 별도 Claude/Codex 세션의 실제 로딩 검증은 수행하지 않았다.
