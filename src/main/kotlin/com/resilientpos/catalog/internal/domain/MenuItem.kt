package com.resilientpos.catalog.internal.domain

import com.resilientpos.foundation.Money

/** 메뉴 항목 값. 검증은 [Menu.create]가 항목 위치와 함께 한다. */
data class MenuItem(
    val code: String,
    val name: String,
    val price: Money,
    val category: String,
)
