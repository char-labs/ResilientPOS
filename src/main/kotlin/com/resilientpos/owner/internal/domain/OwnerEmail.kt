package com.resilientpos.owner.internal.domain

import com.resilientpos.foundation.DomainErrorKind
import com.resilientpos.foundation.DomainException
import java.util.Locale

/**
 * 정규화된 점주 이메일. 앞뒤 공백을 지우고 소문자로 바꾼 값이다.
 * 형식은 `@` 앞뒤에 공백 없는 문자가 있고 254자 이하다. 실제 수신 가능 여부는 확인하지 않는다(이메일 인증은 후속).
 */
@JvmInline
value class OwnerEmail private constructor(val value: String) {

    companion object {
        private const val MAX_LENGTH = 254
        private val FORMAT = Regex("^\\S+@\\S+$")

        /** 정규화하고 형식을 검사한다. 틀리면 `INVALID_EMAIL`이다. */
        fun of(raw: String): OwnerEmail {
            val normalized = normalize(raw)
            if (normalized.length > MAX_LENGTH || !FORMAT.matches(normalized)) {
                throw DomainException(Owner.INVALID_EMAIL, "이메일 형식이 올바르지 않습니다", DomainErrorKind.INVALID)
            }
            return OwnerEmail(normalized)
        }

        /** 검증 없이 정규화만 한다. */
        fun normalize(raw: String): String = raw.trim().lowercase(Locale.ROOT)
    }
}
