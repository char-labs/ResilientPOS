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
- src/test/kotlin/com/resilientpos/order/**
- src/test/kotlin/com/resilientpos/platform/**
- docs/**

## 인수 조건
1. line 개수가 상한을 넘는 주문 생성 요청은 400 `TOO_MANY_ORDER_LINES`이고, 상한과 같은 개수는 통과한다.
2. 상한을 넘는 본문은 읽는 단계에서 400이고 Facade에 닿지 않는다.
3. `application.yaml`에 적은 Jackson 제한 세 개가 실제 `ObjectMapper`에 반영된다. 라이브러리 기본값이 아니라 적은 값이 쓰인다.
4. 타임아웃 설정이 유효한 프로퍼티 이름이어서 실제로 적용된다. 오타가 있으면 조용히 무시되므로 값을 읽어 확인한다.
5. `POSTGRES_PASSWORD` 없이 뜨면 기동에 실패한다. 테스트는 Testcontainers가 접속 값을 주므로 영향받지 않는다.
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
`./gradlew test` 126개 통과다(직전 120 + 신규 6).

**이름의 kebab/camel 차이는 오타가 아니다.** 처음에는 `connection-timeout`을 `connectionTimeout`으로, `max-document-length`를 `maxDocumentLength`로 바꿔 RED를 보려 했는데 **테스트가 통과했다.** Spring의 relaxed binding이 둘을 같은 이름으로 읽기 때문이다. 실재하지 않는 이름(`connect-timeout`, `max-doc-length`)으로 바꾸고서야 빨개졌다.

그래서 이 테스트가 실제로 지키는 것이 무엇인지 분명해졌다. `spring.datasource.hikari.*`와 `spring.jackson.*`은 타입이 있는 설정 클래스에 바인딩되므로 이름을 틀리면 **기동에 실패하거나** relaxed binding이 흡수한다. 반면 `spring.jpa.properties.*`는 Hibernate에 그대로 넘기는 map이라 relaxed binding이 없고, `query.timeoutt`처럼 한 글자만 틀려도 **조용히 사라진다.** 위험이 가장 큰 쪽이 거기이고 테스트가 그것을 잡는다.

`MAX_LINES = 500`은 이 PR에서 유일하게 업무 판단이 들어간 값이다. 근거를 상수 주석에 적었고 실제 영업 데이터로 다시 볼 값이다.

본문 크기 상한은 두 개가 함께 막는다. 문자열 하나가 65536자를 넘거나 문서 전체가 1 MiB를 넘으면 읽는 단계에서 끊긴다. 둘 다 기본값으로 되돌리면 테스트가 빨개지는 것을 확인했다.
