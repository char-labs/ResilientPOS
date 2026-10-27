package com.resilientpos.payment.internal.adapter.persistence.command

import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.foundation.Money
import com.resilientpos.payment.PaymentFixtures
import com.resilientpos.payment.internal.domain.PaymentAttempt
import com.resilientpos.payment.internal.domain.PaymentAttemptRepository
import com.resilientpos.store.StoreId
import com.resilientpos.terminal.TerminalId
import jakarta.persistence.EntityManagerFactory
import java.time.Clock
import java.util.Currency
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.hibernate.SessionFactory
import org.hibernate.stat.Statistics
import org.springframework.transaction.support.TransactionTemplate

/**
 * 새 시도 저장의 JDBC 문장 수를 Hibernate 통계로 고정한다. Docker가 필요하다.
 *
 * 도메인 ID를 저장 전에 만들기 때문에 Spring Data의 `save`는 `@Version`이 없는 새 엔티티를 기존 행으로 보고
 * `merge`한다. 그래서 PK SELECT가 하나 더 나가고 이 비용을 받아들였다([jpa-repository-adapters] 결정 2).
 *
 * **통계는 `SessionFactory` 전체의 누적값이다.** 이제 비동기 리스너가 같은 `SessionFactory`로 SQL을 내므로
 * 앞 테스트의 리스너 작업이 아직 큐에 남아 있으면 그 문장이 이 델타에 섞여 간헐 실패가 날 수 있다.
 * 제대로 고치려면 `statement_inspector`로 자기 스레드의 문장만 세야 하고, 그것은 별도 PR이 한다.
 */
class PaymentAttemptInsertStatementCountIntegrationTest(
    private val repository: PaymentAttemptRepository,
    private val transaction: TransactionTemplate,
    private val identifierGenerator: IdentifierGenerator,
    private val clock: Clock,
    entityManagerFactory: EntityManagerFactory,
) : PostgresIntegrationTest() {

    private val krw: Currency = Currency.getInstance("KRW")
    private val statistics: Statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
    private var statisticsWereEnabled = false

    @BeforeTest
    fun enableStatistics() {
        statisticsWereEnabled = statistics.isStatisticsEnabled
        statistics.isStatisticsEnabled = true
    }

    @AfterTest
    fun restoreStatistics() {
        statistics.isStatisticsEnabled = statisticsWereEnabled
    }

    @Test
    fun `새 시도 저장은 merge의 SELECT 한 번과 INSERT 한 번이다`() {
        val attempt = PaymentAttempt.create(
            PaymentFixtures.createAttemptCommand(
                orderId = UUID.randomUUID(),
                storeId = StoreId(UUID.randomUUID()),
                terminalId = TerminalId(UUID.randomUUID()),
                checkoutNo = 1,
                amount = Money.of("4500", krw),
            ),
            identifierGenerator,
            clock.instant(),
        ).aggregate

        val before = statistics.prepareStatementCount
        transaction.execute { repository.save(attempt) }
        val statements = statistics.prepareStatementCount - before

        assertEquals(2, statements, "저장에 나간 JDBC 문장 수(SELECT, INSERT)")
    }
}
