package com.resilientpos.platform

import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.foundation.UuidV7IdentifierGenerator
import io.lettuce.core.ClientOptions
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.time.Clock
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.session.web.http.SessionRepositoryFilter
import org.springframework.web.servlet.FlashMap
import org.springframework.web.servlet.FlashMapManager
import org.springframework.web.servlet.HandlerExceptionResolver

/**
 * `foundation`은 프레임워크를 모르므로 Spring 빈 등록은 여기서 한다.
 * Facade는 시각을 `Clock`으로 받아 테스트에서 고정할 수 있다.
 * [Tx]의 러너와 [Lock]의 잠금 저장소도 여기서 넣는다. 컨텍스트가 뜰 때마다 그 컨텍스트의 빈으로 바뀐다.
 * 스케줄링은 [EventPublicationResubmitter]와 payment의 결과 불명 확정 작업(`UnknownPaymentResolver`)이 쓴다. Moments 자동 구성도 켜지만 거기에 기대지 않는다.
 */
@Configuration
@EnableScheduling
class PlatformConfiguration {

    @Bean
    fun identifierGenerator(): IdentifierGenerator = UuidV7IdentifierGenerator()

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /**
     * 기본 `SessionFlashMapManager`는 DispatcherServlet이 요청마다 `getSession(false)`로 세션을 읽게 해, 세션 쿠키만 있으면 단말 API(`/v1` 아래)까지 Redis를 읽는다.
     * 이 API는 redirect 속성을 쓰지 않으므로 아무것도 하지 않는 구현으로 바꾼다. 빈 이름 `flashMapManager`는 DispatcherServlet이 찾는 이름이다.
     */
    @Bean
    fun flashMapManager(): FlashMapManager = object : FlashMapManager {
        override fun retrieveAndUpdate(request: HttpServletRequest, response: HttpServletResponse): FlashMap? = null
        override fun saveOutputFlashMap(flashMap: FlashMap, request: HttpServletRequest, response: HttpServletResponse) = Unit
    }

    /**
     * Lettuce 기본값은 연결이 끊긴 동안 명령을 버퍼에 받아 둔다. 그러면 Redis가 재시작하는 동안 단말 요청(빈도 상한)마다 timeout을 다 기다리고,
     * 끝난 명령도 재연결 전까지 버퍼에 남아 장애가 길면 힙이 불어난다. 바로 거절하면 빈도 상한과 잠금은 즉시 fail-open, 세션과 로그인은 즉시 503이다.
     * 응답 없는 명령(패킷이 사라지는 단절)도 기본값은 무제한으로 쌓아 연결이 끊길 때까지(커널 재전송 한도, 약 15분) 요청마다 timeout을 기다리게 한다.
     * 1000개까지만 받으면 그 뒤로는 바로 거절돼 즉시 fail-open이다. 1000은 요청 스레드(Tomcat 기본 200)와 주기 작업이 한꺼번에 내는 명령보다 넉넉히 크다.
     * 스레드 수를 바꾸는 변경(virtual thread 포함)은 이 값을 다시 본다. 정상 부하에서 넘으면 빈도 상한은 fail-open(WARN 10초에 한 줄), 세션은 503이 된다.
     * queue가 차면 Lettuce가 거절마다 스택이 붙은 WARN을 남기므로 그 로거는 application.yaml에서 ERROR로 올린다.
     * Boot가 connect timeout과 `TimeoutOptions`를 넣은 뒤에 적용되므로 그 설정은 남는다. TCP keepalive는 Lettuce 기본값(켜짐)이다.
     */
    @Bean
    fun redisClientOptions() = LettuceClientOptionsBuilderCustomizer {
        it.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).requestQueueSize(REDIS_REQUEST_QUEUE_SIZE)
    }

    @Bean
    fun txInitializer(txRunner: TxRunner) = InitializingBean { Tx.initialize(txRunner) }

    @Bean
    fun lockInitializer(distributedLock: DistributedLock) = InitializingBean { Lock.initialize(distributedLock) }

    /**
     * owner 아래 경로만 맡는다. Spring Session 필터보다 앞이어야 그 필터가 던지는 Redis 오류를 잡는다.
     * 서블릿 필터는 웹 서버를 만들 때 일찍 뜨므로 resolver를 바로 주입하면 MVC 설정과 JPA 빈이 그 전에 만들어져 기동이 실패한다. 오류 때 꺼낸다.
     */
    @Bean
    fun ownerSessionStoreFilter(@Qualifier("handlerExceptionResolver") resolver: ObjectProvider<HandlerExceptionResolver>) =
        FilterRegistrationBean(OwnerSessionStoreFilter(resolver::getObject)).apply {
            addUrlPatterns("/owner/*")
            order = SessionRepositoryFilter.DEFAULT_ORDER - 1
        }

    /**
     * admin 아래 경로만 맡는다. Spring Security 필터보다 앞이어야 운영자 체인이 던지는 IdP 장애 예외를 잡는다. resolver를 늦게 꺼내는 이유는 위와 같다.
     * Security 필터 순서는 설정(`spring.security.filter.order`)으로 바뀔 수 있어 상수가 아니라 그 설정 바로 앞에 둔다.
     */
    @Bean
    fun adminIdentityProviderFilter(
        @Qualifier("handlerExceptionResolver") resolver: ObjectProvider<HandlerExceptionResolver>,
        securityFilter: SecurityFilterProperties,
    ) =
        FilterRegistrationBean(AdminIdentityProviderFilter(resolver::getObject)).apply {
            addUrlPatterns("/admin/*")
            order = securityFilter.order - 1
        }

    private companion object {
        const val REDIS_REQUEST_QUEUE_SIZE = 1_000
    }
}
