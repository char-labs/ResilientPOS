# PR spec: Modulith 외부 전달의 outbox 모드와 발행 기록 재전송을 비교한다

## 목적
브로커로 보내지 못한 발행 기록은 직접 만든 주기 작업 `platform.EventPublicationResubmitter`(#70)가 다룬다. 끊긴 기록 표시, 500건씩 재전송, Redis `Lock`으로 pod 사이 줄 세우기, 완료 7일 뒤 정리를 이 저장소가 소유한다. Spring Modulith 2.1은 같은 일을 외부 outbox 라이브러리(Namastack Outbox, JobRunr)에 맡기는 outbox 모드(`spring.modulith.events.externalization.mode=outbox`)를 더했다. 지금까지 이 모드는 [Modulith·Kafka 키워드](../wiki/modulith-kafka-keywords.md)(볼트) 3절과 8.3·8.17·8.18·8.26에 대안으로만 적혀 있고, 깊이 비교한 기록이 없다.

기대 결과는 사용자가 채택 여부를 결정할 근거다. 항목별 비교표, 추천안, 다시 볼 조건, 근거 링크를 이 spec의 결과 절에 남긴다. "바꾸지 않는다"도 결론이 될 수 있다.

## 범위
- **문제를 먼저 적는다.** outbox 모드라는 수단보다, 지금 방식에서 이 저장소가 직접 소유하는 코드와 남은 빈틈(fail-open 때의 중복 전송, 영영 나가지 못하는 기록의 기아, 사람이 하는 운영 절차)을 먼저 정리한다. 그다음 제약(README Engineering Principles, Flyway가 스키마를 소유한다는 원칙, 멱등 consumer 전제)과 대조한다.
- **기준선은 draft PR #94의 Modulith 2.1.2 동작이다.** `origin/refactor/modulith-2-1-2-resubmit`과 [modulith-2-1-2-resubmit](2026-10-12-modulith-2-1-2-resubmit.md)를 읽기만 한다.
- **비교 항목은 여덟 가지다.**
  1. 호환성: Modulith 2.1.x BOM에 starter가 있는지와 그 버전, Boot 4.1·Jackson 3·Kotlin·PostgreSQL에서 도는지.
  2. 저장: outbox 테이블을 누가 만드는지(Flyway 소유 원칙과 맞는지), 업무 커밋과 같은 트랜잭션에 쓰이는지, outbox 모드에서도 `event_publication` 행이 생기는지.
  3. 여러 pod의 조정과 중복 전송: 지금의 Redis 락·조건부 UPDATE와 비교한다.
  4. 실패 처리: 브로커 장애 때의 재시도·backoff·상한·격리, 역직렬화할 수 없는 기록과 영영 보내지 못하는 기록(spring-modulith#1904, 키워드 8.26의 기아).
  5. 순서: 같은 key(`orderId`)의 전송 순서. 지금은 보장하지 않고 consumer 멱등에 기댄다.
  6. 관측·운영: metric, 탐지·재전송 절차, 완료 기록 정리.
  7. 전환 비용: 지울 수 있는 코드·설정·테스트·문서, 새 의존성과 테이블, 기존 `event_publication` 미완료 행의 처리(배포 조건), 로컬·테스트 인프라의 변화.
  8. Namastack과 JobRunr 중 어느 쪽이 후보인지.
- **근거의 순서.** 항목마다 공식 문서([Spring Modulith 이벤트 레퍼런스](https://docs.spring.io/spring-modulith/reference/events.html), [공식 outbox 예제](https://github.com/spring-projects/spring-modulith/tree/main/spring-modulith-examples/spring-modulith-example-outbox), 각 라이브러리 문서)를 먼저 본다. 문서가 말하지 않으면 sources jar나 upstream 소스, 기동 로그로 확인하고 버전을 함께 적는다. 확인하지 못한 것은 "(미확인)"으로 표시한다.
- **실측은 커밋하지 않는 로컬 실험으로 한다.** 결과만 이 spec에 적고 실험 변경은 되돌린다.
- **결과 절**에 비교표, 추천안, 다시 볼 조건, 근거 링크를 쓴다. 결론을 낸 뒤 채택 여부를 사용자에게 질문 하나로 묻고 그 답을 같은 절에 적는다.
- 끝나면 볼트의 키워드 문서를 그 문서의 갱신 규칙대로 고친다(3절 Externalization mode, 8.3·8.17에 날짜를 붙인 줄). 볼트 파일이라 저장소 커밋에는 들어가지 않는다.

## 제외
- 구현. 채택하더라도 사용자가 결정한 뒤 별도 작업으로 한다.
- 받는 쪽(`@KafkaListener` consumer, 재시도·DLT). outbox 모드도 발행 쪽만 바꾼다.
- #94가 고치는 파일(`build.gradle.kts`, `docs/wiki/domain-model.md`, `EventPublicationResubmitter.kt`와 그 통합 테스트)의 수정.
- 키워드 8.3의 다른 대안(직접 만든 outbox 테이블, CDC)의 비교.
- Modulith 2.1.x BOM이 고정한 버전 밖의 Namastack·JobRunr 평가. 더 높은 버전은 다시 볼 조건에서만 언급한다.

## 관련 문서
없음. 이 PR이 따르는 장기 스펙 문서(DESIGN·concerns·flows)가 없다. 비교의 근거로 읽는 문서는 [kafka-event-redelivery](2026-09-26-kafka-event-redelivery.md), [event-publication-resubmit](2026-09-29-event-publication-resubmit.md), [modulith-2-1-2-resubmit](2026-10-12-modulith-2-1-2-resubmit.md), [도메인 모델](../wiki/domain-model.md) §4.3이다.

## 변경 경로
- docs/spec/2026-10-18-outbox-mode-comparison.md

## 인수 조건
1. 결과 절에 여덟 항목의 비교표가 있고, 칸마다 근거(문서 링크, 소스 파일과 버전, 실험) 또는 "(미확인)"이 붙어 있다.
2. 추천안 하나와 다시 볼 조건이 있다.
3. 비교의 기준선이 #94의 2.1.2 동작이다.
4. Namastack과 JobRunr 중 후보가 근거와 함께 정해져 있다.
5. 사용자의 채택 결정이 결과 절에 적혀 있다.
6. 볼트 키워드 문서의 3절 Externalization mode와 8.3·8.17이 갱신 규칙대로 고쳐져 있다.
7. spec의 링크가 모두 열리고 `check_pr.py`가 통과한다.

## 검증
- 상대 링크: spec이 가리키는 저장소 파일이 모두 있는지 확인한다.
- 외부 링크: 레퍼런스와 예제 URL이 열리는지 확인한다.
- `python3 scripts/check_pr.py --base origin/main --worktree`. 제출 전에는 `--worktree` 없이 한 번 더 실행한다.

## Wiki 영향
- 저장소 wiki: 바뀌지 않는다. 결정 전의 비교라 [도메인 모델](../wiki/domain-model.md) §4.3의 현재 동작 설명이 그대로 맞다. 채택하면 구현 작업이 고친다.
- 볼트: [Modulith·Kafka 키워드](../wiki/modulith-kafka-keywords.md) 3절 Externalization mode, 8.3, 8.17에 비교 결과를 날짜와 함께 적는다.
- 요구사항 변화: 없다.

## 분할 계획
개발 컨텍스트는 "outbox 모드 비교와 채택 결정의 기록" 하나다. 구현은 결정 뒤 별도 작업이다. 시작 시점은 origin/main 9c34aaf다. 열린 PR은 #94 하나이고, 이 PR은 새 spec 파일 하나만 더하므로 경로가 겹치지 않는다.

## TDD 검증 기록
적용하지 않는다. 문서만 바꾸는 비교 작업이다. 실험은 커밋하지 않는다.

## 결과
개발 전 작성. 비교표, 추천안, 다시 볼 조건, 근거, 사용자 결정을 여기에 쓴다.
