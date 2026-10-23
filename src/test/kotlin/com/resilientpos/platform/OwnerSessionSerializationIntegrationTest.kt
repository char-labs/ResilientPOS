package com.resilientpos.platform

import com.resilientpos.PostgresIntegrationTest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import jakarta.servlet.http.Cookie

/**
 * 세션 속성이 Redis에 JSON으로 저장되고, 허용 목록 밖 타입이나 JDK 형식으로 남은 세션은 역직렬화되지 않고 401(다시 로그인)이 되는지
 * 실제 Redis로 검사한다. Docker가 필요하다.
 */
class OwnerSessionSerializationIntegrationTest(
    private val mockMvc: MockMvc,
    private val redis: RedisConnectionFactory,
) : PostgresIntegrationTest() {

    private val context = "sessionAttr:SPRING_SECURITY_CONTEXT".toByteArray()

    private fun key(cookie: Cookie): ByteArray = "spring:session:sessions:${String(Base64.getDecoder().decode(cookie.value))}".toByteArray()

    private fun <T> withRedis(block: (org.springframework.data.redis.connection.RedisConnection) -> T): T =
        redis.connection.use(block)

    private fun listStores(cookie: Cookie) = mockMvc.get("/owner/v1/stores") { cookie(cookie) }

    @Test
    fun `로그인한 세션의 SecurityContext는 JDK 바이트가 아니라 JSON으로 저장되고 로그인 인증 로그아웃이 동작한다`() {
        val owner = mockMvc.logInNewOwner()

        val stored = assertNotNull(withRedis { it.hashCommands().hGet(key(owner.session), context) })

        assertEquals('{', String(stored).first(), String(stored))
        assertFalse(stored[0] == 0xAC.toByte() && stored[1] == 0xED.toByte(), "JDK 직렬화 매직으로 시작한다")
        assertTrue(String(stored).contains(owner.ownerId.toString()), String(stored))
        listStores(owner.session).andExpect { status { isOk() } }
        mockMvc.post("/owner/v1/logout") { asOwner(owner) }.andExpect { status { isNoContent() } }
        listStores(owner.session).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `허용 목록 밖 타입이 든 세션 값은 역직렬화하지 않고 401이다`() {
        val owner = mockMvc.logInNewOwner()
        GadgetProbe.created = false
        val gadget = """{"@class":"${GadgetProbe::class.java.name}"}""".toByteArray()
        withRedis { it.hashCommands().hSet(key(owner.session), context, gadget) }

        listStores(owner.session).andExpect {
            status { isUnauthorized() }
            jsonPath("$.error.errorCode") { value("UNAUTHENTICATED") }
        }

        assertFalse(GadgetProbe.created, "허용 목록 밖 타입이 만들어졌다")
    }

    @Test
    fun `JDK 직렬화 형식으로 남은 옛 세션은 503이 아니라 401이고 같은 쿠키로 다시 로그인할 수 있다`() {
        val owner = mockMvc.logInNewOwner()
        val jdk = JdkSerializationRedisSerializer()
        withRedis { connection ->
            val hash = connection.hashCommands()
            hash.hSet(key(owner.session), "creationTime".toByteArray(), jdk.serialize(System.currentTimeMillis()))
            hash.hSet(key(owner.session), "lastAccessedTime".toByteArray(), jdk.serialize(System.currentTimeMillis()))
            hash.hSet(key(owner.session), "maxInactiveInterval".toByteArray(), jdk.serialize(1800))
            hash.hSet(key(owner.session), context, jdk.serialize("legacy"))
        }

        listStores(owner.session).andExpect {
            status { isUnauthorized() }
            jsonPath("$.error.errorCode") { value("UNAUTHENTICATED") }
        }
        val csrf = mockMvc.issueCsrfToken()
        mockMvc.post("/owner/v1/login") {
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{ "email": "${owner.email}", "password": "$OWNER_PASSWORD" }"""
            withCsrf(csrf)
            cookie(owner.session)
        }.andExpect { status { isOk() } }
    }
}

/** 허용 목록 밖 타입. 생성자가 불리면 역직렬화가 타입 검증 없이 인스턴스를 만든 것이다. */
class GadgetProbe {
    init {
        created = true
    }

    companion object {
        @Volatile
        var created = false
    }
}
