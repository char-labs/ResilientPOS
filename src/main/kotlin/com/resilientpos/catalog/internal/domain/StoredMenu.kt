package com.resilientpos.catalog.internal.domain

/** 저장된 [menu]와 그 [version]. version은 낙관적 잠금과 ETag의 기준이라 aggregate가 아니라 저장 쪽 값이다. */
data class StoredMenu(val menu: Menu, val version: Long)
