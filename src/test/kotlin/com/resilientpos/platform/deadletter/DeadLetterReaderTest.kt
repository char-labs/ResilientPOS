package com.resilientpos.platform.deadletter

import com.resilientpos.platform.DependencyUnavailableException
import java.time.Duration
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.common.PartitionInfo
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.TimeoutException
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.kafka.core.ConsumerFactory
import tools.jackson.databind.json.JsonMapper

/** 실제 브로커로 재현하기 어려운 경우(시간 초과, log 시작보다 앞의 offset). consumer만 바꾼다. */
class DeadLetterReaderTest {

    @Suppress("UNCHECKED_CAST") // Mockito는 제네릭 인터페이스의 타입 인자를 모른다.
    private val consumer = mock(Consumer::class.java) as Consumer<String, ByteArray>

    private fun readerOf(consumer: Consumer<String, ByteArray>) = DeadLetterReader(
        object : ConsumerFactory<String, ByteArray> {
            override fun createConsumer(groupId: String?, clientIdPrefix: String?, clientIdSuffix: String?, properties: Properties?) = consumer
            override fun isAutoCommit() = false
        },
        JsonMapper.builder().build(),
        mock(DeadLetterRedriveJpaRepository::class.java),
        listOf("order.paid"),
    )

    /** 파티션 하나짜리 DLT의 log 시작과 끝을 정한 Kafka의 메모리 consumer. */
    private fun MockConsumer<String, ByteArray>.withLog(start: Long, end: Long) = apply {
        updatePartitions(DLT, listOf(PartitionInfo(DLT, 0, null, emptyArray(), emptyArray())))
        updateBeginningOffsets(mapOf(PARTITION to start))
        updateEndOffsets(mapOf(PARTITION to end))
    }

    @Test
    fun `브로커에 닿지 못해 Kafka 시간 초과가 나면 DependencyUnavailableException이고 consumer를 닫는다`() {
        val timeout = TimeoutException("Timeout expired while fetching topic metadata")
        given(consumer.partitionsFor(eq(DLT), any(Duration::class.java))).willThrow(timeout)

        val thrown = assertFailsWith<DependencyUnavailableException> { readerOf(consumer).read(DLT, 0, 0, 50) }

        assertSame(timeout, thrown.cause)
        verify(consumer).close()
    }

    @Test
    fun `요청 offset이 log 시작보다 앞이면 log 시작의 레코드를 돌려주지 않고 null이다`() {
        val log = MockConsumer<String, ByteArray>("earliest").withLog(start = 5, end = 10)
        // assign 뒤에만 레코드를 넣을 수 있다. 첫 poll이 log 시작(5)의 레코드를 받는다.
        log.schedulePollTask { log.addRecord(ConsumerRecord(DLT, 0, 5L, "018f6b1a-0000-7000-8000-0000000000e1", "{}".toByteArray())) }

        assertNull(readerOf(log).readRecord(DLT, 0, 2))
        assertTrue(log.closed())
    }

    @Test
    fun `poll이 계속 비어 position이 그대로면 5초 deadline에서 DependencyUnavailableException이고 consumer를 닫는다`() {
        val idle = object : MockConsumer<String, ByteArray>("earliest") {
            // 실제 poll은 레코드가 없으면 시간 초과까지 기다린다. MockConsumer는 곧바로 돌아와 5초 동안 빈 루프를 돌므로 그 기다림을 흉내 낸다.
            override fun poll(timeout: Duration): ConsumerRecords<String, ByteArray> = super.poll(timeout).also { Thread.sleep(timeout.toMillis()) }
        }.withLog(start = 0, end = 1)
        val started = System.nanoTime()

        val thrown = assertFailsWith<DependencyUnavailableException> { readerOf(idle).read(DLT, 0, 0, 50) }

        assertIs<TimeoutException>(thrown.cause)
        assertTrue(Duration.ofNanos(System.nanoTime() - started) >= Duration.ofSeconds(5), "deadline 전에 끝났다")
        assertTrue(idle.closed())
    }

    private companion object {
        const val DLT = "order.paid.DLT"
        val PARTITION = TopicPartition(DLT, 0)
    }
}
