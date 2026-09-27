package com.resilientpos.payment.internal.adapter.lock

import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Redis가 응답하지 않아도 결제 시도 생성을 멈추지 않는지 본다. 방어선은 DB unique라서 잠금은 fail-open이다.
 */
class RedisCheckoutLockTest {

    @BeforeTest
    fun startSynchronization() = TransactionSynchronizationManager.initSynchronization()

    @AfterTest
    fun clearSynchronization() = TransactionSynchronizationManager.clearSynchronization()

    @Test
    fun `Redis 오류면 잠그지 않고 돌아온다`() {
        val redis = mock(StringRedisTemplate::class.java)
        given(redis.opsForValue()).willThrow(RedisConnectionFailureException("Redis down"))

        RedisCheckoutLock(redis).lockUntilTransactionEnds(UUID.randomUUID(), 1)

        // 잠그지 않았으니 트랜잭션 끝에 풀 것도 없다.
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty())
    }
}
