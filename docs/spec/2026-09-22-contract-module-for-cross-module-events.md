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

**옮긴 이벤트는 소유 모듈의 sealed 계층에 남을 수 없다.** 남기면 `contract`가 `order`·`payment`를 의존하게 되어 순환이 되돌아온다. `DomainEvent`를 직접 구현한다. 그래서 한 aggregate가 두 종류를 내고, `Transition`의 이벤트 타입 인자가 `OrderEvent`에서 `DomainEvent`로 넓어진다. 지금 그 sealed 닫힘을 쓰는 `when`은 없다.

**`contract`의 타입은 다른 업무 모듈의 타입을 들지 않는다.** `OrderPaymentRequested.orderId`와 `PaymentApproved.paymentId`가 raw `UUID`가 된다. 들면 그 방향으로 간선이 생겨 순환이 되돌아온다. `store`·`terminal`의 ID 값 타입과 `foundation`의 `Money`는 그대로 쓴다 — 그 모듈들은 `contract`를 의존하지 않는다.

**`foundation`에 두지 않는다.** [도메인 모델](../wiki/domain-model.md) §3이 "`foundation`은 모델링 원시 타입만 공유하고 주문·결제 같은 업무 개념을 두는 곳이 아니다"라고 정했다. `OrderPaymentRequested`는 업무 사실이다.

계약: 이벤트의 패키지가 바뀐다. 이벤트는 JSON으로 저장되고 `event_publication.event_type`에 정규화된 클래스 이름이 들어가므로 **이미 저장된 발행 기록은 복원되지 않는다.** 배포된 환경이 없어 그런 행이 없다.

복구: 해당 없음. 타입이 옮겨질 뿐 동작이 바뀌지 않는다.

관측성: 해당 없음.

## 제외
- `order`가 `PaymentApproved`를 실제로 소비하는 리스너. 이 PR은 그 리스너가 들어올 수 있게 길만 연다. 금액 대조와 reconciliation case 열기는 그 PR이 정한다.
- `OrderCreated`·`PaymentAttemptCreated`를 옮기는 것. 소비자가 생기면 그 PR이 옮긴다.
- `OrderPaid` 등 아직 없는 이벤트.
- `@ApplicationModule(allowedDependencies = ...)`로 방향을 잠그는 것. [payment-domain-model](2026-09-22-payment-domain-model.md)에서 이 구성에서는 위반을 잡지 못한다는 것을 실측했다.
- 이벤트 저장 형식의 버전 관리. 배포 환경이 없어 지금 필요 없다.

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
- [현재 상태](../wiki/current-state.md) — 모듈 목록, `payment`의 순환 빈틈 해소, 이벤트 위치를 갱신한다.
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

- **의존 방향 검사를 허용 목록으로 뒀다.** 처음에는 업무 모듈을 금지하는 목록이었는데 두 구멍이 있었다. `platform`처럼 프레임워크가 섞인 모듈을 막지 못했고, 하위 디렉터리를 훑지 않아 `contract/nested/`의 위반이 빠져나갔다. 허용 목록(`foundation`·`store`·`terminal`)과 재귀 탐색으로 바꾸고 둘 다 실제로 잡히는 것을 확인했다.
- **sealed 닫힘 상실의 대가는 지금 0이다.** 이벤트를 분기하는 `when`이 코드에 하나도 없다. 다만 `OrderEvent`·`PaymentEvent`가 각각 구현체 하나만 남았다 — 모듈 안에서만 쓰는 이벤트가 더 생길 자리이고, 끝내 생기지 않으면 그때 걷어낼 값이다.
- **순환이 풀렸다.** 임시 소비자를 둔 채 확인했고, 지운 뒤에도 회귀 테스트 둘이 방향을 지킨다. 하나는 두 이벤트가 `contract` 패키지에 있다는 것을, 다른 하나는 `contract` 소스가 업무 모듈을 import하지 않는다는 것을 본다. Modulith의 순환 검사는 **소비자가 실제로 생긴 뒤에야** 걸리므로 그 전에 방향을 지킬 검사가 따로 필요하다.
- **`PaymentAttemptKind`도 함께 옮겼다.** `PaymentApproved`의 payload라서 남겨 두면 `contract -> payment` 간선이 생겨 순환이 되돌아온다. 소비자가 환불 승인을 결제 승인으로 읽지 않으려면 읽어야 하는 값이므로 계약의 일부가 맞다.
- **식별자가 raw `UUID`가 됐다.** `OrderPaymentRequested.orderId`와 `PaymentApproved.paymentId`다. 계약이 업무 모듈의 타입을 들면 간선이 생긴다. 받는 쪽이 자기 타입으로 감싼다.
- **`Transition`의 타입 인자가 넓어졌다.** 옮긴 이벤트가 소유 모듈의 sealed 계층에서 빠지므로 한 aggregate가 두 종류를 낸다. `Transition`의 이벤트 타입 인자가 `OrderEvent`에서 `DomainEvent`로 바뀌었다. 그 sealed 닫힘을 쓰는 `when`이 없어서 잃은 것이 없다.
- **`OrderCreated`는 그대로다.** 소비자가 없어 옮기지 않았다. 일괄 치환이 `OrderCreated.orderId`까지 `UUID`로 바꿔 테스트가 깨졌고, 되돌렸다.

이벤트의 패키지가 바뀌므로 이미 저장된 `event_publication` 행은 복원되지 않는다. 배포된 환경이 없어 그런 행이 없다.
