package com.resilientpos.catalog

import com.resilientpos.catalog.internal.adapter.persistence.query.MenuQueries
import com.resilientpos.catalog.internal.domain.Menu
import com.resilientpos.catalog.internal.domain.MenuItem
import com.resilientpos.catalog.internal.domain.MenuRepository
import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.Money
import com.resilientpos.owner.OwnerId
import com.resilientpos.platform.Tx
import com.resilientpos.platform.isUniqueViolationOf
import com.resilientpos.store.StoreId
import com.resilientpos.store.StoreManagement
import java.math.BigDecimal
import java.util.Currency
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service

/**
 * `catalog` 모듈의 유일한 진입점. 점주의 메뉴 조회·전체 교체와 단말의 메뉴 조회가 여기를 거친다.
 * 인터페이스를 따로 두지 않는 구체 클래스다. 읽기도 이 클래스에 둔다.
 */
@Service
class MenuManagement(
    private val repository: MenuRepository,
    private val queries: MenuQueries,
    private val stores: StoreManagement,
) {

    /** 매장이 점주의 것이 아니면 `STORE_NOT_FOUND`, 메뉴가 없으면 `MENU_NOT_FOUND`(NOT_FOUND)다. */
    fun find(storeId: StoreId, ownerId: OwnerId): MenuView {
        stores.requireOwned(storeId, ownerId)
        return findForTerminal(storeId)
    }

    /** 단말이 자기 매장([storeId])의 메뉴를 읽는다. 메뉴가 없으면 `MENU_NOT_FOUND`(NOT_FOUND)다. */
    fun findForTerminal(storeId: StoreId): MenuView {
        return queries.find(storeId) ?: throw DomainException(MENU_NOT_FOUND, "메뉴를 찾을 수 없습니다", DomainErrorKind.NOT_FOUND)
    }

    /**
     * 메뉴 전체를 검증해 교체한다. 만들기는 version 0, 고치기는 읽은 값 + 1을 돌려준다.
     * 만들기인데 이미 있음, 고치기인데 없음·version 다름은 모두 `MENU_VERSION_MISMATCH`(CONFLICT)다.
     * 경합의 방어선은 DB다. 수정은 `@Version`의 조건부 UPDATE(낙관적 잠금 예외), 생성은 PK `t_menu_pkey`이고
     * 둘 다 커밋에서 드러나므로 `Tx` 블록 밖에서 번역한다. 바깥 트랜잭션 안에서 부르지 않는다(커밋이 이 메서드의 것이어야 한다).
     */
    fun replace(command: MenuCommand.Replace): MenuView {
        stores.requireOwned(command.storeId, command.ownerId)
        val currency = parseCurrency(command.currency)
        val menu = Menu.create(
            command.storeId,
            currency,
            command.items.mapIndexed { index, it -> MenuItem(it.code, it.name, money(it.price, currency, index + 1), it.category) },
        )
        val version = try {
            Tx.masterTx { save(menu, command.precondition) }
        } catch (_: OptimisticLockingFailureException) {
            throw versionMismatch()
        } catch (e: DataIntegrityViolationException) {
            if (!e.isUniqueViolationOf(MENU_PK_CONSTRAINT)) throw e
            throw versionMismatch()
        }
        return menu.toView(version)
    }

    private fun save(menu: Menu, precondition: MenuCommand.Precondition): Long = when (precondition) {
        MenuCommand.Precondition.Create -> repository.create(menu)
        is MenuCommand.Precondition.Version -> repository.replace(menu, precondition.expected) ?: throw versionMismatch()
    }

    private fun parseCurrency(code: String): Currency =
        try {
            Currency.getInstance(code)
        } catch (_: IllegalArgumentException) {
            throw DomainException(Menu.INVALID_MENU, "알 수 없는 통화 코드입니다", DomainErrorKind.INVALID)
        }

    /** `Money` 위반의 code·kind는 그대로 두고 메시지에 몇 번째 항목인지 붙인다. */
    private fun money(price: BigDecimal, currency: Currency, n: Int): Money =
        try {
            Money.of(price, currency)
        } catch (e: DomainException) {
            throw DomainException(e.code, "${n}번째 항목의 가격: ${e.message}", e.kind)
        }

    private fun Menu.toView(version: Long) =
        MenuView(storeId, currency, items.map { MenuView.Item(it.code, it.name, it.price, it.category) }, version)

    private fun versionMismatch() =
        DomainException(MENU_VERSION_MISMATCH, "메뉴가 다른 곳에서 먼저 바뀌었습니다", DomainErrorKind.CONFLICT)

    companion object {
        const val MENU_NOT_FOUND = "MENU_NOT_FOUND"
        const val MENU_VERSION_MISMATCH = "MENU_VERSION_MISMATCH"
        private const val MENU_PK_CONSTRAINT = "t_menu_pkey"
    }
}
