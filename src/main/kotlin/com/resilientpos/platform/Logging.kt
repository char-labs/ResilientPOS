package com.resilientpos.platform

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.reflect.KClass

/**
 * `companion object { private val log by logger() }`로 선언한다. 클래스 본문에 두면 인스턴스마다 `Lazy`가 생긴다.
 *
 * 이름은 런타임 하위 클래스가 아니라 선언한 타입에서 정해서 `LoggerFactory.getLogger(X::class.java)`와 같다.
 * 메시지는 람다로 적는다(`log.info { "...$x" }`). 레벨이 꺼져 있으면 문자열을 만들지 않는다.
 */
inline fun <reified T : Any> T.logger(): Lazy<KLogger> = lazy { KotlinLogging.logger(loggerName(T::class)) }

/** companion의 이름(`X$Companion`, `X$Factory`)으로 찍히면 같은 클래스의 로그가 둘로 갈린다. */
@PublishedApi
internal fun loggerName(type: KClass<*>): String =
    (if (type.isCompanion) type.java.enclosingClass else type.java).name
