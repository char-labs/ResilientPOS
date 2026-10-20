# PR spec: 같은 키 주문 생성 경합의 unique 위반을 409로 바꾼다

## 목적
같은 `(terminalId, clientOrderId)`의 주문 생성이 겹치면 늦은 요청이 `t_order`의 unique 위반(`DataIntegrityViolationException`)으로 실패하고 HTTP에서 `500 INTERNAL_ERROR`가 된다. 다시 보내면 앞 요청이 만든 주문을 받는 경합인데 단말은 서버 오류와 구분할 수 없다. 이 위반을 기존 경합 응답 `409 CONCURRENT_REQUEST`로 바꾼다(#79).

## 범위
- 소유 모듈: `order`(persistence adapter가 제약 이름으로 위반을 가린다), `platform`(제약 이름 판별 함수를 한 곳에 둔다). Command·Query·Event와 스키마는 바뀌지 않는다. 새 오류 code와 `ErrorType` 항목은 없다.
- 2026-10-20 main(9c34aaf)에서 경합으로 500이 나는 지점을 다시 찾았다.
  - 커밋 시점 버전 충돌(`JpaSystemException`)은 이미 고쳐져 있다. #34가 `BaseEntity`의 두 시각을 `SourceType.VM`으로 바꿔 `ObjectOptimisticLockingFailureException`으로 나오고, #33의 handler가 `409 CONCURRENT_REQUEST`로 바꾼다([commit-version-conflict-optimistic-lock](2026-09-21-commit-version-conflict-optimistic-lock.md) "범위 조정 2", `OrderVersionConflictIntegrationTest`). 이슈의 근거인 [checkout API spec](2026-09-21-order-request-checkout-api.md)의 제외 절은 #34 전에 쓰였다. main에 `SourceType.DB`·`@Generated`는 없고 `@Version`은 `OrderEntity`에만 있으며 주문을 바꾸는 경로는 모두 행을 잠근다. 이 항목은 코드를 바꾸지 않는다.
  - unique 제약은 하나만 HTTP 500에 닿는다. `t_order (terminal_id, client_order_id)`(이름 없이 선언해 PostgreSQL 이름은 `t_order_terminal_id_client_order_id_key`)다. 닿는 길은 둘이다. (1) `POST /v1/orders` 두 건이 겹치고 `Lock`이 fail-open일 때(Redis 장애, 대기 3초 초과). (2) `POST /v1/orders`와 sync `CREATE_ORDER`가 같은 `clientOrderId`로 겹칠 때. 둘은 줄 세우는 수단(Redis `Lock`, watermark 행 잠금)이 달라서 Redis가 정상이어도 생긴다. (2)에서 sync가 지면 배치 전체가 500이다.
  - 나머지 unique는 HTTP 500에 닿지 않는다. `t_order_checkout`·`ux_sync_operation_*`·`ux_terminal_credential_active`는 행 잠금과 사전 조회가 막고, `ux_terminal_credential_token_hash`는 무작위 토큰이다. `ux_payment_attempt_*`는 재결제 endpoint에서는 대상 행 잠금과 최신 시도 확인이 막고, 리스너의 `createAttempt`에서는 닿지만 Kafka 재시도가 받는다. `ux_fulfillment_order`도 리스너에서만 닿는다. `ux_owner_email`과 `ux_terminal_store_code`는 이미 `409 OWNER_EMAIL_DUPLICATED`·`409 TERMINAL_CODE_DUPLICATED`이고, `t_sync_watermark` PK 위반은 sync가 삼킨다.
- 변환: `OrderPersistenceAdapter.save`가 **새 주문일 때만** 저장 끝에 `flush`한다. SQLState `23505`이고 제약 이름이 `t_order_terminal_id_client_order_id_key`이면 원인 예외를 담은 Spring `ConcurrencyFailureException`을 던지고, 그 밖의 위반은 그대로 던진다. 기존 `ApiControllerAdvice.handleConcurrency`가 이 계열을 `409 CONCURRENT_REQUEST`(WARN, 고정 문구)로 바꾼다. advice는 KDoc만 고친다.
  - 저장에서 `flush`하는 이유: 지금은 위반이 커밋에서 드러난다. sync에서는 그 커밋이 `SyncCommandFacade`의 항목 트랜잭션이라 `order`가 가로챌 자리가 없다. 저장 시점으로 당기면 adapter 한 곳이 HTTP와 sync 두 경로를 덮는다. 새 주문의 INSERT가 커밋 대신 저장 시점에 나갈 뿐 문장 수와 트랜잭션 경계는 같다. unique 키 항목은 저장 시점부터 커밋까지 쥐므로 조금 더 오래 잡힌다. flush는 영속성 컨텍스트 전체를 내보내므로, 같은 트랜잭션에서 먼저 바꾼 다른 엔티티의 위반도 여기서 드러난다(지금 그런 호출자는 없다).
- 제약 이름 판별 함수 `isUniqueViolationOf`를 `platform`의 최상위 확장 함수 하나로 옮긴다. 지금 `OwnerPersistenceAdapter`와 `TerminalPersistenceAdapter`에 같은 private 사본이 있고 주문이 세 번째 사용처다. 판별 방식은 아래 범위 조정에서 바꿨다.
- 실패·재시도·복구: 늦은 요청의 트랜잭션은 롤백되고 아무것도 남지 않는다. `POST /v1/orders`는 `clientOrderId`로 멱등이라 단말이 같은 요청을 다시 보내면 앞 요청이 커밋한 주문을 받는다. sync에서는 `DomainException`이 아니라서 항목이 `REJECTED`로 기록되지 않고 배치가 409로 끝난다. 이미 커밋된 앞 항목은 남고, 재전송하면 그 항목은 앞 주문으로 `APPLIED`가 된다.
- 관측성: 이 경로는 ERROR 로그의 `INTERNAL_ERROR`에서 WARN 로그의 `errorCode=CONCURRENT_REQUEST`로 옮겨 가고 5xx 지표에서 빠진다. 더하는 로그와 metric은 없다.

### 합의한 결정 (2026-10-20 grill)
- 범위는 `t_order (terminal_id, client_order_id)` 하나다. 위에서 HTTP 500에 닿지 않는다고 확인한 제약과 버전 충돌은 다루지 않는다.
- 위반은 `DomainException`이 아니라 경합 예외(`ConcurrencyFailureException`)로 바꾼다. 이 위반은 도메인 규칙 위반이 아니다. 멱등 생성 규칙은 앞 요청이 지켰고, 같은 요청이 겹쳤다는 경합을 뜻한다. `DomainException`으로 바꾸면 `SyncCommandFacade.applyOperation`이 그것을 도메인 거절로 보고 항목을 `REJECTED`로 영구 기록한다. 그러면 주문은 있는데 단말은 거절을 받고 새 operationId로 다시 보내야 한다. 지금은 배치가 실패한 뒤 재전송하면 `APPLIED`가 되는데 그보다 나빠진다. 대가: 저장소 코드가 Spring DAO 예외를 직접 던지는 첫 사례다. adapter가 영속 계층 예외를 Spring의 `DataAccessException` 계층으로 번역하는 자리라 계층과 어긋나지 않는다.
- 판별 함수는 `platform`으로 옮긴다. 사본 세 벌 대신 한 벌이고, owner·terminal adapter의 diff가 조금 는다.

### 검토한 선택지
- `DomainException(kind = CONFLICT)`와 새 code(점주 이메일·단말 코드와 같은 형태): 위 sync 부작용 때문에 버렸다. 새 code라서 PR Risk도 🔴이 된다.
- 늦은 요청이 새 트랜잭션으로 다시 조회해 앞 결과를 201로 받기: HTTP에는 가장 매끄럽다. 그 대신 Facade에 재시도 블록이 생긴다. sync는 위반을 예외로 감지하는 순간 호출자 트랜잭션이 중단되므로 이 방식을 쓸 수 없고, 경합 예외가 따로 필요하다. 중단 없이 하려면 `INSERT ... ON CONFLICT DO NOTHING` 뒤에 다시 조회해야 하는데, native SQL이라 Hibernate 저장 경로를 우회한다. 단말은 409를 받고 다시 보내면 같은 결과를 얻으므로 버렸다.
- `ApiControllerAdvice`에서 `DataIntegrityViolationException`의 제약 이름을 보고 409로 바꾸기: `platform`이 `order`의 제약 이름을 알게 되고, sync에서는 배치가 409가 되는 것 말고 차이가 없다. 버렸다.
- 제약에 `ux_order_terminal_client_order` 같은 이름을 붙이는 마이그레이션: 동작은 같고 마이그레이션 번호가 동시에 진행 중인 다른 작업과 겹칠 수 있다. PostgreSQL이 정한 이름을 상수로 쓰고 통합 테스트가 그 이름을 고정한다.

### 범위 조정 (2026-10-20, 제출 전 multi-review 반영)
`OrderPersistenceAdapter` 등 `@Transactional`이 있는 파일이 바뀌어 PR 템플릿 기준 🔴이다. multi-review(Claude 87점, Codex 77점. Gemini는 CLI가 없어 미실행)를 돌렸고 막는 결함은 없었다. 반영한 것은 다음과 같다.
- 판별 함수가 서버 `lc_messages`에 기대고 있었다(두 리뷰어 모두 지적). 메시지에서 ASCII 큰따옴표로 감싼 이름을 찾아서, 제약 이름을 `»…«`(독일어)나 `« … »`(프랑스어)로 감싸는 언어로 서버를 돌리면 owner·terminal·order 세 곳이 모두 500으로 돌아간다. 옮기기 전부터 있던 결함이다. 따옴표 대신 식별자 경계(앞뒤가 영문자·숫자·`_`가 아님)로 제약 이름을 찾게 바꾼다. 번역 메시지에도 제약 이름은 그대로 실리므로 언어와 관계없이 맞는다. pgjdbc `PSQLException.serverErrorMessage.constraint`(번역되지 않는 필드)가 더 정확하지만, pgjdbc를 `runtimeOnly`에서 컴파일 의존성으로 올려야 하고 그 `build.gradle.kts`를 열린 PR #94도 바꾸고 있어서 버렸다. 메시지에서 제약 이름을 빼는 번역이 생기면 그 필드로 바꾼다.
- sync 경합 테스트의 대기 조건을 좁힌다. 클러스터의 잠금 대기 세션을 모두 세지 않고, 앞 트랜잭션의 backend pid에 막힌 세션만 센다(`pg_blocking_pids`). 예외는 타입만 보지 않고 원인 사슬이 그 제약의 unique 위반인지까지 보며, 재전송 뒤 접수 기록 1행과 watermark 1을 확인한다.
- sync API(`POST /v1/terminals/{terminalId}/sync`)의 409 `CONCURRENT_REQUEST`를 REST Docs `sync-receive-batch-concurrent-request`로 문서화한다. 이 PR로 단말이 받게 되는 새 결과다.
- 경합 예외 메시지에 `terminalId`와 `clientOrderId`를 넣는다. 응답은 고정 문구이고, 원인 예외는 `logServerErrorDetail=false`라 키 값을 담지 않는다. 그래서 WARN 로그만으로는 어느 주문인지 특정할 수 없었다.
- `createInCallerTransaction`의 KDoc에 새 주문은 저장에서 flush되므로 같은 키 경합이 호출자 커밋이 아니라 저장에서 경합 예외로 난다는 것을 적는다. `OrderInsertStatementCountIntegrationTest`의 "INSERT와 UPDATE는 커밋의 flush에서 나간다" 주석도 새 주문에 맞게 고친다.
- 위 flush 이유의 "잠금 범위는 같다"와 재조회 선택지를 버린 이유를 더 정확하게 고쳤다.
- 현재 상태(볼트)에 남은 낡은 서술 네 곳을 고친다(Codex 지적).
- 반영하지 않은 것: HTTP 두 건을 실제로 겹치는 통합 테스트는 검증 절에 적은 대로 인수 조건 1과 3이 이어서 덮으므로 더하지 않았다. `entity-map.md`에서 재결제를 "HTTP endpoint 없음"이라고 적은 낡은 서술은 이 PR과 무관해서 고치지 않았다.

## 제외
- 커밋 시점 버전 충돌. #34로 해결됐다. 버전 충돌을 HTTP까지 잇는 테스트는 #34가 정한 대로 잠금 없이 `@Version`에 기대는 endpoint를 처음 더하는 PR이 넣는다.
- 리스너 경로의 unique 위반(`createAttempt`의 `Lock` fail-open, fulfillment). HTTP 응답이 없고 Kafka 재시도가 받는다.
- 이미 처리된 제약(`ux_owner_email`, `ux_terminal_store_code`, watermark PK)과 행 잠금으로 닿지 않는 제약.
- 늦은 요청이 앞 결과를 바로 받게 하는 재조회와 `Retry-After`. 위 선택지와 [checkout API spec](2026-09-21-order-request-checkout-api.md)의 제외를 따른다.
- 제약 이름을 바꾸는 마이그레이션. 다음에 `t_order`의 unique를 건드리는 PR이 이름(`ux_order_terminal_client_order` 등)을 붙이고 이 상수와 테스트를 함께 바꾼다.
- 주문을 지우는 명령. unique는 soft delete된 행에도 걸리는데 `findByClientOrderId`는 그 행을 보지 못한다. 그래서 지운 주문의 키로 다시 만들면 매번 409 `CONCURRENT_REQUEST`가 된다(HTTP는 끝없이 재시도하고 sync는 큐가 멈춘다). 지우는 명령을 더하는 PR이 이 매핑을 다시 본다.

## 관련 문서
없음.

## 변경 경로
- src/main/kotlin/com/resilientpos/order/internal/adapter/persistence/command/OrderPersistenceAdapter.kt
- src/main/kotlin/com/resilientpos/order/OrderCommandFacade.kt
- src/main/kotlin/com/resilientpos/platform/ApiControllerAdvice.kt
- src/main/kotlin/com/resilientpos/platform/UniqueViolation.kt
- src/main/kotlin/com/resilientpos/owner/internal/adapter/persistence/command/OwnerPersistenceAdapter.kt
- src/main/kotlin/com/resilientpos/terminal/internal/adapter/persistence/command/TerminalPersistenceAdapter.kt
- src/test/kotlin/com/resilientpos/order/**
- src/test/kotlin/com/resilientpos/sync/**
- src/test/kotlin/com/resilientpos/platform/**
- docs/wiki/entity-map.md
- docs/spec/2026-10-20-contention-error-mapping.md

## 인수 조건
1. 같은 `(terminal_id, client_order_id)`의 주문이 커밋돼 있을 때 같은 키의 다른 주문을 저장하면 `ConcurrencyFailureException`이 나고, 그 원인 사슬에 `t_order_terminal_id_client_order_id_key`가 있으며, 주문 행은 하나다.
2. 같은 키의 주문 생성이 커밋되기 전에 sync `CREATE_ORDER`가 오면 배치는 `ConcurrencyFailureException`으로 끝난다. 그 항목의 접수 기록은 없고 watermark는 전진하지 않는다. 배치의 예외는 원인 사슬이 그 제약의 unique 위반이다. 앞 생성이 커밋된 뒤 같은 배치를 다시 보내면 그 항목은 `APPLIED`이고 앞 주문의 `orderId`를 받으며, 접수 기록은 1행, watermark는 1이 된다.
3. `POST /v1/orders`에서 Facade가 `ConcurrencyFailureException`을 던지면 `409`, `errorCode=CONCURRENT_REQUEST`, 고정 문구다. REST Docs 스니펫 `orders-create-concurrent-request`가 생긴다. `POST /v1/terminals/{terminalId}/sync`에서 Facade가 같은 예외를 던져도 같은 409이고 스니펫 `sync-receive-batch-concurrent-request`가 생긴다.
4. `platform`의 판별 함수는 SQLState `23505`이고 메시지에 그 제약 이름이 식별자 경계로 있을 때만 참이다. 이름을 감싼 따옴표의 형식(큰따옴표, `»…«`, `« … »`)과는 관계없다. 다른 제약 이름, 앞부분만 같은 다른 제약 이름(`ux_ab`인데 `ux_a`로 물을 때), 다른 SQLState, 원인 사슬에 `SQLException`이 없는 경우는 거짓이다.
5. owner·terminal의 기존 중복 테스트(`409 OWNER_EMAIL_DUPLICATED`, `409 TERMINAL_CODE_DUPLICATED`)가 판별 함수를 옮긴 뒤에도 그대로 통과한다.
6. `./gradlew test` 전체와 `python3 scripts/check_pr.py --base origin/main`이 통과한다.

## 검증
- `./gradlew test --tests 'com.resilientpos.order.*' --tests 'com.resilientpos.sync.*' --tests 'com.resilientpos.platform.*' --tests 'com.resilientpos.owner.*' --tests 'com.resilientpos.terminal.*'`
- `./gradlew test` 전체. Testcontainers의 PostgreSQL·Redis·Kafka를 쓴다(Docker는 OrbStack).
- `python3 scripts/check_pr.py --base origin/main --worktree`(개발 중), `--worktree` 없이 한 번 더(제출 전).
- 정상: 같은 키의 주문 생성이 겹치면 늦은 쪽은 409이고 행은 하나다. 다시 보내면 앞 주문을 받는다.
- 실패: 다른 제약 위반은 이전처럼 `DataIntegrityViolationException` 계열(500)이다. 판별 함수가 제약 이름을 가리지 않게 고치면 인수 조건 4가 실패하는지, adapter의 변환을 지우면 인수 조건 1·2가 실패하는지 본다.
- 경로 (1)(HTTP 두 건, `Lock` fail-open)은 인수 조건 1(adapter 변환)과 3(HTTP 매핑)을 이어서 덮는다. 두 요청이 같은 adapter 코드를 지나므로 따로 경합 테스트를 두지 않는다.

## Wiki 영향
- [엔티티 지도](../wiki/entity-map.md): `t_order` 방어선 줄에 경합 위반이 409 `CONCURRENT_REQUEST`가 된다는 것을 적는다. 4.1 흐름 표의 주문 생성 줄과 4.2의 "배치 전체가 409·500이면" 줄을 같은 키 생성 경합은 409로 고친다. 4.2의 그 줄에는 실패한 항목이 재전송 때 `APPLIED`라는 것을, 4.5 멱등 표의 주문 생성 줄에는 겹치면 409라는 것을 적는다.
- 현재 상태(`docs/wiki/current-state.md`, 볼트)는 볼트에서 갱신한다. 주문 생성의 락이 통하지 않을 때 "오류를 받는다", 주문 API의 "같은 키의 동시 요청이 unique 제약 위반으로 끝나는 것은 알려진 빈틈이다", sync 배치 오류의 409/500 구분, `CONCURRENT_REQUEST` 설명의 "unique 제약 위반은 계속 500이다" 네 곳을 고친다.
- [도메인 모델](../wiki/domain-model.md)은 이 동작을 서술하지 않아 바꾸지 않는다. Kafka·Modulith 작업이 아니라 Modulith·Kafka 키워드(볼트)도 바꾸지 않는다.
- 요구사항 변화: 없다. 같은 키의 겹친 생성은 여전히 늦은 쪽이 실패하고 정합성은 unique가 지킨다. 실패의 HTTP 표현만 재시도할 수 있는 409가 된다.

## 분할 계획
개발 컨텍스트는 하나다. 같은 키 주문 생성 경합의 HTTP 표현이고, 판별 함수 이동은 세 번째 사용처가 생겨서 딸려 온다. 버전 충돌은 #34로 끝났고 리스너 경로는 HTTP 500이 아니라서 뺐다.
겹침 확인(2026-10-20, origin/main 9c34aaf): 열린 PR은 #94(draft, `build.gradle.kts`·`EventPublicationResubmitter`·`docs/wiki/domain-model.md`)뿐이고 이 PR의 경로와 겹치지 않는다. 판별 방식을 고칠 때 `build.gradle.kts`를 바꾸는 안은 #94와 겹쳐서 버렸다(범위 조정). 동시에 진행 중인 `feature/payment-reconciliation-case`(#96)와 `test/event-contract-compatibility`(#78)는 아직 커밋이 없다. 이 PR은 `payment`·마이그레이션·이벤트 코드를 건드리지 않는다. 판별 함수는 main에 owner·terminal adapter의 private 사본 둘만 있고 열린 PR에는 없다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 같은 키의 주문 저장은 경합 예외다 (인수 1) `OrderCommandFacadeIntegrationTest`의 `같은 terminal_id와 client_order_id의 다른 주문 저장은 경합으로 거절되고 행은 하나다`(기존 `같은 terminal_id와 client_order_id는 DB unique가 거절한다`의 기대를 바꿨다. 원인 사슬의 제약 이름과 행 수 단언을 더했다) | `./gradlew test --tests 'com.resilientpos.order.internal.application.OrderCommandFacadeIntegrationTest'` — 바꾼 1개가 assertion 실패. 기대한 `ConcurrencyFailureException` 대신 커밋에서 `DataIntegrityViolationException`(원인 `ConstraintViolationException`, `PSQLException`)이 나왔다 | `OrderPersistenceAdapter.save`가 새 주문일 때만 flush하고 그 제약의 unique 위반을 `ConcurrencyFailureException`으로 바꾼 뒤 같은 명령, 11개 통과 | 아래 판별 함수 이동 뒤 회귀에 포함 |
| sync 생성이 커밋 전 같은 키 생성과 겹치면 배치가 경합 예외로 끝나고 재전송은 APPLIED다 (인수 2) `SyncCreateOrderRaceIntegrationTest`의 `커밋 전인 같은 키 생성과 겹친 sync 생성은 경합 예외로 끝나 기록을 남기지 않고 재전송하면 앞 주문으로 APPLIED다`(앞 생성을 커밋 전에 붙들고 sync INSERT의 잠금 대기를 `pg_stat_activity`로 확인한 뒤 커밋한다) | 해당 없음. 앞 동작의 구현 뒤에 쓴 characterization이라 처음부터 통과했다. 대신 동작을 지워 확인했다. 변환을 `DomainException(kind = CONFLICT)`으로 바꾸면 `./gradlew test --tests 'com.resilientpos.sync.internal.application.SyncCreateOrderRaceIntegrationTest'`이 실패한다. 배치가 예외 없이 끝나고 그 항목이 `REJECTED`, `lastAckedSequence=1`로 확정된다(합의한 결정의 근거). 새 주문의 flush를 지우면 sync가 잠금을 기다리지 않아 대기 단계에서 실패한다. 둘 다 원복했다 | 같은 명령, 1개 통과 | `./gradlew test --tests 'com.resilientpos.sync.*' --tests 'com.resilientpos.order.*'` 193개 통과 |
| 주문 생성의 경합 예외는 409 CONCURRENT_REQUEST다 (인수 3) `OrderControllerTest`의 `Facade가 경합 예외를 던지면 409 CONCURRENT_REQUEST이고 예외 메시지를 담지 않는다`와 REST Docs `orders-create-concurrent-request` | 해당 없음. `ConcurrencyFailureException` 계열의 409 매핑이 #33부터 있어서 처음부터 통과했다(characterization). 대신 `ApiControllerAdvice`의 그 `@ExceptionHandler`를 지우면 `./gradlew test --tests 'com.resilientpos.order.internal.adapter.web.OrderControllerTest'`에서 이 1개가 상태 코드 409 기대 불일치로 실패하는 것을 확인하고 원복했다 | 같은 명령, 18개 통과. 스니펫 디렉터리 생성 확인 | `./gradlew test --tests 'com.resilientpos.order.internal.adapter.web.*' --tests 'com.resilientpos.platform.ApiAdviceTest'` 통과 |
| 판별 함수는 SQLState와 제약 이름이 모두 맞을 때만 참이다 (인수 4·5) `UniqueViolationTest` 5개(23505와 따옴표째 제약 이름이면 참 / 다른 제약 이름 거짓 / 다른 SQLState 거짓 / 원인 사슬에 SQLException 없으면 거짓 / 깊은 원인 사슬에서도 찾는다) | 해당 없음. owner·terminal adapter의 private 사본을 `platform`으로 옮긴 함수의 characterization이라 `./gradlew test --tests 'com.resilientpos.platform.UniqueViolationTest'`가 처음부터 5개 통과했다. 대신 제약 이름 비교 조건을 지우면 `다른 제약 이름이면 거짓이다` 1개가 실패하는 것을 확인하고 원복했다 | 같은 명령, 5개 통과 | `./gradlew test --tests 'com.resilientpos.order.*' --tests 'com.resilientpos.platform.*' --tests 'com.resilientpos.owner.*' --tests 'com.resilientpos.terminal.*' --tests 'com.resilientpos.sync.*'`와 구조 테스트 4종(ModuleStructure·EntityVisibility·TransactionDeclaration·DependencyDirection) 415개 통과. owner·terminal의 기존 중복 테스트 포함(인수 5) |
| 리뷰 반영, 판별 함수는 따옴표 형식과 관계없이 식별자 경계로 제약 이름을 찾는다 (인수 4) `UniqueViolationTest`의 `독일어 번역 메시지의 »« 따옴표 안 제약 이름도 찾는다`, `프랑스어 번역 메시지의 « » 따옴표 안 제약 이름도 찾는다`, `앞부분만 같은 다른 제약 이름이면 거짓이다` | `./gradlew test --tests 'com.resilientpos.platform.UniqueViolationTest'` — 8개 중 2개(독일어·프랑스어)가 assertion 실패. ASCII 큰따옴표로 감싼 이름만 찾았다. `ux_ab` 사례는 따옴표가 경계 역할을 해서 통과했다(characterization) | 식별자 경계 정규식으로 바꾼 뒤 같은 명령, 8개 통과 | `./gradlew test --tests 'com.resilientpos.platform.*' --tests 'com.resilientpos.owner.*' --tests 'com.resilientpos.terminal.*' --tests 'com.resilientpos.order.internal.application.OrderCommandFacadeIntegrationTest'` 통과 |
| 리뷰 반영, sync 경합 테스트는 앞 트랜잭션에 막힌 세션만 기다리고, 원인이 그 제약의 unique 위반이며, 재전송 뒤 접수 기록 1행·watermark 1이다 (인수 2) | 해당 없음. 테스트를 좁힌 것이라 구현 변경이 없다 | `./gradlew test --tests 'com.resilientpos.sync.internal.application.SyncCreateOrderRaceIntegrationTest'` 1개 통과. 앞 트랜잭션의 `pg_backend_pid()`에 막힌 세션이 잡혀서, 테스트의 `JdbcTemplate`이 앞 트랜잭션과 같은 연결을 쓴다는 것도 확인됐다 | 아래 결과 절의 전체 실행에 포함 |
| 리뷰 반영, sync 배치의 경합 예외는 409 CONCURRENT_REQUEST다 (인수 3) `SyncControllerTest`의 `Facade가 경합 예외를 던지면 409 CONCURRENT_REQUEST이고 예외 메시지를 담지 않는다`와 REST Docs `sync-receive-batch-concurrent-request` | 해당 없음. 주문 생성과 같은 `ApiControllerAdvice` handler라 처음부터 통과했다(characterization). handler를 지웠을 때의 실패는 주문 생성 쪽에서 확인했다 | `./gradlew test --tests 'com.resilientpos.sync.internal.adapter.web.SyncControllerTest'` 10개 통과, 스니펫 디렉터리 생성 확인 | 아래 결과 절의 전체 실행에 포함 |

## 결과
- 인수 조건 1~6을 충족했다. 리뷰 반영 뒤 `./gradlew test`는 601개 중 실패 0이다(신규 11개: `UniqueViolationTest` 8, `SyncCreateOrderRaceIntegrationTest` 1, `OrderControllerTest` 1, `SyncControllerTest` 1. 기존 unique 테스트 1개는 기대를 바꿨다). REST Docs 스니펫 `orders-create-concurrent-request`와 `sync-receive-batch-concurrent-request`가 `build/generated-snippets`에 생겼다.
- 구현: `OrderPersistenceAdapter.save`의 새 주문 flush와 제약 이름 변환(예외 메시지에 `terminalId`·`clientOrderId`), `platform`의 `Throwable.isUniqueViolationOf`(owner·terminal adapter의 사본 둘을 대체하고 식별자 경계로 판별), `OrderCommandFacade.create`·`createInCallerTransaction`·`ApiControllerAdvice.handleConcurrency`의 KDoc이다. `ApiControllerAdvice`의 동작과 `ErrorType`은 바뀌지 않았다.
- 합의한 결정의 근거를 테스트로 확인했다. 변환을 `DomainException(kind = CONFLICT)`으로 바꾸면 sync 배치가 예외 없이 끝나고 그 항목이 `REJECTED`, `lastAckedSequence=1`로 확정된다(`SyncCreateOrderRaceIntegrationTest` 실패).
- 이슈 #79의 첫 항목(커밋 시점 버전 충돌)은 #34로 이미 해결돼 코드를 바꾸지 않았다. 근거는 범위 절에 적었다.
- 동작 하나씩 `tdd-driver`에 맡겼다(판별 함수 이동과 adapter 변환, sync 경합, 주문 생성 Controller, 판별 함수의 식별자 경계). 주 에이전트가 diff를 검토했고, sync 테스트의 `!!`을 `checkNotNull`로 바꿨으며, `DomainException` 대조 확인을 직접 돌렸다. 리뷰 반영 중 테스트 강화, sync Controller 문서화, 예외 메시지와 KDoc·주석은 주 에이전트가 직접 했다.
- multi-review: Claude 87점, Codex 77점, Gemini 미실행(CLI 없음). 막는 결함은 없었고 반영한 것과 하지 않은 것은 범위 조정 절에 적었다.
