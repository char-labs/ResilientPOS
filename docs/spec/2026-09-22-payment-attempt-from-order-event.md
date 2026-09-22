# PR spec: OrderPaymentRequested를 받아 결제 시도를 기록한다

## 목적
`order`가 낸 사실이 `payment`의 행이 되는 경로를 잇는다. 지금은 aggregate와 테이블이 있지만 둘을 잇는 코드가 없어 실제로 시도가 만들어지지 않는다. 이 저장소의 **첫 프로덕션 이벤트 리스너**이기도 하다.

## 범위
소유 모듈은 `payment`다.

**리스너가 진입점이다.** `@ApplicationModuleListener`가 `OrderPaymentRequested`를 받아 `CreatePaymentAttemptCommand`로 옮기고 Facade를 부른다. 리스너는 옮기는 일만 하고 판단하지 않는다.

**Facade는 구체 클래스다.** [도메인 모델](../wiki/domain-model.md) §4.4가 "인터페이스를 따로 두지 않고 구현이 둘이 될 때 뽑는다"고 정했다. `OrderCommandFacade`와 같은 모양이다. `@Transactional`을 소유하고 repository port에만 의존한다.

**같은 이벤트가 두 번 와도 시도는 하나다.** 넣기 전에 그 checkout의 진행 중인 승인을 읽고, 있으면 그것을 돌려준다. 순차 재전달은 이 읽기가 전부 걸러 낸다.

`order`의 재시도 처리와 달리 **payload를 대조하지 않는다.** `(orderId, checkoutNo)`만 보고 금액이 달라도 기존 시도를 돌려준다. 금액의 유일성은 `t_order_checkout`의 `(order_id, checkout_no)` unique가 이미 지키므로 같은 회차에 다른 금액이 올 수 없다. 금액 재계산이나 부분 결제가 들어오면 그 전제가 깨지고, 그 PR이 대조를 더한다.

읽기와 쓰기 사이에 다른 트랜잭션이 끼어드는 **동시 경합은 읽기로 닫히지 않는다.** 그 창은 `ux_payment_attempt_inflight_authorization`이 닫는다. 진 쪽은 unique 위반으로 실패한다.

**그 예외는 Facade 안에서 잡을 수 있는 것이 아니다.** `EntityManager.persist`는 flush를 미루므로 제약 위반이 `save` 호출이 아니라 **커밋에서** 터진다. 실측으로 확인했다 — 같은 트랜잭션에서 충돌하는 두 건을 넣어도 두 번째 `save`는 조용히 지나가고 커밋이 `DataIntegrityViolationException`을 낸다. 그때는 `createAttempt`가 이미 반환한 뒤라 메서드 안에 잡을 것이 없다. 미완료 발행 기록이 남고 재전달은 Kafka 설계가 맡는다. **행이 두 개가 되지 않는 것이 여기서 지켜야 할 불변식이고, 그것은 지켜진다.**

**새 행은 `EntityManager.persist`로 넣는다.** `PaymentAttemptEntity`는 할당식 `@Id`에 `@Version`이 없어서 Spring Data의 `save`가 `merge`로 가고 행마다 SELECT가 하나씩 더 나간다. [도메인 모델](../wiki/domain-model.md) §4.2가 정한 규약이고 `OrderPersistenceAdapter`의 선례를 따른다.

**미완료 이벤트를 다시 보내는 절차는 이 PR이 정하지 않는다.** 리스너가 실패하면 `event_publication`에 미완료로 남고 자동 재시도가 없다. 재전달은 **Kafka를 들이는 별도 설계**가 맡기로 했다. 지금 리스너는 DB에만 쓰고 외부 호출이 없어 실패 양상이 좁고(DB 장애, 종료 중단), 재전달 주기·횟수·백오프를 정할 근거가 아직 없다.

**스레드 풀과 종료 동작도 바꾸지 않는다.** 실측값은 코어 8, 최대·큐 무제한, **종료 대기 0ms**다. 이 리스너는 짧은 DB 쓰기 하나라 8개 동시 실행으로 충분하고, 외부 provider를 부르는 리스너가 생기는 PR이 그때 정한다. 종료 중이던 리스너는 기다려 주지 않으므로 트랜잭션이 롤백되고 발행 기록이 미완료로 남는다 — 행이 반쯤 쓰이는 일은 없다.

계약: 새 `DomainException` code가 없다. `PaymentAttemptCreated`·`PaymentApproved`를 발행하지만 **받는 리스너가 없어 기록도 전달도 되지 않는다.** 받는 쪽이 생기기 전에 만들어진 승인 사실은 전달되지 않으며 그 주문들의 재처리는 소비자 PR이 정한다. 0원 주문은 리스너 한 번으로 `APPROVED`까지 간다.

복구: 리스너 실패는 미완료 발행 기록으로 남는다. **재전달과 감지는 다른 일인데 둘 다 없다.** 재전달은 Kafka 설계가 맡고, 감지는 아직 아무것도 하지 않는다 — 미완료 기록을 세는 메트릭도 헬스 지표도 주기 점검도 없어서 사람이 `event_publication`을 직접 보기 전에는 드러나지 않는다. 둘 다 알려진 빈틈이다.

관측성: 리스너가 결과를 `orderId`와 함께 **커밋 뒤에** 남긴다. 메서드 안에서 바로 찍으면 아직 커밋되지 않은 것을 기록했다고 말하게 되므로 `afterCommit` 콜백으로 옮겼다. 실패는 Modulith가 미완료 기록으로 남긴다.

## 제외
- `PaymentGateway` 포트와 `FakePaymentGateway`, provider 호출. **바로 다음 PR**이다. 이 PR의 리스너는 0원이 아닌 시도를 `REQUESTED`로 남겨 둔다.
- provider 결과를 적용하는 전이(`APPROVED`·`DECLINED`·`UNKNOWN`)와 `PaymentDeclined`·`PaymentUnknown` 이벤트.
- `order`가 `PaymentApproved`를 받는 리스너. 아래 "알려진 한계"의 순환 문제를 그 PR이 먼저 풀어야 한다.
- 재전달 절차(Kafka), 스레드 풀·큐·거절 정책, 종료 대기 조정.
- 취소·환불 시도를 만드는 경로.
- 조회 API와 HTTP endpoint.

## 알려진 한계
**이 PR이 `payment → order` 의존을 만든다.** 리스너가 `com.resilientpos.order.OrderPaymentRequested`를 import하기 때문이다. 지금은 `order`가 `payment`를 import하지 않아 순환이 아니다.

그런데 [도메인 모델](../wiki/domain-model.md) §2는 `PaymentApproved`가 `order`로 건너간다고 정했다. `order`가 그 이벤트를 소비하는 순간 `order → payment → order` 순환이 되어 `ModuleStructureTest`가 실패한다. 이 PR에서 재현해 확인했다.

푸는 방법이 셋이고 이 PR은 고르지 않는다. 이벤트 전용 공유 모듈을 두거나, `order`가 소비하지 않고 `payment`가 `order`의 공개 command를 부르거나, 중개 모듈을 두는 것이다. 셋 다 정본 §2·§3을 건드리므로 그 결정을 하는 PR이 wiki를 함께 고친다.

## 관련 문서
선행 PR은 [payment-domain-model](2026-09-22-payment-domain-model.md)과 [payment-attempt-persistence](2026-09-22-payment-attempt-persistence.md)다. 정본은 [도메인 모델](../wiki/domain-model.md) §2~§4, 이벤트 인프라의 현재 상태는 [event-publication-registry](2026-09-21-event-publication-registry.md)다.

## 변경 경로
- src/main/kotlin/com/resilientpos/payment/**
- src/main/resources/application.yaml
- src/test/kotlin/com/resilientpos/payment/**
- src/test/kotlin/com/resilientpos/order/internal/application/OrderEventPublicationIntegrationTest.kt
- docs/**

## 인수 조건
1. `OrderPaymentRequested`가 발행되면 `t_payment_attempt`에 행이 하나 생기고 값이 이벤트와 같다.
2. 0원 주문이면 그 행이 `APPROVED`이고 `PaymentApproved`가 발행된다.
3. 0원이 아니면 `REQUESTED`로 남고 `PaymentApproved`가 발행되지 않는다.
4. 같은 이벤트를 두 번 받아도 행이 하나다. 두 번째 호출이 같은 `paymentId`를 돌려준다.
5. 읽기와 쓰기 사이에 다른 트랜잭션이 끼어들어도 행은 하나다. 진 쪽은 unique 위반으로 실패하고 행을 남기지 않는다.
6. 새 행이 `persist`로 들어간다. 저장 한 건에 SELECT가 따라붙지 않는다.
7. 주문 생성부터 checkout까지 실제로 태우면 결제 시도가 만들어진다.
8. 기존 테스트가 하나도 깨지지 않는다.

## 검증
- `./gradlew test` — 전부 통과. Docker가 필요하다.
- `python3 scripts/check_pr.py --base origin/main --worktree` — PASS.
- 리스너는 비동기라 통합 테스트가 결과를 기다려야 한다. 고정 대기 대신 조건이 만족될 때까지 짧게 반복해서 기다린다.
- 문장 수는 Hibernate 통계로 센다. "persist를 쓴다"를 코드로 읽지 않고 SELECT가 늘지 않는 것으로 확인한다.

## Wiki 영향
- [현재 상태](../wiki/current-state.md) — 첫 프로덕션 리스너가 생긴 사실, 중복 이벤트 처리, 재전달이 Kafka 설계로 미뤄진 것, `payment → order` 의존과 순환 예고를 적는다.
- 요구사항 변화: 주문 checkout이 결제 시도를 만든다. 0원 주문은 그 자리에서 승인된다. 외부로 나가는 API는 바뀌지 않는다.

## 분할 계획
예상 9개 파일 안팎 / 약 450줄. 한도 안이다. Gateway와 결과 적용은 목표가 달라 다음 PR로 나눈다.

## TDD 검증 기록
RED는 타입이 없어 컴파일이 먼저 실패했다. [테스트 전략](../wiki/testing-strategy.md)이 컴파일 실패를 RED로 치지 않으므로, GREEN 뒤에 **되돌려서** 각 테스트가 무엇을 지키는지 확인하고 아래에 적었다.

## 결과
`./gradlew test` 165개 통과다(직전 156 + 신규 9).

- **경로가 끝까지 이어진다.** 주문을 만들고 checkout을 요청하면 `t_payment_attempt`에 행이 하나 생긴다. 금액 9000, 통화 KRW, `kind = AUTHORIZATION`, `status = REQUESTED`가 이벤트 값과 같다. 0원 주문은 같은 한 번으로 `APPROVED`까지 간다.
- **`persist` 규약을 문장 수로 확인했다.** 저장에 JDBC 문장이 1개 나간다. 어댑터를 `jpaRepository.save`로 되돌리면 2개가 되어 테스트가 실패한다. 코드를 읽어 "persist를 쓴다"고 넘기지 않았다.
- **중복 이벤트는 읽기가 걸러 낸다.** 같은 checkout으로 Facade를 다시 부르면 행이 늘지 않고 같은 `paymentId`가 돌아온다. 이벤트도 다시 내지 않는다 — 다시 내면 소비자가 같은 사실을 두 번 본다.
- **기존 이벤트 테스트 4건이 깨졌는데 원인은 설계가 아니라 이름이었다.** `OrderEventPublicationIntegrationTest`가 `listener_id like '%PaymentRequestedListener%'`로 자기 리스너를 거르는데, 새 리스너 이름 `OrderPaymentRequestedListener`가 그 문자열을 포함한다. 필터를 정규화된 클래스 이름으로 좁혔다. 이 저장소에 프로덕션 리스너가 처음 생기면서 드러난 것이라 다음 리스너에서도 같은 일이 나지 않게 이유를 주석에 남겼다.
- **리스너가 예외를 삼키지 않는다.** 삼키면 Modulith가 성공으로 기록해 미완료 기록마저 사라진다. 재전달이 Kafka 설계로 미뤄진 지금, 그 기록이 사람이 볼 수 있는 유일한 흔적이다.
- **멱등 조회가 인덱스 술어와 정확히 같다.** 조회에 `deleted_at`을 적지 않았는데 `@SoftDelete`가 붙여 준다. 실제로 나가는 SQL이 `where order_id=? and checkout_no=? and kind=? and status in (?,?,?) and deleted_at is null`인 것을 Hibernate SQL 로그로 확인했다. 조회와 제약이 갈라지면 "조회는 없다는데 INSERT는 거절당하는" 상태가 되므로 눈으로 넘기지 않았다.
- **제약 위반은 `save`가 아니라 커밋에서 터진다.** `persist`가 flush를 미루기 때문이다. 같은 트랜잭션에 충돌하는 두 건을 넣어 확인했다 — 두 번째 `save`는 조용히 지나가고 커밋이 `DataIntegrityViolationException`을 낸다. 그래서 "rollback-only라 못 잡는다"가 아니라 **"메서드 안에 잡을 것이 없다"**가 정확한 이유다. 결론은 같지만 근거를 고쳤다.
- **`payment -> order` 의존이 생겼고 아직 순환은 아니다.** 프로브로 확인했다. `order/internal/application`에 `PaymentApproved`를 받는 클래스를 임시로 두면 `Cycle detected: Slice order -> ...`로 실패하고, 빼면 통과한다. 위 "알려진 한계"에 적었다.

경합 테스트는 두지 않았다. 두 트랜잭션이 읽기와 쓰기 사이에서 정확히 겹치게 만들려면 테스트가 스케줄링을 통제해야 하는데, 이 저장소에 그 장치가 없다. 대신 DB 제약이 그 창을 닫는다는 것은 [payment-attempt-persistence](2026-09-22-payment-attempt-persistence.md)가 같은 checkout에 진행 중인 승인을 두 번 넣어 확인했다.

검토: multi-review를 실행했다(Codex 실행, Claude 실행, Gemini는 CLI가 없어 미실행).

**받았다 — 이벤트 발행이 아무 테스트도 지키지 않는다.** 통합 테스트가 행만 검사해서, Facade의 `transition.events.forEach(eventPublisher::publishEvent)` 한 줄을 통째로 지워도 **161개가 전부 통과했다.** 실제로 지워 재현했다. 이벤트는 이 모듈의 공개 계약이고 소비자가 아직 없어 EPR에도 기록되지 않으므로, 소비자가 붙기 전에 고정해 둬야 한다. 가짜 저장소와 기록용 publisher로 Facade 단위 테스트를 더해 세 가지를 고정했다 — 승인이 필요한 시도는 생성 사실만, 0원은 생성과 승인을 순서대로, 이미 진행 중이면 저장도 발행도 하지 않는다. 같은 변조로 이제 빨개진다.

**받았다 — 종료 대기 서술이 사실과 다르다.** [현재 상태](../wiki/current-state.md)가 "종료할 때 진행 중인 리스너를 2초 기다린다"고 적고 있었고 이 spec도 그대로 옮겼다. 실행기를 직접 읽어 보니 `awaitTerminationMillis = 0`, `waitForTasksToCompleteOnShutdown = false`다. **기다리지 않는다.** 종료 중이던 리스너의 트랜잭션은 롤백되고 발행 기록이 미완료로 남는다. 행이 반쯤 쓰이는 일은 없으므로 동작 자체는 안전하지만 문서가 틀렸다. 양쪽을 실측값으로 고쳤다.

**받았다 — 성공 로그가 커밋 전에 찍힌다.** 리스너 메서드 전체가 트랜잭션 안이라 메서드 안에서 커밋 뒤를 볼 수 없다. 커밋이 실패하면 로그만 남고 행은 없다. 로그를 옮기려면 트랜잭션 동기화 콜백이 필요한데 그 장치를 이 PR에 들이지 않고, 대신 "정본은 로그가 아니라 `event_publication`의 완료 여부"라는 사실을 KDoc과 spec에 적었다.

**확인됨 — soft delete 조건이 멱등 조회에 자동으로 붙는다.** Codex와 내 확인이 같다. 실제로 나가는 SQL이 `... and status in (?,?,?) and deleted_at is null`이다.

### Claude 리뷰 반영

**받았다 — 성공 로그가 커밋 전에 찍힌다.** 경합에서 진 쪽은 정상 반환한 뒤 커밋에서 롤백되는데, 그때 존재하지 않는 `paymentId`가 기록됐다는 로그만 남는다. 이 설계가 정면으로 다루는 실패 경로에서 로그가 거짓말을 한다. `TransactionSynchronization.afterCommit`으로 옮겼다.

**받았다 — 정본이 자기와 싸운다.** [현재 상태](../wiki/current-state.md)에 "프로덕션 리스너가 아직 없다", "첫 실제 리스너 PR이 재전송을 정한다", "Facade는 아직 없다", "`payment`는 `order`를 import하지 않는다" 네 문장이 남아 있었다. 전부 이 PR이 뒤집은 사실이다. 지우거나 현재 사실로 덮었다.

**받았다 — raw `UUID` 주석 세 곳이 거짓 인과를 주장한다.** "`OrderId`를 import하면 순환이 된다"고 적었는데, 모듈 간선은 리스너가 `OrderPaymentRequested`를 받으면서 이미 생겼다. Modulith의 순환 검사는 타입이 아니라 모듈 단위다. 진짜 이유는 **공개 계약을 `order` 타입에서 떼어 놓는 것**이고, 그래야 순환을 푸는 PR이 `payment`의 공개 API를 건드리지 않는다. 셋 다 고쳤다.

**받았다 — 술어 일치를 주석만 지킨다.** `INFLIGHT_STATUSES`에 상태를 더하면 조회는 넓어지고 인덱스는 그대로라, 새로 만들어야 할 시도가 조용히 만들어지지 않는다. `pg_get_indexdef`를 읽어 상수와 양방향 대조하는 테스트를 더했다. `DECLINED`를 상수에 넣어 보니 실제로 빨개진다.

**받았다 — 멱등 반환이 payload를 대조하지 않는다.** `order`의 재시도 처리와 같은 어법이라고 적었는데 사실이 아니다. `(orderId, checkoutNo)`만 본다. 금액의 유일성은 `t_order_checkout`의 unique가 이미 지키므로 지금은 안전하고, 그 전제를 범위 절에 적었다.

**받았다 — 문장 수 테스트의 격리 전제를 이 PR이 깼다.** 비동기 리스너가 같은 `SessionFactory`로 SQL을 내므로 전역 누적 통계에 다른 스레드의 문장이 섞일 수 있다. 제대로 고치려면 `statement_inspector`로 자기 스레드만 세야 하는데 그것은 별도 PR이고, 한계를 테스트 KDoc에 적었다.

**받았다 — 재전달과 감지를 한 덩어리로 미뤘다.** 둘은 다른 일이다. 재전달은 Kafka가 맡지만 **감지는 아무것도 하지 않는다.** 미완료 기록을 세는 메트릭도 헬스 지표도 없어서 "사람이 본다"는 전제가 성립하지 않는다. 복구 문단을 둘로 나눠 적었다.

**받았다 — `application.yaml` 주석과 테스트 KDoc이 만료됐다.** "재전송 절차는 첫 리스너를 넣는 PR이 정한다"가 이 PR을 가리키고 있었다. Kafka 설계로 고쳤다.

**받았다 — Facade 스테레오타입이 선례와 다르다.** `@Component`를 `@Service`로 맞췄다.

**다음 PR로 넘겼다 — 커밋 시점 실패 경로 테스트.** `@TestConfiguration`으로 멱등 조회를 무력화한 데코레이터를 넣으면 스케줄링 통제 없이 결정적으로 검증할 수 있다는 지적이 맞다. 그 테스트가 `event_publication`이 `FAILED`로 남는지 완료 UPDATE가 롤백되는지도 함께 밝혀 주는데, 운영자가 미완료 건을 찾는 쿼리가 거기 달려 있다. 이 PR에 넣으면 크기가 커져 Gateway PR 전에 닫는 것으로 남긴다.

**다음 PR로 넘겼다 — 미완료 기록 감지 장치.** 주기 점검이나 헬스 지표 하나를 Kafka 없이도 넣을 수 있다는 지적이 맞다. 위 복구 문단에 빈틈으로 적었다.

