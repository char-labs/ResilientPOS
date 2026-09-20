package com.resilientpos.order.internal.adapter.web

import com.resilientpos.order.OrderCommandFacade
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 주문 생성 공개 endpoint. 요청을 `CreateOrderCommand`로 바꿔 Facade를 부르고 결과를 응답으로 바꾸는 일만 한다.
 */
@RestController
@RequestMapping("/v1/orders")
class OrderController(private val facade: OrderCommandFacade) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: CreateOrderRequest): CreateOrderResponse =
        CreateOrderResponse.from(facade.create(request.toCommand()))
}
