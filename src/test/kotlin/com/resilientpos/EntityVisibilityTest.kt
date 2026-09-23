package com.resilientpos

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaConstructor
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.constructors
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import jakarta.persistence.Entity
import kotlin.test.Test

/**
 * JPA 엔티티의 생성 경로와 변경 경로를 컴파일러가 아니라 이 테스트가 고정한다.
 * 규칙은 도메인 규칙 §5다 — 생성은 `from(aggregate)`, 변경은 `apply(aggregate)` 안에서만.
 *
 * 호출부는 `private` 생성자와 `protected set`이 이미 컴파일 단계에서 막는다. 이 테스트가 막는 것은
 * 그 다음이다. 엔티티를 고치는 PR이 생성자를 다시 열거나 setter의 `protected`를 빠뜨리면 여기서 걸린다.
 *
 * 바이트코드에는 소스에 없는 생성자가 둘 더 있고 둘 다 규칙의 대상이 아니다.
 * 하나는 `kotlin("plugin.jpa")`가 JPA를 위해 만드는 인자 없는 생성자이고, 파라미터가 없다는 것으로 거른다.
 * 다른 하나는 `companion object`가 `private` 생성자를 부를 수 있게 Kotlin이 만드는 접근용 생성자다.
 * 값 파라미터 뒤에 `DefaultConstructorMarker`가 붙고 `private`이 아니라서 파라미터 수로는 걸러지지 않는다.
 * 이쪽은 `SYNTHETIC`으로 거른다. 이 구분을 실제 실패로 관측하고 규칙을 좁혔다.
 */
class EntityVisibilityTest {

    private val productionClasses = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.resilientpos")

    private val takeValues = object : DescribedPredicate<JavaConstructor>("값 파라미터를 받는다") {
        override fun test(constructor: JavaConstructor) = constructor.rawParameterTypes.isNotEmpty()
    }

    @Test
    fun `엔티티는 값을 받는 생성자를 밖에 열지 않는다`() {
        constructors()
            .that().areDeclaredInClassesThat().areAnnotatedWith(Entity::class.java)
            .and().doNotHaveModifier(JavaModifier.SYNTHETIC)
            .and(takeValues)
            .should().bePrivate()
            .because("생성은 companion object의 from(aggregate)만 한다")
            .check(productionClasses)
    }

    @Test
    fun `엔티티의 setter는 public이 아니다`() {
        methods()
            .that().areDeclaredInClassesThat().areAnnotatedWith(Entity::class.java)
            .and().haveNameMatching("set[A-Z].*")
            .and().doNotHaveModifier(JavaModifier.SYNTHETIC)
            .should().notBePublic()
            .because("필드 변경은 apply(aggregate) 안에서만 일어난다")
            .check(productionClasses)
    }
}
