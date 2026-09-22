package com.resilientpos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines
import com.resilientpos.contract.OrderPaymentRequested
import com.resilientpos.contract.PaymentApproved
import org.springframework.modulith.core.ApplicationModules

class ModuleStructureTest {

    private val modules = ApplicationModules.of(ResilientPosApplication::class.java)

    @Test
    fun `모듈은 다른 모듈의 internal 패키지에 의존하지 않는다`() {
        modules.verify()
    }

    @Test
    fun `bounded context 7개와 foundation·platform·contract를 application module로 인식한다`() {
        val moduleNames = modules.map { it.identifier.toString() }.sorted()

        assertEquals(
            listOf(
                "contract",
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

    @Test
    fun `모듈을 건너가는 이벤트는 contract가 소유한다`() {
        // order와 payment가 서로의 이벤트 타입을 직접 들면 양방향 의존이 되어 순환 검사에 걸린다.
        // 실제로 재현했다 — PaymentApproved가 payment에 있을 때 order에 소비자를 두면
        // "Cycle detected: Slice order -> Slice payment -> Slice order"로 실패한다.
        // 두 이벤트가 contract에 있는 한 양쪽이 같은 방향으로만 의존한다.
        assertEquals("com.resilientpos.contract", OrderPaymentRequested::class.java.packageName)
        assertEquals("com.resilientpos.contract", PaymentApproved::class.java.packageName)
    }

    @Test
    fun `contract는 업무 모듈을 의존하지 않는다`() {
        // 계약이 order나 payment의 타입을 들면 그 방향으로 간선이 생겨 순환이 되돌아온다.
        // Modulith의 순환 검사는 소비자가 실제로 생긴 뒤에야 걸리므로, 그 전에 여기서 막는다.
        val businessModules = listOf("order", "payment", "sync", "reconciliation", "fulfillment")
        val offenders = Path("src/main/kotlin/com/resilientpos/contract")
            .listDirectoryEntries("*.kt")
            .flatMap { file -> file.readLines().map { file.fileName.toString() to it } }
            .filter { (_, line) -> businessModules.any { line.startsWith("import com.resilientpos.$it.") } }

        assertEquals(emptyList(), offenders)
    }
}
