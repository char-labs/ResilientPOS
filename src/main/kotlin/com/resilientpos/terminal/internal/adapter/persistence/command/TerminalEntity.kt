package com.resilientpos.terminal.internal.adapter.persistence.command

import com.resilientpos.platform.BaseEntity
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import com.resilientpos.terminal.internal.domain.Terminal
import com.resilientpos.terminal.internal.domain.TerminalStatus
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/**
 * 역할: 매장에 속한 단말 하나와 그 활성 상태. 소속 매장의 정본이다.
 * 관계: credential은 [TerminalCredentialEntity]가 `terminalId`로 가리킨다. 연관관계 매핑 없이 컬럼만 가진다.
 * 흐름: 등록이 만들고(`ACTIVE`), 비활성화가 상태만 바꾼다. 회전·비활성화는 이 행을 `for no key update`로 잠그고 시작한다. 전체 지도는 `docs/wiki/entity-map.md`.
 *
 * `Terminal`의 JPA 매핑. 행 잠금이 갱신을 직렬화해서 `@Version`을 두지 않는다. 전 필드는 `var`에 `protected set`이다(`OrderEntity`의 KDoc 참고).
 * `status`는 `EnumType.STRING`이다. 기본값인 ORDINAL이면 값을 끼워 넣을 때 저장된 행의 뜻이 바뀐다.
 */
@Entity
@Table(name = "t_terminal")
class TerminalEntity private constructor(
    id: UUID,
    storeId: UUID,
    status: TerminalStatus,
) : BaseEntity() {

    @Id
    var id: UUID = id
        protected set

    var storeId: UUID = storeId
        protected set

    @Enumerated(EnumType.STRING)
    var status: TerminalStatus = status
        protected set

    /** 바꿀 수 있는 것은 상태뿐이다. 어떤 상태로 바꿀 수 있는지는 aggregate의 `deactivate`가 정한다. */
    fun applyStatus(status: TerminalStatus) {
        this.status = status
    }

    fun toDomain(): Terminal = Terminal.restore(TerminalId(id), StoreId(storeId), status)

    companion object {
        fun from(terminal: Terminal): TerminalEntity =
            TerminalEntity(terminal.id.value, terminal.storeId.value, terminal.status)
    }
}
