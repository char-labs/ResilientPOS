package com.resilientpos.catalog.internal.application

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.awaitUntil
import com.resilientpos.catalog.MenuCommand.Precondition
import com.resilientpos.catalog.MenuFixtures
import com.resilientpos.catalog.MenuManagement
import com.resilientpos.catalog.MenuView
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.owner.OwnerId
import com.resilientpos.store.StoreFixtures
import com.resilientpos.store.StoreId
import com.resilientpos.store.StoreManagement
import java.util.Currency
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import tools.jackson.databind.json.JsonMapper

class MenuManagementIntegrationTest(
    private val menus: MenuManagement,
    private val stores: StoreManagement,
    private val jdbcTemplate: JdbcTemplate,
    private val dataSource: DataSource,
    private val jsonMapper: JsonMapper,
) : PostgresIntegrationTest() {

    private val ownerId = OwnerId(UUID.randomUUID())
    private val storeId: StoreId = stores.create(StoreFixtures.createStoreCommand(ownerId)).storeId
    private val krw = Currency.getInstance("KRW")

    private fun create(items: List<com.resilientpos.catalog.MenuCommand.Item> = MenuFixtures.replace(storeId, ownerId).items) =
        menus.replace(MenuFixtures.replace(storeId, ownerId, items = items))

    private fun replace(expected: Long, items: List<com.resilientpos.catalog.MenuCommand.Item>) =
        menus.replace(MenuFixtures.replace(storeId, ownerId, Precondition.Version(expected), items = items))

    private fun dbVersion(): Long = checkNotNull(jdbcTemplate.queryForObject<Long>("select version from t_menu where store_id = ?", storeId.value))

    private fun menuCount(): Long =
        checkNotNull(jdbcTemplate.queryForObject<Long>("select count(*) from t_menu where store_id = ?", storeId.value))

    private fun dbMenu(): String = checkNotNull(jdbcTemplate.queryForObject<String>("select menu::text from t_menu where store_id = ?", storeId.value))

    private fun assertDomain(code: String, kind: DomainErrorKind, block: () -> Unit) {
        val e = assertFailsWith<DomainException> { block() }
        assertEquals(code, e.code)
        assertEquals(kind, e.kind)
    }

    @Test
    fun `만들면 version 0이고 점주 조회와 단말 조회가 같은 메뉴를 돌려준다`() {
        val created = create()

        assertEquals(0L, created.version)
        assertEquals(MenuView.Item("A1", "아메리카노", Money.of("4000", krw), "커피"), created.items.first())
        assertEquals(created, menus.find(storeId, ownerId))
        assertEquals(created, menus.findForTerminal(storeId))
        assertEquals(0L, dbVersion())
    }

    @Test
    fun `고치면 반환 version이 읽은 값 더하기 하나이고 DB와 같다`() {
        create()

        val updated = replace(0, listOf(MenuFixtures.item(price = "4500")))

        assertEquals(1L, updated.version)
        assertEquals(Money.of("4500", krw), updated.items.single().price)
        assertEquals(updated, menus.find(storeId, ownerId))
        assertEquals(1L, dbVersion())
    }

    @Test
    fun `내용이 같은 재저장도 version이 하나만 오른다`() {
        val items = MenuFixtures.replace(storeId, ownerId).items
        create(items)

        val same = replace(0, items)
        val sameAgain = replace(1, items)

        assertEquals(1L, same.version)
        assertEquals(2L, sameAgain.version)
        assertEquals(2L, dbVersion())
    }

    @Test
    fun `옛 version으로 고치면 MENU_VERSION_MISMATCH이고 저장된 메뉴는 그대로다`() {
        create()
        replace(0, listOf(MenuFixtures.item(price = "4500")))
        val before = dbMenu()

        assertDomain("MENU_VERSION_MISMATCH", DomainErrorKind.CONFLICT) { replace(0, listOf(MenuFixtures.item(price = "9999"))) }

        assertEquals(before, dbMenu())
        assertEquals(1L, dbVersion())
    }

    @Test
    fun `이미 있는데 만들거나 없는데 고치면 MENU_VERSION_MISMATCH다`() {
        assertDomain("MENU_VERSION_MISMATCH", DomainErrorKind.CONFLICT) { replace(0, MenuFixtures.replace(storeId, ownerId).items) }
        assertEquals(0L, menuCount())

        create()
        assertDomain("MENU_VERSION_MISMATCH", DomainErrorKind.CONFLICT) { create() }
        assertEquals(0L, dbVersion())
    }

    @Test
    fun `읽은 뒤 다른 트랜잭션이 먼저 커밋하면 수정은 MENU_VERSION_MISMATCH이고 먼저 커밋한 내용이 남는다`() {
        create()
        val winner = """{"currency":"KRW","items":[]}"""

        withCommittedAfterBlocked(
            "update t_menu set menu = '$winner'::jsonb, version = version + 1 where store_id = '${storeId.value}'",
        ) { replace(0, listOf(MenuFixtures.item(price = "7000"))) }

        assertEquals(1L, dbVersion())
        assertEquals(jsonMapper.readTree(winner), jsonMapper.readTree(dbMenu()))
    }

    @Test
    fun `같은 내용을 다시 저장하는 중에 다른 트랜잭션이 먼저 커밋해도 MENU_VERSION_MISMATCH이고 먼저 커밋한 내용이 남는다`() {
        val items = MenuFixtures.replace(storeId, ownerId).items
        create(items)
        val winner = """{"currency":"KRW","items":[]}"""

        // 내용이 같아 dirty UPDATE 없이 OPTIMISTIC_FORCE_INCREMENT만 version을 올리는 경로다
        withCommittedAfterBlocked(
            "update t_menu set menu = '$winner'::jsonb, version = version + 1 where store_id = '${storeId.value}'",
        ) { replace(0, items) }

        assertEquals(1L, dbVersion())
        assertEquals(jsonMapper.readTree(winner), jsonMapper.readTree(dbMenu()))
    }

    @Test
    fun `먼저 커밋된 생성 뒤 두 번째 생성은 PK 위반이 MENU_VERSION_MISMATCH가 된다`() {
        val winner = """{"currency":"KRW","items":[]}"""

        withCommittedAfterBlocked(
            "insert into t_menu (store_id, menu, version, created_at, updated_at) " +
                "values ('${storeId.value}', '$winner'::jsonb, 0, now(), now())",
        ) { create() }

        assertEquals(0L, dbVersion())
        assertEquals(jsonMapper.readTree(winner), jsonMapper.readTree(dbMenu()))
    }

    /**
     * 다른 트랜잭션이 [blockerSql]을 실행만 하고 커밋하지 않은 채, [attempt]가 같은 행 앞에서 대기하는 것을 확인한 뒤 커밋한다.
     * 대기하던 쪽은 "읽기 -> 다른 트랜잭션 커밋 -> 저장" 순서로 어긋나 `MENU_VERSION_MISMATCH`로 끝나야 한다.
     */
    private fun withCommittedAfterBlocked(blockerSql: String, attempt: () -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        dataSource.connection.use { blocker ->
            blocker.autoCommit = false
            blocker.createStatement().use { it.execute(blockerSql) }
            try {
                val future = executor.submit(Callable { attempt() })
                awaitUntil("저장이 잠금을 기다린다") {
                    checkNotNull(
                        jdbcTemplate.queryForObject<Long>(
                            "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and query ilike '%t_menu%' and pid <> pg_backend_pid()",
                        ),
                    ) > 0
                }
                blocker.commit()
                val e = assertFailsWith<ExecutionException> { future.get(10, TimeUnit.SECONDS) }
                val cause = e.cause as DomainException
                assertEquals("MENU_VERSION_MISMATCH", cause.code)
                assertEquals(DomainErrorKind.CONFLICT, cause.kind)
            } finally {
                blocker.rollback()
                executor.shutdown()
                executor.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `남의 매장이면 조회와 저장 모두 STORE_NOT_FOUND이고 아무것도 저장되지 않는다`() {
        val stranger = OwnerId(UUID.randomUUID())

        assertDomain("STORE_NOT_FOUND", DomainErrorKind.NOT_FOUND) { menus.find(storeId, stranger) }
        assertDomain("STORE_NOT_FOUND", DomainErrorKind.NOT_FOUND) { menus.replace(MenuFixtures.replace(storeId, stranger)) }
        assertEquals(0L, menuCount())
    }

    @Test
    fun `메뉴가 없으면 MENU_NOT_FOUND다`() {
        assertDomain("MENU_NOT_FOUND", DomainErrorKind.NOT_FOUND) { menus.find(storeId, ownerId) }
        assertDomain("MENU_NOT_FOUND", DomainErrorKind.NOT_FOUND) { menus.findForTerminal(storeId) }
    }

    @Test
    fun `Currency가 모르는 통화 코드는 소문자 포함 INVALID_MENU이고 저장되지 않는다`() {
        listOf("krw", "ZZZ", "").forEach { code ->
            assertDomain("INVALID_MENU", DomainErrorKind.INVALID) { menus.replace(MenuFixtures.replace(storeId, ownerId, currency = code)) }
            assertDomain("INVALID_MENU", DomainErrorKind.INVALID) {
                menus.replace(MenuFixtures.replace(storeId, ownerId, currency = code, items = emptyList()))
            }
        }
        assertEquals(0L, menuCount())
    }

    @Test
    fun `저장된 jsonb 문서는 currency와 items의 code name price category뿐이고 price는 decimal string이다`() {
        create(listOf(MenuFixtures.item("A1", "아메리카노", "4000", "커피"), MenuFixtures.item("B1", "치즈케이크", "6500", "디저트")))

        val expected = """
            {"currency":"KRW","items":[
              {"code":"A1","name":"아메리카노","price":"4000","category":"커피"},
              {"code":"B1","name":"치즈케이크","price":"6500","category":"디저트"}]}
        """.trimIndent()
        assertEquals(jsonMapper.readTree(expected), jsonMapper.readTree(dbMenu()))
    }

    @Test
    fun `소수 자릿수가 있는 통화의 가격은 통화 자릿수 그대로 저장되고 읽힌다`() {
        menus.replace(MenuFixtures.replace(storeId, ownerId, currency = "USD", items = listOf(MenuFixtures.item(price = "3.50"))))

        assertEquals("3.50", jsonMapper.readTree(dbMenu()).get("items").get(0).get("price").stringValue())
        assertEquals(Money.of("3.50", Currency.getInstance("USD")), menus.findForTerminal(storeId).items.single().price)
    }
}
