# PR spec: Spring Boot BOM의 취약한 전이 의존성 버전을 올린다

## 목적
Spring Boot 4.1.1 BOM이 정하는 전이 의존성 셋에 수정 버전이 나온 CVE가 있다(OSV 기준). 이 PR은 셋을 수정 버전으로 올려 runtimeClasspath에 남는 알려진 취약점을 없앤다.

- `tomcat-embed-core` 11.0.24: CVE-2026-65905·CVE-2026-65182·CVE-2026-68525(CRITICAL, DIGEST·FORM 인증과 접근 제어). 11.0.25에서 고쳤다.
- `jackson-core`·`jackson-databind` 3.1.5: CVE-2026-89425·89407·91777·91776·68497(HIGH), 83557·19032(MODERATE). 모두 3.1.7에서 고쳤다.
- `lz4-java` 1.10.1(`kafka-clients` 4.2.1 경유): CVE-2026-59949(MODERATE). 1.11.1에서 고쳤고 `kafka-clients` 4.2.2는 1.11.2를 쓴다.

처음에는 `spring-modulith-bom` 2.1.1의 CVE로 알려졌다. 하지만 GitHub advisory DB·OSV·spring.io/security 어디에도 Modulith 2.1.1의 CVE는 없다. Modulith BOM은 다른 BOM을 import하지 않고 자기 아티팩트만 관리하므로 이 셋의 버전을 정하지 않는다. `spring-data-jpa`는 이미 4.1.1이라 CVE-2026-47834에 해당하지 않는다.

## 범위
**결정 1: Boot BOM의 버전 속성을 재정의한다.**
- 문제: Boot 4.1.1 BOM이 고정한 전이 의존성 버전이 취약하다.
- 제약: 근본 해법인 Spring Boot 4.1.2는 아직 배포되지 않았다(2026-10-06 repo1.maven.org `spring-boot-dependencies`의 maven-metadata.xml release는 4.2.0-M2). 마이너를 4.2 마일스톤으로 올리는 것은 이 PR의 목적 밖이다.
- 수단: `io.spring.dependency-management` 플러그인이 읽는 `extra` 속성으로 BOM 버전을 바꾼다. 속성 이름은 `spring-boot-dependencies` 4.1.1 pom의 것을 그대로 쓴다.
  - `tomcat.version` = 11.0.26 (11.x 최신. 11.0.25 이상이면 수정 포함)
  - `jackson-bom.version` = 3.1.7 (3.1.x 안에서 수정 포함 최소 버전. 3.2.x는 마이너 변경이라 Boot가 고르게 둔다)
  - `kafka.version` = 4.2.2 (4.2.x 안에서 `lz4-java` 1.11.2를 쓰는 버전. 4.3.x는 마이너 변경이라 같은 이유로 두지 않는다)
- 각 줄에 이유(CVE)와 "Boot 4.1.2 이상으로 올릴 때 지운다"는 주석을 단다. 의존성 선언에 버전을 직접 박거나 `resolutionStrategy`를 쓰지 않는다. 속성은 같은 BOM이 관리하는 아티팩트(`tomcat-embed-*`, `jackson-*` 3.x 전체, `kafka-*`)를 한꺼번에 맞춘다.
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
- [현재 상태](../wiki/current-state.md)(볼트) "실제 빌드 선언" 표의 프레임워크 줄에 BOM 재정의 세 개와 지울 시점을 적는다. 요구사항 변화는 없다.
- [Modulith·Kafka 키워드](../wiki/modulith-kafka-keywords.md)(볼트) 버전 줄과 출처의 kafka-clients를 4.2.2로 바꾸고 `updated`와 출처의 저장소 근거를 갱신한다. 설정 값 대조에서 차이가 나오면 해당 키워드도 고친다.
- 두 문서는 볼트 링크라 이 PR의 diff에는 나오지 않는다.

## 분할 계획
개발 컨텍스트는 "Boot 4.1.1 BOM의 취약한 전이 의존성 올림" 하나다. 2026-10-06 origin/main 42a27ab 기준으로 열린 PR은 #84(feature/payment-outcome-reconciliation) 하나이고 `build.gradle.kts`를 바꾸지 않는다. 겹칠 수 있는 `application.yaml`은 이 PR이 건드리지 않는다. Boot 4.1.2로 올리면서 재정의를 지우는 일은 배포 뒤의 별도 컨텍스트다.

## TDD 검증 기록
적용하지 않는다. 업무 동작을 바꾸지 않는 빌드 설정 변경이라 새 테스트가 없고, 기존 `./gradlew test` 전체와 의존성 조회로 검증한다.

## 결과
개발 전 작성. 개발 후 실행 결과를 추가한다.
