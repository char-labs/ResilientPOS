# 현재 상태

## 실제 빌드 선언 (2026-09-15 확인)

| 항목 | 저장소에 선언된 상태 |
|---|---|
| 언어/JVM | Kotlin 2.4.10, Java toolchain 25 |
| 프레임워크 | Spring Boot 4.1.1, dependency-management plugin 1.1.7 |
| 빌드 | Gradle Wrapper 9.7.1, Kotlin DSL, 단일 root project |
| 서버/데이터 | Spring MVC, Spring Data JPA, Actuator, Jackson Kotlin |
| DB | PostgreSQL 전용. Flyway가 스키마를 소유하고 JPA는 validate만 한다 |
| 모듈 경계 | Spring Modulith 2.1.1 (BOM + `starter-core`, 테스트는 `starter-test`) |
| 식별자 | `uuid-creator` 6.1.1 (UUID v7) |
| 구조 검증 | Spring Modulith 경계 검증, ArchUnit 1.4.2 의존성 방향 가드 |
| 통합 테스트 | Testcontainers 2.0.5 PostgreSQL 18. `./gradlew test` 실행에 Docker가 필요하다 |
| 테스트 | Kotlin Test/JUnit 5, Spring Boot 테스트 starter, Fixture Monkey 1.2.3; 총 40개 (컨텍스트 1, 스키마 3, 모듈 구조 2, 의존성 방향 2, Money 8, 식별자 4, Order 5, OrderCommandFacade 7, API advice 8) |
| 로컬 하네스 | Python 3 표준 라이브러리 |

근거: [build.gradle.kts](../../build.gradle.kts), [Gradle Wrapper](../../gradle/wrapper/gradle-wrapper.properties), [애플리케이션 설정](../../src/main/resources/application.yaml).
애플리케이션 설정에 PostgreSQL 접속과 Flyway, JPA validate가 있다. 접속 값은 환경 변수로 주입하며 비밀값을
하드코딩하지 않는다. 운영 배포 설정과 커넥션 풀 튜닝은 아직 없다.
이 표는 빌드 선언을 확인한 결과이며, 의존성 다운로드·앱 실행 검증 결과를 의미하지 않는다.

## 계획과 구현 구분
- `com.resilientpos`에 애플리케이션 진입점이 있다. bounded context 7개(`store`, `terminal`, `order`, `payment`,
  `sync`, `reconciliation`, `fulfillment`)는 `package-info.java`의 `@ApplicationModule`로 선언했다. `order`에 첫 타입이 있고
  `store`·`terminal`은 ID 값 타입(`StoreId`, `TerminalId`)만 있으며 나머지 4개는 비어 있다.
  `ModuleStructureTest`가 모듈 인식과 경계 위반을 검증한다. 위반 주입 시 `Module 'order' depends on non-exposed type ...`
  메시지로 실패하는 것을 확인했다.
- Spring Modulith는 2.1.1로 도입했다. `spring-modulith-core` 2.1.1이 `spring-boot-autoconfigure` 4.1.1에 대해
  빌드되어 현재 Boot 버전과 세대가 맞는다. Event Publication Registry(`starter-jpa`)와 `starter-insight`는 아직 없다.
- Kafka, Redis, Kotlin JDSL, OpenTelemetry는 참고 문서의 설계 방향이며 현재 빌드 의존성에는 없다.
- `order` 모듈은 공개 계약(`OrderCommandFacade` 클래스, `CreateOrderCommand`·`CreateOrderResult`, `OrderId`·`ClientOrderId`,
  `OrderStatus`, `OrderEvent`·`OrderCreated`)과 `internal/domain`의 `Order`·`OrderLine`이 있다. `Order.create`가 생성 불변식
  (line 1개 이상, 수량 1 이상, 단가 0 이상, 통화 일치)을 검증하고 `Transition`을 돌려준다. 상태는 `OPEN` 하나다([order-domain-model](../spec/2026-09-16-order-domain-model.md)).
  `OrderCommandFacade`가 `@Transactional` 안에서 같은 `(terminalId, clientOrderId)` 재시도에 기존 결과를 돌려주고, 아니면
  `Order.create` → `OrderRepository.save` → `OrderCreated` 발행(plain Spring 이벤트, EPR 없음) 순으로 진행한다. JPA adapter는
  `internal/adapter/persistence/command`에 있고 `V002__order_create_order.sql`이 `t_order`·`t_order_line`을 만든다
  ([order-create-order-persistence](../spec/2026-09-16-order-create-order-persistence.md)).
- `foundation` 모듈에 `Money`, `IdentifierGenerator`/`UuidV7IdentifierGenerator`, 모델링 원시 타입 `DomainEvent`·`Transition`,
  도메인 규칙 위반을 알리는 `DomainException(code, message, kind)`과 `DomainErrorKind`(`INVALID`·`NOT_FOUND`·`CONFLICT`·`UNPROCESSABLE`)가 있다. 프레임워크 의존성은 없다.
  `Order`·`OrderLine`·`Money`는 규칙 위반을 `DomainException`으로만 던진다. `code`는 API 오류 응답과 로그 `errorCode`에 쓰는 안정적인
  식별자다. `kind`는 HTTP 숫자가 아닌 업무 의미이고 기본값은 `INVALID`다. 지금 코드 5개는 모두 `INVALID`다([foundation-domain-exception](../spec/2026-09-19-foundation-domain-exception.md)).
  `Money`는 통화의 표준 소수 자릿수를 canonical scale로 쓰고, scale 초과 입력과 통화 불일치 연산을 거절한다.
  나눗셈·퍼센트 할인·세금 계산은 없다. 반올림 정책은 매장 규칙이므로 `store` 정책과 checkout에서 다룬다.
- `platform` 모듈은 `IdentifierGenerator`(UUID v7)와 `Clock`(UTC) 빈을 등록하는 `PlatformConfiguration`과 엔티티 공통 컬럼
  (`version`·`created_at`·`updated_at`·`deleted_at`, Hibernate `@SoftDelete`)을 주는 `BaseEntity`를 가진다. `OrderEntity`·`OrderLineEntity`가 상속한다. 모듈은 9개다.
  HTTP 응답 공통 모양도 여기에 있다. `ApiResponseBodyAdvice`가 `com.resilientpos` Controller의 본문을 `ApiResponse`(`result`·`data`·`error`)로
  감싸고, `ApiControllerAdvice`가 Spring MVC 표준 예외와 처리하지 못한 예외를 `ErrorResponse`(`errorCode`·`message`)로 바꾼다.
  `ErrorType`은 HTTP 공통 오류 4개만 가진다. 응답 메시지는 고정 문구이고 예외 메시지는 로그에만 남는다. `DomainException`만 예외로,
  `kind`가 정한 상태(`INVALID` 400, `NOT_FOUND` 404, `CONFLICT` 409, `UNPROCESSABLE` 422)와 함께 예외의 `code`·`message`가 `error`에 그대로 나간다.
  매핑은 else 없는 `when`이라 `kind`를 추가하고 매핑을 빠뜨리면 컴파일되지 않는다. validation은 아직 없고 공개 endpoint도 없다([api-response-advice](../spec/2026-09-19-api-response-advice.md)).
- 제공된 요구사항 문서 7개는 [원문 목록](../references/README.md)에 보관했다. 요구사항 재정리와 원문 간 정합성 검토는 아직 수행하지 않았다.
- 애플리케이션 작업에는 [기존 설계 원칙](../../README.md#engineering-principles)을 참고하되 도입 완료 여부는 코드로 확인한다.
- 개발 기준은 [개발 규칙](development-workflow.md)이다. 공통 LLM 지침과 로컬 검사로 운영한다.

## 테스트 개발 방식
비즈니스·도메인·Controller는 [TDD 전략](testing-strategy.md)을 따른다. Repository 자체 테스트는 대상에서 제외한다.
TDD 스킬과 Claude/Codex 에이전트를 구성했다. 정본은 Obsidian 볼트에 있고 저장소는 `.claude`와 `.codex` 심볼릭 링크로 연결하며 둘 다 추적하지 않는다. 링크 생성은 `make bootstrap`이 담당한다. Fixture Monkey는 `fixture-monkey-starter-kotlin` 1.2.3으로 도입했고 `OrderFixtures`가 첫 사용처다. REST Docs는 첫 Controller PR에서 추가한다.

## Gradle 프로젝트 구조와 분할 조건
단일 Gradle 프로젝트를 유지한다. [개발 가이드 1절](../references/03_Development_Guide.md)은 패키지 경계가 안정되고 독립
build/test/dependency 또는 외부 contract artifact가 실제로 필요할 때만 subproject를 추가하라고 정한다. 현재 모듈 7개는
비어 있고 전체 테스트가 하나의 태스크로 끝난다. 지금 나누면 담길 코드보다 빌드 코드가 많아진다.

아래 중 하나라도 충족하면 별도 PR로 분할을 검토한다.
- `pos-client`처럼 별도 실행체가 생겨 domain이나 contract 코드를 공유해야 한다.
- `contracts/`의 OpenAPI·AsyncAPI 아티팩트를 외부에 배포해야 한다.
- 한 모듈에만 필요한 의존성(Kafka, Redis adapter 등)이 전체 classpath로 새는 것이 실제 문제가 된다.
- 빌드·테스트 시간이 길어져 모듈 단위 증분 빌드가 필요하다.

분할을 미뤄도 경계가 무너지지 않는다. Modulith 검증이 모듈 간 internal 참조와 순환을, ArchUnit 가드가 프레임워크
의존을 막는다. 그래서 나중 분할은 패키지 이름이 바뀌지 않는 파일 이동이다.

## 의존성 방향 가드
`DependencyDirectionTest`가 `foundation`의 프로덕션 코드에서 Spring, JPA, Servlet, Jackson, Kafka 의존을 막는다.
`package-info`의 Modulith 애너테이션은 코드가 아니라 모듈 메타데이터라 예외로 둔다. 같은 애너테이션이 실제 타입에
붙으면 위반으로 잡힌다.

도메인 레이어 규칙도 같은 파일에 있다. `..internal.domain..`의 프로덕션 코드는 위 프레임워크 패키지와 같은 모듈의
`..internal.adapter..`·`..internal.application..`에 의존하지 않는다. Modulith 검증은 모듈 사이만 보므로 모듈 안의 레이어
방향은 이 규칙이 지킨다. `Order`에 `@Component`를 임시로 붙이면 실패하는 것을 확인했다.

## 데이터베이스와 스키마 소유
PostgreSQL이 유일한 엔진이다. H2는 제거했다. 테스트가 운영과 다른 엔진에서 돌면 unique 제약, 타입, 잠금 동작처럼
정확성의 마지막 방어선을 검증하지 못한다.

스키마의 유일한 소유자는 `src/main/resources/db/migration`의 Flyway 마이그레이션이다. JPA는 `validate`라서
애플리케이션이 테이블을 만들거나 바꾸지 않는다. 마이그레이션이 실패하면 애플리케이션이 뜨지 않는다.

파일명은 `V(번호)__(소유 모듈)_(변경 내용).sql`이다. `V001__baseline.sql`은 의도적으로 비어 있고, 업무 테이블은 각
모듈이 후속 마이그레이션으로 추가한다. 첫 업무 마이그레이션은 `V002__order_create_order.sql`(`t_order`, `t_order_line`)이다.

통합 테스트는 Testcontainers로 실제 PostgreSQL 18을 띄운다. **`./gradlew test`에 Docker가 필요하다.** 컨테이너는
JVM당 한 번만 뜨고 Spring 컨텍스트 캐시와 수명을 맞춘다.
