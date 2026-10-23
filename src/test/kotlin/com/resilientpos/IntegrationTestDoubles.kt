package com.resilientpos

import com.resilientpos.payment.PaymentDeclinedRecorder
import com.resilientpos.payment.internal.adapter.gateway.FakePaymentGateway
import com.resilientpos.payment.internal.adapter.gateway.ScriptedPaymentGateway
import jakarta.persistence.EntityManagerFactory
import javax.sql.DataSource
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * 통합 테스트가 같이 쓰는 대역. [PostgresIntegrationTest]가 가져온다. 테스트마다 구성을 바꾸면 컨텍스트가 하나 더 뜨므로 여기에 모은다.
 * 대역은 테스트가 건 키(금액·paymentId)에만 다르게 움직이고, 그 밖의 호출은 앱의 동작을 그대로 탄다.
 */
@TestConfiguration(proxyBeanMethods = false)
class IntegrationTestDoubles {

    @Bean
    @Primary
    fun scriptedPaymentGateway(fake: FakePaymentGateway, entityManagerFactory: EntityManagerFactory, dataSource: DataSource) =
        ScriptedPaymentGateway(fake, entityManagerFactory, dataSource)

    @Bean
    fun paymentDeclinedRecorder() = PaymentDeclinedRecorder()
}
