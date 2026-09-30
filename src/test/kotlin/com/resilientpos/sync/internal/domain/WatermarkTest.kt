package com.resilientpos.sync.internal.domain

import com.resilientpos.terminal.TerminalId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 순번 판정과 전진을 Spring 없이 고정한다. 단말이 보낸 순번을 watermark 하나로 다음·확정됨·gap 셋 중 하나로 가른다.
 */
class WatermarkTest {

    private val watermark = Watermark.restore(TerminalId(UUID.randomUUID()), 2)

    @Test
    fun `새 단말의 watermark는 0이고 첫 순번 1이 다음 항목이다`() {
        val initial = Watermark.initial(TerminalId(UUID.randomUUID()))

        assertEquals(0, initial.lastAckedSequence)
        assertEquals(Watermark.Position.NEXT, initial.judge(1))
        assertEquals(Watermark.Position.GAP, initial.judge(2))
    }

    @Test
    fun `확정된 순번 바로 다음이면 다음 항목이다`() {
        assertEquals(Watermark.Position.NEXT, watermark.judge(3))
    }

    @Test
    fun `확정된 순번 이하면 이미 확정된 항목이다`() {
        assertEquals(Watermark.Position.ACKED, watermark.judge(2))
        assertEquals(Watermark.Position.ACKED, watermark.judge(1))
    }

    @Test
    fun `확정된 순번보다 둘 이상 크면 gap이다`() {
        assertEquals(Watermark.Position.GAP, watermark.judge(4))
        assertEquals(Watermark.Position.GAP, watermark.judge(100))
    }

    @Test
    fun `전진은 하나씩이고 원본은 바뀌지 않는다`() {
        val advanced = watermark.advance()

        assertEquals(3, advanced.lastAckedSequence)
        assertEquals(2, watermark.lastAckedSequence)
        assertEquals(watermark.terminalId, advanced.terminalId)
    }
}
