package com.resilientpos.sync.internal.application

/**
 * 배치 항목 하나의 결과. HTTP 응답에 이름 그대로 나가는 저장 형식이다(도메인 모델 §3 용어집).
 */
enum class SyncItemStatus {
    /** 이번에 적용했고 순번을 확정했다. */
    APPLIED,

    /** 이미 확정한 operation의 같은 내용 재전송. 기록된 결과를 그대로 돌려준다. */
    DUPLICATE,

    /** 소유 컨텍스트가 도메인 규칙으로 거절했다. 순번은 확정된다. 단말은 이 항목을 버린다. */
    REJECTED,

    /** 앞 순번이 아직 확정되지 않았다. 아무것도 쓰지 않는다. 단말은 `lastAckedSequence` 다음부터 다시 보낸다. */
    GAP,

    /** 확정한 순번에 다른 내용이나 모르는 operation이 왔다. 아무것도 쓰지 않는다. 단말과 서버의 상태가 갈라졌다. */
    CONFLICT,
}
