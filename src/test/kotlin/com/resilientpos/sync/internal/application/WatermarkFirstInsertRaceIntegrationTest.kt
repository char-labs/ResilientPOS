package com.resilientpos.sync.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.sync.internal.adapter.persistence.command.WatermarkEntity
import com.resilientpos.sync.internal.adapter.persistence.command.WatermarkJpaRepository
import com.resilientpos.sync.internal.domain.SyncRepository
import com.resilientpos.sync.internal.domain.Watermark
import com.resilientpos.terminal.TerminalId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.support.TransactionTemplate

/**
 * 같은 단말의 첫 배치가 겹쳐 둘 다 "watermark 없음"을 본 뒤 진 쪽이 새 행을 넣는 순간을 만든다. Docker가 필요하다.
 * `@Version`이 없으면 Spring Data의 `save`가 `merge`로 가서 이긴 쪽이 이미 전진시킨 행을 0으로 덮어쓴다.
 * `SyncCommandFacade.ensureWatermark`는 이 자리의 PK 위반을 "이미 있다"로 삼키고 진행하는 것을 전제로 한다.
 */
class WatermarkFirstInsertRaceIntegrationTest(
    private val repository: SyncRepository,
    private val watermarks: WatermarkJpaRepository,
    private val transaction: TransactionTemplate,
) : PostgresIntegrationTest() {

    @Test
    fun `이미 전진한 watermark 행이 있는데 새 행을 넣으면 덮어쓰지 않고 PK 위반이다`() {
        val terminalId = TerminalId(UUID.randomUUID())
        transaction.executeWithoutResult {
            repository.saveWatermark(Watermark.initial(terminalId))
        }
        transaction.executeWithoutResult {
            repository.saveWatermark(Watermark.restore(terminalId, 7))
        }

        // 진 쪽은 findById가 null이던 시점에 이미 지나갔고, 새 엔티티를 그대로 save에 넘긴다.
        assertFailsWith<DataIntegrityViolationException> {
            transaction.executeWithoutResult {
                watermarks.save(WatermarkEntity.from(Watermark.initial(terminalId)))
            }
        }

        assertEquals(7, transaction.execute { repository.findWatermark(terminalId) }?.lastAckedSequence)
    }
}
