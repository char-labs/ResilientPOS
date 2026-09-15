# 도메인 모델과 유비쿼터스 언어

이 프로젝트가 쓰는 DDD 용어의 정의, bounded context 7개의 요약, 코드·스키마·문서가 함께 쓰는 단어 목록이다.
업무 기능 spec을 쓰거나 새 도메인 타입·테이블·이벤트 이름을 만들 때 먼저 읽는다. Facade/Service/Aggregate의
책임 분리 표는 [README의 Engineering Principles](../../README.md#engineering-principles)가 정본이며 여기서 반복하지 않는다.
Kotlin으로 Aggregate·Value Object·Event·Repository를 어떤 형태로 쓰는지는 볼트 규칙 `rules/domain-modeling-kotlin.md`가
담당한다(`.claude/rules` 링크로 `src/**/*.kt` 편집 시 자동 로드).

원문 근거는 [도메인 청사진](../references/05_Domain_Development_Blueprint.md), [개발 가이드](../references/03_Development_Guide.md),
[시스템 설계](../references/02_System_Design.md)다. 원문은 참고 자료라 이 페이지가 정본이고, 원문과 다르게 정한 것은 §4에 적는다.
용어 정의의 골격은 [카카오페이 여신코어 DDD 사례](https://tech.kakaopay.com/post/backend-domain-driven-design/)를 참고했고,
그 사례와 다르게 가는 점은 §6에 있다.

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
| 공개 API (module public surface) | 다른 모듈이 import할 수 있는 것. Facade·ID 값 타입·이벤트만 | `internal` 패키지 아래는 비공개. Modulith 검증이 위반을 빌드 실패로 만든다 | `payment/PaymentManagement.kt`, `PaymentId.kt`, `PaymentApproved.kt`. 위치는 §4.4 미확정 항목 참고 |

## 2. Bounded context 요약

전체 aggregate 맵과 불변식은 [청사진 §2](../references/05_Domain_Development_Blueprint.md)가 원문이다. 여기서는 용어를 맞추기 위한 요약만 둔다.

| Context | Aggregate 루트 | 소유하는 것 | 핵심 불변식 | 공개 Facade |
|---|---|---|---|---|
| `store` | `Store` | 판매 정책, 통화·반올림, 매장 설정 | 정책은 버전으로 관리하고 과거 주문 금액을 사후에 바꾸지 않는다 | `StoreManagement` |
| `terminal` | `Terminal` | 단말 코드, 활성화, credential rotation 이력 | 매장 안에서 코드 유일. 비활성 단말은 인증 불가. 이력 삭제 금지 | `TerminalManagement` |
| `order` | `Order` | line, 할인, checkout 금액·통화, lifecycle | `OPEN`에서만 금액·line 변경. checkout 이후 금액·통화 불변. 결제 승인 금액은 checkout과 일치해야 한다 | `OrderManagement` |
| `payment` | `PaymentAttempt` | provider 참조, provider idempotency, 승인·취소 시도, 결제 정본 상태 | `(orderId, attemptNo)` 유일. `UNKNOWN`은 실패가 아니다. provider 결과는 한 번만 적용한다 | `PaymentManagement` |
| `sync` | `TerminalSyncState` | 단말 watermark, operation 접수·중복 제거, gap·conflict | `lastAckedSequence`는 단조 증가. gap을 건너뛰어 전진하지 않는다 | `SyncManagement` |
| `reconciliation` | `ReconciliationCase` | 불일치 case의 근거, 규칙 버전, 승인·조치 감사 기록 | 근거·규칙·감사 없이 해결하지 않는다. 결제 상태를 직접 고치지 않는다 | `ReconciliationManagement` |
| `fulfillment` | `KitchenTicket`, `ReceiptDelivery` | 주문 이후 주방 ticket과 영수증 전달 | 원본 주문당 natural key로 한 번만 생성. 재시도 side effect는 멱등 | 소비 이벤트만 |

컨텍스트 사이의 소유 규칙:
- `payment`만 결제 정본 상태를 바꾼다. `reconciliation`은 case와 근거를 소유하고 결론을 `PaymentManagement.resolveFromReconciliation(command)` 같은 좁은 공개 command로 요청한다.
- `order`와 `payment`는 서로의 테이블·엔티티·repository를 읽거나 쓰지 않는다. 사실은 이벤트로 건너간다(`OrderPaymentRequested` → payment, `PaymentApproved` → order·reconciliation, `OrderPaid` → fulfillment).
- `sync`는 접수·중복·순서만 판정하고, 받아들인 명령은 소유 컨텍스트의 Command Facade로 넘긴다. `SyncOperationApplied`는 기술 감사 이벤트이며 `OrderCreated`를 대체하지 않는다.
- `foundation`은 프레임워크 없는 값 타입(`Money`, 식별자 생성기)만 공유한다. 공유 도메인 모델을 두는 곳이 아니다.

## 3. 유비쿼터스 언어 용어집

코드 식별자, 테이블·컬럼 이름, 이벤트 이름, spec 본문은 이 표의 단어를 쓴다. 화면 라벨은 현장 용어("포스기")를 써도 되지만 도메인·스키마 이름은 바꾸지 않는다.
새 단어가 필요하면 PR spec에서 제안하고 머지 시 이 표에 추가한다. 이미 있는 단어에 다른 뜻을 붙이지 않는다.

| 용어 (코드·스키마) | 뜻 | 기각한 대안 | 이유 |
|---|---|---|---|
| `terminal` | 매장에 설치된 판매 단말 | `pos` | 제품 전체가 POS라 자기 참조다. 코드에서 `pos`는 position으로 읽힌다. 원문 요구사항과 오프라인 명령 봉투(`terminalId`)가 이미 `terminal`을 쓴다 |
| `operation` (sync) | 단말이 오프라인에서 만든 명령 한 건. 식별자는 `operationId`(UUID v7) | `receipt` | 한 스키마에서 receipt가 접수증과 고객 영수증 두 뜻으로 쓰였다. 봉투의 `operationId`와 어휘를 맞췄다. 테이블은 `t_sync_operation` |
| `watermark` | 단말별로 서버가 확정한 마지막 sequence(`lastAckedSequence`) | `terminal sync state` | state는 모든 테이블에 해당해 아무 말도 하지 않는다. 기획 문서가 이미 쓰는 단어다. 테이블은 `t_sync_watermark` |
| `sequence` (terminal sequence) | 한 단말 안에서 단조 증가하는 명령 순번. 누락(gap) 탐지의 근거 | — | UUID 생성 순서, Kafka 순서, aggregate version과 다르다. 서로 대체하지 않는다 |
| `checkout` | 결제 시도 회차에 대해 확정한 금액·통화·정책 버전 | `checkout snapshot` | snapshot은 구현 용어다. 이 값은 "N회차 결제 시도의 확정 금액"이라는 업무 사실이다. 테이블은 `t_order_checkout` |
| `attempt` (payment) | 한 주문에 대한 결제 시도 1회. `(orderId, attemptNo)` | `payment`, `transaction` | 승인·취소·`UNKNOWN`을 한 회차 안에 묶어야 한다. transaction은 DB 용어와 충돌한다 |
| `UNKNOWN` | provider 결과를 아직 확인하지 못한 결제 상태. 실패가 아니다 | `FAILED`, `TIMEOUT` | timeout은 전송 관찰의 실패이고 결제 결과의 실패가 아니다. `UNKNOWN`에서 새 승인 요청을 재시도하지 않고 provider 조회 → reconciliation으로 수렴한다 |
| `gap` / `duplicate` / `conflict` | sync 판정 결과. 순번 누락 / 이미 접수한 operation의 재전송 / 같은 키에 다른 내용 | 일반 `error`, HTTP 500 | 세 가지는 각각 다른 복구 경로를 가진 업무 결과다. 항목 단위 결과로 돌려주고 부분 성공을 200 하나로 숨기지 않는다 |
| `case` (reconciliation) | 불일치 한 건의 근거·규칙 버전·조치·감사 기록 | `issue`, `ticket` | ticket은 fulfillment의 `KitchenTicket`과 충돌한다 |
| `evidence` | POS·서버·provider 각각이 말하는 사실의 기록 | `proof`, `log` | 세 출처를 나란히 비교하는 것이 reconciliation의 본질이다 |
| `kitchen ticket` | 결제된 주문에서 만든 주방 표시 작업 단위. 주문당 natural key로 1건 | `order ticket` | 주문 자체와 구분한다. 재시도해도 한 번만 만들어진다 |
| `receipt` (fulfillment) | 고객 영수증 전달(`ReceiptDelivery`) | — | sync 쪽에서는 이 단어를 쓰지 않는다(위 `operation` 참고) |
| `store policy` | 통화·반올림·판매 규칙. 버전이 있다 | `settings`, `config` | 정책은 업무 규칙이고 버전으로 과거 주문에 적용된 값을 추적한다 |
| `aggregate version` | 한 aggregate의 상태 순서(`@Version`) | — | 동시 수정 충돌 감지용이다. terminal sequence와 다르다 |

이벤트 이름은 과거형 한 가지 사실이다.

| 쓰지 않는 이름 | 대신 쓰는 이름 | 이유 |
|---|---|---|
| `OrderCompleted` | `OrderAccepted`, `OrderPaid`, `FulfillmentCompleted`, `OrderClosed` | "완료"가 결제 완료인지 주방 완료인지 마감인지 읽히지 않는다 |
| `PaymentFailed`(timeout 시) | `PaymentUnknown` | timeout은 결과가 아니라 미확인이다 |
| `SyncCompleted` | `SyncOperationApplied` + 소유 컨텍스트의 사실 이벤트 | 동기화 접수와 업무 사실을 분리한다 |
| `PaymentConfirmed`(provider 승인 시) | `PaymentApproved` | 원문 [개발 가이드 §1.1](../references/03_Development_Guide.md)과 [청사진 §3](../references/05_Domain_Development_Blueprint.md)이 두 이름을 섞어 쓴다. provider 승인 사실은 `PaymentApproved`다. reconciliation 해결 결과의 이벤트 이름은 payment PR의 spec에서 정한다 |

## 4. 확정한 설계 결정

### 4.1 Aggregate 상태 전이는 불변 객체가 새 인스턴스를 반환한다
- 전이 메서드는 자신을 바꾸지 않고 바뀐 aggregate와 발생한 이벤트를 돌려준다. 예: `order.requestCheckout(policy)`는 `CHECKOUT_REQUESTED` 상태의 새 `Order`와 `OrderPaymentRequested`를 반환한다.
- 근거: 볼트 규칙 `effective-java-for-kotlin.md` Item 17과 `kotlin-idioms-and-design-patterns.md` §1(`order.cancelled(reason)`)이 이미 이 방향이다. 도메인 테스트가 Spring 없이 값 비교로 끝나고, 전이 실패 시 원본이 그대로 남아 실패 원자성(Item 76)이 공짜로 따라온다.
- 원문 [청사진 §1.1](../references/05_Domain_Development_Blueprint.md)의 `status = OrderStatus.CHECKOUT_REQUESTED` 예시는 이 결정으로 대체한다. `@Version` 낙관적 잠금은 JPA 엔티티에 남고, Repository 구현이 새 aggregate를 관리 엔티티에 적용할 때 버전 충돌을 감지한다.

### 4.2 Aggregate와 JPA 엔티티는 별개 클래스다
- aggregate·값 객체·Policy·Service는 `internal/domain`에 두고 JPA·Spring 애너테이션을 갖지 않는다. JPA 엔티티, Spring Data repository, 변환기(`from(aggregate)`, `apply(aggregate)`, `toDomain()`)는 `internal/adapter/persistence/command`에 둔다. Repository 인터페이스는 도메인 쪽, 구현은 adapter 쪽이다.
- 근거: [청사진 §6.1](../references/05_Domain_Development_Blueprint.md) 패키지 구조가 이미 이 분리를 전제하고, [의존성 방향 가드 spec](../spec/2026-09-15-dependency-direction-guard.md)이 "도메인 레이어는 Spring·repository에 의존하지 않는다"는 ArchUnit 규칙을 첫 도메인 PR에 추가하기로 했다. 4.1의 불변 aggregate는 JPA 관리 엔티티와 같은 클래스일 수 없다.
- 비용: aggregate마다 클래스가 둘이고 변환기가 붙는다. 그 대신 도메인 테스트에 DB가 없고, JPA 엔티티 규칙(`data class` 금지, 프록시, 지연 로딩)이 도메인에 새지 않는다.

### 4.3 이벤트 전달과 저장 형식
- 모듈 안 이벤트 전달은 Spring Modulith Event Publication Registry(`EVENT_PUBLICATION` 테이블)를 쓴다. 별도 outbox 테이블과 processed-event 보조 테이블은 만들지 않는다. 멱등성은 `(orderId, attemptNo)` 같은 natural unique key가 담당한다. 근거는 [ADR-004](../references/01_ResilientPOS_PRD_Backend_Requirements.md)와 2026-09-15 DB 설계 리뷰 결정이다. [README](../../README.md#engineering-principles)의 "Event Publication Registry 또는 outbox/inbox"에서 outbox는 외부 전달(Kafka)을 붙일 때의 이야기다.
- 금액은 `NUMERIC(19,4)` + 통화 코드 컬럼, 식별자는 PostgreSQL native `uuid`다([개발 가이드 §3.1·§3.2](../references/03_Development_Guide.md)). 테이블 이름은 `t_` + 단수 명사(`t_order`, `t_payment_attempt`)다.

### 4.4 미확정 — 공개 타입의 위치
원문 둘이 다르다. [ADR-002](../references/01_ResilientPOS_PRD_Backend_Requirements.md)는 모듈 루트(`order/OrderManagement.kt`)에, [청사진 §6.1](../references/05_Domain_Development_Blueprint.md)은 `order/api/` 하위에 둔다.
Spring Modulith 기본은 모듈 루트만 공개이므로 `api/`를 쓰려면 `@NamedInterface`가 필요하다. [모듈 골격 spec](../spec/2026-09-15-module-skeleton.md)은 `@NamedInterface`를 공개 surface가 실제로 커진 뒤로 미뤘다.
첫 도메인 PR의 spec에서 하나로 정하고 이 절을 갱신한다.

### 4.5 미확정 — 공유 도메인 타입의 위치
전이 결과를 담는 `Transition<A>`(바뀐 aggregate + 발생한 이벤트)와 이벤트 표시 타입 `DomainEvent`는 모든 컨텍스트가 쓴다. `foundation`에 두면 §2의 "공유 도메인 모델을 두지 않는다"와 부딪히고, 모듈마다 두면 같은 타입이 일곱 번 생긴다. 첫 도메인 PR에서 정하고 이 절을 갱신한다. 볼트 규칙은 형태만 정하고 위치는 이 절을 따른다.

## 5. 도메인 설계 체크리스트

grill 심문과 업무 기능 spec의 "소유 모듈 / Command·Query·Event / 불변식" 항목을 채울 때 순서대로 묻는다.

1. **소유** — 이 데이터를 소유하는 bounded context는 하나인가? 둘 이상이면 경계가 잘못됐거나 한쪽이 이벤트로 받아야 한다.
2. **루트와 트랜잭션** — aggregate 루트는 무엇이고, 한 트랜잭션에서 함께 맞아야 하는 것은 무엇인가? 그 밖은 ID로만 참조한다.
3. **유일한 진입점** — 이 기능을 다른 컨텍스트에서 수행할 수 있는가? 있다면 안 된다. 소유 컨텍스트의 Facade나 이벤트로만 한다.
4. **규칙의 위치** — 계산·검증이 데이터 가까이(Aggregate·Value Object·Policy) 있는가? Service에 `amount + discount`, `status = ...`, validation if-else가 늘면 옮긴다.
5. **단어** — 새 단어가 생겼는가? §3에 있는 단어인가, 같은 단어를 다른 뜻으로 쓰고 있지 않은가?
6. **사실** — 발행하는 이벤트는 과거형 한 가지 사실인가? "완료"처럼 여러 사실이 섞이면 나눈다.
7. **실패** — 중복 요청, 응답 유실, `UNKNOWN`, gap, 재처리가 상태 모델에 있는가? [README 완료 조건](../../README.md#engineering-principles)의 실패 시나리오와 맞춘다.

## 6. 참고 사례와 다르게 가는 결정

[카카오페이 사례](https://tech.kakaopay.com/post/backend-domain-driven-design/)의 용어 정의와 "도메인 기능은 그 도메인을 통해서만"이라는 원칙은 그대로 따른다. 구조는 다음 세 곳에서 다르다.

| 사례 | 이 저장소 | 이유 |
|---|---|---|
| bounded context = Gradle 모듈 | Spring Modulith 패키지 모듈. Gradle 분할은 [현재 상태](current-state.md)의 조건이 충족될 때만 | 모듈 7개가 비어 있는 지금 나누면 담길 코드보다 빌드 코드가 많다. 경계는 Modulith 검증이 이미 막는다 |
| Kotlin `internal` 가시성으로 JPA 엔티티를 숨김 | `internal` **패키지** + Modulith 검증 | 단일 Gradle 프로젝트에서 Kotlin `internal`은 전체에 보여 경계가 되지 않는다. 사례 스스로 배치용 JPA 엔티티를 중복 생성한 부작용을 기록했다 |
| Command를 aggregate 안의 중첩 클래스로, 상태는 `var … internal set` | Command는 Facade의 공개 입력, aggregate는 불변(§4.1) | CQRS 규약상 Command는 모듈 공개 계약이다. 불변 전이는 기존 Kotlin 규칙과 일치한다 |
| API·배치 공통 로직을 Biz-component 패키지로 | Controller·이벤트 리스너·스케줄러가 같은 Command Facade를 호출 | Facade가 이미 use case 단위의 공통 진입점이다. 별도 레이어를 두지 않는다 |

## 관련 문서
- [README Engineering Principles](../../README.md#engineering-principles) — 역할 분리·CQRS·데이터 정본 원칙
- [테스트 전략](testing-strategy.md) — Aggregate/Value Object/Policy는 Spring 없는 단위 테스트
- [현재 상태](current-state.md) — 모듈 선언 상태, Gradle 분할 조건, 의존성 가드
- [개발 규칙](development-workflow.md) — 업무 기능 spec이 담아야 하는 항목
