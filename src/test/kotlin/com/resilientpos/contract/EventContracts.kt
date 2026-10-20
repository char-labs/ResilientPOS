package com.resilientpos.contract

import com.resilientpos.foundation.Money
import com.resilientpos.payment.PaymentEvent
import com.resilientpos.payment.PaymentId
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import java.time.Instant
import java.util.Currency
import java.util.UUID

/** 외부로 나가는 이벤트 하나의 계약. `sample`은 마지막 fixture와 같은 값이다. */
data class EventContract(val type: Class<*>, val topic: String, val schemaVersion: Int, val sample: Any)

/**
 * `@Externalized` 이벤트 전부의 계약 목록. 새 이벤트를 더하면 여기 한 줄과
 * `src/test/resources/event-contract/토픽/1.json`을 더한다(`docs/wiki/event-contract.md`).
 * 값은 고정이다. 바꾸면 쓰기 검사가 마지막 fixture와 달라져 실패한다.
 */
object EventContracts {

    private val eventId = UUID.fromString("0199f6a0-0000-7000-8000-000000000001")
    private val occurredAt = Instant.parse("2026-10-20T01:02:03.456789Z")
    private val orderId = UUID.fromString("0199f6a0-0000-7000-8000-000000000002")
    private val storeId = StoreId(UUID.fromString("0199f6a0-0000-7000-8000-000000000003"))
    private val terminalId = TerminalId(UUID.fromString("0199f6a0-0000-7000-8000-000000000004"))
    private val paymentId = UUID.fromString("0199f6a0-0000-7000-8000-000000000005")
    private val amount = Money.of("4500", Currency.getInstance("KRW"))

    val all = listOf(
        EventContract(
            OrderPaymentRequested::class.java,
            OrderPaymentRequested.TOPIC,
            OrderPaymentRequested.SCHEMA_VERSION,
            OrderPaymentRequested(eventId, occurredAt, orderId, storeId, terminalId, checkoutNo = 1, amount = amount),
        ),
        EventContract(
            PaymentEvent.AttemptCreated::class.java,
            PaymentEvent.AttemptCreated.TOPIC,
            PaymentEvent.AttemptCreated.SCHEMA_VERSION,
            PaymentEvent.AttemptCreated(
                eventId, occurredAt, PaymentId(paymentId), orderId, storeId, terminalId,
                checkoutNo = 1, kind = PaymentAttemptKind.AUTHORIZATION, amount = amount,
            ),
        ),
        EventContract(
            PaymentApproved::class.java,
            PaymentApproved.TOPIC,
            PaymentApproved.SCHEMA_VERSION,
            PaymentApproved(
                eventId, occurredAt, paymentId, orderId, storeId, terminalId,
                checkoutNo = 1, kind = PaymentAttemptKind.AUTHORIZATION, amount = amount, providerReference = "FAKE-0199f6a0",
            ),
        ),
        EventContract(
            OrderPaid::class.java,
            OrderPaid.TOPIC,
            OrderPaid.SCHEMA_VERSION,
            OrderPaid(eventId, occurredAt, orderId, storeId, terminalId, checkoutNo = 1, amount = amount),
        ),
    )
}
