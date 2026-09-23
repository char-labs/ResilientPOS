package com.resilientpos.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * 로거 이름은 logback 설정과 로그 검색의 키다. `LoggerFactory.getLogger(X::class.java)`와 같은 이름이 나와야
 * 옮겨 온 클래스의 로그가 다른 이름으로 흩어지지 않는다.
 */
class LoggingTest {

    @Test
    fun `companion에서 만든 로거는 바깥 클래스 이름을 쓴다`() {
        assertEquals(WithCompanion::class.java.name, WithCompanion.log.name)
    }

    @Test
    fun `이름 붙인 companion에서 만든 로거도 바깥 클래스 이름을 쓴다`() {
        assertEquals(WithNamedCompanion::class.java.name, WithNamedCompanion.log.name)
    }

    @Test
    fun `companion이 아닌 object에서는 그 object 이름을 쓴다`() {
        assertEquals(Standalone::class.java.name, Standalone.log.name)
    }

    @Test
    fun `하위 클래스 인스턴스에서 읽어도 선언한 클래스 이름을 쓴다`() {
        assertEquals(Parent::class.java.name, Child().log.name)
    }

    @Test
    fun `여러 번 읽어도 같은 로거다`() {
        assertSame(WithCompanion.log, WithCompanion.log)
    }

    class WithCompanion {
        companion object {
            val log by logger()
        }
    }

    class WithNamedCompanion {
        companion object Factory {
            val log by logger()
        }
    }

    object Standalone {
        val log by logger()
    }

    open class Parent {
        val log by logger()
    }

    class Child : Parent()
}
