# PR spec: Flyway 마이그레이션 버전 충돌 검사

## 목적
같은 버전 번호를 가진 마이그레이션이 둘 이상 머지되면 배포된 애플리케이션이 기동 단계에서 죽는다. 각 PR은 혼자 보면 정상이라 리뷰로 잡히지 않고, 두 PR이 합쳐진 뒤에야 드러난다. 다음 PR부터 업무 테이블 마이그레이션이 생기므로 그 전에 로컬 PR 검사가 잡도록 한다.

## 범위
- `scripts/check_pr.py`에 마이그레이션 버전 중복 검사를 추가한다. PR이 추가한 파일과 기준 브랜치의 파일을 함께 본다.
- GitHub Actions 워크플로로 PR마다 빌드와 버전 검사를 실행한다. 빌드가 통과해야 버전 검사가 돈다.
- 워크플로가 부를 수 있도록 `--migrations-only` 모드를 추가한다. spec 이력 규칙은 머지 커밋 위에서 의미가 달라지므로
  워크플로는 버전 검사만 수행한다.
- Flyway가 버전을 숫자로 비교하는 규칙을 따른다. `V001`과 `V1`은 같은 버전이다.
- 기준 브랜치가 원격보다 뒤처져 있으면 경고한다. 오래된 기준으로 통과한 결과를 최신 판정으로 착각하지 않게 한다.
- 위 동작의 하네스 테스트를 추가한다. Python 3 표준 라이브러리만 쓴다.
- 소유 모듈: 없다. 개발 하네스 변경이다. 불변식: 저장소 어디에도 같은 Flyway 버전이 둘 이상 존재하지 않는다.
- 복구·관측성: 해당 없다. 저장소 상태를 읽는 정적 검사다.

## 제외
- 브랜치 보호 설정. 저장소 설정이라 코드로 넣을 수 없다. 머지 전 최신 상태 요구는 소유자가 직접 켜야 한다.
- 워크플로에서의 spec 이력·크기 검사. 머지 커밋 위에서는 첫 커밋 판정이 달라진다. 로컬 검사가 계속 담당한다.
- 마이그레이션 파일명 규칙 검사. Flyway는 반복 실행 마이그레이션 같은 다른 접두사도 지원하므로, 형식을 좁게 강제하면 나중에 막힌다.
- 마이그레이션 내용 검사(되돌릴 수 있는 변경인지, 컬럼 추가가 안전한지).
- checksum 불일치 검사. 지속 DB가 있어야 재현할 수 있어 배포 절차에서 다룬다.

## 관련 문서
없음. 원문 근거는 [개발 가이드 2.5 Migration ownership](../references/03_Development_Guide.md), 선행 PR은 [postgres-flyway-baseline](2026-09-15-postgres-flyway-baseline.md)이다.

## 변경 경로
- .github/workflows/**
- scripts/check_pr.py
- scripts/test_check_pr.py
- docs/spec/2026-09-15-flyway-version-guard.md
- docs/wiki/development-workflow.md
- docs/wiki/borrowed-workflows.md
- docs/wiki/changes.md

## 인수 조건
1. 같은 버전 번호를 가진 마이그레이션이 둘 이상이면 검사가 실패하고 충돌한 파일 경로를 알려준다.
2. 앞자리 0만 다른 버전(`V001`과 `V1`)을 같은 버전으로 판정한다.
3. 기준 브랜치에만 있는 버전과 이번 PR이 추가한 버전이 겹치면 실패한다. 이번 PR 안에서만 보면 중복이 없어도 잡는다.
4. 기준 브랜치가 원격 추적 브랜치보다 뒤처져 있으면 경고를 낸다. 실패로 막지는 않는다.
5. 마이그레이션이 없거나 버전이 모두 다르면 통과한다.
6. `python3 -m unittest discover -s scripts -p 'test_*.py'`가 통과한다.
7. `python3 scripts/check_pr.py --base main`이 통과한다.
8. `--migrations-only` 모드가 spec·크기 검사 없이 버전 중복만 판정한다.
9. PR에서 워크플로가 실행되어 빌드와 버전 검사가 모두 통과한다. 실행 결과를 spec에 기록한다.

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
- [개발 규칙](../wiki/development-workflow.md): 병렬 PR에서 버전이 겹칠 수 있다는 점과 머지 직전 재실행 규칙을 추가하고,
  "GitHub Actions를 쓰지 않는다"는 세 곳의 서술을 실제 상태로 바꾼다.
- [차용 내역](../wiki/borrowed-workflows.md): workflow 없음을 전제로 한 `check-ci` 설명을 갱신한다.
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
| `test_reports_duplicates_without_spec_rules` / `test_ignores_missing_spec_and_size_rules` | `python3 -m unittest discover -s scripts -p 'test_*.py'` — `check()`가 `migrations_only` 인자를 받지 못해 `TypeError`로 2개 실패 | 같은 명령 통과 | 같은 명령 — 64 tests OK |
| `test_stale_base_warns` | `python3 -m unittest discover -s scripts -p 'test_*.py'` — 경고가 없어 실패 | 같은 명령 통과 | 같은 명령 — 46 tests OK |

## 결과
`python3 -m unittest discover -s scripts -p 'test_*.py'` 46 tests OK. `python3 scripts/check_pr.py --base main` 통과.
인수 조건 7개를 모두 만족했다.

다른 프로젝트의 GitHub Actions 게이트를 참고 자료로 받아 이 저장소 방식으로 옮겼다. 옮기면서 정한 것을 남긴다.

1. **하네스에 검사를 넣고, 워크플로가 그 검사를 부른다**: 처음에는 저장소 결정에 따라 하네스에만 넣었다.
   사용자 요청으로 GitHub Actions를 다시 도입하면서 판정 규칙은 한 곳(`check_pr.py`)에 두고 워크플로가 호출하게 했다.
   규칙을 YAML에 복제하면 로컬과 CI의 판정이 갈라진다.
2. **기준 브랜치의 파일을 함께 본다**: 이번 PR이 추가한 파일만 보면 중복이 없어도 기준 브랜치에 같은 번호가
   있을 수 있다. merge-base가 아니라 `--base`로 준 ref의 트리를 읽어 최신 기준으로 판정한다.
3. **앞자리 0을 무시한다**: Flyway는 버전을 숫자로 비교한다. `V001`과 `V1`은 같은 버전이라 둘 다 있으면 기동에
   실패한다. 파일명만 비교하면 이 경우를 놓친다.

닫지 못한 틈을 분명히 적는다. 이 검사의 판정은 실행한 순간의 기준 브랜치에 대한 것이다. 병렬 PR 둘이 각각 같은
번호를 잡고 먼저 머지된 쪽이 반영되면, 나중 PR의 예전 통과 결과는 더 이상 유효하지 않다. 참고 자료의 프로젝트는
브랜치 보호의 최신 상태 요구로 이 틈을 닫았지만 이 저장소는 그 설정을 쓰지 않는다. 그래서 머지 직전
`git fetch` 후 `--base origin/main`으로 재실행하는 것을 개발 규칙에 넣었고, 기준의 원격이 head에 없으면 검사가
경고하도록 했다. 경고는 실패가 아니다. 기준을 SHA로 주거나 원격이 없는 경우까지 막으면 정상 작업을 방해한다.

### 범위 조정 - GitHub Actions 도입 (개발 후)
사용자 요청으로 PR 게이트를 워크플로로 확장했다. 2026-09-07에 제거했던 결정을 뒤집는 변경이라 근거를 남긴다.

로컬 `check_pr.py`는 실행한 순간의 기준으로만 판정한다. 병렬 PR 둘이 같은 번호를 잡으면 먼저 머지된 쪽이 반영된 뒤
나중 PR의 통과 결과는 무효인데, 이를 알아차릴 주체가 사람뿐이었다. 머지 결과 위에서 다시 도는 실행 주체가 있어야
이 틈이 줄어든다.

- **단계 순서**: 빌드가 통과해야 버전 검사를 돈다. 컴파일도 안 되는 변경에 나머지 게이트를 돌릴 이유가 없다.
- **워크플로는 버전 중복만 본다**: spec 첫 커밋 판정은 머지 결과 위에서 의미가 달라진다. 그래서
  `--migrations-only` 모드를 따로 두고 spec 이력·크기·wiki 검사는 로컬에 남겼다.
- **판정 규칙을 YAML에 복제하지 않는다**: 워크플로는 `check_pr.py`를 호출만 한다. 규칙이 두 곳에 있으면 갈라진다.
- **액션 선택**: 요청받은 `gradle/gradle-build-action`은 2024년 7월이 마지막 릴리스이고 `gradle/actions/setup-gradle`로
  대체됐다. 후속 액션의 현재 메이저를 쓴다.
- **JDK 배포본**: 요청에 따라 Amazon Corretto 25를 쓴다. alpine 변형은 적용하지 않았다. `actions/setup-java`에는
  alpine 선택지가 없고, alpine은 job 컨테이너 이미지로만 들어온다. 빌드 job은 Testcontainers로 Docker를 쓰는데
  컨테이너 안에서 돌리면 호스트 Docker 소켓 마운트와 host override 설정이 추가로 필요해 실패 지점이 늘어난다.
  러너의 JDK는 빌드 도구일 뿐 배포 이미지가 아니라서 alpine으로 얻는 것도 없다.
- **브랜치 보호는 넣지 못했다**: 저장소 설정이라 코드로 넣을 수 없다. 머지 전 최신 상태 요구를 켜야 낡은 통과 표시가
  완전히 막힌다. 소유자 조치가 필요하다.

개발 중에 이 검사의 경고가 실제로 동작하는 것을 확인했다. 작업 도중 기준 브랜치에 다른 PR이 머지되자
`기준의 원격 origin/main이 head에 없습니다`가 떴고, 실제로 `origin/main`이 앞서 있었다.

### 워크플로 첫 실행 결과
PR 17에서 실행을 확인했다. 실행 번호 34921818798.

| Job | 결과 | 시간 |
|---|---|---|
| Gradle build | success | 2분 25초 |
| Flyway version gate | success | 6초 |

Corretto 25 설치, Gradle 빌드, Testcontainers 통합 테스트가 러너에서 모두 통과했다. ubuntu-latest에 Docker가 있어
별도 설치 단계 없이 실제 PostgreSQL 컨테이너가 떴다. 버전 검사는 기준 브랜치를 가져와 `--migrations-only`로 판정했고
현재 마이그레이션은 `V001` 하나뿐이라 중복이 없다.

저장소 소유자가 브랜치 보호에서 상태 검사 통과와 머지 전 최신 상태 요구를 켰다. 이로써 로컬 검사만으로는 닫히지 않던
낡은 통과 판정 경로가 실제로 막혔다. 워크플로가 머지 시점 기준으로 다시 돌고, 기준이 오래되면 머지가 막힌다.
