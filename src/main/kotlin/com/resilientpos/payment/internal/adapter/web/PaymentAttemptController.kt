package com.resilientpos.payment.internal.adapter.web

import com.resilientpos.payment.PaymentCommand
import com.resilientpos.payment.PaymentId
import com.resilientpos.payment.PaymentManagement
import com.resilientpos.payment.PaymentQuery
import com.resilientpos.payment.PaymentQueryFacade
import com.resilientpos.terminal.AuthenticatedTerminal
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 결제 시도 공개 endpoint. 요청을 Query·Command로 바꿔 Facade를 부르고 결과를 응답으로 바꾸는 일만 한다.
 */
@RestController
class PaymentAttemptController(
    private val queryFacade: PaymentQueryFacade,
    private val management: PaymentManagement,
) {

    /** 주문이 없거나 다른 매장의 주문이어도 200과 빈 목록이다. */
    @GetMapping("/v1/orders/{orderId}/payment-attempts")
    fun findAttempts(@PathVariable orderId: UUID, terminal: AuthenticatedTerminal): List<PaymentAttemptResponse> =
        queryFacade.findAttempts(PaymentQuery.FindAttempts(orderId, terminal.storeId)).map(PaymentAttemptResponse::from)

    /** 첫 요청과 재요청이 같은 200과 같은 paymentId다. */
    @PostMapping("/v1/payment-attempts/{paymentId}/retry")
    fun retry(@PathVariable paymentId: UUID, terminal: AuthenticatedTerminal): RetryAuthorizationResponse =
        RetryAuthorizationResponse.from(management.retryAuthorization(PaymentCommand.RetryAuthorization(PaymentId(paymentId), terminal.storeId)))
}
