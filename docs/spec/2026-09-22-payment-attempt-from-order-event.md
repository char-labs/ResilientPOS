# PR spec: OrderPaymentRequested를 받아 결제 시도를 기록한다

## 목적
`order`가 낸 사실이 `payment`의 행이 되는 경로를 잇는다. 지금은 aggregate와 테이블이 있지만 둘을 잇는 코드가 없어 실제로 시도가 만들어지지 않는다. 이 저장소의 **첫 프로덕션 이벤트 리스너**이기도 하다.

## 범위
소유 모듈은 `payment`다.

**리스너가 진입점이다.** `@ApplicationModuleListener`가 `OrderPaymentRequested`를 받아 `CreatePaymentAttemptCommand`로 옮기고 Facade를 부른다. 리스너는 옮기는 일만 하고 판단하지 않는다.

**Facade는 구체 클래스다.** [도메인 모델](../wiki/domain-model.md) §4.4가 "인터페이스를 따로 두지 않고 구현이 둘이 될 때 뽑는다"고 정했다. `OrderCommandFacade`와 같은 모양이다. `@Transactional`을 소유하고 repository port에만 의존한다.

**같은 이벤트가 두 번 와도 시도는 하나다.** 넣기 전에 그 checkout의 진행 중인 승인을 읽고, 있으면 그것을 돌려준다. `order`가 같은 `clientOrderId`에 기존 결과를 돌려주는 것과 같은 어법이다. 순차 재전달은 이 읽기가 전부 걸러 낸다.

읽기와 쓰기 사이에 다른 트랜잭션이 끼어드는 **동시 경합은 읽기로 닫히지 않는다.** 그 창은 `ux_payment_attempt_inflight_authorization`이 닫는다. 진 쪽은 unique 위반으로 실패한다. 그 예외를 같은 트랜잭션 안에서 잡아 다시 읽을 수는 없다 — 제약 위반이 트랜잭션을 rollback-only로 만들기 때문이고, `@ApplicationModuleListener`가 이미 트랜잭션을 소유해 그 위에서 새 트랜잭션을 열 자리도 없다. 그래서 잡지 않고 실패시킨다. 미완료 발행 기록이 남고 재전달은 Kafka 설계가 맡는다. **행이 두 개가 되지 않는 것이 여기서 지켜야 할 불변식이고, 그것은 지켜진다.**

**새 행은 `EntityManager.persist`로 넣는다.** `PaymentAttemptEntity`는 할당식 `@Id`에 `@Version`이 없어서 Spring Data의 `save`가 `merge`로 가고 행마다 SELECT가 하나씩 더 나간다. [도메인 모델](../wiki/domain-model.md) §4.2가 정한 규약이고 `OrderPersistenceAdapter`의 선례를 따른다.

**미완료 이벤트를 다시 보내는 절차는 이 PR이 정하지 않는다.** 리스너가 실패하면 `event_publication`에 미완료로 남고 자동 재시도가 없다. 재전달은 **Kafka를 들이는 별도 설계**가 맡기로 했다. 지금 리스너는 DB에만 쓰고 외부 호출이 없어 실패 양상이 좁고(DB 장애, 종료 중단), 재전달 주기·횟수·백오프를 정할 근거가 아직 없다.

**스레드 풀과 종료 대기도 바꾸지 않는다.** 기본값(코어 8, 큐 무제한, 종료 시 2초 대기)을 그대로 쓴다. 이 리스너는 짧은 DB 쓰기 하나라 8개 동시 실행으로 충분하고, 외부 provider를 부르는 리스너가 생기는 PR이 그때 정한다.

계약: 새 `DomainException` code가 없다. `PaymentAttemptCreated`·`PaymentApproved`가 이제 실제로 발행된다. 0원 주문은 리스너 한 번으로 `APPROVED`까지 간다.

복구: 리스너 실패는 미완료 발행 기록으로 남는다. 사람이 보기 전까지 그대로다. 알려진 빈틈이고 Kafka 설계가 닫는다.

관측성: 리스너가 시작·성공을 `orderId`와 함께 남긴다. 실패는 Modulith가 미완료 기록으로 남기고 advice 밖이라 로그를 직접 남긴다.

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
`./gradlew test` 161개 통과다(직전 156 + 신규 5).

- **경로가 끝까지 이어진다.** 주문을 만들고 checkout을 요청하면 `t_payment_attempt`에 행이 하나 생긴다. 금액 9000, 통화 KRW, `kind = AUTHORIZATION`, `status = REQUESTED`가 이벤트 값과 같다. 0원 주문은 같은 한 번으로 `APPROVED`까지 간다.
- **`persist` 규약을 문장 수로 확인했다.** 저장에 JDBC 문장이 1개 나간다. 어댑터를 `jpaRepository.save`로 되돌리면 2개가 되어 테스트가 실패한다. 코드를 읽어 "persist를 쓴다"고 넘기지 않았다.
- **중복 이벤트는 읽기가 걸러 낸다.** 같은 checkout으로 Facade를 다시 부르면 행이 늘지 않고 같은 `paymentId`가 돌아온다. 이벤트도 다시 내지 않는다 — 다시 내면 소비자가 같은 사실을 두 번 본다.
- **기존 이벤트 테스트 4건이 깨졌는데 원인은 설계가 아니라 이름이었다.** `OrderEventPublicationIntegrationTest`가 `listener_id like '%PaymentRequestedListener%'`로 자기 리스너를 거르는데, 새 리스너 이름 `OrderPaymentRequestedListener`가 그 문자열을 포함한다. 필터를 정규화된 클래스 이름으로 좁혔다. 이 저장소에 프로덕션 리스너가 처음 생기면서 드러난 것이라 다음 리스너에서도 같은 일이 나지 않게 이유를 주석에 남겼다.
- **리스너가 예외를 삼키지 않는다.** 삼키면 Modulith가 성공으로 기록해 미완료 기록마저 사라진다. 재전달이 Kafka 설계로 미뤄진 지금, 그 기록이 사람이 볼 수 있는 유일한 흔적이다.
- **`payment -> order` 의존이 생겼고 아직 순환은 아니다.** 프로브로 확인했다. `order/internal/application`에 `PaymentApproved`를 받는 클래스를 임시로 두면 `Cycle detected: Slice order -> ...`로 실패하고, 빼면 통과한다. 위 "알려진 한계"에 적었다.

경합 테스트는 두지 않았다. 두 트랜잭션이 읽기와 쓰기 사이에서 정확히 겹치게 만들려면 테스트가 스케줄링을 통제해야 하는데, 이 저장소에 그 장치가 없다. 대신 DB 제약이 그 창을 닫는다는 것은 [payment-attempt-persistence](2026-09-22-payment-attempt-persistence.md)가 같은 checkout에 진행 중인 승인을 두 번 넣어 확인했다.
