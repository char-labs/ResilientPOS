package com.resilientpos.terminal.internal.adapter.web

import com.resilientpos.platform.RateLimitedException
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.AuthenticatedTerminal
import com.resilientpos.terminal.TerminalId
import com.resilientpos.terminal.internal.adapter.redis.MutableClock
import com.resilientpos.terminal.internal.adapter.web.TerminalInFlightLimiter.Kind
import com.tngtech.archunit.core.domain.JavaCall.Predicates.target
import com.tngtech.archunit.core.domain.JavaClass.Predicates.type
import com.tngtech.archunit.core.domain.properties.HasName.Predicates.name
import com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

/** pod 메모리 집합으로 판정하는 단말별 동시 요청 상한. 인터셉터 연결은 `TerminalAuthenticationInterceptor` 테스트가 본다. */
@ExtendWith(OutputCaptureExtension::class)
class TerminalInFlightLimiterTest {

    private val storeId = StoreId(UUID.randomUUID())
    private val terminal = AuthenticatedTerminal(TerminalId(UUID.randomUUID()), storeId)
    private val meterRegistry = SimpleMeterRegistry()
    private val clock = MutableClock()
    private val limiter = TerminalInFlightLimiter(meterRegistry, clock)

    private fun CapturedOutput.rejectLines(mark: Int) = all.substring(mark).lines().filter { it.contains("동시 요청 상한으로 거절했다") }

    private fun inFlightRejects() = meterRegistry.counter(METRIC, "scope", "in_flight").count()

    @Test
    fun `같은 단말의 같은 묶음은 처리 중이면 다음 요청이 retryAfter 1초로 거절되고 푼 뒤에는 통과한다`() {
        Kind.entries.forEach { kind ->
            limiter.acquire(terminal, kind)

            val rejected = assertFailsWith<RateLimitedException> { limiter.acquire(terminal, kind) }
            assertEquals(1, rejected.retryAfterSeconds)
            assertContains(rejected.message.orEmpty(), "terminalId=${terminal.terminalId.value}")

            limiter.release(terminal, kind)
            limiter.acquire(terminal, kind)
            limiter.release(terminal, kind)
        }
    }

    @Test
    fun `같은 단말이어도 묶음이 다르면 서로 막지 않는다`() {
        limiter.acquire(terminal, Kind.SYNC)
        limiter.acquire(terminal, Kind.REQUEST)

        assertFailsWith<RateLimitedException> { limiter.acquire(terminal, Kind.SYNC) }
        assertFailsWith<RateLimitedException> { limiter.acquire(terminal, Kind.REQUEST) }
    }

    @Test
    fun `다른 단말은 같은 매장이어도 영향을 받지 않는다`() {
        val other = AuthenticatedTerminal(TerminalId(UUID.randomUUID()), storeId)
        limiter.acquire(terminal, Kind.SYNC)

        limiter.acquire(other, Kind.SYNC)
    }

    @Test
    fun `거절마다 in_flight scope counter가 1 오르고 처음부터 0으로 있다`() {
        // counter(...)는 없으면 만들어 주므로 등록 여부를 못 가린다. find는 생성자가 등록한 것만 본다.
        assertEquals(0.0, meterRegistry.find(METRIC).tag("scope", "in_flight").counter()?.count())
        limiter.acquire(terminal, Kind.SYNC)

        repeat(3) { assertFailsWith<RateLimitedException> { limiter.acquire(terminal, Kind.SYNC) } }

        assertEquals(3.0, inFlightRejects())
    }

    @Test
    fun `같은 묶음의 거절 로그는 10초에 한 줄이고 건수와 직전 시각을 다음 줄에 적는다`(output: CapturedOutput) {
        val mark = output.all.length
        limiter.acquire(terminal, Kind.SYNC)
        repeat(3) { assertFailsWith<RateLimitedException> { limiter.acquire(terminal, Kind.SYNC) } }
        clock.advanceSeconds(9)
        assertFailsWith<RateLimitedException> { limiter.acquire(terminal, Kind.SYNC) }
        assertEquals(1, output.rejectLines(mark).size)

        clock.advanceSeconds(1)
        assertFailsWith<RateLimitedException> { limiter.acquire(terminal, Kind.SYNC) }

        val lines = output.rejectLines(mark)
        assertEquals(2, lines.size, lines.joinToString("\n"))
        lines.forEach { assertContains(it, "INFO") }
        assertContains(lines[0], "kind=sync terminalId=${terminal.terminalId.value} storeId=${storeId.value} heldForMs=0 suppressedSinceLast=0 lastLoggedAt=none")
        assertContains(lines[1], "heldForMs=10000 suppressedSinceLast=3 lastLoggedAt=2026-10-21T00:00:00Z")
    }

    @Test
    fun `시계가 뒤로 돌아가도 heldForMs는 0 아래로 찍히지 않는다`(output: CapturedOutput) {
        val mark = output.all.length
        limiter.acquire(terminal, Kind.SYNC)
        clock.advanceSeconds(-5)

        assertFailsWith<RateLimitedException> { limiter.acquire(terminal, Kind.SYNC) }

        assertContains(output.rejectLines(mark).single(), "heldForMs=0 ")
    }

    @Test
    fun `묶음이 다르면 거절 로그도 따로 남긴다`(output: CapturedOutput) {
        val mark = output.all.length
        Kind.entries.forEach { limiter.acquire(terminal, it) }

        Kind.entries.forEach { kind -> assertFailsWith<RateLimitedException> { limiter.acquire(terminal, kind) } }

        val lines = output.rejectLines(mark)
        assertEquals(2, lines.size, lines.joinToString("\n"))
        assertContains(lines[0], "kind=sync")
        assertContains(lines[1], "kind=request")
    }

    /**
     * 429·`RATE_LIMITED`는 handler 전의 거절이어야 단말이 같은 요청을 그대로 다시 보낼 수 있다. 그래서 동시 상한도 인증 인터셉터만 부른다.
     * `release`를 다른 곳에서 부르면 상한이 조용히 깨지므로 같이 건다. 인터셉터의 중첩 클래스는 인터셉터로 본다(음성 규칙).
     * 양성 대조는 인터셉터 본체가 부르는지 이름으로 본다. 시그니처가 바뀌면 규칙이 비지 않고 실패한다.
     */
    @Test
    fun `acquire와 release는 인증 인터셉터만 부른다`() {
        val production = ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.resilientpos")

        listOf("acquire", "release").forEach { method ->
            val call = target(name(method)).and(target(owner(type(TerminalInFlightLimiter::class.java))))
            noClasses().that().doNotBelongToAnyOf(TerminalAuthenticationInterceptor::class.java)
                .should().callMethodWhere(call)
                .check(production)
            classes().that().haveFullyQualifiedName(TerminalAuthenticationInterceptor::class.java.name)
                .should().callMethodWhere(call)
                .check(production)
        }
    }

    private companion object {
        const val METRIC = "inbound.rate.limited"
    }
}
