package com.resilientpos.owner.internal.domain

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.owner.OwnerId

/**
 * 점주 계정 aggregate 루트. 이메일은 정규화된 값이고 비밀번호는 해시(`{id}` 접두어 포함)만 가진다. 불변 객체다.
 */
class Owner private constructor(
    val id: OwnerId,
    val email: OwnerEmail,
    val passwordHash: String,
) {

    companion object {
        const val INVALID_EMAIL = "INVALID_EMAIL"
        const val INVALID_PASSWORD = "INVALID_PASSWORD"
        const val OWNER_EMAIL_DUPLICATED = "OWNER_EMAIL_DUPLICATED"

        fun register(email: OwnerEmail, passwordHash: String, ids: IdentifierGenerator): Owner =
            Owner(OwnerId(ids.next()), email, passwordHash)

        /** 같은 이메일의 계정이 이미 있다. 응답은 409다. */
        fun emailDuplicated(): DomainException =
            DomainException(OWNER_EMAIL_DUPLICATED, "이미 가입된 이메일입니다", DomainErrorKind.CONFLICT)

        /** 저장된 계정을 그대로 재구성한다. persistence adapter의 `toDomain()`만 부른다. */
        fun restore(id: OwnerId, email: OwnerEmail, passwordHash: String) = Owner(id, email, passwordHash)
    }
}
