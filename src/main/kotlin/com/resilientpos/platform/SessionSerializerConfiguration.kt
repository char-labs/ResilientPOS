package com.resilientpos.platform

import java.time.Duration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializer
import org.springframework.security.jackson.SecurityJacksonModules
import org.springframework.session.MapSession
import org.springframework.session.config.SessionRepositoryCustomizer
import org.springframework.session.data.redis.RedisSessionMapper
import org.springframework.session.data.redis.RedisSessionRepository
import tools.jackson.databind.JavaType
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

/**
 * Spring Session이 Redis에 쓰는 세션 속성을 JSON으로 직렬화한다. 이름이 `springSessionDefaultRedisSerializer`인 빈을 Spring Session이 가져다 쓴다.
 * 다형성 타입은 Spring Security가 허용하는 타입만 읽는다(allowlist). JDK 직렬화는 Security 버전이 오르면 `serialVersionUID`가 달라져
 * 기존 세션을 읽지 못하고, Redis에 쓸 수 있는 주체가 역직렬화로 코드를 실행할 수 있다.
 */
@Configuration
class SessionSerializerConfiguration {

    @Bean
    fun springSessionDefaultRedisSerializer(): RedisSerializer<Any> {
        val mapper = JsonMapper.builder().addModules(SecurityJacksonModules.getModules(javaClass.classLoader)).build()
        return SessionJsonSerializer(mapper)
    }

    /**
     * 세션 메타데이터(생성·접근 시각, 유휴 만료)를 읽지 못하면 이미 만료된 세션으로 돌려줘 저장소가 지우게 한다.
     * 기본 매퍼는 이 값이 없으면 `IllegalStateException`을 던져 요청이 500이 된다. 옛 JDK 형식 세션이 그 경우다.
     */
    @Bean
    fun sessionMapperCustomizer() = SessionRepositoryCustomizer<RedisSessionRepository> { repository ->
        val mapper = RedisSessionMapper()
        repository.setRedisSessionMapper { id, entries ->
            try {
                mapper.apply(id, entries)
            } catch (e: IllegalStateException) {
                MapSession(id).apply { maxInactiveInterval = Duration.ZERO }
            }
        }
    }
}

/**
 * [GenericJacksonJsonRedisSerializer]는 최상위 값의 `@class`를 허용 목록(PolymorphicTypeValidator)을 거치지 않고 읽을 타입으로 삼는다(4.1.1).
 * 그러면 Redis에 쓸 수 있는 주체가 최상위 값에 임의 클래스를 지정할 수 있다. 읽을 타입을 `Object`로 고정해 Jackson의 기본 타입 처리가 허용 목록으로 검사하게 한다.
 * 읽지 못하는 값(옛 JDK 직렬화 형식, 허용 목록 밖 타입)은 값이 없는 것으로 다룬다. 세션은 그 속성이 없는 상태가 되어 인증되지 않은 요청과 같다.
 */
internal class SessionJsonSerializer(private val mapper: ObjectMapper) : GenericJacksonJsonRedisSerializer(mapper) {

    override fun resolveType(source: ByteArray, type: Class<*>): JavaType = mapper.typeFactory.constructType(Any::class.java)

    override fun deserialize(source: ByteArray?): Any? =
        try {
            super.deserialize(source)
        } catch (e: org.springframework.data.redis.serializer.SerializationException) {
            log.warn { "세션 값을 읽지 못해 없는 값으로 다룬다: ${e.message}" }
            null
        }

    private companion object {
        val log by logger()
    }
}
