# PR spec: 모듈을 건너가는 이벤트를 contract 모듈로 옮긴다

## 목적
`order`와 `payment`가 서로의 사실을 이벤트로 주고받는 구조가 Modulith의 순환 검사에 걸린다. `payment`는 이미 `order`의 `OrderPaymentRequested`를 받고, 정본이 정한 대로 `order`가 `PaymentApproved`를 받는 순간 `order -> payment -> order` 순환이 된다. 재현해 확인했다. 두 모듈이 함께 의존하는 `contract` 모듈을 만들어 그 간선을 끊는다.

## 범위
새 application module `com.resilientpos.contract`를 만든다. **모듈을 건너가는 이벤트만** 둔다.

| 옮기는 것 | 이유 |
|---|---|
| `OrderPaymentRequested` | `payment`가 받는다 |
| `PaymentApproved` | `order`·`reconciliation`이 받는다([도메인 모델](../wiki/domain-model.md) §2) |
| `PaymentAttemptKind` | `PaymentApproved`의 payload다. 소비자가 환불 승인을 결제 승인으로 읽지 않으려면 이 값을 읽어야 한다 |

소비자가 없는 `OrderCreated`·`PaymentAttemptCreated`는 소유 모듈에 남는다. 경계를 넘는 것만 공유한다는 의도가 타입에 드러난다.

**옮긴 이벤트는 소유 모듈의 sealed 계층에 남을 수 없다.** 선택이 아니라 Kotlin 제약이다. sealed 타입의 구현은 같은 패키지에 있어야 해서(`A class can only extend a sealed class or interface declared in the same package.`) 패키지를 옮기는 순간 `OrderEvent`·`PaymentEvent`를 구현할 수 없다. `DomainEvent`를 직접 구현한다. 그래서 한 aggregate가 두 종류를 내고, `Transition`의 이벤트 타입 인자가 `OrderEvent`에서 `DomainEvent`로 넓어진다. 지금 그 sealed 닫힘을 쓰는 `when`은 없다.

**`contract`의 타입은 다른 업무 모듈의 타입을 들지 않는다.** `OrderPaymentRequested.orderId`와 `PaymentApproved.paymentId`가 raw `UUID`가 된다. 들면 그 방향으로 간선이 생겨 순환이 되돌아온다. `store`·`terminal`의 ID 값 타입과 `foundation`의 `Money`는 그대로 쓴다 — 그 모듈들은 `contract`를 의존하지 않는다.

**`foundation`에 두지 않는다.** [도메인 모델](../wiki/domain-model.md) §3이 "`foundation`은 모델링 원시 타입만 공유하고 주문·결제 같은 업무 개념을 두는 곳이 아니다"라고 정했다. `OrderPaymentRequested`는 업무 사실이다.

계약: 이벤트의 패키지가 바뀐다. `event_publication.event_type`에 정규화된 클래스 이름이 들어가므로 **이미 저장된 발행 기록은 복원되지 않는다.** 배포된 환경이 없어 그런 행이 없다. 배포된 뒤라면 이 이동은 **첫 소비자와 같은 배포에 실려야 한다** — 옛 이름으로 남은 미완료 기록을 새 이름이 가져가지 못하므로, 이동만 먼저 내보내고 소비자를 나중에 내보내면 그 사이에 쌓인 기록이 영구 미완료가 된다.

같은 이유로 **리스너를 옮기는 것도 저장 형식을 건드린다.** `listener_id`에 리스너 메서드의 정규화된 이름이 들어가서, 리스너의 패키지가 바뀌면 옛 이름으로 남은 미완료 기록을 새 리스너가 가져가지 못한다. 이 PR은 리스너를 옮기지 않아 해당하지 않지만, 앞으로 이벤트나 리스너를 옮기는 PR은 배포된 행이 있는지부터 본다.

복구: 해당 없음. 타입이 옮겨질 뿐 동작이 바뀌지 않는다.

관측성: 해당 없음.

## 제외
- `order`가 `PaymentApproved`를 실제로 소비하는 리스너. 이 PR은 그 리스너가 들어올 수 있게 길만 연다. 금액 대조와 reconciliation case 열기는 그 PR이 정한다.
- `OrderCreated`·`PaymentAttemptCreated`를 옮기는 것. 소비자가 생기면 그 PR이 옮긴다.
- `OrderPaid` 등 아직 없는 이벤트.
- **`DependencyDirectionTest`의 프레임워크 금지 규칙에 `contract`를 더하는 것.** 파일 상한 25개에 한 칸이 모자라 뺐다. `contract`는 지금 프레임워크를 전혀 들지 않고 위 방향 검사가 업무 모듈 쪽은 막으므로, 다음 PR이 한 줄로 더한다.
- `@ApplicationModule(allowedDependencies = ...)`로 방향을 잠그는 것. [payment-domain-model](2026-09-22-payment-domain-model.md)에서 이 구성에서는 위반을 잡지 못한다는 것을 실측했다.
- 이벤트 저장 형식의 버전 관리. 배포 환경이 없어 지금 필요 없다.
- **모듈 안 이벤트와 모듈 밖 이벤트를 다른 타입으로 나누는 것**(internal event / integration event 분리). 지금은 경계를 넘는 이벤트가 둘뿐이라 타입 두 벌이 붙잡는 실수보다 읽을 개념이 더 는다. 두 형태 모두 저장 형식이 같아서(`event_type`은 어차피 클래스 정규 이름) 나중에 나눠도 마이그레이션 비용이 새로 생기지 않는다.
- **`contract`가 ID 값 타입을 소유하는 것.** `OrderId`를 `contract`로 올리면 raw `UUID` 없이 계약을 쓸 수 있지만, 그러면 `contract`가 업무 개념의 소유자가 되어 bounded context가 아니라는 이 모듈의 성격이 무너진다. 이것도 저장 형식에 영향이 없다 — 직렬화되는 값은 어느 쪽이든 UUID 문자열이다.

다시 볼 조건: `store`나 `terminal`이 `contract`의 이벤트를 소비하게 되면 그 모듈들도 `contract`를 의존하게 되므로, 지금 `contract`가 그 둘의 ID 값 타입을 드는 것이 역방향 간선이 된다. 그때 ID 표현을 다시 정한다.

## 관련 문서
정본은 [도메인 모델](../wiki/domain-model.md) §2·§3·§4.5다. 순환을 예고한 선행 PR은 [payment-attempt-from-order-event](2026-09-22-payment-attempt-from-order-event.md)다.

## 변경 경로
- src/main/java/com/resilientpos/contract/package-info.java
- src/main/kotlin/com/resilientpos/contract/**
- src/main/kotlin/com/resilientpos/order/**
- src/main/kotlin/com/resilientpos/payment/**
- src/test/kotlin/com/resilientpos/**
- src/test/kotlin/com/resilientpos/ModuleStructureTest.kt
- docs/**

## 인수 조건
1. `ModuleStructureTest`가 `contract`를 application module로 인식한다.
2. `order`가 `PaymentApproved`를 소비해도 순환이 생기지 않는다. 프로브로 확인한다.
3. `contract`는 `order`·`payment`를 import하지 않는다.
4. 주문 생성부터 checkout, 결제 시도 기록까지 기존 동작이 그대로다.
5. 이벤트를 JSON으로 저장했다 복원하면 값이 같다.
6. 기존 테스트가 하나도 깨지지 않는다.

## 검증
- `./gradlew test` — 전부 통과. Docker가 필요하다.
- `python3 scripts/check_pr.py --base feature/payment-attempt-facade --worktree` — PASS. 이 PR은 선행 PR 위에 쌓는다.
- 순환 부재는 `order`에 `PaymentApproved`를 받는 클래스를 임시로 두고 `ModuleStructureTest`로 확인한다. 옮기기 전에는 실패하고 옮긴 뒤에는 통과해야 한다.

## Wiki 영향
- [도메인 모델](../wiki/domain-model.md) — §2·§3에 `contract` 모듈과 그 경계를 더한다. bounded context 7개와 다른 성격이라는 것도 적는다.
- 현재 상태(`docs/wiki/current-state.md`, 볼트) — 모듈 목록, `payment`의 순환 빈틈 해소, 이벤트 위치를 갱신한다.
- 요구사항 변화: 없다. API도 스키마도 동작도 그대로다.

## 분할 계획
예상 10개 파일 안팎 / 약 300줄. 한도 안이다. 대부분 import와 패키지 이동이다.

## TDD 검증 기록
RED를 먼저 관측했다. `order/internal/application`에 `PaymentApproved`를 받는 임시 소비자를 두고 `ModuleStructureTest`를 돌리니 `Cycle detected: Slice order -> Slice payment -> Slice order`로 실패했다. 이벤트를 옮긴 뒤 같은 소비자를 둔 채 다시 돌리니 통과했고, 그다음 소비자를 지웠다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| order가 PaymentApproved를 소비해도 순환이 없다 | `./gradlew test --tests '…ModuleStructureTest'` — `Cycle detected` | 이벤트 이동 뒤 같은 명령 — 통과 | `./gradlew test` — 통과 |
| contract를 application module로 인식한다 | 같은 명령 — 목록에 `contract`가 없음 | 목록에 더한 뒤 통과 | `./gradlew test` — 통과 |
| contract는 업무 모듈을 의존하지 않는다 | `PaymentApproved.kt`에 `import com.resilientpos.order.OrderId`를 넣으면 FAILED | 빼면 통과 | `./gradlew test` — 통과 |

## 결과
`./gradlew test` 167개 통과다(직전 165 + 신규 2).

- **의존 방향은 ArchUnit이 바이트코드로 검사한다.** `ModuleStructureTest`가 `contract`는 업무 모듈(`order`·`payment`·`sync`·`reconciliation`·`fulfillment`)과 `platform`을 의존하지 않는다고 단언한다. 소스 파일의 import 문자열을 읽는 검사와 달리 FQN 직접 표기·하위 패키지·다른 소스 레이아웃(`src/main/java`) 어느 쪽으로 우회해도 잡힌다. `PaymentApproved`에 `OrderId` 필드를 넣어 FAILED를 실제로 관측했다.
- **저장 형식은 리터럴로 고정했다.** 이벤트의 정규 이름이 `event_publication.event_type`에 그대로 들어가므로, 클래스에서 기대값을 다시 계산하는 단언은 이름이 바뀌어도 통과해 아무것도 지키지 않는다. `ModuleStructureTest`와 `OrderEventPublicationIntegrationTest` 양쪽에서 `"com.resilientpos.contract.OrderPaymentRequested"` 문자열을 직접 단언한다.
- **sealed 닫힘 상실의 대가는 지금 0이다.** 이벤트를 분기하는 `when`이 코드에 하나도 없다. 다만 `OrderEvent`·`PaymentEvent`가 각각 구현체 하나만 남았다 — 모듈 안에서만 쓰는 이벤트가 더 생길 자리이고, 끝내 생기지 않으면 그때 걷어낼 값이다.
- **순환이 풀렸다.** 임시 소비자를 둔 채 확인했고 지운 뒤에도 회귀 테스트가 방향을 지킨다. Modulith의 순환 검사는 **소비자가 실제로 생긴 뒤에야** 걸리므로 그 전에 방향을 지킬 검사가 따로 필요하다.
- **`PaymentAttemptKind`도 함께 옮겼다.** `PaymentApproved`의 payload라서 남겨 두면 `contract -> payment` 간선이 생겨 순환이 되돌아온다. 소비자가 환불 승인을 결제 승인으로 읽지 않으려면 읽어야 하는 값이므로 계약의 일부가 맞다.
- **식별자가 raw `UUID`가 됐다.** `OrderPaymentRequested.orderId`와 `PaymentApproved.paymentId`다. 계약이 업무 모듈의 타입을 들면 간선이 생긴다. 받는 쪽이 자기 타입으로 감싼다.
- **`Transition`의 타입 인자가 넓어졌다.** 옮긴 이벤트가 소유 모듈의 sealed 계층에서 빠지므로 한 aggregate가 두 종류를 낸다. `Transition`의 이벤트 타입 인자가 `OrderEvent`에서 `DomainEvent`로 바뀌었다. 그 sealed 닫힘을 쓰는 `when`이 없어서 잃은 것이 없다.
- **`OrderCreated`는 그대로다.** 소비자가 없어 옮기지 않았다. 일괄 치환이 `OrderCreated.orderId`까지 `UUID`로 바꿔 테스트가 깨졌고, 되돌렸다.

이벤트의 패키지가 바뀌므로 이미 저장된 `event_publication` 행은 복원되지 않는다. 배포된 환경이 없어 그런 행이 없다.
