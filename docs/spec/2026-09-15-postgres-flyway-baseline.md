# PR spec: PostgreSQL 단일 엔진과 Flyway 스키마 소유

## 목적
도메인 테이블이 생기기 전에 데이터베이스 기반을 정한다. 지금은 H2가 classpath에 있어 테스트가 실제 PostgreSQL과 다른 엔진에서 돌고, 스키마를 누가 만드는지도 정해지지 않았다. 주문·결제 정합성의 정본이 PostgreSQL이므로 테스트도 같은 엔진에서 돌리고, 스키마 변경은 Flyway 마이그레이션만 소유하게 한다.

## 범위
- H2 의존성과 콘솔을 제거한다. 개발·테스트·운영이 모두 PostgreSQL을 쓴다.
- Flyway를 도입하고 `V001__baseline.sql`로 마이그레이션 파이프라인을 세운다. 버전은 Spring Boot BOM이 관리한다.
- 통합 테스트를 Testcontainers의 실제 PostgreSQL 컨테이너에서 실행한다. 컨테이너 설정은 공용 테스트 기반 클래스 한 곳에 둔다.
- JPA를 `ddl-auto=validate`로 고정한다. 애플리케이션이 스키마를 만들거나 바꾸지 않는다.
- 접속 정보는 환경 변수로 주입하고 기본값에 비밀번호를 하드코딩하지 않는다.
- 소유 모듈: 없다. `platform` 성격의 실행 기반이다. 불변식: 스키마의 유일한 소유자는 Flyway 마이그레이션이다.
- 복구·관측성: 마이그레이션 실패 시 애플리케이션이 뜨지 않는 것이 의도된 동작이다. 부분 적용 상태로 기동하지 않는다.

## 제외
- 업무 테이블. 각 모듈이 자기 테이블을 후속 마이그레이션으로 추가한다. `V001`은 의도적으로 비어 있다.
- Modulith Event Publication Registry 스키마. `starter-jpa`를 넣는 PR에서 함께 다룬다.
- 운영 배포용 접속 정보, 커넥션 풀 튜닝, 읽기 전용 복제본.
- `spring-boot-docker-compose` 같은 로컬 기동 자동화. 필요해지면 별도 PR로 한다.
- Repository 자체 테스트. 테스트 전략의 제외 대상이다.

## 관련 문서
없음. 원문 근거는 [개발 가이드 2.5 Migration ownership](../references/03_Development_Guide.md)과 [시스템 설계 2.1 Logical database ownership](../references/02_System_Design.md)이다.

## 변경 경로
- build.gradle.kts
- src/main/resources/application.yaml
- src/main/resources/db/migration/**
- src/test/kotlin/com/resilientpos/**
- src/test/resources/**
- docs/spec/2026-09-15-postgres-flyway-baseline.md
- docs/wiki/current-state.md
- docs/wiki/changes.md

## 인수 조건
1. H2 의존성이 제거된 상태에서 애플리케이션 컨텍스트 테스트가 실제 PostgreSQL 컨테이너로 통과한다.
2. Flyway가 `V001__baseline.sql`을 적용하고 `flyway_schema_history`에 성공으로 기록한다.
3. JPA 설정이 `validate`라서 애플리케이션이 스키마를 만들지 않는다.
4. 접속 설정에 비밀번호 같은 비밀값이 하드코딩되어 있지 않다.
5. 기존 모듈 경계 검증과 의존성 방향 가드가 그대로 통과한다.
6. `python3 scripts/check_pr.py --base main`이 통과한다.

### Checkpoint와 검증 명령
| Checkpoint | 검증 명령 | 의존 |
|---|---|---|
| postgres-container | `./gradlew test --tests 'com.resilientpos.ResilientPosApplicationTests'` | 없음 |
| flyway-baseline | `./gradlew test --tests 'com.resilientpos.platform.SchemaMigrationTest'` | postgres-container |
| regression | `./gradlew test` | flyway-baseline |

## 검증
```sh
./gradlew test
python3 scripts/check_pr.py --base main --worktree
```
- 정상: 컨테이너가 뜨고 마이그레이션이 적용된 뒤 컨텍스트가 로드된다.
- 실패: 마이그레이션 파일을 훼손하면 checksum 불일치로 기동이 실패하는지 확인한다.

## Wiki 영향
- 현재 상태(`docs/wiki/current-state.md`, 볼트): H2 제거, PostgreSQL 단일 엔진, Flyway 스키마 소유, 테스트에 Docker가 필요하다는 점을 기록한다.
- 변경 기록(`docs/wiki/changes.md`, 2026-09-21 삭제): 이 spec 링크와 테스트 DB 전략 결정 근거를 남긴다.

## 분할 계획
예상 규모: 빌드·설정 약 30줄, 마이그레이션 약 10줄, 테스트 약 70줄, 문서 약 130줄로 총 240줄 내외다.
초과하면 Testcontainers 전환만 남기고 Flyway 도입을 후속 PR로 옮긴다. 후속 순서는 order `CreateOrder` 수직 절단이다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| `baseline 마이그레이션이 성공으로 기록된다` | `./gradlew test --tests 'com.resilientpos.SchemaMigrationTest'` — `PSQLException: relation flyway_schema_history does not exist` | 설정과 `V001` 추가 후 같은 명령 통과 | `./gradlew test` — BUILD SUCCESSFUL, 19 tests |
| `애플리케이션이 업무 테이블을 만들지 않는다` | 같은 명령 — `expected: [flyway_schema_history] but was: []` | 같은 명령 통과 | `./gradlew test` — BUILD SUCCESSFUL, 19 tests |
| `연결된 엔진은 PostgreSQL이다` | RED 없음. 컨테이너와 연결 설정만으로 바로 통과했다. 엔진 전환을 고정하는 회귀 증거로 남긴다 | 같은 명령 통과 | `./gradlew test` — BUILD SUCCESSFUL, 19 tests |
| `contextLoads` 가 실제 PostgreSQL에서 뜬다 | 잘못된 SQL을 `V001`에 넣어 실패. `Failed to execute script V001__baseline.sql`, `ERROR: syntax error at or near ";"` | 파일 복구 후 통과 | `./gradlew test` — BUILD SUCCESSFUL, 19 tests |

## 결과
`./gradlew test` BUILD SUCCESSFUL (19 tests). 인수 조건 6개를 모두 만족했다.

개발 중 막힌 곳과 해결을 기록한다. 셋 다 널리 퍼진 예제를 그대로 쓰면 실패하는 것들이다.

1. **Spring Boot 4는 자동설정을 기술별 모듈로 쪼갰다**: `org.flywaydb:flyway-core`만 넣으면 Flyway가 아예 실행되지
   않는다. 마이그레이션 디렉터리가 있어도 `flyway_schema_history`가 만들어지지 않았다. 자동설정을 담은
   `org.springframework.boot:spring-boot-flyway`로 바꾸니 동작했다. 이 저장소가 이미 쓰는 `spring-boot-starter-webmvc`와
   같은 계열의 이름 변경이다.
2. **Testcontainers 2.x는 아티팩트와 패키지가 바뀌었다**: `org.testcontainers:postgresql`과
   `org.testcontainers:junit-jupiter`는 해석되지 않는다. 각각 `testcontainers-postgresql`,
   `testcontainers-junit-jupiter`이며 클래스도 `org.testcontainers.postgresql.PostgreSQLContainer`로 옮겨졌다.
   버전은 Spring Boot BOM이 2.0.5로 관리한다.
3. **컨테이너는 JVM에 하나만 띄운다**: JUnit의 컨테이너 생명주기를 쓰면 첫 테스트 클래스가 끝날 때 컨테이너가
   내려간다. Spring은 캐시된 컨텍스트를 재사용하므로 다음 클래스가 `Connection is not available`로 30초 타임아웃
   뒤 실패했다. 단독 실행은 통과하고 전체 실행만 실패해서 놓치기 쉽다. 컨테이너를 한 번만 시작하고 정리를
   Testcontainers ryuk에 맡기니 전체 실행이 9초로 끝난다.

범위에서 조정한 것 세 가지를 남긴다.

- `SchemaMigrationTest`는 Checkpoint 표에 적은 `platform` 하위가 아니라 기존 구조 테스트와 같은 `com.resilientpos`에
  뒀다. 운영 코드에 `platform` 패키지가 없는데 테스트에만 만들면 구조가 어긋난다.
- `open-in-view`를 끈다. 켜두면 HTTP 응답을 만드는 동안 영속성 컨텍스트가 열려 있어 지연 로딩이 컨트롤러까지
  따라온다. 읽기 쪽이 DTO만 반환한다는 규약과 맞지 않는다.
- 마이그레이션 checksum 불일치 검증은 하지 않았다. 실행마다 새 컨테이너가 떠서 이력이 매번 새로 만들어지므로
  ephemeral 컨테이너로는 재현할 수 없다. 대신 잘못된 SQL을 넣어 마이그레이션 실패가 기동을 막는 것을 확인했다.
  checksum 검증은 지속 DB를 쓰는 운영 배포 절차에서 다룬다.
