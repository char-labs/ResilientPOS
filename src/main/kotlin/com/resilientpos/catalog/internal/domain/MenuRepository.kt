package com.resilientpos.catalog.internal.domain

/** `Menu`를 저장하는 port. 트랜잭션 안에서만 부른다. 읽기는 `MenuQueries`가 맡는다. */
interface MenuRepository {

    /** 새 메뉴를 넣고 version 0을 돌려준다. 이미 있는 매장이면 커밋에서 PK `t_menu_pkey` 위반이다. */
    fun create(menu: Menu): Long

    /**
     * 같은 트랜잭션에서 읽은 행의 version이 [expectedVersion]과 같을 때만 내용을 바꾸고 새 version을 돌려준다.
     * 행이 없거나 version이 다르면 아무것도 바꾸지 않고 null이다. 내용이 같아도 version은 한 번 오른다.
     * 비교한 값을 커밋의 조건부 UPDATE가 지키므로 읽은 뒤 다른 트랜잭션이 먼저 커밋하면 커밋에서 낙관적 잠금 예외다.
     */
    fun replace(menu: Menu, expectedVersion: Long): Long?
}
