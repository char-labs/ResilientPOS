# ResilientPOS

ResilientPOS는 네트워크 단절과 부분 실패 속에서도 주문·결제 상태가 중복 없이 수렴하는 Offline-First POS 플랫폼입니다.

## 기술 스택

현재 빌드에 선언된 기술입니다. 버전은 [빌드 설정](build.gradle.kts)과 [Gradle Wrapper](gradle/wrapper/gradle-wrapper.properties)를 기준으로 합니다.

| 영역 | 기술 |
|---|---|
| 언어 / JVM | Kotlin 2.4.10 / Java 25 toolchain |
| 프레임워크 | Spring Boot 4.1.1 |
| 웹 / 데이터 접근 | Spring MVC, Spring Data JPA, Jackson Kotlin |
| 데이터베이스 드라이버 | PostgreSQL, H2 |
| 운영 상태 확인 | Spring Boot Actuator, Micrometer Prometheus registry |
| 테스트 | Kotlin Test, JUnit 5, Spring Boot Test |
| 빌드 | Gradle Wrapper 9.7.1, Kotlin DSL |
| 개발 하네스 | Python 3 표준 라이브러리 기반 로컬 PR 검사 |

현재는 단일 Gradle 프로젝트의 애플리케이션 초기 구조입니다. PostgreSQL 연결 구성과 도메인 구현은 아직 완료되지 않았습니다.
Spring Modulith, Kotlin JDSL, Redis, Kafka, OpenTelemetry는 설계상 도입 방향이며 현재 의존성에는 없습니다. [구현 현황](docs/wiki/current-state.md)에서 계획과 실제 상태를 구분합니다.

## 시작하기

클론 직후 한 번 실행합니다. 스킬·에이전트 정본은 Obsidian 볼트에 있고, 저장소에는 링크만 만듭니다.

```sh
make bootstrap
```

볼트가 없으면 안내만 출력하고 정상 종료하므로 CI와 볼트 없는 머신에서도 안전합니다.
볼트 경로가 다르면 `RESILIENTPOS_VAULT` 환경 변수로 재정의합니다.

## 명령어

모든 작업은 `make` 로 수행합니다. `make help` 로 전체 목록을 확인할 수 있습니다.

| 명령 | 설명 |
| --- | --- |
| `make bootstrap` | 클론 직후 1회. Obsidian 볼트로 `.claude`·`.codex` 링크 생성 |
| `make build` | 애플리케이션 빌드 |
| `make test` | 애플리케이션 테스트 |
| `make harness` | 로컬 하네스 테스트 (Python 표준 라이브러리) |
| `make check` | PR 규칙 검사. 기준 커밋은 `BASE` 로 지정 (`make check BASE=main`) |
| `make up` | 로컬 PostgreSQL 기동 (docker compose) |
| `make down` | compose로 띄운 컨테이너를 profile 구분 없이 모두 중지 |

### 로컬 실행 (docker compose)

Docker 관련 파일은 모두 `docker/` 아래에 있고 로컬 개발 전용입니다. 테스트는 이 파일과 무관하게 Testcontainers로 PostgreSQL을 띄웁니다.

| 명령 | 뜨는 것 |
| --- | --- |
| `make up` | PostgreSQL 18 (`localhost:5432`) |
| `docker compose -f docker/docker-compose.yaml --profile app up -d --build` | + 앱 컨테이너 (`localhost:8080`, ECS JSON 로그) |
| `docker compose -f docker/docker-compose.yaml --profile app --profile obs up -d --build` | + Alloy, Grafana LGTM (`localhost:3000`, admin/admin) |

DB 비밀번호는 로컬 전용 기본값 `resilientpos` 입니다. 바꾸려면 `cp docker/.env.example docker/.env` 후 값을 고칩니다(`.env` 는 커밋하지 않습니다).
`make up` 뒤 IDE나 `./gradlew bootRun` 으로 앱을 띄울 때는 `POSTGRES_PASSWORD=resilientpos` 를 직접 줍니다. 애플리케이션 설정은 이 값에 기본값을 두지 않습니다.
수집 경로는 [관측성](docs/wiki/observability.md)에 있습니다.

### 자동완성 (선택)

`make` 탭 자동완성을 위 명령 목록으로만 제한하려면 `~/.zshrc` 에 다음을 추가하고 새 셸을 엽니다.

```sh
source "<저장소 경로>/scripts/make-completion.zsh"
```

현재 디렉터리의 Makefile 에서 `## ` 주석이 달린 타깃만 완성하며, 해당 컨벤션이 없는 다른
프로젝트에서는 기본 동작으로 폴백합니다.

## 개발 방법론

### SDD — Spec-Driven Development

ResilientPOS는 **PR 단위로 명세를 먼저 작성하고 구현·검증하는 SDD**를 적용합니다.
개발 의도와 완료 기준을 명세에 남기고, 구현 중 결정이 바뀌면 명세와 프로젝트 지식을 함께 갱신합니다.

1. **명세**: 개발 전에 `docs/spec/yyyy-mm-dd-slug.md`에 목적, 범위, 제외 항목, 인수 조건과 검증 방법을 작성하고 독립 커밋으로 기록합니다.
2. **구현**: 한 PR은 하나의 목표로 제한합니다. 범위가 커지면 후속 PR과 새 spec으로 분리합니다.
3. **검증**: 인수 조건에 따라 관련 테스트와 로컬 PR 검사를 실행하고 결과를 spec에 기록합니다.
4. **지식 갱신**: 요구사항·설계 변경은 `docs/wiki` 본문에 반영하고, 그 근거는 해당 PR spec에 남깁니다.

별도 plan 문서 대신 spec에 범위 조정과 개발 이력을 남깁니다. 상세 절차와 PR 분할 기준은 [개발 규칙](docs/wiki/development-workflow.md), 작성 형식은 [spec 템플릿](docs/spec-template.md)에서 관리합니다.

### TDD — Test-Driven Development

SDD의 인수 조건을 **실패하는 테스트 → 최소 구현 → 리팩터링**으로 구체화합니다. 비즈니스·도메인은 단위 테스트를 기본으로 하고, 트랜잭션·이벤트 연결은 필요한 통합 테스트로 검증합니다. Repository 자체 테스트는 대상에서 제외합니다.
Controller는 MockMvc와 Spring REST Docs로 HTTP 계약을 검증하며, fixture 객체는 Fixture Monkey로 생성하고 경계값과 문서 예시는 고정합니다. 두 라이브러리의 실제 의존성 설치는 최초 기능 적용 PR에서 진행합니다.
저장소의 `resilientpos-tdd` 스킬과 `tdd-driver` 에이전트가 이 과정을 주도하고 결과를 spec에 남깁니다. [테스트 전략](docs/wiki/testing-strategy.md)

### 설계와 검증 방식

- **도메인 중심 설계**: 업무 경계와 데이터 소유권을 기준으로 모듈을 나누고, Aggregate / Value Object에 불변식을 둡니다. 용어 정의와 유비쿼터스 언어 용어집은 [도메인 모델](docs/wiki/domain-model.md)에 있습니다.
- **모듈형 모놀리스**: 단일 배포 구조에서 모듈 경계를 먼저 검증하고, 독립 배포 필요성이 확인될 때 분리합니다.
- **CQRS**: 쓰기 명령과 읽기 조회의 계약을 분리하는 설계 방향을 따릅니다.
- **실패 시나리오 기반 검증**: 정상 흐름과 함께 중복 요청, 응답 유실, 오프라인 복구, 재처리 등 해당 변경에 관련된 실패 조건을 확인합니다.
- **LLM 공통 문서화**: Claude·Codex·Gemini가 같은 spec과 wiki를 참조하며, 상세 문서는 작업에 필요한 범위에서 읽습니다.

아래 원칙은 도메인 구현 시 적용할 설계 기준입니다. 초기 코드에 모두 구현되었다는 의미는 아닙니다.

## Engineering Principles

- **정합성/복구 가능성/관측 가능성 우선**: 기능보다 데이터 정합성과 장애 복구, 운영 가시성을 우선합니다.
- **모듈 경계 준수 (Spring Modulith)**: `order`, `payment`, `sync`, `reconciliation`, `fulfillment` 경계를 지키며 다른 모듈의 internal 코드/DB를 직접 접근하지 않습니다.
- **역할 분리**:
  - Facade: use case + transaction 조합
  - Aggregate / Value Object / Policy: 상태 전이·불변식·금액 계산·검증 소유
  - Service: 도메인 객체 조율만 담당 (DTO 가공/규칙 중복 금지)
- **CQRS 규약**:
  - 쓰기: 모듈 이름공간 아래 use case별 커맨드와 그 안의 결과 (`OrderCommand.Create -> OrderCommand.Create.Result`)
  - 읽기: `*Query`/`*Criteria -> *View` (쓰기와 같은 모양을 따를지는 첫 조회 PR에서 정한다)
  - 저장소: JPA는 aggregate write, Kotlin JDSL은 DTO projection read
- **데이터 정본 원칙**:
  - 주문/결제 정합성 정본: PostgreSQL
  - Redis: cache, rate limit, 짧은 중복 요청 완화 용도만 사용
  - Redis를 payment idempotency/sync watermark의 정본으로 사용 금지
- **비동기 처리 원칙**:
  - at-least-once 전제
  - 모든 consumer 멱등 보장
  - Event Publication Registry 또는 outbox/inbox 사용
  - Kafka는 독립 consumer/replay/확장 요구가 실제로 생길 때만 도입
- **외부 결제 연동 원칙**:
  - 개인 개발자 test-only 환경으로 제한
  - timeout은 결제 실패 확정이 아님
  - `UNKNOWN -> provider query -> reconciliation` 경로로 수렴
  - 가짜 성공 fallback 금지
- **변경 시 필수 고려사항**:
  - trace, structured log, metric, alert, runbook
  - replay/reconciliation 경로
- **완료 조건**:
  - 정상 흐름 + 실패 시나리오 검증 포함
  - duplicate, timeout, offline sync gap, event replay, Redis/Kafka 장애 테스트 포함

## 문서

- [공통 wiki](docs/wiki/README.md): 현재 지식과 주제별 문서
- [개발 규칙](docs/wiki/development-workflow.md): SDD 절차, PR 범위와 로컬 검증
- [Spec 이력](docs/spec): PR별 명세와 검증 기록
- [요구사항 원문](docs/references/README.md): 제공된 기획·설계 자료
