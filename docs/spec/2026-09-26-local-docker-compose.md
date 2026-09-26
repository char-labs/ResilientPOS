# PR spec: 로컬 개발용 docker compose로 PostgreSQL·앱·LGTM을 띄운다

## 목적
로컬에서 앱을 돌리려면 PostgreSQL을 손으로 준비해야 하고, #56·#57이 만든 metric과 ECS 로그를 실제로 수집해 볼 곳이 없다. 외부 인프라를 끼는 작업의 첫 단계로 저장소에 compose와 Dockerfile을 둔다.

기대 결과: `make up` 한 번이면 IDE에서 띄운 앱이 붙을 PostgreSQL이 뜨고, profile을 켜면 앱 컨테이너와 LGTM까지 떠서 Grafana에서 앱의 metric과 로그를 조회할 수 있다.

## 범위
운영 코드와 테스트는 바뀌지 않는다. 저장소 루트의 인프라 파일과 문서만 더한다.

**서비스와 profile.** Docker 관련 파일은 모두 `docker/` 아래에 두어 애플리케이션 파일과 경계를 나눈다. compose는 `docker/docker-compose.yaml` 하나이고 명령은 `docker compose -f docker/docker-compose.yaml ...`이다.
- 기본(profile 없음): `postgres`. `postgres:18-alpine`으로 메이저를 고정한다. 테스트(Testcontainers)와 같은 메이저이고, `latest`로 두면 다음 메이저가 나오는 날 named volume에 남은 데이터 디렉터리를 읽지 못해 기동하지 않는다. 5432를 호스트에 연다. 데이터는 named volume에 둔다. healthcheck는 `pg_isready`다.
- `app` profile: `app`. `docker/Dockerfile`로 빌드한다. 빌드 컨텍스트는 `gradlew`와 `src`가 필요하므로 저장소 루트다. `postgres`가 healthy가 된 뒤 뜨고 `POSTGRES_URL`은 compose 네트워크의 `postgres`를 가리킨다. `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`를 줘서 stdout이 ECS JSON이 된다. 8080을 호스트에 연다.
- `obs` profile: `alloy`와 `lgtm`(`grafana/otel-lgtm`). Grafana는 3000을 호스트에 연다.
- Kafka·Redis는 넣지 않는다. 빌드 의존성에 없어 앱이 붙지 않으므로 검증할 방법이 없다. 의존성을 더하는 기능 PR이 compose 서비스도 함께 더한다.

**수집 경로.** [관측성](../wiki/observability.md)의 "LGTM을 붙일 때"에 정한 방식을 로컬에서 그대로 쓴다. 설정은 `docker/alloy/config.alloy` 하나다.
- metric: Alloy `prometheus.scrape`가 `app:8080/actuator/prometheus`를 scrape하고 `prometheus.remote_write`로 `lgtm`의 Prometheus에 보낸다. 운영의 Mimir 자리를 로컬에서는 otel-lgtm 안의 Prometheus가 맡는다.
- 로그: Alloy가 docker socket을 읽기 전용으로 마운트해 `app` 컨테이너의 stdout만 읽고, ECS 필드 `service.name`·`log.level`을 `service_name`·`level` label로 올려 `lgtm`의 Loki로 보낸다. label은 이 둘뿐이다.
- 관측은 앱 컨테이너를 전제로 한다. `obs`는 `app`과 함께 켠다(`docker compose --profile app --profile obs up`).

**비밀번호.** compose는 `${POSTGRES_PASSWORD:-resilientpos}`를 쓴다. 로컬·포트폴리오 용도라 기본값으로 바로 뜨게 하고, 바꾸고 싶으면 gitignore된 `docker/.env`에 적는다(compose는 compose 파일이 있는 디렉터리의 `.env`를 읽는다). 변수 목록은 커밋하는 `docker/.env.example`에 둔다. `trust` 인증은 쓰지 않는다. `application.yaml`은 바꾸지 않으므로 호스트에서 앱을 띄울 때는 `POSTGRES_PASSWORD=resilientpos`를 직접 준다.

**Dockerfile.** multi-stage다. 두 스테이지 모두 `amazoncorretto:25-alpine`이다. 빌드 스테이지에서 `./gradlew bootJar`를 돌리고 실행 스테이지로 jar만 복사한다. Corretto는 25에 JRE 전용 이미지가 없어서 alpine JDK로 실행한다.

**JVM 메모리.** 앱 컨테이너에 메모리 한도 1GB(`mem_limit`)를 두고, JVM은 `-XX:+UseG1GC -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`로 띄운다. 한도가 없으면 힙 비율이 Docker VM 전체 메모리를 기준으로 잡힌다. 1GB·2 CPU에서는 JVM이 server-class가 아니라고 보고 SerialGC와 힙 256MB(25%)를 고르므로 G1과 힙 비율을 명시한다. OOM이 나면 반쯤 살아 있는 상태로 두지 않고 프로세스를 끝낸다. 호스트 JDK가 없어도 `--build`로 끝난다. `docker/Dockerfile.dockerignore`로 `build/`·`.gradle/`·`.git/` 등을 컨텍스트에서 뺀다. BuildKit은 컨텍스트 루트의 `.dockerignore` 대신 Dockerfile 옆의 이 파일을 읽으므로 루트에 Docker 파일을 두지 않는다.

**이미지 버전.** 사용자 결정으로 `postgres`를 뺀 이미지(Alloy·otel-lgtm·JDK/JRE)는 `latest` 계열 태그를 허용한다. 로컬 개발 전용이라 재현성보다 갱신 비용을 줄인다.

**Makefile.** `up`(PostgreSQL만)과 `down` 두 타깃을 더한다. profile 조합은 README에 적고 `docker compose`를 직접 쓴다.

계약·복구·관측성: 앱의 API와 설정은 바뀌지 않는다. 상태를 바꾸지 않는다. 이 PR이 로컬 관측 경로를 만든다.

## 제외
- Kafka·Redis compose 서비스. 해당 기능 PR로 미룬다.
- `spring-boot-docker-compose` 연동. 의존성이 늘고 앱 컨테이너와 자동 기동 대상이 겹친다.
- `application.yaml`의 `POSTGRES_PASSWORD` 기본값. 변수를 빠뜨린 인스턴스가 뜨지 않게 한 기존 결정을 유지한다.
- Grafana 대시보드·알림 규칙 provisioning, trace(Tempo는 otel-lgtm에 들어 있지만 앱이 보내지 않는다).
- 호스트에서 띄운 앱을 Alloy가 scrape하는 경로.
- 테스트 구성 변경. 테스트는 계속 Testcontainers만 쓴다.
- 운영 배포용 이미지 최적화(layered jar, 비루트 사용자 강제, digest 고정).

## 관련 문서
- [관측성](../wiki/observability.md): 수집 방식과 label 규칙의 정본이다.
- [시스템 설계 7절](../references/02_System_Design.md#7-nfr-and-observability): Prometheus·Loki·Grafana 파이프라인 방향이다.

## 변경 경로
- docker/docker-compose.yaml
- docker/Dockerfile
- docker/Dockerfile.dockerignore
- docker/.env.example
- .gitignore
- docker/alloy/config.alloy
- Makefile
- README.md
- docs/wiki/observability.md
- docs/spec/2026-09-26-local-docker-compose.md

## 인수 조건
1. `make up`이 PostgreSQL만 띄우고 healthy가 된다. 그 상태에서 호스트의 `POSTGRES_PASSWORD=resilientpos ./gradlew bootRun`이 Flyway 마이그레이션을 마치고 기동한다.
2. `.env` 없이 `docker compose --profile app up --build`가 앱 이미지를 빌드하고, 앱이 컨테이너의 PostgreSQL에 붙어 `GET localhost:8080/actuator/health`가 `UP`을 돌려준다. 앱 stdout은 ECS JSON이다.
3. `docker compose --profile app --profile obs up`에서 Grafana(localhost:3000)의 Prometheus 데이터소스로 `http_server_requests_seconds_count{application="ResilientPOS"}`가 조회된다.
4. 같은 상태에서 Loki 데이터소스로 `{service_name="ResilientPOS"}`가 앱 로그를 돌려주고, label은 `service_name`·`level` 두 개만 붙는다(Loki가 스스로 붙이는 label은 제외).
5. `make down`이 모든 profile의 컨테이너를 내린다.
6. `.env`는 git이 추적하지 않는다.
7. `./gradlew test`가 compose와 무관하게 통과한다.

## 검증
- 인수 1~5: 실제로 각 명령을 실행하고 `docker compose ps`, `curl`, Grafana 데이터소스 proxy API(`/api/datasources/proxy/uid/...`) 또는 Prometheus·Loki HTTP API 조회 결과를 결과 절에 적는다.
- 인수 6: `.env`를 만든 뒤 `git status --short`에 나오지 않는지 본다.
- 인수 7: `./gradlew test`. Docker가 필요하다.
- `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전에는 `--worktree` 없이 한 번 더.

## Wiki 영향
- [관측성](../wiki/observability.md): "아직 없는 것"에서 로컬 LGTM 스택을 빼고, 로컬에서 수집 경로를 띄우는 방법과 운영과의 차이(Mimir 자리를 Prometheus가 맡음)를 적는다.
- README: 로컬 실행 방법(compose profile, 비밀번호 기본값, make 타깃)을 적는다.
- 볼트의 현재 상태 문서는 저장소 밖이라 머지 뒤 따로 고친다.
- 요구사항 변화: 없다. 기존 관측성 wiki가 정한 수집 방식을 로컬에서 구현할 뿐이다.

## 분할 계획
compose 80줄 안팎, Alloy 설정 40줄 안팎, Dockerfile 15줄 안팎, 문서 50줄 안팎이다. 분할하지 않는다.

## TDD 검증 기록
업무 동작 변경이 아니라 로컬 인프라 파일 추가라 적용하지 않는다. 대신 인수 조건마다 실제 compose 실행 결과를 결과 절에 남긴다.

## 범위 조정
- 첫 push 뒤 사용자 요청으로 Docker 관련 파일을 `docker/` 디렉터리로 옮겼다. `compose.yaml`은 `docker/docker-compose.yaml`로 이름을 바꿨고, `Dockerfile`·`.env.example`도 옮겼다. 루트 `.dockerignore`는 `docker/Dockerfile.dockerignore`로 바꿨다. 명령에 `-f docker/docker-compose.yaml`이 붙으므로 Makefile과 README도 함께 고친다. 인수 조건은 경로만 바뀌고 내용은 같으며, 옮긴 뒤 다시 실행해 결과 절에 적는다.
- 사용자 요청으로 베이스 이미지를 `eclipse-temurin`에서 Amazon Corretto로 바꾼다. Temurin은 공식 이미지의 기본값이라 골랐을 뿐 이 프로젝트에 맞춘 근거가 없었다. 빌드는 `amazoncorretto:25`, 실행은 `amazoncorretto:25-headless`로 두어 두 스테이지의 OS(Amazon Linux 2023)를 맞춘다. 실행 이미지는 484MB로 Temurin JRE(356MB)보다 크고 `25-alpine`(372MB)보다도 크지만, 로컬 전용이라 크기보다 두 스테이지의 libc 일치를 택했다. 인수 2를 다시 실행해 결과 절에 적는다.
- 사용자 요청으로 실행·빌드 이미지를 `amazoncorretto:25-alpine`으로 바꾸고, ZGC를 포함한 메모리 설정을 조사해 개선한다. 후보(기본값, G1, G1+Compact Object Headers, ZGC, ZGC+Compact Object Headers)를 같은 조건(1GB·2 CPU, 주문 생성+checkout 3000요청, 동시 16)으로 측정해 고른다. 측정 결과와 채택하지 않은 이유는 결과 절에 적는다.
- 사용자 요청으로 가상 스레드(`spring.threads.virtual.enabled`)를 켤지 같은 방식으로 측정했다. 켜지 않기로 했으므로 애플리케이션 설정과 코드는 바뀌지 않고, 측정과 병목 원인만 결과 절에 기록한다. 동작이 바뀌지 않으므로 wiki 본문은 고치지 않는다. 저장소 밖에 있는 볼트의 현재 상태 문서는 머지 뒤 이 결과를 옮긴다.

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.

- 인수 1: `make up`이 `postgres:18-alpine`을 띄우고 `--wait`로 healthy까지 기다렸다. 호스트의 `POSTGRES_PASSWORD=resilientpos ./gradlew bootRun`이 V001~V007 마이그레이션 7개를 적용하고 기동했으며 `/actuator/health`가 `UP`이었다.
- 인수 2: `.env`가 없는 상태에서 `docker compose --profile app --profile obs up -d --build --wait`로 이미지를 빌드하고 띄웠다. 앱 컨테이너의 `/actuator/health`가 `UP`이었고 stdout은 `{"@timestamp":...,"log":{"level":"INFO",...},"service":{"name":"ResilientPOS",...},...}` 형태의 ECS JSON이었다.
- 인수 3: Grafana 데이터소스 proxy(`uid/prometheus`)로 `sum by (uri,status) (http_server_requests_seconds_count{application="ResilientPOS"})`를 조회하니 `/actuator/health` 200, `/**` 404, `/actuator/prometheus` 200 세 series가 나왔다.
- 인수 4: Loki의 `labels` 응답이 `["level","service_name"]` 두 개였다. `{service_name="ResilientPOS"} | json | log_logger="com.resilientpos.platform.ApiControllerAdvice"`가 앱 로그를 돌려줬다. 다만 kotlin-logging이 logback을 거치지 않고 기동 시 평문 한 줄(`kotlin-logging: initializing... active logger factory: Slf4jLoggerFactory`)을 찍어서, 이 줄은 label 없이 들어가 Loki가 `service_name="unknown_service"`로 둔다. 한 줄뿐이라 그대로 두고 [관측성](../wiki/observability.md)에 적었다.
- 인수 5: `make down`(`docker compose --profile '*' down`) 뒤 이 프로젝트 label이 붙은 컨테이너가 0개였다. named volume은 남긴다.
- 인수 6: `.env`를 만든 뒤 `git status --short`에 나오지 않았다.
- 인수 7: compose를 내린 상태에서 `./gradlew test` 232개 중 실패 0.
- JDK 이미지는 `latest`가 아니라 메이저 25 태그다. Gradle toolchain이 25를 요구하므로 메이저는 맞추고 패치만 따라가는 떠다니는 태그로 뒀다(처음에는 `eclipse-temurin`, 범위 조정 뒤 Corretto).
- compose 프로젝트 이름을 `resilientpos`로 고정했다. Alloy가 프로젝트·서비스 label로 앱 컨테이너를 고르는데, 이름을 고정하지 않으면 디렉터리 이름(worktree마다 다름)이 프로젝트 이름이 된다.
- `python3 scripts/check_pr.py --base origin/main --worktree`: PASS(일반 10개/253줄).
- 범위 조정 뒤 재검증(`docker/`로 옮긴 경로, `make up`·`make down`은 `-f docker/docker-compose.yaml`을 쓴다):
  - `docker/Dockerfile.dockerignore` 적용: build 스테이지 이미지의 `/src`에 `docs`·`.git`이 없고, 만들어 둔 `docker/.env`도 없었다. 그래서 `.env` 패턴을 `**/.env`로 바꿨다.
  - 세 profile을 모두 띄우자 Grafana `/api/health`가 `"database": "ok"`(13.2.2)를 돌려줬다.
  - API: `POST /v1/orders`가 201과 `OPEN` 주문을 돌려줬고, 같은 `clientOrderId`로 다시 보내자 같은 `orderId`가 왔다. `POST /v1/orders/{orderId}/checkout`은 200, `CHECKOUT_REQUESTED`, `amount` 9000을 돌려줬다.
  - DB(psql): 마이그레이션 7개가 성공했다. `t_order`가 `PAID`로 전이됐고 `t_payment_attempt`·`t_fulfillment`에 한 행씩 생겼다. `event_publication` 4행이 모두 `COMPLETED`였다.
  - Grafana: Prometheus에서 `/v1/orders` 201 2건과 checkout 200 1건이 조회됐다. Loki label은 `level`·`service_name`뿐이었다.
  - `make down` 뒤 컨테이너 0개, `docker/.env`는 git이 추적하지 않았다. `./gradlew test --rerun-tasks` 232개 중 실패 0.
- Corretto 전환 뒤 재검증:
  - `amazoncorretto:25` 최소 이미지에 `xargs`가 없어 `gradlew`가 `xargs is not available`로 실패했다. 빌드 스테이지에만 `dnf install -y findutils`를 더했다. 실행 스테이지는 `java -jar`만 쓰므로 넣지 않았다.
  - `--no-cache` 빌드가 성공했고 앱 이미지는 552MB다. 컨테이너의 `java -version`은 `Corretto-25.0.4.8.1`이었다.
  - 세 profile을 띄워 health `UP`, Grafana `database: ok`를 확인했다. `POST /v1/orders` 201과 checkout 200을 받은 뒤 주문이 `PAID`로 전이됐고, 이 주문의 발행 기록 4개가 모두 `COMPLETED`였다. Prometheus에서 checkout metric이 조회됐고 Loki label은 `level`·`service_name`뿐이었다.
- alpine·JVM 메모리 조정 뒤:
  - 측정 조건: 컨테이너 1GB·2 CPU, 줄 20개짜리 주문 생성과 checkout을 300쌍 워밍업한 뒤 1500쌍(3000요청)을 동시 16으로 보냈다. RSS는 `docker stats`, GC는 `/actuator/prometheus`의 `jvm_gc_pause_seconds_*`로 쟀다. 한 번씩 잰 값이라 편차가 있어 G1 두 조합은 두 번 쟀다.

    | 조합 | RSS 유휴→부하 후 | 처리량 | p99 | GC 최대 멈춤 |
    |---|---|---|---|---|
    | 옵션 없음(JVM이 SerialGC, 힙 256MB 선택) | 242→385MB | 438 rps | 109ms | 86ms |
    | G1, 힙 75% (채택, 2회) | 311~328→401~463MB | 490~560 rps | 92~96ms | 14ms |
    | G1, 힙 75%, Compact Object Headers (2회) | 300→386~430MB | 439~472 rps | 105~107ms | 84~94ms |
    | ZGC, 힙 75% | 728→984MB | 389 rps | 176ms | 1ms |
    | ZGC, 힙 75%, Compact Object Headers | 814→934MB | 383 rps | 104ms | 1ms |

  - ZGC는 멈춤이 1ms였지만 RSS가 1GB 한도에 닿았고 처리량과 p99가 나빴다. 이 앱의 지연은 GC 멈춤보다 DB 왕복이 좌우하고, 힙이 작아 ZGC의 메모리 여유분 부담이 더 크다.
  - Compact Object Headers(JDK 25 정식, 기본값 꺼짐)는 유휴 RSS를 10~25MB 줄였지만 두 번 모두 G1 최대 멈춤이 84~94ms로 늘고 처리량이 낮았다. 살아 있는 객체가 100MB 안팎이라 얻을 것이 적어서 넣지 않았다. 힙이 커지면 다시 잰다.
  - `amazoncorretto:25-alpine`은 busybox에 `xargs`가 있어서 `findutils` 설치를 뺐다. 앱 이미지는 552MB에서 440MB로 줄었다.
  - 적용 뒤 컨테이너의 `jcmd VM.flags`에서 `UseG1GC`, `MaxRAMPercentage=75`, `MaxHeapSize=805306368`, `ExitOnOutOfMemoryError`를 확인했다. 유휴 RSS는 316MiB/1GiB였다. 세 profile에서 health `UP`, Grafana `database: ok`, `POST /v1/orders` 201과 checkout 200, 주문 `PAID`와 발행 기록 4개 `COMPLETED`, Prometheus의 `G1 Young Generation` GC metric, Loki label `level`·`service_name`을 확인했다.
- 가상 스레드 검토(켜지 않음):
  - 현재 상태: `spring.threads.virtual.enabled`가 없어서 꺼져 있다.
  - 측정: 위와 같은 컨테이너(1GB·2 CPU, G1·힙 75%)에서 주문 생성과 checkout 3000요청을 보내고, 이번 실행이 만든 발행 기록만 셌다.

    | 동시 요청 | 설정 | 처리량 | p50 | p99 | 부하 종료 때 밀린 이벤트 | 실패한 발행 기록 |
    |---|---|---|---|---|---|---|
    | 16 | 끔 | 643 rps | 11ms | 86ms | 1,448건, 4초 뒤 0 | 0 |
    | 16 | 켬 | 365 rps | 35ms | 152ms | 0 | 0 |
    | 64 | 끔 | 658 rps | 94ms | 297ms | 1,501건, 4초 뒤 0 | 0 |
    | 64 | 켬 | 312 rps | 133ms | 1,014ms | 0 | 0 |
    | 16·64 | 켬, `spring.task.execution.simple.concurrency-limit=8` | 요청이 500으로 실패해 측정 중단 | | | | |

  - 병목은 스레드 수가 아니라 Hikari 커넥션 10개다. 요청과 리스너가 모두 트랜잭션 하나마다 커넥션 하나를 쥔다. 플랫폼 스레드일 때도 Tomcat 요청 스레드(최대 200)가 이미 커넥션보다 많으므로, 스레드를 늘려도 DB가 한 번에 처리하는 양은 늘지 않는다.
  - 켜면 느려지는 이유: 이 설정은 Tomcat 요청 스레드뿐 아니라 Spring Boot 기본 task executor도 바꾼다. Modulith의 커밋 뒤 비동기 리스너가 여기서 돈다. 꺼져 있을 때 이 executor는 코어 8의 `ThreadPoolTaskExecutor`라 리스너는 최대 8개만 돌고 나머지는 메모리 큐에서 기다린다. 요청은 남은 커넥션을 쓰고, 이벤트 처리는 뒤로 밀린다(밀린 1,448건). 켜면 상한이 없는 가상 스레드 `SimpleAsyncTaskExecutor`가 되어, checkout 하나가 만드는 리스너 4개가 모두 곧바로 커넥션을 달라고 줄을 선다. 해야 할 일의 총량은 같고 순서만 바뀐다. 이벤트는 밀리지 않게 되지만(밀린 이벤트 0), 요청 트랜잭션이 리스너 트랜잭션 뒤에서 커넥션을 기다린다. 그래서 p50이 11ms에서 35ms로 늘고 처리량이 43~53% 줄었다.
  - 상한을 다시 걸면 실패하는 이유: `SimpleAsyncTaskExecutor`는 상한에 닿으면 기본값(`rejectTasksWhenLimitReached=false`)에서 작업을 넘기는 스레드를 `await()`로 막는다. 커밋 뒤 리스너를 넘기는 스레드는 요청 스레드다. Spring TX의 `processCommit`은 커밋 뒤 동기화 콜백(`triggerAfterCommit`·`triggerAfterCompletion`, 리스너 전달이 여기서 일어난다)을 부른 다음에야 `cleanupAfterCompletion`에서 커넥션을 반납한다. 그래서 요청 스레드가 커넥션을 쥔 채 리스너 자리를 기다리고, 자리를 차지한 리스너는 커넥션을 받지 못한다. 로그에 `HikariPool-1 - Connection is not available, request timed out after 3009ms (total=10, active=10, idle=0, waiting=60)`가 찍혔고 요청이 500으로 끝났다. 이 교착 경로는 spring-tx 7.0.9와 spring-core 소스로 확인한 추론이다. 스레드 덤프로 직접 확인하지는 않았다.
  - 다시 볼 조건: 실제 PG HTTP 호출처럼 DB 커넥션을 쥐지 않고 오래 기다리는 I/O가 생길 때다. 그때는 리스너 전용 executor를 두고 상한은 호출자를 막지 않는 큐로 건다. 커넥션 풀 크기와 PG 호출이 트랜잭션 밖에서 일어나는지도 함께 정한다.
  - 함께 드러난 것: 꺼진 상태에서 부하가 끝난 시점에 메모리 큐에 밀린 이벤트는 컨테이너가 내려가면 처리되지 않는다. 측정 컨테이너를 내릴 때 `PUBLISHED` 12,562건과 `PROCESSING` 43건이 미완료로 남았다. 현재 상태 문서의 알려진 빈틈(종료할 때 리스너를 기다리지 않음, 재전달 절차 없음)이 재현된 것이다. 측정 데이터가 섞인 로컬 볼륨은 `docker compose -f docker/docker-compose.yaml --profile '*' down -v`로 지웠다.
