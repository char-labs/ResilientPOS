# PR spec: Modulith 외부 전달의 outbox 모드와 발행 기록 재전송을 비교한다

## 목적
브로커로 보내지 못한 발행 기록은 직접 만든 주기 작업 `platform.EventPublicationResubmitter`(#70)가 다룬다. 끊긴 기록 표시, 500건씩 재전송, Redis `Lock`으로 pod 사이 줄 세우기, 완료 7일 뒤 정리를 이 저장소가 소유한다. Spring Modulith 2.1은 같은 일을 외부 outbox 라이브러리(Namastack Outbox, JobRunr)에 맡기는 outbox 모드(`spring.modulith.events.externalization.mode=outbox`)를 더했다. 지금까지 이 모드는 [Modulith·Kafka 키워드](../wiki/modulith-kafka-keywords.md)(볼트) 3절과 8.3·8.17·8.18·8.26에 대안으로만 적혀 있고, 깊이 비교한 기록이 없다.

기대 결과는 사용자가 채택 여부를 결정할 근거다. 항목별 비교표, 추천안, 다시 볼 조건, 근거 링크를 이 spec의 결과 절에 남긴다. "바꾸지 않는다"도 결론이 될 수 있다.

## 범위
- **문제를 먼저 적는다.** outbox 모드라는 수단보다, 지금 방식에서 이 저장소가 직접 소유하는 코드와 남은 빈틈(fail-open 때의 중복 전송, 영영 나가지 못하는 기록의 기아, 사람이 하는 운영 절차)을 먼저 정리한다. 그다음 제약(README Engineering Principles, Flyway가 스키마를 소유한다는 원칙, 멱등 consumer 전제)과 대조한다.
- **기준선은 draft PR #94의 Modulith 2.1.2 동작이다.** `origin/refactor/modulith-2-1-2-resubmit`과 [#94](https://github.com/char-labs/ResilientPOS/pull/94)의 spec `docs/spec/2026-10-12-modulith-2-1-2-resubmit.md`를 읽기만 한다.
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
- **(2026-10-20 추가) 모듈 안 리스너 후보를 실측한다.** 다시 볼 조건의 전제(모듈 안 리스너가 없다)가 지금 맞는지 사용자가 확인을 요청했다. `@ApplicationModuleListener`로 바꿀 만한 두 자리를 커밋하지 않는 로컬 실험으로 재고, 결과와 사용자 결정을 결과 절에 적는다. 볼트 키워드 문서 8.2·8.28에도 날짜를 붙인 줄을 더한다.

## 제외
- 구현. 채택하더라도 사용자가 결정한 뒤 별도 작업으로 한다.
- 받는 쪽(`@KafkaListener` consumer, 재시도·DLT). outbox 모드도 발행 쪽만 바꾼다.
- #94가 고치는 파일(`build.gradle.kts`, `docs/wiki/domain-model.md`, `EventPublicationResubmitter.kt`와 그 통합 테스트)의 수정.
- 키워드 8.3의 다른 대안(직접 만든 outbox 테이블, CDC)의 비교.
- Modulith 2.1.x BOM이 고정한 버전 밖의 Namastack·JobRunr 평가. 더 높은 버전은 다시 볼 조건에서만 언급한다.

## 관련 문서
없음. 이 PR이 따르는 장기 스펙 문서(DESIGN·concerns·flows)가 없다. 비교의 근거로 읽는 문서는 [kafka-event-redelivery](2026-09-26-kafka-event-redelivery.md), [event-publication-resubmit](2026-09-29-event-publication-resubmit.md), [#94](https://github.com/char-labs/ResilientPOS/pull/94)의 spec `docs/spec/2026-10-12-modulith-2-1-2-resubmit.md`, [도메인 모델](../wiki/domain-model.md) §4.3이다.

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
8. 모듈 안 리스너 후보 둘의 실측과 사용자 결정이 결과 절에 있고, 볼트 키워드 문서 8.2·8.28이 갱신 규칙대로 고쳐져 있다.

## 검증
- 상대 링크: spec이 가리키는 저장소 파일이 모두 있는지 확인한다.
- 외부 링크: 레퍼런스와 예제 URL이 열리는지 확인한다.
- `python3 scripts/check_pr.py --base origin/main --worktree`. 제출 전에는 `--worktree` 없이 한 번 더 실행한다.

## Wiki 영향
- 저장소 wiki: 바뀌지 않는다. 결정 전의 비교라 [도메인 모델](../wiki/domain-model.md) §4.3의 현재 동작 설명이 그대로 맞다. 채택하면 구현 작업이 고친다.
- 볼트: [Modulith·Kafka 키워드](../wiki/modulith-kafka-keywords.md) 3절 Externalization mode, 8.3, 8.17에 비교 결과를 날짜와 함께 적는다. 모듈 안 리스너 후보 실측은 8.2·8.28에 적는다.
- 요구사항 변화: 없다.

## 분할 계획
개발 컨텍스트는 "outbox 모드 비교와 채택 결정의 기록" 하나다. 구현은 결정 뒤 별도 작업이다. 시작 시점은 origin/main 9c34aaf다. 열린 PR은 #94 하나이고, 이 PR은 새 spec 파일 하나만 더하므로 경로가 겹치지 않는다.

## TDD 검증 기록
적용하지 않는다. 문서만 바꾸는 비교 작업이다. 실험은 커밋하지 않는다.

## 결과
### 결론
- **추천: 지금은 outbox 모드로 바꾸지 않는다.** `module-listener` 모드와 `EventPublicationResubmitter`(#94의 2.1.2 동작)를 유지한다.
- 이유는 셋이다.
  1. outbox 모드가 줄여 주는 것은 pod 사이 중복 전송과 재전송 조정 코드다. 중복은 이미 멱등 consumer가 견딘다. 지울 수 있는 코드는 재전송 작업의 두 단계와 그 설정 정도이고, 완료 기록 정리와 metric은 대상 테이블만 바뀌어 남는다.
  2. 대신 새 위험이 생긴다. Namastack 기본 설정에서는 재시도 3회 뒤 `FAILED`가 끝 상태다. 그래서 브로커가 몇 분 멈추면 사람이 SQL로 기록을 되살려야 한다. 역직렬화할 수 없는 기록이 key 10개에 걸리면 모든 전송이 멈춘다. 테이블은 Flyway 밖에서 만들어진다. 기본 폴링 2초가 Kafka 홉마다 더해져 결제 완료까지 5~6초가 걸렸다(기준선 0.2초 이하).
  3. 통합이 아직 어리다. 2.1.1은 routing key의 SpEL을 평가하지 않고 문자열 그대로 저장해서, 모든 기록이 한 key로 묶여 전역 한 줄로 처리됐다(GH-1868, 실측). 이 수정을 포함한 Namastack 관련 수정이 아직 릴리스되지 않은 2.1.2에 들어 있다.
- 후보를 고른다면 Namastack이다. JobRunr보다 key 단위 순서, 폴링 지연, 라이선스가 낫다(항목 8).

### 문제 → 제약 → 수단
- **문제.** 지금 이 저장소가 직접 소유하는 것과 남은 빈틈이다.
  - 소유하는 코드: `EventPublicationResubmitter`(끊긴 기록 표시 UPDATE 한 문장, `FailedEventPublications.resubmit` 한 주기 500건, 완료 7일 뒤 정리), `EventPublicationMetrics`(`event_publication_incomplete`), `Lock.executeWithLockNoTx`의 재전송 용도, `resilientpos.event-publication` 설정 셋.
  - 빈틈 1: 락이 fail-open이거나 TTL을 넘기면 처리 중인 행이 두 번 나갈 수 있다. 멱등 consumer가 견딘다([#94](https://github.com/char-labs/ResilientPOS/pull/94)의 spec `docs/spec/2026-10-12-modulith-2-1-2-resubmit.md`).
  - 빈틈 2: 영영 나가지 못하는 기록의 기아. 리스너가 사라진 기록이 500건을 넘거나 역직렬화할 수 없는 기록이 6건 안팎이면 재전송이 막힌다(키워드 8.26, spring-modulith#1904).
  - 빈틈 3: 탐지와 조치를 사람이 한다([도메인 모델](../wiki/domain-model.md) §4.3).
  - 셋 다 운영에서 관측된 적은 없다. 배포된 환경이 아직 없다.
- **제약.**
  - 스키마는 Flyway 마이그레이션이 소유한다([README](../../README.md)).
  - 모든 consumer는 멱등이고 순서에 기대지 않는다([README Engineering Principles](../../README.md#engineering-principles), [도메인 모델](../wiki/domain-model.md) §4.3).
  - 별도 outbox 테이블을 만들지 않는다는 결정이 [도메인 모델](../wiki/domain-model.md) §4.3에 있다. outbox 모드를 채택하면 이 문장을 고쳐야 한다.
- **수단 판단.** outbox 모드가 메우는 것은 빈틈 1과 조정 코드뿐이다. 빈틈 2는 Namastack에서도 모양만 바뀌어 남고(항목 4), 빈틈 3은 `FAILED` 끝 상태 때문에 오히려 사람 손이 늘어난다.

### 비교표
근거 표시: [문서] 공식 문서, [소스] 라이브러리 소스(버전은 아래 근거 절), [실측] 이 작업의 로컬 실험, [추론] 소스와 실측에서 이끈 판단, (미확인) 확인하지 못한 것.

| 항목 | 기준선: `module-listener` + 재전송 작업(#94, 2.1.2) | outbox 모드 + Namastack 1.7.3 | 판정 |
|---|---|---|---|
| 1 호환성 | 지금 동작한다. #94가 2.1.2-SNAPSHOT으로 전체 테스트 590건을 통과했다 [#94 spec] | 2.1.0부터 BOM에 `spring-modulith-starter-namastack`(1.7.3)과 `spring-modulith-starter-jobrunr`(8.6.1)가 있다 [소스: BOM]. Boot 4.1.1·Jackson 3·Kotlin 2.4.10·PostgreSQL 18에서 기동하고 주문부터 fulfillment까지 흐름이 끝난다 [실측]. 2.1.1은 routing key를 SpEL 문자열 그대로 저장한다(GH-1868, 2.1.2에서 수정) [실측, 소스] | 2.1.2 GA 전에는 쓸 수 없다 |
| 2 저장 | `event_publication`(V004·V009, Flyway). 업무 커밋과 같은 트랜잭션에 쓴다. 트랜잭션 밖 발행은 `PUBLISHED`로 남고 전송되지 않는다 [실측]. 재전송 작업이 `stale-after` 뒤 보낸다 [추론] | `outbox_instance`·`outbox_partition`·`outbox_record`를 기동 때 직접 만든다(`namastack.outbox.jdbc.schema-initialization.enabled` 기본 true) [실측, 소스]. 롤백하면 기록과 전송이 0건, 커밋하면 1건이다 [실측]. `event_publication` 행은 생기지 않는다 [실측]. 트랜잭션 밖 발행도 기록되어 전송된다 [실측] | 기준선. Flyway 소유를 지키려면 DDL을 마이그레이션으로 옮기고 자동 생성을 꺼야 하며, Namastack을 올릴 때마다 DDL을 대조해야 한다 |
| 3 여러 pod 조정·중복 | Redis `Lock`(fail-open)과 조건부 UPDATE. fail-open·TTL 초과 때 처리 중인 행의 중복이 남는다 [#94 spec] | 256개 가상 파티션을 인스턴스가 나눠 소유한다(heartbeat 5초, stale 30초, rebalance 10초). 정상 운영에서는 같은 기록을 두 pod가 동시에 보내지 않는다. 전송 뒤 완료 표시 전에 죽으면 30초 남짓 뒤 다른 pod가 다시 보낸다 [소스]. 실측은 인스턴스 하나(256개 모두 소유)뿐이고 여러 pod 동작은 (미확인) | Namastack. 다만 남는 중복을 멱등 consumer가 견디는 것은 같다 |
| 4 실패 처리 | 상한 없이 매분 오래된 것부터 500건 다시 보낸다. 브로커가 돌아오면 모두 나간다 [키워드 8.26]. 기아: 리스너가 사라진 기록 500건 초과, 역직렬화할 수 없는 기록 6건 안팎 [키워드 8.26, 추론] | 재시도 기본 3회(2·4·8초)를 소진하면 `FAILED`가 끝 상태다 [소스]. `stop-on-first-failure`(기본 true)라 그 key의 뒤 기록도 영구히 멈추고 key 조회에서 빠진다 [소스]. `FAILED`를 되살리는 API가 없고 actuator는 삭제만 한다 [소스]. 그래서 브로커 장애가 재시도 시간을 넘으면 그동안의 주문 key가 모두 멈추고 사람이 SQL로 상태를 되돌려야 한다 [추론]. `retry.max-retries`를 크게 잡으면 피할 수 있다 [소스, 미실측]. 역직렬화할 수 없는 기록은 예외가 그 key 처리를 끊고 `NEW`로 남는다. key 조회가 가장 오래된 것부터 10개(`polling.batch-size`)라 그런 key가 10개면 모든 전송이 멈춘다 [소스, 추론]. Modulith가 등록한 Kafka handler에 fallback handler를 붙이는 방법은 (미확인) | 기준선. 기아는 양쪽에 있고, Namastack은 기본값에서 브로커 장애가 사람 손으로 이어진다 |
| 5 순서 | 보장하지 않는다(`serialize-externalization: false`). consumer가 멱등이고 순서에 기대지 않는다 [도메인 모델 §4.3] | 같은 key(`orderId`) 안에서 발행 순서대로 보낸다 [소스]. 2.1.2-SNAPSHOT에서 주문 하나의 기록 넷이 같은 파티션 번호를 가졌다 [실측]. 2.1.1은 모든 기록이 한 key라 전역 한 줄이다 [실측] | Namastack이 낫지만 지금은 필요 없다. 지금 흐름은 앞 이벤트를 소비한 뒤에 다음 이벤트가 나가므로 인과 순서가 저절로 지켜진다 [추론] |
| 6 관측·운영 | `event_publication_incomplete`(상태별, stale 구분). 탐지 SQL과 조치 절차가 도메인 모델 §4.3에 있다 | metric은 별도 artifact(`namastack-outbox-metrics`)다. 상태별 건수, 파티션별 대기 건수, 인스턴스·파티션 수를 준다. 가장 오래된 대기 기록의 나이 같은 시간 기준 값은 없다 [소스]. actuator(별도 artifact)는 삭제만 한다 [소스]. 1.7.3에는 기간 기준 정리가 없고 완료 즉시 지우는 `deleteCompletedRecords`만 있다 [소스]. 7일 보존을 지키려면 정리 작업을 직접 둔다 | 기준선. metric과 절차를 새로 써야 하고 정리 작업은 남는다 |
| 7 전환 비용 | 해당 없음 | 지울 수 있는 것: 재전송 작업의 끊긴 기록 표시와 재전송 두 단계, `stale-after`·`resubmit-interval` 설정, `Lock`의 재전송 용도, 그 통합 테스트. 새로 생기는 것: starter와 metrics 의존성, outbox 테이블 세 개의 마이그레이션, 폴링·재시도 설정, `outbox_record` 정리 작업, metric과 §4.3 절차의 재작성. 발행 기록을 언급하는 테스트 14개 파일을 확인해야 한다(grep 기준, 모두 바뀌지는 않는다). V004·V009는 지우지 않는다. 모듈 안 리스너가 생기면 다시 쓰이기 때문이다. 지금은 리스너가 모두 `@KafkaListener`라 행이 생기지 않는다 [실측, 코드]. 배포 조건: 전환 전에 미완료 `event_publication` 행이 0건이어야 한다. outbox 모드에서는 그 행을 보낼 externalizer 리스너가 없다 [추론]. 새 컨테이너는 없다 [실측] | 기준선. 코드는 줄지만 새로 쓸 것이 비슷하게 많다 |
| 8 Namastack 대 JobRunr | 해당 없음 | 아래 표 | Namastack |

| 비교 | Namastack 1.7.3 | JobRunr 8.6.1 |
|---|---|---|
| 순서 | key 단위로 지킨다 [소스] | job 단위라 key 순서가 없다 [소스] |
| 테이블 | 셋, 기동 때 자동 생성 [실측] | 자체 SQL 마이그레이션 17개 [소스] |
| 폴링 기본값 | 2초(fixed), adaptive는 1~8초 [소스] | 15초 [소스] |
| 재시도 기본값 | 3회, 2초에서 2배씩, 최대 1분 [소스] | 10회, 3의 거듭제곱 초(마지막 간격 약 16시간) [소스] |
| 완료 기록 | 남기거나 완료 즉시 삭제 [소스] | 성공 job은 36시간 뒤 삭제 [소스] |
| 라이선스 | Apache 2.0 [소스: pom] | LGPL v3(오픈소스판) [문서] |

### 실측
- 환경: Spring Boot 4.1.1, Kotlin 2.4.10, Java 25, Testcontainers(PostgreSQL 18, Redis 8, `apache/kafka:4.1.0`). Modulith는 2.1.1(main)과 2.1.2-SNAPSHOT(`20261016.200821-31`), Namastack은 1.7.3이다.
- 방법: 커밋하지 않는 로컬 변경으로 돌리고 되돌렸다. starter 의존성, `mode: outbox`, 관측용 통합 테스트 하나를 더했다. 테스트는 주문을 세 번 만들고 결제를 요청한 뒤 `PAID`와 fulfillment까지의 시간, 발행 기록, outbox 기록, 토픽별 메시지 수를 출력한다. 롤백, 커밋, 트랜잭션 밖에서 `OrderPaid`를 하나씩 발행하는 경우도 본다.
- 기준선의 지연은 main(2.1.1)에서 쟀다. #94의 변경은 재전송 경로만 바꾸고 첫 전송 경로는 바꾸지 않는다.

| 구성 | 결제 요청 → `PAID` | 결제 요청 → fulfillment | 관찰 |
|---|---|---|---|
| `module-listener`(main, 2.1.1) | 109~176ms | 109~207ms | 이벤트마다 `event_publication` 행 하나, 모두 `COMPLETED` |
| outbox, 2.1.1, 폴링 2초(기본) | 5.1~6.0초 | 7.1~8.0초 | `record_key`가 모두 `#{#this.orderId}` 문자열이고 파티션 번호가 모두 5다. Kafka 메시지 key는 전송 때 다시 평가되어 `orderId`로 맞다 |
| outbox, 2.1.2-SNAPSHOT, 폴링 2초(기본) | 5.1~6.0초 | 7.1~8.0초 | `record_key`가 `orderId`다. 주문마다 파티션이 다르고(81·7·216 등), 한 주문의 기록 넷은 같은 파티션이다 |
| outbox, 2.1.2-SNAPSHOT, 폴링 200ms | 0.59~0.73초 | 0.81~0.94초 | 기록 하나가 완료까지 99~225ms다. 대가는 빈 주기에도 pod마다 200ms에 key 조회 한 번이다 [소스] |

- `PAID`까지 Kafka 홉이 셋(결제 요청, 결제 시도 생성, 승인)이고 fulfillment까지 넷이다. 기본 폴링에서 기록 하나가 완료까지 2.0초 안팎 걸려 홉마다 그만큼 더해졌다.
- 롤백한 발행은 두 모드 모두 기록과 전송이 0건이다. 커밋한 발행은 두 모드 모두 1건이 나간다. 트랜잭션 밖 발행은 outbox 모드에서 1건이 나가고, `module-listener` 모드에서는 `PUBLISHED`로 남아 나가지 않는다. 이 저장소는 Facade 트랜잭션 안에서만 발행하므로 실제 차이는 없다.

### 추천안과 대가
- **추천: 바꾸지 않는다.** 키워드 8.3의 [관례]를 이 비교로 [합의]로 바꾼다.
- 대가
  - 재전송 작업과 그 설정, 락의 재전송 용도, 테스트를 계속 소유한다. Modulith를 올릴 때마다 상태 이름과 컬럼을 대조한다(도메인 모델 §4.3의 버전 올리기 절차).
  - 빈틈 1(fail-open 중복)과 빈틈 2(기아)는 그대로 남는다. 멱등 consumer와 §4.3 절차로 견딘다.
  - key 단위 전송 순서가 없다.

### 다시 볼 조건
1. **같은 주문의 이벤트 순서에 기대야 하는 consumer가 생길 때.** 예를 들어 취소·환불 사실이 결제 완료보다 먼저 도착하면 안 되는 흐름이다. 그때는 Namastack의 key 단위 순서가 요구사항이 된다.
2. **재전송 작업의 운영 부담이 실제로 관측될 때.** 락 fail-open 로그가 잦거나, 기아 때문에 §4.3 조치를 되풀이하거나, `event_publication_incomplete` 경보가 잦은 경우다. 키워드 8.17의 다시 볼 조건과 같다.
3. **통합이 안정될 때.** 2.1.2 GA 뒤 다음 패치까지 outbox 모드 결함이 더 나오지 않거나, BOM이 Namastack 1.10.1을 쓰는 2.2가 GA일 때다. 그때 `FAILED`를 되살리는 API, 기간 기준 정리, 시간 기준 metric이 생겼는지 다시 본다.
- 다시 볼 때의 전제: 모듈 안 리스너가 없어 발행 기록을 쓰는 곳이 externalizer뿐이어야 전환이 단순하다. 모듈 안 리스너가 생기면 발행 기록과 outbox를 함께 운영해야 해서 이득이 줄어든다. 2026-10-20에 후보 두 자리를 실측한 뒤에도 모듈 안 리스너를 두지 않기로 해서 이 전제는 그대로다(아래 절).

### 모듈 안 리스너 후보 실측 (2026-10-20 추가)
위 전제가 지금 맞는지 확인하려고, `@ApplicationModuleListener`로 바꿀 만한 두 자리를 커밋하지 않는 로컬 실험으로 쟀다.
- **후보 1: `PaymentEvent.AttemptCreated`.** `payment`가 내고 `payment`가 받는데 Kafka를 한 번 거친다. 키워드 8.2의 다시 볼 조건("이 지연이 문제가 되면 그것만 내부 리스너로 되돌린다")에 해당한다.
- **후보 2: `PaymentEvent.Unknown`을 받아 바로 provider에 묻는 리스너.** 지금은 `UnknownPaymentResolver`가 1분마다 묻는다(키워드 8.28). 그 결정은 즉시 조회를 검토하지 않았다.
- **방법.**
  - 후보 1은 `@Externalized`를 빼고 `PaymentAttemptCreatedListener`를 `@ApplicationModuleListener(propagation = NOT_SUPPORTED)`로 바꿨다. 기본 전파(`REQUIRES_NEW`)도 따로 쟀다.
  - 후보 2는 `Unknown`을 받아 `PaymentManagement.resolveUnknown`을 부르는 리스너를 더했다.
  - 측정 테스트가 provider 포트와 결제 시도 repository를 감싸 호출을 세고 지연과 실패를 넣었다.
  - 환경은 위 실측과 같다. 파티션은 3개, Kafka 재시도와 결과 불명 확정 주기는 운영값(1초부터 2배, 1분)이고 재전송 주기만 10초로 줄였다(운영 1분).
  - 홉별 시간은 행의 `created_at`·`updated_at`(같은 JVM 시계)으로 나눴다. 위 기준선 표의 109~176ms와는 예열, 표본 수, 파티션 수가 달라 직접 비교하지 않는다.

순차 20건(예열 3건 뒤), p50 / max:

| 구성 | 시도 생성 → 승인 적용(`AttemptCreated` 홉) | 결제 요청 → `PAID` |
|---|---|---|
| Kafka(지금) | 13.1 / 16.4ms | 43.1 / 52.5ms |
| 내부 리스너 `NOT_SUPPORTED` | 4.4 / 5.8ms | 36.4 / 40.0ms |
| 내부 리스너 `REQUIRES_NEW` | 3.8 / 14.1ms | 33.1 / 310.5ms |

결제 요청 40건 연속, provider 응답 300ms:

| 구성 | 마지막 `PAID`까지 | provider 동시 호출 | 결제 요청 홉 p50 / max | 승인 홉 p50 / max | 공용 executor 대기열 최대 | provider 호출 중 쥔 커넥션 |
|---|---|---|---|---|---|---|
| Kafka(지금) | 6.1초 | 3 | 21 / 39ms | 21 / 33ms | 2 | 0 |
| 내부 리스너 `NOT_SUPPORTED` | 1.8초 | 8 | 303 / 311ms | 382 / 1,014ms | 35 | 0 |
| 내부 리스너 `REQUIRES_NEW` | 1.8초 | 8 | 294 / 307ms | 386 / 1,017ms | 34 | 호출마다 1개 |

- 처리량 차이는 동시 호출 수에서 온다. Kafka는 파티션 수(3), 내부 리스너는 `applicationTaskExecutor` 스레드 수(8)다 [실측].
- 내부 리스너는 Modulith 외부 전달(`EventExternalizerModuleListener`)과 같은 executor에서 돈다. provider 호출이 스레드를 차지하는 동안 다른 모듈로 가는 이벤트의 전송이 줄을 서서 최대 1초 밀렸다 [실측]. provider 지연이나 타임아웃이 길면 그만큼 더 밀린다 [추론].
- `REQUIRES_NEW`에서는 멈춘 리스너 트랜잭션이 provider 호출 내내 커넥션을 쥔다. 8건을 동시에 부르는 동안 활성 커넥션이 8개(풀 10개)였고 커넥션을 기다리는 스레드가 2개 생겼다. `NOT_SUPPORTED`에서는 호출 중인 스레드에 트랜잭션 자원과 물리 커넥션이 없었다(40건 모두) [실측].

실패:

| 경우 | Kafka(지금) | 내부 리스너 `NOT_SUPPORTED` |
|---|---|---|
| 일시 실패(승인 적용의 행 잠금이 2번 `CannotAcquireLockException`) | 1초·2초 뒤 다시 시도해 3.2초에 `PAID`가 됐다. provider 호출 3번 | 바로 `FAILED`가 되고 재전송 주기마다 한 번씩 다시 시도해 13.8초에 `PAID`가 됐다(주기 10초). 운영 주기 1분이면 최대 약 2분이다 [추론]. provider 호출 3번 |
| 버그성 실패(승인 적용이 계속 `IllegalStateException`) | 재시도 허용 목록 밖이라 바로 DLT로 1건 갔다. provider 호출 1번. 고친 뒤에도 시도가 `REQUESTED`로 남아 DLT를 되돌려야 한다 | DLT 없이 재전송 주기마다 provider를 다시 불렀다(35초 동안 4번, 상한 없음). 고친 뒤 다음 주기에 스스로 `PAID`가 됐다 |

- 발행 기록 행 수는 같다. `AttemptCreated`마다 한 행이고 `listener_id`만 바뀐다 [실측].

결과 불명:

| 경우 | 주기 작업(지금, 1분) | 즉시 조회 리스너 |
|---|---|---|
| 조회하면 승인(끝자리 2), 15초 간격 4건의 결제 요청 → `PAID` | 14.9·29.9·44.9·59.9초 | 51·84·72·78ms |
| 계속 불명(끝자리 4)의 provider 조회 | 주기마다 1번 | 즉시 1번과 주기마다 1번 |
| 발행 기록 | 없음 | `Unknown`마다 한 행 |

- `FakePaymentGateway`는 조회에 바로 답한다. 실제 PG는 타임아웃 직후 아직 처리 중이라고 답할 수 있어, 즉시 조회로 확정되는 비율은 (미확인)이다. 이 리스너도 같은 executor에서 provider를 불러 위의 줄 서기를 겪는다 [추론].
- **판단.**
  - 후보 1이 얻는 것은 홉 하나에 약 9ms다. 치르는 것은 공용 executor 경합, 일시 실패 회복이 초에서 분 단위로 느는 것, 버그성 실패에서 상한 없는 provider 재호출, 재전달 경로가 Kafka와 재전송 작업 둘로 갈리는 것(키워드 8.2의 이유)이다.
  - 후보 2의 효과는 실제 PG에 달려 있다. 최대 대기를 줄이는 효과는 `resilientpos.payment.unknown-resolve-interval`을 줄이는 설정 변경으로도 얻는다.
- **다시 볼 조건.**
  - 후보 1: 결제 응답 지연 목표가 생기고 이 홉이 그 예산을 넘을 때. 그때는 공용 executor 경합과 실패 경로(재시도 상한, DLT를 대신할 것)를 함께 정한다.
  - 후보 2: 실제 PG 어댑터 작업이 타임아웃 직후 조회의 확정 비율을 잴 때. 비율이 높으면 즉시 조회를, 낮으면 주기 조정을 본다.

### 근거
- 공식 문서
  - [Spring Modulith 이벤트 레퍼런스](https://docs.spring.io/spring-modulith/reference/events.html): 외부 전달 절은 기본 방식을 "pragmatic, simple solution"이며 "lacks critical features developers might expect from actual outbox pattern implementations"라고 적는다. Namastack·JobRunr 절은 starter를 더하고 `spring.modulith.events.externalization.mode`를 `outbox`로 두라는 내용과 각 라이브러리 문서 링크뿐이다. 재시도, 실패, 순서, 스키마는 말하지 않아 소스로 확인했다.
  - [공식 outbox 예제](https://github.com/spring-projects/spring-modulith/tree/main/spring-modulith-examples/spring-modulith-example-outbox), [Namastack Outbox 문서](https://outbox.namastack.io/), [JobRunr 문서](https://www.jobrunr.io/en/documentation/).
- upstream 이슈
  - [GH-1868](https://github.com/spring-projects/spring-modulith/issues/1868): Namastack 연동의 routing key 해석. 2.1.2 마일스톤, 닫힘.
  - [GH-1913](https://github.com/spring-projects/spring-modulith/issues/1913): Namastack outbox에 원래 이벤트를 저장하고 매핑을 전송 때로 미룬다. 2.1.2 마일스톤, 닫힘.
  - [GH-1896](https://github.com/spring-projects/spring-modulith/issues/1896): routing SpEL을 매핑 전 원래 이벤트로 평가한다. 2.2.0-RC1 마일스톤이고 2.1.x 브랜치에도 들어갔다.
  - [GH-1904](https://github.com/spring-projects/spring-modulith/issues/1904): 역직렬화할 수 없는 발행 기록의 재전송. 열림.
  - 2.1.2 마일스톤 목표일은 2026-10-22이고, 2026-10-19 현재 Maven Central에 없다.
- 소스
  - Spring Modulith: `NamastackOutboxEventRecorder`(2.1.1 태그와 upstream 2.1.x 비교), BOM 2.1.x·main의 Namastack·JobRunr 버전.
  - Namastack 1.7.3 sources jar: `OutboxProperties`(폴링·재시도·처리 기본값), `OutboxProcessingScheduler`(key 단위 처리와 첫 실패에서 멈춤), `JdbcOutboxRecordRepository`(가장 오래된 key부터 batch-size개, 실패한 key 제외), `JdbcOutboxRecordEntityMapper`(역직렬화), `PermanentFailureOutboxRecordProcessor`, `JdbcOutboxSchemaAutoConfiguration`, `OutboxActuatorEndpoint`, metrics binder.
  - JobRunr 8.6.1 sources jar: `RetryFilter`, `BackgroundJobServerConfiguration`, SQL 마이그레이션.
- 저장소: [kafka-event-redelivery](2026-09-26-kafka-event-redelivery.md), [event-publication-resubmit](2026-09-29-event-publication-resubmit.md), [#94](https://github.com/char-labs/ResilientPOS/pull/94)의 spec `docs/spec/2026-10-12-modulith-2-1-2-resubmit.md`(`origin/refactor/modulith-2-1-2-resubmit`에서 읽음), [도메인 모델](../wiki/domain-model.md) §4.3.

### 사용자 결정
- 2026-10-19 사용자가 **바꾸지 않는다**를 골랐다. `module-listener` 모드와 `EventPublicationResubmitter`를 유지한다. 구현 작업은 없다.
- 고르지 않은 안은 "2.1.2 GA 뒤 Namastack 전환"이다. pod 조정과 key 단위 순서를 얻는 대신 `FAILED` 복구 절차, outbox DDL의 Flyway 이관, 정리 작업과 metric 재작성, 폴링 주기 조정이 생긴다.
- 위 다시 볼 조건 셋을 볼트 키워드 문서 8.3에 옮겨 적었다.
- 2026-10-20 모듈 안 리스너 후보 실측을 보고 사용자가 두 후보 모두 **지금처럼 둔다**를 골랐다. `AttemptCreated`는 Kafka를 거치고, 결과 불명은 주기 작업이 확정한다. 고르지 않은 안은 `AttemptCreated`만 내부 리스너로 바꾸기, 결과 불명 즉시 조회 리스너 더하기, 결과 불명 확정 주기 줄이기다. 결과와 다시 볼 조건을 볼트 키워드 문서 8.2·8.28에 적었다.

### 검증 결과
- 실험 변경(`build.gradle.kts`, `application.yaml`, 관측용 테스트)을 모두 되돌렸고 작업 트리에는 이 spec만 바뀌어 있다.
- 모듈 안 리스너 실험 변경(`PaymentEvent.kt`, `PaymentAttemptCreatedListener.kt`, 결과 불명 리스너, 측정 테스트)도 되돌렸다.
- 상대 링크: 가리키는 저장소 파일이 모두 있다. #94의 spec은 아직 main에 없어 PR 링크와 경로로 적었다.
- 외부 링크 9개가 모두 HTTP 200으로 열렸다.
- `python3 scripts/check_pr.py --base origin/main --worktree`: PASS.
