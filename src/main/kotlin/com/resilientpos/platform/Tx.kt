package com.resilientpos.platform

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 읽기 전용 트랜잭션 선언. 호출자 트랜잭션이 있으면 합류하고 없으면 트랜잭션 없이 돈다(`SUPPORTS`).
 * 트랜잭션 없이 돌면 쿼리마다 따로 커밋되고 세션·커넥션 read-only는 걸리지 않는다. 읽기 전용 표시와 flush 생략은 걸린다.
 * 한 시점이 필요한 조회는 한 쿼리로 읽거나 루트를 잠그고 읽는다(도메인 모델 §4.3).
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
annotation class ReadOnlyTransactional

/**
 * 트랜잭션 경계를 블록으로 선언한다. `@Transactional` 메서드와 달리 같은 클래스 안에서도 트랜잭션이 열린다 —
 * 프록시를 거친 [TxRunner]를 부르기 때문이다. 러너는 `PlatformConfiguration`이 컨텍스트 기동 때 넣는다.
 *
 * ponytail: 전역 러너라 한 JVM에 컨텍스트가 여럿 뜨면 마지막으로 초기화된 컨텍스트의 러너를 쓴다.
 * 운영은 컨텍스트가 하나다. 테스트는 `TxRebindingTestExecutionListener`가 테스트마다 그 컨텍스트의 러너로 다시 묶는다.
 */
object Tx {
    private lateinit var runner: TxRunner

    fun initialize(runner: TxRunner) {
        this.runner = runner
    }

    fun <T> masterTx(action: () -> T): T = runner.runTx(action)

    fun <T> readOnlyTx(action: () -> T): T = runner.runReadOnly(action)
}

/** [Tx]가 부르는 트랜잭션 프록시. 직접 주입해 쓰지 않는다. */
@Component
class TxRunner {

    @Transactional
    fun <T> runTx(action: () -> T): T = action()

    @ReadOnlyTransactional
    fun <T> runReadOnly(action: () -> T): T = action()
}
