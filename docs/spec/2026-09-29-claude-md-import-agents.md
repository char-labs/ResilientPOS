# PR spec: CLAUDE.md에서 AGENTS.md를 import해 지침 로딩을 보장한다

## 목적
Claude Code가 어느 버전·설정에서든 `AGENTS.md`를 프로젝트 지침으로 싣도록 저장소 루트에 `@AGENTS.md` 한 줄짜리 `CLAUDE.md`를 둔다.

배경(결정 근거):
- `AGENTS.md`의 개발 계약은 `CLAUDE.md`를 두지 않고 Claude Code 2.1.277부터의 `AGENTS.md` 폴백에 기댔다.
- 2.1.284 세션에서 `AGENTS.md`가 컨텍스트에 실리지 않았다. 모델이 "main 기준 새 작업은 orca worktree로 넘긴다" 규칙을 몰라 메인 checkout에서 새 작업을 시작했다.
- 폴백은 버전·`/config`의 Project instructions 설정에 따라 달라진다. 반면 `CLAUDE.md`의 `@path` import는 문서화된 기본 로딩 경로다.

## 범위
- 루트 `CLAUDE.md`를 새로 만든다. 내용은 `@AGENTS.md` 한 줄이다.
- `AGENTS.md` 개발 계약의 지침 정본 문장을 고친다. 정본은 `AGENTS.md` 하나이고, `CLAUDE.md`에는 `@AGENTS.md` import 한 줄만 두며 다른 내용을 적지 않는다.
- `docs/wiki/agent-instructions.md` "이 저장소에 적용한 판단"에서 폴백에 기대 import 스텁을 두지 않는다는 서술을 새 결정과 근거로 바꾼다.

## 제외
- 볼트 스킬(`resilientpos-git` 등)은 다른 세션이 고치므로 건드리지 않는다.
- `GEMINI.md`는 이미 `AGENTS.md`를 가리키므로 바꾸지 않는다.
- 과거 PR spec(`docs/spec/2026-*.md`)은 역사 기록이라 고치지 않는다.
- 애플리케이션 코드와 하네스는 건드리지 않는다.

## 관련 문서
없음

## 변경 경로
- CLAUDE.md
- AGENTS.md
- docs/wiki/agent-instructions.md

## 인수 조건
- 루트 `CLAUDE.md`의 내용이 `@AGENTS.md` 한 줄뿐이다.
- `AGENTS.md`와 `docs/wiki/agent-instructions.md`에 `CLAUDE.md`를 두지 않는다거나 `AGENTS.md` 폴백에 기댄다는 문장이 없다.
- `grep -rn "폴백\|2\.1\.277\|스텁을 두지" AGENTS.md docs/wiki`가 아무것도 찾지 않는다.

## 검증
```sh
cat CLAUDE.md
grep -rn "폴백\|2\.1\.277\|스텁을 두지" AGENTS.md docs/wiki
python3 scripts/check_pr.py --base origin/main --worktree
```
- 정상: `CLAUDE.md`가 `@AGENTS.md` 한 줄, grep 결과 없음, `check_pr.py` PASS.
- 실패 시나리오: 폴백 서술이 남으면 grep이 그 줄을 찾는다.

## Wiki 영향
- [LLM 지침 설계 근거](../wiki/agent-instructions.md): Claude Code 로딩 경로 결정을 폴백에서 `CLAUDE.md` import로 바꾸므로 본문을 고친다.

## 분할 계획
지침 로딩 경로 하나를 바꾸는 한 컨텍스트다. 작업 시작 시 origin/main은 e6e2c4a, 열린 PR은 없어 경로 겹침이 없다.

## TDD 검증 기록
문서·지침 파일만 바뀌는 작업이라 업무 동작 테스트가 없다. grep과 `check_pr.py`로 확인한다.

## 결과
개발 전 작성. 개발 후 실행 결과를 추가한다.

- 루트 `CLAUDE.md`를 `@AGENTS.md` 한 줄로 만들었다. `AGENTS.md` 개발 계약의 정본 문장을 "정본은 이 파일 하나, `CLAUDE.md`는 import 한 줄만"으로 고쳤다.
- `docs/wiki/agent-instructions.md`의 폴백 의존 서술을 import 결정과 2.1.284 사고 근거로 바꿨다. `.gitignore`는 루트 `CLAUDE.md`를 제외하지 않아 그대로 추적된다.
- grep 대상에서 `README.md`를 뺐다. `README.md:72`의 "폴백"은 Makefile 자동완성 설명이라 이번 결정과 무관하다.
- `grep -rn "폴백\|2\.1\.277\|스텁을 두지" AGENTS.md docs/wiki` 결과 없음. `check_pr.py --base origin/main --worktree` PASS.
- 로딩 확인(2.1.284, `claude -p`, 파일 도구 차단, 컨텍스트만으로 답하게 함): 이 worktree에서는 `CLAUDE.md`와 `AGENTS.md`가 함께 실렸고, `AGENTS.md`의 orca worktree 명령을 원문 그대로 답했다. 같은 지침이 두 번 실리지 않았다.
- 대조 실행: `AGENTS.md`만 둔 임시 디렉터리에서도 같은 버전의 폴백으로 `AGENTS.md`가 실렸다. 사고 세션에서 실리지 않은 원인(대화형 세션, 시작 디렉터리, `/config` 설정 등)은 재현하지 못했다. 이 변경은 폴백 동작 여부와 상관없이 import로 싣는다.
