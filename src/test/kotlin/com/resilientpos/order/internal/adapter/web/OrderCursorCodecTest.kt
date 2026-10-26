package com.resilientpos.order.internal.adapter.web

import com.resilientpos.order.OrderId
import com.resilientpos.order.OrderQuery
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * cursor는 클라이언트에게 불투명하다. 형식을 고정하는 것은 왕복과 "해석 실패는 모두 400"이다.
 * `IllegalArgumentException`이나 `DateTimeException`이 새면 500이 되어 오류 예산을 태우므로 실패 종류마다 본다.
 */
class OrderCursorCodecTest {

    private val orderId = OrderId(UUID.fromString("018f6b1a-0000-7000-8000-0000000000c1"))

    private fun raw(text: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    @Test
    fun `마이크로초 시각과 id를 담은 base64url 패딩 없는 문자열로 왕복한다`() {
        val cursor = OrderQuery.FindOrders.Cursor(Instant.parse("2026-10-25T23:50:00.123456Z"), orderId)

        val text = OrderCursorCodec.encode(cursor)

        assertEquals("MTc5Mjk3MjIwMDEyMzQ1Ni4wMThmNmIxYS0wMDAwLTcwMDAtODAwMC0wMDAwMDAwMDAwYzE", text)
        assertFalse(text.contains('=') || text.contains('+') || text.contains('/'))
        assertEquals(cursor, OrderCursorCodec.decode(text))
    }

    @Test
    fun `1970년 이전과 범위 끝 시각도 왕복한다`() {
        listOf(Instant.parse("1969-12-31T23:59:59.999999Z"), Instant.parse("0001-01-01T00:00:00Z"), Instant.parse("9999-12-31T23:59:59.999999Z")).forEach {
            val cursor = OrderQuery.FindOrders.Cursor(it, orderId)
            assertEquals(cursor, OrderCursorCodec.decode(OrderCursorCodec.encode(cursor)))
        }
    }

    @Test
    fun `해석할 수 없는 cursor는 종류와 관계없이 400이다`() {
        val invalid = mapOf(
            "Base64가 아님" to "not base64!",
            "빈 문자열" to "",
            "구조에 구분자가 없음" to raw("1792972200123456"),
            "구조에 구분자가 둘" to raw("1792972200123456.${orderId.value}.x"),
            "UUID가 아님" to raw("1792972200123456.not-a-uuid"),
            "숫자가 아님" to raw("abc.${orderId.value}"),
            "시각이 Long 밖" to raw("99999999999999999999.${orderId.value}"),
            "시각이 범위 위" to raw("${Long.MAX_VALUE}.${orderId.value}"),
            "시각이 범위 아래" to raw("${Long.MIN_VALUE}.${orderId.value}"),
        )

        invalid.forEach { (kind, text) ->
            val e = assertThrows<ResponseStatusException>(kind) { OrderCursorCodec.decode(text) }
            assertTrue(e.statusCode == HttpStatus.BAD_REQUEST, kind)
        }
    }
}
