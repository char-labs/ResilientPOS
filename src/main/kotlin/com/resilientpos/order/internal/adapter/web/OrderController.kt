package com.resilientpos.order.internal.adapter.web

import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderId
import com.resilientpos.order.OrderQuery
import com.resilientpos.order.OrderQueryFacade
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.AuthenticatedTerminal
import com.resilientpos.terminal.TerminalId
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 주문 공개 endpoint. 요청을 Command로 바꿔 Facade를 부르고 결과를 응답으로 바꾸는 일만 한다.
 */
@RestController
@RequestMapping("/v1/orders")
class OrderController(private val facade: OrderCommandFacade, private val queryFacade: OrderQueryFacade) {

    /** 인증 단말의 매장 기준이다. 다른 매장의 주문과 없는 주문은 같은 `404 ORDER_NOT_FOUND`다. */
    @GetMapping("/{orderId}")
    fun getOrder(@PathVariable orderId: UUID, terminal: AuthenticatedTerminal): OrderDetailResponse =
        OrderDetailResponse.from(queryFacade.getOrder(OrderQuery.GetOrder(OrderId(orderId), terminal.storeId)))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: CreateOrderRequest, terminal: AuthenticatedTerminal): CreateOrderResponse {
        // 본문 변환(400)보다 소유권 대조(403)가 먼저다. 남의 식별자로 온 요청에 변환 오류 모양을 보여 주지 않는다.
        terminal.requireSame(TerminalId(request.terminalId), StoreId(request.storeId))
        return CreateOrderResponse.from(facade.create(request.toCommand()))
    }

    /** 첫 요청과 재요청이 같은 200과 같은 본문이다. */
    @PostMapping("/{orderId}/checkout")
    fun requestCheckout(@PathVariable orderId: UUID, terminal: AuthenticatedTerminal): RequestCheckoutResponse =
        RequestCheckoutResponse.from(facade.requestCheckout(OrderCommand.RequestCheckout(OrderId(orderId), terminal.storeId, terminal.terminalId)))
}
