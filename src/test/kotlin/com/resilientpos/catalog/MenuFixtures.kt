package com.resilientpos.catalog

import com.navercorp.fixturemonkey.kotlin.giveMeKotlinBuilder
import com.resilientpos.TestFixtureMonkey
import com.resilientpos.owner.OwnerId
import com.resilientpos.store.StoreId
import java.math.BigDecimal

/** `MenuCommand` fixture. 판단에 중요한 값(통화, 가격, 조건)은 고정한다. */
object MenuFixtures {

    fun item(code: String = "A1", name: String = "아메리카노", price: String = "4000", category: String = "커피"): MenuCommand.Item =
        TestFixtureMonkey.instance.giveMeKotlinBuilder<MenuCommand.Item>()
            .setExp(MenuCommand.Item::code, code)
            .setExp(MenuCommand.Item::name, name)
            .setExp(MenuCommand.Item::price, BigDecimal(price))
            .setExp(MenuCommand.Item::category, category)
            .sample()

    fun replace(
        storeId: StoreId,
        ownerId: OwnerId,
        precondition: MenuCommand.Precondition = MenuCommand.Precondition.Create,
        currency: String = "KRW",
        items: List<MenuCommand.Item> = listOf(item(), item("A2", "라테", "4500")),
    ): MenuCommand.Replace =
        TestFixtureMonkey.instance.giveMeKotlinBuilder<MenuCommand.Replace>()
            .setExp(MenuCommand.Replace::storeId, storeId)
            .setExp(MenuCommand.Replace::ownerId, ownerId)
            .setExp(MenuCommand.Replace::currency, currency)
            .setExp(MenuCommand.Replace::items, items)
            .setExp(MenuCommand.Replace::precondition, precondition)
            .sample()
}
