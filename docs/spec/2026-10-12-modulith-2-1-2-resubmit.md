# PR spec: Spring Modulith 2.1.2에 맞춰 발행 기록 재전송을 정리한다

## 목적
Spring Modulith 2.1.2는 발행 기록의 상태 전이에 완료 조건을 더하고(GH-1935), staleness를 마지막 재전송 시각으로 잰다(GH-1837). [event-publication-resubmit](2026-09-29-event-publication-resubmit.md)(#70)과 [event-resubmit-doc-corrections](2026-10-06-event-resubmit-doc-corrections.md)(#88)은 2.1.1의 결함을 전제로 코드 한 문장과 설명을 남겼다. 이 PR은 BOM을 2.1.2로 올리고 그 문장과 설명을 2.1.2 동작에 맞춘다.

기대 결과는 셋이다. 재전송 작업 1단계는 끊긴 기록 UPDATE 한 문장만 남는다. Modulith staleness monitor를 쓰지 않는 이유는 규모로 적힌다. 락은 정합성 수단이 아니라 pod 사이의 중복 전송을 줄이는 수단으로 설명되고, 2.1.2에서 사라진 중복과 남는 중복이 구분된다.

2.1.2는 2026-10-22에 Maven Central에 나왔다. 검증은 정식 2.1.2로 한다.

## 범위
**업스트림 사실.** Maven Central의 2.1.2 sources jar(`spring-modulith-events-api`·`-core`·`-jpa`)와 `spring-modulith-events-jpa` jar의 JPQL 상수로 대조했다(2026-10-23). 아래 커밋은 모두 태그 `2.1.2`(5d7515d5)에 들어 있다.
- GH-1935(b354b8a, main GH-1925의 backport). JPA 저장소의 상태 UPDATE(`markFailed`·`markProcessing`가 쓰는 `UPDATE`)와 `MARK_RESUBMITTED`에 `completionDate is null` 조건이 더해졌다. JDBC·Neo4j도 같다. 그래서 완료된 행은 재전송 표시를 받지 않고, `FAILED`나 `PROCESSING`으로 되돌아가지도 않는다.
- GH-1837(71087ee5). `DefaultEventPublicationRegistry.markFailed(Status, Staleness)`가 `getLastPublicationDate()`로 잰다. 마지막 재전송 시각이 있으면 그것을, 없으면 발행 시각을 쓴다.
- GH-1942. JDBC·JPA가 `minAge`를 무시하던 문제다. 우리는 `minAge`를 쓰지 않아(기본 0) 무관하다.
- GH-1893(21e9c05c). 상태가 빈 행도 재전송 표시와 상태 표시를 받게 됐다. 이 저장소의 행은 상태가 늘 차 있어 무관하다.
- GH-1918(ae793f48). staleness 기간 0을 즉시 만료로 보던 문제다. 우리는 세 기간을 모두 0으로 두어 monitor가 등록되지 않으므로 무관하다.
- spring-modulith#1904(역직렬화할 수 없는 행이 재전송 실행 전체를 끊는다)는 2.1.2에 수정이 없다. 그 한계 설명은 그대로 둔다.
- 2.1.2의 monitor가 하는 일은 다음과 같다. `StalenessMonitorConfiguration`이 `addFixedDelayTask`로 `check-interval`(기본 1분)마다 모든 인스턴스에서 돈다. 상태마다 `findByStatus(status)`를 부른다. 이 JPQL은 `where p.status = ?1`뿐이라 그 상태의 행 전부를 직렬화된 이벤트까지 엔티티로 읽는다. 그다음 자바에서 시각으로 거르고, 남은 행마다 `markFailed`를 불러 한 건씩 UPDATE한다.
- 완료 표시 두 가지(`MARK_COMPLETED_BY_ID`, `MARK_COMPLETED_BY_EVENT_AND_LISTENER_ID`)는 `status = COMPLETED`와 `completion_date`를 함께 쓴다. 정리 DELETE(`DELETE_COMPLETED_BEFORE`)는 `completion_date`만 보고 상태는 보지 않는다.

**결정**
1. `markStalePublicationsFailed`의 두 번째 문장을 지운다. `RESUBMITTED`이면서 `completion_date`가 찬 행을 `COMPLETED`로 돌리던 문장이다.
   - 그 상태는 완료된 행에 `markResubmitted`가 성공해야 생긴다. 2.1.2는 `completion_date is null`인 행에만 재전송 표시를 하고, 완료 표시는 상태와 완료 시각을 함께 쓴다. 재전송 표시 뒤 완료 표시 전에 프로세스가 죽은 행은 `completion_date`가 비어 있어 첫 문장(끊긴 기록)이 맡는다.
   - 2.1.1에서 그 상태로 남은 행은 3단계 정리가 보존 기간 뒤 지운다. 정리 DELETE가 상태를 보지 않기 때문이다. 2.1.1에서 `completion_date`가 찬 채 `FAILED`로 되돌아간 행도 같다. 2.1.2의 실패 기록 읽기는 이 행을 계속 읽지만 재전송 표시가 거절하고, 정리 DELETE가 지운다. 배포된 환경이 없어 전환 처리를 따로 두지 않는다.
2. 끊긴 기록 UPDATE와 monitor 끔은 유지한다. 이유를 "monitor 결함 우회"에서 "규모"로 바꾼다[사용자 결정].
   - #70이 짚은 두 결함은 2.1.2에서 고쳐졌다. 하나는 `RESUBMITTED`를 최초 발행 시각으로 재던 것이고(GH-1837), 다른 하나는 완료된 행을 `FAILED`로 덮던 것이다(GH-1935).
   - 그래도 monitor를 쓰지 않는 이유는 비용이다. 둘 다 pod마다 돌지만 하는 일이 다르다. monitor는 상태마다 그 상태의 행을 직렬화된 이벤트까지 모두 읽어 메모리에서 거르고 건별로 UPDATE한다. 우리 문장은 조건을 건 UPDATE 한 문장이다.
   - monitor는 `FAILED`를 읽지 않는다. 그래서 읽는 양은 판정 기간(5분) 동안 들어온 전송 대기·전송 중 행과 재전송 중 행(최대 500건)이다. 브로커 장애 중에는 그 기간의 트래픽이 모두 여기에 든다. 실측이 아니라 소스에서 추론한 비용이다.
   - 우리 문장의 `completion_date is null` 조건은 metric `stale`과 같은 기준이라 남긴다.
3. `Lock.executeWithLockNoTx`는 그대로 둔다.
   - #70·#88은 "fail-open이면 방금 완료된 행의 재전송은 막지 못한다"고 적었다. 2.1.2에서는 `markResubmitted`가 완료된 행을 잡지 않으므로 이 설명을 고친다. 다른 pod가 먼저 끝낸 행은 다시 나가지 않는다.
   - 처리 중인 행의 중복은 2.1.2에서도 남는다. `markResubmitted`는 `RESUBMITTED`인 행만 거르는데, 재전송 표시 직후 `CompletionRegisteringAdvisor`가 리스너 호출 전에 `markProcessing`으로 그 행을 `PROCESSING`으로 바꾼다. 그래서 락이 빠지면(fail-open, TTL 초과) 같은 `FAILED` 행을 먼저 읽어 둔 pod가 처리 중인 행을 다시 표시해 보낼 수 있다. multi-review(Codex)가 짚었고 2.1.2 소스로 확인했다.
   - 그래서 락의 역할은 브리프가 적은 "중복 읽기·경합 완화"가 아니라 "pod 사이 중복 전송을 줄이는 것"이다. 정합성은 여전히 멱등 consumer가 맡는다.
   - 락이 잡혀도 중복 전송은 생길 수 있다. 전송 뒤 완료 표시 전에 프로세스가 죽거나, 전송이 externalizer 대기열에서 `stale-after`를 넘기면 그 행은 끊긴 기록이 되어 다시 나간다. 뒤쪽은 `application.yaml`의 `stale-after` 주석이 적은 한계와 같다. 그래서 멱등 consumer 전제는 그대로다.
4. BOM `spring-modulith-bom`을 2.1.1에서 2.1.2로 올린다[사용자 결정]. 2.1.2는 Boot 4.1.2에 대해 빌드됐지만(#1956) BOM에 Boot가 없고 `spring-modulith-core`의 Boot 의존은 optional이라 이 저장소의 Boot는 4.1.1 그대로다.
5. 통합 테스트 `끊긴 기록은 마지막 시도부터 5분이 지나야 실패로 바뀌고 완료된 재전송 행은 완료로 돌린다`에서 완료된 재전송 행과 그 주석을 빼고, 이름을 `끊긴 기록은 마지막 시도부터 5분이 지나야 실패로 바뀐다`로 줄인다[사용자 결정].
6. 문서를 고친다.
   - `EventPublicationResubmitter` KDoc: 1단계, 2단계의 fail-open 설명, monitor를 쓰지 않는 이유.
   - [도메인 모델](../wiki/domain-model.md) §4.3: 재전송 항목의 1·2단계와 "Modulith 버전을 올리는 PR의 절차" 5번. 이전 spec과 keywords 8.18은 이 절차를 §4.7이라고 불렀지만 실제 위치는 §4.3이다. §4.7 스키마 변경 검토 절은 바뀌지 않는다.
   - [Modulith·Kafka 키워드](../wiki/modulith-kafka-keywords.md)(볼트): 그 문서의 갱신 규칙을 따른다. 머리의 기준 버전, 2절의 Staleness monitor 항목과 재전송 API 동작 항목, 8.17·8.18에 날짜를 붙인 줄, 8.18의 절 번호, 출처를 고친다. 2절 "주의"의 "공식 문서가 외부 조정이 필요하다고 적는다"는 2026-10-12 레퍼런스와 `FailedEventPublications`·`ResubmissionOptions` Javadoc에 그 문장이 없어서 찾지 못했다고 적는다.
   - [현재 상태](../wiki/current-state.md)(볼트): 빌드 선언 표의 Modulith 버전, Modulith 도입 문단의 버전 문장.

**공식 문서 대조.** [Spring Modulith 이벤트 레퍼런스](https://docs.spring.io/spring-modulith/reference/events.html)는 2026-10-12 현재 2.1.1 판이다. 이 페이지는 monitor를 "`PUBLISHED`·`PROCESSING`·`RESUBMITTED`에서 해당 기간보다 오래된 기록을 `FAILED`로 바꾸는 주기 작업"으로, 재전송을 "`FAILED`를 `RESUBMITTED`로 바꾸고 마지막 재전송 시각을 갱신한다"로 설명한다. 무엇을 기준으로 시각을 재는지, 완료된 행을 어떻게 다루는지, 몇 건씩 읽는지는 적지 않는다. 그래서 동작은 소스로 확인했다. 2026-10-23 현재도 레퍼런스는 2.1.1 판이고, GitHub Release 노트는 없으며 마일스톤 2.1.2는 열려 있다. 열린 셋은 릴리스 작업 이슈 #1910과 의존성 올림 #1955(Micrometer Tracing 1.7.2)·#1956(Spring Boot 4.1.2)이고, 두 의존성 올림 커밋은 이미 태그에 들어 있다. 그래서 근거는 마일스톤의 닫힌 이슈(#1935·#1837·#1942·#1918·#1893), 태그 `2.1.2`, Maven Central의 jar다.

## 제외
- #85가 넣은 BOM 버전 덮어쓰기 셋(`tomcat.version`·`jackson-bom.version`·`kafka.version`). Boot 4.1.2로 올리는 맥락이라 이 PR과 다르다.
- Boot 4.1.2로 올리기. 2.1.2가 Boot 4.1.2로 빌드됐어도 이 저장소의 Boot는 그대로 해석된다(결정 4). 동시에 진행 중인 `fix/boot-4-1-2`가 맡는다.
- monitor로 되돌리기. 사용자가 끊긴 기록 UPDATE 유지를 골랐다.
- 업스트림이 이미 고정하는 저장소 테스트를 우리 쪽에 다시 만드는 일. 완료된 행의 재전송 표시 거절과 실패 표시 거절은 b354b8a의 JPA·JDBC·Neo4j 저장소 통합 테스트가 고정한다. multi-review(Claude)가 우리 재전송 경로로 같은 동작을 보는 통합 테스트를 제안했지만 같은 이유로 만들지 않는다. 대신 정식 jar의 JPQL 상수를 직접 확인했다(결과 절).
- fail-open 경합(처리 중인 행의 재표시)을 재현하는 테스트. multi-review(Codex)가 권장 수준으로 제안했다. 문서가 이 중복을 허용한다고 적으므로 고정할 보장이 없다.
- 1단계의 `completion_date is null` 조건을 고정하는 테스트 행. 2.1.2에서 생기지 않는 상태를 테스트가 일부러 만들어야 해서 넣지 않는다[사용자 결정]. multi-review의 두 리뷰어가 모두 이 회귀 검출이 사라진 것을 짚고 행을 남기거나 대체 행을 넣자고 했지만, grill에서 사용자가 고른 trade-off와 같다.
- #1904 대응과 역직렬화할 수 없는 행의 격리.
- 이전 PR spec(#70, #88)의 수정. 이 spec이 링크한다.
- 끊긴 기록 UPDATE의 조건 변경, `EventPublicationMetrics`, `application.yaml`. 설정의 "staleness 모니터는 끈 채로 둔다(EventPublicationResubmitter)" 주석은 KDoc을 가리키므로 그대로 맞다.

## 관련 문서
없음

## 변경 경로
- build.gradle.kts
- src/main/kotlin/com/resilientpos/platform/EventPublicationResubmitter.kt
- src/test/kotlin/com/resilientpos/platform/EventPublicationResubmitterIntegrationTest.kt
- docs/wiki/domain-model.md

## 인수 조건
- `markStalePublicationsFailed`는 UPDATE 한 문장이다. 끊긴 기록의 조건(`completion_date is null`, 세 상태, 마지막 시도부터 `stale-after`가 지남)은 바뀌지 않는다.
- 통합 테스트 `끊긴 기록은 마지막 시도부터 5분이 지나야 실패로 바뀐다`가 다섯 행(오래된 `PUBLISHED`·`PROCESSING`, 최근 `PUBLISHED`, 오래된 `RESUBMITTED`, 최근 `RESUBMITTED`)의 상태를 단언한다.
- `build.gradle.kts`의 `spring-modulith-bom`이 2.1.2다. 저장소는 `mavenCentral()`뿐이고 #85의 덮어쓰기 셋은 그대로다.
- 커밋 상태(정식 2.1.2)에서 `./gradlew test` 전체가 통과한다. 정식 jar의 `JpaEventPublicationRepository` 상수에 GH-1935의 `completionDate is null` 조건이 있다.
- KDoc, domain-model, 볼트 문서에 아래 설명이 현재 동작으로 남아 있지 않다. keywords의 날짜를 붙인 이력 줄은 갱신 규칙대로 남는다.
  - monitor가 `RESUBMITTED`를 최초 발행 시각으로 잰다.
  - monitor가 완료된 행을 `FAILED`로 덮는다.
  - fail-open이면 방금 완료된 행의 재전송은 막지 못한다.
  - 완료됐는데 `RESUBMITTED`로 남은 행을 `COMPLETED`로 돌린다.
- monitor를 쓰지 않는 이유가 규모(상태별 전체 읽기, 건별 UPDATE)로 적혀 있다.
- 락이 빠지면 처리 중(`PROCESSING`)인 행이 다시 나갈 수 있다는 것과, 완료된 행은 다시 나가지 않는다는 것이 구분되어 적혀 있다.
- `python3 scripts/check_pr.py --base origin/main --worktree`와 `--worktree` 없는 실행이 통과한다.

## 검증
- 대상 테스트: `./gradlew test --tests 'com.resilientpos.platform.EventPublicationResubmitterIntegrationTest'`.
- 전체 테스트: 커밋 상태 그대로 `./gradlew test`.
- 의존성: `./gradlew dependencyInsight`로 Modulith 2.1.2와 Boot 버전을 본다. 정식 jar의 `JpaEventPublicationRepository` 상수를 `javap`로, GH-1837·monitor·재전송 순서를 sources jar로 확인한다.
- 남은 문장 찾기: `grep -rn "최초 발행 시각\|FAILED\`로 덮\|막지 못한다\|COMPLETED\`로 돌" src docs`. 볼트 링크인 문서도 포함한다.
- `python3 scripts/check_pr.py --base origin/main --worktree`를 실행하고, 제출 전에는 `--worktree` 없이 다시 실행한다.
- PR 전에 `resilientpos-multi-review`를 돌린다.
- 실패 시나리오: jar에 GH-1935가 빠져 있으면 재전송 관련 통합 테스트는 통과해도 근거가 틀린다. 그래서 jar의 `JpaEventPublicationRepository`에 `completionDate is null` 조건이 있는지 직접 확인한다.

## Wiki 영향
- [도메인 모델](../wiki/domain-model.md) §4.3: 재전송 1단계·2단계 설명과 버전 올리기 절차 5번을 2.1.2 동작에 맞춘다. 요구사항 변화는 없다. 재전송·정리의 주기, 대상, 상한은 같고, 일어나지 않게 된 상태를 정리하던 문장만 빠진다.
- [Modulith·Kafka 키워드](../wiki/modulith-kafka-keywords.md)와 [현재 상태](../wiki/current-state.md)는 볼트 링크라 이 PR의 diff에 나오지 않는다. 고치는 곳은 범위 절 결정 6에 있다.

## 분할 계획
개발 컨텍스트는 "Modulith 2.1.2로 올리고 재전송 작업을 그 동작에 맞춘다" 하나다. origin/main 7282190에서 시작했고 2026-10-23 63b73bc로 rebase했다. 그때 열린 PR은 이 PR뿐이다. Boot 4.1.2 업그레이드와 #85 덮어쓰기 제거는 다른 컨텍스트다.

동시에 진행 중인 `fix/boot-4-1-2`는 `build.gradle.kts`의 4행(Boot 플러그인)과 22~30행(빈 줄과 덮어쓰기 셋)을 바꾼다(그 spec 389e760). 이 PR은 33행(BOM)만 바꾸고 그 사이에 바뀌지 않는 줄이 있어, 두 변경을 63b73bc 기준으로 3-way merge하면 충돌 없이 합쳐진다(`git merge-file`로 모의 확인). 먼저 머지된 쪽에 맞춰 나중 쪽이 rebase한다.

볼트 문서(keywords·current-state)는 머지 전에 먼저 반영해 두었다[사용자 결정]. 그래서 머지까지 볼트가 main보다 앞선다. PR을 닫으면 볼트를 되돌린다.

## TDD 검증 기록
적용하지 않는다. 업무 동작을 바꾸지 않는다. 2.1.2에서 생기지 않게 된 상태를 정리하던 문장을 지우는 플랫폼 변경이라 RED가 생기지 않는다. 기존 통합 테스트에서 그 행을 빼고, 회귀는 정식 2.1.2의 전체 `./gradlew test`로 확인한다.

## 결과
범위 절의 결정 1~6을 모두 반영했다. 아래 검증은 2026-10-23에 origin/main 63b73bc로 rebase한 상태에서 정식 2.1.2로 했다.

- **의존성.** `./gradlew dependencyInsight`(runtimeClasspath)에서 `spring-modulith-events-jpa`가 2.1.2(BOM 2.1.2), `spring-boot-autoconfigure`가 4.1.1, `micrometer-tracing`이 1.7.1이다. 2.1.2는 Boot 4.1.2·Micrometer Tracing 1.7.2로 빌드됐지만 BOM에 Boot가 없고 `spring-modulith-core`의 Boot 의존은 optional이다. #85의 덮어쓰기 셋은 그대로다.
- **jar의 GH-1935.** Maven Central의 `spring-modulith-events-jpa-2.1.2.jar`(sha1을 Central의 `.sha1`과 대조)를 `javap`로 읽었다. 상태 `UPDATE`와 `MARK_RESUBMITTED` 모두 `p.completionDate is null`을 가진다. `FIND_BY_STATUS`는 `where p.status = ?1`뿐이고 `DELETE_COMPLETED_BEFORE`는 `completionDate`만 본다.
- **sources jar.** `DefaultEventPublicationRegistry.markFailed(Status, Staleness)`가 `getLastPublicationDate()`로 거르고(GH-1837), `StalenessMonitorConfiguration`은 `addFixedDelayTask`로 등록한다. `processPublications`는 `withFilter` 술어를 부른 뒤 `markResubmitted`를 하므로 main(#76)의 `selected` counter 설명도 그대로 맞다. 태그 `2.1.2`와 2026-10-12에 대조한 upstream 2.1.x db6283c 사이에 이벤트 모듈은 pom 버전만 바뀌었다.
- **rebase.** 충돌 없이 끝났다. main이 같은 파일에 더한 것은 `EventPublicationResubmitter`의 `selected` counter와 `withFilter`, 통합 테스트의 생성자 주입 정리와 counter 인자, domain-model의 다른 문단이다. `git range-diff`에서 이 PR의 코드 커밋 둘은 rebase 전과 같고 domain-model 커밋은 문맥 줄만 다르다.
- **대상 테스트.** `EventPublicationResubmitterIntegrationTest` 7건과 main의 `EventPublicationResubmitSelectedIntegrationTest` 1건이 #110의 fork 2개·컨텍스트 재사용 설정에서 통과했다. 이름을 줄인 `끊긴 기록은 마지막 시도부터 5분이 지나야 실패로 바뀐다`도 그중 하나다.
- **전체 테스트.** 커밋 상태 그대로 `./gradlew test --rerun-tasks`를 돌렸다. 905건이 모두 통과했다(실패 0, 오류 0, 건너뜀 0, 컴파일 포함 4분 38초).
- **남은 문장 찾기.** 검증 절의 grep에 걸린 재전송 설명은 keywords 8.17·8.18의 2026-09-29 이력 줄뿐이다. 갱신 규칙대로 남기고 바로 뒤에 2026-10-12 줄을 더했다.
- **브리프 밖에서 더 고친 것.**
  - keywords 2절 "주의"와 8.17 "왜 이것"의 "공식 문서가 외부 조정이 필요하다고 적는다"는 레퍼런스와 Javadoc에서 찾지 못했다고 적었다. 8.17은 날짜를 붙인 줄로 적었다.
  - keywords 8.17의 2026-09-29 줄에 있던 "바깥 트랜잭션 안에서 부르면 거절한다"는 지금 `Lock` 계약(검사 없음)과 달라 날짜를 붙인 줄로 바로잡았다. 8.27의 인덱스 근거에서 지운 정리 문장도 날짜를 붙여 적었다.
  - keywords 8.18은 버전 올리기 절차가 §4.7에 있다고 가리켰다. 실제 위치인 §4.3으로 고쳤다.
  - 2.1.1 이후 이벤트 모듈 변경 8건을 대조했다. 재전송 경로에 닿는 것은 GH-1935·GH-1837이다. GH-1893(상태가 빈 행도 표시할 수 있다)과 GH-1942(`withMinAge` 적용)는 keywords에 날짜를 붙여 적었고, 이 저장소에는 영향이 없다.
  - current-state의 "2.1.2 GA는 Boot 4.1.2로 올라갈 수 있다"를 확인한 사실(결정 4)로 바꿨다. keywords 8.3의 "아직 릴리스되지 않은 2.1.2"에는 날짜를 붙인 줄을 더했다.
- **multi-review.** Claude는 84점, Codex는 74점이었다. Gemini CLI는 이 머신에 없어 실행하지 못했다. Codex는 리뷰어 에이전트가 CLI를 부르지 않고 돌아와서, `codex exec`를 stdin을 닫고 직접 실행했다. 반영한 것은 아래와 같다.
  - 2단계의 "fail-open이어도 pod 하나만 보낸다"를 Claude는 과장이라고, Codex는 틀렸다고 했다. Claude는 끊긴 기록 판정을 넘긴 재표시를, Codex는 `PROCESSING`인 행의 재표시를 근거로 들었다. 둘 다 소스로 확인했다. 결정 3과 KDoc·domain-model·keywords를 고쳤다. 브리프의 "락은 중복 읽기·경합만 줄인다"도 이 근거로 바꿨다.
  - KDoc에 멱등 consumer 단서를 되살렸다(Claude).
  - monitor 비용 설명을 고쳤다(Claude). 우리 UPDATE도 pod마다 돈다. 그리고 monitor는 `FAILED`를 읽지 않는다.
  - keywords의 `Lock` 거절 문장과 8.27 근거(Claude·Codex), spec의 GH-1893과 2.1.1에서 남은 `FAILED` 행(Claude), GA 뒤 재검증 절차(Claude)를 고쳤다.
  - 반영하지 않은 테스트 제안 셋과 그 이유는 제외 절에 있다. 1단계 조건 회귀 테스트는 두 리뷰어가 모두 짚었다.
  - rebase 뒤에는 다시 돌리지 않았다. rebase가 이 PR의 코드를 바꾸지 않았고(위 range-diff), 그 뒤에 바뀐 것은 이 spec과 볼트 문서뿐이다. 같은 파일에 들어온 main의 `selected` counter 주석과 이 PR의 KDoc은 "락이 빠지면 두 pod가 같은 행을 읽는다"로 서로 맞는지 직접 대조했다.
- **`check_pr.py`.** `--base origin/main --worktree`와 `--worktree` 없는 실행이 모두 PASS다.
