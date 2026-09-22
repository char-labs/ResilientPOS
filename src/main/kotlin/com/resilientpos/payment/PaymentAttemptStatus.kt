package com.resilientpos.payment

/**
 * 결제 시도의 정본 상태. provider가 말하는 상태를 그대로 옮기지 않고 이 네 값으로만 표현한다.
 *
 * 무엇을 요청했는지는 [PaymentAttemptKind]가, 그 답이 무엇이었는지는 이 값이 말한다. 두 축이라
 * 취소 요청에 새 상태를 더할 필요가 없다. 외부 연동 전략의 `CANCEL_PENDING`은
 * (`CANCELLATION`, [REQUESTED])이고, 취소가 승인되면 (`CANCELLATION`, [APPROVED])다.
 *
 * 원문 세 개가 서로 다른 이름을 쓴다. 용어집이 `PaymentConfirmed`를 금기어로 두고 `PaymentApproved`로
 * 정했으므로 그 줄기를 따랐다. `CONFIRMED`·`FAILED`·`PROCESSING`은 쓰지 않는다.
 */
enum class PaymentAttemptStatus {
    /** 시도를 만들었고 provider 결과를 아직 적용하지 않았다. */
    REQUESTED,

    /** provider가 승인했거나, 0원이라 provider를 부르지 않고 승인으로 기록했다. */
    APPROVED,

    /** provider가 거절했다. 결과를 받았다는 점에서 [UNKNOWN]과 다르다. */
    DECLINED,

    /**
     * provider 결과를 확인하지 못했다. **실패가 아니다.**
     * 새 승인 요청으로 재시도하지 않고 provider 조회와 reconciliation으로 수렴한다.
     */
    UNKNOWN,
}
