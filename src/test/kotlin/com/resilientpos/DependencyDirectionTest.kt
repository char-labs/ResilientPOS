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

    /**
     * 도메인 레이어는 프레임워크뿐 아니라 같은 모듈의 adapter·application 구현에도 의존하지 않는다.
     * Modulith 경계 검증은 모듈 사이만 보므로 모듈 안의 레이어 방향은 여기서 지킨다.
     */
    @Test
    fun `도메인 레이어는 프레임워크와 adapter와 application에 의존하지 않는다`() {
        noClasses()
            .that().resideInAPackage(DOMAIN_LAYER)
            .should().dependOnClassesThat().resideInAnyPackage(*FRAMEWORK_PACKAGES, *IMPLEMENTATION_LAYERS)
            .check(productionClasses)
    }

    companion object {
        private const val ROOT_PACKAGE = "com.resilientpos"
        private const val PACKAGE_METADATA = "package-info"
        private const val DOMAIN_LAYER = "..internal.domain.."

        private val IMPLEMENTATION_LAYERS = arrayOf(
            "..internal.adapter..",
            "..internal.application..",
        )

        private val FRAMEWORK_PACKAGES = arrayOf(
            "org.springframework..",
            "jakarta..",
            "tools.jackson..",
            "com.fasterxml.jackson..",
            "org.apache.kafka..",
        )
    }
}
