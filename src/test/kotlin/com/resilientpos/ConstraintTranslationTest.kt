package com.resilientpos

import com.resilientpos.platform.JdslQueryExecutor
import com.tngtech.archunit.core.domain.JavaCall.Predicates.target
import com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo
import com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo
import com.tngtech.archunit.core.domain.properties.HasName.Predicates.name
import com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching
import com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import jakarta.persistence.EntityManager
import kotlin.test.Test
import org.springframework.data.repository.Repository as SpringDataRepository
import org.springframework.transaction.TransactionStatus

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
     * (credential 회전의 `ux_terminal_credential_active`는 DEFERRABLE INITIALLY DEFERRED다). Hibernate `Session`은 `EntityManager`의 하위 타입이고,
     * `JpaTransactionManager`에서 `TransactionStatus.flush()`는 세션을 flush한다. 테스트 코드는 경합을 만들려고 flush하므로 대상이 아니다.
     */
    @Test
    fun `운영 코드는 flush를 부르지 않는다`() {
        noClasses()
            .should().callMethodWhere(
                target(name("flush").or(name("saveAndFlush")).or(name("saveAllAndFlush")))
                    .and(
                        target(
                            owner(
                                assignableTo(EntityManager::class.java)
                                    .or(assignableTo(SpringDataRepository::class.java))
                                    .or(assignableTo(TransactionStatus::class.java)),
                            ),
                        ),
                    ),
            )
            .check(productionClasses)
    }

    /**
     * Facade는 블록 안 조회의 auto-flush가 드러낸 위반도 `DataIntegrityViolationException`으로 받는다고 기댄다. `@Repository`인 [JdslQueryExecutor]와
     * Spring Data repository가 번역하기 때문이다. `@Component` 어댑터가 `EntityManager`로 직접 쿼리를 만들면 Hibernate 예외가 그대로 나와 그 번역을 빗나간다.
     */
    @Test
    fun `EntityManager의 쿼리는 JdslQueryExecutor만 만든다`() {
        noClasses()
            .that().doNotHaveFullyQualifiedName(JdslQueryExecutor::class.java.name)
            .should().callMethodWhere(
                target(nameMatching("create.*Query")).and(target(owner(assignableTo(EntityManager::class.java)))),
            )
            .check(productionClasses)
    }
}
