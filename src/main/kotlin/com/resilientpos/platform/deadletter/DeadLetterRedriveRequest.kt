package com.resilientpos.platform.deadletter

/** `POST /admin/v1/dead-letters/{topic}/{partition}/{offset}/redrive` 요청. 없거나 규칙에 어긋난 사유는 [DeadLetterRedriver]가 `INVALID_REDRIVE_REASON`으로 거절한다. */
data class DeadLetterRedriveRequest(val reason: String? = null)
