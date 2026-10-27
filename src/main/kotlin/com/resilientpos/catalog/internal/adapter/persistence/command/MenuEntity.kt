package com.resilientpos.catalog.internal.adapter.persistence.command

import com.resilientpos.platform.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * 역할: 매장 하나의 메뉴 문서(코드·이름·가격·분류)의 정본. 매장당 한 행이다.
 * 흐름: 점주의 메뉴 전체 교체가 처음 한 번 만들고 이후 같은 행의 문서를 통째로 바꾼다. 지우지 않는다. 전체 지도는 `docs/wiki/entity-map.md`.
 *
 * `Menu`의 JPA 매핑. 전 필드는 `var`에 `protected set`이다(`OrderEntity`의 KDoc 참고).
 * [menu]는 jsonb 컬럼의 `String`이다. 직렬화는 `MenuDocumentMapper`가 한다.
 */
@Entity
@Table(name = "t_menu")
class MenuEntity private constructor(
    storeId: UUID,
    menu: String,
) : BaseEntity() {

    @Id
    var storeId: UUID = storeId
        protected set

    @JdbcTypeCode(SqlTypes.JSON)
    var menu: String = menu
        protected set

    /**
     * 수정 경합의 방어선이다. 수정은 내용이 같아도 한 번 올린다(바뀌면 dirty UPDATE, 같으면 `MenuPersistenceAdapter.replace`의 `OPTIMISTIC_FORCE_INCREMENT` 잠금).
     * nullable인 이유는 `OrderEntity`와 같다(primitive면 Spring Data가 새 엔티티를 `merge`로 보낸다). 생성은 `save`가 `persist`로 보낸다.
     */
    @Version
    var version: Long? = null
        protected set

    /** 관리 엔티티의 문서를 바꾼다. */
    fun apply(menu: String) {
        this.menu = menu
    }

    companion object {
        fun from(storeId: UUID, menu: String): MenuEntity = MenuEntity(storeId, menu)
    }
}
