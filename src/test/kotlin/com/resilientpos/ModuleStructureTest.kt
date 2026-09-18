package com.resilientpos

import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.modulith.core.ApplicationModules

class ModuleStructureTest {

    private val modules = ApplicationModules.of(ResilientPosApplication::class.java)

    @Test
    fun `모듈은 다른 모듈의 internal 패키지에 의존하지 않는다`() {
        modules.verify()
    }

    @Test
    fun `bounded context 7개와 foundation과 platform을 application module로 인식한다`() {
        val moduleNames = modules.map { it.identifier.toString() }.sorted()

        assertEquals(
            listOf(
                "foundation",
                "fulfillment",
                "order",
                "payment",
                "platform",
                "reconciliation",
                "store",
                "sync",
                "terminal",
            ),
            moduleNames,
        )
    }
}
