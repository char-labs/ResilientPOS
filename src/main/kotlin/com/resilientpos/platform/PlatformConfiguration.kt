package com.resilientpos.platform

import com.resilientpos.foundation.IdentifierGenerator
import com.resilientpos.foundation.UuidV7IdentifierGenerator
import java.time.Clock
import org.springframework.beans.factory.InitializingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

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

    @Bean
    fun txInitializer(txRunner: TxRunner) = InitializingBean { Tx.initialize(txRunner) }

    @Bean
    fun lockInitializer(distributedLock: DistributedLock) = InitializingBean { Lock.initialize(distributedLock) }
}
