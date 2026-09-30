package com.resilientpos.sync.internal.domain

/**
 * 접수 기록의 판정. 둘 다 순번을 확정한 결정이다. gap·conflict·duplicate는 기록을 남기지 않으므로 여기에 없다.
 */
enum class SyncOperationStatus {
    /** 소유 컨텍스트가 명령을 적용했다. */
    APPLIED,

    /** 소유 컨텍스트가 도메인 규칙으로 거절했다. 같은 내용을 다시 보내도 같은 거절이다. */
    REJECTED,
}
