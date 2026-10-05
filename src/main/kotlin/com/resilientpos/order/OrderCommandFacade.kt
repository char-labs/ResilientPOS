package com.resilientpos.order

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.order.internal.application.OrderEventPublisher
import com.resilientpos.order.internal.domain.Order
import com.resilientpos.order.internal.domain.OrderRepository
import com.resilientpos.platform.Lock
import com.resilientpos.platform.logger
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
            createInCallerTransaction(command)
        }
    }

    /**
     * 호출자 트랜잭션에 합류하는 생성. 잠그지 않는다 — 같은 키의 경합을 호출자가 스스로 줄 세우고 한 커밋으로 묶어야 할 때
     * (sync intake, watermark 행 잠금 아래) 부른다. 방어선은 [create]와 같은 unique `(terminal_id, client_order_id)`다.
     * 트랜잭션 안에서만 부른다. 밖에서 부르면 주문 행만 어댑터의 안전망 트랜잭션으로 커밋되어 호출자가 묶으려던 커밋이 갈라진다. 검사는 두지 않는다(계약은 이 KDoc이다).
     */
    fun createInCallerTransaction(command: OrderCommand.Create): OrderCommand.Create.Result {
        val existing = repository.findByClientOrderId(command.terminalId, command.clientOrderId)
        if (existing != null) {
            existing.verifySamePayload(command)
            return OrderCommand.Create.Result(existing.id, existing.status)
        }

        val transition = Order.create(command, identifierGenerator, clock.instant())
        val saved = repository.save(transition.aggregate)
        transition.events.forEach(eventPublisher::publish)
        return OrderCommand.Create.Result(saved.id, saved.status)
    }

    /**
     * 주문 행을 잠그고 읽어서 같은 주문의 요청이 겹치면 뒤 요청이 앞 커밋을 기다린다.
     * 이미 요청된 주문이면 도메인이 같은 주문과 빈 이벤트를 돌려주므로 같은 경로로 기존 결과를 돌려준다.
     */
    fun requestCheckout(command: OrderCommand.RequestCheckout): OrderCommand.RequestCheckout.Result = Tx.masterTx {
        val order = repository.findByIdForUpdate(command.orderId)
        // 다른 매장 주문의 존재를 드러내지 않으려고 없음으로 취급하고, 운영자가 구분할 수 있게 WARN으로 남긴다. 토큰은 남기지 않는다.
        if (order != null && order.storeId != command.storeId) {
            log.warn { "다른 매장의 주문 checkout 요청을 주문 없음으로 처리한다 orderId=${command.orderId.value} storeId=${command.storeId.value}" }
            return@masterTx confirmCheckout(null)
        }
        confirmCheckout(order)
    }

    /**
     * 단말이 아는 `clientOrderId`로 주문 행을 잠그고 같은 결제 요청을 한다. `orderId`를 먼저 읽고 다시 잠그지 않으려고
     * 키로 바로 잠근다. 호출자 트랜잭션이 있으면 합류한다(sync 항목 트랜잭션).
     * 단말이 본 합계(`expectedAmount`)가 확정 금액과 다르면 `CHECKOUT_AMOUNT_MISMATCH`로 거절한다. 이미 요청된 주문도 같다.
     */
    fun requestCheckout(command: OrderCommand.RequestCheckoutByClientOrderId): OrderCommand.RequestCheckout.Result =
        Tx.masterTx {
            confirmCheckout(
                order = repository.findByClientOrderIdForUpdate(command.terminalId, command.clientOrderId),
                verifyBeforeSave = { it.verifyCheckoutAmount(command.expectedAmount) },
            )
        }

    /** `verifyBeforeSave`는 전이한 주문을 저장·발행하기 전에 검사한다. 던지면 아무것도 저장하지 않고 이벤트도 내지 않는다. */
    private fun confirmCheckout(order: Order?, verifyBeforeSave: (Order) -> Unit = {}): OrderCommand.RequestCheckout.Result {
        order ?: throw DomainException(Order.ORDER_NOT_FOUND, "주문을 찾을 수 없습니다", DomainErrorKind.NOT_FOUND)

        val transition = order.requestCheckout(identifierGenerator, clock.instant())
        verifyBeforeSave(transition.aggregate)
        // 이벤트가 없는 전이는 바뀐 것이 없다(멱등). 저장하면 자식 SELECT 둘만 다시 나간다.
        val saved = if (transition.events.isEmpty()) transition.aggregate else repository.save(transition.aggregate)
        transition.events.forEach(eventPublisher::publish)
        val checkout = saved.checkouts.last()
        return OrderCommand.RequestCheckout.Result(saved.id, saved.status, checkout.checkoutNo, checkout.amount)
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
        private val log by logger()
        private const val CREATE_LOCK_PREFIX = "order:create-lock"

        fun createLockKey(terminalId: TerminalId, clientOrderId: ClientOrderId): String =
            "$CREATE_LOCK_PREFIX:${terminalId.value}:${clientOrderId.value}"
    }
}
