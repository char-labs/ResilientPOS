package com.resilientpos.order

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.order.internal.domain.Order
import com.resilientpos.order.internal.domain.OrderRepository
import java.time.Clock
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 주문 생성 use case의 진입점. 인터페이스를 따로 두지 않는 구체 클래스다. `@Transactional`을 소유하고
 * 조회 → `Order.create` → 저장 → 이벤트 발행 → 결과 변환 순서로 진행한다.
 */
@Service
class OrderCommandFacade(
    private val repository: OrderRepository,
    private val identifierGenerator: IdentifierGenerator,
    private val clock: Clock,
    private val eventPublisher: ApplicationEventPublisher,
) {

    @Transactional
    fun create(command: CreateOrderCommand): CreateOrderResult {
        // 가장 싼 거절을 가장 먼저 한다. line 개수는 command만 보면 알 수 있는데 `Order.create`까지 미루면
        // 조회 문장이 먼저 나가면서 커넥션을 잡는다. Hibernate는 첫 문장에서 커넥션을 얻으므로
        // 여기서 거절하면 상한을 넘는 요청은 커넥션을 아예 건드리지 않는다.
        Order.requireLineCountWithinLimit(command.lines.size)

        val existing = repository.findByClientOrderId(command.terminalId, command.clientOrderId)
        if (existing != null) {
            existing.verifySamePayload(command)
            return CreateOrderResult(existing.id, existing.status)
        }

        val transition = Order.create(command, identifierGenerator, clock.instant())
        val saved = repository.save(transition.aggregate)
        transition.events.forEach(eventPublisher::publishEvent)
        return CreateOrderResult(saved.id, saved.status)
    }

    /**
     * 주문 행을 잠그고 읽어서 같은 주문의 요청이 겹치면 뒤 요청이 앞 커밋을 기다린다.
     * 이미 요청된 주문이면 도메인이 같은 주문과 빈 이벤트를 돌려주므로 같은 경로로 기존 결과를 돌려준다.
     */
    @Transactional
    fun requestCheckout(command: RequestCheckoutCommand): RequestCheckoutResult {
        val order = repository.findByIdForUpdate(command.orderId)
            ?: throw DomainException(Order.ORDER_NOT_FOUND, "주문을 찾을 수 없습니다", DomainErrorKind.NOT_FOUND)

        val transition = order.requestCheckout(identifierGenerator, clock.instant())
        val saved = repository.save(transition.aggregate)
        transition.events.forEach(eventPublisher::publishEvent)
        val checkout = saved.checkouts.last()
        return RequestCheckoutResult(saved.id, saved.status, checkout.checkoutNo, checkout.amount)
    }
}
