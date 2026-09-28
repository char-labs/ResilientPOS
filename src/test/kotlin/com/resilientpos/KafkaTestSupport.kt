package com.resilientpos

import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.fail
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.serialization.StringDeserializer

/** 통합 테스트가 브로커를 직접 읽는 도구. 앱의 consumer group과 겹치지 않게 group 없이 파티션을 배정받는다. */
object KafkaTestSupport {

    private val bootstrapServers get() = PostgresIntegrationTest.kafka.bootstrapServers

    /** 토픽의 처음부터 지금 끝까지 읽는다. */
    fun records(topic: String): List<ConsumerRecord<String, String>> =
        KafkaConsumer(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                ConsumerConfig.CLIENT_ID_CONFIG to "test-reader-${UUID.randomUUID()}",
            ),
            StringDeserializer(),
            StringDeserializer(),
        ).use { consumer ->
            val partitions = consumer.partitionsFor(topic, Duration.ofSeconds(5)).map { TopicPartition(topic, it.partition()) }
            consumer.assign(partitions)
            consumer.seekToBeginning(partitions)
            val end = consumer.endOffsets(partitions)
            val result = mutableListOf<ConsumerRecord<String, String>>()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (partitions.any { consumer.position(it) < end.getValue(it) }) {
                if (System.nanoTime() >= deadline) fail("10초 안에 $topic 끝까지 읽지 못했다")
                result += consumer.poll(Duration.ofMillis(200))
            }
            result
        }

    fun records(topic: String, key: UUID) = records(topic).filter { it.key() == key.toString() }

    /** group이 토픽의 지금 끝까지 offset을 커밋할 때까지 기다린다. 앞에 넣은 메시지의 처리가 끝났다는 뜻이다. */
    fun awaitConsumed(groupId: String, topic: String) = admin { admin ->
        val partitions = admin.describeTopics(listOf(topic)).allTopicNames().get().getValue(topic)
            .partitions().map { TopicPartition(topic, it.partition()) }
        val end = admin.listOffsets(partitions.associateWith { OffsetSpec.latest() }).all().get()
            .mapValues { it.value.offset() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (true) {
            val committed = admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata().get()
            if (partitions.all { (committed[it]?.offset() ?: 0) >= end.getValue(it) }) break
            if (System.nanoTime() >= deadline) fail("20초 안에 $groupId 가 $topic 을 끝까지 처리하지 못했다: $committed / $end")
            Thread.sleep(50)
        }
    }

    fun topicConfig(topic: String, name: String): String = admin { admin ->
        val resource = ConfigResource(ConfigResource.Type.TOPIC, topic)
        admin.describeConfigs(listOf(resource)).all().get().getValue(resource).get(name).value()
    }

    fun topicPartitionCounts(): Map<String, Int> = admin { admin ->
        val names = admin.listTopics().names().get()
        admin.describeTopics(names).allTopicNames().get().mapValues { it.value.partitions().size }
    }

    private fun <T> admin(block: (Admin) -> T): T =
        Admin.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers)).use(block)
}
