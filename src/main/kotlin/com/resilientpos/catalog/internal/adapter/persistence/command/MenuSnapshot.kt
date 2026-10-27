package com.resilientpos.catalog.internal.adapter.persistence.command

/** `t_menu` 한 행의 문서와 version을 엔티티를 영속성 컨텍스트에 올리지 않고 읽는 투영. */
interface MenuSnapshot {
    val menu: String
    val version: Long?
}
