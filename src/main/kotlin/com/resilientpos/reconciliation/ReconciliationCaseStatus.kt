package com.resilientpos.reconciliation

/**
 * case 상태. System Design §5의 모델이다: `OPEN → INVESTIGATING → AUTO_REPAIRED 또는 MANUAL_REVIEW → RESOLVED`.
 * `OPEN`·`INVESTIGATING`은 case를 여는 한 번의 처리 안에서만 지나가 감사 기록에만 남고 저장된 case의 상태로는 관측되지 않는다.
 * `AUTO_REPAIRED`로 가는 전이는 아직 없다. 자동 조치 규칙을 만드는 PR이 더한다.
 */
enum class ReconciliationCaseStatus {
    OPEN,
    INVESTIGATING,
    AUTO_REPAIRED,
    MANUAL_REVIEW,
    RESOLVED,
}
