package com.resilientpos.fulfillment.internal.application

import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.fulfillment.internal.domain.Fulfillment
import com.resilientpos.fulfillment.internal.domain.FulfillmentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * fulfillment를 만드는 일은 전부 여기를 거친다. 인터페이스를 따로 두지 않는 구체 클래스다. 구현이 둘이 될 때 뽑는다.
 *
 * 모듈 루트가 아니라 `internal`에 둔다. `fulfillment`의 공개 계약은 소비하는 이벤트뿐이고(도메인 모델 §2),
 * 이 Facade는 주문이 `PAID`인지 묻지 않고 호출자를 믿는다. 루트에 두면 다른 모듈이 결제 안 된 주문의 fulfillment를 만들 수 있다.
 */
@Service
class FulfillmentCommandFacade(
    private val repository: FulfillmentRepository,
    private val identifierGenerator: IdentifierGenerator,
) {

    /**
     * 결제된 주문의 fulfillment를 만든다. 그 주문의 fulfillment가 있으면 그것을 돌려주고 없으면 만든다.
     * 재배달이나 resubmit은 기존 fulfillment를 돌려받는다.
     *
     * 읽은 뒤 넣기 전의 창은 `t_fulfillment`의 unique `(order_id)`가 닫는다. 동시 배달에서
     * 진 쪽은 커밋에서 unique 위반으로 실패한다.
     */
    @Transactional
    fun createFulfillment(command: FulfillmentCommand.Create): FulfillmentCommand.Create.Result {
        // ponytail: 같은 주문이 동시에 두 번 오면 진 쪽이 unique 위반으로 FAILED를 남긴다(fulfillment는 이미 있다).
        // 지금 그 창은 처리 중인 기록의 resubmit뿐이다. 재발행·Kafka가 생기면 insert ... on conflict (order_id) do nothing 뒤 조회로 바꾼다.
        val existing = repository.findByOrderId(command.orderId)
        if (existing != null) {
            return FulfillmentCommand.Create.Result(existing.id, created = false)
        }

        val fulfillment = Fulfillment.create(command.orderId, identifierGenerator)
        val saved = repository.save(fulfillment)
        return FulfillmentCommand.Create.Result(saved.id, created = true)
    }
}
