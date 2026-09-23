# PR spec: 현재 상태 문서를 볼트로 옮겨 병렬 PR 충돌을 없앤다

## 목적
`docs/wiki/current-state.md`는 거의 모든 PR이 한 줄씩 고친다. 테스트 수 한 줄만 해도 PR마다 바뀌어서, PR을 나란히 올리면 뒤에 머지되는 쪽이 매번 이 파일에서 충돌한다.

## 범위
파일을 Obsidian 볼트 `ResilientPOS/wiki/current-state.md`로 옮기고 저장소에서 지운다. `scripts/link_claude.py`가 스킬·에이전트처럼 이 파일을 같은 경로에 심볼릭 링크로 건다. 경로가 그대로라 저장소와 볼트의 참조를 고치지 않는다. 링크는 `.gitignore`에 둔다.

`link_claude.py`는 지금 디렉터리만 건다. 파일도 걸 수 있게 넓힌다.

계약: 저장소에서 파일이 사라진다. 클론이나 pull 뒤 `make bootstrap`을 돌려야 읽힌다. GitHub에서는 이 경로의 링크가 열리지 않는다.

복구: 해당 없음.

관측성: 해당 없음.

## 제외
- 참조 경로 변경. 링크가 같은 경로에 서므로 필요 없다.
- 문서 내용 변경. `main`의 것을 그대로 옮긴다.

## 변경 경로
- docs/wiki/current-state.md
- docs/wiki/README.md
- .gitignore
- scripts/link_claude.py
- scripts/test_link_claude.py
- docs/spec/2026-09-23-current-state-to-vault.md

## 인수 조건
1. `make bootstrap` 뒤 `docs/wiki/current-state.md`가 볼트 파일을 가리키는 링크다.
2. 저장소는 그 파일을 추적하지 않는다.
3. 기존 디렉터리 링크는 그대로 걸린다.

## 검증
- `python3 scripts/test_link_claude.py` — 통과.
- `python3 scripts/check_pr.py --base origin/main --worktree` — PASS.

## Wiki 영향
- [wiki 목차](../wiki/README.md) — 현재 상태가 볼트에 있다는 것을 한 줄 적는다.
- 요구사항 변화: 없다.

## 분할 계획
6개 파일. 한도 안이다.

## 결과
구현 전이다.
