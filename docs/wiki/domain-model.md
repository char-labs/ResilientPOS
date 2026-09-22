# 도메인 모델과 유비쿼터스 언어

이 프로젝트가 쓰는 DDD 용어의 정의, bounded context 7개의 요약, 코드·스키마·문서가 함께 쓰는 단어 목록이다.
업무 기능 spec을 쓰거나 새 도메인 타입·테이블·이벤트 이름을 만들 때 먼저 읽는다. Facade/Service/Aggregate의
책임 분리 표는 [README의 Engineering Principles](../../README.md#engineering-principles)가 정본이며 여기서 반복하지 않는다.
Kotlin으로 Aggregate·Value Object·Event·Repository를 어떤 형태로 쓰는지는 볼트 규칙 `rules/domain-modeling-kotlin.md`가
담당한다(`.claude/rules` 링크로 `src/**/*.kt` 편집 시 자동 로드).

원문 근거는 [도메인 청사진](../references/05_Domain_Development_Blueprint.md), [개발 가이드](../references/03_Development_Guide.md),
[시스템 설계](../references/02_System_Design.md)다. 원문은 참고 자료라 이 페이지가 정본이고, 원문과 다르게 정한 것은 §4에 적는다.
용어 정의의 골격은 [카카오페이 여신코어 DDD 사례](https://tech.kakaopay.com/post/backend-domain-driven-design/)를 참고했고,
사례의 도식 여섯 장에서 가져온 규약은 §2·§5·§6에, 사례와 다르게 가는 점은 §8에 있다.

## 1. 용어 정의

표의 타입 이름은 목표 모델이다. 지금 저장소에 있는 타입은 `foundation`의 `Money`·`IdentifierGenerator`뿐이며, 현재 구현 상태는 [현재 상태](current-state.md)가 정본이다.

| 용어 | 정의 | 구분 기준 | ResilientPOS 예시 |
|---|---|---|---|
| 도메인 (Domain) | 소프트웨어가 해결하려는 문제 영역 | — | 네트워크 단절과 부분 실패 속에서도 주문·결제 상태가 중복 없이 수렴하는 매장 판매 운영 |
| 유비쿼터스 언어 (Ubiquitous Language) | 기획·개발·문서·코드·스키마가 같은 뜻으로 쓰는 단어 집합 | 한 단어가 두 뜻이면 둘 중 하나를 바꾼다 | §3 용어집. `terminal`(단말)을 코드·테이블·spec이 모두 쓰고 `pos`는 쓰지 않는다 |
| 바운디드 컨텍스트 (Bounded Context) | 한 모델과 그 용어가 유효한 명시적 경계. 경계 밖과는 공개 계약(Facade·이벤트)으로만 통한다 | 같은 단어가 다른 뜻이거나 데이터 소유자가 다르면 경계가 다르다 | Spring Modulith application module 7개 `com.resilientpos.{store,terminal,order,payment,sync,reconciliation,fulfillment}`. `ModuleStructureTest`가 경계를 검사한다. Gradle 모듈이 아니다 |
| 애그리거트 (Aggregate) / 루트 | 한 트랜잭션에서 함께 일관성을 지켜야 하는 객체 묶음. 루트 하나만 외부에 노출되고 루트가 불변식을 소유한다 | "이 둘이 한 트랜잭션에서 반드시 함께 맞아야 하는가?" 예면 같은 aggregate, 아니면 별도 aggregate이고 ID로만 참조한다 | `Order`(루트) + `OrderLine` + checkout 금액. `OrderLine`을 밖에서 바꾸지 않고 `order.changeLine(...)`으로 바꾼다. `Order`와 `PaymentAttempt`는 별개 aggregate라 `orderId`로 참조하고 이벤트로 연결한다 |
| 엔티티 (Entity) | 식별자로 구별되고 생명주기가 있는 객체 | 속성이 모두 같아도 식별자가 다르면 다른 것 | `Order`, `PaymentAttempt`, `TerminalSyncState`, `ReconciliationCase`, `KitchenTicket`. 식별자는 UUID v7을 애플리케이션이 저장 전에 만든다 |
| 값 객체 (Value Object) | 식별자가 없고 속성이 같으면 같은 것. 불변 | "두 인스턴스의 모든 속성이 같으면 같은 것인가?" 예면 값 객체 | `Money`, `OrderId`, `TerminalCode`, checkout 확정 금액. Kotlin `data class` + `of`/`from` 팩토리 |
| 정책 (`*Policy`) | 어느 한 aggregate에 자연스럽게 속하지 않는 계산·판정을 가진 불변 값 객체. 상태가 없고 입력 → 출력만 있다 | 규칙이 두 aggregate 사이에 있거나 매장·버전별로 바뀌면 Policy | `CheckoutPolicy`(통화 scale·반올림·합계), store가 소유하는 판매 정책. DDD 표준 용어로는 도메인 서비스의 한 형태다 |
| 도메인 서비스 (`*Service`) | 한 bounded context 안의 비즈니스 행위를 표현하는 객체 | 규칙이 어느 aggregate에도 자연스럽게 속하지 않을 때만 만든다. 책임 경계는 [README](../../README.md#engineering-principles) | `CheckoutService.request(order, policy)` |
| 애플리케이션 서비스 (`*Facade`) | use case 하나를 대표하는 진입점 | 호출자(Controller·이벤트 리스너·스케줄러)가 누구든 같은 use case면 같은 Facade다. 책임과 금지 사항은 [README](../../README.md#engineering-principles) | `OrderCommandFacade.create(CreateOrderCommand): CreateOrderResult`, `OrderQueryFacade.find(FindOrdersQuery)` |
| 명령 / 조회 / 결과 / 뷰 | 쓰기 의도(`*Command`)와 그 결과(`*Result`), 읽기 의도(`*Query`/`*Criteria`)와 그 투영(`*View`). 규약은 [README](../../README.md#engineering-principles) | 상태를 바꾸면 Command, 바꾸지 않으면 Query. Command는 Facade의 공개 입력이라 aggregate 안에 중첩하지 않는다 | `CreateOrderCommand → CreateOrderResult`, `FindOrdersQuery(OrderSearchCriteria) → OrderSummaryView`. 전체 카탈로그는 [청사진 §4](../references/05_Domain_Development_Blueprint.md) |
| 도메인 이벤트 (Domain Event) | 이미 일어난 한 가지 사실. 불변이고 과거형이다 | aggregate가 상태 전이의 결과로 만들고, Facade가 커밋 안에서 발행하고, Event Publication Registry가 전달한다 | `OrderPaymentRequested`, `PaymentApproved`, `PaymentUnknown`, `SyncGapDetected`, `KitchenTicketCreated`. `OrderCompleted`처럼 여러 사실을 뭉친 이름은 쓰지 않는다 |
| 리포지토리 (Repository) | aggregate 단위로 읽고 저장하는 port | aggregate 전체를 다루면 Repository, 화면용 투영을 만들면 Query repository(§4.2) | `OrderRepository.findById(OrderId): Order?`, `save(Order)` |
| 공개 API (module public surface) | 다른 모듈이 import할 수 있는 것. Facade·ID 값 타입·이벤트만 | `internal` 패키지 아래는 비공개. Modulith 검증이 위반을 빌드 실패로 만든다. 모듈을 건너가는 이벤트만 예외로 `contract`가 소유한다(§4.4) | `payment/PaymentManagement.kt`, `PaymentId.kt`, `PaymentAttemptCreated.kt`처럼 모듈 루트(§4.4) |

## 2. Bounded context 요약

전체 aggregate 맵과 불변식은 [청사진 §2](../references/05_Domain_Development_Blueprint.md)가 원문이다. 여기서는 용어를 맞추기 위한 요약만 둔다.

| Context | Aggregate 루트 | 소유하는 것 | 핵심 불변식 | 공개 Facade |
|---|---|---|---|---|
| `store` | `Store` | 판매 정책, 통화·반올림, 매장 설정 | 정책은 버전으로 관리하고 과거 주문 금액을 사후에 바꾸지 않는다 | `StoreManagement` |
| `terminal` | `Terminal` | 단말 코드, 활성화, credential rotation 이력 | 매장 안에서 코드 유일. 비활성 단말은 인증 불가. 이력 삭제 금지 | `TerminalManagement` |
| `order` | `Order` | line, 할인, checkout 금액·통화, lifecycle | `OPEN`에서만 금액·line 변경. checkout 이후 금액·통화 불변. 결제 승인 금액은 checkout과 일치해야 한다 | `OrderManagement` |
| `payment` | `PaymentAttempt` | provider 참조, provider idempotency, 승인·취소 시도, 결제 정본 상태 | 같은 checkout에 진행 중인 승인 시도는 하나. `UNKNOWN`은 실패가 아니다. provider 결과는 한 번만 적용한다 | `PaymentManagement` |
| `sync` | `TerminalSyncState` | 단말 watermark, operation 접수·중복 제거, gap·conflict | `lastAckedSequence`는 단조 증가. gap을 건너뛰어 전진하지 않는다 | `SyncManagement` |
| `reconciliation` | `ReconciliationCase` | 불일치 case의 근거, 규칙 버전, 승인·조치 감사 기록 | 근거·규칙·감사 없이 해결하지 않는다. 결제 상태를 직접 고치지 않는다 | `ReconciliationManagement` |
| `fulfillment` | `KitchenTicket`, `ReceiptDelivery` | 주문 이후 주방 ticket과 영수증 전달 | 원본 주문당 natural key로 한 번만 생성. 재시도 side effect는 멱등 | 소비 이벤트만 |

컨텍스트 사이의 소유 규칙:
- `payment`만 결제 정본 상태를 바꾼다. `reconciliation`은 case와 근거를 소유하고 결론을 `PaymentManagement.resolveFromReconciliation(command)` 같은 좁은 공개 command로 요청한다.
- `order`와 `payment`는 서로의 테이블·엔티티·repository를 읽거나 쓰지 않는다. 사실은 이벤트로 건너간다(`OrderPaymentRequested` → payment, `PaymentApproved` → order·reconciliation, `OrderPaid` → fulfillment).
- **모듈을 건너가는 이벤트는 `contract` 모듈이 소유한다.** 두 모듈이 서로의 사실을 주고받으면 타입 의존이 양방향이 되어 Modulith의 순환 검사에 걸린다. 양쪽이 함께 의존하는 곳에 그 사실을 둔다. `contract`는 bounded context가 아니다 — 업무 규칙도 상태도 없고 계약만 있다. **다른 업무 모듈의 타입을 들지 않는다**(식별자를 raw `UUID`로 든다). 들면 그 방향으로 간선이 생겨 순환이 되돌아온다. 소비자가 없는 이벤트는 소유 모듈에 남는다 — 경계를 넘는 것만 공유한다.
- `order`는 합계가 0원인 주문도 똑같이 checkout을 확정하고 `OrderPaymentRequested`를 낸다. 전액 할인·프로모션 주문이 거절되지 않고 주문·결제·영수증 조회가 같은 경로로 보이게 하려는 것이다. `payment`는 0원 attempt를 provider 호출 없이 승인으로 기록하고 `PaymentApproved`를 내야 한다.
- `sync`는 접수·중복·순서만 판정하고, 받아들인 명령은 소유 컨텍스트의 Command Facade로 넘긴다. `SyncOperationApplied`는 기술 감사 이벤트이며 `OrderCreated`를 대체하지 않는다.
- `foundation`은 프레임워크 없는 값 타입(`Money`, 식별자 생성기)과 모델링 원시 타입(`Transition`, `DomainEvent`, §4.5)만 공유한다. 주문·결제 같은 업무 개념을 두는 곳이 아니다. 모듈을 건너가는 이벤트는 업무 사실이므로 `foundation`이 아니라 `contract`에 둔다.

### aggregate 안의 구성
루트가 바뀔 때 같은 트랜잭션에서 함께 남겨야 하는 것은 별도 컨텍스트가 아니라 그 aggregate의 자식 엔티티다. 상태 변경과 그 기록이 따로 구현되면 기록이 누락되거나 중복된다. 아래는 [청사진 §2·§6.2](../references/05_Domain_Development_Blueprint.md)를 바탕으로 이 PR에서 정리한 설계안이며, 각 모듈의 첫 마이그레이션이 정본이 된다.

| 루트 | 자식 엔티티 (같은 트랜잭션) | 왜 같은 aggregate인가 |
|---|---|---|
| `Order` | `OrderLine`(판매 시점 상품 코드·이름·단가·수량), checkout(회차별 확정 금액. 통화는 주문의 것이다. 정책 버전은 store 정책이 생길 때 더한다. `Checkout`은 `OrderLine`처럼 도메인 ID `CheckoutId`를 갖고 `t_order_checkout`의 PK는 `id` 하나다. 회차 중복은 unique `(order_id, checkout_no)`가 막는다) | line 변경과 금액, checkout 확정과 상태 전이가 한 커밋으로 맞아야 한다 |
| `PaymentAttempt` | provider 요청·응답 결과. provider idempotency 기록이 attempt의 자식인지 컨텍스트 공용 테이블인지는 payment PR spec에서 정한다 | "provider 결과를 한 번만 적용했다"를 같은 트랜잭션의 기록이 증명한다. 취소는 별도 attempt다([외부 연동 전략](../references/04_External_Integration_Strategy.md)) |
| `Terminal` | credential rotation 이력 | 회전과 이력이 따로 커밋되면 어느 자격증명이 유효한지 알 수 없다 |
| `TerminalSyncState` | operation 접수 기록(`t_sync_operation`) | watermark 전진과 접수 기록이 함께 커밋돼야 gap을 건너뛰지 않는다 |
| `ReconciliationCase` | evidence, 조치·승인 감사 기록 | "근거·감사 없이 해결하지 않는다"는 불변식이 자식과 루트를 묶는다 |
| `Store` | policy 버전 | 정책을 바꿀 때 새 버전 행이 함께 커밋되지 않으면 이미 발행된 checkout이 가리키는 버전이 없어진다 |
| `KitchenTicket`, `ReceiptDelivery` | 없음(단독 루트) | natural key 한 건이면 충분하다 |

자식 엔티티는 루트를 통해서만 만들고 바꾼다. 다른 aggregate의 사실(예: 결제 승인)이 필요하면 자식으로 끌어오지 않고 이벤트로 받는다. 한 컨텍스트에 루트가 여럿일 수 있다(`fulfillment`). 루트 하나가 곧 컨텍스트는 아니다.

## 3. 유비쿼터스 언어 용어집

코드 식별자, 테이블·컬럼 이름, 이벤트 이름, spec 본문은 이 표의 단어를 쓴다. 화면 라벨은 현장 용어("포스기")를 써도 되지만 도메인·스키마 이름은 바꾸지 않는다.
새 단어가 필요하면 PR spec에서 제안하고 머지 시 이 표에 추가한다. 이미 있는 단어에 다른 뜻을 붙이지 않는다.
영어 이름은 직역이 아니라 업무 의미를 담는다. 사례가 납부를 Payment가 아니라 Recovery(한도를 복원한다)로 부른 것처럼, 이 저장소의 `reconciliation`(불일치를 근거로 해소한다), `fulfillment`(주문 이후를 이행한다)도 같은 기준이다.

| 용어 (코드·스키마) | 뜻 | 기각한 대안 | 이유 |
|---|---|---|---|
| `terminal` | 매장에 설치된 판매 단말 | `pos` | 제품 전체가 POS라 자기 참조다. 코드에서 `pos`는 position으로 읽힌다. 원문 요구사항과 오프라인 명령 봉투(`terminalId`)가 이미 `terminal`을 쓴다 |
| `operation` (sync) | 단말이 오프라인에서 만든 명령 한 건. 식별자는 `operationId`(UUID v7) | `receipt` | 한 스키마에서 receipt가 접수증과 고객 영수증 두 뜻으로 쓰였다. 봉투의 `operationId`와 어휘를 맞췄다. 테이블은 `t_sync_operation` |
| `watermark` | 단말별로 서버가 확정한 마지막 sequence(`lastAckedSequence`) | `terminal sync state` | state는 모든 테이블에 해당해 아무 말도 하지 않는다. 기획 문서가 이미 쓰는 단어다. 테이블은 `t_sync_watermark` |
| `sequence` (terminal sequence) | 한 단말 안에서 단조 증가하는 명령 순번. 누락(gap) 탐지의 근거 | — | UUID 생성 순서, Kafka 순서, aggregate version과 다르다. 서로 대체하지 않는다 |
| `checkout` | 결제 시도 회차에 대해 확정한 금액·통화·정책 버전(정책 버전은 store 정책이 생길 때 더한다) | `checkout snapshot` | snapshot은 구현 용어다. 이 값은 "N회차 결제 시도의 확정 금액"이라는 업무 사실이다. 테이블은 `t_order_checkout`. [청사진 §1.1](../references/05_Domain_Development_Blueprint.md)의 `CheckoutSnapshot`·`snapshotOf`는 `Checkout`·`checkoutOf`로 대체한다 |
| `attempt` (payment) | 한 주문에 대한 provider 요청 1회. 식별은 `PaymentId`이고 회차 번호는 두지 않는다. 승인과 취소는 각각 별도 attempt다 | `payment`, `transaction` | 한 요청의 결과(승인·거절·`UNKNOWN`)를 한 회차에 담아야 한다. transaction은 DB 용어와 충돌한다 |
| `UNKNOWN` | provider 결과를 아직 확인하지 못한 결제 상태. 실패가 아니다 | `FAILED`, `TIMEOUT` | timeout은 전송 관찰의 실패이고 결제 결과의 실패가 아니다. `UNKNOWN`에서 새 승인 요청을 재시도하지 않고 provider 조회 → reconciliation으로 수렴한다 |
| `gap` / `duplicate` / `conflict` | sync 판정 결과. 순번 누락 / 이미 접수한 operation의 재전송 / 같은 키에 다른 내용 | 일반 `error`, HTTP 500 | 세 가지는 각각 다른 복구 경로를 가진 업무 결과다. 항목 단위 결과로 돌려주고 부분 성공을 200 하나로 숨기지 않는다 |
| `case` (reconciliation) | 불일치 한 건의 근거·규칙 버전·조치·감사 기록 | `issue`, `ticket` | ticket은 fulfillment의 `KitchenTicket`과 충돌한다 |
| `evidence` | POS·서버·provider 각각이 말하는 사실의 기록 | `proof`, `log` | 세 출처를 나란히 비교하는 것이 reconciliation의 본질이다 |
| `kitchen ticket` | 결제된 주문에서 만든 주방 표시 작업 단위. 주문당 natural key로 1건 | `order ticket` | 주문 자체와 구분한다. 재시도해도 한 번만 만들어진다 |
| `receipt` (fulfillment) | 고객 영수증 전달(`ReceiptDelivery`) | — | sync 쪽에서는 이 단어를 쓰지 않는다(위 `operation` 참고) |
| `store policy` | 통화·반올림·판매 규칙. 버전이 있다 | `settings`, `config` | 정책은 업무 규칙이고 버전으로 과거 주문에 적용된 값을 추적한다 |
| `aggregate version` | 한 aggregate의 상태 순서(`@Version`) | — | 동시 수정 충돌 감지용이다. terminal sequence와 다르다 |
| `contract` (모듈) | 모듈을 건너가는 이벤트와 그 payload를 소유하는 모듈. bounded context가 아니다 | `shared`, `common`, `api` | "공개 계약"이라는 일반어와 겹치지만, 여기서는 **그 계약을 담는 모듈 이름**으로만 쓴다. 모듈 자신의 공개 surface는 §1의 "공개 API"로 부른다. `shared`·`common`은 무엇이든 들어와도 되는 이름이라 경계가 서지 않고, `api`는 §4.4가 쓰지 않기로 한 하위 패키지 이름과 충돌한다 |

이벤트 이름은 과거형 한 가지 사실이다.

| 쓰지 않는 이름 | 대신 쓰는 이름 | 이유 |
|---|---|---|
| `OrderCompleted` | `OrderAccepted`, `OrderPaid`, `FulfillmentCompleted`, `OrderClosed` | "완료"가 결제 완료인지 주방 완료인지 마감인지 읽히지 않는다 |
| `PaymentFailed`(timeout 시) | `PaymentUnknown` | timeout은 결과가 아니라 미확인이다 |
| `SyncCompleted` | `SyncOperationApplied` + 소유 컨텍스트의 사실 이벤트 | 동기화 접수와 업무 사실을 분리한다 |
| `PaymentConfirmed`(provider 승인 시) | `PaymentApproved` | 원문 [개발 가이드 §1.1](../references/03_Development_Guide.md)과 [청사진 §3](../references/05_Domain_Development_Blueprint.md)이 두 이름을 섞어 쓴다. provider 승인 사실은 `PaymentApproved`다. reconciliation 해결 결과의 이벤트 이름은 payment PR의 spec에서 정한다 |

## 4. 확정한 설계 결정

### 4.1 Aggregate 상태 전이는 불변 객체가 새 인스턴스를 반환한다
- 전이 메서드는 자신을 바꾸지 않고 바뀐 aggregate와 발생한 이벤트를 돌려준다. 예: `order.requestCheckout(ids, now)`는 `CHECKOUT_REQUESTED` 상태의 새 `Order`와 `OrderPaymentRequested`를 반환한다. 처음 설계의 인자는 `policy`였다. 합계가 단가 곱 수량의 합뿐이고 `Money`가 통화 자릿수를 강제해서 반올림할 계산이 없으므로 `CheckoutPolicy`와 정책 버전은 할인·세금처럼 반올림이 필요한 첫 PR이 store 정책과 함께 도입한다([order-request-checkout-domain](../spec/2026-09-21-order-request-checkout-domain.md)).
- 근거: 볼트 규칙 `effective-java-for-kotlin.md` Item 17과 `kotlin-idioms-and-design-patterns.md` §1(`order.cancelled(reason)`)이 이미 이 방향이다. 도메인 테스트가 Spring 없이 값 비교로 끝나고, 전이 실패 시 원본이 그대로 남아 실패 원자성(Item 76)이 공짜로 따라온다.
- 원문 [청사진 §1.1](../references/05_Domain_Development_Blueprint.md)의 `status = OrderStatus.CHECKOUT_REQUESTED` 예시는 이 결정으로 대체한다. `@Version` 낙관적 잠금은 JPA 엔티티에 남고, Repository 구현이 새 aggregate를 관리 엔티티에 적용할 때 버전 충돌을 감지한다.

### 4.2 Aggregate와 JPA 엔티티는 별개 클래스다
- aggregate·값 객체·Policy·Service는 `internal/domain`에 두고 JPA·Spring 애너테이션을 갖지 않는다. JPA 엔티티, Spring Data repository, 변환기(`from(aggregate)`, `apply(aggregate)`, `toDomain()`)는 `internal/adapter/persistence/command`에 둔다. Repository 인터페이스는 도메인 쪽, 구현은 adapter 쪽이다.
- 근거: [청사진 §6.1](../references/05_Domain_Development_Blueprint.md) 패키지 구조가 이미 이 분리를 전제하고, [의존성 방향 가드 spec](../spec/2026-09-15-dependency-direction-guard.md)이 "도메인 레이어는 Spring·repository에 의존하지 않는다"는 ArchUnit 규칙을 첫 도메인 PR에 추가하기로 했다. 4.1의 불변 aggregate는 JPA 관리 엔티티와 같은 클래스일 수 없다.
- 이름: JPA 엔티티는 aggregate 이름 + `Entity`(`OrderEntity`, `OrderLineEntity`)이고 `Jpa`를 넣지 않는다. Spring Data repository만 도메인 port(`OrderRepository`)와 단순 이름이 겹치므로 `OrderJpaRepository`처럼 `Jpa`를 넣는다.
- **JPA 연관관계 매핑을 쓰지 않는다.** `@OneToMany`·`@ManyToOne`·`@OneToOne`·`@ManyToMany`와 `@ElementCollection`이 대상이다. 다른 aggregate는 물론 같은 aggregate 안의 자식도 부모 ID 컬럼만 가진 독립 엔티티와 독립 Spring Data repository로 둔다(`OrderLineEntity.orderId`, `OrderLineJpaRepository`). 조립과 변경 반영(자식 ID로 맞춰 갱신·추가·삭제)은 persistence adapter가 한다. fetch 전략·cascade·flush 순서가 매핑 뒤에 숨지 않고 읽는 쿼리가 코드에 보인다. 그래서 자식도 도메인 ID(`OrderLineId`)를 가진다. 위치는 식별자가 아니다. DB FK 제약도 두지 않는다. 참조 컬럼에는 index만 두고, 무결성은 aggregate를 한 트랜잭션으로 저장하는 소유 모듈이 지킨다.
- 엔티티는 `platform.BaseEntity`를 상속한다. Hibernate의 `@CreationTimestamp`·`@UpdateTimestamp`(`SourceType.VM`, 애플리케이션 시각)가 채우는 `created_at`·`updated_at`, `@SoftDelete(TIMESTAMP)`의 `deleted_at`(DB `current_timestamp`)을 준다. 두 시각을 `SourceType.DB`로 두지 않는다. Hibernate 7.4.5가 UPDATE의 generated 값을 읽느라 갱신 행 수 검사를 건너뛰어 `@Version` 충돌이 낙관적 잠금 예외로 나오지 않는다. 한 INSERT의 `created_at`과 `updated_at`은 같지 않을 수 있고 `@Version`을 선언한 엔티티에서 갱신된 적 없는 행은 `version = 0`으로 판정한다([commit-version-conflict-optimistic-lock](../spec/2026-09-21-commit-version-conflict-optimistic-lock.md)). 삭제된 행은 모든 조회에서 걸러진다. JPA callback이나 활성 상태 컬럼으로 직접 구현하지 않는다. `id`는 `BaseEntity`에 없다(도메인 ID는 UUID v7을 저장 전에 만든다). 엔티티 생성자 파라미터에는 기본값을 두지 않는다. 인자 없는 생성자는 `kotlin("plugin.jpa")`가 만들고, 기본값이 있으면 값이 빈 엔티티가 컴파일된다([order-create-order-persistence](../spec/2026-09-16-order-create-order-persistence.md)).
- **`@Version`은 `BaseEntity`에 두지 않고 낙관적 잠금이 필요한 엔티티가 직접 선언한다.** 지금은 aggregate 루트인 `OrderEntity`뿐이다. 루트를 통해서만 바뀌는 `OrderLineEntity`와 넣기만 하는 `OrderCheckoutEntity`에는 없고 두 테이블에 `version` 컬럼도 없다. 기반 클래스를 버전 있는 것과 없는 것 둘로 나누지도 않는다. 공통 기반에 두면 모든 테이블에 `version`이 생겨서 필요 없는 곳에 왜 있는지를 매번 설명해야 한다. 필요한 곳에 선언이 있으면 그 선언이 의도를 말해 준다. 선언할 때 타입은 `Long?`이다. 도메인 ID를 저장 전에 만들기 때문에 primitive면 Spring Data가 새 엔티티를 기존 행으로 보고 `merge`해 SELECT와 UPDATE가 더 나간다. 같은 이유로 `@Version`이 없는 엔티티의 새 행은 Spring Data의 `save`·`saveAll`에 넘기지 않고 persistence adapter가 `EntityManager.persist`로 넣는다. 넘기면 새 행마다 SELECT가 하나씩 더 나간다. 기존 행은 같은 트랜잭션에서 읽은 관리 엔티티라 `apply`만 하면 된다. adapter의 저장 메서드는 `@Transactional`을 선언해 Facade의 트랜잭션에 합류한다. 선언이 없으면 트랜잭션 없이 부른 호출에서 루트만 커밋되고 자식의 `persist`가 실패해 자식 없는 루트가 남는다([base-entity-explicit-version](../spec/2026-09-21-base-entity-explicit-version.md)).
- 비용: aggregate마다 클래스가 둘이고 변환기가 붙는다. 그 대신 도메인 테스트에 DB가 없고, JPA 엔티티 규칙(`data class` 금지, 프록시, 지연 로딩)이 도메인에 새지 않는다.

### 4.3 이벤트 전달과 저장 형식
- 모듈 안 이벤트 전달은 Spring Modulith Event Publication Registry(`EVENT_PUBLICATION` 테이블)를 쓴다. 별도 outbox 테이블과 processed-event 보조 테이블은 만들지 않는다. 멱등성은 자연 키의 unique 제약이 담당한다. `payment`에서는 진행 중인 승인을 하나로 묶는 partial unique가 그것이고(`(order_id, checkout_no)`에 상태 술어를 건다), 무조건적인 복합 키는 한 주문의 시도 수를 제한하므로 쓰지 않는다. 근거는 [ADR-004](../references/01_ResilientPOS_PRD_Backend_Requirements.md)이고, 보조 테이블을 두지 않는 것은 이 PR에서 정했다. "스키마 변경 검토" 절의 질문 1·4와 같은 판단이다. [README](../../README.md#engineering-principles)의 "Event Publication Registry 또는 outbox/inbox"에서 outbox는 외부 전달(Kafka)을 붙일 때의 이야기다.
- `event_publication`은 테이블·컬럼 이름을 Modulith의 JPA 엔티티가 정한다. `t_` 접두어 규칙과 `BaseEntity` 공통 컬럼의 예외다. 엔티티가 라이브러리 안에 있어서 바꿀 수 없다. 스키마는 같은 버전 JDBC 모듈의 공식 PostgreSQL 스키마를 기준으로 Flyway 마이그레이션에 직접 쓴다. JPA가 `validate`라서 Modulith를 올릴 때 엔티티의 컬럼이 바뀌었는지 확인하고 마이그레이션을 함께 넣어야 한다([event-publication-registry](../spec/2026-09-21-event-publication-registry.md)).
- **Modulith 버전을 올리는 PR의 절차.** 라이브러리는 이 테이블을 만들어 주지 않는다. JPA 모듈에는 스키마 SQL이 없고 스키마의 소유자는 Flyway다.
  1. 새 버전 `spring-modulith-events-jdbc` jar의 `schemas/vN/schema-postgresql.sql`을 쓰던 버전의 것과 diff한다. Modulith가 자기 스키마를 버전으로 관리해서 두 버전이 jar에 함께 들어 있다. 실제 v1 → v2 변경은 nullable 컬럼 셋(`status`, `completion_attempts`, `last_resubmission_date`) 추가였다.
  2. 바뀐 만큼 마이그레이션을 추가한다. 추가만 하는 변경이면 이전 버전 인스턴스는 모르는 컬럼이 늘어난 것뿐이라 함께 떠 있어도 된다.
  3. 추가가 아닌 변경(이름·타입)이면 라이브러리 엔티티를 우리가 못 바꿔서 이중 쓰기를 할 수 없다. 남아 있는 미완료 기록을 어떻게 보존할지 함께 정한다.
- **컬럼이 늘거나 타입·테이블 이름이 바뀌면 기동이 막히고 CI가 먼저 잡는다.** `Schema validation: missing column [이름] in table [event_publication]`처럼 대상이 메시지에 나온다. 통합 테스트가 컨텍스트를 띄우므로 버전을 올리는 PR에서 실패한다(2026-09-21에 `V004`를 v1 스키마로 되돌려 확인했다). **반대로 라이브러리가 컬럼을 없애면 조용히 통과한다.** `validate`는 매핑된 컬럼만 검사해서(4.7절 질문 8) 쓰이지 않는 컬럼이 남는다. 이건 사람이 정리한다.
- **이벤트 클래스의 모양이 저장 형식이다.** 발행 기록은 이벤트를 JSON으로, `event_type`에는 클래스의 정규 이름을 저장한다. 미완료 기록이 남아 있는 동안 이벤트의 필드를 지우거나 이름·타입을 바꾸면, 또는 이벤트 클래스를 옮기거나 이름을 바꾸면 복원이 실패해서 그 이벤트를 다시 보낼 수 없다. 하위 호환인 변경은 기본값이 있는 필드 추가뿐이다. 그 밖의 변경은 새 이벤트 타입을 더하고 옛 타입의 미완료 기록이 비워진 뒤에 옛 타입을 지운다.
- **리스너의 이름도 저장 형식이다.** `listener_id`에는 리스너의 클래스 정규 이름·메서드·이벤트 타입이 저장되고(`패키지.클래스.메서드(이벤트타입)`) Modulith는 `serialized_event`와 `listener_id`가 같은 행을 찾아 완료를 표시한다. 미완료 기록이 남은 동안 리스너의 패키지·클래스·메서드 이름을 바꾸면 그 기록을 끝낼 주체가 없어진다.
- **이벤트는 Facade의 트랜잭션 안에서만 발행한다.** 발행 기록이 업무 상태와 같은 커밋에 들어가려면 발행이 커밋 전, 트랜잭션 안이어야 한다. 리스너는 커밋 뒤에 실행된다. 트랜잭션 밖에서 발행하면 발행 기록은 남는데 리스너가 실행되지 않아서 그 기록이 미완료로 남는다(2026-09-21 임시 테스트로 확인).
- 금액은 `NUMERIC(19,4)` + 통화 코드 컬럼, 식별자는 PostgreSQL native `uuid`다([개발 가이드 §3.1·§3.2](../references/03_Development_Guide.md)). 테이블 이름은 `t_` + 단수 명사(`t_order`, `t_payment_attempt`)다.

### 4.4 공개 타입은 모듈 루트에 둔다
- Facade, `*Command`/`*Result`, `*Query`/`*Criteria`/`*View`, 도메인 이벤트, ID 값 타입은 모듈 기본 패키지(`com.resilientpos.order`)에 두고, 그 밖은 전부 `internal/` 아래다.
- **예외는 모듈을 건너가는 이벤트다.** 소유 모듈에 두면 보내는 쪽과 받는 쪽이 서로를 의존해 순환이 된다. 그런 이벤트와 그 payload는 `com.resilientpos.contract`가 소유하고, 양쪽이 그 모듈을 함께 의존한다. 소비자가 없는 이벤트는 소유 모듈에 남는다 — 경계를 넘는 것만 옮긴다([contract-module-for-cross-module-events](../spec/2026-09-22-contract-module-for-cross-module-events.md)). Facade는 인터페이스를 따로 두지 않는 구체 클래스(`class OrderCommandFacade`)다. 구현이 둘이 될 때 인터페이스를 뽑는다. [ADR-002](../references/01_ResilientPOS_PRD_Backend_Requirements.md)를 따르고 [청사진 §6.1](../references/05_Domain_Development_Blueprint.md)의 `api/` 하위 패키지는 쓰지 않는다.
- 근거: Spring Modulith는 모듈 기본 패키지만 공개로 보므로 애너테이션 없이 지금의 `ModuleStructureTest`가 그대로 경계를 지킨다. `api/`를 쓰려면 모듈마다 `@NamedInterface`가 필요하다.
- 다시 볼 조건: 한 모듈의 루트 파일이 20개를 넘거나 공개 surface를 `api`/`events`처럼 나눠 의존을 허용해야 할 때 `@NamedInterface`로 옮긴다. 그때도 패키지 이름만 바뀌는 파일 이동이다.

### 4.5 `Transition`과 `DomainEvent`는 `foundation`에 둔다
- `data class Transition<A, E : DomainEvent>(val aggregate: A, val events: List<E>)`와 `interface DomainEvent { val eventId: UUID; val occurredAt: Instant }`를 `Money` 옆에 둔다. 둘 다 프레임워크 의존이 없어 `DependencyDirectionTest`의 `foundation` 규칙 안에 있다.
- `eventId`는 소비자 멱등성 기록 `(consumer, eventId)`([개발 가이드 §4.2](../references/03_Development_Guide.md))의 키다. 모듈 안에만 머무는 이벤트는 `sealed interface OrderEvent : DomainEvent`처럼 닫아 `when` 누락을 컴파일러가 잡게 한다.
- **§4.4의 `contract` 이벤트는 닫힌 계층에 들어가지 않는다.** 선택이 아니라 언어 제약이다. Kotlin은 sealed 타입의 구현을 같은 패키지로 제한해서(`A class can only extend a sealed class or interface declared in the same package.` — 실제로 컴파일해 확인했다) 다른 패키지로 옮긴 이벤트는 `OrderEvent`·`PaymentEvent`를 구현할 수 없다. `DomainEvent`를 직접 구현하고, 그래서 한 aggregate가 두 종류를 내므로 `Transition`의 이벤트 타입 인자가 `DomainEvent`까지 넓어진다. 잃는 것은 그 이벤트들에 대한 `when` 누락 검사인데, 지금 그 닫힘을 쓰는 `when`이 코드에 하나도 없다.
- 이것은 §2의 "업무 개념을 두지 않는다"에 어긋나지 않는다. 주문·결제를 모르는 모델링 원시 타입이며, 모듈마다 복제하면 이벤트 공통 필드를 강제할 수 없다. 처음 필요한 도메인 PR에서 추가한다.

### 4.6 컨텍스트 사이 동기 호출은 sync intake 한 곳만, 한 트랜잭션으로
원문이 서로 다르다. [청사진 §1](../references/05_Domain_Development_Blueprint.md)은 "Facade끼리 호출하지 않는다"고 조건 없이 금지하고, [시스템 설계 §1.3](../references/02_System_Design.md)은 "단일 즉시 결과와 강한 일관성이 필요한 경우에만 다른 모듈의 public facade를 동기 호출한다"고 조건부로 허용하며, 청사진 자신도 §3.2에서 `sync`가 받아들인 명령을 소유 컨텍스트의 Command Facade로 넘긴다. 시스템 설계 §1.3을 따르되 허용 범위를 다음으로 좁힌다.

- **허용되는 동기 호출은 `sync` → `order`/`payment`의 공개 Command Facade뿐이다.** 그 밖의 컨텍스트 간 변경은 이벤트다. 같은 모듈 안에서 Facade가 Facade를 부르지 않는다는 청사진 §1은 그대로 적용한다.
- **항목 하나 = 로컬 트랜잭션 하나.** `sync` Command Facade가 트랜잭션을 소유하고, 호출된 `order`/`payment` Facade는 같은 트랜잭션에 합류한다(`REQUIRED`). `REQUIRES_NEW`로 커밋을 나누지 않는다. 중복·gap·conflict 판정, 소유 aggregate 쓰기, 접수 기록, watermark 전진, 도메인 이벤트의 Event Publication Registry 기록이 한 커밋이다. 응답은 청사진 §3.2대로 커밋된 항목만 `APPLIED`다.
- **후속 처리는 커밋 뒤 이벤트다.** `OrderCreated`·`SyncOperationApplied`는 같은 커밋에 기록되고 `@ApplicationModuleListener`(AFTER_COMMIT)가 fulfillment·reconciliation을 처리한다. 외부 consumer가 생기면 같은 기록을 `@Externalized`로 Kafka에 relay한다(§4.3). provider 승인 호출은 트랜잭션 밖이며, `PAYMENT_REQUESTED` 항목은 attempt 생성까지만 트랜잭션 안이다.
- 근거: 기획 PRD 3.2·PR-02는 항목별 수락·중복·충돌·누락 판정과 watermark를 응답으로 요구하고, 개발 가이드 §4.3은 "안전한 명령 하나를 트랜잭션으로 적용하고 항목별 결과를 반환"한다. 커밋을 나누면 크래시 창이 생기고, 순수 이벤트로 바꾸면 주문 상태에 기반한 충돌 판정을 응답에 실을 수 없다.
- 다시 볼 조건: `order`나 `payment`를 독립 배포로 분리할 때다. [시스템 설계 §6.2](../references/02_System_Design.md) 2단계대로 이 경로를 먼저 "`ACCEPTED` 응답 + 이벤트 적용"으로 바꿔 원격 경계를 시뮬레이션하고 단말 계약을 함께 바꾼다. 시스템 설계 §1.3의 `checkout` orchestration module이 필요해지면 같은 조건(한 곳, 한 트랜잭션)으로 ADR을 쓴다.

### 4.7 스키마 변경 검토
컬럼·테이블·제약·인덱스를 더하거나 빼는 PR은 spec에 `스키마 변경 검토` 절을 두고 아래 질문에 답한다. 해당 없는 질문은 이유를 적는다. 답은 구현자가 스스로 승인하지 않는다. PR을 제출하기 전에 구현자가 아닌 리뷰어가 마이그레이션 SQL, 엔티티, 그 컬럼을 읽고 쓰는 코드를 열어 spec의 답과 대조한다. 에이전트가 작업했으면 `code-reviewer`처럼 읽기 전용 도구만 가진 다른 에이전트에 맡긴다. 대조한 주체와 시점, 불일치 항목을 spec의 같은 절 끝에 `- 대조 기록:`으로 시작하는 한 줄로 남긴다. `check_pr.py`는 마이그레이션을 바꾸는 PR에 이 절과 이 줄이 있는지를 제출 전 이력 검사에서 확인한다([개발 규칙](development-workflow.md)). 원칙 자체는 [README의 데이터 정본 원칙](../../README.md#engineering-principles), [청사진 §6.2·§7.1](../references/05_Domain_Development_Blueprint.md), [개발 가이드 §2.5](../references/03_Development_Guide.md)에 있고, 이 절은 그것을 스키마를 바꾸는 시점의 질문으로 모은 것이다.

**필요성: 불필요한 컬럼을 넣지 않는다.**
1. PostgreSQL에 이미 저장된 컬럼·테이블에서 그 값을 도출하거나 직접 비교할 수 있는가.
2. 지금 있는 코드가 그 값을 실제로 읽는가, 아니면 아직 없는 명령을 대비하는 것인가.
3. 필요해지는 시점에 기존 행을 채울 수 있는가.
- 나중에 채울 수 있는 값인데 기존 데이터로 지금 판단할 수 있거나 지금 읽는 코드가 없으면 추가하지 않고, 다시 볼 조건만 spec과 코드 주석에 남긴다. 단 질문 4에 해당하는 값이면 이 판정보다 질문 4가 우선한다. 참고 문서가 그 컬럼을 예시로 들었더라도 같은 확인을 거친다.
- 중복 컬럼은 디스크를 쓰는 데서 끝나지 않는다. 같은 사실의 정본이 둘이 되어 어긋났을 때 어느 쪽이 맞는지 알 수 없고, 나중에 읽는 사람이 그 컬럼이 왜 있는지를 이력에서 찾아야 하며, 머지된 뒤에는 제거에도 마이그레이션과 배포된 데이터 처리가 필요하다.

**정합성: 필요한 컬럼을 빼지 않는다.**
4. 이 값이 불변식의 마지막 방어선인가. unique 제약, `@Version`, 멱등 키와 접수 기록, 상태, 판매 시점 snapshot이 여기에 든다. 그렇다면 PostgreSQL의 컬럼과 제약으로 두고 질문 1~3으로 빼지 않는다. Redis의 값, Kafka의 이벤트와 offset은 질문 1의 "기존 데이터"가 아니다. Redis 값은 TTL·eviction·장애로 사라질 수 있다. Kafka 이벤트는 보존 기간 안에서는 남지만 PostgreSQL 트랜잭션과 함께 commit되지 않고 다시 전달될 수 있으며 unique 같은 불변식 검사 수단이 없다. 그래서 둘 다 정본이 될 수 없다. unique 제약마다 soft delete된 행이 계속 걸려야 하는지도 적는다. `BaseEntity`의 `@SoftDelete`는 조회만 거르고 제약은 삭제된 행에도 걸린다. 걸려야 하는 값(멱등 키)이면 그 조회가 삭제된 행도 보는지 확인하고, 재사용을 허용하는 값이면 `WHERE deleted_at IS NULL` partial unique 인덱스로 둔다.
5. 정본은 하나인가. 성능이나 조회를 위해 일부러 만드는 사본(캐시, read model, consumer 쪽 테이블)은 질문 1에서 도출할 수 있다고 나와도 추가할 수 있다. 대신 사본임을 밝힌다. 같은 사실을 두 컬럼이나 두 저장소가 가지면 누가 쓰는지와 같은 트랜잭션에서 함께 바뀌는지를 적는다. 다른 트랜잭션이나 다른 저장소에서 갱신되는 사본은 정본이 무엇인지와 어긋났을 때의 복구 경로를 적는다.
6. 이 값을 읽고 판단한 뒤 쓰는 경로(check-then-act)가 있는가. 있다면 경합을 막는 것이 unique 제약, `@Version`, 조건부 UPDATE, row lock 중 무엇인지 적는다. 답이 `@Version`이면 그 변경이 version을 가진 행을 실제로 UPDATE하는지 확인한다. `@Version`은 필요한 엔티티만 선언하므로(4.2) 자식 행에는 version이 없다. 엔티티 사이에 연관관계 매핑이 없어서 자식 행만 바뀌면 루트의 version이 오르지 않고, 그 변경은 낙관적 잠금으로 보호되지 않는다. Redis 락이나 짧은 TTL 키는 경합을 줄이는 힌트일 뿐이고 DB 검사를 생략하는 근거가 아니다. 한 aggregate를 여러 쿼리로 읽는 경로라면 READ COMMITTED에서 서로 다른 commit을 함께 읽을 수 있는지도 본다.

**반영: 마이그레이션이 락과 배포를 깨지 않는다.**
7. 이 DDL이 잡는 락과 대상 테이블의 크기는 어떤가.
   - nullable 컬럼이나 non-volatile 기본값(상수, `now()`)이 있는 컬럼의 추가는 메타데이터 변경이라 `ACCESS EXCLUSIVE`를 잡는 시간이 짧다. volatile 기본값(`gen_random_uuid()`, `clock_timestamp()`), stored generated 컬럼, identity 컬럼은 테이블과 인덱스를 전부 재작성한다.
   - 짧은 `ACCESS EXCLUSIVE`도 긴 트랜잭션 뒤에서 기다리는 동안 뒤따르는 모든 쿼리를 세운다. 마이그레이션 안에서 `SET LOCAL lock_timeout`을 두고, 시간 초과로 실패하면 다시 실행한다.
   - 일반 `CREATE INDEX`는 `SHARE` 락이라 빌드 동안 쓰기만 막는다. `SET NOT NULL`, 타입 변경, unique·PK 제약 추가는 `ACCESS EXCLUSIVE`라 스캔·재작성·인덱스 빌드 동안 읽기까지 막는다. 타입 변경은 binary coercible(`varchar` 길이 확장, `varchar`에서 `text`)이면 재작성이 없다.
   - 큰 테이블에서는 나눈다. `NOT NULL`은 nullable 추가, 배치 backfill, `NOT VALID` 제약 추가, `VALIDATE CONSTRAINT`(`SHARE UPDATE EXCLUSIVE`라 읽기와 쓰기를 막지 않는다) 순서다. unique는 `CREATE UNIQUE INDEX CONCURRENTLY` 뒤에 `ADD CONSTRAINT ... USING INDEX`로 붙인다. partial·expression 인덱스는 제약에 붙일 수 없다.
   - `CONCURRENTLY`는 트랜잭션 안에서 실행할 수 없다. Flyway는 이 문장을 감지해 그 마이그레이션을 트랜잭션 없이 실행하고 다른 문장과 섞이면 실패시키므로, 그 문장만 담은 별도 마이그레이션으로 둔다. 이 마이그레이션을 처음 넣는 PR에서 `spring.flyway.postgresql.transactional-lock=false`를 함께 설정한다. 기본값이면 Flyway 자신의 락 트랜잭션을 `CONCURRENTLY`가 기다려 끝나지 않을 수 있다(Flyway 문서의 경고이고 이 저장소에서 재현하지는 않았다). 실패하면 롤백되지 않고 INVALID 인덱스가 남는다. 이 인덱스는 쓰기 비용을 계속 내고 unique면 INSERT도 계속 거절하므로 다시 실행하기 전에 `DROP INDEX CONCURRENTLY IF EXISTS`로 지운다.
   - 제약을 더하기 전에 위반 행을 세는 쿼리와 그 결과, 위반 행의 처리 방침을 적는다. 위반 행이 있으면 마이그레이션이 실패해 새 버전이 기동하지 못한다.
8. 이전 버전 애플리케이션이 새 스키마에서 뜨고 쓰는가. 순서는 expand, 이중 읽기·쓰기, backfill, 전환, 정리다. JPA가 `validate`라서 엔티티에 없는 컬럼이 추가되는 것은 이전 버전의 기동을 막지 않는다. 기본값 없는 `NOT NULL` 컬럼은 이전 버전의 INSERT를 실패시킨다. 컬럼 삭제와 이름 변경은 이전 버전의 기동을 막고, Hibernate가 SELECT와 INSERT에 컬럼을 모두 명시하므로 이미 떠 있는 이전 버전 인스턴스의 조회와 쓰기도 즉시 실패시킨다. 삭제는 그 컬럼을 쓰는 코드가 배포에서 빠진 다음 PR에서 한다.
9. 이 값이 도메인 이벤트의 payload나 캐시되는 DTO에 실리는가. 실린다면 이벤트 schema의 하위 호환과 캐시 무효화를 함께 적는다.

사례:
- `t_order.payload_hash`는 질문 1·3에서 걸려 추가하지 않았다. 대상 필드가 이미 `t_order`·`t_order_line`에 있고 line 변경 명령이 생기기 전까지는 현재 상태가 최초 요청과 같아서 직접 비교로 대체했다([order-idempotency-conflict](../spec/2026-09-20-order-idempotency-conflict.md)).
- `sync`의 operation 접수 기록이 가질 payload hash는 같은 질문을 통과한다. 접수 기록은 operation의 payload를 저장하지 않으므로 도출할 수 없고(질문 1), `(terminalId, operationId)` 재전송 판정의 근거다(질문 4).
- `t_order_checkout`은 통화 컬럼을 두지 않았다(질문 1, `t_order.currency`에서 읽는다). `amount`는 지금은 line 합계와 같아도 확정 시점에만 알 수 있는 값이라 둔다(질문 3·4). 읽고 판단한 뒤 쓰는 `RequestCheckout`의 경합은 주문 행 잠금(`PESSIMISTIC_WRITE`, PostgreSQL에서는 `for no key update`, 대기 3초 제한)이 막고, unique `(order_id, checkout_no)`와 `@Version`이 그 뒤의 방어선이다(질문 6). Hibernate가 INSERT를 UPDATE보다 먼저 flush해서 잠금이 없으면 `@Version`보다 unique 제약이 먼저 걸린다([order-request-checkout-persistence](../spec/2026-09-21-order-request-checkout-persistence.md)).
- `t_order_line.version`과 `t_order_checkout.version`은 질문 2·4에서 걸려 없앴다. 업무 코드가 읽지 않았고, 행 단위 version은 서로 다른 line을 고치는 두 트랜잭션을 막지 못해 aggregate의 방어선이 아니었다. 대신 같은 line 행을 동시에 고치는 경우의 충돌 감지는 포기했다. 지금 기존 주문의 line을 고치는 명령이 없다는 것이 그 전제이고, 명령을 넣는 PR이 루트 잠금이나 루트 version 증가를 함께 정한다. 방어선은 루트(`t_order`의 행 잠금과 `@Version`)와 unique `(order_id, checkout_no)`다. 배포된 환경이 없던 시점이라 질문 8의 순서를 밟지 않고 코드 제거와 컬럼 삭제를 한 PR로 했다([base-entity-explicit-version](../spec/2026-09-21-base-entity-explicit-version.md)).
- `t_order`의 unique `(terminal_id, client_order_id)`는 질문 4·6의 답이다. Redis로 중복 요청을 먼저 걸러내더라도 이 제약은 남는다. 멱등 키는 삭제 뒤에도 재사용하면 안 되므로 partial unique로 바꾸지 않는다. 주문을 soft delete하는 명령이 생기면 `findByClientOrderId`가 삭제된 주문도 찾게 해야 한다. 지금은 주문을 삭제하는 코드가 없다.

기술 근거(2026-09-20 확인, PostgreSQL 18·Flyway 12.4.0·Hibernate 7.4.5): [ALTER TABLE](https://www.postgresql.org/docs/18/sql-altertable.html), [CREATE INDEX](https://www.postgresql.org/docs/18/sql-createindex.html), [Explicit Locking](https://www.postgresql.org/docs/18/explicit-locking.html), [Transaction Isolation](https://www.postgresql.org/docs/18/transaction-iso.html), [Flyway 트랜잭션 처리](https://documentation.red-gate.com/fd/migration-transaction-handling-273973399.html), [Flyway PostgreSQL 락](https://documentation.red-gate.com/fd/postgresql-database-277579325.html). Hibernate `validate`가 매핑된 컬럼만 검사한다는 점은 사용자 가이드에 없어서 `AbstractSchemaValidator` 소스로 확인했다.

## 5. 레이어 경계를 넘는 타입

접미사만 보고 어느 레이어의 객체인지 읽혀야 한다. 표의 타입 이름은 §1과 같이 목표 모델이다.

| 경계 | 넘어가는 타입 | 넘어가지 않는 것 |
|---|---|---|
| HTTP ↔ Controller | `*Request` / `*Response` | Command, aggregate |
| Controller·이벤트 리스너·스케줄러 ↔ Facade | `*Command`, `*Query`/`*Criteria` → `*Result`, `*View` | Request DTO, JPA 엔티티 |
| Facade ↔ Service·aggregate | `*Command`, aggregate, Value Object, `*Policy`, `Transition`(`foundation`, §4.5) | `*Request`/`*Response`, `*View` |
| Facade ↔ Repository | aggregate, ID 값 타입 | Spring Data 타입, JPA 엔티티 |
| Repository 구현 ↔ JPA | `*Entity` — adapter 안에서만 | — |
| 모듈 ↔ 모듈 | 공개 Facade의 `*Command`/`*Result`와 `*Query`/`*Criteria`/`*View`, 도메인 이벤트, ID 값 타입 | `internal` 아래 전부, 테이블 |

## 6. 도식 규약

장기 스펙의 [DESIGN·flow 계층](development-workflow.md#장기-스펙-계층과-pr-spec)에 그림을 넣을 때 지킨다.

**컨텍스트 맵 (DESIGN)**
- 컨텍스트마다 상자 하나. 안에 aggregate 루트를 적는다. 자식 엔티티는 concern의 ERD가 맡는다.
- 컨텍스트 맵 안에서는 컨텍스트 사이에 화살표를 긋지 않는다. 이벤트 관계는 [청사진 §2.1](../references/05_Domain_Development_Blueprint.md)처럼 별도 목록이나 그림으로 적는다. 컨텍스트 맵에 동기 호출 화살표가 필요해지면 경계가 의심스럽다는 신호다(§4.6).

**유스케이스 시퀀스 (flow)**
- 참여자는 호출자, Facade, 관여 컨텍스트, DB, 이벤트 전달(EPR), 소비자 순으로 놓는다. 컨텍스트 lifeline에 닿는 메시지 하나가 그 컨텍스트가 제공해야 하는 공개 기능 하나다.
- 전제 조건 확인(조회)은 트랜잭션 상자 **앞에** 두고, 실패 분기는 그 자리에서 끝낸다.
- 트랜잭션 상자는 쓰기만 감싼다. 상자 안이 한 커밋으로 맞아야 하는 전부다. provider 호출·외부 전송은 상자 밖이다.
- 커밋 뒤에 발행되는 이벤트와 그 소비자(다른 컨텍스트의 후속 쓰기)를 같은 그림 아래쪽에 그린다. 호출자 응답은 커밋 직후이고 소비자보다 먼저다.
- 실패 시퀀스(중복·응답 유실·`UNKNOWN`·gap)는 flow 템플릿대로 같은 파일에 둔다.

## 7. 도메인 설계 체크리스트

grill 심문과 업무 기능 spec의 "소유 모듈 / Command·Query·Event / 불변식" 항목을 채울 때 순서대로 묻는다.

1. **소유** — 이 데이터를 소유하는 bounded context는 하나인가? 둘 이상이면 경계가 잘못됐거나 한쪽이 이벤트로 받아야 한다.
2. **루트와 트랜잭션** — aggregate 루트는 무엇이고, 한 트랜잭션에서 함께 맞아야 하는 것은 무엇인가? 그 밖은 ID로만 참조한다.
3. **유일한 진입점** — 이 기능을 다른 컨텍스트에서 수행할 수 있는가? 있다면 안 된다. 소유 컨텍스트의 Facade나 이벤트로만 한다.
4. **규칙의 위치** — 계산·검증이 데이터 가까이(Aggregate·Value Object·Policy) 있는가? Service에 `amount + discount`, `status = ...`, validation if-else가 늘면 옮긴다. 루트가 바뀔 때 남겨야 하는 이력은 자식 엔티티로 같은 트랜잭션에 있는가(§2)?
5. **단어** — 새 단어가 생겼는가? §3에 있는 단어인가, 같은 단어를 다른 뜻으로 쓰고 있지 않은가?
6. **사실** — 발행하는 이벤트는 과거형 한 가지 사실인가? "완료"처럼 여러 사실이 섞이면 나눈다.
7. **실패** — 중복 요청, 응답 유실, `UNKNOWN`, gap, 재처리가 상태 모델에 있는가? [README 완료 조건](../../README.md#engineering-principles)의 실패 시나리오와 맞춘다.

## 8. 참고 사례와 다르게 가는 결정

[카카오페이 사례](https://tech.kakaopay.com/post/backend-domain-driven-design/)에서 가져온 것: 용어 정의(§1), "도메인 기능은 그 도메인을 통해서만"(§7의 3번), 상태 변경과 이력을 한 aggregate에 두는 기준(§2), 경계마다 타입 접미사를 고정하는 레이어 도식(§5), 구현 전에 팀이 함께 보는 시퀀스(§6). 사례가 그렇게 한 이유는 "정책이 명확하고 복잡한 도메인을 견고하게 만들고, 여신 전문가·기획·개발이 같은 말을 쓰기 위해서"였다. 오프라인 정합성과 결제 모호성이 핵심인 이 프로젝트도 같은 조건이다. 구조는 다음에서 다르다.

| 사례 | 이 저장소 | 이유 |
|---|---|---|
| bounded context = Gradle 모듈 | Spring Modulith 패키지 모듈. Gradle 분할은 [현재 상태](current-state.md)의 조건이 충족될 때만 | 모듈 7개가 비어 있는 지금 나누면 담길 코드보다 빌드 코드가 많다. 경계는 Modulith 검증이 이미 막는다 |
| Kotlin `internal` 가시성으로 JPA 엔티티를 숨김 | 목적은 같고 수단이 둘로 갈린다. 모듈 경계는 `internal` **패키지** + Modulith 검증, 엔티티를 밖에서 만들거나 바꾸지 못하게 하는 것은 `private` 생성자와 `var … protected set`이다([entity-property-visibility](../spec/2026-09-22-entity-property-visibility.md)) | 단일 Gradle 프로젝트에서 Kotlin `internal`은 전체에 보여 경계가 되지 않는다. 사례 스스로 배치용 JPA 엔티티를 중복 생성한 부작용을 기록했다. 생성·변경을 `from`·`apply`로 묶는 일은 키워드 없이 생성자와 setter 가시성만으로 된다. `private set`이 아닌 이유는 `allOpen`이 `@Entity`에 걸려 있어 멤버가 open이고 Kotlin이 open 프로퍼티의 `private set`을 금지하기 때문이다 |
| Command를 aggregate 안의 중첩 클래스로, 상태는 `var … internal set` | Command는 Facade의 공개 입력, aggregate는 불변(§4.1) | CQRS 규약상 Command는 모듈 공개 계약이다. 불변 전이는 기존 Kotlin 규칙과 일치한다 |
| API·배치 공통 로직을 Biz-component 패키지로 | Controller·이벤트 리스너·스케줄러가 같은 Command Facade를 호출 | Facade가 이미 use case 단위의 공통 진입점이다. 별도 레이어를 두지 않는다 |
| 도메인 모듈에는 use case가 없고, 별도 `core_app`이 여러 도메인을 한 트랜잭션으로 조합(가입 = 회원 + 계좌 + 한도) | use case(Facade)는 컨텍스트 안에 있고, 컨텍스트를 넘는 변경은 이벤트가 기본이다. 동기 호출의 허용 조건과 트랜잭션 전파는 §4.6 | 컨텍스트별로 독립 배포로 뽑아내는 경로([시스템 설계 §6](../references/02_System_Design.md))를 열어 두려면 컨텍스트를 넘는 트랜잭션을 기본값으로 삼을 수 없다. 사례는 단일 배포 코어라 한 트랜잭션으로 묶을 수 있었다 |
| 자식 엔티티 이름에 `snapshot`(계좌 Snapshot, 연체 snapshot) | checkout 확정 금액에는 쓰지 않는다(§3) | 사례의 snapshot은 시점 복사본이라는 구현 사실이고, 우리의 checkout은 "N회차 확정 금액"이라는 업무 사실이다. 시점 복사본이 정말 필요해지면 그때 `snapshot`을 그 뜻으로만 쓴다 |
| [시스템 설계 §5](../references/02_System_Design.md)의 order 상태 `OFFLINE_CREATED → SYNCED → PAYMENT_PENDING` | `CreateOrder`의 첫 상태는 `OPEN`이다. 오프라인 생성·동기화 여부는 `sync`가 소유하는 사실이라 order 상태에 넣지 않는다. 상태 열거값은 각 명령 PR이 추가한다([order-domain-model](../spec/2026-09-16-order-domain-model.md)) | order 상태는 line·금액을 바꿀 수 있는지(§2 불변식)만 뜻해야 한다. 생성 경로를 상태에 섞으면 같은 주문이 경로에 따라 다른 lifecycle을 가진다 |

## 관련 문서
- [README Engineering Principles](../../README.md#engineering-principles) — 역할 분리·CQRS·데이터 정본 원칙
- [테스트 전략](testing-strategy.md) — Aggregate/Value Object/Policy는 Spring 없는 단위 테스트
- [현재 상태](current-state.md) — 모듈 선언 상태, Gradle 분할 조건, 의존성 가드
- [개발 규칙](development-workflow.md) — 업무 기능 spec이 담아야 하는 항목
