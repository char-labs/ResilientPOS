# PR spec: Spring Modulith 2.1.2에 맞춰 발행 기록 재전송을 정리한다

## 목적
Spring Modulith 2.1.2는 발행 기록의 상태 전이에 완료 조건을 더하고(GH-1935), staleness를 마지막 재전송 시각으로 잰다(GH-1837). [event-publication-resubmit](2026-09-29-event-publication-resubmit.md)(#70)과 [event-resubmit-doc-corrections](2026-10-06-event-resubmit-doc-corrections.md)(#88)은 2.1.1의 결함을 전제로 코드 한 문장과 설명을 남겼다. 이 PR은 BOM을 2.1.2로 올리고 그 문장과 설명을 2.1.2 동작에 맞춘다.

기대 결과는 셋이다. 재전송 작업 1단계는 끊긴 기록 UPDATE 한 문장만 남는다. Modulith staleness monitor를 쓰지 않는 이유는 규모로 적힌다. 락은 정합성 수단이 아니라 경합을 줄이는 수단으로 설명된다.

2.1.2는 아직 Maven Central에 없다(마일스톤 due 2026-10-22). 사용자 결정에 따라 배포된 것처럼 2.1.2를 커밋하고, 검증은 커밋하지 않는 스냅숏 빌드로 한다. PR은 draft로 열고 GA 뒤 정식 버전으로 다시 검증한다.

## 범위
**업스트림 사실.** 2026-10-12에 로컬 클론(`~/ybcha/spring-modulith`)의 `upstream/2.1.x` db6283c 소스로 대조했다.
- GH-1935(b354b8a, main GH-1925의 backport). JPA 저장소의 상태 UPDATE(`markFailed`·`markProcessing`가 쓰는 `UPDATE`)와 `MARK_RESUBMITTED`에 `completionDate is null` 조건이 더해졌다. JDBC·Neo4j도 같다. 그래서 완료된 행은 재전송 표시를 받지 않고, `FAILED`나 `PROCESSING`으로 되돌아가지도 않는다.
- GH-1837(71087ee5). `DefaultEventPublicationRegistry.markFailed(Status, Staleness)`가 `getLastPublicationDate()`로 잰다. 마지막 재전송 시각이 있으면 그것을, 없으면 발행 시각을 쓴다.
- GH-1942. JDBC·JPA가 `minAge`를 무시하던 문제다. 우리는 `minAge`를 쓰지 않아(기본 0) 무관하다.
- GH-1918(ae793f48). staleness 기간 0을 즉시 만료로 보던 문제다. 우리는 세 기간을 모두 0으로 두어 monitor가 등록되지 않으므로 무관하다.
- spring-modulith#1904(역직렬화할 수 없는 행이 재전송 실행 전체를 끊는다)는 2.1.x에 수정이 없다. 그 한계 설명은 그대로 둔다.
- 2.1.x의 monitor가 하는 일은 다음과 같다. `StalenessMonitorConfiguration`이 `addFixedDelayTask`로 `check-interval`(기본 1분)마다 모든 인스턴스에서 돈다. 상태마다 `findByStatus(status)`를 부른다. 이 JPQL은 `where p.status = ?1`뿐이라 그 상태의 행 전부를 직렬화된 이벤트까지 엔티티로 읽는다. 그다음 자바에서 시각으로 거르고, 남은 행마다 `markFailed`를 불러 한 건씩 UPDATE한다.
- 완료 표시 두 가지(`MARK_COMPLETED_BY_ID`, `MARK_COMPLETED_BY_EVENT_AND_LISTENER_ID`)는 `status = COMPLETED`와 `completion_date`를 함께 쓴다. 정리 DELETE(`DELETE_COMPLETED_BEFORE`)는 `completion_date`만 보고 상태는 보지 않는다.

**결정**
1. `markStalePublicationsFailed`의 두 번째 문장을 지운다. `RESUBMITTED`이면서 `completion_date`가 찬 행을 `COMPLETED`로 돌리던 문장이다.
   - 그 상태는 완료된 행에 `markResubmitted`가 성공해야 생긴다. 2.1.2는 `completion_date is null`인 행에만 재전송 표시를 하고, 완료 표시는 상태와 완료 시각을 함께 쓴다. 재전송 표시 뒤 완료 표시 전에 프로세스가 죽은 행은 `completion_date`가 비어 있어 첫 문장(끊긴 기록)이 맡는다.
   - 2.1.1에서 그 상태로 남은 행은 3단계 정리가 보존 기간 뒤 지운다. 정리 DELETE가 상태를 보지 않기 때문이다. 배포된 환경이 없어 전환 처리를 따로 두지 않는다.
2. 끊긴 기록 UPDATE와 monitor 끔은 유지한다. 이유를 "monitor 결함 우회"에서 "규모"로 바꾼다[사용자 결정].
   - #70이 짚은 두 결함은 2.1.2에서 고쳐졌다. 하나는 `RESUBMITTED`를 최초 발행 시각으로 재던 것이고(GH-1837), 다른 하나는 완료된 행을 `FAILED`로 덮던 것이다(GH-1935).
   - 그래도 monitor를 쓰지 않는 이유는 비용이다. monitor는 상태마다 전체 행을 메모리로 읽고, 건별로 UPDATE하며, 모든 pod에서 돈다. 브로커 장애로 미완료 행이 쌓이면 이 비용이 행 수와 pod 수에 비례해 는다. 우리 문장은 조건을 건 UPDATE 한 문장이다.
   - 우리 문장의 `completion_date is null` 조건은 metric `stale`과 같은 기준이라 남긴다.
3. `Lock.executeWithLockNoTx`는 그대로 둔다.
   - #70·#88은 "fail-open이면 방금 완료된 행의 재전송은 막지 못한다"고 적었다. 2.1.2에서는 `markResubmitted`가 완료된 행을 잡지 않으므로 이 설명을 고친다. 다른 pod가 먼저 끝낸 행은 다시 나가지 않는다.
   - 락의 역할은 정합성이 아니다. pod 사이에서 같은 `FAILED` 행을 중복으로 읽고 표시를 다투는 일을 줄인다.
   - 중복 전송은 여전히 생길 수 있다. 전송 뒤 완료 표시 전에 프로세스가 죽으면 그 행은 끊긴 기록이 되어 다시 나간다. 그래서 멱등 consumer 전제는 그대로다.
4. BOM `spring-modulith-bom`을 2.1.1에서 2.1.2로 올린다[사용자 결정]. 커밋에는 정식 버전만 두고 스냅숏 저장소를 넣지 않는다. 그래서 GA 전에는 커밋 상태로 의존성을 해석하지 못한다.
5. 통합 테스트 `끊긴 기록은 마지막 시도부터 5분이 지나야 실패로 바뀌고 완료된 재전송 행은 완료로 돌린다`에서 완료된 재전송 행과 그 주석을 빼고, 이름을 `끊긴 기록은 마지막 시도부터 5분이 지나야 실패로 바뀐다`로 줄인다[사용자 결정].
6. 문서를 고친다.
   - `EventPublicationResubmitter` KDoc: 1단계, 2단계의 fail-open 설명, monitor를 쓰지 않는 이유.
   - [도메인 모델](../wiki/domain-model.md) §4.3: 재전송 항목의 1·2단계와 "Modulith 버전을 올리는 PR의 절차" 5번. 이전 spec과 keywords 8.18은 이 절차를 §4.7이라고 불렀지만 실제 위치는 §4.3이다. §4.7 스키마 변경 검토 절은 바뀌지 않는다.
   - [Modulith·Kafka 키워드](../wiki/modulith-kafka-keywords.md)(볼트): 그 문서의 갱신 규칙을 따른다. 머리의 기준 버전, 2절의 Staleness monitor 항목과 재전송 API 동작 항목, 8.17·8.18에 날짜를 붙인 줄, 8.18의 절 번호, 출처를 고친다. 2절 "주의"의 "공식 문서가 외부 조정이 필요하다고 적는다"는 확인하지 못했다. 2026-10-12 레퍼런스와 `FailedEventPublications`·`ResubmissionOptions` Javadoc에 그 문장이 없어서 "(미확인)"으로 표시한다.
   - [현재 상태](../wiki/current-state.md)(볼트): 빌드 선언 표의 Modulith 버전, Modulith 도입 문단의 버전 문장.

**공식 문서 대조.** [Spring Modulith 이벤트 레퍼런스](https://docs.spring.io/spring-modulith/reference/events.html)는 2026-10-12 현재 2.1.1 판이다. 이 페이지는 monitor를 "`PUBLISHED`·`PROCESSING`·`RESUBMITTED`에서 해당 기간보다 오래된 기록을 `FAILED`로 바꾸는 주기 작업"으로, 재전송을 "`FAILED`를 `RESUBMITTED`로 바꾸고 마지막 재전송 시각을 갱신한다"로 설명한다. 무엇을 기준으로 시각을 재는지, 완료된 행을 어떻게 다루는지, 몇 건씩 읽는지는 적지 않는다. 그래서 동작은 소스로 확인했다. 2.1.2 릴리스 노트는 아직 없다. 근거는 마일스톤 2.1.2(due 2026-10-22, open)의 이슈 목록이다. 닫힌 #1935·#1837·#1942·#1918이 있고, 열린 #1956(Spring Boot 4.1.2로 업그레이드)도 있다.

## 제외
- #85가 넣은 BOM 버전 덮어쓰기 셋(`tomcat.version`·`jackson-bom.version`·`kafka.version`). Boot 4.1.2로 올리는 맥락이라 이 PR과 다르다.
- 2.1.2 GA가 Boot 4.1.2를 기준으로 빌드되는 경우(#1956)의 대응. GA 뒤 재검증에서 확인한다.
- monitor로 되돌리기. 사용자가 끊긴 기록 UPDATE 유지를 골랐다.
- 업스트림이 이미 고정하는 저장소 테스트를 우리 쪽에 다시 만드는 일. 완료된 행의 재전송 표시 거절과 실패 표시 거절은 b354b8a의 JPA·JDBC·Neo4j 저장소 통합 테스트가 고정한다.
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
- `build.gradle.kts`의 `spring-modulith-bom`이 2.1.2다. 스냅숏 저장소와 `-SNAPSHOT`은 커밋에 없다. #85의 덮어쓰기 셋은 그대로다.
- 2.1.2-SNAPSHOT으로 바꾼 로컬 변경에서 `./gradlew test` 전체가 통과하고, 쓴 스냅숏 타임스탬프가 결과 절에 있다.
- KDoc, domain-model, 볼트 문서에 아래 설명이 현재 동작으로 남아 있지 않다. keywords의 날짜를 붙인 이력 줄은 갱신 규칙대로 남는다.
  - monitor가 `RESUBMITTED`를 최초 발행 시각으로 잰다.
  - monitor가 완료된 행을 `FAILED`로 덮는다.
  - fail-open이면 방금 완료된 행의 재전송은 막지 못한다.
  - 완료됐는데 `RESUBMITTED`로 남은 행을 `COMPLETED`로 돌린다.
- monitor를 쓰지 않는 이유가 규모(상태별 전체 읽기, 건별 UPDATE, 모든 pod에서 실행)로 적혀 있다.
- `python3 scripts/check_pr.py --base origin/main --worktree`와 `--worktree` 없는 실행이 통과한다.

## 검증
- 대상 테스트: `./gradlew test --tests 'com.resilientpos.platform.EventPublicationResubmitterIntegrationTest'`.
- 전체 테스트: 커밋하지 않는 로컬 변경으로 BOM을 `2.1.2-SNAPSHOT`으로 바꾸고 `repositories`에 `https://repo.spring.io/snapshot`을 잠시 넣는다. 그 상태로 `./gradlew test`를 돌린다. 해석된 스냅숏 타임스탬프는 Gradle 캐시의 jar 이름으로 기록하고, 끝나면 로컬 변경을 되돌린다.
- 남은 문장 찾기: `grep -rn "최초 발행 시각\|FAILED\`로 덮\|막지 못한다\|COMPLETED\`로 돌" src docs`. 볼트 링크인 문서도 포함한다.
- `python3 scripts/check_pr.py --base origin/main --worktree`를 실행하고, 제출 전에는 `--worktree` 없이 다시 실행한다.
- PR 전에 `resilientpos-multi-review`를 돌린다.
- 실패 시나리오: 스냅숏에 GH-1935가 빠져 있으면 재전송 관련 통합 테스트는 통과해도 근거가 틀린다. 그래서 스냅숏 jar의 `JpaEventPublicationRepository`에 `completionDate is null` 조건이 있는지 직접 확인한다.

## Wiki 영향
- [도메인 모델](../wiki/domain-model.md) §4.3: 재전송 1단계·2단계 설명과 버전 올리기 절차 5번을 2.1.2 동작에 맞춘다. 요구사항 변화는 없다. 재전송·정리의 주기, 대상, 상한은 같고, 일어나지 않게 된 상태를 정리하던 문장만 빠진다.
- [Modulith·Kafka 키워드](../wiki/modulith-kafka-keywords.md)와 [현재 상태](../wiki/current-state.md)는 볼트 링크라 이 PR의 diff에 나오지 않는다. 고치는 곳은 범위 절 결정 6에 있다.

## 분할 계획
개발 컨텍스트는 "Modulith 2.1.2로 올리고 재전송 작업을 그 동작에 맞춘다" 하나다. origin/main 7282190에서 시작했고, 2026-10-12 시작 시점에 열린 PR이 없어 경로 겹침이 없다. GA 뒤 정식 버전 재검증과 ready 전환도 이 PR 안에서 한다. Boot 4.1.2 업그레이드와 #85 덮어쓰기 제거는 다른 컨텍스트다.

## TDD 검증 기록
적용하지 않는다. 업무 동작을 바꾸지 않는다. 2.1.2에서 생기지 않게 된 상태를 정리하던 문장을 지우는 플랫폼 변경이라 RED가 생기지 않는다. 기존 통합 테스트에서 그 행을 빼고, 회귀는 2.1.2-SNAPSHOT의 전체 `./gradlew test`로 확인한다.

## 결과
개발 전 작성. 개발 후 실행 결과를 더한다.
