package com.resilientpos.platform

import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice

/**
 * `basePackages`로 우리 Controller만 감싼다. Actuator 응답은 그대로 나간다.
 */
@RestControllerAdvice(basePackages = ["com.resilientpos"])
class ApiResponseBodyAdvice : ResponseBodyAdvice<Any> {

    // ponytail: String을 돌려주는 Controller는 StringHttpMessageConverter가 envelope를 못 써서 ClassCastException이 난다.
    // 그런 endpoint가 생기면 객체로 돌려주거나 여기서 converterType을 거른다.
    override fun supports(
        returnType: MethodParameter,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Boolean = true

    override fun beforeBodyWrite(
        body: Any?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest,
        response: ServerHttpResponse,
    ): Any? = body as? ApiResponse<*> ?: ApiResponse.success(body)
}
