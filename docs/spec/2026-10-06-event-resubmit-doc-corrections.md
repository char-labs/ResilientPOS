# PR spec: 발행 기록 재전송 문서의 틀린 설명 두 가지를 바로잡는다

## 목적
[event-publication-resubmit](2026-09-29-event-publication-resubmit.md)(#70)이 남긴 설명 두 가지가 Spring Modulith 2.1.1 소스와 다르다. spring-modulith#1904를 분석하다가 드러났다. 이 PR은 동작(SQL·스케줄·락)을 그대로 두고 KDoc·테스트 주석·spec·wiki의 설명만 소스에 맞춘다.

1. "재전송 중 Modulith가 행 단위로 예외를 잡고 다음 행으로 넘어간다." 리스너가 사라진 기록에는 맞지만 역직렬화할 수 없는 기록에는 틀렸다.
2. "락이 fail-open이면 완료된 행이 `completion_date`가 찬 채 `RESUBMITTED`로 남아 `maxInFlight` 칸을 차지한다. Modulith의 완료 표시가 `completion_date`가 빈 행만 바꾸기 때문이다." 근거가 틀렸다.

## 범위
2026-10-06에 Gradle 캐시의 sources jar로 대조한 사실이다. 대상은 `spring-modulith-events-core`·`-events-jpa`·`-events-jackson` 2.1.1과 `spring-context` 7.0.9다. 설명은 이 사실에 맞춘다.

**사실 1: 재전송 루프의 예외 처리**(`DefaultEventPublicationRegistry.processPublications`)
- 행마다 `markResubmitted`가 먼저 커밋된다. 이어서 try 블록에서 `inProgress.register(it)`와 `consumer.accept(it)`를 부르고, catch에서 `inProgress.unregister(it)` 뒤에 INFO 로그 `Error republishing event publication …`을 남긴다.
- 진행 중 목록(`inProgress`)의 키는 이벤트 객체다. `register`와 `unregister`가 모두 `getEvent()`를 부른다. JPA 어댑터(`JpaEventPublicationAdapter.getEvent`)는 처음 부를 때 역직렬화하고, 실패하면 결과를 남기지 않아 다음 호출도 다시 역직렬화한다.
- **역직렬화 실패.** `register`에서 예외가 난다. catch의 `unregister`가 다시 역직렬화하다 또 던지므로 예외가 catch 밖으로 샌다. `forEach`가 멈추고 `FailedEventPublications.resubmit`이 던진다(spring-modulith#1904). `Error republishing` 로그는 남지 않는다.
  - `Lock.executeWithLockNoTx`는 블록의 예외를 잡지 않으므로 `resubmitAndPurge`가 던진다. 그 주기의 3단계 정리도 건너뛴다. 로그는 Spring 스케줄러의 ERROR `Unexpected error occurred in scheduled task`(`TaskUtils.LoggingErrorHandler`)와 역직렬화 예외다.
  - 그 행은 `markResubmitted`가 이미 커밋돼 `RESUBMITTED`로 남는다. `findFailedPublications`는 `FAILED`만 읽으므로 다음 주기에는 이 행을 건너뛴다. 1단계가 마지막 시도부터 5분(`stale-after`) 뒤 이 행을 `FAILED`로 되돌린다. 읽기는 오래된 것부터라 이 행이 다시 앞에 잡히고, 되돌릴 때마다 실행이 한 번씩 끊긴다. 그 실행에서 이 행 뒤에 올 행은 다음 주기로 밀린다.
  - 이런 행이 여러 개면 각각 돌아올 때마다 실행을 끊는다. 한 행이 돌아오는 간격은 대략 6주기다(`stale-after` 5분, 주기 1분). 그래서 6건 안팎만 쌓여도 거의 모든 실행이 첫 행에서 끊긴다. 그러면 다른 실패 기록이 나가지 못하고 정리도 돌지 않는다. 소스로 추론한 것이고 실측하지 않았다. 500건 기준은 이 경우에 맞지 않는다.
  - 이 행은 대부분의 시간을 `RESUBMITTED`로 보낸다. 그래서 domain-model §4.3의 탐지 SQL(`FAILED`와 상태가 빈 행)에 잘 잡히지 않는다. 문서의 탐지 SQL 조건에 `RESUBMITTED`를 더한다.
- **리스너가 사라짐.** `PersistentApplicationEventMulticaster.invokeTargetListener`가 행마다 ERROR 로그 `Listener … not found! Skipping invocation and leaving event publication … failed.`를 남긴다. 이어서 `markFailed`로 그 행을 곧바로 `FAILED`로 돌린다. 예외가 나지 않으므로 실행은 다음 행으로 이어진다. 기존 설명대로 매 주기 다시 시도되고, 500건을 넘게 쌓이면 읽기를 모두 차지한다.

**사실 2: 재전송된 기록의 완료 표시**
- `processPublications`는 리스너를 부르기 전에 그 기록을 같은 JVM의 진행 중 목록에 넣는다. externalizer가 끝나면 `CompletionRegisteringAdvisor`가 registry의 `markCompleted`를 부른다. registry는 진행 중 목록에서 기록을 찾으면 ID로 완료를 표시한다(`JpaEventPublicationRepository`의 `MARK_COMPLETED_BY_ID`). 이 UPDATE는 `status`를 `COMPLETED`로 바꾸고 `completion_date`를 채우며, `completion_date` 조건이 없다. `completion_date is null` 조건은 진행 중 목록에서 찾지 못했을 때 쓰는 `MARK_COMPLETED_BY_EVENT_AND_LISTENER_ID`에만 있다.
- 그래서 다른 pod가 먼저 완료시킨 행을 fail-open으로 다시 보내도, 정상 경로에서는 전송이 끝나면 `COMPLETED`로 돌아온다. 완료됐는데 `RESUBMITTED`로 남는 것은 재전송 표시 뒤 완료 표시 전에 프로세스가 죽은 경우 정도다. 1단계의 정리 UPDATE(`RESUBMITTED`이고 `completion_date`가 찬 행을 `COMPLETED`로)는 해가 없으므로 그대로 두고 근거만 고친다.
- 같은 경로에서 재전송이 실패하면 advisor가 `markFailed`를 부른다. 저장소의 UPDATE는 ID와 `status != FAILED`만 조건으로 걸어서, 완료된 행을 `completion_date`가 찬 채 `FAILED`로 되돌린다. 업스트림 결함이다. 1단계의 끊긴 기록 UPDATE는 `completion_date is null` 조건이라 이 행을 건드리지 않는다. `findFailedPublications`의 조건은 `status = FAILED`이거나 상태와 `completion_date`가 모두 빈 행이다. `FAILED` 가지에 `completion_date` 조건이 없으므로 이 행은 다시 읽힌다. 다음 주기에 한 번 더 나가고, 성공하면 `COMPLETED`로 돌아온다. 그동안 발행 기록 metric은 `completion_date is null`인 행만 세므로 `failed`에 잡히지 않는다.

**고칠 곳**
- `EventPublicationResubmitter` KDoc: 1단계의 fail-open 설명, "영영 나가지 못하는 행" 문단.
- `EventPublicationResubmitterIntegrationTest`: 완료된 재전송 행을 넣는 곳의 주석. 테스트 코드는 그대로 둔다.
- [event-publication-resubmit](2026-09-29-event-publication-resubmit.md): 1단계 설명의 괄호, 재전송 결정의 fail-open 항목, 한계의 "영영 나가지 못하는 기록", 결과의 2차 multi-review 항목. 같은 한계 절에 사실 2의 업스트림 결함을 더한다.
- [도메인 모델](../wiki/domain-model.md) §4.3: 1단계 설명, "영영 나가지 못하는 기록"의 로그·기준·탐지 SQL, §4.7 버전 올리기 절차 5번의 완료 표시 대조 항목.
- Modulith·Kafka 키워드(`docs/wiki/modulith-kafka-keywords.md`, 볼트)(볼트): 재전송 API의 동작 항목, 8.17의 fail-open 설명, 8.26의 대가. 그 문서의 갱신 규칙에 따라 날짜를 붙인 정정 줄과 출처를 더한다.
- 현재 상태(`docs/wiki/current-state.md`, 볼트)(볼트): "영영 나가지 못하는 기록이 500건을 넘으면" 문장.

## 제외
- 동작 변경. 1단계 SQL, 재전송 옵션, 스케줄, 락은 그대로다. 정리 UPDATE도 지우지 않는다.
- 역직렬화 실패 행의 격리나 재전송 직접 구현. #70 제외 절의 "영영 나가지 못하는 기록의 격리"에 그대로 남는다.
- 업스트림 수정(#1904, `markFailed`가 완료된 행을 덮는 문제). spring-modulith 기여 작업이 따로 다룬다.
- 6건 안팎 추정의 실측.

## 관련 문서
없음

## 변경 경로
- src/main/kotlin/com/resilientpos/platform/EventPublicationResubmitter.kt
- src/test/kotlin/com/resilientpos/platform/EventPublicationResubmitterIntegrationTest.kt
- docs/spec/2026-09-29-event-publication-resubmit.md
- docs/wiki/domain-model.md

## 인수 조건
- 고칠 곳에 "행 단위로 예외를 잡는다"가 역직렬화 실패까지 덮는 문장으로 남아 있지 않다. 리스너가 사라진 경우와 역직렬화 실패가 구분되어 있다.
- 고칠 곳에 "Modulith의 완료 표시가 `completion_date`가 빈 행만 바꾼다"가 재전송된 기록의 근거로 남아 있지 않다.
- 업스트림 결함(완료된 행이 `FAILED`로 되돌아가 다시 읽힘)이 #70 spec의 한계 절에 있다.
- 코드 diff는 KDoc과 주석뿐이다.
- `./gradlew compileKotlin compileTestKotlin`이 통과한다.
- `python3 scripts/check_pr.py --base origin/main --worktree`가 통과하고, 제출 전 `--worktree` 없는 실행도 통과한다.

## 검증
- `git diff origin/main -- src`로 KDoc·주석 밖의 변경이 없는지 본다.
- `grep -rn "completion_date\`가 빈 행만\|행 단위로 예외" src docs`(볼트 링크 포함)로 남은 문장을 찾는다.
- `./gradlew compileKotlin compileTestKotlin`.
- `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전에는 `--worktree` 없이.

## Wiki 영향
- [도메인 모델](../wiki/domain-model.md) §4.3·§4.7: 재전송 작업의 설명과 탐지 SQL을 사실에 맞춘다. 요구사항 변화는 없다. 동작은 그대로이고 설명만 고친다.
- Modulith·Kafka 키워드(`docs/wiki/modulith-kafka-keywords.md`, 볼트)·현재 상태(`docs/wiki/current-state.md`, 볼트)는 볼트 링크라 이 PR의 diff에 나오지 않는다.

## 분할 계획
개발 컨텍스트는 "#70 문서의 Modulith 동작 설명 정정" 하나다. origin/main 2b47e38에서 시작했고 열린 PR이 없어 경로 겹침이 없다. 업스트림 수정과 역직렬화 실패 행의 격리는 별도 컨텍스트다.

## TDD 검증 기록
적용하지 않는다. 동작을 바꾸지 않는 KDoc·주석·문서 변경이라 새 테스트가 없다. 컴파일과 `check_pr.py`로 확인한다.

## 결과
범위 절의 고칠 곳을 모두 고쳤다. 2026-10-06 실행 결과는 아래와 같다.

- **sources 대조와 브리프의 차이.** 브리프의 두 사실은 sources와 같았다. 대조하면서 아래를 더 확인해 반영했다.
  - 역직렬화 실패로 끊긴 실행에서는 `Error republishing` 로그가 남지 않는다. 예외가 `Lock` 블록 밖으로 나가 3단계 정리도 그 주기에 건너뛴다.
  - 이런 행이 6건 안팎만 쌓여도 거의 모든 실행이 끊긴다. 500건 기준은 리스너가 사라진 경우에만 맞다.
  - 이런 행은 대부분 `RESUBMITTED`로 있어 기존 탐지 SQL에 잘 잡히지 않는다. 문서의 SQL 조건에 `RESUBMITTED`를 더했다.
  - 완료된 행이 `FAILED`로 되돌아가면 `findFailedPublications`에 다시 잡힌다. 한 번 더 나가고 성공하면 `COMPLETED`가 되며, 그동안 metric의 `failed`에는 보이지 않는다.
- **같은 설명이 남아 있던 곳.** 브리프에 없던 세 곳을 더 고쳤다. `EventPublicationResubmitterIntegrationTest`의 주석, domain-model §4.7 절차 5번, #70 spec 1단계의 괄호다. 볼트 문서는 keywords의 재전송 API 동작 항목·8.26과 current-state도 고쳤다. keywords 8.18에는 고칠 설명이 없었다.
- **`git diff origin/main -- src`.** 주석(KDoc, `//`) 줄만 바뀌었다.
- **`./gradlew compileKotlin compileTestKotlin`.** 통과했다.
- **`check_pr.py`.** `--base origin/main --worktree`와 `--worktree` 없는 실행이 모두 PASS다.
