package com.resilientpos.sync.internal.domain

import com.resilientpos.terminal.TerminalId

/**
 * 단말별로 서버가 확정한 마지막 순번(`lastAckedSequence`). sync aggregate 루트이고 단말당 하나다.
 * 단조 증가하며 gap을 건너뛰어 전진하지 않는다. 항목 처리는 이 행을 잠근 채 진행해 같은 단말의 배치가 직렬화된다.
 * 접수 기록([SyncOperation])은 이 루트와 같은 트랜잭션에서 함께 쓰이지만 수가 한정되지 않아 루트가 목록으로 들지 않는다.
 */
class Watermark private constructor(
    val terminalId: TerminalId,
    val lastAckedSequence: Long,
) {

    /** 단말이 보낸 순번을 다음 항목·이미 확정된 항목·gap 셋 중 하나로 가른다. */
    fun judge(sequence: Long): Position = when {
        sequence == lastAckedSequence + 1 -> Position.NEXT
        sequence <= lastAckedSequence -> Position.ACKED
        else -> Position.GAP
    }

    /** 다음 항목을 확정한 뒤의 watermark. 판정이 [Position.NEXT]일 때만 부른다. */
    fun advance(): Watermark = Watermark(terminalId, lastAckedSequence + 1)

    enum class Position { NEXT, ACKED, GAP }

    override fun equals(other: Any?) = other is Watermark && other.terminalId == terminalId
    override fun hashCode() = terminalId.hashCode()

    companion object {
        /** 아직 아무 순번도 확정하지 않은 단말. 첫 항목은 순번 1이다. */
        fun initial(terminalId: TerminalId): Watermark = Watermark(terminalId, 0)

        /** 저장된 watermark를 그대로 재구성한다. persistence adapter의 `toDomain()`만 부른다. */
        fun restore(terminalId: TerminalId, lastAckedSequence: Long): Watermark = Watermark(terminalId, lastAckedSequence)
    }
}
