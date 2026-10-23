package com.resilientpos.platform

import com.tngtech.archunit.core.domain.JavaCall.Predicates.target
import com.tngtech.archunit.core.domain.JavaClass.Predicates.type
import com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RateLimitedExceptionTest {

    @Test
    fun `스택 트레이스를 만들지 않는다`() {
        assertTrue(RateLimitedException(1, "scope=terminal").stackTrace.isEmpty())
    }

    @Test
    fun `Retry-After가 1 미만이면 만들지 못한다`() {
        assertFailsWith<IllegalArgumentException> { RateLimitedException(0, "scope=terminal") }
    }

    /**
     * 단말은 `RATE_LIMITED`를 받으면 같은 요청을 그대로 다시 보낸다. 그래서 이 예외는 상태를 바꾸기 전(handler 전)의 거절에만 써야 한다.
     * handler 안에서 상태가 바뀐 뒤의 거절이 이 예외를 쓰면 단말이 이미 처리된 요청을 다시 보낸다.
     */
    @Test
    fun `RateLimitedException은 handler 전에 판정하는 빈도·동시 상한 limiter만 만든다`() {
        val production = ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.resilientpos")
        // 시그니처가 아니라 소유 타입으로 맞춘다. 기본값 인자를 더하면 Kotlin이 합성 생성자를 만들어 시그니처 규칙을 빠져나간다.
        val construct = target(owner(type(RateLimitedException::class.java)))

        // limiter 안의 람다·companion 같은 중첩 클래스(`RedisTerminalRateLimiter$…`)도 limiter로 본다.
        noClasses().that().haveNameNotMatching(LIMITERS.joinToString("|") { Pattern.quote(it) + "(\\$.*)?" })
            .should().callConstructorWhere(construct)
            .check(production)
        // 양성 대조. limiter의 이름이 바뀌면 위 규칙이 모든 생성을 금지한 채 이 규칙이 실패해 드러난다.
        for (limiter in LIMITERS) {
            classes().that().haveFullyQualifiedName(limiter)
                .should().callConstructorWhere(construct)
                .check(production)
        }
    }

    private companion object {
        /** platform 테스트가 terminal 내부 타입을 import하지 않게 이름으로 가리킨다. 이름이 바뀌면 양성 대조가 실패한다. */
        val LIMITERS = listOf(
            "com.resilientpos.terminal.internal.adapter.redis.RedisTerminalRateLimiter",
            "com.resilientpos.terminal.internal.adapter.web.TerminalInFlightLimiter",
        )
    }
}
