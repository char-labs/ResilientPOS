package com.resilientpos.platform

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date

/**
 * 운영자 JWT를 테스트 리소스의 고정 RSA 키로 서명한다. 서명용 코드는 테스트에만 있다.
 * 앱은 운영과 같은 설정(issuer-uri, audiences, authorities-claim-name)으로 검증하고 서명 키만 [jwksUri]에서 받는다.
 * `public-key-location`은 `issuer-uri`가 함께 있으면 Boot가 무시하므로(`KeyValueCondition`) JWKS를 로컬 서버로 내준다.
 */
object OperatorJwt {
    const val ISSUER = "http://localhost:8180/realms/resilientpos"
    const val AUDIENCE = "resilientpos-admin"
    private const val KEY_ID = "operator-test"

    private val signing = loadKeyPair()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        val jwks = JWKSet(RSAKey.Builder(signing.public as RSAPublicKey).keyID(KEY_ID).build()).toString().toByteArray()
        createContext("/jwks") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, jwks.size.toLong())
            exchange.responseBody.use { it.write(jwks) }
        }
        start()
    }

    val jwksUri: String get() = "http://127.0.0.1:${server.address.port}/jwks"

    /** 기본값은 유효한 운영자 토큰이다. 검사할 항목만 바꿔 부른다. */
    fun token(
        issuer: String = ISSUER,
        audience: String = AUDIENCE,
        roles: List<String> = listOf("OPERATOR"),
        expiresAt: Instant = Instant.now().plusSeconds(300),
        key: KeyPair = signing,
    ): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject("operator-1")
            .claim("roles", roles)
            .expirationTime(Date.from(expiresAt))
            .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims)
        jwt.sign(RSASSASigner(key.private as RSAPrivateKey))
        return jwt.serialize()
    }

    fun otherKey(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun loadKeyPair(): KeyPair {
        val factory = KeyFactory.getInstance("RSA")
        val private = factory.generatePrivate(PKCS8EncodedKeySpec(pem("private.pem")))
        val public = factory.generatePublic(X509EncodedKeySpec(pem("public.pem")))
        return KeyPair(public, private)
    }

    private fun pem(name: String): ByteArray {
        val text = checkNotNull(OperatorJwt::class.java.getResource("/operator-jwt/$name")).readText()
        return Base64.getMimeDecoder().decode(text.lines().filterNot { it.startsWith("-----") }.joinToString(""))
    }
}
