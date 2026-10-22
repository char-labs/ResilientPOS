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
- 실험용으로 리스너를 고쳐 fork 2개와 합쳐 쟀더니 test task가 fork 2개만일 때보다 느렸다(155초에서 179초).
- 그래서 `maxSize=1`을 그대로 두고 `src/main`은 바꾸지 않는다. 리스너의 재시작 한계는 운영에서 컨텍스트를 멈췄다 다시 시작하지 않으므로 이 PR에서 고치지 않고 사용자에게 보고한다.

계약: 업무 계약(HTTP·이벤트·토픽·consumer group)은 바뀌지 않는다. 바뀌는 것은 PR 검사 job 구성, Gradle 빌드 설정, 테스트 기반 설정이다.

## 제외
- setup-gradle 캐시 쓰기 정책 변경(PR job에서도 저장). 결정 4.
- Gradle configuration cache, 원격 build cache.
- 테스트를 여러 job·matrix로 나누기. fork로 같은 러너 안에서 나눈다.
- Testcontainers 컨테이너 reuse. fork끼리 DB를 같이 쓰게 된다.
- 테스트 삭제·병합, 검증 동작을 약하게 하는 대기 시간 단축.
- 모듈 분할.
- 볼트 rules(`engineering-principles.md`의 "GitHub Actions와 pre-push hook이 없어")와 `resilientpos-git` 스킬("GitHub Actions workflow를 쓰지 않습니다")의 낡은 문구. 볼트 파일이라 이 PR에서 고치지 않고 PR 끝에 사용자에게 보고한다.

## 관련 문서
없음. 이 PR이 따르는 장기 스펙 문서가 없다. 앞 PR은 [flyway-version-guard](2026-09-15-flyway-version-guard.md)(Flyway gate), [kafka-event-redelivery](2026-09-26-kafka-event-redelivery.md)(컨텍스트 캐시 1개 제한)다.

## 변경 경로
- docs/spec/2026-10-22-ci-build-time.md
- .github/workflows/pr-checks.yml
- gradle.properties
- build.gradle.kts
- src/test/resources/**
- src/test/kotlin/com/resilientpos/**
- docs/wiki/development-workflow.md

## 인수 조건
1. PR 실행에서 Gradle build와 Flyway version gate가 함께 시작한다(`needs` 없음).
2. `gradle.properties`가 build cache를 켠다. 결과 절에 setup-gradle 공식 문서 근거와, 어떤 실행에서 어떤 task가 `FROM-CACHE`였는지 실측이 있다.
3. 이 PR 같은 커밋의 PR 실행 3번이 모두 성공하고 중앙값이 3분 이하다. 결과 절에 run id 셋과 각 시간이 있다.
4. 로컬 `./gradlew test` 전체가 3회 연속 통과한다. `@Disabled`·재시도 플러그인을 쓰지 않는다.
5. 결과 절에 기준선과 후보별(캐시만, fork만, 합침) 로컬 수치, 머신 사양, CI run id가 있다.
6. 컨텍스트 캐시를 키웠다면 결과 절에 pause가 Kafka listener container를 멈춰 consumer가 group을 떠난다는 공식 문서·sources jar 근거와 실측이 있다. 키우지 않았다면 이유가 있다.
7. 테스트의 Kafka consumer·listener 설정을 바꿨다면 공식 문서 근거와 기본값 확인이 이 spec에 있고 Modulith·Kafka 키워드 문서가 갱신됐다.
8. [개발 규칙](../wiki/development-workflow.md)의 "PR 워크플로" 절이 병렬 구성과 build cache를 적는다.

## 검증
- 개발 중: `python3 scripts/check_pr.py --base origin/main --worktree`
- 제출 전: `python3 scripts/check_pr.py --base origin/main`
- 전체 테스트: `./gradlew test`를 3회 연속 실행한다. 후보별 측정은 `./gradlew test --rerun`으로 build cache를 건너뛰고 잰다.
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
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
