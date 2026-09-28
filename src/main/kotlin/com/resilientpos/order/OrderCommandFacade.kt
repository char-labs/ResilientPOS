package com.resilientpos.order

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.order.internal.application.OrderEventPublisher
import com.resilientpos.order.internal.domain.Order
import com.resilientpos.order.internal.domain.OrderRepository
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations

/**
 * 주문 명령 use case(생성·checkout 요청·결제 반영)의 진입점. 인터페이스를 따로 두지 않는 구체 클래스다.
 * 트랜잭션을 [TransactionOperations]로 열고 조회 → 도메인 전이 → 저장 → 이벤트 발행 → 결과 변환 순서로 진행한다.
 * 애너테이션 대신 블록으로 여는 이유는 발행에 넘길 트랜잭션 증거(`TransactionStatus`)를 얻기 위해서다.
 */
@Service
class OrderCommandFacade(
    private val repository: OrderRepository,
    private val identifierGenerator: IdentifierGenerator,
    private val clock: Clock,
    private val eventPublisher: OrderEventPublisher,
    private val transactions: TransactionOperations,
) {

    fun create(command: OrderCommand.Create): OrderCommand.Create.Result {
        // 가장 싼 거절을 가장 먼저 한다. line 개수는 command만 보면 알 수 있는데 `Order.create`까지 미루면
        // 조회 문장이 먼저 나가면서 커넥션을 잡는다. 트랜잭션을 열기 전에 거절하므로
        // 상한을 넘는 요청은 커넥션을 아예 건드리지 않는다.
        Order.requireLineCountWithinLimit(command.lines.size)

        return requireNotNull(
            transactions.execute { transaction ->
                val existing = repository.findByClientOrderId(command.terminalId, command.clientOrderId)
                if (existing != null) {
                    existing.verifySamePayload(command)
                    return@execute OrderCommand.Create.Result(existing.id, existing.status)
                }

                val transition = Order.create(command, identifierGenerator, clock.instant())
                val saved = repository.save(transition.aggregate)
                eventPublisher.publishAll(transaction, transition.events)
                OrderCommand.Create.Result(saved.id, saved.status)
            },
        )
    }

    /**
     * 주문 행을 잠그고 읽어서 같은 주문의 요청이 겹치면 뒤 요청이 앞 커밋을 기다린다.
     * 이미 요청된 주문이면 도메인이 같은 주문과 빈 이벤트를 돌려주므로 같은 경로로 기존 결과를 돌려준다.
     */
    fun requestCheckout(command: OrderCommand.RequestCheckout): OrderCommand.RequestCheckout.Result = requireNotNull(
        transactions.execute { transaction ->
            val order = repository.findByIdForUpdate(command.orderId)
                ?: throw DomainException(Order.ORDER_NOT_FOUND, "주문을 찾을 수 없습니다", DomainErrorKind.NOT_FOUND)

            val transition = order.requestCheckout(identifierGenerator, clock.instant())
            val saved = repository.save(transition.aggregate)
            eventPublisher.publishAll(transaction, transition.events)
            val checkout = saved.checkouts.last()
            OrderCommand.RequestCheckout.Result(saved.id, saved.status, checkout.checkoutNo, checkout.amount)
        },
    )

    /** 결제 승인을 확정값과 대조해 `PAID`로 전이한다. 다르면 `PAYMENT_APPROVAL_MISMATCH`로 실패한다. */
    fun markPaid(command: OrderCommand.MarkPaid): OrderCommand.MarkPaid.Result = requireNotNull(
        transactions.execute { transaction ->
            val order = repository.findByIdForUpdate(command.orderId)
                ?: throw DomainException(Order.ORDER_NOT_FOUND, "주문을 찾을 수 없습니다", DomainErrorKind.NOT_FOUND)

            val transition = order.markPaid(command.checkoutNo, command.amount, identifierGenerator, clock.instant())
            val saved = repository.save(transition.aggregate)
            eventPublisher.publishAll(transaction, transition.events)
            OrderCommand.MarkPaid.Result(saved.id, saved.status)
        },
    )
}
