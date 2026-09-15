# PR spec: Flyway 마이그레이션 버전 충돌 검사

## 목적
같은 버전 번호를 가진 마이그레이션이 둘 이상 머지되면 배포된 애플리케이션이 기동 단계에서 죽는다. 각 PR은 혼자 보면 정상이라 리뷰로 잡히지 않고, 두 PR이 합쳐진 뒤에야 드러난다. 다음 PR부터 업무 테이블 마이그레이션이 생기므로 그 전에 로컬 PR 검사가 잡도록 한다.

## 범위
- `scripts/check_pr.py`에 마이그레이션 버전 중복 검사를 추가한다. PR이 추가한 파일과 기준 브랜치의 파일을 함께 본다.
- Flyway가 버전을 숫자로 비교하는 규칙을 따른다. `V001`과 `V1`은 같은 버전이다.
- 기준 브랜치가 원격보다 뒤처져 있으면 경고한다. 오래된 기준으로 통과한 결과를 최신 판정으로 착각하지 않게 한다.
- 위 동작의 하네스 테스트를 추가한다. Python 3 표준 라이브러리만 쓴다.
- 소유 모듈: 없다. 개발 하네스 변경이다. 불변식: 저장소 어디에도 같은 Flyway 버전이 둘 이상 존재하지 않는다.
- 복구·관측성: 해당 없다. 저장소 상태를 읽는 정적 검사다.

## 제외
- GitHub Actions 워크플로와 브랜치 보호 설정. 이 저장소는 명시 실행 방식이며 [개발 규칙](../wiki/development-workflow.md)이 그렇게 정했다.
- 마이그레이션 파일명 규칙 검사. Flyway는 반복 실행 마이그레이션 같은 다른 접두사도 지원하므로, 형식을 좁게 강제하면 나중에 막힌다.
- 마이그레이션 내용 검사(되돌릴 수 있는 변경인지, 컬럼 추가가 안전한지).
- checksum 불일치 검사. 지속 DB가 있어야 재현할 수 있어 배포 절차에서 다룬다.

## 관련 문서
없음. 원문 근거는 [개발 가이드 2.5 Migration ownership](../references/03_Development_Guide.md), 선행 PR은 [postgres-flyway-baseline](2026-09-15-postgres-flyway-baseline.md)이다.

## 변경 경로
- scripts/check_pr.py
- scripts/test_check_pr.py
- docs/spec/2026-09-15-flyway-version-guard.md
- docs/wiki/development-workflow.md
- docs/wiki/changes.md

## 인수 조건
1. 같은 버전 번호를 가진 마이그레이션이 둘 이상이면 검사가 실패하고 충돌한 파일 경로를 알려준다.
2. 앞자리 0만 다른 버전(`V001`과 `V1`)을 같은 버전으로 판정한다.
3. 기준 브랜치에만 있는 버전과 이번 PR이 추가한 버전이 겹치면 실패한다. 이번 PR 안에서만 보면 중복이 없어도 잡는다.
4. 기준 브랜치가 원격 추적 브랜치보다 뒤처져 있으면 경고를 낸다. 실패로 막지는 않는다.
5. 마이그레이션이 없거나 버전이 모두 다르면 통과한다.
6. `python3 -m unittest discover -s scripts -p 'test_*.py'`가 통과한다.
7. `python3 scripts/check_pr.py --base main`이 통과한다.

### Checkpoint와 검증 명령
| Checkpoint | 검증 명령 | 의존 |
|---|---|---|
| duplicate-detection | `python3 -m unittest discover -s scripts -p 'test_*.py'` | 없음 |
| stale-base-warning | 같은 명령 | duplicate-detection |
| self-check | `python3 scripts/check_pr.py --base main` | duplicate-detection |

## 검증
```sh
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/check_pr.py --base main --worktree
```
- 정상: 버전이 서로 다르면 통과한다.
- 실패: 같은 버전을 두 개 만들어 검사가 실패하는지, 기준 브랜치와 겹칠 때도 실패하는지 확인한다.

## Wiki 영향
- [개발 규칙](../wiki/development-workflow.md): 병렬 PR에서 버전이 겹칠 수 있다는 점과 머지 직전 재실행 규칙을 추가한다.
- [변경 기록](../wiki/changes.md): 이 spec 링크와 검사 범위의 한계를 남긴다.

## 분할 계획
예상 규모: 하네스 약 25줄, 테스트 약 60줄, 문서 약 90줄로 총 180줄 내외다.
초과하면 기준 브랜치 뒤처짐 경고를 후속 PR로 옮기고 중복 검사만 남긴다. 후속 순서는 order `CreateOrder` 수직 절단이다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| `test_duplicate_version_fails` | `python3 -m unittest discover -s scripts -p 'test_*.py'` — `AssertionError: 'Flyway 버전 2' not found in ''`. 검사가 없어 중복을 통과시킴 | 같은 명령 통과 | 같은 명령 — 46 tests OK |
| `test_leading_zero_is_same_version` | `python3 -m unittest discover -s scripts -p 'test_*.py'` — 같은 이유로 실패 | 같은 명령 통과 | 같은 명령 — 46 tests OK |
| `test_collision_with_base_branch_fails` | `python3 -m unittest discover -s scripts -p 'test_*.py'` — 같은 이유로 실패 | 같은 명령 통과 | 같은 명령 — 46 tests OK |
| `test_distinct_versions_pass` | RED 없음. 검사 추가 전에도 통과했다. 중복 검사가 정상 파일을 막지 않는다는 회귀 증거로 남긴다 | 같은 명령 통과 | 같은 명령 — 46 tests OK |
| `test_stale_base_warns` | `python3 -m unittest discover -s scripts -p 'test_*.py'` — 경고가 없어 실패 | 같은 명령 통과 | 같은 명령 — 46 tests OK |

## 결과
`python3 -m unittest discover -s scripts -p 'test_*.py'` 46 tests OK. `python3 scripts/check_pr.py --base main` 통과.
인수 조건 7개를 모두 만족했다.

다른 프로젝트의 GitHub Actions 게이트를 참고 자료로 받아 이 저장소 방식으로 옮겼다. 옮기면서 정한 것을 남긴다.

1. **워크플로가 아니라 하네스에 넣었다**: 이 저장소는 GitHub Actions와 브랜치 보호를 쓰지 않기로 이미 정했다.
   PR마다 실행하는 `check_pr.py`가 그 자리를 대신하므로 검사도 거기에 둔다.
2. **기준 브랜치의 파일을 함께 본다**: 이번 PR이 추가한 파일만 보면 중복이 없어도 기준 브랜치에 같은 번호가
   있을 수 있다. merge-base가 아니라 `--base`로 준 ref의 트리를 읽어 최신 기준으로 판정한다.
3. **앞자리 0을 무시한다**: Flyway는 버전을 숫자로 비교한다. `V001`과 `V1`은 같은 버전이라 둘 다 있으면 기동에
   실패한다. 파일명만 비교하면 이 경우를 놓친다.

닫지 못한 틈을 분명히 적는다. 이 검사의 판정은 실행한 순간의 기준 브랜치에 대한 것이다. 병렬 PR 둘이 각각 같은
번호를 잡고 먼저 머지된 쪽이 반영되면, 나중 PR의 예전 통과 결과는 더 이상 유효하지 않다. 참고 자료의 프로젝트는
브랜치 보호의 최신 상태 요구로 이 틈을 닫았지만 이 저장소는 그 설정을 쓰지 않는다. 그래서 머지 직전
`git fetch` 후 `--base origin/main`으로 재실행하는 것을 개발 규칙에 넣었고, 기준의 원격이 head에 없으면 검사가
경고하도록 했다. 경고는 실패가 아니다. 기준을 SHA로 주거나 원격이 없는 경우까지 막으면 정상 작업을 방해한다.
