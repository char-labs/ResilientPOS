package com.resilientpos.platform.deadletter

import com.resilientpos.contract.OrderPaid
import com.resilientpos.contract.PaymentApproved
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource
import org.springframework.modulith.events.Externalized

/** 토픽 → 이벤트 타입 표. 클래스패스를 실제로 훑는다. */
class DeadLetterEventTypesTest {

    @Test
    fun `설정된 토픽마다 Externalized 대상의 토픽이 같은 이벤트 타입 하나를 찾는다`() {
        val topics = configuredTopics()

        val types = DeadLetterEventTypes(topics)

        assertEquals(OrderPaid::class.java, types.typeOf(OrderPaid.TOPIC))
        assertEquals(PaymentApproved::class.java, types.typeOf(PaymentApproved.TOPIC))
        topics.forEach { topic ->
            assertEquals(topic, types.typeOf(topic).getAnnotation(Externalized::class.java).value.substringBefore("::"), topic)
        }
    }

    @Test
    fun `설정된 토픽에 Externalized 타입이 없으면 그 토픽 이름과 함께 생성이 실패한다`() {
        val e = assertFailsWith<IllegalStateException> { DeadLetterEventTypes(listOf(OrderPaid.TOPIC, "order.unknown")) }

        assertContains(e.message.orEmpty(), "order.unknown")
    }

    /** `application.yaml`의 `resilientpos.kafka.topics`. */
    private fun configuredTopics(): List<String> {
        val properties = checkNotNull(YamlPropertiesFactoryBean().apply { setResources(ClassPathResource("application.yaml")) }.getObject())
        return properties.getProperty("resilientpos.kafka.topics").split(",")
    }
}
