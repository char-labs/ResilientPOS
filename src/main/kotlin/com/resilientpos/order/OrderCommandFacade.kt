package com.resilientpos.order

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.order.internal.application.OrderEventPublisher
import com.resilientpos.order.internal.domain.Order
import com.resilientpos.order.internal.domain.OrderRepository
import com.resilientpos.platform.Lock
import com.resilientpos.platform.Tx
import com.resilientpos.terminal.TerminalId
import java.time.Clock
import org.springframework.stereotype.Service

/**
 * 주문 명령 use case(생성·checkout 요청·결제 반영)의 진입점. 인터페이스를 따로 두지 않는 구체 클래스다.
 * 트랜잭션(`Tx.masterTx`)을 소유하고 조회 → 도메인 전이 → 저장 → 이벤트 발행 → 결과 변환 순서로 진행한다.
 */
@Service
class OrderCommandFacade(
    private val repository: OrderRepository,
    private val identifierGenerator: IdentifierGenerator,
    private val clock: Clock,
    private val eventPublisher: OrderEventPublisher,
) {

    /**
     * 같은 `(terminalId, clientOrderId)` 요청이 여러 인스턴스에 동시에 오면 [Lock]이 줄 세운다. 늦게 온 요청은 앞 요청의
     * 커밋을 기다렸다가 그 주문을 돌려받는다. 락은 최선 노력이고 방어선은 unique `(terminal_id, client_order_id)`다.
     * 락이 통하지 않으면 늦은 요청은 커밋에서 unique 위반으로 실패한다.
     */
    fun create(command: OrderCommand.Create): OrderCommand.Create.Result {
        // 가장 싼 거절을 가장 먼저 한다. line 개수는 command만 보면 알 수 있는데 뒤로 미루면
        // 락을 기다리고 조회 문장이 나가면서 커넥션을 잡는다. 여기서 거절하면 상한을 넘는 요청은
        // Redis도 커넥션도 건드리지 않는다.
        Order.requireLineCountWithinLimit(command.lines.size)

        return Lock.executeWithLock(createLockKey(command.terminalId, command.clientOrderId)) {
            val existing = repository.findByClientOrderId(command.terminalId, command.clientOrderId)
            if (existing != null) {
                existing.verifySamePayload(command)
                return@executeWithLock OrderCommand.Create.Result(existing.id, existing.status)
            }

            val transition = Order.create(command, identifierGenerator, clock.instant())
            val saved = repository.save(transition.aggregate)
            transition.events.forEach(eventPublisher::publish)
            OrderCommand.Create.Result(saved.id, saved.status)
        }
    }

    /**
     * 주문 행을 잠그고 읽어서 같은 주문의 요청이 겹치면 뒤 요청이 앞 커밋을 기다린다.
     * 이미 요청된 주문이면 도메인이 같은 주문과 빈 이벤트를 돌려주므로 같은 경로로 기존 결과를 돌려준다.
     */
    fun requestCheckout(command: OrderCommand.RequestCheckout): OrderCommand.RequestCheckout.Result = Tx.masterTx {
        val order = repository.findByIdForUpdate(command.orderId)
            ?: throw DomainException(Order.ORDER_NOT_FOUND, "주문을 찾을 수 없습니다", DomainErrorKind.NOT_FOUND)

        val transition = order.requestCheckout(identifierGenerator, clock.instant())
        val saved = repository.save(transition.aggregate)
        transition.events.forEach(eventPublisher::publish)
        val checkout = saved.checkouts.last()
        OrderCommand.RequestCheckout.Result(saved.id, saved.status, checkout.checkoutNo, checkout.amount)
    }

    /** 결제 승인을 확정값과 대조해 `PAID`로 전이한다. 다르면 `PAYMENT_APPROVAL_MISMATCH`로 실패한다. */
    fun markPaid(command: OrderCommand.MarkPaid): OrderCommand.MarkPaid.Result = Tx.masterTx {
        val order = repository.findByIdForUpdate(command.orderId)
            ?: throw DomainException(Order.ORDER_NOT_FOUND, "주문을 찾을 수 없습니다", DomainErrorKind.NOT_FOUND)

        val transition = order.markPaid(command.checkoutNo, command.amount, identifierGenerator, clock.instant())
        val saved = repository.save(transition.aggregate)
        transition.events.forEach(eventPublisher::publish)
        OrderCommand.MarkPaid.Result(saved.id, saved.status)
    }

    companion object {
        private const val CREATE_LOCK_PREFIX = "order:create-lock"

        fun createLockKey(terminalId: TerminalId, clientOrderId: ClientOrderId): String =
            "$CREATE_LOCK_PREFIX:${terminalId.value}:${clientOrderId.value}"
    }
}
