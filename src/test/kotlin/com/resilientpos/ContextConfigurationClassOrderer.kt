package com.resilientpos

import org.junit.jupiter.api.ClassOrderer
import org.junit.jupiter.api.ClassOrdererContext
import org.springframework.test.context.BootstrapUtils

/**
 * 같은 Spring 컨텍스트 구성을 쓰는 테스트 클래스를 이어서 돌린다. `src/test/resources/junit-platform.properties`로 등록한다.
 *
 * 컨텍스트 캐시가 하나라(`spring.properties`) 구성이 같은 두 클래스 사이에 다른 구성이 끼면 같은 컨텍스트를 다시 띄운다.
 * 정렬 키는 컨텍스트 캐시의 키(`MergedContextConfiguration`)다. 키가 같은 클래스끼리 묶고 그 안은 이름순이다.
 */
class ContextConfigurationClassOrderer : ClassOrderer {

    override fun orderClasses(context: ClassOrdererContext) {
        context.classDescriptors.sortWith(compareBy({ contextKey(it.testClass) }, { it.testClass.name }))
    }

    private fun contextKey(testClass: Class<*>): Int =
        BootstrapUtils.resolveTestContextBootstrapper(testClass).buildMergedContextConfiguration().hashCode()
}
