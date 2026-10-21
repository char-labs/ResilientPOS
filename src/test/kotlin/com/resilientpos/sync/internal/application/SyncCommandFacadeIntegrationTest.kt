package com.resilientpos.sync.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.order.ClientOrderId
import com.resilientpos.order.OrderCommand
import com.resilientpos.order.OrderFixtures
import com.resilientpos.order.OrderCommandFacade
import com.resilientpos.order.internal.domain.Order
import com.resilientpos.order.internal.domain.OrderLine
import com.resilientpos.store.StoreId
import com.resilientpos.sync.internal.domain.CheckoutPayload
import com.resilientpos.sync.internal.domain.OperationPayload
import com.resilientpos.sync.internal.domain.OrderPayload
import com.resilientpos.terminal.TerminalId
import io.micrometer.core.instrument.MeterRegistry
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject

/**
 * 배치 항목의 판정(적용·중복·거절·gap·충돌)과 watermark 전진이 실제 `order` Facade와 PostgreSQL까지 이어지는지 본다. Docker가 필요하다.
 * 커밋된 행을 JDBC로 세어 확인한다. 항목 트랜잭션이 하나인지는 Facade 결과가 아니라 롤백 뒤 남은 행으로 본다.
 */
class SyncCommandFacadeIntegrationTest(
    @Autowired private val facade: SyncCommandFacade,
    @Autowired private val orders: OrderCommandFacade,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val meterRegistry: MeterRegistry,
) : PostgresIntegrationTest() {

    private val terminalId = TerminalId(UUID.randomUUID())

    private fun payload(command: OrderCommand.Create = OrderFixtures.createOrderCommand(terminalId = terminalId)) = OrderPayload(
        storeId = command.storeId,
        clientOrderId = command.clientOrderId,
        currency = command.currency.currencyCode,
        lines = command.lines.map { OrderPayload.Line(it.productCode, it.productName, it.unitPrice.amount, it.quantity.toBigDecimal()) },
    )

    private fun operation(sequence: Long, payload: OperationPayload = payload(), operationId: UUID = UUID.randomUUID(), createdAt: Instant? = null) =
        SyncCommand.ReceiveBatch.Operation(operationId, sequence, payload, createdAt)

    // OrderFixtures 기본 line의 합계(1001 x 2 + 500 x 3)다. 단말이 본 합계로 싣는다.
    private fun checkout(
        sequence: Long,
        clientOrderId: ClientOrderId,
        operationId: UUID = UUID.randomUUID(),
        amount: BigDecimal = BigDecimal("3502"),
    ) = operation(sequence, CheckoutPayload(clientOrderId, amount), operationId)

    private fun orderStatus(clientOrderId: ClientOrderId): String? =
        jdbcTemplate.query("select status from t_order where terminal_id = ? and client_order_id = ?", { rs, _ -> rs.getString(1) }, terminalId.value, clientOrderId.value).firstOrNull()

    private fun checkoutRows(): Long =
        checkNotNull(
            jdbcTemplate.queryForObject<Long>(
                "select count(*) from t_order_checkout where order_id in (select id from t_order where terminal_id = ?)", terminalId.value,
            ),
        )

    private fun kinds(): List<String> =
        jdbcTemplate.queryForList("select kind from t_sync_operation where terminal_id = ? order by sequence", String::class.java, terminalId.value).filterNotNull()

    /** 주문의 `OrderPaymentRequested` 발행 기록 수. 완료된 기록도 행이 남으므로 발행됐는지를 센다. */
    private fun paymentRequestedPublications(clientOrderId: ClientOrderId): Long {
        val orderId = jdbcTemplate.queryForObject<UUID>("select id from t_order where client_order_id = ?", clientOrderId.value)
        return checkNotNull(
            jdbcTemplate.queryForObject<Long>(
                "select count(*) from event_publication where serialized_event like ? and event_type like '%OrderPaymentRequested'", "%$orderId%",
            ),
        )
    }

    private fun receive(vararg operations: SyncCommand.ReceiveBatch.Operation) =
        facade.receiveBatch(SyncCommand.ReceiveBatch(terminalId, OrderFixtures.storeId, operations.toList()))

    private fun orderRows(): Long =
        checkNotNull(jdbcTemplate.queryForObject<Long>("select count(*) from t_order where terminal_id = ?", terminalId.value))

    private fun operationRows(): Long =
        checkNotNull(jdbcTemplate.queryForObject<Long>("select count(*) from t_sync_operation where terminal_id = ?", terminalId.value))

    private fun storedWatermark(): Long? =
        jdbcTemplate.query("select last_acked_sequence from t_sync_watermark where terminal_id = ?", { rs, _ -> rs.getLong(1) }, terminalId.value).firstOrNull()

    @Test
    fun `순번대로 온 배치는 모두 적용되고 watermark가 마지막 순번이 된다`() {
        val result = receive(operation(1), operation(2), operation(3))

        assertEquals(listOf(SyncItemStatus.APPLIED, SyncItemStatus.APPLIED, SyncItemStatus.APPLIED), result.items.map { it.status })
        result.items.forEach { assertNotNull(it.orderId) }
        assertEquals(3, result.lastAckedSequence)
        assertEquals(3, storedWatermark())
        assertEquals(3, orderRows())
        assertEquals(3, operationRows())
    }

    @Test
    fun `같은 배치를 다시 보내면 모두 중복이고 같은 orderId를 받으며 행이 늘지 않는다`() {
        val operations = arrayOf(operation(1), operation(2))
        val first = receive(*operations)

        val resent = receive(*operations)

        assertEquals(listOf(SyncItemStatus.DUPLICATE, SyncItemStatus.DUPLICATE), resent.items.map { it.status })
        assertEquals(first.items.map { it.orderId }, resent.items.map { it.orderId })
        assertEquals(2, resent.lastAckedSequence)
        assertEquals(2, orderRows())
        assertEquals(2, operationRows())
    }

    @Test
    fun `확정된 operation에 다른 내용이 오거나 모르는 operation이 확정된 순번으로 오면 충돌이고 아무것도 바뀌지 않는다`() {
        val order = payload()
        val applied = operation(1, order)
        receive(applied)
        val changed = order.copy(lines = order.lines.take(1) + order.lines.last().copy(quantity = BigDecimal(9)))

        val result = receive(
            operation(1, changed, operationId = applied.operationId),
            operation(1, payload()),
        )

        assertEquals(listOf(SyncItemStatus.CONFLICT, SyncItemStatus.CONFLICT), result.items.map { it.status })
        assertEquals(1, result.lastAckedSequence)
        assertEquals(1, storedWatermark())
        assertEquals(1, orderRows())
        assertEquals(1, operationRows())
    }

    @Test
    fun `이미 기록된 operation이 다른 순번으로 오면 확정된 순번이든 다음 순번이든 충돌이고 아무것도 바뀌지 않는다`() {
        val order = payload()
        val applied = operation(1, order)
        receive(applied)

        val result = receive(
            operation(2, order, operationId = applied.operationId),
            operation(2, payload()),
        )

        // 첫 항목은 다음 순번(2)인데 operationId가 이미 기록돼 CONFLICT다. 둘째 항목은 순번 2가 아직 비어 있으므로 APPLIED다.
        assertEquals(listOf(SyncItemStatus.CONFLICT, SyncItemStatus.APPLIED), result.items.map { it.status })
        assertEquals(2, result.lastAckedSequence)
        assertEquals(2, orderRows())

        // 같은 내용이라도 원래(1)와 다른 확정된 순번으로 오면 DUPLICATE가 아니라 CONFLICT다.
        val acked = receive(operation(2, order, operationId = applied.operationId))

        assertEquals(SyncItemStatus.CONFLICT, acked.items.single().status)
        assertEquals(2, storedWatermark())
        assertEquals(2, operationRows())
    }


    @Test
    fun `순번이 비면 그 항목은 gap이고 watermark는 멈추며 빈 순번이 오면 이어서 적용된다`() {
        val fourth = operation(4)

        val first = receive(operation(1), operation(2), fourth)

        assertEquals(listOf(SyncItemStatus.APPLIED, SyncItemStatus.APPLIED, SyncItemStatus.GAP), first.items.map { it.status })
        assertEquals(2, first.lastAckedSequence)
        assertEquals(2, storedWatermark())
        assertEquals(2, orderRows())

        val second = receive(operation(3), fourth)

        assertEquals(listOf(SyncItemStatus.APPLIED, SyncItemStatus.APPLIED), second.items.map { it.status })
        assertEquals(4, second.lastAckedSequence)
        assertEquals(4, storedWatermark())
        assertEquals(4, orderRows())
    }

    @Test
    fun `금액 규칙에 어긋나는 항목은 배치 오류가 아니라 거절이고 다음 항목은 적용된다`() {
        // KRW는 소수 자릿수가 0이라 4500.5는 Money가 INVALID_MONEY_SCALE로 거절한다. 이 거절이 Controller에서 나면 배치 전체가
        // 400이 되어 단말이 같은 봉투를 영원히 다시 보낸다. 항목 안에서 나야 REJECTED로 확정되고 단말이 버린다.
        val badScale = payload().let { it.copy(lines = listOf(it.lines.first().copy(unitPrice = BigDecimal("4500.5")))) }

        val result = receive(operation(1, badScale), operation(2))

        assertEquals(listOf(SyncItemStatus.REJECTED, SyncItemStatus.APPLIED), result.items.map { it.status })
        assertEquals("INVALID_MONEY_SCALE", result.items[0].errorCode)
        assertEquals(2, storedWatermark())
        assertEquals(1, orderRows())
    }

    @Test
    fun `같은 clientOrderId의 주문이 다른 내용으로 이미 있으면 항목은 IDEMPOTENCY_CONFLICT 거절이고 순번은 확정된다`() {
        // 기존 주문 조회 뒤에 나는 거절이라 첫 트랜잭션이 읽은 것을 롤백하는 경로를 지난다.
        val command = OrderFixtures.createOrderCommand(terminalId = terminalId)
        orders.create(command)
        val differentContent = payload(command).let { it.copy(lines = listOf(it.lines.first().copy(quantity = BigDecimal(7)))) }

        val result = receive(operation(1, differentContent))

        assertEquals(SyncItemStatus.REJECTED, result.items.single().status)
        assertEquals(Order.IDEMPOTENCY_CONFLICT, result.items.single().errorCode)
        assertEquals(1, storedWatermark())
        assertEquals(1, orderRows())
    }

    @Test
    fun `같은 단말의 같은 배치가 동시에 오면 항목 단위로 직렬화되어 주문과 기록이 항목 수만큼만 생긴다`() {
        val batch = arrayOf(operation(1), operation(2), operation(3))
        val executor = Executors.newFixedThreadPool(2)

        try {
            val results = List(2) { executor.submit<SyncCommand.ReceiveBatch.Result> { receive(*batch) } }.map { it.get(30, TimeUnit.SECONDS) }

            results.flatMap { it.items }.forEach { assert(it.status == SyncItemStatus.APPLIED || it.status == SyncItemStatus.DUPLICATE) { it.toString() } }
            assertEquals(3, storedWatermark())
            assertEquals(3, orderRows())
            assertEquals(3, operationRows())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `주문이 도메인 규칙으로 거절되면 항목은 거절이고 주문 행 없이 순번만 확정되며 재전송은 같은 거절의 중복이다`() {
        val blankName = payload().let { it.copy(lines = listOf(OrderPayload.Line("SKU-1", " ", BigDecimal("1000"), BigDecimal.ONE))) }
        val rejected = operation(1, blankName)

        val first = receive(rejected, operation(2))

        assertEquals(listOf(SyncItemStatus.REJECTED, SyncItemStatus.APPLIED), first.items.map { it.status })
        assertEquals(OrderLine.BLANK_PRODUCT_NAME, first.items[0].errorCode)
        assertNull(first.items[0].orderId)
        assertEquals(2, first.lastAckedSequence)
        assertEquals(1, orderRows())
        assertEquals(2, operationRows())

        val resent = receive(rejected)

        assertEquals(SyncItemStatus.DUPLICATE, resent.items.single().status)
        assertEquals(OrderLine.BLANK_PRODUCT_NAME, resent.items.single().errorCode)
        assertEquals(2, operationRows())
    }

    @Test
    fun `컬럼 길이를 넘는 상품명은 DB 오류가 아니라 거절이고 순번은 확정되며 다음 항목은 적용된다`() {
        val tooLong = payload().let { it.copy(lines = listOf(it.lines.first().copy(productName = "가".repeat(256)))) }

        val result = receive(operation(1, tooLong), operation(2))

        assertEquals(listOf(SyncItemStatus.REJECTED, SyncItemStatus.APPLIED), result.items.map { it.status })
        assertEquals(OrderLine.PRODUCT_NAME_TOO_LONG, result.items[0].errorCode)
        assertEquals(2, result.lastAckedSequence)
        assertEquals(2, storedWatermark())
        assertEquals(1, orderRows())
    }

    @Test
    fun `컬럼 길이를 넘는 상품 코드는 DB 오류가 아니라 거절이고 순번은 확정되며 다음 항목은 적용된다`() {
        val tooLong = payload().let { it.copy(lines = listOf(it.lines.first().copy(productCode = "S".repeat(65)))) }

        val result = receive(operation(1, tooLong), operation(2))

        assertEquals(listOf(SyncItemStatus.REJECTED, SyncItemStatus.APPLIED), result.items.map { it.status })
        assertEquals(OrderLine.PRODUCT_CODE_TOO_LONG, result.items[0].errorCode)
        assertEquals(2, storedWatermark())
        assertEquals(1, orderRows())
    }

    @Test
    fun `NUL 문자가 든 상품명은 DB 오류가 아니라 거절이고 순번은 확정되며 다음 항목은 적용된다`() {
        val nul = payload().let { it.copy(lines = listOf(it.lines.first().copy(productName = "아메\u0000리카노"))) }

        val result = receive(operation(1, nul), operation(2))

        assertEquals(listOf(SyncItemStatus.REJECTED, SyncItemStatus.APPLIED), result.items.map { it.status })
        assertEquals(OrderLine.INVALID_PRODUCT_NAME, result.items[0].errorCode)
        assertEquals(2, storedWatermark())
        assertEquals(1, orderRows())
    }

    @Test
    fun `ISO 4217에 없는 통화는 배치 오류가 아니라 거절이고 순번은 확정되며 다음 항목은 적용된다`() {
        val unknown = payload().copy(currency = "ZZZ")

        val result = receive(operation(1, unknown), operation(2))

        assertEquals(listOf(SyncItemStatus.REJECTED, SyncItemStatus.APPLIED), result.items.map { it.status })
        assertEquals(Money.UNSUPPORTED_CURRENCY, result.items[0].errorCode)
        assertEquals(2, storedWatermark())
        assertEquals(1, orderRows())
    }

    @Test
    fun `코드는 유효하지만 Money가 받지 못하는 통화 XXX는 거절이고 순번은 확정되며 다음 항목은 적용된다`() {
        val result = receive(operation(1, payload().copy(currency = "XXX")), operation(2))

        assertEquals(listOf(SyncItemStatus.REJECTED, SyncItemStatus.APPLIED), result.items.map { it.status })
        assertEquals(Money.UNSUPPORTED_CURRENCY, result.items[0].errorCode)
        assertEquals(2, storedWatermark())
        assertEquals(1, orderRows())
    }

    @Test
    fun `소수 수량은 잘려서 적용되지 않고 거절이며 주문 행이 없고 다음 항목은 적용된다`() {
        val fractional = payload().let { it.copy(lines = listOf(it.lines.first().copy(quantity = BigDecimal("1.5")))) }

        val result = receive(operation(1, fractional), operation(2))

        assertEquals(listOf(SyncItemStatus.REJECTED, SyncItemStatus.APPLIED), result.items.map { it.status })
        assertEquals(OrderLine.INVALID_QUANTITY, result.items[0].errorCode)
        assertEquals(2, storedWatermark())
        assertEquals(1, orderRows())
    }

    @Test
    fun `Int 범위를 넘는 수량은 배치 오류가 아니라 거절이고 순번은 확정되며 다음 항목은 적용된다`() {
        val tooMany = payload().let { it.copy(lines = listOf(it.lines.first().copy(quantity = BigDecimal("4294967297")))) }

        val result = receive(operation(1, tooMany), operation(2))

        assertEquals(listOf(SyncItemStatus.REJECTED, SyncItemStatus.APPLIED), result.items.map { it.status })
        assertEquals(OrderLine.INVALID_QUANTITY, result.items[0].errorCode)
        assertEquals(2, storedWatermark())
        assertEquals(1, orderRows())
    }

    @Test
    fun `극단적인 지수의 단가는 지수를 펴지 않고 INVALID_MONEY_AMOUNT 거절이며 다음 항목은 적용된다`() {
        val hugeExponent = payload().let { it.copy(lines = listOf(it.lines.first().copy(unitPrice = BigDecimal("1E+999999999")))) }

        val result = receive(operation(1, hugeExponent), operation(2))

        assertEquals(listOf(SyncItemStatus.REJECTED, SyncItemStatus.APPLIED), result.items.map { it.status })
        assertEquals("INVALID_MONEY_AMOUNT", result.items[0].errorCode)
        assertEquals(2, storedWatermark())
        assertEquals(1, orderRows())
    }

    @Test
    fun `접수 기록 저장이 실패하면 그 항목의 주문 행도 남지 않는다`() {
        // 순번 2의 접수 기록을 잠금 밖에서 미리 넣어 둔다(수동 수정을 흉내 낸다). 둘째 항목은 주문을 넣은 뒤 접수 기록의
        // unique (terminal_id, sequence)에 걸려 커밋에 실패한다. 항목 트랜잭션이 하나라면 그 주문 행도 함께 롤백된다.
        jdbcTemplate.update(
            "insert into t_sync_operation (id, terminal_id, operation_id, sequence, payload_hash, status, created_at, updated_at) " +
                "values (?, ?, ?, 2, 'stray', 'APPLIED', now(), now())",
            UUID.randomUUID(), terminalId.value, UUID.randomUUID(),
        )
        val secondOrder = payload(OrderFixtures.createOrderCommand(terminalId = terminalId, clientOrderId = ClientOrderId(UUID.randomUUID())))

        assertFailsWith<DataIntegrityViolationException> {
            receive(operation(1), operation(2, secondOrder))
        }

        assertEquals(1, storedWatermark())
        assertEquals(1, orderRows())
        assertEquals(
            0L,
            jdbcTemplate.queryForObject<Long>("select count(*) from t_order where client_order_id = ?", secondOrder.clientOrderId.value),
        )
    }

    @Test
    fun `주문 생성 다음 순번의 결제 요청이 같은 배치로 오면 둘 다 적용되고 주문은 CHECKOUT_REQUESTED다`() {
        val order = payload()

        val result = receive(operation(1, order), checkout(2, order.clientOrderId))

        assertEquals(listOf(SyncItemStatus.APPLIED, SyncItemStatus.APPLIED), result.items.map { it.status })
        assertEquals(result.items[0].orderId, result.items[1].orderId)
        assertEquals("CHECKOUT_REQUESTED", orderStatus(order.clientOrderId))
        assertEquals(1, checkoutRows())
        assertEquals(listOf("CREATE_ORDER", "REQUEST_CHECKOUT"), kinds())
        assertEquals(2, result.lastAckedSequence)
        assertEquals(2, storedWatermark())
        assertEquals(1L, paymentRequestedPublications(order.clientOrderId))
    }

    @Test
    fun `같은 clientOrderId의 결제 요청이 새 operationId로 다시 오면 적용되고 주문 행 checkout 행 발행 기록은 그대로다`() {
        val order = payload()

        val result = receive(operation(1, order), checkout(2, order.clientOrderId), checkout(3, order.clientOrderId))

        assertEquals(listOf(SyncItemStatus.APPLIED, SyncItemStatus.APPLIED, SyncItemStatus.APPLIED), result.items.map { it.status })
        assertEquals(1, result.items.map { it.orderId }.distinct().size)
        assertEquals(1, orderRows())
        assertEquals(1, checkoutRows())
        assertEquals(3, storedWatermark())
        assertEquals(1L, paymentRequestedPublications(order.clientOrderId))
    }

    @Test
    fun `결제 요청까지 적용된 배치를 다시 보내면 모두 중복이고 checkout 행과 접수 기록이 늘지 않는다`() {
        val order = payload()
        val batch = arrayOf(operation(1, order), checkout(2, order.clientOrderId))
        val first = receive(*batch)

        val resent = receive(*batch)

        assertEquals(listOf(SyncItemStatus.DUPLICATE, SyncItemStatus.DUPLICATE), resent.items.map { it.status })
        assertEquals(first.items.map { it.orderId }, resent.items.map { it.orderId })
        assertEquals(1, checkoutRows())
        assertEquals(2, operationRows())
    }

    @Test
    fun `인증된 단말의 매장과 다른 storeId의 주문 생성 항목은 STORE_MISMATCH 거절이고 순번은 확정되며 다음 항목은 적용되고 재전송은 같은 거절의 중복이다`() {
        val otherStore = payload(OrderFixtures.createOrderCommand(terminalId = terminalId, storeId = StoreId(UUID.randomUUID())))
        val foreign = operation(1, otherStore)

        val first = receive(foreign, operation(2))

        assertEquals(listOf(SyncItemStatus.REJECTED, SyncItemStatus.APPLIED), first.items.map { it.status })
        assertEquals(SyncCommandFacade.STORE_MISMATCH, first.items[0].errorCode)
        assertNull(first.items[0].orderId)
        assertEquals(2, first.lastAckedSequence)
        assertEquals(1, orderRows())
        assertEquals(null, orderStatus(otherStore.clientOrderId))

        val resent = receive(foreign)

        assertEquals(SyncItemStatus.DUPLICATE, resent.items.single().status)
        assertEquals(SyncCommandFacade.STORE_MISMATCH, resent.items.single().errorCode)
        assertEquals(2, operationRows())
    }

    @Test
    fun `주문이 없는 clientOrderId의 결제 요청은 ORDER_NOT_FOUND 거절이고 순번은 확정되며 재전송은 같은 거절의 중복이다`() {
        val missing = checkout(1, ClientOrderId(UUID.randomUUID()))

        val first = receive(missing, operation(2))

        assertEquals(listOf(SyncItemStatus.REJECTED, SyncItemStatus.APPLIED), first.items.map { it.status })
        assertEquals("ORDER_NOT_FOUND", first.items[0].errorCode)
        assertNull(first.items[0].orderId)
        assertEquals(2, first.lastAckedSequence)

        val resent = receive(missing)

        assertEquals(SyncItemStatus.DUPLICATE, resent.items.single().status)
        assertEquals("ORDER_NOT_FOUND", resent.items.single().errorCode)
        assertEquals(2, operationRows())
        assertEquals(0, checkoutRows())
    }

    @Test
    fun `확정된 결제 요청 operationId에 다른 clientOrderId가 오거나 주문 생성 operationId가 결제 요청으로 오면 충돌이고 아무것도 바뀌지 않는다`() {
        val order = payload()
        val create = operation(1, order)
        val applied = checkout(2, order.clientOrderId)
        receive(create, applied)

        val result = receive(
            checkout(2, ClientOrderId(UUID.randomUUID()), operationId = applied.operationId),
            checkout(1, order.clientOrderId, operationId = create.operationId),
        )

        assertEquals(listOf(SyncItemStatus.CONFLICT, SyncItemStatus.CONFLICT), result.items.map { it.status })
        assertEquals(2, result.lastAckedSequence)
        assertEquals(1, orderRows())
        assertEquals(1, checkoutRows())
        assertEquals(listOf("CREATE_ORDER", "REQUEST_CHECKOUT"), kinds())
    }

    @Test
    fun `서버에 같은 clientOrderId의 주문이 다른 합계로 있으면 주문 생성은 IDEMPOTENCY_CONFLICT 결제 요청은 CHECKOUT_AMOUNT_MISMATCH 거절이고 주문은 OPEN이다`() {
        val command = OrderFixtures.createOrderCommand(terminalId = terminalId)
        orders.create(command)
        val differentContent = payload(command).let { it.copy(lines = listOf(it.lines.first().copy(quantity = BigDecimal(7)))) }

        // 단말의 합계는 자기가 보낸 내용의 합계(1001 x 7)다. 서버의 확정 금액 3502와 다르다.
        val result = receive(
            operation(1, differentContent),
            checkout(2, command.clientOrderId, amount = BigDecimal("7007")),
        )

        assertEquals(listOf(SyncItemStatus.REJECTED, SyncItemStatus.REJECTED), result.items.map { it.status })
        assertEquals(listOf(Order.IDEMPOTENCY_CONFLICT, Order.CHECKOUT_AMOUNT_MISMATCH), result.items.map { it.errorCode })
        assertEquals("OPEN", orderStatus(command.clientOrderId))
        assertEquals(0, checkoutRows())
        assertEquals(0L, paymentRequestedPublications(command.clientOrderId))
        assertEquals(2, result.lastAckedSequence)
        assertEquals(2, storedWatermark())
    }

    @Test
    fun `금액 불일치로 거절된 결제 요청을 재전송하면 같은 거절의 중복이고 올바른 금액의 새 operation은 적용된다`() {
        val command = OrderFixtures.createOrderCommand(terminalId = terminalId)
        orders.create(command)
        val mismatched = checkout(1, command.clientOrderId, amount = BigDecimal("7007"))
        receive(mismatched)

        val resent = receive(mismatched)

        assertEquals(SyncItemStatus.DUPLICATE, resent.items.single().status)
        assertEquals(Order.CHECKOUT_AMOUNT_MISMATCH, resent.items.single().errorCode)
        assertEquals("OPEN", orderStatus(command.clientOrderId))

        // 단말이 서버 주문의 합계(3502)로 새 operationId·다음 순번을 보내면 적용된다.
        val corrected = receive(checkout(2, command.clientOrderId, amount = BigDecimal("3502")))

        assertEquals(SyncItemStatus.APPLIED, corrected.items.single().status)
        assertEquals(2, corrected.lastAckedSequence)
        assertEquals("CHECKOUT_REQUESTED", orderStatus(command.clientOrderId))
        assertEquals(1, checkoutRows())
    }

    @Test
    fun `확정된 결제 요청 operationId에 다른 amount가 오면 충돌이고 아무것도 바뀌지 않는다`() {
        val order = payload()
        val applied = checkout(2, order.clientOrderId)
        receive(operation(1, order), applied)

        val result = receive(checkout(2, order.clientOrderId, operationId = applied.operationId, amount = BigDecimal("3501")))

        assertEquals(SyncItemStatus.CONFLICT, result.items.single().status)
        assertEquals(2, result.lastAckedSequence)
        assertEquals(1, checkoutRows())
        assertEquals(2, operationRows())
    }

    @Test
    fun `결제 요청의 접수 기록 저장이 실패하면 checkout 행과 OrderPaymentRequested 발행 기록도 남지 않고 주문은 OPEN이다`() {
        // kind를 적지 않은 INSERT(이전 버전 인스턴스)가 기본값으로 성공하는 것도 함께 본다.
        val strayId = UUID.randomUUID()
        jdbcTemplate.update(
            "insert into t_sync_operation (id, terminal_id, operation_id, sequence, payload_hash, status, created_at, updated_at) " +
                "values (?, ?, ?, 2, 'stray', 'APPLIED', now(), now())",
            strayId, terminalId.value, UUID.randomUUID(),
        )
        assertEquals("CREATE_ORDER", jdbcTemplate.queryForObject<String>("select kind from t_sync_operation where id = ?", strayId))
        val order = payload()

        assertFailsWith<DataIntegrityViolationException> {
            receive(operation(1, order), checkout(2, order.clientOrderId))
        }

        assertEquals("OPEN", orderStatus(order.clientOrderId))
        assertEquals(0, checkoutRows())
        assertEquals(0L, paymentRequestedPublications(order.clientOrderId))
    }

    @Test
    fun `배치가 상한을 넘으면 저장소를 건드리기 전에 거절한다`() {
        val operations = Array(SyncCommandFacade.MAX_OPERATIONS + 1) { operation(it + 1L) }

        val failure = assertFailsWith<DomainException> { receive(*operations) }

        assertEquals(SyncCommandFacade.SYNC_BATCH_TOO_LARGE, failure.code)
        assertNull(storedWatermark())
        assertEquals(0, orderRows())
    }

    private fun operationCounts(): Map<SyncItemStatus, Double?> =
        SyncItemStatus.entries.associateWith { meterRegistry.find("sync.operation").tag("status", it.name).counter()?.count() }

    private fun countedDelta(block: () -> Unit): Map<SyncItemStatus, Double> {
        val before = operationCounts()
        block()
        val after = operationCounts()
        return SyncItemStatus.entries.associateWith { checkNotNull(after[it]) - checkNotNull(before[it]) }
    }

    @Test
    fun `sync operation counter는 다섯 status 모두 기동 때 0 이상으로 등록돼 있다`() {
        SyncItemStatus.entries.forEach { assertNotNull(meterRegistry.find("sync.operation").tag("status", it.name).counter(), it.name) }
    }

    @Test
    fun `배치 항목 결과마다 status별 counter가 1씩 오른다 적용 중복 gap`() {
        val applied = operation(1)
        val applyDelta = countedDelta { receive(applied, operation(3)) }
        val resendDelta = countedDelta { receive(applied) }

        assertEquals(mapOf(SyncItemStatus.APPLIED to 1.0, SyncItemStatus.GAP to 1.0), applyDelta.filterValues { it != 0.0 })
        assertEquals(mapOf(SyncItemStatus.DUPLICATE to 1.0), resendDelta.filterValues { it != 0.0 })
    }

    @Test
    fun `배치 항목 결과마다 status별 counter가 1씩 오른다 거절 충돌`() {
        val badScale = payload().let { it.copy(lines = listOf(it.lines.first().copy(unitPrice = BigDecimal("4500.5")))) }
        val applied = operation(2)
        val delta = countedDelta {
            receive(operation(1, badScale), applied)
            receive(operation(2, payload(), operationId = applied.operationId), operation(2))
        }

        assertEquals(mapOf(SyncItemStatus.REJECTED to 1.0, SyncItemStatus.APPLIED to 1.0, SyncItemStatus.CONFLICT to 2.0), delta.filterValues { it != 0.0 })
    }

    @Test
    fun `상한을 넘어 거절된 배치는 counter를 올리지 않는다`() {
        val delta = countedDelta {
            assertFailsWith<DomainException> { receive(*Array(SyncCommandFacade.MAX_OPERATIONS + 1) { operation(it + 1L) }) }
        }

        assertEquals(SyncItemStatus.entries.associateWith { 0.0 }, delta)
    }

    private fun convergence() = checkNotNull(meterRegistry.find("sync.convergence").timer()) { "sync.convergence timer가 없다" }

    /** (건수 증가, 총 시간 증가 초)를 돌려준다. */
    private fun convergenceDelta(block: () -> Unit): Pair<Long, Double> {
        val before = convergence().let { it.count() to it.totalTime(TimeUnit.SECONDS) }
        block()
        val after = convergence().let { it.count() to it.totalTime(TimeUnit.SECONDS) }
        return (after.first - before.first) to (after.second - before.second)
    }

    private fun secondsAgo(seconds: Long): Instant = Instant.now().minusSeconds(seconds)

    @Test
    fun `sync convergence timer는 기동 때 등록돼 있다`() {
        assertNotNull(meterRegistry.find("sync.convergence").timer())
    }

    @Test
    fun `createdAt이 있는 APPLIED 항목은 단말이 만든 뒤 확정까지의 시간을 한 번 기록한다`() {
        val (count, seconds) = convergenceDelta { receive(operation(1, createdAt = secondsAgo(90))) }

        assertEquals(1L, count)
        assertTrue(seconds in 90.0..120.0, "totalTime=$seconds")
    }

    @Test
    fun `createdAt이 있는 REJECTED 항목도 기록한다`() {
        val badScale = payload().let { it.copy(lines = listOf(it.lines.first().copy(unitPrice = BigDecimal("4500.5")))) }

        val (count, seconds) = convergenceDelta {
            val result = receive(operation(1, badScale, createdAt = secondsAgo(90)))
            assertEquals(SyncItemStatus.REJECTED, result.items.single().status)
        }

        assertEquals(1L, count)
        assertTrue(seconds in 90.0..120.0, "totalTime=$seconds")
    }

    @Test
    fun `createdAt이 없으면 기록하지 않는다`() {
        val (count, _) = convergenceDelta { receive(operation(1)) }

        assertEquals(0L, count)
    }

    @Test
    fun `미래 createdAt은 기록하지 않고 항목은 정상 APPLIED다`() {
        val (count, _) = convergenceDelta {
            val result = receive(operation(1, createdAt = Instant.now().plusSeconds(3600)))
            assertEquals(SyncItemStatus.APPLIED, result.items.single().status)
        }

        assertEquals(0L, count)
    }

    @Test
    fun `createdAt만 다른 재전송은 DUPLICATE이고 기록하지 않는다`() {
        val applied = operation(1, createdAt = secondsAgo(90))
        receive(applied)

        val (count, _) = convergenceDelta {
            val resent = receive(applied.copy(createdAt = secondsAgo(10)))
            assertEquals(SyncItemStatus.DUPLICATE, resent.items.single().status)
        }

        assertEquals(0L, count)
    }

    @Test
    fun `GAP과 CONFLICT 항목은 createdAt이 있어도 기록하지 않는다`() {
        val applied = operation(1)
        receive(applied)

        val (count, _) = convergenceDelta {
            val result = receive(
                operation(2, operationId = applied.operationId, createdAt = secondsAgo(90)),
                operation(5, createdAt = secondsAgo(90)),
            )
            assertEquals(listOf(SyncItemStatus.CONFLICT, SyncItemStatus.GAP), result.items.map { it.status })
        }

        assertEquals(0L, count)
    }
}
