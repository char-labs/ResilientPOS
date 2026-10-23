package com.resilientpos

import org.junit.jupiter.api.ClassOrderer
import org.junit.jupiter.api.ClassOrdererContext
import org.springframework.test.context.BootstrapUtils
import org.springframework.test.context.MergedContextConfiguration

/**
 * 같은 Spring 컨텍스트 구성을 쓰는 테스트 클래스를 이어서 돌린다. `src/test/resources/junit-platform.properties`로 등록한다.
 *
 * 컨텍스트 캐시가 하나라(`spring.properties`) 구성이 같은 두 클래스 사이에 다른 구성이 끼면 같은 컨텍스트를 다시 띄운다.
 * 묶는 기준은 컨텍스트 캐시의 키(`MergedContextConfiguration`)의 equals다. 묶음은 그 안에서 이름이 가장 빠른 클래스 순이고,
 * 묶음 안은 이름순이라 실행마다 순서가 같다.
 *
 * 구성을 계산하면 `@ServiceConnection` 필드를 읽느라 기반 클래스의 컨테이너가 뜬다. 계산이 실패하면(Docker가 없는 등)
 * 그 클래스는 자기 이름을 키로 둔다. 원래 오류는 그 클래스를 실행할 때 다시 나고, 다른 클래스는 그대로 돈다.
 */
class ContextConfigurationClassOrderer : ClassOrderer {

    override fun orderClasses(context: ClassOrdererContext) {
        val keys: Map<Class<*>, Any> = context.classDescriptors.associate { it.testClass to contextKey(it.testClass) }
        val firstName: Map<Any, String> = keys.entries.groupBy({ it.value }, { it.key.name }).mapValues { (_, names) -> names.min() }
        context.classDescriptors.sortWith(compareBy({ firstName.getValue(keys.getValue(it.testClass)) }, { it.testClass.name }))
    }

    private fun contextKey(testClass: Class<*>): Any =
        runCatching<MergedContextConfiguration> {
            BootstrapUtils.resolveTestContextBootstrapper(testClass).buildMergedContextConfiguration()
        }.getOrElse { testClass.name }
}
