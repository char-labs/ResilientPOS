package com.resilientpos.sync.internal.adapter.persistence.command

import com.resilientpos.platform.BaseEntity
import com.resilientpos.sync.internal.domain.Watermark
import com.resilientpos.terminal.TerminalId
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/**
 * 역할: 단말별로 서버가 확정한 마지막 순번. 단말은 sync 응답의 이 값 다음부터 다시 보낸다.
 * 관계: `terminalId`가 PK이고 `SyncOperationEntity`와 같은 단말 ID로 묶인다. 조인·FK는 없다.
 * 흐름: 단말의 첫 sync 배치에서 0으로 생기고, 항목이 `APPLIED`·`REJECTED`로 확정될 때마다 그 항목의 트랜잭션 안에서 행을 잠근 채 +1이 된다. 전체 지도는 `docs/wiki/entity-map.md`.
 *
 * `Watermark`의 JPA 매핑. 단말 ID가 곧 PK다 — 단말당 행 하나이고 서버 식별자를 따로 만들 이유가 없다.
 * `@Version`은 두지 않는다. 항목 처리는 이 행을 잠그고 진행하며 잠금 없이 쓰는 경로가 없다.
 * 생성은 `from`, 변경은 `apply`만 한다(도메인 규칙 §5). `val`을 쓰지 않는 이유는 `OrderEntity`의 KDoc에 있다.
 */
@Entity
@Table(name = "t_sync_watermark")
class WatermarkEntity private constructor(
    terminalId: UUID,
    lastAckedSequence: Long,
) : BaseEntity() {

    @Id
    var terminalId: UUID = terminalId
        protected set

    var lastAckedSequence: Long = lastAckedSequence
        protected set

    fun apply(watermark: Watermark) {
        lastAckedSequence = watermark.lastAckedSequence
    }

    fun toDomain(): Watermark = Watermark.restore(TerminalId(terminalId), lastAckedSequence)

    companion object {
        fun from(watermark: Watermark): WatermarkEntity = WatermarkEntity(watermark.terminalId.value, watermark.lastAckedSequence)
    }
}
