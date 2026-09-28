package com.resilientpos

import org.springframework.test.context.event.ApplicationEvents

/** 기록된 이벤트 중 한 타입만 목록으로 돌려준다. `stream(X::class.java)`를 타입 인자로 줄인다. */
inline fun <reified T : Any> ApplicationEvents.ofType(): List<T> = stream(T::class.java).toList()
