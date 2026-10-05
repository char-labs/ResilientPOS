package com.resilientpos.terminal.internal.adapter.web

import com.resilientpos.terminal.AuthenticatedTerminal
import org.springframework.context.annotation.Configuration
import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** v1 경로에 인증 인터셉터를 걸고 Controller 파라미터 [AuthenticatedTerminal]을 요청 속성에서 채운다. */
@Configuration
class TerminalWebConfiguration(private val interceptor: TerminalAuthenticationInterceptor) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor).addPathPatterns("/v1/**")
    }

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers += object : HandlerMethodArgumentResolver {
            override fun supportsParameter(parameter: MethodParameter) =
                parameter.parameterType == AuthenticatedTerminal::class.java

            // 인터셉터가 v1 경로에서 먼저 채운다. 속성이 없으면 인터셉터 범위 밖 endpoint라는 뜻이므로 조용히 null을 넘기지 않고 실패한다.
            override fun resolveArgument(
                parameter: MethodParameter,
                mavContainer: ModelAndViewContainer?,
                webRequest: NativeWebRequest,
                binderFactory: WebDataBinderFactory?,
            ): Any = checkNotNull(
                webRequest.getAttribute(TerminalAuthenticationInterceptor.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST),
            ) { "AuthenticatedTerminal은 인증 인터셉터가 걸린 경로에서만 받을 수 있다" }
        }
    }
}
