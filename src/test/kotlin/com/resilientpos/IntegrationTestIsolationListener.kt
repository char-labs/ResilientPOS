package com.resilientpos

import com.resilientpos.payment.internal.adapter.gateway.ScriptedPaymentGateway
import java.time.Duration
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestContext
import org.springframework.test.context.TestExecutionListener

/**
 * 통합 테스트 사이를 격리한다. `META-INF/spring.factories`로 등록한다.
 *
 * 테스트 클래스가 시작하기 전에 Flyway 이력 표를 뺀 모든 표를 비운다. 컨텍스트는 다른 클래스와 같이 쓰고 데이터만 비워서,
 * 앞 클래스가 남긴 행이 매장·전체 개수를 세는 단언에 섞이지 않는다. 클래스 안의 테스트끼리는 테스트마다 새 ID로 격리한다.
 * 테스트 메서드 앞에서 비우지 않는 것은 JUnit이 테스트 인스턴스를 먼저 만들어, 필드 초기화가 넣은 행(단말 등록 등)이 지워지기 때문이다.
 * 앞 클래스의 비동기 consumer가 비운 뒤에 쓰는 행은 막지 못한다. 그 consumer의 트랜잭션과 교착하면 잠깐 뒤 다시 비운다.
 *
 * 테스트 메서드가 끝나면 공용 결제 gateway에 건 동작과 기록을 지운다.
 */
class IntegrationTestIsolationListener : TestExecutionListener {

    override fun beforeTestClass(testContext: TestContext) {
        testContext.applicationContext.getBeanProvider(JdbcTemplate::class.java).ifAvailable(::truncateAll)
    }

    override fun afterTestMethod(testContext: TestContext) {
        testContext.applicationContext.getBeanProvider(ScriptedPaymentGateway::class.java).ifAvailable(ScriptedPaymentGateway::reset)
    }

    private fun truncateAll(jdbcTemplate: JdbcTemplate) {
        // 표 이름은 카탈로그에서 읽은 값이라 입력이 아니다. 따옴표로 감싸 예약어와 대소문자를 그대로 둔다.
        val tables = jdbcTemplate.queryForList(
            "select tablename from pg_tables where schemaname = current_schema() and tablename <> 'flyway_schema_history'",
            String::class.java,
        )
        if (tables.isEmpty()) return
        val truncate = "truncate table " + tables.joinToString { "\"$it\"" }
        // 앞 클래스의 비동기 consumer 트랜잭션이 표를 잡고 있으면 교착으로 이쪽이 취소된다. 그 트랜잭션은 곧 끝나므로 잠깐 뒤 다시 비운다.
        repeat(TRUNCATE_ATTEMPTS - 1) {
            try {
                jdbcTemplate.execute(truncate)
                return
            } catch (_: PessimisticLockingFailureException) {
                Thread.sleep(TRUNCATE_RETRY_DELAY.toMillis())
            }
        }
        jdbcTemplate.execute(truncate)
    }

    private companion object {
        const val TRUNCATE_ATTEMPTS = 5
        val TRUNCATE_RETRY_DELAY: Duration = Duration.ofMillis(200)
    }
}
