package com.resilientpos

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import kotlin.test.Test

class TransactionDeclarationTest {

    private val productionClasses = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.resilientpos")

    /**
     * 클래스에 붙이면 나중에 더한 조회 메서드까지 트랜잭션이 걸린다. 트랜잭션이 필요한 메서드가 스스로 선언한다.
     * 애너테이션 정의는 제외한다. 메타 애너테이션으로 쓰는 것은 클래스 선언이 아니다.
     */
    @Test
    fun `클래스에 트랜잭션을 선언하지 않는다`() {
        noClasses()
            .that().areNotAnnotations()
            .should().beMetaAnnotatedWith(TRANSACTIONAL)
            .orShould().beMetaAnnotatedWith(JAKARTA_TRANSACTIONAL)
            .check(productionClasses)
    }

    companion object {
        private const val TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional"
        private const val JAKARTA_TRANSACTIONAL = "jakarta.transaction.Transactional"
    }
}
