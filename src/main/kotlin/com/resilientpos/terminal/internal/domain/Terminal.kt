package com.resilientpos.terminal.internal.domain

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId

/**
 * 단말 aggregate 루트. 한 매장에 속하고 그 소속은 바뀌지 않는다(옮기려면 새 단말로 등록한다).
 * 불변 객체라 상태 전이는 새 인스턴스를 돌려준다. credential 이력 [TerminalCredential]은 이 aggregate의 자식이지만 루트가 목록으로 들지 않고 `terminalId`로 루트를 가리킨다. 회전은 루트 행을 잠근 채 한 커밋으로 바꾼다.
 */
class Terminal private constructor(
    val id: TerminalId,
    val storeId: StoreId,
    val status: TerminalStatus,
) {

    /** 비활성화한다. 이미 비활성이면 그대로다. 되돌리는 전이는 없다. */
    fun deactivate(): Terminal = if (status == TerminalStatus.INACTIVE) this else Terminal(id, storeId, TerminalStatus.INACTIVE)

    companion object {
        const val TERMINAL_NOT_FOUND = "TERMINAL_NOT_FOUND"
        const val TERMINAL_INACTIVE = "TERMINAL_INACTIVE"

        fun register(storeId: StoreId, ids: IdentifierGenerator): Terminal =
            Terminal(TerminalId(ids.next()), storeId, TerminalStatus.ACTIVE)

        /** 찾는 단말이 없다. 응답은 404다. */
        fun notFound(): DomainException =
            DomainException(TERMINAL_NOT_FOUND, "단말을 찾을 수 없습니다", DomainErrorKind.NOT_FOUND)

        /** 비활성 단말에는 credential을 새로 발급할 수 없다. 응답은 409다. */
        fun inactive(): DomainException =
            DomainException(TERMINAL_INACTIVE, "비활성 단말입니다", DomainErrorKind.CONFLICT)

        /** 저장된 단말을 그대로 재구성한다. persistence adapter의 `toDomain()`만 부른다. */
        fun restore(id: TerminalId, storeId: StoreId, status: TerminalStatus) = Terminal(id, storeId, status)
    }
}
