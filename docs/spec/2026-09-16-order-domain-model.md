# PR spec: order 도메인 모델과 CreateOrder 계약

## 목적
첫 업무 도메인 코드를 `order` 모듈에 만든다. `Order` aggregate가 주문 생성 불변식을 소유하고, `CreateOrder` 명령의 공개 계약(Command·Result·Facade 인터페이스·`OrderCreated` 이벤트)을 고정한다. 영속화와 Facade 구현은 이 브랜치 위의 다음 PR(`order-create-order-persistence`)이 맡는다. 두 PR을 나눈 이유는 한 PR로 만들면 약 840줄·22파일로 저장소 한도(600줄·20파일 경고)를 넘기 때문이다. 도메인 규칙은 Spring·DB 없이 값 비교로 검증할 수 있어 독립 PR로 자연스럽게 떨어진다.

## 범위
- `foundation`에 모델링 원시 타입 두 개를 둔다. `DomainEvent`(eventId, occurredAt)와 `Transition`(바뀐 aggregate와 발생한 이벤트 목록). [도메인 모델 §4.5](../wiki/domain-model.md)의 결정을 코드로 옮긴다.
- 소유 모듈의 ID 값 타입: `store` 루트에 `StoreId`, `terminal` 루트에 `TerminalId`. 두 모듈에 다른 타입은 만들지 않는다. `order`는 이 공개 타입에만 의존한다.
- `order` 루트(공개): `OrderCommandFacade` 인터페이스, `CreateOrderCommand`(storeId, terminalId, clientOrderId, currency, lines)와 line 입력, `CreateOrderResult`(orderId, status), `OrderId`, `ClientOrderId`, `OrderStatus`, `sealed interface OrderEvent : DomainEvent`와 `OrderCreated`.
- `order/internal/domain`(비공개): `Order` aggregate 루트, `OrderLine` 자식 값. `Order.create(...)`가 `Transition`을 돌려주고 생성 불변식을 검증한다. 합계는 line 단가와 수량으로 계산하는 파생 값이며 필드로 저장하지 않는다.
- `DependencyDirectionTest`에 도메인 레이어 규칙을 추가한다. `order.internal.domain`은 Spring, JPA, Servlet, Jackson, `internal.adapter`, `internal.application`에 의존하지 않는다. [dependency-direction-guard](2026-09-15-dependency-direction-guard.md)가 첫 도메인 PR로 미룬 항목이다.
- 소유 모듈: `order`(생성 불변식), `foundation`(원시 타입), `store`·`terminal`(ID 타입만). 계약: `CreateOrderCommand → CreateOrderResult`, 이벤트 `OrderCreated`. 불변식: 아래 인수 조건 1~5. 복구·관측성: 이 PR은 저장·외부 호출·트랜잭션이 없어 재시도·복구 경로와 로그 속성이 생기지 않는다. 재시도 판정(같은 terminalId·clientOrderId면 기존 결과 반환)은 Facade 구현이 있는 다음 PR이 맡는다.

## 제외
- Facade 구현, `OrderRepository` port와 JPA adapter, Flyway `V002` 마이그레이션, `@ApplicationModuleTest`, Fixture Monkey 도입. 모두 다음 PR `order-create-order-persistence`.
- REST Controller(`POST /v1/orders`)와 REST Docs. 그 다음 PR.
- Event Publication Registry(`starter-jpa`, `event_publication` 테이블). 첫 `@ApplicationModuleListener`가 생기는 PR에서 넣는다. 이 PR은 이벤트 타입 정의까지다.
- `OPEN` 이외의 `OrderStatus` 값과 상태 전이(`RequestCheckout`, `ChangeOrderLine`, 취소). 각 명령 PR이 추가한다.
- 할인, 세금, 반올림 정책(`CheckoutPolicy`), `store` 정책 버전 참조.
- `storeId`·`terminalId`의 존재 검증. `store`·`terminal` 모듈에 aggregate가 없다. ID는 FK 없는 참조다.
- `OrderService`. 생성 규칙이 전부 `Order` 안에 있어 조율할 대상이 없다. 규칙이 aggregate 밖으로 나올 때 만든다.
- `docs/spec/DESIGN.md` 계층. 한 모듈 안의 일이라 PR spec과 [도메인 모델](../wiki/domain-model.md)로 충분하다.

## 관련 문서
DESIGN 계층은 없다. 원문 근거는 [도메인 청사진 §1.1·§2·§3.1](../references/05_Domain_Development_Blueprint.md), [개발 가이드 §3.3](../references/03_Development_Guide.md), [시스템 설계 §5](../references/02_System_Design.md)다. 정본은 [도메인 모델](../wiki/domain-model.md) §2·§4다. 선행 PR은 [domain-model-glossary](2026-09-15-domain-model-glossary.md), [foundation-value-types](2026-09-15-foundation-value-types.md).

## 변경 경로
- src/main/kotlin/com/resilientpos/foundation/DomainEvent.kt
- src/main/kotlin/com/resilientpos/store/StoreId.kt
- src/main/kotlin/com/resilientpos/terminal/TerminalId.kt
- src/main/kotlin/com/resilientpos/order/**
- src/main/kotlin/com/resilientpos/order/internal/domain/**
- src/test/kotlin/com/resilientpos/order/internal/domain/**
- src/test/kotlin/com/resilientpos/DependencyDirectionTest.kt
- docs/spec/2026-09-16-order-domain-model.md
- docs/wiki/domain-model.md
- docs/wiki/current-state.md
- docs/wiki/changes.md

## 인수 조건
1. line 1개 이상, 수량 1 이상, 단가 0 이상, 모든 단가 통화가 주문 통화와 같은 `CreateOrderCommand`로 `Order.create`를 부르면 `OPEN` 상태의 `Order`와 `OrderCreated` 하나를 담은 `Transition`이 나온다. `OrderCreated`는 orderId, storeId, terminalId, clientOrderId, eventId, occurredAt을 가진다.
2. line이 비어 있으면 `EMPTY_ORDER`로 거절한다.
3. 수량이 1보다 작으면 `INVALID_QUANTITY`, 단가가 음수면 `NEGATIVE_UNIT_PRICE`로 거절한다.
4. 주문 통화와 다른 통화의 단가가 하나라도 있으면 `CURRENCY_MISMATCH`로 거절한다.
5. `Order`의 합계는 각 line의 단가 곱 수량의 합이고, 저장 필드가 아니라 계산 결과다. `Money.zero(currency)`에서 시작해 빈 line 예외 처리 없이 합산한다.
6. `Transition`과 `DomainEvent`는 `foundation`에 있고 `DependencyDirectionTest`의 기존 `foundation` 규칙을 통과한다.
7. `DependencyDirectionTest`에 추가한 도메인 레이어 규칙이 `order.internal.domain`의 프로덕션 코드를 검사 대상으로 잡고(0건이 아님) 통과한다. `Order`에 `@Component`나 `jakarta.persistence` 애너테이션을 임시로 붙이면 실패하는 것을 확인하고 되돌린다.
8. `ModuleStructureTest`가 `order`의 `store`·`terminal`·`foundation` 공개 타입 의존을 허용하고, `internal.domain`은 모듈 밖에 노출되지 않는다.
9. `python3 scripts/check_pr.py --base main --worktree`가 통과한다.

### Checkpoint와 검증 명령
| Checkpoint | 검증 명령 | 의존 |
|---|---|---|
| order-domain | `./gradlew test --tests 'com.resilientpos.order.*'` | 없음 |
| dependency-direction | `./gradlew test --tests 'com.resilientpos.DependencyDirectionTest'` | order-domain |
| module-structure | `./gradlew test --tests 'com.resilientpos.ModuleStructureTest'` | order-domain |

## 검증
```sh
./gradlew test
python3 scripts/check_pr.py --base main --worktree
```
- 정상: 유효한 명령으로 `OPEN` 주문과 `OrderCreated`가 만들어지고 합계가 맞는다.
- 실패: 빈 line, 0 이하 수량, 음수 단가, 통화 불일치가 각각 지정한 메시지로 거절된다. 도메인 클래스에 Spring·JPA 애너테이션을 붙이면 ArchUnit이 실패한다.

## Wiki 영향
- [도메인 모델](../wiki/domain-model.md): `OrderStatus`의 첫 상태는 `OPEN`이고 오프라인·동기화 여부는 order 상태가 아니라 `sync`가 소유하는 사실이라는 결정을 §8에 추가한다. [시스템 설계 §5](../references/02_System_Design.md)의 `OFFLINE_CREATED → SYNCED`는 이 결정으로 대체한다. 상태 열거값은 각 명령 PR이 추가한다.
- 현재 상태(`docs/wiki/current-state.md`, 볼트): `order` 모듈에 첫 타입이 생겼고, `foundation`에 `Transition`·`DomainEvent`가 있으며, `DependencyDirectionTest`에 도메인 레이어 규칙이 있다는 사실을 반영한다. "도메인 레이어 규칙은 아직 없다" 문장을 지운다.
- 변경 기록(`docs/wiki/changes.md`, 2026-09-21 삭제): 이 spec 링크와 결정을 남긴다. 제품 요구사항은 바뀌지 않는다. 청사진 §1.1의 `OrderService.create`는 만들지 않고, 시스템 설계 §5의 order 상태 이름을 wiki 결정으로 대체한다.

## 분할 계획
예상 규모: 구현 약 190줄(foundation 15, ID 20, order 공개 75, domain 80), 테스트 약 130줄, 문서 약 180줄로 총 500줄·15파일 내외. 경고 한도(600줄·20파일) 안이다.
초과하면 `StoreId`·`TerminalId` 값 타입을 다음 PR로 옮기고 이 PR은 `order` 안에서 UUID를 그대로 쓴다.
후속 순서는 이 브랜치 위의 stacked PR `order-create-order-persistence`(Facade 구현, `OrderRepository`와 JPA adapter, `V002` 마이그레이션, 재시도 시 기존 결과 반환, Fixture Monkey) → `order-create-order-api`(REST Controller, REST Docs) → `RequestCheckout`이다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| `유효한 명령으로 OPEN 주문과 OrderCreated를 만든다` | RED 관측 없음. 공개 타입과 `Order.create`의 정상 경로를 컴파일을 맞추는 최소 선언으로 넣는 과정에서 정상 경로가 함께 완성되어 첫 실행에 통과했다. 이 행은 characterization 근거다 | `./gradlew test --tests 'com.resilientpos.order.internal.domain.OrderTest'` — 통과 | 같은 명령 통과 |
| `빈 line을 EMPTY_ORDER로 거절한다` | 같은 명령 — `Expected an exception of class java.lang.IllegalArgumentException to be thrown, but was completed successfully with the result: Transition(...)`. `require` 미구현 | `Order.create`에 `require(lines.isNotEmpty()) { EMPTY_ORDER }` 추가 후 같은 명령 통과 | 같은 명령 통과 |
| `0 이하 수량과 음수 단가를 거절한다` | 같은 명령 — 수량 0과 단가 -1000 두 사례 모두 예외 미발생으로 실패 | `OrderLine.init`에 `INVALID_QUANTITY`·`NEGATIVE_UNIT_PRICE` `require` 추가 후 같은 명령 통과 | 같은 명령 통과 |
| `주문 통화와 다른 단가를 CURRENCY_MISMATCH로 거절한다` | 같은 명령 — 예외 미발생으로 실패 | `Order.create`에 line 통화 일치 `require` 추가 후 같은 명령 통과 | 같은 명령 통과 |
| `합계는 line 단가 곱 수량의 합이다` | 같은 명령 — `expected: Money(amount=3500, currency=KRW) but was: Money(amount=0, currency=KRW)`. `total`이 `Money.zero` stub | `total`을 `lines.fold(Money.zero(currency)) { acc, line -> acc + line.amount }`로 교체 후 같은 명령 통과 | `./gradlew test --tests 'com.resilientpos.order.*'` — 5개 통과, `./gradlew test --tests 'com.resilientpos.foundation.*'` — 12개 통과 |
| `도메인 레이어는 프레임워크와 adapter와 application에 의존하지 않는다` | 규칙 추가 후 `./gradlew test --tests 'com.resilientpos.DependencyDirectionTest'` 통과. RED 대신 부정 확인: `Order`에 `@org.springframework.stereotype.Component`를 임시로 붙이고 같은 명령 실행 → `DependencyDirectionTest > 도메인 레이어는 ... FAILED`, `2 tests completed, 1 failed`. 되돌린 뒤 통과 | 같은 명령 통과 | `./gradlew test` — BUILD SUCCESSFUL, 25 tests |

## 결과
`./gradlew test` BUILD SUCCESSFUL (25 tests, 기존 19 + `OrderTest` 5 + 도메인 레이어 규칙 1). 인수 조건 1~8을 만족했다.

- `ModuleStructureTest`는 수정 없이 통과했다. `order`가 `store`·`terminal`·`foundation`의 공개 타입에만 의존하고 `internal.domain`은
  모듈 밖에 노출되지 않는다. 변경 경로에서 이 파일을 뺐다.
- `OrderLine`은 `Order.kt`가 아니라 별도 파일 `OrderLine.kt`에 두었다. 수량·단가 불변식을 line 자신의 `init`이 소유해 `Order.create`는
  line 목록과 통화 일치만 본다.
- `Order.CURRENCY_MISMATCH`는 `Money.CURRENCY_MISMATCH`와 같은 문자열이지만 뜻이 다르다. `Money`는 연산 두 항의 통화 불일치,
  `Order`는 주문 통화와 line 단가 통화의 불일치다. 각자의 상수로 둔다.
- 정상 경로 테스트는 RED를 관측하지 못했다. 공개 타입 7개를 먼저 만들어야 테스트가 컴파일되는데, 그 최소 선언 단계에서 생성 경로가
  함께 완성됐다. 실패 사례 4개와 합계 계산은 RED→GREEN을 관측했다.
