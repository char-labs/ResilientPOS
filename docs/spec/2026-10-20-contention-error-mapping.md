# PR spec: 같은 키 주문 생성 경합의 unique 위반을 409로 바꾼다

## 목적
같은 `(terminalId, clientOrderId)`의 주문 생성이 겹치면 늦은 요청이 `t_order`의 unique 위반(`DataIntegrityViolationException`)으로 실패하고 HTTP에서 `500 INTERNAL_ERROR`가 된다. 같은 요청이면 다시 보낼 때 앞 요청이 만든 주문을 받는 경합인데, 단말은 서버 오류와 구분할 수 없다. 이 위반을 기존 경합 응답 `409 CONCURRENT_REQUEST`로 바꾼다(#79). 그러면서 unique 위반을 번역하는 자리를 정한다. 저장 메서드는 저장만 하고, 커밋을 소유한 Facade가 번역한다.

## 범위
- 소유 모듈: `order`(주문 생성 Facade가 위반을 번역한다), `owner`·`terminal`(같은 규칙으로 번역 자리를 Facade로 옮긴다), `platform`(제약 이름 판별 함수를 한 곳에 둔다). Command·Query·Event는 바뀌지 않는다. 스키마는 V016 하나(credential 제약의 검사 시점)만 바뀐다. 새 오류 code와 `ErrorType` 항목은 없다.
- 2026-10-20 main(9c34aaf)에서 경합으로 500이 나는 지점을 다시 찾았다.
  - 커밋 시점 버전 충돌(`JpaSystemException`)은 이미 고쳐져 있다. #34가 `BaseEntity`의 두 시각을 `SourceType.VM`으로 바꿔 `ObjectOptimisticLockingFailureException`으로 나오고, #33의 handler가 `409 CONCURRENT_REQUEST`로 바꾼다([commit-version-conflict-optimistic-lock](2026-09-21-commit-version-conflict-optimistic-lock.md) "범위 조정 2", `OrderVersionConflictIntegrationTest`). 이슈의 근거인 [checkout API spec](2026-09-21-order-request-checkout-api.md)의 제외 절은 #34 전에 쓰였다. main에 `SourceType.DB`·`@Generated`는 없고 `@Version`은 `OrderEntity`에만 있으며 주문을 바꾸는 경로는 모두 행을 잠근다. 이 항목은 코드를 바꾸지 않는다.
  - unique 제약은 하나만 HTTP 500에 닿는다. `t_order (terminal_id, client_order_id)`(이름 없이 선언해 PostgreSQL 이름은 `t_order_terminal_id_client_order_id_key`)다. 닿는 길은 둘이다. (1) `POST /v1/orders` 두 건이 겹치고 `Lock`이 fail-open일 때(Redis 장애, 대기 3초 초과). (2) `POST /v1/orders`와 sync `CREATE_ORDER`가 같은 `clientOrderId`로 겹칠 때. 둘은 줄 세우는 수단(Redis `Lock`, watermark 행 잠금)이 달라서 Redis가 정상이어도 생긴다. (2)에서 sync가 지면 배치 전체가 500이다.
  - 나머지 unique는 HTTP 500에 닿지 않는다. `t_order_checkout`·`ux_sync_operation_*`·`ux_terminal_credential_active`는 행 잠금과 사전 조회가 막고, `ux_terminal_credential_token_hash`는 무작위 토큰이다. `ux_payment_attempt_*`는 재결제 endpoint에서는 대상 행 잠금과 최신 시도 확인이 막고, 리스너의 `createAttempt`에서는 닿지만 Kafka 재시도가 받는다. `ux_fulfillment_order`도 리스너에서만 닿는다. `ux_owner_email`과 `ux_terminal_store_code`는 이미 `409 OWNER_EMAIL_DUPLICATED`·`409 TERMINAL_CODE_DUPLICATED`이고, `t_sync_watermark` PK 위반은 sync가 삼킨다.
- 변환: `OrderCommandFacade.create`가 `Lock.executeWithLock` 블록을 감싼다. 블록의 커밋에서 난 `DataIntegrityViolationException`이 SQLState `23505`이고 제약 이름이 `t_order_terminal_id_client_order_id_key`이면, 원인 예외를 담고 메시지에 `terminalId`·`clientOrderId`를 넣은 Spring `ConcurrencyFailureException`을 던진다. 그 밖의 예외는 그대로 던진다. 기존 `ApiControllerAdvice.handleConcurrency`가 이 계열을 `409 CONCURRENT_REQUEST`(WARN, 고정 문구)로 바꾼다. advice는 KDoc만 고친다.
  - 덮는 범위: HTTP 요청이 늦은 쪽이면 409다. HTTP끼리 겹친 경우(1)와 sync에게 진 HTTP(2)가 여기 든다. sync 항목이 늦은 쪽이면 커밋이 sync 항목 트랜잭션이라 `order`가 가로채지 않고, 배치는 main과 같이 500이다(제외 참고).
  - `OrderPersistenceAdapter`는 main 그대로다. 저장 메서드에서 flush하지 않으므로 새 주문의 INSERT는 지금처럼 커밋에서 나간다.
- 번역 자리 규칙: persistence adapter의 저장 메서드는 변경을 영속성 컨텍스트에 넘기는 데서 끝난다. 제약 위반을 가리려고 flush하거나 번역하지 않는다. 위반은 커밋에서 드러나고, 그 커밋을 소유한 Facade(`Tx`·`Lock` 블록을 연 쪽)가 블록 밖에서 제약 이름으로 가려 바꾼다. 업무 규칙이 막은 중복(같은 이메일, 같은 매장의 단말 코드)은 도메인 예외, 같은 멱등 키의 요청이 겹친 경합은 경합 예외다.
  - 점주 이메일(`OwnerManagement.signUp`)과 단말 코드(`TerminalManagement.register`)도 이 규칙으로 옮긴다. 응답(`409 OWNER_EMAIL_DUPLICATED`, `409 TERMINAL_CODE_DUPLICATED`)은 그대로다. 두 어댑터의 `save`에서 flush와 번역이 빠진다.
  - credential 회전이 SQL 순서를 맞추려고 쓰던 flush도 없앤다. 제약을 커밋 때 검사하게 바꾼다(범위 조정 5, V016).
  - 블록 밖에서 잡는 이유: `Tx.masterTx`와 `Lock` 블록의 커밋은 블록이 끝날 때 일어난다. 세 Facade 메서드는 바깥 트랜잭션 없이 불린다(`signUp`·`register`의 `Tx.masterTx`는 `REQUIRED`라 바깥이 있으면 커밋이 바깥으로 간다. `create`는 `Lock`이 새 트랜잭션을 열어 블록에서 커밋한다). 호출자 트랜잭션에 합류하는 `createInCallerTransaction`은 번역하지 않는다. 이 계약은 각 KDoc에 적는다.
  - 구조 테스트(ArchUnit)가 `..adapter.persistence..` 패키지의 판별 함수(`isUniqueViolationOf`) 호출과 운영 코드 전체의 flush(`EntityManager`·Spring Data repository·`TransactionStatus`의 `flush`·`saveAndFlush`·`saveAllAndFlush`) 호출을 막고, JPA·Hibernate 쿼리 생성은 `@Repository`인 `JdslQueryExecutor`만 하게 한다(범위 조정 6·7·8). 결정은 [도메인 모델](../wiki/domain-model.md) §4.2에, 코드 형태는 볼트 rules `domain-modeling-kotlin.md`(§5·§7)에 적고, `effective-java-for-kotlin.md`의 Item 73 줄에 DB 제약 위반은 Facade가 번역한다는 예외를 단다.
- 제약 이름 판별 함수 `isUniqueViolationOf`를 `platform`의 최상위 확장 함수 하나로 옮긴다. main에는 `OwnerPersistenceAdapter`와 `TerminalPersistenceAdapter`에 같은 private 사본이 있었다. 사용처는 세 Facade다. 판별 방식은 아래 범위 조정에서 바꿨다.
- 실패·재시도·복구: 늦은 요청의 트랜잭션은 롤백되고 아무것도 남지 않는다. `POST /v1/orders`는 `clientOrderId`로 멱등이라 단말이 다시 보내면 먼저 저장된 주문과 내용이 같을 때 그 주문을 받고, 다르면 `409 IDEMPOTENCY_CONFLICT`를 받는다. sync 항목이 늦은 쪽이면 배치가 500이고 그 항목은 기록되지 않는다. 재전송하면 다시 처리되어 먼저 저장된 주문과 내용이 같을 때 `APPLIED`, 다르면 `REJECTED`(`IDEMPOTENCY_CONFLICT`)다.
- 관측성: HTTP 경로는 ERROR 로그의 `INTERNAL_ERROR`에서 WARN 로그의 `errorCode=CONCURRENT_REQUEST`로 옮겨 가고 5xx 지표에서 빠진다. 경합 예외 메시지의 주문 키로 대상을 찾는다. sync 경로는 그대로다. 더하는 로그와 metric은 없다.

### 합의한 결정 (2026-10-20 grill)
- 범위는 `t_order (terminal_id, client_order_id)` 하나다. 위에서 HTTP 500에 닿지 않는다고 확인한 제약과 버전 충돌은 다루지 않는다.
- 위반은 `DomainException`이 아니라 경합 예외(`ConcurrencyFailureException`)로 바꾼다. 이 위반은 도메인 규칙 위반이 아니다. 멱등 생성 규칙은 앞 요청이 지켰고, 같은 요청이 겹쳤다는 경합을 뜻한다. 기존 경합 응답이라 새 오류 code가 없고 단말의 재시도 방식(같은 요청을 다시 보낸다)이 그대로다. 대가: 저장소 코드가 Spring DAO 예외를 직접 던지는 첫 사례다.
- 판별 함수는 `platform`으로 옮긴다. 사본 세 벌 대신 한 벌이다.
- 번역은 커밋을 소유한 Facade가 하고 저장 메서드는 저장만 한다(2026-10-20 사용자 결정, 범위 조정 4). 대가: sync 항목이 늦은 쪽인 경합은 500으로 남는다. 점주·단말도 같은 규칙으로 옮기고 구조 테스트와 rules로 앞으로도 지키게 한다.

### 검토한 선택지
- `DomainException(kind = CONFLICT)`와 새 code(점주 이메일·단말 코드와 같은 형태): 이 위반은 경합이라 버렸다. 새 code라서 PR Risk도 🔴이 된다.
- 어댑터 `save`가 새 주문일 때만 flush하고 그 자리에서 번역하기(처음 구현한 안): 어댑터 한 곳이 HTTP와 sync를 함께 덮는다. 그 대신 `save`가 저장 말고도 즉시 전송과 번역을 하고, flush가 영속성 컨텍스트 전체를 내보내며, 호출부에서는 즉시 전송인지 보이지 않는다. 사용자 결정으로 버렸다.
- port에 flush 메서드를 두고 Facade가 `save` 뒤에 부르기: 호출부에서 전송 시점이 보이지만 도메인 port에 영속 개념이 들어가서 버렸다.
- 늦은 요청이 새 트랜잭션으로 다시 조회해 앞 결과를 201로 받기: HTTP에는 가장 매끄럽다. 그 대신 Facade에 재시도 블록이 생긴다. sync는 위반을 예외로 감지하는 순간 호출자 트랜잭션이 중단되므로 이 방식을 쓸 수 없다. 중단 없이 하려면 `INSERT ... ON CONFLICT DO NOTHING` 뒤에 다시 조회해야 하는데, HQL이든 native든 엔티티 저장 경로를 거치지 않는 삽입이라 adapter의 저장 규칙(`from`·`apply`, 자식 행)을 우회한다. 단말은 409를 받고 다시 보내면 같은 결과를 얻으므로 버렸다.
- `ApiControllerAdvice`에서 `DataIntegrityViolationException`의 제약 이름을 보고 409로 바꾸기: `platform`이 `order`의 제약 이름을 알게 되어 버렸다.
- 제약에 `ux_order_terminal_client_order` 같은 이름을 붙이는 마이그레이션: 동작은 같고 마이그레이션 번호가 동시에 진행 중인 다른 작업과 겹칠 수 있다. PostgreSQL이 정한 이름을 상수로 쓰고 통합 테스트가 그 이름을 고정한다.

### 범위 조정 (2026-10-20, 제출 전 multi-review 반영)
`OrderPersistenceAdapter` 등 `@Transactional`이 있는 파일이 바뀌어 PR 템플릿 기준 🔴이다. multi-review(Claude 87점, Codex 77점. Gemini는 CLI가 없어 미실행)를 돌렸고 막는 결함은 없었다. 반영한 것은 다음과 같다.
- 판별 함수가 서버 `lc_messages`에 기대고 있었다(두 리뷰어 모두 지적). 메시지에서 ASCII 큰따옴표로 감싼 이름을 찾아서, 제약 이름을 `»…«`(독일어)나 `« … »`(프랑스어)로 감싸는 언어로 서버를 돌리면 owner·terminal·order 세 곳이 모두 500으로 돌아간다. 옮기기 전부터 있던 결함이다. 따옴표 대신 식별자 경계(앞뒤가 영문자·숫자·`_`가 아님)로 제약 이름을 찾게 바꾼다. 번역 메시지에도 제약 이름은 그대로 실리므로 언어와 관계없이 맞는다. pgjdbc `PSQLException.serverErrorMessage.constraint`(번역되지 않는 필드)가 더 정확하지만, pgjdbc를 `runtimeOnly`에서 컴파일 의존성으로 올려야 하고 그 `build.gradle.kts`를 열린 PR #94도 바꾸고 있어서 버렸다. 메시지에서 제약 이름을 빼는 번역이 생기면 그 필드로 바꾼다.
- sync 경합 테스트의 대기 조건을 좁힌다. 클러스터의 잠금 대기 세션을 모두 세지 않고, 앞 트랜잭션의 backend pid에 막힌 세션만 센다(`pg_blocking_pids`). 예외는 타입만 보지 않고 원인 사슬이 그 제약의 unique 위반인지까지 보며, 재전송 뒤 접수 기록 1행과 watermark 1을 확인한다.
- sync API(`POST /v1/terminals/{terminalId}/sync`)의 409 `CONCURRENT_REQUEST`를 REST Docs `sync-receive-batch-concurrent-request`로 문서화한다. 이 PR로 단말이 받게 되는 새 결과다.
- 경합 예외 메시지에 `terminalId`와 `clientOrderId`를 넣는다. 응답은 고정 문구이고, 원인 예외는 `logServerErrorDetail=false`라 키 값을 담지 않는다. 그래서 WARN 로그만으로는 어느 주문인지 특정할 수 없었다.
- `createInCallerTransaction`의 KDoc에 새 주문은 저장에서 flush되므로 같은 키 경합이 호출자 커밋이 아니라 저장에서 경합 예외로 난다는 것을 적는다. `OrderInsertStatementCountIntegrationTest`의 "INSERT와 UPDATE는 커밋의 flush에서 나간다" 주석도 새 주문에 맞게 고친다.
- 현재 상태(볼트)에 남은 낡은 서술 네 곳을 고친다(Codex 지적).
- 반영하지 않은 것: HTTP 두 건을 실제로 겹치는 통합 테스트는 검증 절에 적은 대로 인수 조건 1과 3이 이어서 덮으므로 더하지 않았다. `entity-map.md`에서 재결제를 "HTTP endpoint 없음"이라고 적은 낡은 서술은 이 PR과 무관해서 고치지 않았다. `OrderRepository.save`의 port KDoc은 바꾸지 않았다. 경합 예외는 Spring 타입이라 도메인 port에 적지 않고, 호출자가 읽는 Facade KDoc에 둔다.

### 범위 조정 2 (2026-10-20, 반영 커밋 재리뷰)
반영 커밋(`dd6a97d..HEAD`)만 다시 multi-review했다. Claude 92점, Codex 88점(CLI 직접 실행), Gemini는 CLI가 없어 미실행이다. 막는 결함은 없었다. 반영한 것은 다음과 같다.
- 재전송 결과를 조건 없이 "앞 주문으로 `APPLIED`"라고 적었다(두 리뷰어 모두 지적). 앞 주문과 내용이 같을 때만 그 주문으로 `APPLIED`이고, 다르면 `verifySamePayload`가 `IDEMPOTENCY_CONFLICT`로 거절해 `REJECTED`로 순번이 확정된다. 앞서 확정된 항목(`APPLIED`·`REJECTED`)은 `DUPLICATE`이고 `CONFLICT`였던 항목은 다시 `CONFLICT`다. REST Docs 설명, 이 spec, `entity-map.md`, 현재 상태(볼트)를 이렇게 고친다.
- 판별 함수의 경계 문자를 PostgreSQL의 따옴표 없는 식별자에 쓰는 문자(유니코드 글자·숫자·`_`·`$`)로 넓힌다(두 리뷰어 모두 지적). ASCII만 보면 `ux_a$1` 같은 이름에서 `ux_a`가 참이 된다. 넓혀도 번역 메시지는 깨지지 않는다. PostgreSQL 17·18의 `src/backend/po` 번역 15개를 2026-10-20에 확인했고, 모두 제약 이름 자리를 남기며 그 앞뒤가 따옴표나 공백이다. KDoc에 이 근거와 함께, `logServerErrorDetail=false`로 메시지에 다른 식별자(DETAIL의 키 값)가 실리지 않는다는 전제를 적는다. 앞쪽 경계(`pk_ux_a`)와 `$` 사례를 테스트에 더한다.
- sync 경합 테스트의 대기 조회를 `pg_stat_activity`에서 `pg_locks`로 바꾼다. 테스트 트랜잭션 안에서 읽는 `pg_stat_activity`는 처음 읽은 시점의 세션 목록에 고정돼서, 그 뒤 새로 열린 연결이 sync를 맡으면 대기를 보지 못한다. 원인 단언은 검증 대상인 판별 함수를 쓰지 않고 SQLState와 메시지로 직접 본다. `JdbcTemplate`이 앞 트랜잭션과 같은 연결을 쓴다는 전제를 주석으로 적는다.
- 한계 표식을 저장소 규약인 `ponytail:`로 맞춘다. `create`의 KDoc에 sync 항목과 겹치는 경로를 적는다. 예외 메시지에 주문 키가 실리는지 통합 테스트에서 단언한다. 판정과 무관해진 테스트 이름("따옴표째")을 고친다.
- 테스트 두 곳(`OrderCommandFacadeIntegrationTest`, `SyncCreateOrderRaceIntegrationTest`)은 제약 이름을 상수가 아니라 리터럴로 든다. DB에 실제로 붙은 이름을 고정하려는 것이라 운영 코드의 상수를 공유하지 않는다. 이름을 바꾸면 상수와 두 테스트를 함께 고친다.
- 반영하지 않은 것: `generateSequence`의 원인 순환은 기존 코드이고 현실에서 생기지 않는다. JDBC batch를 켜면 SQL 본문을 담은 `BatchUpdateException`이 원인 사슬에 들어오므로 그때 판별 함수를 다시 본다.

### 범위 조정 3 (2026-10-20, 두 번째 반영 커밋 리뷰)
두 번째 반영 커밋(`45046ea..bf9ad89`)을 다시 multi-review했다. Claude 94점, Codex 87점(CLI 직접 실행), Gemini는 CLI가 없어 미실행이다. 막는 결함은 없었다. 반영한 것은 다음과 같다.
- 재전송 결과를 "같은 내용으로 다시 보내면 같은 주문을 받는다"라고 적은 곳은 늦은 요청을 그대로 다시 보내는 것으로 읽힌다(두 리뷰어 모두 지적). 비교 기준은 먼저 저장된 주문이다. 그래서 "먼저 저장된 주문과 내용이 같으면 그 주문, 다르면 `IDEMPOTENCY_CONFLICT`"로 고친다. 고치는 곳은 `create` KDoc, `entity-map.md`, 현재 상태(볼트), 이 spec이다.
- 판별 함수의 보장 범위를 따옴표 없는 식별자로 좁혀 적는다(두 리뷰어 모두 지적). 따옴표로 감싼 식별자는 `-`·`.`·공백도 담을 수 있어서 메시지에서 경계를 가릴 수 없다. 저장소의 제약 이름은 모두 소문자·숫자·`_`다. 그런 이름이 필요해지면 pgjdbc `ServerErrorMessage.constraint`로 바꾼다. 비ASCII 글자 경계(`ux_a한`) 테스트를 더한다. 경계를 ASCII로 되돌리면 이 테스트가 실패한다.
- 경합 예외 메시지의 `terminalId`도 단언한다(두 리뷰어 모두 지적). 테스트가 제약 이름을 리터럴로 드는 이유를 그 자리에 한 줄 적는다.
- TDD 기록의 낡은 메커니즘과 테스트 이름을 지금 것으로 고친다.

### 범위 조정 4 (2026-10-20, 번역 자리를 Facade로 옮김)
사용자가 "저장 메서드는 저장만 하면 되지 않나, `flushNewOrder`도 굳이 필요한가"를 물었다. 어댑터 안에서 flush해야 했던 이유는 sync 경로까지 어댑터 한 곳에서 덮으려는 것이었다. 그 대가와 선택지(위 검토한 선택지)를 보고 사용자가 정했다. 저장 메서드는 저장만 하고 번역은 커밋을 소유한 Facade가 하며, sync 항목이 늦은 경우는 500으로 둔다. 같은 규칙을 앞으로도 지키도록 점주·단말을 옮기고 구조 테스트와 rules에 넣는다.
- `OrderPersistenceAdapter`를 main으로 되돌린다(`flushNewOrder`와 제약 상수 삭제). 번역은 `OrderCommandFacade.create`가 한다.
- 위 범위 조정 1~3의 항목 가운데 sync 경합 테스트(`SyncCreateOrderRaceIntegrationTest`), sync 409 REST Docs(`sync-receive-batch-concurrent-request`), 어댑터 flush를 전제로 한 `createInCallerTransaction` KDoc과 `OrderInsertStatementCountIntegrationTest` 주석, 저장소 직접 호출로 경합 예외를 확인하던 `OrderCommandFacadeIntegrationTest`의 변경은 없앤다. sync의 동작이 main과 같아졌기 때문이다. 대기 조건(`pg_locks`·`pg_blocking_pids`), 원인 단언(SQLState·메시지), 제약 이름 리터럴은 새 주문 생성 경합 테스트가 이어받는다.
- 합의한 결정의 `DomainException` 기각 근거 가운데 "sync가 항목을 `REJECTED`로 영구 기록한다"는 더는 해당하지 않는다. 번역이 `create`에만 있고 sync는 `createInCallerTransaction`을 부르기 때문이다. 경합이라는 의미와 기존 409 계약만으로 결정을 유지한다.

### 범위 조정 5 (2026-10-20, 남은 flush 제거)
사용자가 운영 코드에 남은 `entityManager.flush()`(`TerminalPersistenceAdapter.update(credential)`)를 이 PR에서 없애기로 정했다. 그 flush는 번역용이 아니라 SQL 순서용이었다. credential 회전은 한 트랜잭션에서 옛 행을 폐기(UPDATE)하고 새 유효 행을 넣는데(INSERT), Hibernate는 한 flush에서 INSERT를 UPDATE보다 먼저 보낸다. 그래서 새 행이 들어가는 순간 옛 행이 아직 유효해 partial unique 인덱스 `ux_terminal_credential_active`에 걸렸다.
- `ux_terminal_credential_active`를 커밋 때 검사하는 exclusion 제약(`EXCLUDE USING btree (terminal_id WITH =) WHERE (revoked_at IS NULL) DEFERRABLE INITIALLY DEFERRED`)으로 바꾼다(V016). 조건(단말당 유효 행 하나)과 이름은 그대로다. PostgreSQL의 unique 제약은 술어(WHERE)를 가질 수 없고 partial unique 인덱스는 DEFERRABLE이 될 수 없어서 exclusion 제약을 쓴다.
- `TerminalPersistenceAdapter.update(credential)`에서 flush를 지운다. 회전은 폐기와 새 발급을 한 커밋에 넣고, 제약은 커밋에서 한 번 검사한다.
- 운영 코드에 flush가 없어지므로 규칙을 "저장 메서드는 flush하지 않는다"로 줄이고 구조 테스트가 `..adapter.persistence..`의 `EntityManager.flush` 호출도 막는다. 한 커밋 안의 변경 순서가 제약에 걸리면 flush로 순서를 맞추지 않고 그 제약을 커밋 때 검사하게 한다(볼트 rules, 도메인 모델 wiki).
- 버리는 대안: 회전을 두 트랜잭션으로 나누기(유효 credential이 없는 틈이 생기고 원자성이 깨진다), JDSL bulk update로 폐기를 바로 실행하기(rules가 쓰기 경로에서 금지한다), 중간 조회로 auto-flush를 일으키기(flush가 숨을 뿐이다), port에 flush 메서드 두기(도메인 port에 영속 개념이 들어간다).
- 마이그레이션 번호: 기준 브랜치(origin/main b9449c4)의 마지막이 V015라 V016을 쓴다. 열린 #100도 V016·V017을 쓴다. 먼저 머지된 쪽이 번호를 갖고 나중 쪽이 다음 번호로 바꾼다. 번호를 바꾸면 마이그레이션 파일 이름, `SchemaMigrationTest`의 적용 목록(`"016" to true`), `TerminalCredentialSchemaIntegrationTest` KDoc의 V016, 이 spec과 PR 본문의 V016을 함께 고친다. 나중 번호(V018)를 미리 잡으면 이 PR이 먼저 머지된 뒤 #100의 V016·V017이 이미 적용된 V018보다 앞서 Flyway가 기동을 막는다.

### 범위 조정 6 (2026-10-21, PR 전체 4차 multi-review 반영)
범위 조정 4·5 뒤의 PR 전체(`origin/main...c72f9d4`)를 다시 multi-review했다. Claude 93점, Codex 84점(CLI 직접 실행), Gemini는 CLI가 없어 미실행이다. 막는 결함은 없었다. 반영한 것은 다음과 같다.
- flush 구조 테스트를 운영 코드 전체로 넓히고 Spring Data repository의 `flush`·`saveAndFlush`·`saveAllAndFlush`도 막는다(두 리뷰어 모두 지적). `..adapter.persistence..`의 `EntityManager.flush`만 막아서 결과 절의 "운영 코드에 flush가 없다"를 고정하지 못했다. Hibernate `Session`은 `EntityManager`의 하위 타입이라 함께 걸린다. 테스트 코드는 경합을 만들려고 flush하므로 대상이 아니다.
- 블록 안 auto-flush(Codex High): 블록 안의 조회가 auto-flush를 일으켜 위반이 커밋 전에 드러나면 Hibernate 예외가 그대로 나와 Facade의 `catch`를 빗나간다는 지적이다. 운영 코드의 조회는 `@Repository`인 `JdslQueryExecutor`와 Spring Data repository만 거친다(`EntityManager.createQuery`를 부르는 곳은 `JdslQueryExecutor` 하나다). `@Repository`의 예외 번역이 그 위반을 같은 `DataIntegrityViolationException`으로 바꾸고, `EntityManager.find`는 auto-flush하지 않는다. 그래서 `catch`를 넓히지 않고 이 전제를 wiki와 rules에 적는다.
- 커밋 때 검사하는 제약(Claude M1): 리뷰어는 그 위반이 JDBC 커밋에서 나서 `JpaSystemException` 계열로 온다고 추정했다. 실행해 보니 `JpaTransactionManager`의 커밋에서 `DataIntegrityViolationException`으로 온다. 커밋 실패 테스트가 `Exception` 대신 이 타입을 고정한다. 다만 exclusion 위반은 SQLState `23P01`이라 판별 함수(`23505`만 본다)가 거짓을 낸다. 그래서 업무 오류로 번역할 제약은 exclusion 제약으로 두지 않는다는 줄을 wiki와 rules에 더한다.
- 주문 생성 409의 REST Docs 설명이 재전송 결과를 조건 없이 적었다(두 리뷰어 모두 지적, 범위 조정 3에서 REST Docs만 빠졌다). "먼저 저장된 주문과 내용이 같으면 그 주문, 다르면 409 `IDEMPOTENCY_CONFLICT`"로 고친다. 같은 테스트의 가짜 예외 메시지를 실제 형식(`terminalId=… clientOrderId=…`)으로 바꾸고, 응답에 `clientOrderId=`가 없는지 단언한다.
- `application.yaml`의 `logServerErrorDetail` 주석이 판정 자리를 "adapter"로 적었다(두 리뷰어 모두 지적). Facade로 고친다.
- `OwnerManagementTest`에 두 단위 테스트를 더한다(Claude 지적). 같은 이메일로 다시 가입하면 `OWNER_EMAIL_DUPLICATED`이고, 다른 제약의 위반은 그대로 던진다. 지금은 통합 테스트만 409 경로를 덮는다.
- spec 서술을 지금 상태에 맞춘다. 변경 경로에서 되돌린 `OrderPersistenceAdapter.kt`와 `src/test/kotlin/com/resilientpos/sync/**`를 빼고 `application.yaml`을 더한다. 블록 밖에서 잡는 이유의 괄호를 `signUp`·`register`로 한정한다. Wiki 영향과 분할 계획의 절 범위, 열린 PR 겹침을 고친다. 스키마 검토 질문 7에 잠금 대기 동안의 최악 정지를 적는다. V016 번호를 바꿀 때 함께 고칠 곳을 적는다.
- 반영하지 않은 것: Hibernate가 409마다 ERROR 로그(`HHH000346`)를 하나 더 남긴다는 추정(Claude)은 실행해 보니 아니었다. 점주 중복과 주문 경합 모두 ERROR 로그 없이 Hibernate의 WARN 두 줄(SQLState `23505`, 서버 메시지)만 남는다. Facade의 `catch`를 `RuntimeException`으로 넓히자는 제안(Codex)은 위 전제로 대신한다. 도메인 port KDoc(`TerminalRepository`)에 남은 제약 이름과 `SchemaMigrationTest`의 제약 형태 단언은 선택 항목이라 두었다.

### 범위 조정 7 (2026-10-21, 5차 multi-review 반영)
범위 조정 6의 반영 커밋(`c72f9d4..e2a936e`)을 다시 multi-review했다. Claude 93점, Codex 91점(CLI 직접 실행), Gemini는 CLI가 없어 미실행이다. Critical·High는 없었다. 반영한 것은 다음과 같다.
- 블록 안 auto-flush의 번역 전제를 구조 테스트로 고정한다(두 리뷰어 모두 지적). `ConstraintTranslationTest`에 `EntityManager`의 쿼리 생성(`create`로 시작해 `Query`로 끝나는 메서드)은 `JdslQueryExecutor`만 부른다는 규칙을 더한다. `@Component` 어댑터가 `EntityManager`로 직접 쿼리를 만들면 그 auto-flush 위반이 번역되지 않아 409가 500으로 돌아가기 때문이다. `JdslQueryExecutor`의 KDoc에도 Facade 번역이 이 `@Repository` 번역에 기댄다는 것을 적는다.
- flush 규칙의 대상 타입에 `TransactionStatus`를 더한다(두 리뷰어 모두 지적). `JpaTransactionManager`에서 `TransactionStatus.flush()`는 세션을 flush한다.
- wiki §4.2와 rules §5·§7의 서술을 테스트 범위에 맞춘다. 번역을 빗나가는 경우를 "`@Repository`가 아닌 빈에서 `EntityManager`로 쿼리할 때"로, 전제의 대상을 "JPA 조회"로 좁힌다(운영 코드에는 `JdbcTemplate` 조회도 있다). flush 금지는 첫 문장부터 운영 코드 전체·목적 무관으로 적는다. exclusion 규칙은 수단을 막지 않고 "exclusion 위반을 업무 오류로 번역해야 하면 그 PR이 판별 함수가 `23P01`도 보게 바꾼다(그 전에는 500)"로 적는다(Claude 지적).
- 테스트 정리: REST Docs 409 테스트의 비노출 단언을 메시지 형식이 아니라 값(`CLIENT_ORDER_ID`)으로 보고, 가짜 예외에 제약 이름을 담은 원인 예외를 붙여 제약 이름이 응답에 없는지도 본다. `OwnerManagementTest` 새 테스트 이름의 "커밋"을 뺀다(가짜 저장소는 `save`에서 던진다). Spring Data `Repository` import에 별칭을 준다(`@Repository` 애너테이션과 이름이 같다).
- spec 서술: TDD 표에 바뀌기 전 테스트 이름에 안내를 붙이고, Wiki 영향의 "두 문장"을 내용으로 고치고, 로그를 확인한 실제 명령(세 클래스)을 적는다. 스키마 검토 질문 7의 최악 정지에 잠금을 얻은 뒤의 DDL 시간과 재기동 때의 반복을 더한다.
- 반영하지 않은 것: 단말·주문 Facade의 "다른 제약은 그대로 던진다" 분기 단위 테스트(Claude). 점주와 같은 모양이고 주문은 통합 테스트가 번역 분기를 덮는다. Java SAM 메서드 참조로 부르는 flush(`JavaMethodReference`, Claude 확신 낮음)는 운영 코드가 Kotlin이라 두었다.

### 범위 조정 8 (2026-10-21, 6차 Codex 리뷰 반영)
범위 조정 7의 반영 커밋(`e2a936e..f59e216`)을 다시 multi-review했다. Claude 94점, Codex 94점(CLI 직접 실행), Gemini는 CLI가 없어 미실행이다. Critical·High는 없었다. 사용자가 Codex 지적을 반영하고 리뷰는 여기서 마치기로 정했다.
- `JdslQueryExecutor`의 `@Repository`를 구조 테스트로 고정한다(Codex Medium). 위반을 실제로 번역하는 것은 이 애너테이션인데, 범위 조정 7의 규칙은 쿼리를 만드는 자리만 묶어서 애너테이션이 빠져도 통과했다.
- 쿼리 생성 규칙을 Hibernate 고유 API까지 넓힌다(Codex Medium). 대상 타입에 `org.hibernate.query.QueryProducer`(`SharedSessionContract`의 상위 타입)를 더하고, 이름을 `create`나 `getNamed`로 시작해 `Query`나 `Call`로 끝나는 메서드로 넓힌다. 그러면 `createStoredProcedureCall`·`getNamedProcedureCall`·`getNamedQuery`·`getNamedNativeQuery`도 잡힌다. 메서드 목록은 hibernate-core 7.4.5 jar를 `javap`으로 확인했다. wiki·rules·`JdslQueryExecutor` KDoc의 규칙 서술도 이 범위에 맞춘다.
- spec 범위 줄의 flush 대상에 `TransactionStatus`를 더하고, TDD 표의 `OwnerManagementTest` 옛 이름에 안내를 단다(Codex Low).
- 반영하지 않은 것: auto-flush를 실제로 일으켜 `DataIntegrityViolationException`을 단언하는 통합 테스트는 Codex가 선택 항목으로 들었고, 구조 테스트가 쿼리 자리와 `@Repository`를 함께 고정한다. Claude 6차 지적 가운데 Codex와 겹치지 않는 문구 정리와 `TransactionSynchronization`을 거치는 flush는 사용자 결정으로 반영하지 않았다.

## 제외
- 커밋 시점 버전 충돌. #34로 해결됐다. 버전 충돌을 HTTP까지 잇는 테스트는 #34가 정한 대로 잠금 없이 `@Version`에 기대는 endpoint를 처음 더하는 PR이 넣는다.
- sync 항목이 늦은 쪽인 주문 생성 경합. 커밋이 sync 항목 트랜잭션이라 `order`가 번역하지 않고 배치가 500이다. 그 항목은 기록되지 않고 재전송하면 다시 처리된다. 409로 바꾸려면 sync가 `order`의 제약 이름을 알아야 해서 모듈 경계 문제가 된다. 필요해지면 따로 다룬다.
- 리스너 경로의 unique 위반(`createAttempt`의 `Lock` fail-open, fulfillment). HTTP 응답이 없고 Kafka 재시도가 받는다.
- 행 잠금으로 닿지 않는 제약과 watermark PK.
- 늦은 요청이 앞 결과를 바로 받게 하는 재조회와 `Retry-After`. 위 선택지와 [checkout API spec](2026-09-21-order-request-checkout-api.md)의 제외를 따른다.
- 제약 이름을 바꾸는 마이그레이션. 다음에 `t_order`의 unique를 건드리는 PR이 이름(`ux_order_terminal_client_order` 등)을 붙이고 이 상수와 테스트를 함께 바꾼다.
- 주문을 지우는 명령. unique는 soft delete된 행에도 걸리는데 `findByClientOrderId`는 그 행을 보지 못한다. 그래서 지운 주문의 키로 다시 만들면 HTTP는 매번 409 `CONCURRENT_REQUEST`, sync는 매번 배치 500이 된다. 지우는 명령을 더하는 PR이 이 매핑을 다시 본다.

## 관련 문서
없음.

## 변경 경로
- src/main/kotlin/com/resilientpos/order/OrderCommandFacade.kt
- src/main/kotlin/com/resilientpos/owner/OwnerManagement.kt
- src/main/kotlin/com/resilientpos/terminal/TerminalManagement.kt
- src/main/kotlin/com/resilientpos/platform/ApiControllerAdvice.kt
- src/main/kotlin/com/resilientpos/platform/UniqueViolation.kt
- src/main/kotlin/com/resilientpos/platform/JdslQueryExecutor.kt
- src/main/kotlin/com/resilientpos/owner/internal/adapter/persistence/command/OwnerPersistenceAdapter.kt
- src/main/kotlin/com/resilientpos/terminal/internal/adapter/persistence/command/TerminalPersistenceAdapter.kt
- src/main/kotlin/com/resilientpos/terminal/internal/adapter/persistence/command/TerminalCredentialEntity.kt
- src/main/kotlin/com/resilientpos/terminal/internal/domain/TerminalRepository.kt
- src/main/resources/db/migration/*__terminal_credential_active_deferrable.sql
- src/main/resources/application.yaml
- src/test/kotlin/com/resilientpos/order/**
- src/test/kotlin/com/resilientpos/owner/**
- src/test/kotlin/com/resilientpos/terminal/**
- src/test/kotlin/com/resilientpos/platform/**
- src/test/kotlin/com/resilientpos/ConstraintTranslationTest.kt
- src/test/kotlin/com/resilientpos/SchemaMigrationTest.kt
- docs/wiki/entity-map.md
- docs/wiki/domain-model.md
- docs/spec/2026-10-20-contention-error-mapping.md

## 인수 조건
1. 같은 키의 주문이 커밋 전일 때 `OrderCommandFacade.create`가 같은 키·같은 내용으로 겹치면 `ConcurrencyFailureException`으로 끝난다. 원인 사슬의 가장 안쪽은 SQLState `23505`이고 메시지에 `t_order_terminal_id_client_order_id_key`가 있으며, 예외 메시지에 `terminalId`와 `clientOrderId`가 있다. 주문 행은 하나다. 앞 주문이 커밋된 뒤 같은 요청으로 다시 `create`하면 앞 주문의 `orderId`를 받는다.
2. 같은 이메일의 점주 가입과 같은 매장·코드의 단말 등록은 지금처럼 `409 OWNER_EMAIL_DUPLICATED`·`409 TERMINAL_CODE_DUPLICATED`이고, 번역은 `OwnerManagement.signUp`·`TerminalManagement.register`가 한다. 점주 가입에서 다른 제약의 위반은 번역하지 않고 그대로 던진다. persistence adapter는 flush하거나 번역하지 않는다.
3. `POST /v1/orders`에서 Facade가 `ConcurrencyFailureException`을 던지면 `409`, `errorCode=CONCURRENT_REQUEST`, 고정 문구다. REST Docs 스니펫 `orders-create-concurrent-request`가 생긴다.
4. `platform`의 판별 함수는 SQLState `23505`이고 메시지에 그 제약 이름이 식별자 경계(앞뒤가 따옴표 없는 PostgreSQL 식별자 문자인 유니코드 글자·숫자·`_`·`$`가 아님)로 있을 때만 참이다. 이름을 감싼 따옴표의 형식(큰따옴표, `»…«`, `« … »`)과는 관계없다. 다른 제약 이름, 앞이나 뒤만 같은 다른 제약 이름(`ux_a`로 물을 때 `ux_ab`, `pk_ux_a`, `ux_a$1`, `ux_a한`), 다른 SQLState, 원인 사슬에 `SQLException`이 없는 경우는 거짓이다.
5. 구조 테스트 `ConstraintTranslationTest`가 `..adapter.persistence..` 패키지의 클래스는 판별 함수를 부르지 않고, 운영 코드는 `EntityManager`·Spring Data repository·`TransactionStatus`의 `flush`·`saveAndFlush`·`saveAllAndFlush`를 부르지 않으며, `EntityManager`·Hibernate `QueryProducer`의 쿼리 생성은 `JdslQueryExecutor`만 하고 그 클래스는 `@Repository`라는 것을 고정한다. 다시 부르면 실패한다.
6. 단말 credential 회전은 flush 없이 한 커밋에서 옛 행 폐기와 새 행 발급을 하고 지금처럼 성공한다. `ux_terminal_credential_active`는 커밋 때 검사한다. 한 트랜잭션에서 새 유효 행을 넣은 뒤 옛 행을 폐기하면 커밋되고, 커밋 때 같은 단말의 유효 행이 둘이면 커밋이 `DataIntegrityViolationException`으로 실패하고 원인 사슬에 SQLState `23P01`이 있다.
7. `./gradlew test` 전체와 `python3 scripts/check_pr.py --base origin/main`이 통과한다.

## 스키마 변경 검토
V016은 `t_terminal_credential`의 partial unique 인덱스 `ux_terminal_credential_active`를 같은 조건·같은 이름의 exclusion 제약 `EXCLUDE USING btree (terminal_id WITH =) WHERE (revoked_at IS NULL) DEFERRABLE INITIALLY DEFERRED`로 바꾼다. 컬럼과 테이블은 바뀌지 않는다.
1. 기존 데이터로 대신할 수 있는가: 새 값을 더하지 않는다. 이미 있는 방어선의 검사 시점만 문장 끝에서 커밋으로 옮긴다.
2. 지금 읽는 코드가 있는가: `TerminalManagement.rotateCredential`이 한 트랜잭션에서 옛 행을 폐기하고 새 유효 행을 넣는다. 이 제약이 그 순서를 강제해서 어댑터가 flush로 폐기를 먼저 내보내고 있었다.
3. 기존 행을 채울 수 있는가: 채울 컬럼이 없다. 기존 행은 지금 인덱스가 같은 조건을 강제하고 있어 새 제약을 만들 때의 검증을 통과한다. 배포 전 확인 쿼리는 `select terminal_id from t_terminal_credential where revoked_at is null group by terminal_id having count(*) > 1`이고 V012 인덱스가 있는 한 0행이다.
4. 불변식의 마지막 방어선인가: 그대로 "유효 credential은 단말당 하나"의 마지막 방어선이고 PostgreSQL 제약으로 남는다. 커밋 때 검사하므로 한 트랜잭션 안에서 잠시 유효 행이 둘이어도 커밋 전에 하나가 되면 통과하고, 커밋 때 둘이면 실패한다. 술어는 이전처럼 `revoked_at IS NULL`뿐이라 soft delete된 행에도 걸린다. 지우는 명령이 없고 이력 삭제 금지가 불변식이라는 V012의 판단을 따른다.
5. 정본은 하나인가: 바뀌지 않는다. 정본은 `revoked_at is null`인 행이다.
6. 읽고 판단한 뒤 쓰는 경로가 있는가: 회전은 단말 행 잠금(`PESSIMISTIC_WRITE`, 대기 3초)이 직렬화하고 이 제약은 그 뒤의 방어선이다. 잠금 밖에서 두 트랜잭션이 같은 단말의 유효 행을 넣으면 INSERT는 기다리지 않고, 커밋 때 검사가 상대의 미확정 행을 기다린다. 둘 다 커밋하려 하면 교착(40P01)으로 한쪽이 실패하고, 한쪽이 먼저 커밋됐으면 늦은 쪽이 23P01로 실패한다. 어느 쪽이든 유효 행은 하나다. 앱 경로(회전은 행 잠금, 등록은 새 단말)는 이 상황에 닿지 않는다.
7. 락과 크기: `DROP INDEX`와 `ADD CONSTRAINT ... EXCLUDE`는 `t_terminal_credential`에 ACCESS EXCLUSIVE를 잡고 인덱스를 다시 만든다. 행 수는 단말 수에 회전 횟수를 곱한 정도라 작다. V008과 같이 `set local lock_timeout = '5s'`로 대기를 제한한다. DDL이 잠금을 쥐는 동안에는 `/v1` 요청마다 하는 인증 조회(`findActiveByTokenHash`)도 이 표를 읽으므로 그만큼 멈춘다(표가 작아 짧다, V014와 같은 판단). DDL이 잠금을 기다리는 동안에도 뒤에 온 인증 조회가 그 잠금 요청 뒤에 줄을 서므로 최악의 정지는 대기 5초에 잠금을 얻은 뒤의 짧은 DDL 시간(인덱스를 다시 만드는 시간)을 더한 것이고, 시간 초과로 기동이 실패해 재기동하면 시도마다 되풀이될 수 있다. 그동안 요청이 커넥션을 쥔 채 기다려서, 풀이 비면 Hikari `connection-timeout`(3초)을 넘긴 요청이 오류를 받을 수 있다. 배포 때 한 번이고, 5초를 넘기면 마이그레이션이 실패해 새 버전이 기동하지 않을 뿐 데이터는 그대로라 감수한다. 한 마이그레이션 트랜잭션 안에서 바꾸므로 제약이 없는 순간이 밖에서 보이지 않는다.
8. 이전 버전 호환: 이전 버전 코드(폐기를 먼저 flush)는 새 스키마에서도 그대로 동작한다. 순서가 맞으면 커밋 때 검사도 통과한다. 새 코드(flush 없음)는 이전 스키마에서 회전이 실패하므로 스키마가 먼저 바뀌어야 하는데, Flyway가 새 버전이 기동할 때 먼저 적용한다. 애플리케이션을 되돌려도 이전 코드가 새 스키마에서 동작한다. 엔티티 매핑은 바뀌지 않고 `ddl-auto: validate`는 제약을 보지 않는다. 위반은 문장이 아니라 커밋에서 드러나고 SQLState가 `23505`(unique)에서 `23P01`(exclusion)으로 바뀐다. 이 제약을 번역하는 코드는 없어 오류 응답으로 끝난다(23P01은 500).
9. 이벤트·캐시에 실리는가: 실리지 않는다. 인증 결과는 캐시하지 않는다.
- 대조 기록: code-reviewer 에이전트(읽기 전용, 구현자 아님), 2026-10-20 두 차례(94efbdc, 2c67315). 질문 1~9와 V016 SQL(조건·이름이 V012와 같음, drop 뒤 같은 이름의 EXCLUDE DEFERRABLE INITIALLY DEFERRED)을 대조했다. 1차 불일치 6건을 모두 반영했다: TerminalRepository.update(credential) KDoc의 flush 서술, TerminalCredentialEntity KDoc의 partial unique 서술, 질문 6의 잠금 밖 동시 삽입 서술과 V016 주석, 커밋 실패 테스트의 23P01 단언과 질문 8의 예외 서술, TDD 표 credential 줄의 RED 기록, 질문 3 확인 쿼리와 질문 7 인증 조회 정지 서술. 2차에서 근거 없는 409 서술과 "엔티티" 표현을 지적받아 고쳤다. 확인 불가로 남은 것은 회전 테스트 5개 RED(tdd-driver 관측, 리뷰어는 재실행 안 함)다.

## 검증
- `./gradlew test --tests 'com.resilientpos.order.*' --tests 'com.resilientpos.owner.*' --tests 'com.resilientpos.terminal.*' --tests 'com.resilientpos.platform.*' --tests 'com.resilientpos.ConstraintTranslationTest'`
- `./gradlew test` 전체. Testcontainers의 PostgreSQL·Redis·Kafka를 쓴다(Docker는 OrbStack).
- `python3 scripts/check_pr.py --base origin/main --worktree`(개발 중), `--worktree` 없이 한 번 더(제출 전).
- 정상: 같은 키의 주문 생성이 겹치면 늦은 HTTP 요청은 409이고 행은 하나다. 다시 보내면 먼저 저장된 주문과 내용이 같을 때 그 주문을 받고, 다르면 `409 IDEMPOTENCY_CONFLICT`다. 점주·단말 중복은 지금처럼 409다.
- 회전: flush를 지운 채 V016이 없으면 회전 테스트가 partial unique 위반으로 실패하는지, V016을 더하면 통과하는지 본다.
- 실패: 다른 제약 위반은 이전처럼 `DataIntegrityViolationException` 계열(500)이다. Facade의 변환을 지우면 인수 조건 1이 실패하는지, 판별 호출을 어댑터로 되돌리면 인수 조건 5가 실패하는지 본다.

## Wiki 영향
- [엔티티 지도](../wiki/entity-map.md): `t_order` 방어선 줄에 주문 생성 API에서 겹친 위반은 Facade가 409 `CONCURRENT_REQUEST`로 바꾸고 sync 항목이 지면 배치 500이라는 것을 적는다. 4.1 흐름 표의 주문 생성 줄과 4.5 멱등 표의 주문 생성 줄에 늦은 HTTP 요청은 409이고 재요청 결과는 먼저 저장된 주문과 내용이 같을 때 그 주문이라는 것을 적는다. 4.2의 "배치 전체가 409·500이면" 줄은 main 그대로다. 단말·점주 방어선 줄의 "persistence adapter가 제약 이름으로 가려"를 Facade로 고친다. credential 방어선·만드는 곳 줄을 커밋 때 검사하는 exclusion 제약과 flush 없는 회전으로 고친다.
- 현재 상태(`docs/wiki/current-state.md`, 볼트)는 볼트에서 갱신한다. 주문 생성의 락이 통하지 않을 때 "오류를 받는다", 주문 API의 "같은 키의 동시 요청이 unique 제약 위반으로 끝나는 것은 알려진 빈틈이다", `CONCURRENT_REQUEST` 설명의 "unique 제약 위반은 계속 500이다"를 고친다. sync 배치 오류의 409/500 서술은 main 그대로다.
- 볼트 rules: `domain-modeling-kotlin.md` §5에 저장 메서드는 저장만 하고 제약 위반은 커밋을 소유한 Facade가 번역한다는 규칙을, §7에 하지 말 것 한 줄을 더한다. `effective-java-for-kotlin.md` Item 73 줄에 DB 제약 위반의 예외를 단다. 범위 조정 6·7에서 §5에 아래 §4.2와 같은 두 가지 내용(블록 안 JPA 조회의 번역 전제, exclusion 위반을 번역할 때 판별 함수를 바꾸는 규칙)을 더하고 §5·§7의 flush 금지를 운영 코드 전체·목적 무관으로 적는다. 고친 뒤 `make bootstrap`으로 다시 복사한다. 저장소에 추적되지 않는 경로라 커밋은 없다.
- [도메인 모델](../wiki/domain-model.md) §4.2에 저장 메서드는 저장만 하고 제약 위반은 커밋을 소유한 Facade가 번역하며 flush로 순서를 맞추지 않는다는 결정을 한 줄 더한다. 같은 줄에 블록 안 JPA 조회는 `@Repository`를 거쳐 같은 예외로 번역된다는 전제와, exclusion 위반을 업무 오류로 번역하려면 판별 함수를 함께 바꾼다는 것을 적는다(범위 조정 6·7). §4.7(스키마 변경 검토)의 단말 credential 항목에서 회전이 폐기를 먼저 flush한다는 서술을 커밋 때 검사하는 제약으로 고친다. 열린 PR #94·#100도 이 파일을 고치지만 서로 다른 줄이다(분할 계획). Kafka·Modulith 작업이 아니라 Modulith·Kafka 키워드(볼트)도 바꾸지 않는다.
- 요구사항 변화: 없다. 같은 키의 겹친 생성은 여전히 늦은 쪽이 실패하고 정합성은 unique가 지킨다. 실패의 HTTP 표현만 재시도할 수 있는 409가 된다.

## 분할 계획
개발 컨텍스트는 하나다. 같은 키 주문 생성 경합의 HTTP 표현과 unique 위반을 번역하는 자리다. 번역 자리 규칙을 이 PR에서 정하므로 같은 판별 함수를 쓰는 점주·단말을 함께 맞춘다. 버전 충돌은 #34로 끝났고 리스너 경로는 HTTP 500이 아니라서 뺐다.
겹침 확인(2026-10-21, 범위 조정 6·7·8의 착수와 push 직전. origin/main은 b9449c4 그대로이고 열린 PR의 head도 바뀌지 않았다): #98(이벤트 계약)과 #99(outbox 비교 spec)는 이 PR의 rebase 전에 머지됐다. 열린 PR은 #94(`build.gradle.kts`·`EventPublicationResubmitter`·`docs/wiki/domain-model.md`)와 #100(결제 대조)이다. `domain-model.md`는 이 PR이 §4.2 한 줄과 §4.7의 credential 항목을 고치고 #94·#100은 다른 줄을 고친다. `entity-map.md`는 4.5 멱등 표에서 #100과 겹친다. 이 PR은 주문 생성 줄을 고치고 #100은 그 아래에 대조 case 줄을 더한다. `application.yaml`은 이 PR이 `logServerErrorDetail` 주석을, #100이 `resilientpos.payment`와 `kafka.topics`를 고쳐 줄이 다르다. 마이그레이션은 V016 하나이고 #100의 V016·V017, `SchemaMigrationTest`의 적용 목록과 번호를 조율한다(범위 조정 5). 먼저 머지된 쪽에 맞춰 다른 쪽이 rebase한다. 판별 방식을 고칠 때 `build.gradle.kts`를 바꾸는 안은 #94와 겹쳐서 버렸다(범위 조정). 이 PR은 `payment`·이벤트 코드를 건드리지 않는다. 판별 함수는 main에 owner·terminal adapter의 private 사본 둘만 있었고 열린 PR에는 없다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 주문 생성의 경합 예외는 409 CONCURRENT_REQUEST다 (인수 3) `OrderControllerTest`의 `Facade가 경합 예외를 던지면 409 CONCURRENT_REQUEST이고 예외 메시지를 담지 않는다`와 REST Docs `orders-create-concurrent-request` | 해당 없음. `ConcurrencyFailureException` 계열의 409 매핑이 #33부터 있어서 처음부터 통과했다(characterization). 대신 `ApiControllerAdvice`의 그 `@ExceptionHandler`를 지우면 `./gradlew test --tests 'com.resilientpos.order.internal.adapter.web.OrderControllerTest'`에서 이 1개가 상태 코드 409 기대 불일치로 실패하는 것을 확인하고 원복했다 | 같은 명령, 18개 통과. 스니펫 디렉터리 생성 확인 | `./gradlew test --tests 'com.resilientpos.order.internal.adapter.web.*' --tests 'com.resilientpos.platform.ApiAdviceTest'` 통과 |
| 판별 함수는 SQLState와 제약 이름이 모두 맞을 때만 참이다 (인수 4·5) `UniqueViolationTest` 5개(23505와 메시지의 제약 이름이면 참 / 다른 제약 이름 거짓 / 다른 SQLState 거짓 / 원인 사슬에 SQLException 없으면 거짓 / 깊은 원인 사슬에서도 찾는다) | 해당 없음. owner·terminal adapter의 private 사본을 `platform`으로 옮긴 함수의 characterization이라 `./gradlew test --tests 'com.resilientpos.platform.UniqueViolationTest'`가 처음부터 5개 통과했다. 대신 제약 이름 비교 조건을 지우면 `다른 제약 이름이면 거짓이다` 1개가 실패하는 것을 확인하고 원복했다 | 같은 명령, 5개 통과 | `./gradlew test --tests 'com.resilientpos.order.*' --tests 'com.resilientpos.platform.*' --tests 'com.resilientpos.owner.*' --tests 'com.resilientpos.terminal.*' --tests 'com.resilientpos.sync.*'`와 구조 테스트 4종(ModuleStructure·EntityVisibility·TransactionDeclaration·DependencyDirection) 415개 통과. owner·terminal의 기존 중복 테스트 포함(인수 5) |
| 리뷰 반영, 판별 함수는 따옴표 형식과 관계없이 식별자 경계로 제약 이름을 찾는다 (인수 4) `UniqueViolationTest`의 `독일어 번역 메시지의 »« 따옴표 안 제약 이름도 찾는다`, `프랑스어 번역 메시지의 « » 따옴표 안 제약 이름도 찾는다`, `앞부분만 같은 다른 제약 이름이면 거짓이다` | `./gradlew test --tests 'com.resilientpos.platform.UniqueViolationTest'` — 8개 중 2개(독일어·프랑스어)가 assertion 실패. ASCII 큰따옴표로 감싼 이름만 찾았다. `ux_ab` 사례는 따옴표가 경계 역할을 해서 통과했다(characterization) | 식별자 경계 정규식으로 바꾼 뒤 같은 명령, 8개 통과 | `./gradlew test --tests 'com.resilientpos.platform.*' --tests 'com.resilientpos.owner.*' --tests 'com.resilientpos.terminal.*' --tests 'com.resilientpos.order.internal.application.OrderCommandFacadeIntegrationTest'` 통과 |
| 재리뷰 반영, 경계 문자는 따옴표 없는 PostgreSQL 식별자 문자다 (인수 4) `UniqueViolationTest`의 `제약 이름 뒤에 식별자 문자 $가 이어지면 거짓이다`, `제약 이름 앞에 식별자 문자가 이어지면 거짓이다` | `./gradlew test --tests 'com.resilientpos.platform.UniqueViolationTest'` — 10개 중 1개(`$` 사례)가 assertion 실패. ASCII 경계는 `$`를 식별자 문자로 보지 않았다. 앞쪽 경계 사례는 characterization이라 통과했고, lookbehind를 지우면 그 사례가 실패하는 것을 확인한 뒤 그 파일만 원복했다(`$` 사례는 RED라 이미 실패 중이었다) | 경계 문자를 유니코드 글자·숫자·`_`·`$`로 넓힌 뒤 같은 명령, 10개 통과 | `./gradlew test --tests 'com.resilientpos.platform.*' --tests 'com.resilientpos.owner.*' --tests 'com.resilientpos.terminal.*' --tests 'com.resilientpos.order.internal.application.OrderCommandFacadeIntegrationTest'` 통과 |
| 두 번째 반영 리뷰 반영, 경계는 비ASCII 글자도 식별자 문자로 본다 (인수 4) `UniqueViolationTest`의 `제약 이름 뒤에 비ASCII 글자가 이어지면 거짓이다` | 해당 없음. 이미 넓힌 경계를 고정하는 characterization이라 처음부터 통과했다. 대신 경계를 `[A-Za-z0-9_$]`로 되돌리면 `./gradlew test --tests 'com.resilientpos.platform.UniqueViolationTest' --rerun`에서 11개 중 이 1개만 실패하는 것을 확인하고 그 파일만 원복했다 | 같은 명령, 11개 통과 | `./gradlew test` 604개 통과 |
| 주문 생성 Facade가 같은 키 경합을 경합 예외로 바꾼다 (인수 1) `OrderCreateRaceIntegrationTest`의 `커밋 전인 같은 키 생성과 겹친 create는 경합 예외로 끝나고 다시 요청하면 앞 주문을 받는다`(테스트 트랜잭션이 같은 키 주문을 넣고 flush한 뒤 커밋 전에 붙들고, `create`의 INSERT가 그 트랜잭션에 막힌 것을 `pg_locks`와 `pg_blocking_pids`로 확인한 뒤 커밋한다) | 어댑터를 main으로 되돌린 상태에서 `./gradlew test --tests 'com.resilientpos.order.internal.application.OrderCreateRaceIntegrationTest'` — 1개 실패. 기대한 `ConcurrencyFailureException` 대신 `DataIntegrityViolationException`이 나왔다 | `create`가 `Lock` 블록 밖에서 잡아 번역한 뒤 같은 명령, 1개 통과 | `./gradlew test --tests 'com.resilientpos.order.*' --tests 'com.resilientpos.sync.*'`와 구조 테스트 3종(TransactionDeclaration·ModuleStructure·DependencyDirection) 통과 |
| 저장 메서드는 번역하지 않고 점주·단말 번역을 Facade로 옮긴다 (인수 2·5) `ConstraintTranslationTest`의 `persistence 어댑터는 unique 위반 판별 함수를 부르지 않는다` | `./gradlew test --tests 'com.resilientpos.ConstraintTranslationTest'` — 1개 실패. 위반 2건(`OwnerPersistenceAdapter.save`, `TerminalPersistenceAdapter.save`가 `isUniqueViolationOf`를 부른다) | 두 어댑터에서 flush와 번역을 빼고 `OwnerManagement.signUp`·`TerminalManagement.register`가 블록 밖에서 번역한 뒤 같은 명령, 1개 통과. 어댑터에 판별 호출을 잠깐 넣으면 다시 실패하는 것을 확인하고 원복했다 | owner·terminal·order·platform과 구조 테스트 353개 통과. 중복 409 통합 테스트와 Controller 테스트는 기대값을 바꾸지 않고 통과했다(인수 2). `OwnerManagementTest`의 가짜 저장소는 DB처럼 `DataIntegrityViolationException`을 던지게 바꿨다 |
| 어댑터는 flush하지 않고 credential 제약은 커밋 때 검사한다 (인수 5·6) `ConstraintTranslationTest`의 `persistence 어댑터는 EntityManager flush를 부르지 않는다`(범위 조정 6에서 `운영 코드는 flush를 부르지 않는다`로 이름과 범위를 바꿨다), `TerminalCredentialSchemaIntegrationTest`의 `한 트랜잭션에서 새 유효 행을 넣은 뒤 옛 행을 폐기하면 커밋되고 유효 행은 하나다`·`한 트랜잭션에서 같은 단말의 유효 행 둘을 넣으면 커밋에서 실패한다`·`같은 단말의 revoked_at이 null인 행 두 개는 exclusion 위반이다`(기존 unique 위반 테스트의 기대를 제약 종류에 맞게 바꾸고 SQLState `23P01`을 단언), 기존 회전 테스트 | 구조: `./gradlew test --tests 'com.resilientpos.ConstraintTranslationTest'` 2개 중 1개 실패(`TerminalPersistenceAdapter.kt:84`의 flush). flush를 지우자 `./gradlew test --tests 'com.resilientpos.terminal.*'` 61개 중 회전 테스트 5개가 partial unique 위반으로 실패. 스키마 테스트는 V016 없이 5개 중 3개 실패(커밋 때 검사 2개, exclusion SQLState 1개) | V016(partial unique 인덱스를 같은 조건·같은 이름의 `DEFERRABLE INITIALLY DEFERRED` exclusion 제약으로 바꿈) 뒤 같은 명령 모두 통과 | terminal·owner·구조 테스트·`SchemaMigrationTest`(적용 버전 목록에 016 추가) 121개 통과. 전체는 결과 절 |
| 4차 리뷰 반영, 운영 코드는 flush를 부르지 않는다 (인수 5) `ConstraintTranslationTest`의 `운영 코드는 flush를 부르지 않는다` | `platform`에 임시 파일로 `EntityManager.flush`와 `JpaRepository.saveAndFlush` 호출을 넣었다. 기존 규칙(`..adapter.persistence..`의 `EntityManager.flush`)은 `./gradlew test --tests 'com.resilientpos.ConstraintTranslationTest' --rerun`에서 그대로 통과했고, 넓힌 규칙은 같은 명령에서 두 호출을 위반으로 보고 실패했다 | 임시 파일을 지운 뒤 같은 명령, 2개 통과 | 전체는 결과 절 |
| 4차 리뷰 반영, 점주 가입은 이메일 unique 위반만 번역한다 (인수 2) `OwnerManagementTest`의 `같은 이메일로 다시 가입하면 커밋의 unique 위반을 OWNER_EMAIL_DUPLICATED로 바꾼다`(범위 조정 7에서 `같은 이메일로 다시 가입하면 저장소의 이메일 unique 위반을 OWNER_EMAIL_DUPLICATED로 바꾼다`로 이름을 바꿨다), `다른 제약의 위반은 번역하지 않고 그대로 던진다` | 해당 없음. 이미 있는 번역의 characterization이라 처음부터 통과했다. 대신 `OwnerManagement.signUp`의 번역 줄을 지우면 첫 테스트만, `throw e`를 도메인 예외로 바꾸면 둘째 테스트만 `./gradlew test --tests 'com.resilientpos.owner.internal.application.OwnerManagementTest' --rerun`에서 실패하는 것을 확인하고 그 파일만 원복했다 | 같은 명령, 13개 통과 | 같은 명령과 `OrderControllerTest` 18개 통과(REST Docs 설명과 가짜 메시지 형식을 고친 뒤) |
| 4차 리뷰 반영, 커밋 때 검사 위반은 `DataIntegrityViolationException`이다 (인수 6) `TerminalCredentialSchemaIntegrationTest`의 `한 트랜잭션에서 같은 단말의 유효 행 둘을 넣으면 커밋에서 실패한다` | 해당 없음. 리뷰어는 `JpaSystemException` 계열을 추정했지만, 기대 타입을 `Exception`에서 `DataIntegrityViolationException`으로 좁힌 `./gradlew test --tests 'com.resilientpos.terminal.internal.application.TerminalCredentialSchemaIntegrationTest' --rerun`이 처음부터 5개 통과했다(characterization) | 같은 명령, 5개 통과 | `./gradlew test --tests 'com.resilientpos.terminal.internal.application.TerminalCredentialSchemaIntegrationTest' --tests 'com.resilientpos.owner.internal.application.OwnerManagementIntegrationTest' --tests 'com.resilientpos.order.internal.application.OrderCreateRaceIntegrationTest' --rerun`으로 함께 돌렸고, 그 결과 파일의 `OwnerManagementIntegrationTest`·`OrderCreateRaceIntegrationTest` 로그에 `HHH000346`(ERROR)은 없고 Hibernate WARN 두 줄만 있었다 |
| 5차 리뷰 반영, `EntityManager`의 쿼리는 `JdslQueryExecutor`만 만들고 `TransactionStatus.flush`도 막는다 (인수 5) `ConstraintTranslationTest`의 `EntityManager의 쿼리는 JdslQueryExecutor만 만든다`, `운영 코드는 flush를 부르지 않는다` | `platform`에 임시 파일로 `EntityManager.createQuery`와 `TransactionStatus.flush` 호출을 넣었다. 범위 조정 6의 규칙은 `./gradlew test --tests 'com.resilientpos.ConstraintTranslationTest' --rerun`에서 통과했고, 새 규칙은 같은 명령에서 3개 중 2개가 두 호출을 위반으로 보고 실패했다 | 임시 파일을 지운 뒤 같은 명령, 3개 통과 | 전체는 결과 절 |
| 6차 Codex 리뷰 반영, Hibernate 세션의 쿼리 생성도 묶고 `JdslQueryExecutor`의 `@Repository`를 고정한다 (인수 5) `ConstraintTranslationTest`의 `EntityManager의 쿼리는 JdslQueryExecutor만 만든다`(대상 넓힘), `JdslQueryExecutor는 @Repository다` | `platform`에 임시 파일로 `SharedSessionContract.createStoredProcedureCall`·`getNamedQuery` 호출을 넣고 `JdslQueryExecutor`의 `@Repository`를 잠깐 지웠다. 범위 조정 7의 규칙은 `./gradlew test --tests 'com.resilientpos.ConstraintTranslationTest' --rerun`에서 3개 모두 통과했고, 새 규칙은 같은 명령에서 4개 중 2개가 두 호출과 애너테이션 없음을 보고 실패했다 | 임시 파일을 지우고 `JdslQueryExecutor.kt`만 원복한 뒤 같은 명령, 4개 통과 | 전체는 결과 절 |

## 결과
- 인수 조건 1~7을 충족했다. origin/main(b9449c4) 위에서 `./gradlew test`는 619개 중 실패 0이다(신규: `UniqueViolationTest` 11, `OrderCreateRaceIntegrationTest` 1, `OrderControllerTest` 1, `ConstraintTranslationTest` 4, `TerminalCredentialSchemaIntegrationTest` 2, `OwnerManagementTest` 2. 그 클래스의 기존 unique 위반 테스트는 exclusion 위반으로 기대를 바꿨다). REST Docs 스니펫 `orders-create-concurrent-request`가 `build/generated-snippets`에 생겼다.
- 구현: `OrderCommandFacade.create`의 번역(예외 메시지에 `terminalId`·`clientOrderId`), `OwnerManagement.signUp`·`TerminalManagement.register`의 번역 이전, 두 어댑터 `save`에서 flush·번역 제거, `platform`의 `Throwable.isUniqueViolationOf`(식별자 경계로 판별), credential 제약을 커밋 때 검사하는 exclusion 제약으로 바꾸는 V016과 `TerminalPersistenceAdapter.update(credential)`의 flush 제거, 구조 테스트 `ConstraintTranslationTest`(어댑터의 판별 함수 호출, 운영 코드의 flush 호출, `JdslQueryExecutor` 밖의 `EntityManager`·Hibernate 세션 쿼리 생성 금지, `JdslQueryExecutor`의 `@Repository` 고정), `ApiControllerAdvice.handleConcurrency`와 `JdslQueryExecutor`의 KDoc, `application.yaml`의 `logServerErrorDetail` 주석이다. `OrderPersistenceAdapter`, sync 코드, `ApiControllerAdvice`의 동작, `ErrorType`은 바뀌지 않았다. 운영 코드에 `entityManager.flush()`가 남지 않았다.
- 하네스: 볼트 rules `domain-modeling-kotlin.md` §5·§7과 `effective-java-for-kotlin.md` Item 73을 고치고 `make bootstrap`으로 다시 복사했다. `workflow-reviewer` 검토는 WARN(막는 결함 없음)이었고, 규칙을 메서드 이름·호출 모양이 아니라 역할로 적고, 예외 종류를 고르는 기준을 wiki에 적고, "이미 있다"는 뜻의 위반은 이유를 KDoc에 적고 삼킬 수 있게(`SyncCommandFacade.ensureWatermark`) 고쳤다. 결정은 [도메인 모델](../wiki/domain-model.md)의 "Aggregate와 JPA 엔티티는 별개 클래스다" 절에 있다. 범위 조정 6에서 §5에 블록 안 조회의 번역 전제와 exclusion 제약 규칙을 더하고, §5·§7의 flush 금지 범위를 운영 코드 전체로 넓혀 다시 복사했다. 범위 조정 7에서 그 전제를 JPA 조회로 좁히고, flush 금지를 목적 무관으로, exclusion 규칙을 판별 함수를 함께 바꾸는 쪽으로 고쳐 다시 복사했다. 범위 조정 8에서 쿼리 생성 규칙의 대상을 Hibernate 세션과 `@Repository` 고정까지 넓혀 다시 복사했다.
- 이슈 #79의 첫 항목(커밋 시점 버전 충돌)은 #34로 이미 해결돼 코드를 바꾸지 않았다. 근거는 범위 절에 적었다.
- 동작 하나씩 `tdd-driver`에 맡겼다(판별 함수 이동, 판별 함수의 식별자 경계, 주문 생성 Controller, 주문 생성 Facade 번역, 구조 테스트와 점주·단말 이전). 주 에이전트가 diff를 검토하고 변이 확인 일부와 문서·KDoc·되돌리기를 직접 했다. 범위 조정 6·7·8은 새 동작 없이 구조 테스트를 넓히고 기존 동작을 고정하는 테스트라 주 에이전트가 직접 하고 변이로 확인했다.
- multi-review: 1차 Claude 87점·Codex 77점, 2차(반영 커밋) Claude 92점·Codex 88점, 3차(두 번째 반영 커밋) Claude 94점·Codex 87점. Codex는 2·3차 모두 CLI를 직접 실행했고 Gemini는 CLI가 없어 미실행이다. 세 번 모두 막는 결함은 없었다. 4차(범위 조정 4·5 뒤의 PR 전체)는 Claude 93점·Codex 84점이고 막는 결함이 없었다. 반영은 범위 조정 6이다. 5차(범위 조정 6의 반영 커밋)는 Claude 93점·Codex 91점이고 Critical·High가 없었다. 반영은 범위 조정 7이다. 6차(범위 조정 7의 반영 커밋)는 Claude 94점·Codex 94점이고 Critical·High가 없었다. 사용자 결정으로 Codex 지적만 범위 조정 8로 반영하고 7차 리뷰는 돌리지 않았다.
