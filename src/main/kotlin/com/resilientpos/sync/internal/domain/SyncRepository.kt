package com.resilientpos.sync.internal.domain

import com.resilientpos.terminal.TerminalId
import java.util.UUID

/**
 * [Watermark]와 [SyncOperation]을 저장·조회하는 port. 시그니처에 aggregate와 ID 값 타입만 쓴다.
 * 둘은 같은 트랜잭션에서 함께 쓰이고, 그 정합성은 watermark 행 잠금이 지킨다.
 */
interface SyncRepository {

    /** 잠그지 않고 읽는다. 배치가 끝난 뒤 응답에 실을 값과 빈 배치의 조회다. */
    fun findWatermark(terminalId: TerminalId): Watermark?

    /**
     * watermark 행을 잠그고 읽는다. 항목마다 이 잠금 아래에서 판정하고 전진해 같은 단말의 배치가 직렬화된다.
     * 트랜잭션 안에서만 부른다. 행이 없으면 null이다. Facade는 배치를 시작할 때 [Watermark.initial]을 먼저 넣어 두므로 항목 처리에서는 늘 있다.
     */
    fun findWatermarkForUpdate(terminalId: TerminalId): Watermark?

    /** 행이 있으면 갱신하고 없으면 넣는다. 트랜잭션 안에서만 부른다. */
    fun saveWatermark(watermark: Watermark): Watermark

    fun findOperation(terminalId: TerminalId, operationId: UUID): SyncOperation?

    /**
     * 접수 기록을 넣는다. 트랜잭션 안에서만 부른다.
     * 같은 `(terminalId, operationId)`가 이미 있으면 DB unique 제약이 거절하고 이 호출을 담은 트랜잭션이 실패한다.
     */
    fun saveOperation(operation: SyncOperation): SyncOperation
}
