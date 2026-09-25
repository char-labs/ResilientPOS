package com.resilientpos.platform

import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.foundation.UuidV7IdentifierGenerator
import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * `foundation`은 프레임워크를 모르므로 Spring 빈 등록은 여기서 한다.
 * Facade는 시각을 `Clock`으로 받아 테스트에서 고정할 수 있다.
 * 스케줄링은 발행 기록 metric의 주기 갱신([EventPublicationMetrics])이 쓴다. Modulith Moments의 자동 구성도 스케줄링을 켜지만
 * 그것에 기대면 `spring.modulith.moments.enabled=false`로 Moments를 끌 때 갱신도 멈춘다. 여기서 켜 두면 Moments를 꺼도 갱신은 계속 돈다.
 */
@Configuration
@EnableScheduling
class PlatformConfiguration {

    @Bean
    fun identifierGenerator(): IdentifierGenerator = UuidV7IdentifierGenerator()

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
