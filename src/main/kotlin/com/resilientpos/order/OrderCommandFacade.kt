package com.resilientpos.order

/**
 * 주문 생성 use case의 진입점. 구현은 영속화가 필요한 다음 PR이 맡는다.
 */
interface OrderCommandFacade {
    fun create(command: CreateOrderCommand): CreateOrderResult
}
