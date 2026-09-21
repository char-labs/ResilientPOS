# PR spec: Spring Modulith Event Publication Registry 도입

## 목적
`OrderCommandFacade`는 도메인 이벤트를 plain Spring 이벤트로 발행한다. 리스너가 커밋 뒤에 실패하거나 프로세스가 죽으면 이벤트가 사라지고, `RequestCheckout`의 재요청은 이벤트를 다시 내지 않으므로 복구할 길이 없다. `OrderPaymentRequested`를 받는 `payment` 모듈이 생기기 전에, 이벤트 발행 기록이 업무 상태와 같은 트랜잭션에 남고 처리 완료가 추적되는 Event Publication Registry를 넣는다.

## 범위
- 소유 모듈: `platform`(인프라). 업무 모듈의 코드는 바꾸지 않는다. 검증 테스트는 `order` 아래에 둔다. 지금 이벤트를 발행하는 곳이 `order`뿐이어서다.
- 의존성: `spring-modulith-starter-jpa`를 더한다(BOM 2.1.1). `starter-core`·`events-api`·`events-core`·`events-jackson`·`events-jpa`를 끌어온다. 테스트가 `EventSerializer`를 쓰려고 `spring-modulith-events-core`를 테스트 범위에 한 줄 더한다(starter에서는 runtime 범위다).
- 스키마: `V004__platform_event_publication.sql`이 `event_publication`을 만든다. JPA가 `validate`라서 Modulith의 엔티티 `DefaultJpaEventPublication`(`@Table(name = "EVENT_PUBLICATION")`)에 맞는 테이블이 없으면 애플리케이션이 뜨지 않는다. JPA용 jar에는 스키마 SQL이 없어서 같은 버전 JDBC 모듈의 공식 PostgreSQL 스키마(`schemas/v2/schema-postgresql.sql`)를 기준으로 쓰고 엔티티의 매핑과 대조했다.
- 동작 방식(Modulith의 것이고 이 PR이 구현하지 않는다): 트랜잭션 안에서 `publishEvent`를 부르면 그 이벤트를 받는 트랜잭션 이벤트 리스너마다 발행 기록 한 행이 같은 트랜잭션에 저장된다. 리스너는 커밋 뒤에 실행되고 성공하면 기록에 완료 시각이 찍힌다. 실패하면 미완료로 남는다. **리스너가 없는 이벤트는 기록되지 않는다.**
- Facade가 커밋 전에 이벤트를 발행하는 순서는 그대로 둔다. Registry가 발행 기록을 업무 상태와 같은 커밋에 넣으려면 발행이 트랜잭션 안이어야 한다. #33의 리뷰가 확인하라고 넘긴 항목의 답이다.
- 직렬화: 발행 기록은 이벤트를 JSON으로 저장한다(`events-jackson`). `OrderPaymentRequested`와 `OrderCreated`에 실린 `Money`(`BigDecimal`과 `java.util.Currency`), `@JvmInline value class` ID(`OrderId`·`StoreId`·`TerminalId`·`ClientOrderId`), `UUID`, `Instant`가 저장됐다가 같은 값으로 복원되는지 테스트로 고정한다. 복원되지 않으면 미완료 이벤트를 다시 보낼 수 없다.
- 검증용 리스너: 프로덕션 리스너가 아직 없어서 테스트 클래스 하나에만 주입하는 `@ApplicationModuleListener`로 검증한다. 다른 통합 테스트의 컨텍스트에는 들어가지 않는다.
- 설정: 기본값을 쓴다. 완료 처리는 UPDATE(완료 표시만 하고 행을 남긴다), 재기동 시 미완료 이벤트 재발행은 끈 상태다.
- 정합성 불변식: 발행 기록과 업무 상태 변경은 한 커밋이다. 업무 트랜잭션이 롤백되면 발행 기록도 남지 않고 리스너도 실행되지 않는다.
- 실패·재시도·복구: 리스너가 실패한 이벤트는 미완료 기록으로 남는다. 다시 보내는 절차(재전송 주기, 재기동 시 재발행, 인스턴스가 여럿일 때의 중복 방지)는 이 PR에 없다. 실제 리스너가 생기는 `payment` PR이 정한다.
- 관측성: 미완료 기록은 `select * from event_publication where completion_date is null`로 볼 수 있다. 지표와 알림은 관측성 작업의 몫이다.

### 합의한 결정 (2026-09-21 grill)
- 완료 처리는 UPDATE다. 초기에 "이 이벤트가 언제 발행되고 언제 처리됐는지"를 DB에서 바로 확인할 수 있다. 테이블이 계속 자라므로 오래된 완료 행을 지우는 정리 작업이 필요해지고, 이벤트 양이 실제로 생기는 `payment` 이후에 정한다. DELETE는 처리 이력이 사라지고 ARCHIVE는 테이블이 하나 더 필요해서 버렸다.
- 이 PR은 기반만 넣는다. 실제 리스너, 재전송 정책, 리스너를 실행하는 스레드 풀의 조정은 `payment` PR이 정한다.
- 테이블 이름은 프레임워크가 정한 `event_publication` 그대로다. `t_` 접두어 규칙의 예외다. 엔티티가 라이브러리 안에 있어서 이름을 바꿀 수 없고, 별도 outbox 테이블을 만들지 않고 Modulith의 것을 그대로 쓴다는 기존 결정([도메인 모델](../wiki/domain-model.md) 4.3절)을 따른다.
- 재기동 시 미완료 이벤트 재발행은 켜지 않는다. 인스턴스가 여럿이면 같은 이벤트를 여러 인스턴스가 다시 보낸다.

### 범위 조정 (2026-09-21, 개발 중 확인한 사실로 서술을 고친다)
처음 spec은 "비동기를 켜지 않으면 리스너가 커밋 뒤에 발행한 스레드에서 실행된다"고 적었다. 틀렸다. 테스트용 리스너가 `task-1` 같은 별도 스레드에서 실행되는 것을 보고 Modulith 2.1.1의 jar를 확인했다.
- `EventPublicationAutoConfiguration`의 `AsyncEnablingConfiguration`이 `@EnableAsync`를 켠다(다른 비동기 구성이 없을 때). 그래서 `@ApplicationModuleListener`는 Spring Boot의 기본 task executor에서 비동기로 실행된다. 우리 코드에 `@EnableAsync`가 없어도 그렇다.
- 같은 자동 구성의 `AsyncPropertiesDefaulter`가 `spring.task.execution.shutdown.await-termination`을 켜고 대기 시간을 2초로 둔다. 종료할 때 진행 중인 리스너를 2초 기다린다는 뜻이다.
- 영향: 업무 요청의 응답은 리스너를 기다리지 않는다. 리스너의 성공·실패는 요청 스레드 밖에서 기록된다. 그래서 검증 테스트는 발행 기록의 상태가 바뀔 때까지 기다린 뒤 확인한다. 종료 대기 2초 안에 끝나지 못한 리스너의 이벤트는 미완료 기록으로 남고, 다시 보내는 절차가 없는 동안은 그대로 남는다(제외 참고).
- `EventSerializer`가 든 `spring-modulith-events-core`는 starter에서 runtime 범위라 테스트가 컴파일되지 않았다. 테스트 범위 의존성 한 줄을 더했다.

### 범위 조정 2 (2026-09-21, 제출 전 multi-review와 스키마 대조 반영)
마이그레이션이 있고 의존성 하나가 전역 동작(비동기 실행, 종료 대기)을 바꾸며 이벤트 클래스가 저장 형식이 되므로 PR 템플릿 기준 🔴이다. multi-review(Claude, Codex 실행. Gemini는 CLI가 없어 미실행)와 구현자가 아닌 `code-reviewer`의 스키마 대조를 돌렸다. 셋 다 막는 결함은 없다고 판정했다. 리뷰어의 지적 중 사실 확인이 필요한 것은 jar와 임시 테스트로 확인했다(임시 테스트는 커밋하지 않았다).
- 합의한 기본값을 설정에 적는다. `application.yaml`에 `spring.modulith.events.completion-mode: update`와 `spring.modulith.events.republish-outstanding-events-on-restart: false`를 명시한다. 지금 기본값과 같아서 동작은 바뀌지 않는다. Modulith를 올릴 때 기본값이 바뀌어도 결정이 조용히 뒤집히지 않는다. 속성 이름과 기본값은 `events-core` 2.1.1의 설정 메타데이터에서 확인했다.
- 리스너를 실행하는 Spring Boot 기본 task executor는 코어 8, 최대 무제한, 큐 무제한이다(실행 중인 빈의 값을 확인했다). 큐가 무제한이면 최대 크기는 쓰이지 않으므로 동시에 도는 리스너는 8개가 상한이고 밀린 작업은 메모리에 쌓인다. 종료 대기 2초 안에 시작하지 못한 작업은 실행되지 않고 미완료 기록으로 남는다. provider를 호출하는 리스너를 넣는 `payment` PR이 풀·큐·거절 정책을 정한다.
- 완료 표시는 `serializedEvent`와 `listenerId`가 같은 행을 찾아 갱신한다(`JpaEventPublicationRepository`의 쿼리를 확인했다). `serialized_event`의 hash 인덱스가 이 조회에 쓰인다. `listener_id`에는 리스너의 클래스 정규 이름·메서드·이벤트 타입이 저장된다(예: `...PaymentRequestedListener.on(com.resilientpos.order.OrderPaymentRequested)`). 그래서 이벤트 클래스뿐 아니라 **리스너의 패키지·클래스·메서드 이름도 저장 형식**이다. 미완료 기록이 남은 동안 바꾸면 그 기록을 끝낼 주체가 없어진다.
- 트랜잭션 밖에서 이벤트를 발행하면 발행 기록은 남는데 리스너가 실행되지 않았다(기록 1행, 수신 0건을 확인했다). 그 기록은 미완료로 남는다. 이벤트 발행은 Facade의 트랜잭션 안에서만 한다는 규칙을 wiki에 적는다.
- Modulith에는 오래 머문 발행 기록을 감시하는 설정(`spring.modulith.events.staleness.*`)이 있고 기본값은 비어 있다. 재전송 절차를 정할 때 함께 본다.
- 테스트를 고친다. 발행 기록 조회에 `listener_id` 조건을 더한다(실제 리스너가 생기면 같은 이벤트에 행이 둘이 된다). 롤백 테스트의 300ms 대기를 없애고, 롤백 뒤에 다른 주문의 checkout을 커밋해 그 완료를 기다린 다음 롤백된 주문의 수신이 0건인지 본다. 기다리다 시간이 넘으면 그때의 발행 기록 상태를 메시지에 담는다. 자릿수가 어긋난 금액의 JSON은 `Money`의 검증이 복원을 거절한다는 것을 테스트로 고정한다.
- 공식 스키마와 다르게 쓴 곳을 스키마 변경 검토 끝에 적는다.

## 스키마 변경 검토
[도메인 모델](../wiki/domain-model.md) 4.7절 질문의 답이다. 이 테이블은 프레임워크가 소유하고 컬럼을 읽고 쓰는 코드도 Modulith 안에 있다.

1. 도출 가능성: 발행 기록은 "이 이벤트를 이 리스너에게 전달해야 하고 아직 끝나지 않았다"는 사실이다. 업무 테이블에서 도출할 수 없다. 컬럼은 엔티티 `DefaultJpaEventPublication`이 상속하는 `JpaEventPublication`(`@MappedSuperclass`)의 필드와 일대일이다(`id`, `listener_id`, `event_type`, `serialized_event`, `publication_date`, `completion_date`, `status`, `completion_attempts`, `last_resubmission_date`). 우리가 고를 수 있는 컬럼이 없다.
2. 읽는 코드: Modulith의 `JpaEventPublicationRepository`가 모든 컬럼을 읽고 쓴다. 우리 코드는 읽지 않는다. 테스트만 SQL로 확인한다.
3. 나중에 채울 수 있는가: 새 테이블이라 해당 없다. 이 PR 전에 발행된 이벤트는 기록이 없고 채울 수도 없다. 지금까지 리스너가 없었으므로 잃은 전달도 없다.
4. 마지막 방어선: 이 테이블이 이벤트 전달 내구성의 방어선이다. PostgreSQL에 두고 업무 상태와 같은 트랜잭션에 쓴다. Redis나 Kafka로 대신하지 않는다. unique 제약은 PK뿐이다. `BaseEntity`를 상속하지 않으므로 `version`·`created_at`·`updated_at`·`deleted_at`이 없고 soft delete도 없다. 저장소 규칙 "모든 엔티티는 `BaseEntity`를 상속한다"의 예외이고 이유는 엔티티가 라이브러리의 것이기 때문이다.
5. 정본: `serialized_event`는 발행 시점 사실의 사본이다. 업무 상태의 정본은 계속 aggregate의 테이블이다. 같은 트랜잭션에서 함께 쓰이므로 어긋난 채 커밋되지 않는다. 그 뒤에 aggregate가 바뀌어도 이미 발행된 이벤트의 내용은 바뀌지 않는 것이 맞다.
6. check-then-act: 우리 코드에는 없다. Modulith가 완료를 표시할 때의 경합은 리스너 하나가 기록 하나를 맡는 구조라 생기지 않는다. 재전송을 켜면 여러 인스턴스가 같은 미완료 기록을 집을 수 있어서 이 PR에서는 켜지 않는다.
7. 락과 크기: 새 테이블이라 기존 테이블에 락을 잡지 않는다. 인덱스 둘은 공식 스키마의 것이고 이름도 저장소의 `ix_` 관례가 아니라 공식 이름 그대로 뒀다. Modulith를 올릴 때 공식 스키마와 대조하기 쉽게 하려는 것이다. `serialized_event`의 hash 인덱스는 Modulith가 완료를 표시할 때 직렬화된 이벤트로 기록을 찾는 조회에, `completion_date` 인덱스는 미완료 조회에 쓰인다. UPDATE 방식이라 테이블이 계속 자란다. 정리 작업은 위 결정대로 뒤로 미뤘다.
8. 이전 버전 호환: 이전 버전은 이 테이블을 모르고 JPA `validate`는 매핑된 엔티티만 검사하므로 기동과 쓰기에 영향이 없다. 반대로 새 버전은 이 마이그레이션 없이는 뜨지 않는다. Flyway가 기동 때 먼저 적용하므로 배포 순서 문제는 없다.
9. 이벤트·캐시: 이벤트 클래스의 모양이 저장 형식이 된다. 미완료 기록이 남아 있는 동안 이벤트의 필드를 지우거나 이름·타입을 바꾸면 복원이 실패한다. 이벤트 변경은 필드 추가(기본값 있음)만 하위 호환이다. `event_type`에는 클래스의 정규 이름이 저장되므로 이벤트 클래스의 패키지 이동과 이름 변경도 같은 제약을 받는다. 이 규칙을 [도메인 모델](../wiki/domain-model.md) 4.3절에 적는다. 캐시는 없다.

## 제외
- 실제 리스너와 `payment` 모듈. 다음 작업이다.
- 미완료 이벤트 재전송(주기, 재기동 시 재발행, 다중 인스턴스 중복 방지)과 완료 행 정리 작업.
- 리스너를 실행하는 스레드 풀의 조정(크기, 큐, 거절 정책)과 종료 대기 시간. 위 범위 조정에 적은 대로 비동기 실행 자체는 Modulith가 켠다. 실제 리스너가 하는 일(provider 호출 여부)을 보고 정한다.
- Kafka로의 외부 전달(`@Externalized`)과 `spring-modulith-starter-insight`.
- `OrderCreated`를 받는 리스너. 지금은 받는 곳이 없어서 기록되지 않는다.
- 종료할 때 task executor의 2초 대기와 DataSource 종료의 순서. 커넥션 풀이 먼저 닫히면 성공한 리스너의 완료 표시가 실패해 미완료로 남을 수 있다. 확인하지 않았고 `payment` PR의 확인 항목이다.
- `@ApplicationModuleListener`가 아닌 평범한 `@TransactionalEventListener`도 발행 기록을 만든다. 어느 쪽이든 첫 리스너를 넣는 PR이 재전송과 정리 작업을 함께 정한다.

## 관련 문서
없음.

## 변경 경로
- build.gradle.kts
- src/main/resources/application.yaml
- src/main/resources/db/migration/V004__platform_event_publication.sql
- src/test/kotlin/com/resilientpos/SchemaMigrationTest.kt
- src/test/kotlin/com/resilientpos/order/internal/application/**
- AGENTS.md
- docs/spec/2026-09-21-event-publication-registry.md
- docs/wiki/current-state.md
- docs/wiki/domain-model.md
- docs/wiki/changes.md

## 인수 조건
1. `V004`가 적용되고 `event_publication` 테이블이 있으며 JPA `validate`를 통과해 애플리케이션 컨텍스트가 뜬다.
2. `OrderPaymentRequested`를 받는 트랜잭션 이벤트 리스너가 있을 때 `facade.requestCheckout`이 끝나면 `event_publication`에 그 이벤트의 행이 하나 있고 `event_type`이 이벤트 클래스의 정규 이름이며 `completion_date`가 채워져 있다. 리스너는 이벤트를 한 번 받았다.
3. 리스너가 예외를 던지면 `requestCheckout`은 그대로 성공하고(업무 트랜잭션은 이미 커밋됐다) 그 이벤트의 행은 `completion_date`가 비어 있는 채로 남는다.
4. `requestCheckout`을 부른 바깥 트랜잭션이 롤백되면 `event_publication`에 행이 남지 않고 리스너도 이벤트를 받지 않는다.
5. 저장된 `serialized_event`를 Modulith의 `EventSerializer`로 복원하면 발행한 `OrderPaymentRequested`와 같은 값이다(`Money`, value class ID, `UUID`, `Instant` 포함). `OrderCreated`도 직렬화했다가 복원하면 같은 값이다.
6. 받는 리스너가 없는 `OrderCreated`는 `facade.create` 뒤에도 `event_publication`에 행을 남기지 않는다.
7. 기존 테스트 81개와 모듈 구조·의존성 방향 테스트가 그대로 통과한다.

## 검증
- 통합: `./gradlew test --tests 'com.resilientpos.order.internal.application.OrderEventPublicationIntegrationTest'` (Docker 필요)
- 스키마: `./gradlew test --tests 'com.resilientpos.SchemaMigrationTest'`
- 전체 회귀: `./gradlew test`
- 하네스: `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전 `--worktree` 없이 한 번 더.
- 정상 시나리오: 인수 조건 1·2·5. 실패·복구 시나리오: 3·4. 성질 확인: 6.

## Wiki 영향
- [현재 상태](../wiki/current-state.md): 빌드 선언 표와 "Event Publication Registry는 아직 없다", "plain Spring 이벤트, EPR 없음" 서술을 고친다. 리스너가 없으면 기록되지 않는다는 점, 기본 설정, 재전송이 없다는 알려진 빈틈, 마이그레이션 `V004`, 테스트 수를 반영한다.
- [도메인 모델](../wiki/domain-model.md): 4.3절에 테이블 이름과 `BaseEntity` 예외, 이벤트 클래스 변경의 하위 호환 규칙을 적는다.
- [AGENTS.md](../../AGENTS.md): 기술 스택 요약에 Event Publication Registry를 더한다.
- [변경 기록](../wiki/changes.md): 이 spec 링크와 결정을 적는다.

## 분할 계획
예상 규모는 빌드 1줄, 마이그레이션 약 20줄, 테스트 약 170줄, 문서 약 170줄로 총 360줄·9파일 내외다. 경고 한도(600줄·20파일) 안이다. 직렬화 왕복(인수 조건 5)이 기본 구성으로 통과하지 않으면 그 해결(직렬화 설정이나 이벤트 타입 조정)의 크기를 보고 같은 spec에 범위 조정을 적은 뒤 이 PR에 넣을지 나눌지 정한다. 다음 작업은 `payment` 모듈의 `PaymentAttempt`와 `CreatePaymentAttempt`다. 그 PR이 첫 실제 리스너, 재전송 정책, 리스너를 실행하는 스레드 풀의 조정을 정하고 0원 attempt를 provider 호출 없이 승인한다.

## TDD 검증 기록
인프라 도입이라 도메인 동작의 RED는 없다. Registry의 동작을 이 저장소의 구성에서 확인하는 통합 테스트를 먼저 쓰고, 의존성과 마이그레이션이 없는 상태에서 실패하는 것을 확인한 뒤 넣는다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 인수 조건 1: 스키마 테스트의 기대값 | 미실행 | 미실행 | 미실행 |
| 인수 조건 2: `리스너가 성공하면 발행 기록이 완료로 표시된다` | 미실행 | 미실행 | 미실행 |
| 인수 조건 3: `리스너가 실패하면 업무 결과는 그대로이고 발행 기록은 미완료로 남는다` | 미실행 | 미실행 | 미실행 |
| 인수 조건 4: `업무 트랜잭션이 롤백되면 발행 기록도 리스너 호출도 없다` | 미실행 | 미실행 | 미실행 |
| 인수 조건 5: `저장된 이벤트를 복원하면 발행한 값과 같다` | 미실행 | 미실행 | 미실행 |
| 인수 조건 6: `받는 리스너가 없는 이벤트는 기록되지 않는다` | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
