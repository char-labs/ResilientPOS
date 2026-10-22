package com.resilientpos.platform.deadletter

/** `POST /admin/v1/dead-letters/{topic}/{partition}/{offset}/dismiss` 요청. 없거나 규칙에 어긋난 사유는 [DeadLetterDismisser]가 `INVALID_DISMISS_REASON`으로 거절한다. */
data class DeadLetterDismissRequest(val reason: String? = null)
