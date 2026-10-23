# PR spec: PR 검사 시간을 3분 안으로 줄인다

## 목적
PR 검사(`.github/workflows/pr-checks.yml`) 한 번이 5분 넘게 걸린다. 이슈는 없고 사용자가 직접 요청했다.

기준선은 CI 실행 39736986219(feature/alert-state-gauges PR)다. 전체 5분 22초 가운데 준비와 캐시 복원이 약 9초, Gradle daemon 기동과 설정이 16초, `compileKotlin`·`compileTestKotlin`이 약 33초, test가 4분 11초다. Flyway version gate(5초)는 빌드가 끝난 뒤에야 돈다. 최근 PR 실행 8건은 4분 42초~5분 53초로 1분 넘게 흔들린다.

로컬 기준선(k6 worktree의 `build/test-results`, 2026-10-22)은 130개 클래스, 868개 테스트, 합계 251초이고 한 JVM에서 직렬로 돈다. Spring 컨텍스트 기동이 57번이고 "Started … in N seconds" 로그만 합해도 70초다(종료 시간 제외). 오래 걸리는 클래스는 `ResilientPosApplicationTests` 21초(테스트 1개), `KafkaRetryIntegrationTest` 18초, `DeadLetterRedriveIntegrationTest` 17초다.

기대 결과: 이 PR의 같은 커밋 PR 실행 3번의 중앙값이 3분 이하다. 테스트가 검증하는 동작은 그대로다.

## 범위
빌드·CI 설정과 테스트 기반만 바꾼다. 업무 동작, 운영 설정(`src/main/**`)은 바꾸지 않는다.

### 결정
**1. 세 범위를 한 PR로 낸다(사용자 결정).** Flyway gate 병렬화, Gradle build cache, test task 시간 단축이다.

**2. 성공 기준(사용자 결정).**
- 이 PR을 push해서 나온 PR 실행을 같은 커밋으로 3번(첫 실행과 재실행 2번) 돌려, workflow run 전체 시간(생성부터 끝까지)의 중앙값이 3분 이하면 달성이다. 3번 모두 성공해야 한다.
- 로컬은 목표 없이 바꾸기 전과 뒤의 수치를 남긴다.
- 고정 비용(준비·daemon·컴파일)이 약 1분이라 test가 줄어도 그 아래로는 내려가지 않는다.

**3. Flyway version gate를 빌드와 병렬로 돌린다.** `migration_version_check`의 `needs: build`를 지운다. 이 gate는 빌드 산출물을 쓰지 않고 `check_pr.py --migrations-only`만 돌린다. 빌드가 실패해도 gate 결과가 따로 나온다. "컴파일도 안 되는 변경에 나머지 게이트를 돌릴 이유가 없다"는 지금 wiki의 이유는 gate가 5초라 더는 맞지 않는다.

**4. Gradle build cache를 켠다.**
- 저장소 루트에 `gradle.properties`를 두고 `org.gradle.caching=true`를 넣는다. 로컬도 같은 설정을 쓴다.
- compile과 test 모두 캐시한다(Gradle 기본값, 사용자 결정). 입력에 없는 외부 변화(태그가 움직이는 컨테이너 이미지, 간헐적 실패)는 test가 캐시로 건너뛰어지는 실행에서 잡지 못한다. 코드를 바꾼 다음 PR에서 드러난다.
- setup-gradle의 캐시 저장 정책은 기본값(기본 브랜치 job에서만 저장)을 유지한다. 단일 모듈이라 소스가 바뀐 PR에서는 compile·test가 다시 돌아 효과가 작다는 것을 사용자가 알고 넣기로 했다.
- 실제로 어디서 적중하는지(같은 커밋 재실행, main push, 문서만 바꾼 PR)를 setup-gradle 공식 문서와 실측으로 결과 절에 남긴다.

**5. test 시간은 후보를 따로 잰 뒤 합친다(사용자 결정).**
- 기준선에서 컨텍스트 캐시 확대만, fork만 각각 한 번씩 잰다. 그다음 둘을 합쳐 잰다. 후보별 몫과 실패 원인을 나누려는 것이다.
- Kafka 테스트의 대기·재시도 간격은 둘을 합쳐도 CI 3분을 못 맞출 때만 손댄다. 테스트가 검증하는 동작을 약하게 만들지 않는다.

**6. 컨텍스트 캐시 확대 후보.**
- 지금 `src/test/resources/spring.properties`의 `spring.test.context.cache.maxSize=1`은 캐시된 컨텍스트의 Kafka consumer가 같은 group 파티션을 가져가 지금 테스트의 메시지를 처리하는 것을 막으려고 둔 값이다.
- spring-test 7.0.9에는 `spring.test.context.cache.pause`(7.0.3부터, 기본 `on_context_switch`)와 `ContextCache.PauseMode`가 있다. 쉬는 컨텍스트의 Lifecycle 빈(Kafka listener container, #109의 Redis 해제 알림 listener container, 스케줄러)이 멈춰 consumer가 group을 떠나는지 Spring Framework 공식 문서와 sources jar(spring-context·spring-kafka)로 확인한다.
- 멈추면 `maxSize`를 키운다. 값은 실측으로 정한다. 캐시된 컨텍스트는 쉬어도 Hikari 풀(기본 10)을 쥐고 있을 수 있으므로 PostgreSQL 연결 한도(기본 100)와 대조한다.
- 멈추지 않으면 캐시 확대는 하지 않고 이유를 결과 절에 적는다.

**7. fork 수는 고정값이다(사용자 결정).** Gradle `maxParallelForks`를 CI(4 vCPU)에서 2와 3을 재서 빠른 쪽 상수로 둔다. 로컬도 같은 값이다. CI에서 깨진 테스트를 로컬에서 같은 fork 수로 재현하려는 것이다. `PostgresIntegrationTest`가 PostgreSQL·Redis·Kafka 컨테이너를 JVM마다 static으로 띄우고 reuse를 쓰지 않아 fork끼리 DB를 같이 쓰지 않는다.

**8. 바꾼 뒤 다시 검증할 것.**
- 전역 `Tx`·`Lock`: `TxRebindingTestExecutionListener`가 테스트 메서드마다 그 컨텍스트의 러너·잠금 저장소로 다시 묶는다. 캐시를 키우면 쉬는 컨텍스트의 백그라운드 스레드가 다른 컨텍스트의 러너를 쓰지 않는지 본다.
- Kafka consumer group 공유: 캐시를 키운 뒤 쉬는 컨텍스트가 파티션을 쥐지 않는지 실측한다.
- 주기 작업: 테스트는 재전송·결과 불명 확정 작업을 하루 뒤로 미룬다. 쉬는 컨텍스트에서 다른 주기 작업(미처리 DLT gauge 갱신 등)이 도는지 본다.

**9. 순서 의존이 드러나면 원인을 고친다(사용자 결정).**
- 테스트 격리 문제면 이 PR에서 고친다. 운영 코드 결함으로 보이면 멈추고 사용자에게 보고한다.
- 고칠 수 없으면 그 변경(fork 또는 캐시)을 빼고 이유를 결과 절에 적는다.
- `@Disabled`와 테스트 재시도 플러그인은 쓰지 않는다. 로컬 전체 test 3회 연속과 CI 3회가 모두 통과해야 한다.

**10. Kafka consumer·listener 설정을 테스트에서 바꾸면** 공식 문서로 Best Practice를 대조해 근거를 이 spec에 남긴다. 기본값은 sources jar나 기동 로그의 ConsumerConfig values로 확인한다. PR 전에 Modulith·Kafka 키워드 문서(볼트)를 그 문서의 갱신 규칙대로 고친다.

### 범위 조정
**1. 컨텍스트 캐시 확대를 뺀다(2026-10-22, 사용자 결정).** 결정 6의 전제는 맞았다. 쉬는 컨텍스트의 Kafka listener container는 멈추고 consumer는 group을 떠난다(결과 절). 그런데 두 가지 이유로 뺀다.
- 쉬는 컨텍스트에서는 `LettuceConnectionFactory`(SmartLifecycle)도 멈춰 Redis client를 닫는다. #109의 `LockReleaseListener`는 구독 컨테이너를 빈이 아닌 내부 객체로 두고 `ApplicationReadyEvent`에서만 구독한다. 그래서 다시 시작한 컨텍스트에는 해제 알림 구독이 없고 `LockIntegrationTest`가 깨진다. 고치려면 `src/main`을 바꿔야 한다.
- 실험용으로 리스너를 고쳐 fork 2개와 합쳐 쟀더니 test task가 fork 2개만일 때보다 느렸다(155초에서 179초, 각각 한 번 측정).
- 그래서 `maxSize=1`을 그대로 두고 `src/main`은 바꾸지 않는다. 리스너의 재시작 한계는 운영에서 컨텍스트를 멈췄다 다시 시작하지 않으므로 이 PR에서 고치지 않고 사용자에게 보고한다.

**2. 리뷰(Claude·Codex)를 반영해 둘을 더하고 인수 조건을 다듬는다(2026-10-22).**
- REST Docs 스니펫 디렉터리(`build/generated-snippets`)를 Test output으로 선언한다. 선언하지 않으면 test가 캐시에서 올 때 스니펫이 생기지 않는다. 로컬에서 `clean` 뒤 캐시가 적중한 빌드에 이 디렉터리가 없었다. [테스트 전략](../wiki/testing-strategy.md)의 "테스트 실행 후 실제 파일을 확인한다"가 깨진다.
- `OwnerSessionStoreOutageIntegrationTest`의 "아무도 듣지 않는 Redis 포트"를 닫은 임시 포트에서 고정 포트 1로 바꾼다. fork가 병렬로 돌면 다른 fork의 컨테이너 포트 매핑이나 웹 서버가 닫은 임시 포트를 가져갈 수 있다.
- 인수 조건 2의 CI 적중 실측은 머지 전에 할 수 없다. main 캐시에 build cache 항목이 아직 없고 PR 실행은 캐시를 저장하지 않는다. 그래서 PR 재실행에서 적중하지 않는 것, 로컬 적중, 문서 근거로 좁힌다. main push 적중은 머지 뒤 확인할 항목으로 사용자에게 보고한다.
- 같은 커밋 재실행은 run id가 같고 attempt 번호만 오른다. 인수 조건 3은 run id와 attempt로 적고, attempt마다 시작부터 끝까지 잰다.
- fork 2와 3의 CI 비교를 인수 조건에 넣는다. 인수 조건 3은 고른 값이 들어간 최종 커밋의 실행이다.

**3. test를 CI job 2개로 나눈다(2026-10-23, 사용자 결정).** fork 2로 CI 전체가 4분 26초와 4분 14초였고 3분에 못 미쳤다(결과 절). fork 3은 더 느렸다. 컨텍스트 기동(58번, 102초)과 fork마다의 컨테이너 기동(약 20초)이 남고, Kafka fetch 대기를 줄여도 10~20초라 합의한 후보로는 3분에 닿지 않는다.
- build job을 matrix로 둘 띄운다. 각 job이 `./gradlew build -PtestShard=번호/2`를 돌린다. 두 job 모두 준비·컴파일을 따로 하므로 러너 시간은 약 두 배가 된다.
- 나누는 기준은 바깥 클래스 이름(중첩 클래스는 바깥 클래스를 따른다)의 해시다(사용자 결정). 모든 클래스가 정확히 한 job에서 돌고 관리할 목록이 없다. 대신 무거운 클래스가 한쪽에 몰릴 수 있다.
- `testShard`가 없으면(로컬) 전부 돈다. 형식이 틀리면 빌드가 바로 실패한다.
- job 안의 fork는 2개 그대로다(러너마다 4 vCPU).
- 한 job이 실패해도 다른 job은 끝까지 돈다(`fail-fast: false`). 실패를 한 번에 보려는 것이다.
- 제외 절의 "테스트를 여러 job·matrix로 나누기"를 범위로 옮긴다.

**4. 테스트 컨텍스트를 덜 띄우도록 테스트 코드를 바꾼다(2026-10-23, 사용자 결정).** job 분할 뒤 최종 커밋 3회 중앙값이 4분 5초였다. job마다 test가 약 2분 25초이고, 컨텍스트가 job마다 거의 다 다시 뜬다(두 job 합계 59번). 사용자가 Fake로 장애 주입 테스트를 정리해 이 PR에서 줄이자고 했고, 국내 사례를 참고하라고 했다.
- 참고한 사례는 결과 절 "참고 자료"에 있다. 공통 기법은 컨테이너를 JVM 안에서 공유하고, 클래스마다 다른 설정(`@MockBean` 등)을 없애 컨텍스트를 재사용하고, 장애는 공통 stub으로 재현하는 것이다.
- 같은 컨텍스트 구성을 쓰는 클래스를 이어서 돌린다. JUnit `ClassOrderer`가 클래스를 컨텍스트 캐시 키(`MergedContextConfiguration`) 순으로 묶는다. 캐시가 하나라 같은 구성 사이에 다른 구성이 끼면 같은 컨텍스트를 다시 띄우기 때문이다. 로컬에서 두 job의 기동 합계가 59번에서 43번이 됐다.
- 정렬하자 순서 의존이 하나 드러났다. API 통합 테스트가 같은 픽스처 매장으로 연달아 돌면서 #102의 매장 rate limit bucket이 바닥나 429가 났다. 전에는 컨텍스트를 다시 띄우는 사이에 bucket이 찼다. 결정 9대로 원인을 고친다. 통합 테스트마다 시작 전에 공유 Redis 컨테이너의 `rate-limit:*` 키를 지운다.
- 클래스마다 달랐던 설정 가운데 다른 테스트에 무해한 것을 `PostgresIntegrationTest`로 올린다. `@AutoConfigureMockMvc`(통합 테스트 67개 가운데 16개가 쓴다)와, 장애 주입에 쓰는 spy bean 셋(`FulfillmentCommandFacade`, `OrderCommandFacade`, `ReconciliationManagement`)이다. spy는 클래스 수준 `@MockitoSpyBean(types = …)`로 선언하고, 기본값대로 테스트마다 초기화된다(`MockReset.AFTER`). 테스트는 이 spy를 주입받아 쓴다.
- 다음은 합치지 않는다. 결제 gateway를 감싼 중첩 구성 9개(두 스레드를 맞물리는 gateway, 호출 순간 DB 자원을 검사하는 gateway, 첫 시도만 거절하는 gateway, 분산 락을 끈 구성)는 공용으로 바꾸면 테스트마다 동작을 갈아 끼우는 공유 상태가 되고, 앞 테스트가 남긴 Kafka 메시지를 비동기 consumer가 다음 테스트의 동작으로 처리할 수 있다. IdP stub은 JWK 장애 테스트가 같은 customizer를 켜고 끄는 다른 stub을 쓰고, IdP 장애 테스트는 stub이 없는 상태를 전제로 한다. Redis 장애 구성 셋과 스케줄 속성 구성 하나는 다른 설정 자체가 목적이다.

계약: 업무 계약(HTTP·이벤트·토픽·consumer group)은 바뀌지 않는다. 바뀌는 것은 PR 검사 job 구성, Gradle 빌드 설정, 테스트 기반 설정이다.

## 제외
- setup-gradle 캐시 쓰기 정책 변경(PR job에서도 저장). 결정 4.
- Gradle configuration cache, 원격 build cache.
- Testcontainers 컨테이너 reuse. fork끼리 DB를 같이 쓰게 된다.
- 테스트 삭제·병합, 검증 동작을 약하게 하는 대기 시간 단축.
- 모듈 분할.
- 볼트 rules(`engineering-principles.md`의 "GitHub Actions와 pre-push hook이 없어")와 `resilientpos-git` 스킬("GitHub Actions workflow를 쓰지 않습니다")의 낡은 문구. 볼트 파일이라 이 PR에서 고치지 않고 PR 끝에 사용자에게 보고한다.

## 한계
- 문서만 바꾼 머지가 이어지면 main push의 test도 캐시에서 온다. 그동안 태그가 움직이는 이미지(`postgres:18-alpine`, `redis:8-alpine`)가 바뀌어도 main은 녹색이고, 다음 코드 PR이 자기 변경과 상관없이 깨질 수 있다. 의심되면 `./gradlew test --rerun`으로 다시 돌린다.
- 시간 상한을 단언하는 테스트(`RedisTerminalRateLimiterOutageIntegrationTest`의 500ms, `AdminIdentityProviderTimeoutsTest`, `awaitUntil` 마감)는 fork가 CPU를 나눠 쓰면 여유가 줄어든다. 결정 9의 감시 대상이다.
- fork 수가 같아도 클래스를 fork에 나누는 순서와 fork 안의 실행 순서는 환경마다 다를 수 있다. 고정값은 CPU 수에 따라 배치가 바뀌지 않게 할 뿐이다.
- 로컬 후보 비교는 다른 세션과 compose 스택이 함께 도는 머신에서 각각 한 번 잰 값이다.

## 관련 문서
없음. 이 PR이 따르는 장기 스펙 문서가 없다. 앞 PR은 [flyway-version-guard](2026-09-15-flyway-version-guard.md)(Flyway gate), [kafka-event-redelivery](2026-09-26-kafka-event-redelivery.md)(컨텍스트 캐시 1개 제한)다.

## 변경 경로
- docs/spec/2026-10-22-ci-build-time.md
- .github/workflows/pr-checks.yml
- gradle.properties
- build.gradle.kts
- src/test/resources/spring.properties
- src/test/resources/junit-platform.properties
- src/test/kotlin/com/resilientpos/**
- docs/wiki/development-workflow.md

## 인수 조건
1. PR 실행에서 Gradle build와 Flyway version gate가 함께 시작한다(`needs` 없음).
2. `gradle.properties`가 build cache를 켠다. 결과 절에 setup-gradle 공식 문서 근거, 로컬 `FROM-CACHE` 실측, PR 재실행에서 적중하지 않는다는 CI 실측이 있다(범위 조정 2).
3. 이 PR 최종 커밋의 PR 실행 3번(attempt 1·2·3)이 모두 성공하고 중앙값이 3분 이하다. 결과 절에 run id와 attempt별 시간이 있다.
4. 로컬 `./gradlew test --rerun` 전체가 3회 연속 통과한다. `@Disabled`·재시도 플러그인을 쓰지 않는다.
5. 결과 절에 기준선과 후보별(캐시만, fork만, 합침) 로컬 수치, 머신 사양, CI run id가 있다.
6. 컨텍스트 캐시를 키웠다면 결과 절에 pause가 Kafka listener container를 멈춰 consumer가 group을 떠난다는 공식 문서·sources jar 근거와 실측이 있다. 키우지 않았다면 이유가 있다.
7. 테스트의 Kafka consumer·listener 설정을 바꿨다면 공식 문서 근거와 기본값 확인이 이 spec에 있고 Modulith·Kafka 키워드 문서가 갱신됐다.
8. [개발 규칙](../wiki/development-workflow.md)의 "PR 워크플로" 절이 병렬 구성과 build cache를 적는다.
9. 결과 절에 CI에서 잰 fork 2와 3의 run id·test step 시간과 고른 값이 있다.
10. test가 캐시에서 와도 `build/generated-snippets`가 되살아난다. `OwnerSessionStoreOutageIntegrationTest`가 닫은 임시 포트를 쓰지 않는다.
11. CI의 두 job이 돈 클래스와 테스트 수를 합치면 로컬 전체와 같다(빠지거나 겹친 클래스가 없다). 결과 절에 job별 시간이 있다.
12. 결과 절에 범위 조정 4 전과 뒤의 컨텍스트 기동 수(job별)와 CI 시간이 있다. 정렬 뒤에도 로컬 `./gradlew test --rerun`이 통과하고, 드러난 순서 의존의 원인과 고친 방법이 있다.

## 검증
- 개발 중: `python3 scripts/check_pr.py --base origin/main --worktree`
- 제출 전: `python3 scripts/check_pr.py --base origin/main`
- 전체 테스트: `./gradlew test --rerun`을 3회 연속 실행한다. 캐시를 켠 뒤에는 `--rerun` 없이 돌리면 test가 `FROM-CACHE`나 up-to-date로 건너뛰어진다. 후보별 측정도 `--rerun`으로 잰다.
- CI 시간: attempt마다 `gh api repos/char-labs/ResilientPOS/actions/runs/실행번호/attempts/번호`의 `run_started_at`부터 `updated_at`까지다. 기준선 39736986219도 같은 방법으로 잰다.
- 로컬 수치: `build/test-results/test/*.xml`의 클래스 수·테스트 수·시간 합계, wall clock, "Started … in N seconds" 로그 수와 합계.
- CI: `gh run view 실행번호 --json jobs`로 job·step 시간을 읽는다. 같은 커밋 재실행은 `gh run rerun 실행번호`다.
- 실패 시나리오: fork나 캐시 변경 뒤 특정 순서에서만 깨지는 테스트가 나오면 결정 9를 따른다.

## Wiki 영향
- [개발 규칙](../wiki/development-workflow.md)의 "PR 워크플로" 절: 두 단계를 순서대로 돈다는 서술과 "빌드를 앞에 두는 이유"를 병렬 구성과 build cache로 고친다.
- 업무 요구사항·도메인 모델은 무영향이다. 업무 동작과 운영 설정을 바꾸지 않는다.
- Modulith·Kafka 키워드 문서(볼트, 저장소 추적 대상 아님)는 결정 10에 해당할 때만 고친다.

## 분할 계획
하나의 개발 컨텍스트(PR 검사 시간)다. 세 범위가 같은 workflow 파일과 같은 실측을 공유한다.

열린 PR과의 겹침(2026-10-22 확인):
- #109(perf/k6-capacity-measurement)는 확인 중에 main에 머지됐다(309e93d). 이 브랜치는 커밋이 없을 때 fast-forward로 맞췄다.
- draft #94(refactor/modulith-2-1-2-resubmit)가 `build.gradle.kts`의 Modulith BOM 줄을 고친다. 이 PR은 `tasks.withType` Test 블록만 고칠 예정이라 hunk가 다르다. push 전에 다시 확인한다.

## TDD 검증 기록
적용하지 않는다. 업무 동작 변경이 없고 빌드·CI 설정과 테스트 기반 설정만 바꾼다. 검증은 전체 테스트 반복 통과와 CI 실측이다.

## 결과

### 측정 환경
- 로컬: Apple M3 Pro 11코어, 메모리 36GB, Docker는 OrbStack(VM 11 CPU, 16GB), JDK Corretto 25.0.4, Gradle 9.7.1. 다른 worktree의 compose 스택(`resilientpos`)과 다른 세션이 함께 돈다. 시간은 `./gradlew test --rerun`의 test task wall이다(컴파일 제외, 미리 `testClasses`).
- CI: GitHub 표준 러너 `ubuntu-latest`(ubuntu-24.04, 이미지 20261018.339.1). 저장소가 public이라 4 vCPU, 16GB다.

### 로컬 후보별 실측 (main 309e93d, 133개 클래스, 901개 테스트)
| 실험 | test task wall | 클래스 시간 합계 | 컨텍스트 기동 수 / "Started" 합계 | 결과 |
|---|---|---|---|---|
| 기준선(fork 1, 캐시 1) | 278초 | 273.8초 | 58번 / 72.2초 | 통과 |
| 캐시만(크기 64, 실험용 PostgreSQL 연결 한도 1000) | 260초 | 250.2초 | 34번 / 45.5초 | `LockIntegrationTest` 1건 실패(20초 대기) |
| fork 2만 | 155초 | 286.2초 | 57번 / 91.4초 | 통과 |
| fork 3만 | 143초 | 347.3초 | 65번 / 117.5초 | 통과 |
| fork 2와 캐시(실험용 리스너 수정 포함) | 179초 | 308.3초 | 36번 / 91.8초 | 통과 |

- 기준선이 사용자 측정(130개 클래스, 868개 테스트, 251초)보다 큰 것은 #109가 테스트를 더했기 때문이다.
- 서로 다른 컨텍스트 구성은 34개이고 그 가운데 DB를 쓰는 것이 23개다(캐시만 실험의 기동 수와 HikariPool 수). 캐시를 키우면 쉬는 컨텍스트도 Hikari 풀(기본 10개 연결)을 쥐어 PostgreSQL 기본 한도 100을 넘는다. 그래서 캐시 실험은 연결 한도를 임시로 올렸다.
- fork 2와 캐시를 합친 첫 실행은 머신이 17시 04분에 564초 동안 잠들어(`pmset -g log`의 Idle Sleep) 무효로 하고 `caffeinate -i`로 다시 쟀다. 이후 측정은 모두 잠자기를 막았다.
- fork 3은 fork 2보다 12초 빨랐지만 컨텍스트 기동 합계가 91.4초에서 117.5초로 늘어 경합이 보인다. 코어가 4개인 CI에서는 차이가 더 작을 수 있어 fork 수는 CI 실측으로 정한다(결정 7).
- 최종 구성(fork 2, 캐시 1)으로 `./gradlew test --rerun`을 3회 연속 돌려 모두 통과했다(901개, 실패 0). wall은 305초, 305초, 300초인데, 이때 머신이 스왑 39.9GB 가운데 38.9GB를 쓰고 load average가 147이어서 시간은 비교에 쓰지 않는다.

### 컨텍스트 pause 근거 (결정 6, 범위 조정 1)
- [Spring Framework 7.0 Context Pausing](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/context-pausing.html): 캐시된 컨텍스트는 쓰이지 않을 때 멈추고, 다시 꺼낼 때 auto-startup 빈을 다시 시작한다. 기본 `PauseMode`는 `ON_CONTEXT_SWITCH`다(다음에 꺼내는 컨텍스트가 다를 때만 멈춘다).
- spring-test 7.0.9 `ContextCache`(sources jar): `spring.test.context.cache.pause`는 7.0.3부터 있고 기본값이 `on_context_switch`다. `DefaultContextCache`가 컨텍스트를 바꿀 때 쓰지 않는 컨텍스트에 `pause()`를, 꺼낼 때 `restart()`를 부른다.
- spring-context 7.0.9 `DefaultLifecycleProcessor.onPause`(sources jar): `isPauseable()`이 true인(기본값) SmartLifecycle 빈만 멈춘다. 문서는 Lifecycle도 멈춘다고 적지만 코드는 SmartLifecycle만 멈춘다.
- spring-kafka 4.1.1 `KafkaListenerEndpointRegistry`(sources jar): SmartLifecycle이고 `isPauseable`을 다시 정의하지 않아 멈춘다. 멈추면 `KafkaMessageListenerContainer`가 consumer 스레드를 끝내며 `consumer.close()`를 부른다.
- kafka-clients 4.2.2 `CloseOptions`(sources jar): 기본 `close()`는 dynamic member면 group을 떠난다. 이 앱은 `group.instance.id`를 두지 않아 dynamic member다.
- spring-context 7.0.9 `ExecutorLifecycleDelegate`: 스케줄러 실행기도 멈추고, 멈춘 동안 작업은 재시작까지 기다린다.
- spring-data-redis 4.1.1 `LettuceConnectionFactory`(sources jar): SmartLifecycle이고 `stop()`이 Redis client를 닫는다. `LockReleaseListener`의 구독 컨테이너는 빈이 아니라 멈추지도 다시 시작하지도 않고, 구독은 `ApplicationReadyEvent`에서 한 번만 연다. 다시 시작한 컨텍스트에서 `LockIntegrationTest`의 "앱과 테스트가 해제 알림 채널을 구독한다"가 20초 안에 충족되지 않았다.
- 캐시만 실험에서 Kafka 테스트는 실패하지 않았다. 쉬는 컨텍스트가 파티션을 가져가는 문제는 재현되지 않았다.
- 테스트의 Kafka consumer·listener 설정은 바꾸지 않았다. 결정 10은 해당하지 않아 Modulith·Kafka 키워드 문서는 고치지 않는다.

### build cache 근거 (결정 4)
- [setup-gradle 문서](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md): Gradle 홈의 `caches`(local build cache `caches/build-cache-1` 포함)와 `notifications`를 저장·복원한다. 기본 브랜치 job에서만 캐시를 쓰고 다른 브랜치 job은 읽기만 한다. 복원은 같은 브랜치를 먼저, 그다음 기본 브랜치를 찾는다. `pull_request` 실행의 캐시 범위는 merge ref이고 기본값으로 쓰지 않는다. `cache-cleanup: on-success`(기본값)는 저장 전에 이번 실행이 쓰지 않은 파일을 지운다.
- 그래서 적중하는 곳은 main 캐시와 입력이 같은 task뿐이다. 문서만 고친 PR은 compile·test가 적중하고(test 입력인 runbook·알림 규칙 제외), 소스를 고친 PR은 다시 돈다. PR을 같은 커밋으로 다시 돌려도 PR 실행이 캐시를 저장하지 않아 처음 실행과 같다. main push를 같은 커밋으로 다시 돌리면 첫 실행이 저장한 캐시(키가 job과 커밋)에서 모두 적중한다. main 쪽 두 경우는 문서에서 끌어낸 것이고, 이 PR이 머지되기 전에는 main 캐시에 build cache 항목이 없어 CI로 확인할 수 없다.
- 로컬 실측: 최종 구성으로 test를 돌린 뒤 `./gradlew clean build`를 하면 `compileKotlin`·`compileTestKotlin`·`test`가 `FROM-CACHE`이고 3초에 끝났다.

### 전역 상태와 consumer group 재검증 (결정 8)
- fork는 JVM마다 PostgreSQL·Redis·Kafka 컨테이너를 따로 띄운다. 전역 `Tx`·`Lock`은 JVM 단위라 fork끼리 섞이지 않고, consumer group은 브로커가 달라 fork끼리 나누지 않는다.
- 컨텍스트 캐시는 1개 그대로라 한 JVM 안의 전역 상태와 group 공유 조건은 바뀌지 않았다.

### 리뷰 반영 (범위 조정 2)
- `resilientpos-multi-review`: Claude 81/100, Codex는 점수 없이 지적만, Gemini는 CLI가 없어 미실행이다. 두 리뷰어가 함께 짚은 것은 닫은 임시 포트의 경쟁과, 인수 조건 2의 CI 적중 실측을 머지 전에 할 수 없다는 것이다. 둘 다 범위 조정 2로 반영했다.
- Claude만 짚은 REST Docs 스니펫 누락은 실측으로 확인했다. 고치기 전에는 `clean` 뒤 캐시가 적중한 빌드에 `build/generated-snippets`가 없었다. output을 선언한 뒤에는 `./gradlew test --rerun`이 스니펫 디렉터리 73개를 만들고, `clean` 뒤 `./gradlew test`가 `FROM-CACHE`일 때도 73개가 그대로 되살아났다.
- 리뷰 반영 뒤 `./gradlew test --rerun` 전체가 통과했다(901개, 실패 0, wall 223초. 부하가 큰 머신이라 시간은 비교에 쓰지 않는다).
- 이 PR에서 고치지 않고 보고만 하는 것: build job에 `timeout-minutes`가 없어 fork가 멈추면 기본 360분까지 돈다. `README.md`의 "테스트는 이 파일과 무관하게"는 테스트가 `docker/prometheus`를 읽으므로 맞지 않는다.
