package com.resilientpos

import com.resilientpos.contract.OrderPaymentRequested
import com.resilientpos.contract.PaymentApproved
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
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

    /**
     * 계약이 업무 모듈의 타입을 들면 그 방향으로 간선이 생겨, 끊으려던 순환이 되돌아온다.
     * `modules.verify()`의 순환 검사는 소비자가 실제로 생긴 뒤에야 걸리므로 그 전에 방향을 지킨다.
     * import 문자열이 아니라 바이트코드 의존을 보므로 FQN 직접 표기·하위 패키지·소스 레이아웃에 견딘다.
     */
    @Test
    fun `contract는 업무 모듈에 의존하지 않는다`() {
        val productionClasses = ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.resilientpos.contract")

        noClasses()
            .that().resideInAPackage("..contract..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "..order..",
                "..payment..",
                "..sync..",
                "..reconciliation..",
                "..fulfillment..",
                "..platform..",
            )
            .check(productionClasses)
    }

    @Test
    fun `모듈을 건너가는 이벤트의 정규 이름을 고정한다`() {
        // 클래스에서 기대값을 다시 계산하면 옮기든 이름을 바꾸든 항상 통과한다. 리터럴로 박는다.
        // 이 이름은 event_publication.event_type과 listener_id에 그대로 들어가는 저장 형식이다.
        assertEquals("com.resilientpos.contract.OrderPaymentRequested", OrderPaymentRequested::class.java.name)
        assertEquals("com.resilientpos.contract.PaymentApproved", PaymentApproved::class.java.name)
    }
}
