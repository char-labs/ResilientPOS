package com.resilientpos.contract

/** 외부로 나가는 이벤트 하나의 계약. `sample`은 마지막 fixture와 같은 값이다. */
data class EventContract(val type: Class<*>, val topic: String, val schemaVersion: Int, val sample: Any)
