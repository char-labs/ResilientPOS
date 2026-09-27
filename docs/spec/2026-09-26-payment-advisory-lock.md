# PR spec: 같은 checkout의 첫 결제 시도를 DB 제약으로 하나로 묶는다

> 파일 이름은 첫 설계(advisory lock)에서 왔다. 리뷰에서 DB 제약으로 방향을 바꿨고, 바뀐 이유는 "검토한 대안" 절에 있다. spec 첫 커밋 이력을 지키려고 파일 이름은 그대로 둔다.

## 목적
`PaymentManagement.createAttempt`는 그 checkout의 승인 시도를 결말과 무관하게 읽고, 없을 때만 새 시도를 넣는다. 같은 `OrderPaymentRequested`가 동시에 두 번 오면 두 트랜잭션이 모두 "없음"을 읽는다. 앞쪽이 커밋되고 그 시도가 거절(`DECLINED`)된 뒤에 뒤쪽이 커밋하면, partial unique `ux_payment_attempt_inflight_authorization`이 `DECLINED`를 술어에서 빼고 보므로 뒤쪽의 삽입이 통과한다(`PaymentManagement.kt`의 `ponytail:` 표식).

결과는 이중 승인이 아니다. 그 partial unique가 살아 있는 승인 시도를 checkout당 하나로 묶기 때문이다. 결과는 **사용자가 요청하지 않은 재결제**다. 거절된 결제를 provider에 한 번 더 요청하고, 같은 사실의 재배달이 재결제 의도로 바뀐다. 거절 뒤 재결제는 사용자가 명시적으로 요청하는 `retryAuthorization`만 할 일이다([payment-retry-after-decline](2026-09-26-payment-retry-after-decline.md)).

지금은 재발행이 꺼져 있어 동시 배달이 없다. Kafka 도입 설계(브랜치 `feature/kafka-event-redelivery`의 `docs/spec/2026-09-26-kafka-event-redelivery.md`, 아직 main 밖)에서 consumer가 at-least-once로 전달하면 리밸런스 순간에 같은 사실이 동시에 올 수 있다. 이 PR은 Kafka와 무관하게 main 위에서 그 창을 먼저 닫는다.

기대 결과: 같은 checkout 회차에 **사실로 만든 첫 승인 시도**는 DB가 하나로 묶는다. 겹친 생성의 진 쪽은 커밋에서 unique 위반으로 실패하고 아무것도 남기지 않는다.

## 범위
소유 모듈은 `payment`다.

**재결제로 만든 시도에 원래 시도를 적는다.** `t_payment_attempt.retried_from uuid null`을 더한다. `PaymentAttempt.retry`가 만든 시도는 거절된 원래 시도의 `id`를, `PaymentAttempt.create`가 만든 시도는 null을 가진다. aggregate에 `retriedFrom: PaymentId?`가 생기고 엔티티가 그대로 옮긴다.

**사실로 만든 첫 승인 시도를 unique로 묶는다.** `ux_payment_attempt_first_authorization on t_payment_attempt (order_id, checkout_no) where kind = 'AUTHORIZATION' and retried_from is null`. 같은 `(orderId, checkoutNo)`의 `OrderPaymentRequested`로 만드는 시도는 이 한 행뿐이다. 앞 시도가 거절돼도 술어에서 빠지지 않으므로 기존 partial unique가 못 막던 창을 닫는다. 기존 partial unique는 그대로 둔다. 그것은 재결제를 포함해 살아 있는 승인 시도를 하나로 묶는 제약이고, 이 제약은 사실의 멱등 키다.

**겹친 생성의 진 쪽은 실패한다.** 앞쪽 커밋 뒤 뒤쪽의 삽입(커밋 시 flush)이 이 제약에 걸려 `DataIntegrityViolationException`으로 실패한다. 리스너가 던지므로 발행 기록이 미완료로 남고, 재전달(Kafka)이 오면 `findAuthorization`이 앞 시도를 찾아 돌려준다. 지금 진행 중인 시도끼리 겹칠 때의 동작과 같다. 진 쪽을 예외 없이 앞 시도로 돌려주는 처리(`on conflict`)는 하지 않는다.

**`ponytail:` 표식을 지우고** `createAttempt`·`findAuthorization`의 설명을 이 제약 기준으로 고친다. 리스너 주석(진 쪽은 커밋에서 unique 위반)은 그대로 참이다. 적용된 V006의 주석은 Flyway checksum 때문에 고치지 않고 V008 주석이 이어받는다.

계약: 이벤트와 `listener_id`, 오류 code는 바뀌지 않는다. `retriedFrom`은 이벤트에 싣지 않는다.

복구: 진 쪽 실패는 트랜잭션 롤백이라 행이 남지 않는다. 발행 기록 `FAILED`는 기존 metric(`event.publication.incomplete`)에 잡힌다.

관측성: 새 로그·metric은 없다.

## 제외
- Kafka consumer와 재배달(별도 PR).
- 진 쪽을 예외 없이 앞 시도로 돌려주는 것(`insert ... on conflict do nothing` 뒤 재조회). 재전달이 같은 결과를 내고, JPA `persist` 경로를 네이티브 삽입으로 바꿔야 한다.
- `fulfillment`의 같은 창(`FulfillmentCommandFacade`의 `ponytail:`). Kafka 재시도가 해소하기로 합의했다.
- `retried_from`에 unique를 걸어 "거절 시도당 재결제 하나"를 DB로 묶는 것. 지금은 재결제의 대상 행 잠금이 막고, 그 경로를 바꾸는 PR이 정한다.
- `retried_from`을 읽는 조회. 지금은 제약만 쓴다.
- 진 쪽 실패를 흡수하는 것. 진 쪽 발행 기록은 `FAILED`로 남아 진짜 실패와 같은 알림(`event.publication.incomplete{status=failed}`)에 섞이고, `createAttempt`가 바깥 트랜잭션(§4.6 sync intake)에 합류하면 그 트랜잭션 전체가 롤백된다. 리스너나 호출자가 이 제약 이름의 위반만 잡아 `createAttempt`를 한 번 더 부르면 기존 시도를 돌려받는다. 빈도가 생기는 Kafka PR과 sync intake PR이 정한다.

## 검토한 대안
- **`(orderId, checkoutNo)` advisory lock**(이 PR의 첫 구현): 스키마 없이 창을 닫지만, 불변식이 DB 제약이 아니라 앱 코드에 숨는다. 새 삽입 경로가 잠금을 잊으면 다시 열리고, `createAttempt`마다 쿼리가 네 개 늘며(잠금 대기 한도를 걸고 되돌리는 처리 포함), 해시 충돌과 PostgreSQL 전용 기능을 떠안는다. 이 문제는 동시 실행 제어보다 "같은 사실은 시도 하나"라는 멱등 키 문제라서, 저장소 원칙(멱등 키는 unique)대로 제약으로 옮겼다.
- **PR을 닫고 표식만 남기기**: 결과가 이중 승인이 아니라 드문 비의도 재결제라 합리적이지만, Kafka PR이 이 창을 알고 들어가야 한다.
- **Redis·Redisson 잠금**: 커밋과 해제 사이의 창, TTL 만료가 있다. AGENTS.md가 정합성을 Redis로 대신하지 않도록 정했다.

## 관련 문서
[도메인 모델](../wiki/domain-model.md) §4.3·§4.7. 앞 PR은 [payment-attempt-from-order-event](2026-09-22-payment-attempt-from-order-event.md), [payment-gateway-authorization](2026-09-23-payment-gateway-authorization.md), [payment-retry-after-decline](2026-09-26-payment-retry-after-decline.md)다.

## 변경 경로
- src/main/kotlin/com/resilientpos/payment/**
- src/main/resources/db/migration/V008__payment_first_authorization.sql
- src/test/kotlin/com/resilientpos/payment/**
- src/test/kotlin/com/resilientpos/SchemaMigrationTest.kt
- docs/wiki/domain-model.md
- docs/spec/2026-09-26-payment-advisory-lock.md

## 인수 조건
1. 같은 checkout의 `createAttempt`가 겹치고 앞 시도가 거절까지 가도 승인 시도는 하나이고, 진 쪽은 `DataIntegrityViolationException`으로 실패한다.
2. 재결제로 만든 시도는 `retriedFrom`에 거절된 원래 시도를 가진다. 그래서 거절 → 재결제 → 승인 흐름이 새 제약에 걸리지 않는다.
3. 마이그레이션은 backfill하지 않는다. 한 회차에 승인 시도가 둘 이상인 데이터가 있으면 인덱스 생성이 실패해 트랜잭션 전체가 롤백된다.
4. 기존 테스트가 깨지지 않는다.

## 스키마 변경 검토
대상은 `retried_from` 컬럼 추가와 unique 인덱스 `ux_payment_attempt_first_authorization` 추가다. backfill은 없다.

1. 기존 데이터로 도출할 수 있는가: 행 사이의 관계는 도출할 수 있다. 한 checkout의 승인 시도 중 `id`가 가장 작은 것이 첫 시도이고(UUID v7), 나머지는 재결제다. 그러나 unique 인덱스는 행 하나의 값만 볼 수 있어서 "가장 작은 `id`"를 술어로 쓸 수 없다. 첫 시도를 행 자신에서 알아볼 값이 있어야 제약을 걸 수 있다.
2. 지금 코드가 읽는가: 조회는 읽지 않는다. 이 제약이 읽는다. 불리언(`is_retry`)도 같은 제약을 걸 수 있지만, 같은 크기로 어느 거절 시도를 다시 시도했는지까지 남는 `uuid`를 골랐다. 이 관계는 재결제 시점에만 확실하다. 나중에 `id` 순서로 추정하면 재결제가 한 줄로 이어질 때만 맞는다.
3. 나중에 채울 수 있는가: 채울 행이 없어야 한다. 재결제 명령은 HTTP endpoint가 없어 운영에서 불린 적이 없고, 재발행도 꺼져 있어 경합도 없었다. 그래서 한 회차의 승인 시도는 하나뿐이고 모두 null이 맞다. 둘 이상인 행이 있다면 재결제인지 이 PR이 닫는 경합의 흔적인지 데이터로 가릴 수 없다. `id` 순서로 채우는 backfill(`lag(id)`)은 경합 흔적까지 재결제로 분류해 덮으므로 넣지 않는다(리뷰 대조에서 지적).
4. 불변식의 마지막 방어선인가: 그렇다. "같은 사실로 만든 승인 시도는 하나"의 멱등 키다. soft delete된 행에도 걸리는 전체 제약으로 둔다(`deleted_at` 술어 없음). 멱등 키라서 삭제 뒤 재사용하면 안 된다. `findAuthorization`은 `@SoftDelete`로 삭제된 행을 거르지만 이 테이블의 행은 지우지 않는다(V006 주석). 지우는 명령이 생기면 그 조회가 삭제된 행도 보게 해야 한다. Redis·Kafka로 대신하지 않는다.
5. 정본은 하나인가: `retried_from`은 정본이다. 사본이 아니다.
6. check-then-act: `createAttempt`가 읽고 없으면 넣는다. 경합은 이 unique가 막는다(진 쪽은 커밋에서 실패). 재결제는 대상 행 잠금이 직렬화하고, 재결제가 넣는 행은 `retried_from`이 있어 이 제약 밖이다.
7. 락과 크기: V008은 Flyway가 한 트랜잭션으로 돌린다. `add column`이 잡은 `ACCESS EXCLUSIVE`는 커밋까지 유지되므로 뒤의 `CREATE UNIQUE INDEX` 빌드 동안에도 `t_payment_attempt`의 읽기와 쓰기가 모두 막힌다. 지금 행 수에서는 짧다. 행이 커지면 컬럼 추가와 인덱스를 나누고 인덱스는 `CONCURRENTLY` 별도 마이그레이션으로 둔다. V005처럼 `set local lock_timeout = '5s'`를 둔다. 배포 전 위반 행 쿼리 `select order_id, checkout_no, count(*) from t_payment_attempt where kind = 'AUTHORIZATION' and retried_from is null group by 1, 2 having count(*) > 1`가 0행이어야 한다. 0행이 아니면 V008이 통째로 롤백되어(Flyway 이력에도 남지 않는다) 새 버전이 뜨지 않는다. 그때는 사람이 재결제인지 경합 흔적인지 판정하고, 그 행에 `retried_from`을 채우는 수정 마이그레이션을 쓴다. 삭제로는 치울 수 없다(soft delete한 행도 이 제약에 걸린다).
8. 이전 버전 호환: 이전 버전은 `retried_from`을 모르므로 기동한다(nullable, JPA `validate`는 매핑된 컬럼만 본다). 이전 버전의 `createAttempt`는 null로 넣어 새 제약과 맞는다. 이전 버전의 `retryAuthorization`은 null로 넣어 새 제약에 걸리지만 이 명령은 HTTP endpoint가 없어 운영에서 부르는 곳이 없다.
9. 이벤트·캐시: 싣지 않는다.
- 대조 기록: code-reviewer 에이전트(읽기 전용), 2026-09-27, 대조 대상 V008__payment_first_authorization.sql·V006__payment_payment_attempt.sql·PaymentAttempt.kt(create·retry·copy·restore)·PaymentAttemptEntity.kt·PaymentAttemptPersistenceAdapter.kt·PaymentAttemptJpaRepository.kt·PaymentManagement.kt(createAttempt·retryAuthorization)·application.yaml(flyway), 1차 불일치(lag backfill이 경합 흔적을 재결제로 덮음)는 backfill 제거로 반영 확인, 2차 불일치(롤백 뒤 retried_from을 채울 수 없는 복구 절차)는 V008 수정 재배포 절차로 반영 확인, 3차 불일치 없음(보완 권장 두 가지 — V008이 이미 성공한 환경의 checksum, 경합 흔적 행의 처리 — 는 구현자가 7번 답에 더함, 재대조 안 함). 이후 multi-review(Claude·Codex) 지적으로 질문 7의 락 설명을 정정하고(`add column`의 `ACCESS EXCLUSIVE`가 인덱스 빌드 끝까지 유지됨) 복구 절차를 줄였다. 이 변경도 재대조하지 않았다

## 검증
- 경합 재현 통합 테스트: 한 트랜잭션(B)이 `createAttempt`를 마치고 커밋하지 않은 채로, 다른 스레드(A)가 같은 checkout으로 `createAttempt`를 불러 커밋한다. 금액 끝자리가 1이라 `FakePaymentGateway`가 거절한다. A가 거절된 것을 본 뒤 B를 커밋한다. 제약이 없으면 B가 통과해 시도가 둘이 된다(RED). 제약이 있으면 B의 커밋이 unique 위반으로 실패하고 시도는 하나다.
- 재결제 도메인 테스트가 `retriedFrom`을 확인하고, 기존 재결제 통합 테스트(거절 → 재결제 → 승인 → `PAID`)가 새 제약 아래에서 통과한다.
- 마이그레이션은 V007 상태의 DB에 정상 데이터와 한 회차에 승인 시도가 둘인 데이터를 각각 두고 V008을 적용해 확인한다(일회성 확인, 결과에 기록).
- `./gradlew test` — 전부 통과. Docker가 필요하다.
- `python3 scripts/check_pr.py --base origin/main --worktree` — PASS.

## Wiki 영향
- [도메인 모델](../wiki/domain-model.md) §4.3: `payment`의 재배달 멱등 문장에 "동시에 온 같은 사실은 첫 승인 시도 unique가 막는다"를 더한다.
- 현재 상태 문서는 볼트에서 갱신한다.
- 요구사항 변화: 없다. 같은 사실은 시도 하나라는 기존 요구를 동시 배달에서도 지킨다.

## 분할 계획
예상 12개 파일 / 약 400줄(spec 포함). 한도 안이다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 겹친 생성이 거절까지 가도 시도는 하나다 (인수 조건 1) `PaymentAttemptCreationConcurrencyIntegrationTest` | 제약 없는 main 코드로 `./gradlew test --tests '*PaymentAttemptCreationConcurrencyIntegrationTest'` — `Expected an exception of class DataIntegrityViolationException ... but was completed successfully`. B의 커밋이 통과해 시도가 둘이 됐다 | V008 뒤 같은 명령 통과. B의 커밋이 `ux_payment_attempt_first_authorization` 위반으로 실패하고 시도는 하나다 | `./gradlew test` — 243개 통과 |
| 재결제 시도는 원래 시도를 가리킨다 (인수 조건 2) `PaymentAttemptRetryTest` | 속성만 두고 `retry`가 null을 넣은 채로 `--tests '*PaymentAttemptRetryTest'` — `expected: PaymentId(...0001) but was: null` | `retriedFrom = id`로 통과 | `./gradlew test` — 243개 통과 |

## 결과
`./gradlew test` 243개 통과다(main 241 + 신규 2). `python3 scripts/check_pr.py --base origin/main --worktree` PASS.

- `PaymentAttempt`에 `retriedFrom`이 생겼고 `retry`만 채운다. 엔티티는 `retried_from`으로 옮긴다. `PaymentManagement`와 repository는 동작이 그대로이고 주석만 제약 기준으로 바뀌었다(`ponytail:` 표식 삭제).
- V008이 컬럼 추가와 unique 인덱스를 한 트랜잭션으로 한다. backfill은 없다.
- 동작을 지워 확인했다. `retry`가 `retriedFrom`을 비우면 재결제 통합 테스트 2개가 모두 `ux_payment_attempt_first_authorization` 위반으로 실패한다(인수 조건 2).
- 마이그레이션은 일회성으로 확인했다. PostgreSQL 18 컨테이너에 V001~V007을 적용하고 1회차 승인, 2회차 승인, 취소 한 행씩을 넣은 뒤 V008을 적용하면 통과했다. 같은 회차에 거절된 승인 시도를 하나 더 넣고 다시 적용하면 `could not create unique index ... Key (order_id, checkout_no)=(..., 1) is duplicated`로 실패하고 컬럼 추가도 함께 롤백됐다(인수 조건 3).
- 첫 V008은 `lag(id)` backfill로 기존 재결제 행을 채웠다. 리뷰 대조에서 그 backfill이 이미 일어난 경합 흔적까지 재결제로 분류해 "위반이 남으면 기동을 막는다"는 방어가 동작하지 않는다는 지적을 받았고, 채울 행이 원래 없으므로 backfill을 뺐다.
- 기존 테스트 두 개를 고쳤다. `SchemaMigrationTest`에 008을 더했고, `PaymentAttemptPersistenceIntegrationTest`의 1-to-many 사례가 재결제 행에 `retriedFrom`을 주도록 바꿨다(그렇지 않으면 사실로 만든 시도가 한 회차에 셋이 되어 새 제약에 걸린다). `PaymentAttemptSchemaTest`의 컬럼 목록에 `retried_from`을 더했다.
- multi-review(Claude 86점, Codex 68점, Gemini CLI 없음)를 반영했다. 경합 테스트가 걸린 제약 이름을 확인하고, 새 인덱스의 술어(`retried_from IS NULL`, `deleted_at` 없음)를 `PaymentAttemptSchemaTest`로 고정했다. `save` KDoc과 wiki §4.3의 멱등 키 설명, spec 질문 7의 락 설명을 고치고 제외 절에 진 쪽 실패 흡수를 더했다. 테스트 결정성(A의 커밋이 B의 flush보다 먼저다)과 엔티티 `private set`(allOpen 때문에 쓸 수 없다) 지적은 받아들이지 않았다.
- advisory lock 첫 구현(커밋 `bfb760f`)은 이 방향으로 대체했다. 이유는 "검토한 대안" 절에 있다.
