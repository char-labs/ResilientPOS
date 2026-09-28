package com.resilientpos

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaAnnotation
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods
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

    /**
     * 읽기 전용은 `@ReadOnlyTransactional`(`readOnly = true`, `SUPPORTS`) 하나로 쓴다. `@Transactional(readOnly = true)`를
     * 직접 붙이면 전파가 `REQUIRED`라 호출자에 트랜잭션이 없을 때 새로 연다. 같은 "읽기 전용"이 두 가지 의미가 된다.
     */
    @Test
    fun `메서드에 읽기 전용 트랜잭션을 직접 선언하지 않는다`() {
        noMethods()
            .should().beAnnotatedWith(readOnlyTransactional)
            .check(productionClasses)
    }

    companion object {
        private const val TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional"
        private const val JAKARTA_TRANSACTIONAL = "jakarta.transaction.Transactional"

        private val readOnlyTransactional = DescribedPredicate.describe("@Transactional(readOnly = true)") { annotation: JavaAnnotation<*> ->
            annotation.rawType.name == TRANSACTIONAL && annotation.get("readOnly").orElse(false) == true
        }
    }
}
