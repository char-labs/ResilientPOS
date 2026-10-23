package com.resilientpos.store.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.owner.OwnerId
import com.resilientpos.store.StoreFixtures
import com.resilientpos.store.StoreId
import com.resilientpos.store.StoreManagement
import com.resilientpos.store.StoreView
import com.resilientpos.store.internal.domain.Store
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject

class StoreManagementIntegrationTest(
    private val stores: StoreManagement,
    private val jdbcTemplate: JdbcTemplate,
) : PostgresIntegrationTest() {

    private val ownerId = OwnerId(UUID.randomUUID())
    private val otherOwnerId = OwnerId(UUID.randomUUID())

    private fun create(name: String, owner: OwnerId = ownerId) = stores.create(StoreFixtures.createStoreCommand(owner, name))

    @Test
    fun `생성하면 trim한 이름과 점주로 한 행이 저장된다`() {
        val result = create("  강남점 ")

        assertEquals("강남점", result.name)
        assertEquals("강남점", jdbcTemplate.queryForObject<String>("select name from t_store where id = ?", result.storeId.value))
        assertEquals(ownerId.value, jdbcTemplate.queryForObject<UUID>("select owner_id from t_store where id = ?", result.storeId.value))
    }

    @Test
    fun `이름이 틀리면 INVALID_STORE_NAME이고 행이 늘지 않는다`() {
        val e = assertFailsWith<DomainException> { create("   ") }

        assertEquals(Store.INVALID_STORE_NAME, e.code)
        assertEquals(0, jdbcTemplate.queryForObject<Long>("select count(*) from t_store where owner_id = ?", ownerId.value))
    }

    @Test
    fun `내 매장만 생성 순으로 돌려주고 이름 중복을 허용한다`() {
        val first = create("본점")
        val second = create("본점")
        val third = create("지점")
        create("남의 매장", otherOwnerId)

        assertEquals(
            listOf(StoreView(first.storeId, "본점"), StoreView(second.storeId, "본점"), StoreView(third.storeId, "지점")),
            stores.findByOwner(ownerId),
        )
        assertEquals(emptyList(), stores.findByOwner(OwnerId(UUID.randomUUID())))
    }

    @Test
    fun `내 매장이면 requireOwned가 통과하고 남의 매장과 없는 매장은 같은 STORE_NOT_FOUND다`() {
        val mine = create("내 매장")
        val others = create("남의 매장", otherOwnerId)

        stores.requireOwned(mine.storeId, ownerId)
        listOf(others.storeId, StoreId(UUID.randomUUID())).forEach { storeId ->
            val e = assertFailsWith<DomainException> { stores.requireOwned(storeId, ownerId) }
            assertEquals(Store.STORE_NOT_FOUND, e.code)
            assertEquals(DomainErrorKind.NOT_FOUND, e.kind)
        }
    }
}
