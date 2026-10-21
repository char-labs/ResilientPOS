package com.resilientpos.platform.deadletter

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.modulith.events.Externalized
import org.springframework.stereotype.Component

/**
 * 설정된 토픽(`resilientpos.kafka.topics`)마다 그 토픽으로 나가는 이벤트 타입. 재투입 전 읽기 검사가 이 타입으로 값을 읽는다.
 *
 * 기동할 때 클래스패스(`com.resilientpos`)의 `@Externalized` 타입을 훑어 만들고, 대상 문자열의 `::` 앞을 토픽으로 본다.
 * 이벤트 클래스를 import하면 `platform`이 업무 모듈을 의존해 `payment → platform`과 순환하므로 이름으로 찾는다(`KafkaConfiguration`과 같은 이유).
 * 설정된 토픽 하나라도 타입이 없거나 둘 이상이면 토픽 목록과 `@Externalized`가 어긋난 것이라 기동이 실패한다.
 */
@Component
class DeadLetterEventTypes(
    @Value("\${resilientpos.kafka.topics}") topics: List<String>,
) {
    private val types: Map<String, Class<*>> = externalized().groupBy(::topicOf).let { byTopic ->
        topics.associateWith { topic ->
            val found = byTopic[topic].orEmpty()
            check(found.size == 1) { "토픽 $topic 으로 나가는 @Externalized 타입이 하나가 아니다: ${found.map { it.name }}" }
            found.single()
        }
    }

    /** [topic]은 설정된 토픽이다. 다른 이름은 부르는 쪽의 버그라 [NoSuchElementException]이다. */
    fun typeOf(topic: String): Class<*> = types.getValue(topic)

    private fun externalized(): List<Class<*>> =
        ClassPathScanningCandidateComponentProvider(false)
            .apply { addIncludeFilter(AnnotationTypeFilter(Externalized::class.java)) }
            .findCandidateComponents(BASE_PACKAGE)
            .map { Class.forName(it.beanClassName) }

    private fun topicOf(type: Class<*>): String =
        checkNotNull(AnnotatedElementUtils.findMergedAnnotation(type, Externalized::class.java)).target.substringBefore("::")

    private companion object {
        const val BASE_PACKAGE = "com.resilientpos"
    }
}
