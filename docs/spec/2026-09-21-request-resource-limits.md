# PR spec: 요청 하나가 자원을 무한정 잡지 않게 한다

## 목적
요청 하나가 쓸 수 있는 자원에 상한이 하나도 없다. 본문 크기, 주문 line 개수, 커넥션을 기다리는 시간, 쿼리와 트랜잭션이 도는 시간 모두 제한이 없어서, 요청 하나가 스레드와 커넥션을 무기한 잡을 수 있다. 각 지점에 상한을 두고 근거를 남긴다.

## 범위
소유는 둘로 나뉜다. line 개수는 `order`의 업무 규칙이고, 나머지는 `platform`이 소유하는 운영 설정이다.

**본문 크기.** Spring Boot 4가 Jackson의 스트림 제한을 설정으로 노출한다. 기본값은 문서 길이가 `-1`(무제한)이고 문자열 하나가 1억 자다. 숫자 길이만 1000자로 막혀 있는데, [money-input-boundary](2026-09-21-money-input-boundary.md)의 거절 비용이 그 값에 기대고 있다. 세 값을 모두 `application.yaml`에 적어 기본값이 바뀌어도 뒤집히지 않게 한다.

**line 개수.** `Order.create`가 line 개수를 보지 않는다. line 하나마다 `t_order_line` 행이 하나씩 들어가고 합계를 접는 비용도 개수를 따라간다. 본문 크기 상한이 간접적으로 막지만 그것은 전송 계층의 사실이고 업무 규칙이 아니다. 영수증 한 장에 담기는 품목 수의 상한을 도메인 불변식으로 둔다.

**기다리는 시간.** 커넥션을 얻는 데, 쿼리 하나가 도는 데, 트랜잭션 하나가 도는 데 모두 상한이 없다. DB가 느려지면 요청 스레드가 무기한 묶이고, 톰캣 스레드가 다 묶이면 상태 조회조차 응답하지 못한다. 잠금 대기를 3초로 제한한 [order-request-checkout-persistence](2026-09-21-order-request-checkout-persistence.md)의 선례를 따라 나머지 대기에도 상한을 준다.

**빈 비밀값.** `POSTGRES_PASSWORD`의 기본값이 빈 문자열이라, 환경 변수를 빠뜨린 채 뜬 인스턴스가 빈 비밀번호로 접속을 시도한다. 실패가 기동 시점에 드러나지 않고 첫 요청에서야 드러난다. 기본값을 없애 없으면 기동하지 못하게 한다.

계약: 새 `DomainException` code 하나가 응답 본문의 `error.errorCode`로 나간다.

| code | 의미 |
|---|---|
| `TOO_MANY_ORDER_LINES` | 주문 line 개수가 상한을 넘었다. `DomainErrorKind.INVALID`라 400이다. |

복구: line 개수는 요청을 나누면 된다. 본문 크기 초과와 타임아웃은 각각 400과 5xx이고, 타임아웃은 다른 시점에 성공할 수 있다.

관측성: 기존 advice의 로그를 그대로 쓴다. 로그에 어떤 요청인지 적는 일은 뒤따르는 PR이 한다.

## 제외
- 톰캣 스레드 풀·커넥션 풀 크기 조정. 배포 환경이 없어 정할 근거가 없다. 상한을 두는 것과 크기를 정하는 것은 다른 결정이다.
- 속도 제한(rate limit). 요청 **하나**의 상한이 이 PR의 범위이고, 요청의 **빈도**는 다루지 않는다.
- 로그에 주문·요청 식별자를 남기는 것.
- `quantity`의 상한. line 하나의 수량은 금액 상한이 간접적으로 막는다.
- profile 분리(local/prod). 지금 설정 파일이 하나뿐이고 나눌 대상이 생기면 그때 나눈다.

## 관련 문서
없음

## 변경 경로
- src/main/resources/application.yaml
- src/main/kotlin/com/resilientpos/order/internal/domain/Order.kt
- src/main/kotlin/com/resilientpos/order/OrderCommandFacade.kt
- src/test/kotlin/com/resilientpos/order/**
- src/test/kotlin/com/resilientpos/platform/**
- docs/**

## 인수 조건
1. line 개수가 상한을 넘는 주문 생성 요청은 400 `TOO_MANY_ORDER_LINES`이고, 상한과 같은 개수는 통과한다.
2. 상한을 넘는 본문은 읽는 단계에서 400이고 Facade에 닿지 않는다.
3. `application.yaml`에 적은 Jackson 제한 세 개가 실제 `ObjectMapper`에 반영된다. 라이브러리 기본값이 아니라 적은 값이 쓰인다.
4. 타임아웃 설정이 유효한 프로퍼티 이름이어서 실제로 적용된다. 오타가 있으면 조용히 무시되므로 값을 읽어 확인한다.
5. `POSTGRES_PASSWORD` 없이 뜨면 Hikari의 인증 실패로 기동하지 못한다. placeholder 미해석 자체는 예외가 아니다. 테스트는 Testcontainers가 접속 값을 주므로 영향받지 않는다.
6. 기존 테스트가 하나도 깨지지 않는다.

## 검증
- `./gradlew test` — 전부 통과.
- `python3 scripts/check_pr.py --base fix/money-input-boundary --worktree` — PASS. 이 PR은 앞선 금액 입구 PR 위에 쌓는다.
- 설정이 실제로 먹는지는 컨텍스트를 띄워 값을 읽어 확인한다. yaml에 적기만 하고 확인하지 않으면 오타를 잡지 못한다.

## Wiki 영향
- [현재 상태](../wiki/current-state.md) — "운영 배포 설정과 커넥션 풀 튜닝은 아직 없다"를 실제 상태로 고치고, 상한 값과 근거를 적는다.
- [변경 이력](../wiki/changes.md) — 항목 추가.
- 요구사항 변화: 주문 line 개수에 상한이 생긴다. 넘으면 400이다.

## 분할 계획
예상 3개 파일 + 테스트. 경고 한도 안이다. 로그 속성은 목표가 달라 뒤따르는 PR로 나눈다.

## TDD 검증 기록

설정 변경의 RED는 되돌려서 관측했다. 설정은 코드와 달리 "아직 안 썼다" 상태를 만들기 어려워서, 쓴 뒤에 다시 기본값·틀린 이름으로 바꿔 테스트가 실제로 빨개지는지 봤다. 테스트가 잡지 못하면 그 테스트는 아무것도 지키지 않는다.

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| line 개수가 상한을 넘으면 TOO_MANY_ORDER_LINES로 거절한다 | `./gradlew test --tests '…OrderTest'` — 상한 검사를 지운 상태에서 FAILED | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| line 개수가 상한과 같으면 통과한다 | 같은 명령 — 컴파일 실패(`MAX_LINES` 없음) | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| 상한을 넘는 본문은 읽는 단계에서 400으로 끊긴다 | `./gradlew test --tests '…OrderControllerTest'` — 두 상한을 Jackson 기본값으로 되돌린 상태에서 FAILED | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| 요청 본문을 읽는 양에 상한이 걸려 있다 | `./gradlew test --tests '…ResourceLimitConfigurationTest'` — `max-doc-length`(실재하지 않는 이름)로 FAILED | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| 커넥션을 기다리는 시간에 상한이 걸려 있다 | 같은 명령 — `connect-timeout`(실재하지 않는 이름)로 FAILED | 같은 명령 — 통과 | `./gradlew test` — 통과 |
| 쿼리와 트랜잭션이 도는 시간에 상한이 걸려 있다 | 같은 명령 — `query.timeoutt`로 FAILED | 같은 명령 — 통과 | `./gradlew test` — 통과 |

## 결과
`./gradlew test` 130개 통과다(직전 120 + 신규 10).

**이름의 kebab/camel 차이는 오타가 아니다.** 처음에는 `connection-timeout`을 `connectionTimeout`으로, `max-document-length`를 `maxDocumentLength`로 바꿔 RED를 보려 했는데 **테스트가 통과했다.** Spring의 relaxed binding이 둘을 같은 이름으로 읽기 때문이다. 실재하지 않는 이름(`connect-timeout`, `max-doc-length`)으로 바꾸고서야 빨개졌다.

그래서 이 테스트가 실제로 지키는 것이 무엇인지 분명해졌다. `spring.datasource.hikari.*`와 `spring.jackson.*`은 타입이 있는 설정 클래스에 바인딩되므로 이름을 틀리면 **기동에 실패하거나** relaxed binding이 흡수한다. 반면 `spring.jpa.properties.*`는 Hibernate에 그대로 넘기는 map이라 relaxed binding이 없고, `query.timeoutt`처럼 한 글자만 틀려도 **조용히 사라진다.** 위험이 가장 큰 쪽이 거기이고 테스트가 그것을 잡는다.

`MAX_LINES = 500`은 이 PR에서 유일하게 업무 판단이 들어간 값이다. 근거를 상수 주석에 적었고 실제 영업 데이터로 다시 볼 값이다.

본문 크기 상한은 두 개가 함께 막는다. 문자열 하나가 65536자를 넘거나 문서 전체가 1 MiB를 넘으면 읽는 단계에서 끊긴다. 둘 다 기본값으로 되돌리면 테스트가 빨개지는 것을 확인했다.

## 값을 고른 근거 확인

값을 적기만 하고 "합리적으로 보인다"로 넘기지 않기 위해 세 가지를 실제로 계산·확인했다.

**본문 상한이 정상 주문을 막지 않는가.** 최악의 정상 주문을 만들어 재 봤다. line 500개, `productCode` 64자(컬럼 상한), `productName` 255자를 전부 한글(UTF-8에서 3바이트), `unitPrice`는 정수부 15자리, `quantity`는 `Int` 최댓값이다. 결과는 **465,192바이트(0.44 MiB)로 1 MiB의 절반이 안 된다.** 가장 긴 문자열 필드도 255자라 65536자 상한과는 두 자릿수 차이다. 문서 상한에 걸리는 지점은 line 1100개 부근이므로, **먼저 걸리는 것은 언제나 line 개수 상한(500)이다.** 순서가 이 편이 낫다. 일반적인 파싱 실패 대신 `TOO_MANY_ORDER_LINES`라는 이유가 붙은 400이 나간다.

**이미 500개를 넘는 주문이 있으면 어떻게 되는가.** `Order.restore`는 이 검사를 거치지 않는다. 의도한 것이고 `restore`의 기존 계약("주문 단위 생성 불변식은 최초 `create` 때 검증됐으므로 다시 묻지 않는다")과 같다. 그런 주문이 있더라도 조회와 checkout이 그대로 동작한다. 상한을 더하면서 기존 데이터를 막아 세우지 않는다. 배포된 환경이 없어서 지금 그런 행은 없다.

**`socketTimeout: 30`이 Flyway를 막지 않는가.** 지금은 막지 않는다. 마이그레이션 4개가 모두 작고 Testcontainers에서 전부 통과한다. 다만 **이 값은 마이그레이션에도 걸린다.** Flyway가 같은 `DataSource`를 쓰기 때문이다. 30초를 넘길 수 있는 마이그레이션(큰 테이블의 인덱스 생성, 백필)을 더하는 PR은 이 값을 함께 봐야 한다. 코드로 막을 수 없는 종류의 제약이라 사실만 적는다.

`spring.transaction.default-timeout`은 `@Transactional`에 `timeout`을 명시하지 않은 메서드에만 적용된다. 지금 명시한 곳은 없으므로 `OrderCommandFacade`의 두 메서드와 `OrderPersistenceAdapter` 하나가 모두 30초를 쓴다.

## multi-review 반영

Claude 리뷰(85점)가 블로킹 없이 Important 8건을 냈다. 사실 주장부터 검증하고 받았다.

**받았다 — line 개수 검사가 커넥션을 잡은 뒤에 걸린다.** 지적대로였다. `Order.create`는 `repository.findByClientOrderId` **뒤**에 불리므로, 상한을 넘는 요청도 조회 문장을 먼저 내보내며 커넥션을 잡았다. 본문 상한이 허용하는 최소 크기 line은 1 MiB에 약 15,000개가 들어가므로 "본문 상한이 간접적으로 막는다"는 서술도 30배 느슨했다. 검사를 `Order.requireLineCountWithinLimit`으로 빼고 Facade가 저장소를 건드리기 전에 부르게 했다. Hibernate가 첫 문장에서 커넥션을 얻으므로 이제 상한을 넘는 요청은 커넥션을 아예 건드리지 않는다. 되돌려 보니 수정 전에는 `verifyNoInteractions(repository)`가 실패한다.

**받았다 — `restore`가 우회한다는 사실에 ponytail이 없다.** `requireStorableTotal`에는 있고 `MAX_LINES`에는 없는 비대칭이었다. 새 함수의 KDoc에 "line을 더하는 명령을 새로 만드는 PR도 이것을 부른다"와 "`restore`는 부르지 않는다"를 그 이유와 함께 적었다.

**받았다 — 정상 최대 주문이 본문 상한을 통과하는지 잠그는 테스트가 없다.** 여유가 두 배뿐이라 `MAX_LINES`를 올리거나 line DTO에 필드를 더하면 정상 주문이 전송 단계에서 끊기고, 그때 나가는 것은 `TOO_MANY_ORDER_LINES`가 아니라 이유를 알 수 없는 일반 400이다. `OrderApiIntegrationTest`에 line 500개 × 모든 필드 컬럼 상한 주문이 201로 저장되고 `t_order_line` 행이 500개인지 보는 테스트를 더했다.

그 테스트를 쓰다가 하나 알았다. **필드 폭이 아니라 합계 상한이 실질 한계다.** 처음에는 단가도 최댓값(15자리)으로 잡았는데 500개를 더하면 18자리가 되어 `INVALID_MONEY_AMOUNT`가 났다. 500 line을 담으려면 단가가 12자리 이하여야 한다. 본문 크기를 걱정할 일이 아니었다.

**받았다 — 본문 테스트가 두 상한을 동시에 위반해서 어느 쪽이 끊었는지 모른다.** 한글 40만 자는 문서 길이와 문자열 길이를 함께 넘었다. 둘로 갈랐다. 짧은 문자열만 가진 line 2만 개(문자열은 상한 안, 문서만 초과)와 ASCII 7만 자 한 개(문서는 70 KB로 상한 안, 문자열만 초과)다.

**받았다 — `POSTGRES_PASSWORD`의 실패 모드가 주석과 다르다.** 지적대로다. 해석되지 않은 placeholder는 `@ConfigurationProperties` 바인딩에서 **예외가 아니라 리터럴 문자열**이 된다(`Environment.getProperty`는 던지지만 바인딩은 던지지 않는다. 직접 확인했다). 기동이 멈추는 실제 지점은 Hikari의 인증 실패이고 로그도 `password authentication failed`다. **DB가 `trust` 인증이면 아무것도 막지 못한다.** 주석과 wiki를 실제 동작으로 고쳤다. 인수 조건 5를 "기동에 실패한다"에서 실제 실패 모드로 낮춰 적는다.

**받았다 — Flyway가 `socketTimeout` 아래에서 돈다, 쿼리 10초가 읽기에만 붙는다, 문자열 상한과 컬럼 상한의 간극, profile 미분리의 대가.** 코드로 막을 수 없거나 이 PR의 범위가 아닌 것들이라 [현재 상태](../wiki/current-state.md)의 알려진 빈틈에 전부 적었다.

**받지 않았다 — Jackson 기본 문자열 길이가 2천만이라는 지적.** 실측했다. `tools.jackson.core.StreamReadConstraints.defaults()`는 이 버전에서 `maxStringLength=100000000`, `maxNumberLength=1000`, `maxDocumentLength=-1`이다. 2천만은 jackson-core 2.15의 값이고 Jackson 3에서는 다르다. 문서의 "1억"이 맞다.

**받지 않았다 — 쿼리 타임아웃이 실제로 문장을 끊는지 `pg_sleep`으로 확인하자는 제안.** 테스트 하나가 10초를 쓴다. 이 PR이 지키려는 것은 "적어 둔 값이 실제 빈에 실린다"이고 그것은 이미 잡혀 있다. Hibernate가 쿼리 힌트와 트랜잭션 잔여 중 무엇을 쓰는지는 별도로 확인할 가치가 있어 알려진 빈틈에 적었다.

**받지 않았다 — `PGProperty` 상수와 비교해 드라이버 계약까지 잠그자는 제안.** `postgresql`이 `runtimeOnly`라 `testImplementation` 추가가 필요하고, 얻는 것(드라이버가 키 이름을 바꾸는 경우)에 비해 의존성 경계를 무르게 하는 대가가 크다.
