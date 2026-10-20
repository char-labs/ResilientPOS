package com.resilientpos.contract

import com.resilientpos.KafkaTestSupport
import com.resilientpos.PostgresIntegrationTest
import com.resilientpos.awaitUntil
import com.resilientpos.foundation.DomainException
import com.resilientpos.payment.PaymentEvent
import io.micrometer.core.instrument.MeterRegistry
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.fail
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.converter.RecordMessageConverter
import org.springframework.kafka.support.mapping.DefaultJacksonJavaTypeMapper
import org.springframework.messaging.support.MessageBuilder
import org.springframework.modulith.events.Externalized
import org.springframework.modulith.events.core.EventSerializer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/**
 * 외부로 나가는 이벤트의 저장 형식 계약. Kafka 값과 발행 기록(`serialized_event`)을 앱이 실제로 쓰는 빈으로 읽고 쓴다.
 * 규칙과 fixture 갱신 방법은 `docs/wiki/event-contract.md`가 정본이다.
 */
class EventContractCompatibilityTest(
    @Autowired private val converter: RecordMessageConverter,
    @Autowired private val eventSerializer: EventSerializer,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val kafkaTemplate: KafkaTemplate<Any, Any>,
    @Autowired private val meterRegistry: MeterRegistry,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : PostgresIntegrationTest() {

    @Test
    fun `Externalized 이벤트마다 계약과 fixture가 있고 마지막 fixture가 지금 버전이다`() {
        val externalized = ClassPathScanningCandidateComponentProvider(false)
            .apply { addIncludeFilter(AnnotationTypeFilter(Externalized::class.java)) }
            .findCandidateComponents("com.resilientpos")
            .map { Class.forName(it.beanClassName) }
            .toSet()

        assertEquals(externalized, EventContracts.all.map { it.type }.toSet(), "EventContracts에 없거나 남은 이벤트가 있다")
        EventContracts.all.forEach { contract ->
            assertEquals(contract.topic, contract.type.getAnnotation(Externalized::class.java).value.substringBefore("::"))
            val latest = latest(contract)
            assertEquals(contract.schemaVersion, jsonMapper.readTree(latest.json)["schemaVersion"]?.intValue(), latest.name)
        }
    }

    @Test
    fun `쌓인 fixture를 두 경로가 같게 읽고 지금 버전 fixture의 값을 잃지 않는다`() {
        EventContracts.all.forEach { contract ->
            fixtures(contract).forEach { fixture ->
                val read = readByKafka(contract, fixture.json)
                assertEquals(read, readByRegistry(contract, fixture.json), fixture.name)

                val stored = jsonMapper.readTree(fixture.json)
                // 옛 버전 fixture의 기대값은 변환(upcast)을 더하는 PR이 정한다.
                if (stored["schemaVersion"]?.intValue() == contract.schemaVersion) {
                    assertRetains(stored, jsonMapper.readTree(eventSerializer.serialize(read).toString()), fixture.name)
                }
            }
        }
    }

    @Test
    fun `지금 직렬화가 두 경로 모두 마지막 fixture와 같다`() {
        EventContracts.all.forEach { contract ->
            val latest = latest(contract)
            val expected = jsonMapper.readTree(latest.json)
            val written = eventSerializer.serialize(contract.sample).toString()
            assertEquals(expected, jsonMapper.readTree(written), "${latest.name}와 형식이 다르다. 바꾼 형식은 다음 번호 fixture로 더한다: $written")

            val record = converter.fromMessage(MessageBuilder.withPayload(contract.sample).build(), contract.topic)
            assertEquals(expected, jsonMapper.readTree(record.value() as ByteArray), "Kafka 값: ${latest.name}")
        }
    }

    @Test
    fun `모르는 필드가 더해진 메시지도 두 경로가 읽는다`() {
        EventContracts.all.forEach { contract ->
            val latest = latest(contract)
            val withUnknown = (jsonMapper.readTree(latest.json) as ObjectNode).put("addedLater", "새 버전이 더한 필드").toString()

            assertEquals(contract.sample, readByKafka(contract, withUnknown), latest.name)
            assertEquals(contract.sample, readByRegistry(contract, withUnknown), latest.name)
        }
    }

    @Test
    fun `빠진 필드와 모르는 enum 값을 규칙 표대로 읽거나 거절한다`() {
        val approved = contractOf(PaymentApproved::class.java)
        val base = jsonMapper.readTree(latest(approved).json) as ObjectNode
        fun without(vararg names: String) = base.deepCopy().apply { names.forEach { remove(it) } }.toString()

        readBoth(approved, without("providerReference", "schemaVersion")).forEach { read ->
            read as PaymentApproved
            assertNull(read.providerReference, "빠진 nullable은 null이다")
            assertEquals(1, read.schemaVersion, "빠진 schemaVersion은 1이다. 첫 변환 PR이 이 기대를 다시 정한다")
        }
        listOf(without("orderId"), without("checkoutNo"), base.deepCopy().put("kind", "REFUND").toString()).forEach { json ->
            assertFails("빠진 non-null과 모르는 enum 값은 실패한다: $json") { readByKafka(approved, json) }
            assertFails("빠진 non-null과 모르는 enum 값은 실패한다: $json") { readByRegistry(approved, json) }
        }
    }

    @Test
    fun `머지된 enum 이름과 null 값을 두 경로가 읽는다`() {
        // 머지된 이름이다. DLT·발행 기록에 남아 있을 수 있어 지우거나 바꾸면 안 된다. 새 값은 여기에 더한다.
        val mergedKinds = listOf("AUTHORIZATION", "CANCELLATION")
        listOf(PaymentApproved::class.java, PaymentEvent.AttemptCreated::class.java).map(::contractOf).forEach { contract ->
            mergedKinds.forEach { kind ->
                val stored = (jsonMapper.readTree(latest(contract).json) as ObjectNode).put("kind", kind)
                if (stored.has("providerReference")) stored.putNull("providerReference")

                val (byKafka, byRegistry) = readBoth(contract, stored.toString())
                assertEquals(byKafka, byRegistry, "${contract.topic} kind=$kind")
                assertEquals(stored, jsonMapper.readTree(eventSerializer.serialize(byKafka).toString()), "${contract.topic} kind=$kind")
            }
        }
    }

    @Test
    fun `아는 버전보다 높은 schemaVersion은 Kafka 변환기와 발행 기록 복원 모두 도메인 코드로 거절한다`() {
        EventContracts.all.forEach { contract ->
            val newer = serializedWithVersion(contract.sample, contract.schemaVersion + 1)

            assertRejected(contract) { readByKafka(contract, newer) }
            assertRejected(contract) { readByRegistry(contract, newer) }
        }
    }

    @Test
    fun `아는 버전보다 높은 메시지는 DLT로 가고 도메인 코드로 세며 뒤 메시지는 처리된다`() {
        val before = deadLetters(OrderPaid.TOPIC)
        val newer = UUID.randomUUID()
        val next = UUID.randomUUID()

        sendOrderPaid(newer, OrderPaid.SCHEMA_VERSION + 1)
        sendOrderPaid(next, OrderPaid.SCHEMA_VERSION)

        awaitUntil("DLT에 들어간다") { KafkaTestSupport.records(OrderPaid.TOPIC + ".DLT", newer).size == 1 }
        awaitUntil("뒤 주문의 fulfillment가 생긴다") { fulfillmentCount(next) == 1L }
        assertEquals(before + 1, deadLetters(OrderPaid.TOPIC))
        assertEquals(0L, fulfillmentCount(newer))
    }

    /** 고정 인스턴스의 주문만 바꿔 보낸다. */
    private fun sendOrderPaid(orderId: UUID, schemaVersion: Int) {
        val event = (contractOf(OrderPaid::class.java).sample as OrderPaid).copy(orderId = orderId)
        kafkaTemplate.send(OrderPaid.TOPIC, orderId.toString(), serializedWithVersion(event, schemaVersion).toByteArray())
            .get(5, TimeUnit.SECONDS)
    }

    /** 높은 버전은 가드 때문에 인스턴스로 만들 수 없어 직렬화한 JSON에서 고친다. */
    private fun serializedWithVersion(event: Any, schemaVersion: Int): String =
        (jsonMapper.readTree(eventSerializer.serialize(event).toString()) as ObjectNode).put("schemaVersion", schemaVersion).toString()

    private fun deadLetters(topic: String) =
        meterRegistry.find("event.kafka.dead-letter").tag("topic", topic).tag("reason", UNSUPPORTED_EVENT_SCHEMA_VERSION)
            .counter()?.count() ?: 0.0

    private fun fulfillmentCount(orderId: UUID) =
        jdbcTemplate.queryForObject<Long>("select count(*) from t_fulfillment where order_id = ?", orderId)

    private data class Fixture(val name: String, val json: String)

    /** `event-contract/토픽/번호.json`을 번호 순으로 읽는다. 번호는 1부터 빈틈없이 이어져야 한다. */
    private fun fixtures(contract: EventContract): List<Fixture> {
        val numbered = PathMatchingResourcePatternResolver().getResources("classpath*:event-contract/${contract.topic}/*.json")
            .map { resource ->
                val name = checkNotNull(resource.filename)
                val number = name.removeSuffix(".json").toIntOrNull() ?: fail("${contract.topic}/$name: fixture 이름은 번호.json이다")
                number to Fixture("${contract.topic}/$name", resource.contentAsByteArray.decodeToString())
            }
            .sortedBy { it.first }
        assertEquals((1..numbered.size).toList(), numbered.map { it.first }, "${contract.topic}: fixture 번호는 1부터 빈틈없이 잇는다")
        return numbered.map { it.second }
    }

    private fun contractOf(type: Class<*>) = EventContracts.all.single { it.type == type }

    private fun latest(contract: EventContract) = fixtures(contract).lastOrNull() ?: fail("${contract.topic}: fixture가 없다")

    /** 저장된 필드가 다시 쓴 JSON에 같은 값으로 남는지 본다. 지금 클래스에만 있는 필드(뒤에 더한 것)는 상관없다. */
    private fun assertRetains(stored: JsonNode, rewritten: JsonNode, path: String) {
        if (!stored.isObject) return assertEquals(stored, rewritten, path)
        stored.properties().forEach { (name, value) ->
            val actual = rewritten[name] ?: fail("$path: $name 이 사라졌다(삭제나 이름 변경)")
            assertRetains(value, actual, "$path.$name")
        }
    }

    /** 다른 클래스 이름의 타입 헤더를 단다. 읽기가 헤더가 아니라 파라미터 타입을 쓰는지(`INFERRED`)도 함께 본다. */
    private fun readByKafka(contract: EventContract, json: String): Any {
        val record = ConsumerRecord(contract.topic, 0, 0L, "key", json.toByteArray()).apply {
            headers().add(DefaultJacksonJavaTypeMapper.DEFAULT_CLASSID_FIELD_NAME, "com.resilientpos.moved.Event".toByteArray())
        }
        return converter.toMessage(record, null, null, contract.type).payload
    }

    private fun readBoth(contract: EventContract, json: String) = listOf(readByKafka(contract, json), readByRegistry(contract, json))

    private fun readByRegistry(contract: EventContract, json: String): Any = eventSerializer.deserialize(json, contract.type)

    private fun assertRejected(contract: EventContract, read: () -> Any) {
        val thrown = assertFails("${contract.type.simpleName}: 높은 버전을 읽었다") { read() }
        val code = generateSequence(thrown) { it.cause }.filterIsInstance<DomainException>().firstOrNull()?.code
        assertEquals(UNSUPPORTED_EVENT_SCHEMA_VERSION, code, "${contract.type.simpleName}: $thrown")
    }
}
