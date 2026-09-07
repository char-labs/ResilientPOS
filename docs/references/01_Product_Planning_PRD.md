# ResilientPOS — Product Planning PRD

> Offline-First POS 주문·결제 동기화 플랫폼  
> 문서 버전: v2.0 · 기준일: 2026-09-03

## 1. 제품 정의

**한 줄 정의:** 인터넷이 끊겨도 주문을 계속 받고, 재연결 뒤 POS·서버·결제 상태가 중복 없이 수렴하도록 만드는 POS 플랫폼.

ResilientPOS는 카페·소형 F&B 매장의 다중 단말을 주 대상으로 한다. 실제 카드사 연동·ERP 전체를 만드는 것이 목적이 아니라, POS에서 가장 위험한 실패 조건(네트워크 단절, 응답 유실, 중복/역순 전달, 비동기 처리 실패)을 제품 기능으로 다루고 증명하는 것이 목적이다.

| 항목 | 정의 |
|---|---|
| 주 사용자 | 매장 직원, POS 단말, 운영자 |
| 핵심 가치 | 영업 연속성, 중복 결제 방지, 복구 가능한 정합성 |
| 권장 기술 방향 | Kotlin, Spring Boot, PostgreSQL, OpenTelemetry; Kafka/Kubernetes는 필요 단계에서 도입 |
| 포트폴리오 목표 | 기술 나열보다 장애·정합성·관측성을 설계하고 수치로 검증한 경험 |

## 2. 문제와 성공 기준

### 2.1 해결할 문제

- 인터넷이 불안정해도 매장은 주문을 멈출 수 없다.
- 결제 승인 뒤 응답이 유실되면 단말은 성공/실패를 확정할 수 없으며, 무심한 재시도는 중복 결제가 될 수 있다.
- 오프라인 큐는 중복·순서 뒤바뀜·누락 상태로 서버에 도착할 수 있다.
- DB 변경과 비동기 후속 작업 사이의 부분 실패는 주문·주방·영수증 상태를 다르게 만들 수 있다.
- 서버, 단말, PG 기록의 차이는 결국 탐지하고 안전하게 복구해야 한다.

### 2.2 제품 목표

| ID | 목표 | 성공 기준 |
|---|---|---|
| G-01 | 오프라인 주문 지속 | 인터넷 30분 단절 중 로컬 주문 생성 성공률 99.9% 이상 |
| G-02 | 중복 방지 | 10만 건 fault-injection에서 중복 주문/결제 side effect 0건 |
| G-03 | 최종 수렴 | 재연결 뒤 60초 이내 단말-서버 주문 상태 99.99% 수렴 |
| G-04 | 이벤트 신뢰성 | DB에 커밋된 업무 변경의 후속 이벤트 영구 유실 0건 |
| G-05 | 운영 가능성 | order/payment/trace ID 하나로 5분 안에 실패 경로 특정 |
| G-06 | 장애 탐지 | 정의한 Sev-1/2 시나리오의 95% 이상을 60초 이내 탐지하고 runbook 연결 |

### 2.3 비목표

- 실 카드사/PG 인증·정산·PCI 범위를 구현하지 않는다. 결정론적 mock/sandbox PG를 사용한다.
- ERP, 재고, CRM 전체를 만들지 않는다.
- 분산 환경의 "exactly once"를 주장하지 않는다. at-least-once 전달과 애플리케이션 멱등성·정합성 복구를 보장 범위로 한다.
- 초기부터 서비스별 독립 배포를 하지 않는다. 논리 경계를 검증한 뒤, 운영상 필요가 검증될 때만 분리한다.

## 3. 사용자와 핵심 시나리오

| 액터 | 목적 | 실패 시 영향 |
|---|---|---|
| 매장 직원 | 주문/결제를 빠르게 처리 | 영업 중단, 고객 이탈 |
| POS 단말 | 로컬 저장, 재전송, 상태 표시 | 주문 누락/중복/혼란 |
| 운영자 | 불일치 발견과 안전한 조치 | 수동 복구 비용, 금전 리스크 |
| PG adapter | 승인/조회/취소 결과 제공 | 금전 상태 불확실성 |
| 주방/영수증 처리 | 확정된 주문 후속 처리 | 누락/중복 처리 |

### 3.1 Happy path

온라인 주문 생성 → 결제 요청 → 승인 확정 → 주방/영수증 후속 처리 → 완료.

### 3.2 Offline recovery path

1. 단말은 `operationId`, `clientOrderId`, 단말별 단조 증가 `localSequence`를 부여하고 로컬 DB에 먼저 저장한다.
2. 재연결 후 단말은 batch sync를 보낸다.
3. 서버는 중복, 누락 구간, 충돌을 항목별로 판정하고 watermark를 응답한다.
4. 단말은 확인된 결과를 로컬 큐에 반영하고 누락 구간만 재전송한다.

### 3.3 Payment ambiguity path

PG 승인 뒤 HTTP 응답이 유실되면 결제를 `FAILED`로 단정하지 않는다. 동일 idempotency key로 결과를 조회하고, 결과가 불명확하면 `UNKNOWN`으로 유지한 뒤 PG 조회/reconciliation을 통해 `CONFIRMED` 또는 `FAILED`로 수렴한다.

## 4. 기능 범위와 우선순위

| Epic | 우선순위 | 포함 기능 | 완료 기준 |
|---|---|---|---|
| Offline Order | P0 | 로컬 ID/sequence, batch sync, duplicate/gap 탐지 | 30분 offline 시나리오 통과 |
| Payment Reliability | P0 | idempotency, 결제 상태 기계, UNKNOWN 해결 | 중복 승인 0건 |
| Event Reliability | P0 | durable publication, retry, idempotent consumer | 영구 유실 0건 |
| Reconciliation | P0 | terminal/server/PG 비교, repair case | 불일치 탐지·감사 가능 |
| Observability | P0 | trace, metrics, alert, runbook | 실패 주문 원인 5분 내 특정 |
| HA/Deployment | P1 | readiness, graceful shutdown, autoscaling | 배포 중 사용자 오류율 악화 없음 |

## 5. 사용자 관점 요구사항

| ID | 요구사항 | 수용 기준 |
|---|---|---|
| PR-01 | 단말은 오프라인에서도 주문을 안전하게 보관한다. | 앱 재시작 뒤에도 로컬 큐와 sequence가 유지된다. |
| PR-02 | sync 결과는 각 operation의 수락/중복/충돌/누락 사유를 보여준다. | 부분 성공이 숨겨지지 않는다. |
| PR-03 | 같은 결제 요청을 재전송해도 새 승인으로 이어지지 않는다. | 동일 키·동일 payload는 원 결과를 반환한다. |
| PR-04 | 결제 미확정은 사용자·운영자에게 명확히 구분되어 보인다. | timeout을 성공/실패로 거짓 표기하지 않는다. |
| PR-05 | 운영자는 불일치의 근거와 조치 이력을 확인한다. | 자동/수동 repair 모두 before/after, 사유, 실행자를 남긴다. |
| PR-06 | 장애 알림에는 진단과 복구의 시작점이 있다. | dashboard, trace, runbook, 조치 링크를 제공한다. |

## 6. 단계별 출시 계획

| Phase | 산출물 | 검증 시나리오 |
|---|---|---|
| 1. Reliable Core | 주문/결제, Fake PG, 상태 전이, idempotency, 기본 관측성 | 응답 유실 후 재시도에도 중복 승인 0 |
| 2. Offline Sync | POS simulator/client, local queue, batch sync, gap repair | 30분 offline 후 500건 수렴 |
| 3. Event-driven | durable event publication, 필요 시 Kafka/outbox, projection | producer/consumer kill 뒤 유실 0 |
| 4. Production-like | K8s, alerts, load/chaos, 운영 runbook | 무작위 장애의 MTTD/MTTR 측정 |
| 5. Evidence | benchmark, failure matrix, ADR, postmortem | 재현 가능한 결과를 README에 공개 |

## 7. 사업/운영 제약

- PostgreSQL이 주문·결제의 authoritative source다. Redis는 cache/rate limiting에만 사용하며 정확성의 전제조건이 될 수 없다.
- 카드 PAN, 민감 결제 토큰, 불필요한 PII는 저장·로그·trace에 넣지 않는다.
- replay/repair는 운영 권한, 사유, 실행 전후 상태, 감사 로그를 요구한다.
- 오프라인 동기화의 순서 증거는 UUID 시간순이 아니라 단말의 durable sequence다.

## 8. 리스크와 커뮤니케이션 기준

| 리스크 | 제품 영향 | 완화 |
|---|---|---|
| 과도한 MSA | 기능보다 배포/운영 보일러플레이트가 커짐 | 모듈러 모놀리스로 시작, 명시된 분리 조건 충족 시만 추출 |
| PG mock의 단순화 | 결제 신뢰성 설명이 약해짐 | timeout, delayed confirmation, 중복 callback, lookup을 계약으로 모델링 |
| exactly-once 과장 | 설계 신뢰도 저하 | 전달 보장·멱등성·복구 범위를 문서/데모에서 분리 설명 |
| 관측성 형식화 | 장애 원인 파악 불가 | 모든 chaos test에 alert→trace→runbook 합격 조건 포함 |

## 9. 포트폴리오 데모의 완성 정의

정상 주문 데모가 아니라 다음을 한 흐름으로 보여준다: 네트워크 단절 또는 PG 응답 유실 주입 → 알림 발생 → trace/log로 원인 확인 → idempotent retry 또는 reconciliation 조치 → mismatch 0과 SLO 정상화 확인.

기술 선택과 상세 시스템 설계는 [시스템 설계 문서](02_System_Design.md), 실제 구현 규약과 테스트는 [개발 가이드](03_Development_Guide.md)를 따른다.
