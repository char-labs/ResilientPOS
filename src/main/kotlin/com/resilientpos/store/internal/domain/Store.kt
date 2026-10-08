package com.resilientpos.store.internal.domain

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.owner.OwnerId
import com.resilientpos.store.StoreId

/**
 * 매장 aggregate 루트. 점주 한 명이 소유하고 소유자와 이름을 바꾸는 명령은 없다. 이름은 중복될 수 있다. 불변 객체다.
 */
class Store private constructor(
    val id: StoreId,
    val ownerId: OwnerId,
    val name: String,
) {

    companion object {
        const val INVALID_STORE_NAME = "INVALID_STORE_NAME"
        const val STORE_NOT_FOUND = "STORE_NOT_FOUND"
        private const val MAX_NAME_CODE_POINTS = 100
        private val INVISIBLE = Regex("[\\p{Cc}\\p{Cf}]")

        /** [rawName]을 trim해 1~100자(code point)이고 제어·서식 문자(`Cc`·`Cf`)가 없으면 만든다. 아니면 `INVALID_STORE_NAME`이다. */
        fun create(ownerId: OwnerId, rawName: String, ids: IdentifierGenerator): Store {
            val name = rawName.trim()
            if (name.isEmpty() || name.codePointCount(0, name.length) > MAX_NAME_CODE_POINTS || INVISIBLE.containsMatchIn(name)) {
                throw DomainException(INVALID_STORE_NAME, "매장 이름은 앞뒤 공백을 지운 뒤 1~100자여야 합니다", DomainErrorKind.INVALID)
            }
            return Store(StoreId(ids.next()), ownerId, name)
        }

        /** 없는 매장과 남의 매장을 구분하지 않는다. 존재를 알리지 않으려는 것이다. 응답은 404다. */
        fun notFound(): DomainException =
            DomainException(STORE_NOT_FOUND, "매장을 찾을 수 없습니다", DomainErrorKind.NOT_FOUND)
    }
}
