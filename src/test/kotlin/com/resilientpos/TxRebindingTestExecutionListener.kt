package com.resilientpos

import com.resilientpos.platform.DistributedLock
import com.resilientpos.platform.Lock
import com.resilientpos.platform.Tx
import com.resilientpos.platform.TxRunner
import org.springframework.test.context.TestContext
import org.springframework.test.context.TestExecutionListener

/**
 * Spring 테스트마다 [Tx]와 [Lock]을 그 테스트 컨텍스트의 러너·잠금 저장소로 다시 묶는다. `META-INF/spring.factories`로 등록한다.
 *
 * `Tx`는 전역이라 한 JVM의 마지막 초기화가 이긴다. 컨텍스트 없는 Facade 단위 테스트는 프록시가 아닌 `TxRunner`를 넣는데,
 * 그 뒤에 캐시된 컨텍스트를 다시 쓰는 통합 테스트는 기동 때의 초기화가 다시 돌지 않아 트랜잭션 없이 Facade를 부른다.
 * 웹 슬라이스처럼 러너가 없는 컨텍스트는 건드리지 않는다.
 */
class TxRebindingTestExecutionListener : TestExecutionListener {

    override fun beforeTestMethod(testContext: TestContext) {
        testContext.applicationContext.getBeanProvider(TxRunner::class.java).ifAvailable(Tx::initialize)
        testContext.applicationContext.getBeanProvider(DistributedLock::class.java).ifAvailable(Lock::initialize)
    }
}
