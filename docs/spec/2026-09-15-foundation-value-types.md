# PR spec: foundation 값 타입 Money와 UUID v7 식별자

## 목적
주문·결제 구현 전에 금액과 식별자를 안전한 값 타입으로 고정한다. 금액 계산과 ID 생성은 틀려도 예외가 나지 않고 조용히 잘못된 결과를 남기므로, 도메인 코드가 생기기 전에 규칙과 테스트를 먼저 둔다.

## 범위
- `com.resilientpos.foundation` 모듈을 만들고 `@ApplicationModule`로 선언한다. 다른 모듈이 의존하는 공개 값 타입만 둔다.
- `Money`: `BigDecimal` 금액과 `java.util.Currency`를 가지는 불변 값 타입. 통화의 표준 소수 자릿수를 canonical scale로 쓰고,
  덧셈·뺄셈·정수 배수·크기 비교를 제공한다. 비교는 `compareTo` 연산자 함수로만 제공하고 `Comparable`은 구현하지 않는다.
- `IdentifierGenerator` 인터페이스와 `UuidV7IdentifierGenerator` 구현. `uuid-creator` 6.1.1을 사용한다.
- 소유 모듈: `foundation`. 계약: 위 두 타입의 public API. 불변식: 금액의 scale은 통화 기준으로 canonical하고, 통화가 다른 금액은 연산하지 않으며, 식별자는 UUID version 7이다.
- 복구·관측성: 해당 없다. 이 PR은 프레임워크·저장소·외부 호출이 없는 순수 값 타입이라 재시도·복구 경로와 metric이 생기지 않는다.

## 제외
- 나눗셈, 퍼센트 할인, 세금 계산. 반올림 정책은 매장 규칙이므로 `store` 정책과 `CheckoutPolicy`가 생길 때 다룬다.
- 금액의 음수 금지 같은 업무 불변식. `Money`는 음수를 표현할 수 있고, 허용 여부는 각 Aggregate가 판단한다.
- 통화별 예외 scale 테이블(`CurrencyScalePolicy`). `java.util.Currency`의 표준 자릿수로 충분한 동안 만들지 않는다.
- `Comparable<Money>` 구현. 통화가 다른 금액에는 순서가 없으므로 전순서를 전제하는 표준 정렬 API에 넘기지 않는다.
- JPA 매핑, DB 컬럼(`NUMERIC(19,4)`), API 직렬화 형식. 각각 persistence·web adapter가 생기는 PR에서 정한다.
- 단말(pos-client)의 UUID v7 생성기. 서버 구현을 wire 계약으로 삼지 않는다.

## 관련 문서
없음. 원문 근거는 [개발 가이드 3.1 IDs / 3.2 Money](../references/03_Development_Guide.md), 선행 PR은 [module-skeleton](2026-09-15-module-skeleton.md)이다.

## 변경 경로
- build.gradle.kts
- src/main/java/com/resilientpos/foundation/package-info.java
- src/main/kotlin/com/resilientpos/foundation/**
- src/test/kotlin/com/resilientpos/**
- docs/spec/2026-09-15-foundation-value-types.md
- docs/wiki/current-state.md
- docs/wiki/changes.md

## 인수 조건
1. `Money.of("1000", KRW)`처럼 통화 표준 자릿수에 맞는 입력은 canonical scale로 생성된다. KRW는 scale 0, USD는 scale 2다.
2. 통화 자릿수를 넘는 소수 입력은 `INVALID_MONEY_SCALE`로 거절한다. 조용히 반올림하지 않는다.
3. 통화가 다른 두 금액의 덧셈·뺄셈·비교는 `CURRENCY_MISMATCH`로 거절한다.
4. 같은 통화의 덧셈·뺄셈·정수 배수는 canonical scale을 유지한 결과를 돌려주고 원본을 바꾸지 않는다.
5. `UuidV7IdentifierGenerator.next()`는 version 7 UUID를 만들고, 연속 생성한 값의 시간 성분이 뒤로 가지 않으며, 중복이 없다.
6. `ModuleStructureTest`가 `foundation`을 포함한 모듈 8개를 인식하고 경계 검증이 통과한다.
7. `python3 scripts/check_pr.py --base feature/module-skeleton`이 통과한다.

### Checkpoint와 검증 명령
| Checkpoint | 검증 명령 | 의존 |
|---|---|---|
| money | `./gradlew test --tests 'com.resilientpos.foundation.MoneyTest'` | 없음 |
| identifier | `./gradlew test --tests 'com.resilientpos.foundation.UuidV7IdentifierGeneratorTest'` | 없음 |
| module-structure | `./gradlew test --tests 'com.resilientpos.ModuleStructureTest'` | money, identifier |

## 검증
```sh
./gradlew test
python3 scripts/check_pr.py --base feature/module-skeleton --worktree
```
- 정상: canonical scale 생성, 같은 통화 연산, version 7 식별자.
- 실패: scale 초과 입력, 통화 불일치 연산이 각각 지정한 메시지로 거절되는지 확인한다.

## Wiki 영향
- [현재 상태](../wiki/current-state.md): `foundation` 모듈 추가와 모듈 8개, `uuid-creator` 의존성을 반영한다.
- [변경 기록](../wiki/changes.md): 이 spec 링크와 결정 근거를 남긴다.

## 분할 계획
예상 규모: 구현 약 90줄, 테스트 약 120줄, 문서 약 110줄로 총 320줄 내외. 경고 한도(600줄) 안이다.
초과하면 `UuidV7IdentifierGenerator`를 후속 PR로 분리하고 이번 PR은 `Money`만 남긴다. 후속 순서는 Flyway·PostgreSQL 기반 → order `CreateOrder` 수직 절단이다.

## TDD 검증 기록
| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| `통화의 표준 자릿수를 canonical scale로 쓴다` | `./gradlew test --tests 'com.resilientpos.foundation.*'` — 최소 선언의 `TODO()`가 `NotImplementedError`를 던져 실패 (10개 전부) | `./gradlew test --tests 'com.resilientpos.foundation.*'` — BUILD SUCCESSFUL, MoneyTest 6개 통과 | `./gradlew test` — BUILD SUCCESSFUL, 13 tests |
| `통화 자릿수를 넘는 소수 입력을 거절한다` | `./gradlew test --tests 'com.resilientpos.foundation.*'` — 최소 선언의 `TODO()`가 `NotImplementedError`를 던져 실패 (10개 전부) | `./gradlew test --tests 'com.resilientpos.foundation.*'` — BUILD SUCCESSFUL | `./gradlew test` — BUILD SUCCESSFUL, 13 tests |
| `통화가 다른 금액은 더하지 않는다` / `비교하지 않는다` | `./gradlew test --tests 'com.resilientpos.foundation.*'` — 최소 선언의 `TODO()`가 `NotImplementedError`를 던져 실패 (10개 전부) | `./gradlew test --tests 'com.resilientpos.foundation.*'` — BUILD SUCCESSFUL | `./gradlew test` — BUILD SUCCESSFUL, 13 tests |
| `덧셈과 뺄셈과 정수 배수가 scale을 유지하고 원본을 바꾸지 않는다` / `같은 통화 금액의 크기를 비교한다` | `./gradlew test --tests 'com.resilientpos.foundation.*'` — 최소 선언의 `TODO()`가 `NotImplementedError`를 던져 실패 (10개 전부) | `./gradlew test --tests 'com.resilientpos.foundation.*'` — BUILD SUCCESSFUL | `./gradlew test` — BUILD SUCCESSFUL, 13 tests |
| `version 7 식별자를 만든다` / `시간 성분이 뒤로 가지 않는다` / `중복이 없다` / `시간 성분은 생성 시각을 담는다` | `./gradlew test --tests 'com.resilientpos.foundation.*'` — 최소 선언의 `TODO()`가 `NotImplementedError`를 던져 실패 (10개 전부) | `./gradlew test --tests 'com.resilientpos.foundation.*'` — BUILD SUCCESSFUL, UuidV7IdentifierGeneratorTest 4개 통과 | `./gradlew test` — BUILD SUCCESSFUL, 13 tests |
| `bounded context 7개와 foundation을 application module로 인식한다` | `./gradlew test --tests 'com.resilientpos.ModuleStructureTest'` — `expected: <[fulfillment, order, ...]> but was: <[foundation, fulfillment, order, ...]>`. foundation 패키지가 생기면서 모듈이 8개가 됨 | `package-info.java` 선언과 기대값 갱신 후 같은 명령 통과 | `./gradlew test` — BUILD SUCCESSFUL, 13 tests |

## 결과
`./gradlew test` BUILD SUCCESSFUL (13 tests). 인수 조건 7개를 모두 만족했다.

개발 중 정한 것 세 가지를 기록한다.

1. **private constructor 대신 `init` 불변식**: 개발 가이드 3.2 예시는 `data class Money private constructor`지만,
   Kotlin data class는 private 생성자여도 `copy()`가 공개라 우회 경로가 남는다. `init`에서 canonical scale을 검사하면
   생성자·`copy()`·`of()` 모든 경로에 같은 불변식이 걸리므로 생성자를 열어 두고 `init`으로 막았다.
2. **`CurrencyScalePolicy`를 만들지 않았다**: `java.util.Currency.getDefaultFractionDigits()`가 KRW 0, USD 2를 준다.
   매장·국가별 예외 반올림 규칙이 실제로 필요해질 때 `store` 모듈의 정책으로 만든다.
3. **UUID 단조 증가를 요구하지 않는다**: `UuidCreator.getTimeOrderedEpoch()`는 같은 밀리초 안에서 난수 부분의 순서를
   보장하지 않는다. 테스트는 상위 48비트 시간 성분이 뒤로 가지 않는 것까지만 검증한다. 동기화 순서의 증거는
   기획 문서대로 단말의 durable sequence이지 UUID의 시간순이 아니다.

`foundation`은 `@ApplicationModule`로 선언한 일반 모듈이다. 공개 타입만 가지므로 다른 모듈이 기본 규칙으로 의존할 수 있고,
open/shared 모듈 설정이 필요 없다.
