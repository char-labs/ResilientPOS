package com.resilientpos.order.internal.adapter.web

import com.resilientpos.order.OrderId
import com.resilientpos.order.OrderQuery
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * 점주 주문 목록 cursor의 인코딩. `"<occurredAt epoch 마이크로초>.<orderId>"`를 패딩 없는 base64url로 감싼다.
 * 클라이언트에게는 불투명하고 목록 응답 변환에만 쓰므로 공용으로 만들지 않는다.
 * 해석 실패는 모두 `ResponseStatusException(BAD_REQUEST)`로 던진다. `IllegalArgumentException`이 새면 500이 되어 오류 예산을 태운다.
 */
object OrderCursorCodec {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    // PostgreSQL timestamptz가 받는 범위 안에서 Long 마이크로초가 넘치지 않게 연도 1~9999로 닫는다.
    private val MIN: Instant = Instant.parse("0001-01-01T00:00:00Z")
    private val MAX: Instant = Instant.parse("9999-12-31T23:59:59.999999Z")

    fun encode(cursor: OrderQuery.FindOrders.Cursor): String {
        val micros = ChronoUnit.MICROS.between(Instant.EPOCH, cursor.occurredAt)
        return encoder.encodeToString("$micros.${cursor.orderId.value}".toByteArray())
    }

    fun decode(text: String): OrderQuery.FindOrders.Cursor {
        try {
            val parts = String(decoder.decode(text)).split('.')
            if (parts.size != 2) throw badRequest()
            val occurredAt = Instant.EPOCH.plus(parts[0].toLong(), ChronoUnit.MICROS)
            return OrderQuery.FindOrders.Cursor(requireStorable(occurredAt), OrderId(UUID.fromString(parts[1])))
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: RuntimeException) {
            // Base64, 숫자, UUID 형식과 시각 계산 오버플로(DateTimeException, ArithmeticException)가 모두 여기로 온다. 입력만 보는 순수 해석이라 원인은 한 가지다.
            throw badRequest()
        }
    }

    /** 목록의 시각 입력(cursor, `from`·`to`)이 PostgreSQL `timestamptz`에 바인딩될 수 있는지 본다. 밖이면 DB 오류(500) 대신 400이다. */
    fun requireStorable(instant: Instant): Instant {
        if (instant < MIN || instant > MAX) throw badRequest()
        return instant
    }

    private fun badRequest() = ResponseStatusException(HttpStatus.BAD_REQUEST)
}
