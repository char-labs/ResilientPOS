package com.resilientpos.platform

import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.foundation.UuidV7IdentifierGenerator
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.time.Clock
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
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
}
