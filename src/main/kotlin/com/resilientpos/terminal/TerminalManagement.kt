package com.resilientpos.terminal

import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.platform.Tx
import com.resilientpos.terminal.internal.domain.Terminal
import com.resilientpos.terminal.internal.domain.TerminalCredential
import com.resilientpos.terminal.internal.domain.TerminalRepository
import java.time.Clock
import org.springframework.stereotype.Service

/**
 * `terminal` 모듈의 유일한 진입점. 단말 등록·인증·credential 회전·비활성화는 전부 여기를 거친다.
 * 인터페이스를 따로 두지 않는 구체 클래스다. 구현이 둘이 될 때 뽑는다.
 *
 * 회전과 비활성화는 단말 행을 잠그고 시작해 같은 단말에서 직렬화한다. Redis `Lock`은 함께 쓰지 않는다.
 * 인증 결과는 캐시하지 않는다. 폐기가 즉시 반영돼야 하고 Redis는 정본이 될 수 없다.
 */
@Service
class TerminalManagement(
    private val repository: TerminalRepository,
    private val identifierGenerator: IdentifierGenerator,
    private val clock: Clock,
) {

    /** 단말과 첫 credential을 한 트랜잭션에 넣고 평문 토큰을 돌려준다. 매장 존재는 확인하지 않는다. */
    fun register(command: TerminalCommand.Register): TerminalCommand.Register.Result = Tx.masterTx {
        val terminal = repository.save(Terminal.register(command.storeId, identifierGenerator))
        val issued = TerminalCredential.issue(terminal.id, identifierGenerator)
        repository.save(issued.credential)
        TerminalCommand.Register.Result(terminal.id, issued.token)
    }

    /** 유효한 토큰이면 단말과 매장을, 모르는 토큰·회전된 토큰·비활성 단말이면 null을 돌려준다. */
    fun authenticate(token: String): AuthenticatedTerminal? = Tx.readOnlyTx {
        repository.findActiveByTokenHash(TerminalCredential.hash(token))?.let { AuthenticatedTerminal(it.id, it.storeId) }
    }

    /** 유효 credential을 폐기하고 새로 발급한다. 옛 행은 이력으로 남는다. 비활성 단말은 거절한다. */
    fun rotateCredential(command: TerminalCommand.RotateCredential): TerminalCommand.RotateCredential.Result = Tx.masterTx {
        val terminal = repository.findByIdForUpdate(command.terminalId) ?: throw Terminal.notFound()
        terminal.requireActive()
        repository.findActiveCredential(terminal.id)?.let { repository.update(it.revoke(clock.instant())) }
        val issued = TerminalCredential.issue(terminal.id, identifierGenerator)
        repository.save(issued.credential)
        TerminalCommand.RotateCredential.Result(issued.token)
    }

    /** 단말을 비활성화한다. 이미 비활성이면 그대로다. */
    fun deactivate(command: TerminalCommand.Deactivate): Unit = Tx.masterTx {
        val terminal = repository.findByIdForUpdate(command.terminalId) ?: throw Terminal.notFound()
        repository.update(terminal.deactivate())
        Unit
    }
}
