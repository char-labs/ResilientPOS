package com.resilientpos

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import kotlin.test.Test

class DependencyDirectionTest {

    private val productionClasses = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(ROOT_PACKAGE)

    /**
     * `package-info`는 코드가 아니라 모듈 메타데이터라서 Modulith 애너테이션을 허용한다.
     * 같은 애너테이션이 실제 타입에 붙으면 위반으로 잡힌다.
     */
    @Test
    fun `foundation은 프레임워크에 의존하지 않는다`() {
        noClasses()
            .that().resideInAPackage("..foundation..")
            .and().doNotHaveSimpleName(PACKAGE_METADATA)
            .should().dependOnClassesThat().resideInAnyPackage(*FRAMEWORK_PACKAGES)
            .check(productionClasses)
    }

    companion object {
        private const val ROOT_PACKAGE = "com.resilientpos"
        private const val PACKAGE_METADATA = "package-info"

        private val FRAMEWORK_PACKAGES = arrayOf(
            "org.springframework..",
            "jakarta..",
            "tools.jackson..",
            "com.fasterxml.jackson..",
            "org.apache.kafka..",
        )
    }
}
