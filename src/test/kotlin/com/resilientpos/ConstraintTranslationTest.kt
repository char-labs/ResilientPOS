package com.resilientpos

import com.tngtech.archunit.core.domain.JavaCall.Predicates.target
import com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo
import com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo
import com.tngtech.archunit.core.domain.properties.HasName.Predicates.name
import com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import jakarta.persistence.EntityManager
import kotlin.test.Test

class ConstraintTranslationTest {

    private val productionClasses = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.resilientpos")

    /**
     * 저장 메서드는 저장만 하고 제약 위반은 커밋을 연 Facade가 번역한다. 어댑터가 판별 함수를 부르면 flush로 위반을 앞당겨 가리게 된다
     * (rules domain-modeling §5, wiki "Aggregate와 JPA 엔티티는 별개 클래스다" 절).
     */
    @Test
    fun `persistence 어댑터는 unique 위반 판별 함수를 부르지 않는다`() {
        noClasses()
            .that().resideInAPackage("..adapter.persistence..")
            .should().callMethodWhere(
                target(name("isUniqueViolationOf"))
                    .and(target(owner(equivalentTo(Class.forName("com.resilientpos.platform.UniqueViolationKt"))))),
            )
            .check(productionClasses)
    }

    /**
     * 저장 메서드는 저장만 한다. flush로 위반을 앞당겨 가리거나 순서를 맞추지 않는다. 순서가 제약에 걸리면 그 제약을 커밋 때 검사하게 한다
     * (credential 회전의 `ux_terminal_credential_active`는 DEFERRABLE INITIALLY DEFERRED다).
     */
    @Test
    fun `persistence 어댑터는 EntityManager flush를 부르지 않는다`() {
        noClasses()
            .that().resideInAPackage("..adapter.persistence..")
            .should().callMethodWhere(
                target(name("flush")).and(target(owner(assignableTo(EntityManager::class.java)))),
            )
            .check(productionClasses)
    }
}
