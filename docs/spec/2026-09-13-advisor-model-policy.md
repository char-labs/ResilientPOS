# PR spec: 어드바이저 도구 문서 기준으로 에이전트 모델 배정 근거 갱신

## 목적
Anthropic 어드바이저 도구 문서(platform.claude.com, agents-and-tools/tool-use/advisor-tool)는 "값싼 실행자 모델 + 상위
어드바이저 모델" 조합이 상위 모델 단독과 비슷한 품질을 같거나 낮은 비용으로 내고, 실행자 역량이 어드바이저에 가까울수록
이점이 줄어든다고 측정했다. 볼트 오케스트레이터는 구현 앞뒤에 `opus` 게이트(설계 검증·코드 리뷰·QA)를 두므로 그 게이트가
어드바이저 역할이고, 구현 실행자 `tdd-driver`를 `opus`로 두는 것은 이중 지출이다. 사용자 결정으로 `tdd-driver`를 `sonnet`으로
내리고 자문 경로를 문서 원형(단발 자문 허용, 권고 길이 상한, 메인 tier 조건부 시작/종료 자문)에 맞췄다. 저장소 wiki의 모델
배정표와 근거를 볼트 정본과 일치시킨다.

## 범위
- `docs/wiki/agent-instructions.md` 모델 배정 표에서 `tdd-driver`를 `sonnet` 행으로 옮기고, 배정 근거로 어드바이저 문서의
  측정(실행자/어드바이저 구조, 이점 감소 조건)과 "게이트가 어드바이저 역할"이라는 해석을 한 문단 추가한다. 게이트 없는 단독
  호출이면 dispatch 시 `opus`로 올린다는 예외도 적는다.
- `docs/wiki/borrowed-workflows.md` 결정 절의 모델 문장을 같은 기준으로 고친다.
- `docs/wiki/changes.md`에 이번 spec 링크와 요구사항 영향을 남긴다.

## 제외
볼트 파일 자체(정본은 이미 갱신됨), `.claude/agents` 링크 내용, 에이전트 정의 형식 변경, 다른 에이전트의 모델 재배정,
`check_pr.py`·`link_claude.py` 변경, 애플리케이션 코드.

## 변경 경로
- docs/wiki/agent-instructions.md
- docs/wiki/borrowed-workflows.md
- docs/wiki/changes.md
- docs/spec/2026-09-13-advisor-model-policy.md

## 인수 조건
- `docs/wiki/agent-instructions.md` 표의 각 에이전트 모델이 볼트 `agents/*.md`의 `model:`과 이름 단위로 일치한다(`tdd-driver`는 `sonnet`).
- 근거 문단이 어드바이저 문서를 링크하고, 이점 감소 조건과 단독 호출 예외를 담는다.
- `borrowed-workflows.md`에 "TDD 구현 = opus"라는 옛 문장이 남지 않는다.
- `AGENTS.md`는 변경하지 않는다(모델 배정은 wiki가 정본).

## 검증
- 볼트 `agents/*.md`의 `name:`·`model:`을 뽑아 wiki 표와 대조하는 스크립트를 실행한다. 불일치 0이어야 한다.
- `python3 scripts/check_pr.py --base main --worktree`(개발 중)와 `--base main`(제출 전)이 통과한다.
- `python3 -m unittest discover -s scripts -p 'test_*.py'`가 통과한다(문서 PR, 회귀 확인).
- 변경한 wiki의 상대 링크가 모두 해소되는지 확인한다.

## Wiki 영향
`docs/wiki/agent-instructions.md`(모델 배정표·근거), `docs/wiki/borrowed-workflows.md`(결정 절), `docs/wiki/changes.md`(기록).
제품 요구사항은 바뀌지 않는다. 개발 에이전트의 모델 배정 기준과 그 근거가 바뀐다.

## 분할 계획
예상 변경은 4개 파일, 추가와 삭제 합계 60줄 안쪽이다. 경고선에 이를 여지가 없다.

## TDD 검증 기록
문서 전용 작업이므로 적용하지 않는다. 검증은 이름·모델 대조 스크립트와 `check_pr.py`로 대신한다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 해당 없음(문서 전용) | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
