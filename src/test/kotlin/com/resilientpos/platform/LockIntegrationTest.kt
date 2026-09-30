package com.resilientpos.platform

import com.resilientpos.PostgresIntegrationTest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * [Lock] 블록이 같은 키끼리 줄 서고, 블록의 트랜잭션을 커밋한 뒤에 풀리는지 본다. 실제 Redis와 PostgreSQL을 쓴다.
 * 블록이 쓰는 표는 이 테스트만 쓰는 탐침이다. 컨테이너를 다른 테스트와 함께 쓰므로 끝나면 지운다 —
 * 남기면 `SchemaMigrationTest`의 테이블 목록 검사가 실행 순서에 따라 깨진다.
 */
class LockIntegrationTest(
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val transactions: TransactionOperations,
    @Autowired private val distributedLock: DistributedLock,
    @Autowired private val redis: StringRedisTemplate,
    @Autowired transactionManager: PlatformTransactionManager,
) : PostgresIntegrationTest() {

    private val requiresNew = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @BeforeTest
    fun createProbe() {
        jdbcTemplate.execute("create table if not exists t_lock_probe (id uuid primary key)")
    }

    @AfterTest
    fun dropProbe() {
        jdbcTemplate.execute("drop table if exists t_lock_probe")
    }

    private fun probes(id: UUID): Long =
        checkNotNull(jdbcTemplate.queryForObject<Long>("select count(*) from t_lock_probe where id = ?", id))

    private fun insertProbe(id: UUID) = jdbcTemplate.update("insert into t_lock_probe (id) values (?)", id)

    @Test
    fun `같은 키의 블록은 앞 블록이 커밋한 뒤에 돌아 그 쓰기를 본다`() {
        val key = "test:lock:${UUID.randomUUID()}"
        val id = UUID.randomUUID()
        val entered = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val first = executor.submit {
                Lock.executeWithLock(key) {
                    insertProbe(id)
                    entered.countDown()
                    // 커밋을 늦춰 뒤 블록이 잠금 없이 들어오면 커밋 전 상태를 읽게 한다.
                    Thread.sleep(300)
                }
            }
            entered.await(5, TimeUnit.SECONDS)

            val seen = Lock.executeWithLock(key) { probes(id) }

            first.get(5, TimeUnit.SECONDS)
            assertEquals(1, seen)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `바깥 트랜잭션 안에서 부르면 잠그기 전에 거절한다`() {
        val key = "test:lock:${UUID.randomUUID()}"
        val id = UUID.randomUUID()

        assertFailsWith<IllegalStateException> {
            transactions.executeWithoutResult { Lock.executeWithLock(key) { insertProbe(id) } }
        }
        assertFalse(redis.hasKey(key))
        assertEquals(0, probes(id))
    }

    @Test
    fun `블록이 던지면 롤백한 뒤 락을 푼다`() {
        val key = "test:lock:${UUID.randomUUID()}"
        val id = UUID.randomUUID()

        assertFailsWith<IllegalStateException> {
            Lock.executeWithLock(key) {
                insertProbe(id)
                error("블록 실패")
            }
        }

        assertEquals(0, probes(id))
        assertFalse(redis.hasKey(key))
    }

    @Test
    fun `트랜잭션 없는 블록은 줄만 세우고 쓰기는 각자 바로 커밋한다`() {
        val key = "test:lock:${UUID.randomUUID()}"
        val id = UUID.randomUUID()

        val seenFromAnotherConnection = Lock.executeWithLockNoTx(key) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            insertProbe(id)
            // 블록이 끝나기 전에 새 트랜잭션(다른 커넥션)으로 읽는다. 블록이 트랜잭션 안이면 커밋 전이라 0이다.
            requiresNew.execute { probes(id) }
        }

        assertEquals(1, seenFromAnotherConnection)
        assertFalse(redis.hasKey(key))
    }

    @Test
    fun `트랜잭션 없는 블록을 트랜잭션 안에서 부르면 잠그기 전에 거절한다`() {
        val key = "test:lock:${UUID.randomUUID()}"

        assertFailsWith<IllegalStateException> {
            transactions.executeWithoutResult { Lock.executeWithLockNoTx(key) { } }
        }
        assertFalse(redis.hasKey(key))
    }

    @Test
    fun `토큰이 다르면 풀지 않는다`() {
        val key = "test:lock:${UUID.randomUUID()}"
        val token = checkNotNull(distributedLock.tryAcquire(key))

        distributedLock.release(key, "다른 요청의 토큰")
        val heldAfterForeignRelease = redis.hasKey(key)
        distributedLock.release(key, token)

        assertTrue(heldAfterForeignRelease)
        assertFalse(redis.hasKey(key))
    }
}
