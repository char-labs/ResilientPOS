# PR spec: Spring Boot BOM의 취약한 전이 의존성 버전을 올린다

## 목적
Spring Boot 4.1.1 BOM이 정하는 전이 의존성 셋에 수정 버전이 나온 CVE가 있다(OSV 기준). 이 PR은 셋을 수정 버전으로 올려 runtimeClasspath에 남는 알려진 취약점을 없앤다.

- `tomcat-embed-core` 11.0.24: CVE-2026-65905·CVE-2026-65182·CVE-2026-68525(CRITICAL, DIGEST·FORM 인증과 접근 제어). 11.0.25에서 고쳤다.
- `jackson-core`·`jackson-databind` 3.1.5: CVE-2026-89425·89407·91777·91776·68497(HIGH), 83557·19032(MODERATE). 모두 3.1.7에서 고쳤다.
- `lz4-java` 1.10.1(`kafka-clients` 4.2.1 경유): CVE-2026-59949(MODERATE). 1.11.1에서 고쳤고 `kafka-clients` 4.2.2는 1.11.2를 쓴다.

처음에는 `spring-modulith-bom` 2.1.1의 CVE로 알려졌다. 하지만 GitHub advisory DB·OSV·spring.io/security 어디에도 Modulith 2.1.1의 CVE는 없다. Modulith BOM은 다른 BOM을 import하지 않고 자기 아티팩트만 관리하므로 이 셋의 버전을 정하지 않는다. `spring-data-jpa`는 이미 4.1.1이라 CVE-2026-47834에 해당하지 않는다.

업스트림 근거(2026-10-06 확인):
- **Spring Boot.** main(4.2.0-SNAPSHOT)은 Tomcat 11.0.26(spring-projects/spring-boot#51819)과 Jackson 3.1.6(#51679)을 4.2.0-M2에 넣었다. 4.2.0-M2로 가도 Jackson은 고쳐지지 않는다. 4.1.x 브랜치의 `gradle.properties`는 아직 `tomcatVersion=11.0.24`·`jacksonVersion=3.1.5`이고 Kafka는 4.2.1이다. 4.1.2 마일스톤은 열려 있고(닫힌 이슈 114, 남은 이슈 0, 예정일 없음) 버전 올림 이슈는 없다. 메인테이너(wilkinsona)는 #51476에서 버전 올림 이슈를 받지 않는다고 했다. 릴리스 직전에 도구가 최신 maintenance 버전을 가져가므로 그때까지는 Boot의 Tomcat 버전을 덮어쓰라는 것이다. Jackson 3.1.7로 올리는 커뮤니티 PR #51929도 같은 이유로 `status: invalid`로 닫혔다.
- **Tomcat 노출 범위.** 같은 #51476에서 이 Tomcat CVE는 Servlet security constraint를 쓰는 애플리케이션에만 해당한다고 했다. 이 저장소는 Servlet 인증·security constraint를 쓰지 않는다(단말 인증은 `HandlerInterceptor`, [terminal-authentication](2026-10-05-terminal-authentication.md) 결정 1). 실제 노출 가능성은 낮지만 등급이 CRITICAL이라 올린다.
- **Jackson.** FasterXML/jackson-databind#6203(CVE-2026-91776)이 닫혔고 3.1.7이 Maven Central에 있다.
- **Kafka·lz4-java.** lz4-java #100·#127(XXHash JNI)이 2026-09-25 닫혔다. apache/kafka는 KAFKA-20842(1.11.1)·KAFKA-20936(1.11.2)·KAFKA-21174(1.11.4)를 반영했고 1.12.0(#23637)은 열려 있다. `kafka-clients` 4.2.2의 `lz4-java` 1.11.2는 CVE-2026-59949(1.11.1에서 수정)를 덮는다.
- **Spring Modulith.** CVE 이슈가 없다. 2.1.2 마일스톤(남은 것은 "Release 2.1.2"뿐)은 버그 수정만 담는다. 그중 #1918(상태별 staleness 기간 0을 즉시 만료로 취급)은 이 저장소에 해당하지 않는다. staleness monitor의 세 기간을 모두 0(기본값)으로 두어 monitor가 등록되지 않기 때문이다(Modulith·Kafka 키워드(`docs/wiki/modulith-kafka-keywords.md`, 볼트) Staleness monitor). Modulith는 올리지 않는다.

## 범위
**결정 1: Boot BOM의 버전 속성을 재정의한다.**
- 문제: Boot 4.1.1 BOM이 고정한 전이 의존성 버전이 취약하다.
- 제약: 근본 해법인 Spring Boot 4.1.2는 아직 배포되지 않았다(2026-10-06 repo1.maven.org `spring-boot-dependencies`의 maven-metadata.xml release는 4.2.0-M2). 마이너를 4.2 마일스톤으로 올리는 것은 이 PR의 목적 밖이다.
- 수단: `io.spring.dependency-management` 플러그인이 읽는 `extra` 속성으로 BOM 버전을 바꾼다. 속성 이름은 `spring-boot-dependencies` 4.1.1 pom의 것을 그대로 쓴다.
  - `tomcat.version` = 11.0.26 (11.x 최신. 11.0.25 이상이면 수정 포함)
  - `jackson-bom.version` = 3.1.7 (3.1.x 안에서 수정 포함 최소 버전. 3.2.x는 마이너 변경이라 Boot가 고르게 둔다)
  - `kafka.version` = 4.2.2 (4.2.x 안에서 `lz4-java` 1.11.2를 쓰는 버전. 4.3.x는 마이너 변경이라 같은 이유로 두지 않는다)
- 각 줄에 이유(CVE)를 주석으로 단다. 묶음 주석에는 덮어쓰는 근거(#51476)와 다시 볼 조건을 적는다.
- **다시 볼 조건: 세 줄은 Boot 4.1.2 이상으로 올리는 PR에서 지운다.** 그 PR은 지우기 전에 새 BOM의 `tomcat.version`·`jackson-bom.version`·`kafka.version`이 이 값 이상인지 확인한다. 낮으면 그 줄만 남긴다. 의존성 선언에 버전을 직접 박거나 `resolutionStrategy`를 쓰지 않는다. 속성은 같은 BOM이 관리하는 아티팩트(`tomcat-embed-*`, `jackson-*` 3.x 전체, `kafka-*`)를 한꺼번에 맞춘다.
- `lz4-java`는 따로 올리지 않는다. `kafka-clients` 4.2.2가 1.11.2를 끌어오는지를 dependencyInsight로 확인한다.

**결정 2: Kafka 클라이언트 동작 대조.** `kafka-clients`가 패치 버전만 바뀌지만 재시도·DLT·재전송 경로가 기대는 클라이언트 기본값을 기동 로그로 대조한다. 바꾸기 전 4.2.1 상태에서 Kafka 통합 테스트(`KafkaRetryIntegrationTest`·`KafkaEventDeliveryIntegrationTest`·`EventPublicationResubmitterIntegrationTest`)의 `ProducerConfig`·`ConsumerConfig`·`AdminClientConfig values`를 뽑아 두었고(키 311개, 식별자·주소 키 제외), 4.2.2에서 같은 방식으로 뽑아 비교한다.

`application.yaml`은 건드리지 않는다. PR #84와 겹칠 수 있는 유일한 경로다.

## 제외
- Spring Boot 4.1.2 이상 또는 4.2.x로 올리기. 4.1.2가 배포되면 이 PR의 재정의 세 줄을 지우는 후속 작업이다.
- Jackson 3.2.x·Kafka 4.3.x 같은 마이너 올림.
- 의존성 취약점 자동 검사(OSV·Dependabot) 도입. 이번에는 수동 조회로 확인한다.
- Kafka 브로커 이미지(`apache/kafka:4.1.0`) 변경.

## 관련 문서
없음

## 변경 경로
- build.gradle.kts

## 인수 조건
- `./gradlew dependencyInsight --configuration runtimeClasspath`가 `tomcat-embed-core` 11.0.26, `jackson-core`·`jackson-databind` 3.1.7, `kafka-clients` 4.2.2, `lz4-java` 1.11.2를 보인다.
- runtimeClasspath의 모든 좌표를 `https://api.osv.dev/v1/querybatch`로 조회했을 때 남은 취약점이 없다. 남으면 결과에 좌표와 ID, 남기는 이유를 적는다.
- `./gradlew test` 전체가 통과한다. Kafka 통합 테스트(재시도·DLT·재전송)를 포함한다.
- 4.2.1과 4.2.2의 클라이언트 설정 값 차이를 결과에 적는다. 차이가 재시도·DLT·재전송 동작에 닿으면 그 영향을 적는다.
- `python3 scripts/check_pr.py --base origin/main`이 통과한다.

## 검증
- `./gradlew -q dependencyInsight --configuration runtimeClasspath --dependency` 뒤에 `tomcat-embed-core`, `jackson-databind`, `jackson-core`, `kafka-clients`, `lz4-java`를 차례로 넣어 선택된 버전을 본다.
- `./gradlew -q dependencies --configuration runtimeClasspath`에서 좌표를 뽑아 OSV querybatch(ecosystem Maven)로 조회한다. 실패 시나리오는 하나라도 vulns가 돌아오는 것이다.
- `./gradlew test`.
- Kafka 통합 테스트의 결과 XML system-out에서 클라이언트 설정 값을 뽑아 4.2.1 기준값과 비교한다.
- `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전에는 `--worktree` 없이.

## Wiki 영향
- 현재 상태(`docs/wiki/current-state.md`, 볼트)(볼트) "실제 빌드 선언" 표의 프레임워크 줄에 BOM 재정의 세 개와 지울 시점을 적는다. 요구사항 변화는 없다.
- Modulith·Kafka 키워드(`docs/wiki/modulith-kafka-keywords.md`, 볼트)(볼트) 버전 줄과 출처의 kafka-clients를 4.2.2로 바꾸고 `updated`와 출처의 저장소 근거를 갱신한다. 설정 값 대조에서 차이가 나오면 해당 키워드도 고친다.
- 두 문서는 볼트 링크라 이 PR의 diff에는 나오지 않는다.

## 분할 계획
개발 컨텍스트는 "Boot 4.1.1 BOM의 취약한 전이 의존성 올림" 하나다. 작업은 origin/main 42a27ab에서 시작했다. 그때 열린 PR은 #84(feature/payment-outcome-reconciliation) 하나였고 `build.gradle.kts`를 바꾸지 않았다. 겹칠 수 있는 `application.yaml`은 이 PR이 건드리지 않는다. #84가 52f507a로 머지되어 그 위로 rebase했고, 지금 열린 PR 가운데 이 PR과 경로가 겹치는 것은 없다. Boot 4.1.2로 올리면서 재정의를 지우는 일은 배포 뒤의 별도 컨텍스트다.

## TDD 검증 기록
적용하지 않는다. 업무 동작을 바꾸지 않는 빌드 설정 변경이라 새 테스트가 없고, 기존 `./gradlew test` 전체와 의존성 조회로 검증한다.

## 결과
`build.gradle.kts`에 BOM 버전 속성 셋을 덮어썼다. 2026-10-06 실행 결과는 아래와 같다.

- **dependencyInsight(runtimeClasspath).** `tomcat-embed-core`·`tomcat-embed-websocket`은 11.0.24에서 11.0.26, `jackson-core`·`jackson-databind`는 3.1.5에서 3.1.7, `kafka-clients`는 4.2.1에서 4.2.2가 됐다. `lz4-java`(`at.yawk.lz4`)는 따로 선언하지 않았는데 1.10.1에서 1.11.2로 따라 올라갔다.
- **OSV querybatch.** `./gradlew -q dependencies --configuration runtimeClasspath`에서 해석된 좌표 144개를 ecosystem Maven으로 조회했다. 바꾼 뒤에는 취약 좌표가 0개다(rebase 뒤 재조회도 0개). 조회 방식이 놓치지 않는지 보려고 바꾸기 전 빌드 파일로도 같은 조회를 돌렸다. 좌표 4개에서 GHSA 11건이 나왔다. `tomcat-embed-core` 3건, `jackson-databind` 5건, `jackson-core` 2건, `lz4-java` 1건이고 목적 절의 CVE 11건과 수가 같다.
- **`./gradlew test`.** origin/main 52f507a(#84 머지) 위로 rebase한 뒤 459개가 모두 통과했다(실패·오류·건너뜀 0). rebase 전 42a27ab 기준에서도 424개가 모두 통과했다. Kafka 통합 테스트도 4.2.2에서 통과했다. `KafkaRetryIntegrationTest`(재시도·DLT) 7개, `KafkaEventDeliveryIntegrationTest`(externalization·전달) 6개, `EventPublicationResubmitterIntegrationTest`(재전송) 7개다. 기동 로그의 `Kafka version`은 4.2.2다.
- **클라이언트 설정 값 대조.** 세 테스트의 기동 로그에서 `ProducerConfig`·`ConsumerConfig`·`AdminClientConfig values`를 뽑았다(키 311개, `client.id`·`group.id`·`bootstrap.servers`·`group.instance.id` 제외). 4.2.1과 4.2.2의 값이 모두 같고, rebase 뒤에 다시 뽑아도 같다. 재시도·DLT·재전송이 기대는 `acks=-1`, `enable.idempotence=true`, `delivery.timeout.ms=120000`, `max.block.ms=60000`도 그대로라 동작 영향이 없다.
- **`check_pr.py`.** `--base origin/main`으로 `--worktree` 없이 실행해 PASS다. 하네스 테스트(`python3 -m unittest discover -s scripts -p 'test_*.py'`)도 통과한다.
