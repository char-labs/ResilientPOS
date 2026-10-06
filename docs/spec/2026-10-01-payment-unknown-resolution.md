# PR spec: 결과 불명 결제를 provider 조회로 확정한다

## 목적
provider 결과를 확인하지 못한 결제 시도는 `UNKNOWN`에 머물고, 그 시도를 다시 집어 provider에 묻는 주체가 없다. 그래서 주문은 `CHECKOUT_REQUESTED`에 영영 남는다. `UNKNOWN`이 재결제를 막기 때문에(`PaymentAttempt.retry`) 사용자는 다시 결제할 수도 없다. README 원칙 `UNKNOWN -> provider query -> reconciliation`의 앞 두 단계를 만든다. `payment`가 주기적으로 `UNKNOWN` 시도를 provider에 물어 승인 또는 거절로 확정하고, 승인이면 기존 `PaymentApproved` 경로로 주문이 `PAID`가 된다. 거절이면 그 시도가 재결제 대상이 된다.

이슈 #74의 세 항목 가운데 "거절 뒤 주문이 갈 상태"는 지금 상태 유지로, "재결제 입구"는 #75로 정했다(아래 범위·제외).

## 범위
소유 모듈은 `payment`다. `order`는 KDoc·`ponytail:` 표식 문구만 고치고 동작을 바꾸지 않는다. `contract`와 `reconciliation`은 바꾸지 않는다.

**거절 뒤 주문은 `CHECKOUT_REQUESTED`에 둔다.** 재결제는 [payment-retry-after-decline](2026-09-26-payment-retry-after-decline.md)이 정한 대로 같은 checkout 회차의 새 시도다. 이 상태는 "이 회차의 결제가 아직 끝나지 않았다"를 그대로 말한다. 거절이든 결과 불명이든 그 회차는 아직 끝나지 않은 것이다. 주문을 `OPEN`으로 되돌리면 두 문제가 생긴다. 늦게 도착한 checkout 재요청이 2회차를 확정해 다시 청구하고, `markPaid`는 상태를 보지 않으므로 옛 회차의 승인이 `OPEN` 주문을 `PAID`로 넘긴다. 새 상태(`PAYMENT_DECLINED`)는 재결제 승인이 오면 그 상태에서 `PAID`로 가는 전이를 더해야 하고, 그 대가로 얻는 것은 주문만 보고 거절을 아는 것뿐이다. 그래서 `PaymentEvent.Declined`·`Unknown`은 `payment`에 남고 `event_publication`의 `event_type`(저장 형식)은 그대로다. 단말은 #75의 결제 시도 조회로 거절을 안다.

**`UNKNOWN`을 확정하는 일은 `payment`가 한다.** provider port와 결제 정본 상태가 모두 `payment`의 것이다(domain-model §2). `reconciliation`은 "결제 상태를 직접 고치지 않는다". 게다가 `reconciliation`에서 `payment`로 가는 동기 호출은 §4.6이 막는다. 조회로 결말이 나는 정상 경로에는 case가 필요 없다. case는 조회로도 확정되지 않는 시도를 위한 자리라서 그것을 해소할 명령·규칙 버전·승인 주체가 생길 때 연다(제외).

**provider 조회 port.** `PaymentGateway`에 `query(request: AuthorizationRequest): AuthorizationOutcome`을 더한다. 승인 요청과 같은 요청(같은 `paymentId`, 곧 provider 멱등 키)의 결과를 묻는다. 결과 타입은 승인 요청과 같은 세 갈래다. `Unknown`은 "조회로도 확인하지 못했다"는 뜻이다. 계약은 `authorize`와 같아서 던지지 않고 `Unknown`을 돌려준다. `Declined`는 돈이 움직이지 않았다고 provider가 확인한 경우에만 쓴다. provider가 아직 처리 중이라고 답하면 `Unknown`이다. 방금 `UNKNOWN`이 된 시도도 다음 주기에 조회되므로, 처리 중을 거절로 접으면 재결제가 이중 청구가 된다. provider의 "그런 요청 없음"을 어느 갈래로 접을지는 실제 PG 어댑터 PR이 정한다. `amount`는 실제 조회에는 필요 없지만 같은 요청 값을 그대로 넘기려고 함께 싣는다.

**`FakePaymentGateway` 규칙.** 최소 단위 끝자리로 다섯 경로를 낸다. 통합 테스트와 compose가 스텁 없이 모든 경로를 밟게 하려는 것이다.

| 끝자리 | `authorize` | `query` |
|---|---|---|
| 1 | `Declined` | 부르지 않음 |
| 2 | `Unknown` | `Approved`(`FAKE-` 참조) |
| 3 | `Unknown` | `Declined`(`FAKE-` 참조) |
| 4 | `Unknown` | `Unknown` |
| 그 밖 | `Approved` | 부르지 않음 |

끝자리 3·4는 지금 승인 경로다. 그 금액으로 승인을 기대하는 기존 테스트 픽스처는 다른 끝자리로 옮긴다. `query`에 2·3·4가 아닌 금액이 오면 `authorize`와 같은 답을 준다.

**전이는 aggregate가 한다.** `PaymentAttempt`에 `UNKNOWN`에서만 허용하는 확정 전이 두 개를 더한다. 조회가 승인이면 `APPROVED`와 `PaymentApproved`(provider 참조 포함)를, 거절이면 `DECLINED`와 `PaymentEvent.Declined`를 낸다. 결과는 승인 요청에서 바로 결말이 났을 때와 같은 행 상태와 같은 이벤트다. 그래서 새 이벤트·토픽·consumer가 없고, 승인은 기존 `PaymentApprovedListener`가 주문 `PAID`로 잇는다. `UNKNOWN`이 아닌 시도에는 기존 `PAYMENT_RESULT_ALREADY_APPLIED`로 거절한다(새 오류 code 없음). `REQUESTED`는 이 경로로 오지 않는다. 대상 조회가 `UNKNOWN`만 고르고 Facade가 잠근 뒤 다시 확인하기 때문이다. 불변식 "provider 결과는 한 번만 적용한다"는 그대로다. `UNKNOWN`은 결과가 아니라 결과가 없다는 기록이라, 확정은 결과를 처음 적용하는 일이다. KDoc과 domain-model §2에 이 해석을 적는다.

**Facade: `PaymentManagement.resolveUnknown(PaymentCommand.ResolveUnknown(paymentId))`.** 결과는 `PaymentCommand.ResolveUnknown.Result(paymentId, status)`다. `authorize`와 같은 모양이다.
1. `@Transactional(propagation = NOT_SUPPORTED)`. 처음 읽는 `findById`는 `Tx.masterTx`로 감싸 provider를 부르는 동안 DB 자원을 쥐지 않는다.
2. `UNKNOWN`이 아니거나 승인 시도(`AUTHORIZATION`)가 아니면 provider를 부르지 않고 지금 상태를 돌려준다. 조회 요청은 승인 요청이라 취소 시도에 쓰면 취소를 승인 조회로 확정하게 된다. 취소 시도를 만드는 코드는 아직 없고, 생기는 PR이 취소 조회를 정한다.
3. `gateway.query`를 트랜잭션 밖에서 부른다.
4. 결과가 `Unknown`이면 아무것도 쓰지 않고 `UNKNOWN`을 돌려준다. 가짜 성공도 가짜 실패도 만들지 않는다.
5. `Tx.masterTx`에서 `findByIdForUpdate`로 행을 잠그고 다시 읽는다. 여전히 `UNKNOWN`이면 확정 전이를 저장하고 같은 커밋에서 발행한다. 아니면 그 결말을 돌려준다.

호출자 트랜잭션에 합류하지 않으므로 sync intake의 한 커밋 안에서 부르지 않는다(`authorize`와 같은 KDoc 계약).

**주기 작업: `payment.internal.application.UnknownPaymentResolver`.** `@Scheduled(fixedDelay)`이고 간격은 `resilientpos.payment.unknown-resolve-interval`(기본 1분)이다. 모든 pod에서 돌고 `Lock.executeWithLockNoTx("payment:unknown-resolve")` 블록이 pod 사이를 줄 세운다. 블록이 곧 락 범위이고, 블록 안의 모든 트랜잭션은 블록이 끝나기 전에 커밋된다.

```kotlin
@Scheduled(fixedDelayString = "\${resilientpos.payment.unknown-resolve-interval}", initialDelayString = "\${resilientpos.payment.unknown-resolve-interval}")
fun resolveUnknown() = Lock.executeWithLockNoTx(LOCK_KEY) {
    repository.findUnknownAuthorizationIds(BATCH_SIZE)          // @ReadOnlyTransactional, id 오름차순
        .forEach { resolveOne(it) }                              // 한 건 실패는 ERROR 로그를 남기고 다음 건으로
}
```

- 대상은 `kind = AUTHORIZATION`이고 `status = UNKNOWN`인 시도다. 오래된 것(`id` 오름차순, UUID v7)부터 한 주기에 최대 100건이다. 앞쪽 N건을 읽는 조회가 `JdslQueryExecutor`에 없어 `findAll(limit) { }` 오버로드를 더한다(`setMaxResults` 한 줄, 기존 호출은 그대로).
- 한 건이 던지면(잠금 대기 초과, DB 오류) 그 건의 `paymentId`와 예외를 ERROR로 남기고 다음 건으로 간다. 한 건이 배치 전체를 막지 않게 하려는 것이다. 그 건은 `UNKNOWN`으로 남아 다음 주기에 다시 잡힌다. 대상 조회 자체가 던지면 그 주기를 건너뛰고 스케줄러가 로그를 남긴다.
- 조회로도 확정되지 않은 건은 다음 주기에 다시 묻는다. 횟수 상한은 없다.

**스케줄러 스레드.** Spring 공용 스케줄러는 기본 스레드가 하나다. 이 작업이 provider 조회로 길어지면 같은 스레드의 발행 기록 재전송(`EventPublicationResubmitter`)과 Modulith Moments가 그동안 멈추고, 재전송이 늦으면 확정한 `PaymentApproved`의 전달도 늦는다. `spring.task.scheduling.pool.size`를 2로 두어 두 작업이 서로를 막지 않게 한다. 실제 PG 어댑터 PR이 조회 타임아웃과 함께 다시 본다.

**여러 pod와 재전달에서의 멱등.** 정합성은 행 잠금 뒤 `UNKNOWN`을 다시 확인하는 데서 나온다. 두 pod가 같은 시도를 함께 확정해도 하나만 적용하고 이벤트도 하나만 낸다. 락은 provider 중복 조회를 줄이는 완화 수단이다. Redis 장애나 대기 초과로 fail-open되거나 블록이 TTL(10초)을 넘겨 다른 pod가 들어와도(배치가 락 대기 한도 3초를 넘기면 늦게 온 pod는 fail-open으로 함께 돈다), provider 조회는 읽기라 겹쳐도 해가 없다. 확정으로 난 `PaymentApproved`가 다시 배달되면 `markPaid`가 이미 `PAID`인 주문에 아무것도 하지 않는다(기존 멱등).

**`Lock` 블록 안의 행 잠금.** `Lock` KDoc과 §4.7 질문 6은 "블록 안에서 행 잠금을 기다리지 않는다"고 정한다. 막으려는 것은 락을 쥔 쪽은 행을, 행을 쥔 쪽은 락을 기다리는 교착이다. 이 블록에서 잠그는 `UNKNOWN` 시도 행을 잠그는 다른 경로는 `authorize`의 적용과 `retryAuthorization`이다. 둘 다 이 락 키를 잡지 않으므로 순환이 생기지 않는다. 이 예외와 근거를 작업 KDoc에 적는다.

**`Order.markPaid`의 `ponytail:` 표식.** 해제 조건이 "결말이 난 시도를 바꾸는 경로(reconciliation)가 생기면"이다. 이번 확정은 `UNKNOWN`을 바꾸지만 한 회차에 승인이 둘이 되는 길은 여전히 없다. `UNKNOWN`도 partial unique `ux_payment_attempt_inflight_authorization`의 술어 안에 있다. 그래서 확정 전에는 같은 회차에 새 시도가 들어가지 못하고, `APPROVED`·`DECLINED`는 다시 바뀌지 않는다. `paymentId` 대조는 들이지 않고 표식 문구만 고친다. 그 전제를 통합 테스트(결과 불명 → 거절 확정 → 재결제 → 승인 → `PAID`)가 고정한다.

**계약.** 새 공개 타입은 `PaymentCommand.ResolveUnknown`과 그 `Result`다. 새 설정 키는 `resilientpos.payment.unknown-resolve-interval`이다. 새 오류 code, 새 enum 값, 새 이벤트, 새 토픽은 없다. `event_publication`의 `event_type`·`listener_id`도 바뀌지 않는다. 스키마에는 인덱스 하나를 더한다(스키마 변경 검토).

**복구.** 확정 트랜잭션이 실패하면 아무것도 남지 않고 다음 주기에 다시 한다. 확정 뒤 주문 전이 실패는 기존 `PaymentApprovedListener`의 재시도·DLT 경로를 그대로 따른다. 작업이 멈추면 `UNKNOWN`이 쌓일 뿐 잘못 확정되지는 않는다. 같은 Facade를 사람이 부르는 운영 입구는 없다(제외).

**관측성.** 로그만 남긴다. 확정하면 INFO(`paymentId`·`orderId`·`checkoutNo`·결말), 조회로도 불명이면 WARN(`paymentId`·`orderId`), 한 건 실패는 ERROR(`paymentId`와 예외)다. 확정 로그는 `Tx.masterTx`가 반환한 뒤에 찍어 로그 시점이 곧 커밋 뒤가 되게 한다. metric은 제외 절에 있다.

## 제외
- `reconciliation` case(`t_reconciliation_case`, `ReconciliationManagement`). 조회로도 확정되지 않는 시도를 넘겨받는 자리다. 해소할 명령·규칙 버전·승인 주체가 없으면 열기만 하는 표가 된다. 그것들을 정하는 PR이 만든다. 그 전까지는 WARN 로그가 단서다.
- 재결제의 운영 호출자. 단말이 재결제를 요청하려면 거절된 시도의 `paymentId`가 필요한데 그 값을 알려 줄 조회 API가 #75다. 단말 토큰 인증은 #81로 들어왔지만 토큰을 발급할 HTTP 입구가 아직 없어 외부 노출 전이다. sync operation으로 받는 길은 단말이 `paymentId`를 모르고, `retryAuthorization`이 따로 커밋하는 구조라 sync의 한 커밋 안에서 부를 수 없다. #75가 endpoint로 만든다.
- 거절·불명을 `order`가 받는 것과 `PaymentDeclined`·`PaymentUnknown`의 `contract` 이동. 위 범위의 결정이다. 주문 취소나 다른 결제 수단처럼 주문이 거절을 알아야 하는 명령이 생기면 그 PR이 옮긴다.
- UNKNOWN 건수 gauge, 작업 생존 metric, 알림 규칙. [event-publication-resubmit](2026-09-29-event-publication-resubmit.md)과 같이 알림 규칙을 만드는 PR이 함께 정한다.
- 조회 횟수·마지막 조회 시각 컬럼과 조회 상한. 상한에 걸린 건을 넘겨받을 case가 없고, 상한 값을 정할 실제 provider 근거도 없다.
- `REQUESTED`로 멈춘 시도(승인 요청 consumer가 끝내 실패해 DLT에 간 것). 단서는 DLT 메시지이고 재투입은 기존 절차다. 이 작업은 `UNKNOWN`만 본다.
- `CANCELLATION` 시도의 조회. 취소 시도를 만드는 코드가 없다.
- 실제 PG 어댑터, 조회 타임아웃, provider의 "요청 없음" 처리.
- 조회 결과의 금액·참조를 시도와 대조하는 것. 대조가 어긋날 때 갈 곳이 case라서 case와 함께 한다.

## 관련 문서
- [payment-gateway-authorization](2026-09-23-payment-gateway-authorization.md)
- [payment-retry-after-decline](2026-09-26-payment-retry-after-decline.md)
- [order-mark-paid](2026-09-23-order-mark-paid.md)
- [event-publication-resubmit](2026-09-29-event-publication-resubmit.md)(`Lock.executeWithLockNoTx` 주기 작업의 선례)

## 변경 경로
- src/main/kotlin/com/resilientpos/payment/**
- src/main/kotlin/com/resilientpos/order/internal/domain/Order.kt
- src/main/kotlin/com/resilientpos/platform/JdslQueryExecutor.kt
- src/main/kotlin/com/resilientpos/platform/Lock.kt
- src/main/kotlin/com/resilientpos/platform/PlatformConfiguration.kt
- src/main/resources/application.yaml
- src/main/resources/db/migration/**
- src/test/kotlin/com/resilientpos/**
- src/test/resources/**
- docs/spec/2026-10-01-payment-unknown-resolution.md
- docs/wiki/domain-model.md
- docs/wiki/entity-map.md
- docs/wiki/observability.md

## 인수 조건
1. `FakePaymentGateway`가 위 표대로 답한다. 끝자리 1과 그 밖의 `authorize`는 지금과 같다.
2. `PaymentAttempt`의 확정 전이는 `UNKNOWN`에서만 된다. 승인은 `APPROVED`·provider 참조·`PaymentApproved`를, 거절은 `DECLINED`·provider 참조·`PaymentEvent.Declined`를 낸다. `UNKNOWN`이 아니면 `PAYMENT_RESULT_ALREADY_APPLIED`로 거절한다.
3. `resolveUnknown`이 `UNKNOWN` 시도를 조회 결과대로 확정하고 같은 커밋에서 발행한다. 승인은 `PaymentApproved`의 발행 기록이 같은 커밋에 남는다. 거절(`PaymentEvent.Declined`)은 받는 쪽이 없어 발행 기록이 생기지 않으므로 발행 자체를 관측한다. 조회 결과가 `Unknown`이면 행도 발행 기록도 바뀌지 않는다. `UNKNOWN`이 아닌 시도는 provider를 부르지 않고 지금 상태를 돌려준다.
4. provider 조회 순간 그 스레드에 EntityManager와 커넥션이 묶여 있지 않다.
5. 같은 시도의 `resolveUnknown`이 동시에 둘 와도 한 번만 적용되고 이벤트는 하나다.
6. 주기 작업 한 번이 `UNKNOWN` 승인 시도만 오래된 순으로 최대 100건 처리한다. 취소 시도는 고르지 않고, `resolveUnknown`에 직접 넘겨도 조회하지 않는다. 한 건이 던져도 나머지는 처리된다. 작업은 `payment:unknown-resolve` 키의 `Lock` 블록 안에서 돌고, 다른 쪽이 그 키를 쥔 동안에는 조회하지 않는다. 대상 조회의 `kind`·`status`는 리터럴로 렌더된다(테스트가 렌더된 쿼리를 확인한다).
7. 끝자리 2인 주문을 checkout하면 결제가 `UNKNOWN`이 되고, 작업 한 번 뒤 시도는 `APPROVED`, 주문은 `PAID`가 된다(Kafka 포함 통합).
8. 끝자리 3인 주문은 작업 뒤 시도가 `DECLINED`, 주문은 `CHECKOUT_REQUESTED`다. 그 시도에 `retryAuthorization`을 하면 새 시도가 생기고, 승인되면 주문이 `PAID`가 된다(재결제 승인은 테스트 전용 gateway).
9. 끝자리 4인 주문은 작업 뒤에도 시도가 `UNKNOWN`, 주문은 `CHECKOUT_REQUESTED`다.
10. V013 인덱스가 유효한(`indisvalid`) 인덱스로 만들어진다.
11. `./gradlew test` 전체와 `python3 scripts/check_pr.py --base origin/main --worktree`가 통과한다.

### Checkpoint와 검증 명령
| Checkpoint | 검증 명령 | 의존 |
|---|---|---|
| fake (1) | `./gradlew test --tests 'com.resilientpos.payment.internal.adapter.gateway.FakePaymentGatewayTest'` | 없음 |
| 전이 (2) | `./gradlew test --tests 'com.resilientpos.payment.internal.domain.*'` | 없음 |
| Facade (3~5) | `./gradlew test --tests 'com.resilientpos.payment.internal.application.*'` | fake, 전이 |
| 작업 (6) | `./gradlew test --tests 'com.resilientpos.payment.internal.application.UnknownPaymentResolver*'` | Facade |
| 주문까지 (7~9) | `./gradlew test --tests 'com.resilientpos.payment.*' --tests 'com.resilientpos.order.*'` | 작업 |
| 인덱스 (10) | `./gradlew test --tests 'com.resilientpos.SchemaMigrationTest'` | 없음 |
| 전체 (11) | `./gradlew test` · `python3 scripts/check_pr.py --base origin/main --worktree` | 모두 |

## 스키마 변경 검토
`t_payment_attempt`에 `create index concurrently ix_payment_attempt_unknown_authorization on t_payment_attempt (id) where kind = 'AUTHORIZATION' and status = 'UNKNOWN'`를 더한다(`V013`, 이 문장만 담은 마이그레이션).

질문별 답(도메인 모델 §4.7):
1. 새 값이 아니라 기존 컬럼의 인덱스다. 대체 수단이 없다. 기존 인덱스(`ix_payment_attempt_order`, 두 partial unique)는 `(order_id, ...)`로 시작하거나 `UNKNOWN`만 모으지 않아서 "모든 주문의 `UNKNOWN`"을 좁히지 못한다. 인덱스가 없으면 pod마다 매분 지우지 않는 결제 원장 전체를 순차 스캔하거나 PK 인덱스로 전부 훑는다(`UNKNOWN`이 100건 미만인 평상시에는 어느 쪽이든 끝까지 읽는다).
2. 지금 코드가 읽는다. 주기 작업의 대상 조회다.
3. 인덱스라 기존 행은 빌드가 채운다.
4. 불변식의 방어선이 아니다. 성능 인덱스다. 확정의 정합성은 행 잠금과 `UNKNOWN` 재확인이 지킨다(질문 6).
5. 정본은 `status` 컬럼 하나다.
6. `status`를 읽고 판단한 뒤 쓰는 경로가 있다(대상 조회 → provider 조회 → 확정). 경합은 확정 트랜잭션의 row lock(`findByIdForUpdate`)과 잠근 뒤의 `UNKNOWN` 재확인이 막는다. 대상 조회는 잠그지 않는다. 고른 뒤 다른 pod가 먼저 확정했으면 재확인에서 돌아선다. `Lock` 블록 안에서 행 잠금을 기다리는 예외와 그 근거는 범위 절에 있다.
7. `CREATE INDEX CONCURRENTLY`라 `SHARE UPDATE EXCLUSIVE`를 잡고 빌드 동안 읽기와 쓰기를 막지 않는다. 트랜잭션 밖에서 돌아야 해서 이 문장만 담은 마이그레이션으로 둔다. `spring.flyway.postgresql.transactional-lock=false`는 V009 때 이미 설정했다. 실패하면 INVALID 인덱스와 `flyway_schema_history`의 `success = false` 행이 남는다. 다시 배포하기 전에 `drop index concurrently if exists ix_payment_attempt_unknown_authorization`로 인덱스를 지우고 실패 행을 정리한다(`flyway repair` 또는 행 삭제). `if not exists`는 쓰지 않는다. 진행 중인 쓰기 트랜잭션을 기다리므로 이전 버전 pod의 긴 트랜잭션이 기동을 늦출 수 있고, 상한은 트랜잭션 상한 30초다. 제약이 아니라 위반 행 검사는 없다. partial 술어가 조회에 쓰이려면 플래너가 조회 조건에서 그 술어를 증명해야 한다. 바인드 파라미터의 generic plan은 그것을 증명하지 못한다(event-publication-resubmit 실측). 그래서 대상 조회는 `kind`·`status`를 리터럴로 렌더하고, 실행 계획에서 이 인덱스를 쓰는지 확인해 결과 절에 남긴다.
8. 인덱스 추가라 이전 버전의 기동과 쓰기에 영향이 없다.
9. payload나 캐시 DTO에 실리지 않는다.
- 대조 기록: code-reviewer 에이전트(읽기 전용), 2026-10-06, 불일치 없음(V013·V006·V008·V009·application.yaml·PaymentAttemptEntity·PaymentAttemptPersistenceAdapter·JdslQueryExecutor·PaymentManagement·UnknownPaymentResolver·PaymentAttempt·SchemaMigrationTest와 대조, 실행 계획은 결과 절 실측으로 확인하고 재실행하지 않음). 참고 ① 질문 1이 PK 인덱스 전체 순회를 빠뜨려 문구를 보탰다 ② 리터럴 렌더를 고정하는 회귀 테스트가 없다는 지적은 multi-review 반영에서 테스트를 더해 해소했다

## 검증
- 정상: 끝자리 2(조회 승인)는 주문 `PAID`, 끝자리 3(조회 거절)은 재결제로 `PAID`까지 간다.
- 실패: 끝자리 4(계속 불명)는 아무것도 바꾸지 않는다. 한 건이 실패해도 배치가 이어진다. 동시 확정은 한 번만 적용된다. provider 조회는 트랜잭션 밖이다.
- 기존 테스트가 끝자리 3·4를 승인으로 기대했다면 픽스처를 옮기고, 주기 작업이 테스트 중에 끼어들지 않게 테스트 설정의 간격을 길게 둔다.
- 통합 테스트는 Testcontainers의 PostgreSQL·Redis·Kafka를 쓴다(Docker는 OrbStack).

## Wiki 영향
- [domain-model](../wiki/domain-model.md)
  - §2 `payment` 불변식에 "`UNKNOWN`은 provider 조회로 한 번 확정한다"를 더하고, "`Declined`·`Unknown`은 받을 쪽이 없어 `payment`에 남는다"의 근거를 이번 결정(주문은 `CHECKOUT_REQUESTED` 유지)으로 고친다.
  - §4.3에 `UNKNOWN` 확정 주기 작업(간격·락·배치·멱등·`Lock` 블록 안 행 잠금 예외)을 적는다.
- [entity-map](../wiki/entity-map.md): `t_payment_attempt` 흐름에 `UNKNOWN` → 조회 확정을 더한다.
- [observability](../wiki/observability.md): 확정 작업의 로그 세 줄과 사람이 볼 조건(같은 `paymentId`의 WARN이 계속됨)을 적는다.
- [modulith-kafka-keywords](../wiki/modulith-kafka-keywords.md): 이벤트·토픽·consumer·Modulith 설정을 바꾸지 않는다. PR 전에 그 문서의 갱신 규칙으로 해당 여부를 다시 확인한다.
- [current-state](../wiki/current-state.md)는 볼트 정본이라 이 저장소 diff에 들지 않는다. 머지 뒤 볼트에서 갱신한다.

## 분할 계획
한 개발 컨텍스트("결제 결과가 주문까지 수렴")다. 조회 port·Fake·전이·Facade·작업·인덱스는 서로 없으면 검증할 수 없어 나누지 않는다. 재결제 HTTP endpoint와 조회 API는 #75, reconciliation case는 후속이다. 2026-10-01 작업 시작 시 origin/main은 8133048이고 열린 PR이 없었다. 2026-10-06 구현 중에 #81(단말 인증, `V012__terminal_credential.sql`)과 #82(시연 화면)가 main에 들어와 origin/main 24b2728 위로 rebase했다. 마이그레이션 번호가 겹쳐 이 PR의 인덱스를 `V013`으로 옮겼다. 그 밖에 #81과 겹친 경로는 테스트 픽스처(`OrderFixtures` 등)뿐이고 rebase 충돌은 없었다. 열린 PR #83은 `static/terminal/index.html`과 자기 spec만 바꿔 겹치지 않는다.

## TDD 검증 기록

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| Fake가 끝자리 2·3·4를 결과 불명으로 두고 조회로 승인·거절·불명을 답한다 / `FakePaymentGatewayTest` 5건(`끝자리가 3이나 4여도 승인 요청은 결과를 모른다`, `끝자리가 2인 요청을 조회하면 승인이다` 등) | `./gradlew test --tests 'com.resilientpos.payment.internal.adapter.gateway.FakePaymentGatewayTest'` 9건 중 4건 실패. 최소 선언(`query`가 `authorize`와 같은 답)이라 4503 승인 요청이 `Approved`, 끝자리 2 조회가 `Unknown`, 3·4 조회가 `Approved`였다 | 같은 명령 9건 통과 | `./gradlew test --tests 'com.resilientpos.payment.*' --tests 'com.resilientpos.order.*'` 190건 통과. 끝자리 3·4로 승인을 기대하던 픽스처는 없었다 |
| 결과 불명 시도만 조회 결과로 확정한다 / `PaymentAttemptConfirmationTest` 4건 | `./gradlew test --tests 'com.resilientpos.payment.internal.domain.*'` 26건 중 4건 실패. 최소 선언이 `requireRequested` 가드를 써서 UNKNOWN 확정 3건이 `DomainException`, REQUESTED 거절 1건은 예외가 나지 않았다 | 같은 명령 26건 통과. 그 사이 테스트의 기대값 생성 오류(다른 픽스처로 기대 이벤트를 만듦) 2건을 고쳤고 구현은 그대로였다 | `./gradlew test --tests 'com.resilientpos.payment.*'` 79건 통과 |
| `resolveUnknown`이 조회를 트랜잭션 밖에서 하고 확정과 발행을 한 커밋으로 남긴다 / `PaymentManagementTest`의 확정 단위 5건, `PaymentUnknownResolutionIntegrationTest`의 `조회가 승인이면 시도가 APPROVED가 되고 provider 참조가 남고 승인 발행 기록이 생긴다`·`조회가 거절이면 시도가 DECLINED가 되고 거절 발행 기록이 생긴다`·`결말이 난 시도는 provider를 조회하지 않고 지금 상태를 돌려준다`·`provider를 조회하는 동안 DB 자원을 쥐지 않는다` | `./gradlew test --tests 'com.resilientpos.payment.*'` 90건 중 10건 실패. 최소 선언이 늘 `UNKNOWN`을 돌려줘 확정·provider 미조회·`PAYMENT_ATTEMPT_NOT_FOUND`·조회 횟수 단언이 어긋났다 | 같은 명령 통과 | `./gradlew test --tests 'com.resilientpos.payment.*' --tests 'com.resilientpos.order.*'` 213건 통과 |
| 조회로도 불명이면 아무것도 바꾸지 않는다 / `조회로도 불명이면 행도 발행 기록도 바뀌지 않는다` | 최소 선언에서도 통과했다. 아무것도 쓰지 않는 동작이라 characterization이고 RED 증거가 아니다. 단위 테스트 `조회로도 불명이면 아무것도 저장하지도 발행하지도 않고 UNKNOWN을 돌려준다`는 조회 횟수 단언으로 실패했다 | 통과 | 위와 같다 |
| 같은 시도의 동시 확정은 한 번만 적용된다 / `PaymentUnknownResolutionConcurrencyIntegrationTest`의 `같은 시도의 확정이 겹쳐도 한 번만 적용되고 이벤트는 하나다` | 위 실행에서 실패. 두 조회가 모두 도착해야 답하는 gateway인데 최소 선언이 조회하지 않았다 | 통과 | 위와 같다 |
| 주기 작업이 결과 불명 승인 시도를 오래된 순으로 처리하고 한 건 실패에 멈추지 않는다 / `UnknownPaymentResolverIntegrationTest` 5건, `SchemaMigrationTest`의 `결제 시도 UNKNOWN 인덱스가 유효하게 만들어진다` | `./gradlew test --tests 'com.resilientpos.payment.internal.application.UnknownPaymentResolver*'` 5건 모두 실패. 빈 작업이라 `expected APPROVED but was UNKNOWN`, 조회 순서가 빈 목록, 락을 기다리지 않음, 스케줄러에 작업 없음 | 같은 명령과 `SchemaMigrationTest` 함께 11건 통과 | payment·order·platform·`SchemaMigrationTest`·`ModuleStructureTest`·`TransactionDeclarationTest` 302건 통과 |
| 결과 불명 결제가 조회 승인으로 주문 `PAID`까지 간다 / `PaymentUnknownToOrderIntegrationTest`의 `조회로 승인된 결과 불명 시도는 주문을 PAID로 만든다`, `조회로도 불명인 시도는 UNKNOWN으로 남고 주문은 CHECKOUT_REQUESTED에 머문다` | 앞 동작들로 경로가 이미 있어 처음부터 통과했다(characterization). 작업 호출을 잠시 빼면 주문 `PAID` 대기가 `ConditionTimeoutException`으로 실패하는 것을 확인하고 되돌렸다 | `./gradlew test --tests 'com.resilientpos.payment.internal.application.PaymentUnknownToOrder*'` 3건 통과 | `./gradlew test` 전체 453건 통과 |
| 조회로 거절이 확정된 시도를 재결제해 주문 `PAID`까지 간다 / `조회로 거절된 시도를 재결제하면 새 시도가 승인되어 주문이 PAID가 된다` | 위와 같이 characterization | 위와 같다 | 위와 같다 |
| 리뷰 반영: 취소 시도는 결과 불명이어도 조회하지 않는다 / `PaymentManagementTest`의 `UNKNOWN이어도 취소 시도는 provider를 조회하지 않고 지금 상태를 돌려준다`, `UnknownPaymentResolverIntegrationTest`의 `UNKNOWN 취소 시도는 조회하지 않고 그대로 둔다` | `./gradlew test --tests 'com.resilientpos.payment.PaymentManagementTest'`에서 조회 횟수 0 단언이 실패했다. 작업 수준 테스트는 대상 조회가 이미 승인 시도만 골라 characterization이다 | 같은 명령 통과 | `./gradlew test` 전체 457건 통과 |
| 리뷰 반영: 잠근 뒤 이미 확정됐으면 덮지 않는다, 락을 쥔 동안 조회하지 않는다 / `조회하는 동안 다른 호출이 먼저 확정했으면 덮지 않고 그 결말을 돌려준다`, `다른 쪽이 락을 쥐고 있으면 풀릴 때까지 기다린다`(조회 0건 단언 추가) | 이미 구현된 동작이라 characterization | 통과 | 위와 같다 |
| 리뷰 반영: 대상 조회의 리터럴 렌더 고정 / `대상 조회는 kind와 status를 바인드 파라미터가 아니라 리터럴로 렌더한다` | 구현이 이미 리터럴이라 처음부터 통과했다. 어댑터의 status 조건을 바인드 값 비교로 잠시 바꾸면 `status = :param1`이 렌더되어 실패하는 것을 확인하고 되돌렸다 | Hibernate `Statistics`의 실행 쿼리 문자열로 확인, 통과 | 위와 같다 |

## 결과
- 인수 조건 1~10을 위 테스트로 확인했다. 리뷰 반영 뒤 전체 `./gradlew test`는 457건 통과다.
- 대상 조회는 Hibernate가 `where (pae1_0.kind='AUTHORIZATION') and (pae1_0.status='UNKNOWN') and pae1_0.deleted_at is null order by pae1_0.id fetch first ? rows only`로 렌더한다(`enumLiteral`). PostgreSQL 18 컨테이너에 V001~V013을 적용하고 승인 시도 20만 건과 `UNKNOWN` 50건을 넣어 `analyze`했다. 그 뒤 이 문장을 prepare해 `plan_cache_mode = force_generic_plan`으로 실행 계획을 보면 `Index Scan using ix_payment_attempt_unknown_authorization`이다. `deleted_at is null`은 인덱스 술어에 없어 Filter로 남는다.
- 앞쪽 N건 조회가 `JdslQueryExecutor`에 없어 `findAll(limit) { }`을 더했다. 범위 조정은 spec에 먼저 기록했다.
- 거절(`PaymentEvent.Declined`)은 외부화되지 않고 받는 리스너도 없어 `event_publication`에 행이 생기지 않는다. 통합 테스트는 거절 발행을 테스트용 `@EventListener`로 관측한다.
- Kafka·Modulith: 외부화 설정·토픽·consumer group·재시도·DLT는 바꾸지 않았다. 새 발행 지점(`resolveUnknown`)은 기존 Facade와 같이 업무 상태 저장과 같은 트랜잭션에서 발행하므로, [Spring Modulith 이벤트 문서](https://docs.spring.io/spring-modulith/reference/events.html)의 "발행 기록은 발행한 트랜잭션과 함께 커밋되고 커밋 뒤 외부화된다"는 계약을 그대로 따른다(domain-model §4.3). 외부 전달을 하지 않기로 한 결정은 볼트 `modulith-kafka-keywords` 8.28에 적었다.
- 구현 중 main에 #81·#82가 들어와 rebase했고 인덱스 마이그레이션을 `V013`으로 옮겼다(분할 계획).
- multi-review(2026-10-06): Claude 86점, Codex 79점(`codex exec` 직접 실행, reviewer-codex 에이전트는 CLI 실행에 실패해 버렸다), Gemini는 CLI가 없어 미실행.
  - 반영: 공용 스케줄러 스레드 2개(Claude), 승인 시도만 확정(Codex), 리터럴 렌더 회귀 테스트(둘 다), 락 대기 테스트의 조회 0건 단언·재확인 분기 단위 테스트(Claude), entity-map §4.4·결말 화살표·마이그레이션 목록, domain-model의 `executeWithLockNoTx` 서술(바깥 트랜잭션 검사가 있다고 적힌 이전부터의 불일치), `PaymentGateway.query`의 "처리 중이면 Unknown", 낡은 KDoc·주석.
  - 반영하지 않음: 영영 불명인 건 100건의 기아(Codex Important)는 사용자와 합의한 한계라 `ponytail:` 표식과 observability 단서로 둔다. `NOT_SUPPORTED` 직접 선언이 규칙 위반이라는 지적(Codex)은 리뷰 프롬프트가 규칙을 좁게 적은 탓이다. 규칙은 `@Transactional` 애너테이션만 허용하고 `authorize`·`retryAuthorization`이 같은 선언을 쓴다(domain-model §4.3). 101건 상한 테스트는 상수 하나를 위해 행 101개를 넣는 비용에 비해 얻는 것이 적어 두지 않았다. `findAll(limit)` 오버로드는 호출부가 명확해 그대로 둔다.
