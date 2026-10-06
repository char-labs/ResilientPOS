package com.resilientpos.terminal.internal.domain

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalCode
import com.resilientpos.terminal.TerminalId

/**
 * 단말 aggregate 루트. 한 매장에 속하고 그 소속은 바뀌지 않는다(옮기려면 새 단말로 등록한다).
 * 불변 객체라 상태 전이는 새 인스턴스를 돌려준다. credential 이력 [TerminalCredential]은 이 aggregate의 자식이지만 루트가 목록으로 들지 않고 `terminalId`로 루트를 가리킨다. 회전은 루트 행을 잠근 채 한 커밋으로 바꾼다.
 */
class Terminal private constructor(
    val id: TerminalId,
    val storeId: StoreId,
    val terminalCode: TerminalCode,
    val status: TerminalStatus,
) {

    /** 비활성화한다. 이미 비활성이면 그대로다. 되돌리는 전이는 없다. */
    fun deactivate(): Terminal = if (status == TerminalStatus.INACTIVE) this else Terminal(id, storeId, terminalCode, TerminalStatus.INACTIVE)

    /** credential을 새로 발급하기 전의 전제 조건. 비활성 단말은 [inactive]로 거절한다. */
    fun requireActive() {
        when (status) {
            TerminalStatus.ACTIVE -> Unit
            TerminalStatus.INACTIVE -> throw inactive()
        }
    }

    companion object {
        const val TERMINAL_NOT_FOUND = "TERMINAL_NOT_FOUND"
        const val TERMINAL_INACTIVE = "TERMINAL_INACTIVE"
        const val TERMINAL_CODE_DUPLICATED = "TERMINAL_CODE_DUPLICATED"

        fun register(storeId: StoreId, terminalCode: TerminalCode, ids: IdentifierGenerator): Terminal =
            Terminal(TerminalId(ids.next()), storeId, terminalCode, TerminalStatus.ACTIVE)

        /** 찾는 단말이 없다. 응답은 404다. */
        fun notFound(): DomainException =
            DomainException(TERMINAL_NOT_FOUND, "단말을 찾을 수 없습니다", DomainErrorKind.NOT_FOUND)

        /** 비활성 단말에는 credential을 새로 발급할 수 없다. 응답은 409다. */
        fun inactive(): DomainException =
            DomainException(TERMINAL_INACTIVE, "비활성 단말입니다", DomainErrorKind.CONFLICT)

        /** 같은 매장에 같은 코드의 단말이 이미 있다. 응답은 409다. */
        fun codeDuplicated(): DomainException =
            DomainException(TERMINAL_CODE_DUPLICATED, "같은 매장에 같은 단말 코드가 이미 있습니다", DomainErrorKind.CONFLICT)

        /** 저장된 단말을 그대로 재구성한다. persistence adapter의 `toDomain()`만 부른다. */
        fun restore(id: TerminalId, storeId: StoreId, terminalCode: TerminalCode, status: TerminalStatus) =
            Terminal(id, storeId, terminalCode, status)
    }
}
