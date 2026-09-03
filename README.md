# ResilientPOS

ResilientPOS는 네트워크 단절과 부분 실패 속에서도 주문·결제 상태가 중복 없이 수렴하는 Offline-First POS 플랫폼입니다.

## Engineering Principles

- **정합성/복구 가능성/관측 가능성 우선**: 기능보다 데이터 정합성과 장애 복구, 운영 가시성을 우선합니다.
- **모듈 경계 준수 (Spring Modulith)**: `order`, `payment`, `sync`, `reconciliation`, `fulfillment` 경계를 지키며 다른 모듈의 internal 코드/DB를 직접 접근하지 않습니다.
- **역할 분리**:
  - Facade: use case + transaction 조합
  - Aggregate / Value Object / Policy: 상태 전이·불변식·금액 계산·검증 소유
  - Service: 도메인 객체 조율만 담당 (DTO 가공/규칙 중복 금지)
- **CQRS 규약**:
  - 쓰기: `*Command -> *Result`
  - 읽기: `*Query`/`*Criteria -> *View`
  - 저장소: JPA는 aggregate write, Kotlin JDSL은 DTO projection read
- **데이터 정본 원칙**:
  - 주문/결제 정합성 정본: PostgreSQL
  - Redis: cache, rate limit, 짧은 중복 요청 완화 용도만 사용
  - Redis를 payment idempotency/sync watermark의 정본으로 사용 금지
- **비동기 처리 원칙**:
  - at-least-once 전제
  - 모든 consumer 멱등 보장
  - Event Publication Registry 또는 outbox/inbox 사용
  - Kafka는 독립 consumer/replay/확장 요구가 실제로 생길 때만 도입
- **외부 결제 연동 원칙**:
  - 개인 개발자 test-only 환경으로 제한
  - timeout은 결제 실패 확정이 아님
  - `UNKNOWN -> provider query -> reconciliation` 경로로 수렴
  - 가짜 성공 fallback 금지
- **변경 시 필수 고려사항**:
  - trace, structured log, metric, alert, runbook
  - replay/reconciliation 경로
- **완료 조건**:
  - 정상 흐름 + 실패 시나리오 검증 포함
  - duplicate, timeout, offline sync gap, event replay, Redis/Kafka 장애 테스트 포함

## New Work Item Template

새 작업 생성 시 아래 항목을 반드시 채웁니다.

1. 문제 정의 (Problem)
2. Owning module (`order`/`payment`/`sync`/`reconciliation`/`fulfillment`)
3. Command / Query / Event 설계
4. 데이터 정합성 규칙 (idempotency, source of truth, 불변식)
5. 실패·재시도·복구 방식 (timeout, replay, reconciliation 경로 포함)
6. 관측 지표 (trace/log/metric/alert/runbook)
7. 테스트/완료 기준 (정상 + duplicate/timeout/offline sync gap/event replay/Redis-Kafka 장애)
