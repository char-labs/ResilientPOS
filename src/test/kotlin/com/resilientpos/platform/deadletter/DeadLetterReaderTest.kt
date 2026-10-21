package com.resilientpos.platform.deadletter

import com.resilientpos.platform.DependencyUnavailableException
import java.time.Duration
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.common.errors.TimeoutException
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.kafka.core.ConsumerFactory
import tools.jackson.databind.json.JsonMapper

/** 브로커에 닿지 못하는 경우. 실제 브로커로는 시간 초과를 재현하기 어려워 consumer만 바꾼다. */
class DeadLetterReaderTest {

    @Suppress("UNCHECKED_CAST") // Mockito는 제네릭 인터페이스의 타입 인자를 모른다.
    private val consumer = mock(Consumer::class.java) as Consumer<String, ByteArray>

    private val consumers = object : ConsumerFactory<String, ByteArray> {
        override fun createConsumer(groupId: String?, clientIdPrefix: String?, clientIdSuffix: String?, properties: Properties?) = consumer
        override fun isAutoCommit() = false
    }

    private val reader = DeadLetterReader(consumers, JsonMapper.builder().build(), mock(DeadLetterRedriveJpaRepository::class.java), listOf("order.paid"))

    @Test
    fun `브로커에 닿지 못해 Kafka 시간 초과가 나면 DependencyUnavailableException이고 consumer를 닫는다`() {
        val timeout = TimeoutException("Timeout expired while fetching topic metadata")
        given(consumer.partitionsFor(eq("order.paid.DLT"), any(Duration::class.java))).willThrow(timeout)

        val thrown = assertFailsWith<DependencyUnavailableException> { reader.read("order.paid.DLT", 0, 0, 50) }

        assertSame(timeout, thrown.cause)
        verify(consumer).close()
    }
}
