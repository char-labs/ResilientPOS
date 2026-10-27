package com.resilientpos.catalog.internal.domain

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.store.StoreId
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class MenuTest {

    private val storeId = StoreId(UUID.fromString("00000000-0000-0000-0000-0000000000d1"))
    private val krw = Currency.getInstance("KRW")
    private val usd = Currency.getInstance("USD")

    private fun item(
        code: String = "A1",
        name: String = "아메리카노",
        price: Money = Money.of("4500", krw),
        category: String = "커피",
    ) = MenuItem(code, name, price, category)

    private fun assertInvalid(items: List<MenuItem>, currency: Currency = krw): DomainException {
        val e = assertFailsWith<DomainException> { Menu.create(storeId, currency, items) }
        assertEquals(Menu.INVALID_MENU, e.code)
        assertEquals(DomainErrorKind.INVALID, e.kind)
        return e
    }

    @Test
    fun `유효한 항목은 순서를 지켜 메뉴가 된다`() {
        val items = listOf(item("A1"), item("A2", "라테", Money.of("5000", krw), "커피"))

        val menu = Menu.create(storeId, krw, items)

        assertEquals(storeId, menu.storeId)
        assertEquals(krw, menu.currency)
        assertEquals(items, menu.items)
    }

    @Test
    fun `같음은 storeId로 판단하고 내용이 달라도 같은 매장이면 같다`() {
        val a = Menu.create(storeId, krw, listOf(item("A1")))
        val b = Menu.create(storeId, usd, listOf(item("B1", price = Money.of("1.50", usd))))
        val other = Menu.create(StoreId(UUID.fromString("00000000-0000-0000-0000-0000000000d2")), krw, listOf(item("A1")))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, other)
    }

    @Test
    fun `항목 0개와 200개는 통과하고 201개는 거절한다`() {
        assertEquals(0, Menu.create(storeId, krw, emptyList()).items.size)
        assertEquals(200, Menu.create(storeId, krw, (1..200).map { item("C$it") }).items.size)
        assertInvalid((1..201).map { item("C$it") })
    }

    @Test
    fun `코드 이름 분류 길이 경계는 1자와 64 255 50자까지다`() {
        Menu.create(storeId, krw, listOf(item(code = "a".repeat(64), name = "n".repeat(255), category = "c".repeat(50))))
        Menu.create(storeId, krw, listOf(item(code = "a", name = "n", category = "c")))
        assertInvalid(listOf(item(code = "a".repeat(65))))
        assertInvalid(listOf(item(name = "n".repeat(256))))
        assertInvalid(listOf(item(category = "c".repeat(51))))
        assertInvalid(listOf(item(code = "")))
        assertInvalid(listOf(item(name = "")))
        assertInvalid(listOf(item(category = "")))
    }

    @Test
    fun `서로게이트 쌍은 한 글자로 센다`() {
        val emoji = "🍺"
        Menu.create(storeId, krw, listOf(item(code = emoji.repeat(64), name = emoji.repeat(255), category = emoji.repeat(50))))
        assertInvalid(listOf(item(code = emoji.repeat(65))))
        assertInvalid(listOf(item(name = emoji.repeat(256))))
        assertInvalid(listOf(item(category = emoji.repeat(51))))
    }

    @Test
    fun `공백만 있는 값은 NBSP와 U+3000 포함 거절한다`() {
        listOf(" ", "\t", " ", "　", " 　 ").forEach { blank ->
            assertInvalid(listOf(item(code = blank)))
            assertInvalid(listOf(item(name = blank)))
            assertInvalid(listOf(item(category = blank)))
        }
    }

    @Test
    fun `NUL이 든 값은 거절한다`() {
        assertInvalid(listOf(item(code = "A\u0000B")))
        assertInvalid(listOf(item(name = "A\u0000B")))
        assertInvalid(listOf(item(category = "A\u0000B")))
    }

    @Test
    fun `메뉴 안에서 코드가 겹치면 거절하고 메시지에 몇 번째 항목인지 적는다`() {
        val e = assertInvalid(listOf(item("A1"), item("A2"), item("A1", "다른 이름")))

        assertEquals(true, e.message.contains("3번째"), e.message)
    }

    @Test
    fun `음수 가격은 거절한다`() {
        assertInvalid(listOf(item(price = Money.of("-1", krw))))
    }

    @Test
    fun `0원은 통과한다`() {
        Menu.create(storeId, krw, listOf(item(price = Money.zero(krw))))
    }

    @Test
    fun `가격 통화가 메뉴 통화와 다르면 거절한다`() {
        assertInvalid(listOf(item(price = Money.of("1.50", usd))), krw)
    }

    @Test
    fun `통화 자릿수를 넘는 가격은 Money의 code로 나가 메뉴를 만들지 못한다`() {
        val e = assertFailsWith<DomainException> {
            Menu.create(storeId, krw, listOf(item(price = Money.of(BigDecimal("4500.5"), krw))))
        }
        assertEquals(Money.INVALID_MONEY_SCALE, e.code)
    }

    @Test
    fun `지원하지 않는 통화는 항목이 0개여도 Money의 code로 거절한다`() {
        val xxx = Currency.getInstance("XXX")
        val e = assertFailsWith<DomainException> { Menu.create(storeId, xxx, emptyList()) }
        assertEquals(Money.UNSUPPORTED_CURRENCY, e.code)
    }
}
