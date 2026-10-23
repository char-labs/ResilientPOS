package com.resilientpos.sync.internal.application

import com.navercorp.fixturemonkey.kotlin.giveMeKotlinBuilder
import com.resilientpos.TestFixtureMonkey
import com.resilientpos.order.OrderFixtures
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId

/** `SyncCommand` fixture. 매장은 [OrderFixtures]의 기본 매장을 따르고, 파라미터 이름과 순서는 커맨드 생성자와 같다. */
object SyncFixtures {

    fun receiveBatchCommand(
        terminalId: TerminalId,
        storeId: StoreId = OrderFixtures.storeId,
        operations: List<SyncCommand.ReceiveBatch.Operation>,
        reconnectedAt: String? = null,
    ): SyncCommand.ReceiveBatch =
        TestFixtureMonkey.instance.giveMeKotlinBuilder<SyncCommand.ReceiveBatch>()
            .setExp(SyncCommand.ReceiveBatch::terminalId, terminalId)
            .setExp(SyncCommand.ReceiveBatch::storeId, storeId)
            .setExp(SyncCommand.ReceiveBatch::operations, operations)
            .setExp(SyncCommand.ReceiveBatch::reconnectedAt, reconnectedAt)
            .sample()
}
