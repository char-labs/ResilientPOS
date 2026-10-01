package com.resilientpos.sync.internal.domain

import java.security.MessageDigest
import java.util.HexFormat

/** 단말이 operation으로 실어 온 내용. 종류마다 구현이 하나다. */
sealed interface OperationPayload {
    val kind: SyncOperationKind

    /** 재전송 판정에 쓰는 SHA-256 hex. 저장 형식이라 공식을 바꾸지 않는다. */
    fun hash(): String
}

/** 필드마다 `길이:값;`으로 이어 SHA-256 hex를 만든다. [OperationPayload.hash]의 공식이라 바꾸지 않는다. */
internal fun lengthPrefixedSha256(fields: List<String>): String {
    val canonical = fields.joinToString(separator = "") { "${it.length}:$it;" }
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
}
