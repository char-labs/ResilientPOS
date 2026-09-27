# PR spec: Kafka·Modulith 이벤트 작업 규칙을 저장소 공통 지침에 올린다

## 목적
#62(Kafka 재전달) 작업 중 사용자가 두 규칙을 정했다. 하나는 Kafka·Spring Kafka·Spring Modulith 이벤트 작업을 공식 문서로 대조한 뒤 적용하고,
문서가 기본값을 말하지 않으면 sources jar나 기동 로그(ProducerConfig·ConsumerConfig values)로 실제 값을 확인하며 추측으로 적지 않는다는 것이다.
다른 하나는 그런 작업을 끝낼 때마다 PR 전에 볼트 `ResilientPOS/wiki/modulith-kafka-keywords.md`의 키워드와 "대안과 선택 이유"를 갱신한다는 것이다.
지금 두 규칙은 개인 메모리와 볼트에만 있어 볼트나 메모리가 없는 에이전트에게 적용되지 않는다. `AGENTS.md`에 올리고, 키워드 문서를
`docs/wiki/current-state.md`처럼 `make bootstrap`이 저장소 경로에 링크하게 한다.

## 범위
- `AGENTS.md` "작업별로 읽기와 검증"에 한 줄을 더한다. Kafka·Spring Kafka·Modulith 이벤트(externalization, 재시도·DLT·재전송) 작업은
  공식 문서로 대조하고 근거를 PR spec에 남기며, 작업을 끝내면 PR 전에 [Modulith·Kafka 키워드](../wiki/modulith-kafka-keywords.md)의
  갱신 규칙대로 키워드와 선택 이유를 갱신한다. 갱신 절차의 정본은 그 문서 맨 앞 "갱신 규칙" 절이므로 AGENTS.md는 절차를 되풀이하지 않는다.
  #61이 같은 절의 "애플리케이션/빌드 변경 검증" 줄을 고치므로, 새 줄은 그 줄과 붙지 않는 "스키마 변경 검토" 줄 바로 뒤에 둔다.
- `scripts/link_claude.py`의 `LINKS`에 `('docs/wiki/modulith-kafka-keywords.md', 'wiki/modulith-kafka-keywords.md')`를 더한다.
  파일 링크는 [current-state-to-vault](2026-09-23-current-state-to-vault.md)에서 이미 지원하므로 로직은 바꾸지 않는다.
- `.gitignore`에 그 링크 경로를 더한다.
- `scripts/test_link_claude.py`에 두 wiki 문서가 링크 대상에 들어 있는지 확인하는 테스트를 더한다. 링크 생성 자체는 기존 테스트가
  `LINKS` 전체를 돌며 검증한다.
- `docs/wiki/README.md` 작업별 진입점에 키워드 문서를 한 줄 더하고, 볼트에 있으며 `make bootstrap`이 링크한다고 적는다.

계약: 볼트가 없는 환경에서는 링크가 생기지 않아 AGENTS.md의 링크가 열리지 않는다. current-state와 같은 한계이며, 규칙 문장 자체는 AGENTS.md에 있으므로 적용된다.

복구: 해당 없음.

관측성: 해당 없음.

## 제외
- 키워드 문서 내용 변경. 볼트에서 따로 관리한다.
- 볼트 `rules/` 추가.
- `docs/wiki/domain-model.md` §4.3. #62가 이미 "Kafka 관련 작업은 공식 문서로 대조한다" 줄을 더하므로 충돌을 피한다.
- #62 범위의 코드·wiki. #62와 겹치는 파일은 바꾸지 않는다.

## 관련 문서
없음.

## 변경 경로
- AGENTS.md
- .gitignore
- scripts/link_claude.py
- scripts/test_link_claude.py
- docs/wiki/README.md
- docs/spec/2026-09-27-kafka-work-rules.md

## 인수 조건
1. `make bootstrap` 뒤 `docs/wiki/modulith-kafka-keywords.md`가 볼트 파일을 가리키는 링크다.
2. 저장소는 그 파일을 추적하지 않는다.
3. 기존 링크 네 개와 rules 복사는 그대로 동작한다.
4. `AGENTS.md`에 두 규칙이 한 줄로 있고 키워드 문서의 갱신 규칙 절을 가리킨다.
5. #62의 변경 파일과 겹치는 파일이 없다.

## 검증
- RED: 테스트를 먼저 더한 뒤 `python3 -m unittest discover -s scripts -p 'test_link_claude.py'`가 키워드 문서 누락으로 실패하는지 확인한다.
- GREEN·회귀: `python3 -m unittest discover -s scripts -p 'test_*.py'`가 통과한다.
- 실측: `make bootstrap` 뒤 `ls -l docs/wiki/modulith-kafka-keywords.md`가 볼트를 가리키고 `git status`에 나타나지 않는다.
- `python3 scripts/check_pr.py --base origin/main --worktree`와 제출 전 `--worktree` 없는 실행이 PASS다.
- 지침 변경은 workflow 스킬로 누락·중복·충돌을 점검한다.

## Wiki 영향
- [wiki 목차](../wiki/README.md): 키워드 문서가 볼트에 있고 `make bootstrap`이 링크한다는 것을 한 줄 적는다.
- 요구사항 변화: 없다. 에이전트 작업 규칙과 개발 환경 구성만 바뀐다.

## 분할 계획
6개 파일, 50줄 안쪽이다. 경고선에 이르지 않는다.

## TDD 검증 기록
업무 동작 변경이 아니다. 하네스 스크립트 동작은 unittest로 RED→GREEN을 거친다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| test_links_cover_vault_wiki_documents | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
