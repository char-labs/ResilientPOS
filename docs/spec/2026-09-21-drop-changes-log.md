# PR spec: 정본이 따로 있는 사본 기록 두 곳을 없앤다

## 목적
같은 사실을 두 곳에 적는 규칙이 두 개 있고 둘 다 비용만 남기고 있다.

`docs/wiki/changes.md`는 매 PR이 파일 끝에 항목을 덧붙이는 구조라, PR을 병렬로 진행하면 거의 매번 이 파일 한
곳에서 충돌한다. 실제로 PR 번호 23은 개발 도중 이 파일 한 곳에서만 충돌해 rebase를 했다. 그런데 이 파일이 담는
내용(spec 링크, 요구사항 영향, 결정 근거)은 이미 각 PR의 spec 안에 같은 말로 들어 있다. 사본을 유지하려고 충돌을
겪고 있다.

AGENTS.md의 "현재 기술 스택" 요약도 같은 구조다. 내용이 `build.gradle.kts`를 한 번 읽으면 그대로 나오는 사실인데
매 세션 로드되는 문서가 그 사본을 들고 있다. 사본이라 어긋난다. 바로 앞 PR인 번호 25가 이 요약에서 "H2 드라이버"와
"Spring Modulith는 아직 의존성에 없다"는 틀린 문장 두 개를 고쳤는데, 그 PR이 머지되는 사이에 다른 PR이 같은 절에
Event Publication Registry 문장을 더했다. 고치는 속도보다 어긋나는 속도가 빠르다.

AGENTS.md와 검사 하네스가 들고 있던 이 사본 두 개를 지운다. 저장소의 모든 기술 스택 사본을 없애는 것은 아니다.
남기는 사본과 그 이유는 제외 절에 적는다.

## 범위
- `docs/wiki/changes.md`를 삭제한다. 기록 요구는 PR spec 한 곳으로 모은다.
- 그 파일을 요구하거나 가리키는 자리를 모두 정리한다. AGENTS.md의 개발 계약 문장, `scripts/check_pr.py`의 검사와
  `scripts/test_check_pr.py`의 해당 fixture·단언, PR 템플릿의 체크 항목, `docs/wiki/development-workflow.md`와
  `docs/wiki/README.md`의 안내가 대상이다.
- AGENTS.md의 "현재 기술 스택" 절을 `build.gradle.kts`와 `gradle/wrapper/gradle-wrapper.properties`가 정본이라는
  한 줄로 줄인다. 기본 패키지 이름은 그 줄에 함께 둔다.
- spec 템플릿의 "Wiki 영향" 절은 그대로 둔다. 바뀐 wiki 본문을 적는 자리이고, 없어지는 것은 변경 기록 파일 하나다.

## 제외
- 애플리케이션 코드와 빌드 스크립트는 바꾸지 않는다.
- 이미 머지된 spec 문서 안의 `changes.md` 링크와 문장은 고치지 않는다. 그 문서들은 작성 시점의 결정을 적은 기록이고
  나중에 손대면 기록이 아니게 된다. 링크가 깨진 채 남는 것은 알고 받아들이는 결과다.
- `docs/wiki`의 다른 본문 페이지는 갱신 대상이 아니다. 이번 변경은 기록 규칙을 없애는 것이지 지식 본문을 바꾸는 것이
  아니다.
- PR 번호 25 브랜치에 남아 있던 기술 스택 축약 커밋 두 개는 가져오지 않고 여기서 다시 만든다. 그 브랜치는 이미
  머지된 PR의 브랜치라 그대로 올리면 머지된 커밋이 중복된다.
- `docs/wiki/current-state.md`의 빌드 선언 표와 루트 `README.md`의 기술 스택 표는 건드리지 않는다. 앞의 것은 확인
  시점이 박힌 관측 기록이고 뒤의 것은 외부 독자용 소개다. 둘 다 AGENTS.md가 들고 있던 것보다 크고 자세한 사본이다.
  특히 README의 H2와 Spring Modulith 문장은 지금 빌드와 어긋나 있는데, 이 PR 이전부터 있던 문제라서 별도 PR로
  고친다. 그래서 이 PR은 사본 문제를 끝내지 못하고 AGENTS.md 몫만 처리한다.
- 변경 기록 파일이 주던 이득 하나는 포기한다. 모든 PR의 요구사항 영향 판정을 한 파일에서 통독하며 감사하는 능력이다.
  이제 spec을 하나씩 열어야 한다. 작성자가 적고 `docs/spec/` 파일명이 날짜 접두라 목록 정렬이 시간순 색인 역할을
  하므로 지불할 만하다고 봤다.

## 관련 문서
없음. 장기 스펙 계층을 따르지 않는 규칙 변경이다. 이 PR이 고치는 두 사본을 만든 선행 PR spec은
[development-rules-original-first](2026-09-13-development-rules-original-first.md)와
[agents-md-currency](2026-09-20-agents-md-currency.md)다.

## 변경 경로
- AGENTS.md
- README.md
- .github/**
- docs/**
- scripts/**

## 인수 조건
- `docs/wiki/changes.md`가 없다.
- 저장소에서 `changes.md`를 요구하거나 링크하는 자리가 이미 머지된 spec 문서 밖에는 남아 있지 않다.
- `scripts/check_pr.py`가 변경 기록 갱신을 더 이상 요구하지 않고, 나머지 검사는 그대로 동작한다.
- AGENTS.md의 "현재 기술 스택" 절이 정본 포인터 한 줄이고 버전·의존성 나열이 없다.
- AGENTS.md의 개발 계약에 wiki 본문을 즉시 갱신한다는 요구는 남아 있고, 변경 기록 파일 요구만 빠졌다.

## 검증
- `python3 -m unittest discover -s scripts -p 'test_*.py'`가 모두 통과한다. 검사 한 개를 지우므로 그 검사를 확인하던
  테스트와 fixture도 함께 지운 뒤 나머지가 깨지지 않는지 본다.
- `python3 scripts/check_pr.py --base origin/main --worktree`가 PASS로 끝난다. 이 PR 자체가 변경 기록을 갱신하지
  않으므로, 검사를 제대로 지웠다면 통과하고 덜 지웠다면 바로 실패한다.
- `grep -rn "changes.md\|변경 기록\|wiki 기록"`으로 남은 참조가 이미 머지된 spec 문서뿐인지 확인한다. 파일명만
  찾으면 같은 개념을 한국어로 쓴 자리를 놓친다.
- `grep -n "Kotlin 2.4"`로 AGENTS.md에 버전 문자열이 남아 있지 않은지 확인한다.
- 이 spec 파일에 부등호 여는 문자가 없는지 커밋 전에 `grep -c`로 확인해 0을 얻는다.
- 애플리케이션 코드가 바뀌지 않으므로 `./gradlew test`는 실행하지 않는다.

## Wiki 영향
`docs/wiki/changes.md`를 삭제한다. 제품 요구사항과 설계는 바뀌지 않는다. 바뀌는 것은 기록을 어디에 남기는가라는
개발 절차 규칙 하나다. 이후로는 PR spec 하나가 그 PR의 목적, 결정, 범위 조정, 결과를 모두 담는다.
`docs/wiki/development-workflow.md`와 `docs/wiki/README.md`에서 변경 기록을 전제한 안내 문장을 고친다.

## 분할 계획
파일 8개 안팎이고 줄 수는 삭제하는 410줄이 대부분이다. 한도인 25개 파일과 800줄에서 멀어 나누지 않는다.
한도 경고가 나오면 기술 스택 절 축약을 후속 PR로 옮긴다. 두 변경은 서로 의존하지 않는다.

## TDD 검증 기록
업무 동작 변경이 아니라 적용하지 않는다. 다만 하네스 코드를 고치므로 `scripts/test_check_pr.py`를 검사 제거에 맞게
같이 고치고 위 검증 절의 단위 테스트 명령으로 확인한다.

## 결과
`docs/wiki/changes.md` 410줄을 지우고, 그 파일을 요구하거나 가리키던 자리를 정리했다. `check_pr.py`에서 검사 블록
여섯 줄을 지웠고 `difflib`과 `Path`는 지운 블록 밖에서도 쓰이므로 import를 남겼다. `test_check_pr.py`에서는
`test_wiki_missing` 하나와 fixture 두 곳을 지웠다. AGENTS.md의 기술 스택 다섯 줄은 정본 포인터 한 줄이 되었다.

`python3 -m unittest discover -s scripts -p 'test_*.py'`는 124개가 통과했다. `python3 scripts/check_pr.py --base
origin/main --worktree`는 PASS이고 일반 10개 파일 559줄로 한도 안이다. 이 PR 자체가 변경 기록을 갱신하지 않는데도
통과한 것이 검사가 실제로 제거되었다는 증거다. `grep`으로 남은 참조를 확인한 결과 이미 머지된 spec 문서와,
새 규칙을 적은 `development-workflow.md`의 "별도의 변경 기록 파일은 두지 않는다" 한 줄만 남았다.
`grep -n "Kotlin 2.4" AGENTS.md`는 결과가 없다.

리뷰에서 받은 지적을 반영했다. 처음 조사가 파일명 문자열만 찾아 한국어 표현만 쓰는 다섯 곳을 놓쳤고, 그중 넷은
검사기가 지금도 "wiki 기록"을 검사한다고 적고 있었다. 이 PR이 없애려던 종류의 거짓 서술을 새로 만드는 자리라
먼저 고쳤다. 목적 문단이 "정본만 남긴다"고 과장한 것도 낮췄다. `current-state.md`와 `README.md`에 더 크고 더
어긋난 기술 스택 사본이 남아 있어서, 이 PR은 사본 문제를 끝내지 못하고 AGENTS.md 몫만 처리한다.

남은 강제력을 따져 보면, 사라진 것은 changes.md에 spec 링크와 특정 문구가 추가되었는지 보는 존재 검사다.
`SECTIONS`의 `Wiki 영향`이 비어 있거나 placeholder면 실패하는 검사는 그대로 남아 있어서, 요구사항 영향을
적었는지 묻는 요구 자체는 살아 있다. 전후 모두 내용이 맞는지는 검사하지 않고 썼는지만 본다. 검사 대상이 충돌하는
파일에서 충돌하지 않는 파일로 옮겨간 것이라 교환이 유리하다고 판단했다.

하네스 변경의 검사 우회 여부도 따로 봤다. 지운 블록은 다른 검사의 입력을 만들지 않았고, 마이그레이션 버전 중복,
변경 경로 범위, 크기 한도, 스키마 변경 검토, spec 첫 커밋 다섯 게이트는 입력이 서로 독립이다. 우회 경로는 없다.

### 범위 조정 - 한국어 표현만 남은 참조 다섯 곳 추가 정리 (개발 중)
`changes.md`라는 파일명 대신 "변경 기록", "wiki 기록"이라는 한국어 표현만 쓰는 자리가 다섯 곳 더 있었다.
`README.md`의 SDD 절차 4번, `docs/wiki/development-workflow.md`의 세 곳, `docs/wiki/borrowed-workflows.md`의
검사 범위 설명이다. 변경 경로에 `README.md`를 더한다.

처음 조사에서 파일명 문자열만 찾아 놓쳤다. 이 다섯 곳은 성격이 두 가지로 나뉜다. `README.md`는 없어진 파일로
연결하라고 안내하고, 나머지 넷은 로컬 검사기가 지금도 "wiki 기록"을 검사한다고 적고 있다. 뒤쪽이 더 나쁘다.
사라진 강제 장치가 아직 있다고 읽히면 기록을 빠뜨려도 검사가 잡아 줄 것이라 믿게 된다. 이 PR이 줄이는 강제력을
정확히 적는 것이 이번 변경의 일부다.

검사 항목 나열에서 "wiki 기록"만 빼고 나머지 항목과 문장 구조는 그대로 둔다. `docs/wiki/README.md`의 진입점
한 줄도 같이 고친다. 그 줄이 기술 스택을 AGENTS.md에서 보라고 안내하는데 그 내용이 이제 없어서, 기술 스택 현황은
현재 상태(`docs/wiki/current-state.md`, 볼트)로 보낸다.
