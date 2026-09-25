# PR spec: Prometheus metric과 JSON 구조화 로그로 LGTM 연결 기반을 만든다

## 목적
나중에 Grafana LGTM(Loki·Grafana·Tempo·Mimir)을 붙일 때 애플리케이션 코드를 고치지 않고 수집 설정만으로 연결되게 한다. 지금은 Actuator가 있지만 metric을 가져갈 엔드포인트가 없고, 로그는 평문이라 Loki에서 필드로 검색할 수 없다.

기대 결과: Prometheus가 `/actuator/prometheus`를 scrape할 수 있고, 환경 변수 하나로 stdout 로그가 JSON이 된다.

## 범위
소유 모듈은 `platform`에 가깝지만 코드는 없고 빌드·설정만 바뀐다.

**metric.** `io.micrometer:micrometer-registry-prometheus`를 `runtimeOnly`로 추가한다. 버전은 Spring Boot BOM이 관리한다. `management.endpoints.web.exposure.include`를 `health,prometheus`로 둔다. 같은 포트로 노출하며, 다른 Actuator 엔드포인트는 열지 않는다.

**공통 태그.** `management.metrics.tags.application`에 `${spring.application.name}`을 준다. 여러 서비스가 한 Prometheus에 모일 때 대시보드가 서비스를 가르는 기준이다.

**구조화 로그.** Spring Boot 내장 `logging.structured.format.console`을 쓴다. 기본값은 지금처럼 평문이고, 배포 환경에서 `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`를 주면 JSON이 된다. yaml에는 값을 적지 않고 주석으로 사용법만 남긴다. 로컬 개발과 테스트 출력은 읽기 쉽게 둔다. ECS를 고른 이유는 Loki가 어떤 JSON이든 읽고, ECS가 필드 이름이 표준화되어 있어서다.

계약: HTTP API와 응답 형식은 바뀌지 않는다. `/actuator/prometheus`가 새로 생긴다.

복구: 해당 없음. 상태를 바꾸지 않는다.

관측성: 이 PR이 그 기반이다. 요청 수·지연·오류율(`http_server_requests_seconds_*`), JVM, Hikari 풀 metric이 Prometheus 형식으로 나간다.

## 제외
- trace, OpenTelemetry, OTLP exporter. 샘플링과 Collector 장애 격리 결정이 따로 필요하다.
- MDC와 correlation id. trace를 들일 때 traceId와 함께 정한다.
- 도메인 metric(`payment_unknown_total` 등). 해당 기능 PR이 각자 더한다.
- docker compose와 로컬 LGTM 스택.
- metric 접근 제한(인증, management 포트 분리). 배포 형태가 정해질 때 ingress나 포트로 정한다.

## 관련 문서
없음

## 변경 경로
- build.gradle.kts
- src/main/resources/application.yaml
- src/test/kotlin/com/resilientpos/platform/ObservabilityConfigurationTest.kt
- docs/wiki/observability.md
- docs/wiki/README.md
- README.md
- docs/spec/2026-09-25-prometheus-structured-logging.md

## 인수 조건
1. `GET /actuator/prometheus`가 200과 Prometheus text 형식을 돌려준다.
2. 응답에 요청 metric(`http_server_requests_seconds`)이 `application="ResilientPOS"` 태그와 함께 있다.
3. `health`와 `prometheus` 외의 Actuator 엔드포인트(예: `/actuator/env`)는 404다.
4. 환경 변수 없이 실행하면 로그가 평문이고, `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`를 주면 한 줄이 JSON 객체 하나다.
5. 기존 테스트가 모두 통과한다.

## 검증
- 인수 1~3: `./gradlew test --tests 'com.resilientpos.platform.ObservabilityConfigurationTest'`. Docker가 필요하다.
- 인수 4: 같은 명령을 `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`를 주고 한 번 더 실행해 테스트 출력이 JSON인지 눈으로 확인하고 결과 절에 적는다. 로깅 시스템을 테스트 안에서 다시 초기화하면 캐시된 다른 컨텍스트의 로그 설정까지 흔들리므로 자동 테스트로 만들지 않는다.
- 인수 5: `./gradlew test`.
- `python3 scripts/check_pr.py --base origin/main --worktree`.

## Wiki 영향
- 새 [관측성](../wiki/observability.md) 페이지에 metric 엔드포인트, 공통 태그, JSON 로그 토글, LGTM을 붙일 때 필요한 수집 설정(Prometheus scrape 대상, Alloy가 stdout JSON을 읽는다는 전제)을 적는다.
- [wiki 목차](../wiki/README.md)에 그 페이지를 한 줄 건다.
- README 기술 표의 운영 상태 확인 행에 Prometheus metric을 더한다.
- 볼트의 현재 상태 문서는 저장소 밖이라 이 PR 머지 뒤 따로 고친다.
- 요구사항 변화: 없다. `docs/references/02_System_Design.md`의 7절이 정한 파이프라인 중 metric과 로그의 첫 단계다.

## 분할 계획
설정 10줄 안팎, 테스트 40줄 안팎, 문서 40줄 안팎이다. 분할하지 않는다.

## TDD 검증 기록
업무 동작 변경이 아니라 설정 변경이다. 대신 설정이 실제로 적용되는지 보는 통합 테스트로 RED를 관측한다. 의존성·설정을 넣기 전에 테스트를 먼저 돌려 실패를 기록한다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 1~3, `ObservabilityConfigurationTest` | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
