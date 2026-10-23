package com.resilientpos.store

import com.navercorp.fixturemonkey.kotlin.giveMeKotlinBuilder
import com.resilientpos.TestFixtureMonkey
import com.resilientpos.owner.OwnerId

/** `StoreCommand` fixture. 파라미터 이름과 순서는 커맨드 생성자와 같다. 매장 이름은 중복을 허용한다. */
object StoreFixtures {

    fun createStoreCommand(ownerId: OwnerId, name: String = "테스트 매장"): StoreCommand.Create =
        TestFixtureMonkey.instance.giveMeKotlinBuilder<StoreCommand.Create>()
            .setExp(StoreCommand.Create::ownerId, ownerId)
            .setExp(StoreCommand.Create::name, name)
            .sample()
}
