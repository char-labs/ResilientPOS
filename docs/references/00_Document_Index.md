# ResilientPOS 문서 맵

원본 `01_ResilientPOS_PRD_Backend_Requirements.md`를 독자가 목적에 맞게 읽을 수 있도록 아래 네 문서로 재구성했다. 세 본문은 서로 다른 관점을 다루며, 동일한 규칙을 충돌 없이 참조한다.

| 문서 | 독자 | 다루는 질문 |
|---|---|---|
| [01_Product_Planning_PRD.md](01_Product_Planning_PRD.md) | PO, 면접관, 팀 전체 | 어떤 문제를 누구를 위해 해결하며, 무엇을 성공으로 볼 것인가? |
| [02_System_Design.md](02_System_Design.md) | 아키텍트, 백엔드 개발자 | 어떤 경계·데이터·이벤트·운영 설계로 신뢰성을 만들고 MSA로 진화할 것인가? |
| [03_Development_Guide.md](03_Development_Guide.md) | 구현 담당자 | 어떤 코드 구조·규약·테스트·릴리스 순서로 구현할 것인가? |
| [04_External_Integration_Strategy.md](04_External_Integration_Strategy.md) | 백엔드/운영 담당자 | 어떤 외부 API를 왜 붙이고, timeout·circuit·rate limit·fallback을 어떻게 설계할 것인가? |
| [05_Domain_Development_Blueprint.md](05_Domain_Development_Blueprint.md) | 도메인 구현 담당자 | Event storming 결과를 어떤 aggregate·Facade/Service·CQRS·persistence 규약으로 코드화할 것인가? |

## 기준 결정 요약

- 서버는 우선 **Spring Modulith 기반 단일 deployable**로 운영한다. 도메인 경계와 데이터 소유권은 MSA 수준으로 강제하지만, 분산 배포는 실제 독립성 요구가 생긴 뒤에 한다.
- 서버/오프라인 POS가 모두 생성할 수 있는 **UUID v7**을 기본 ID로 사용한다. PostgreSQL에는 native `uuid`로 저장하며, terminal sequence는 별도의 동기화 순서 증거다.
- 돈은 `BigDecimal` 기반 `Money` value object와 PostgreSQL `NUMERIC(19,4)`를 사용한다. API에서는 decimal string만 전송하고, 통화·scale·rounding을 명시한다.
- 내부 비동기는 Spring Modulith Event Publication Registry(EPR)로 추적한다. 외부 소비자가 생긴 이벤트만 Kafka/outbox로 externalize하며, 모든 소비자는 at-least-once를 전제로 멱등 처리한다.
- 관측성은 P0이다. Metrics → Trace → Logs → Runbook → Replay/Reconciliation 경로를 기능 완료 조건으로 검증한다.

## 문서 변경 이력

| 날짜 | 버전 | 변경 |
|---|---|---|
| 2026-09-03 | v2.0 | 단일 PRD를 기획·시스템 설계·개발 가이드로 분리. MSA 진화, 계약, 데이터 소유권, 버전 호환성 원칙과 공식 레퍼런스를 보강. |
| 2026-09-03 | v2.1 | Toss Payments test, Slack webhook, Frankfurter FX 후보와 외부 API resilience 전략 문서 추가. |
| 2026-09-03 | v2.2 | Toss Payments 테스트 상점/키 발급, 콘솔 확인, server-side 승인, webhook 재검증, 테스트 오류 재현 절차 추가. |
| 2026-09-03 | v2.3 | 개인 개발자 전제로 test-only 범위를 명시하고, 사업자 계약·live MID/키·실거래를 문서 범위에서 제외. |
| 2026-09-03 | v2.4 | 도메인별 이벤트 스토밍 및 교차 검토 결과를 Facade/Service, CQRS, JPA/Kotlin JDSL, Redis/Kafka 구현 블루프린트로 추가. |
