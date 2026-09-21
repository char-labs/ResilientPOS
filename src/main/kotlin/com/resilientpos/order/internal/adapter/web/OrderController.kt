package com.resilientpos.order.internal.adapter.web

import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.OrderId
import com.resilientpos.order.RequestCheckoutCommand
import java.util.UUID
import org.springframework.http.HttpStatus
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
class OrderController(private val facade: OrderCommandFacade) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: CreateOrderRequest): CreateOrderResponse =
        CreateOrderResponse.from(facade.create(request.toCommand()))

    /** 첫 요청과 재요청이 같은 200과 같은 본문이다. */
    @PostMapping("/{orderId}/checkout")
    fun requestCheckout(@PathVariable orderId: UUID): RequestCheckoutResponse =
        RequestCheckoutResponse.from(facade.requestCheckout(RequestCheckoutCommand(OrderId(orderId))))
}
