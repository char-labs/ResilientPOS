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
@Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
annotation class ReadOnlyTransactional

/**
 * 트랜잭션 경계를 블록으로 선언한다. `@Transactional` 메서드와 달리 같은 클래스 안에서도 트랜잭션이 열린다 —
 * 프록시를 거친 [TxRunner]를 부르기 때문이다. 러너는 `PlatformConfiguration`이 컨텍스트 기동 때 넣는다.
 * Spring 트랜잭션에서 쓰는 것은 `@Transactional` 애너테이션의 전파 속성뿐이다. `org.springframework.transaction.support`의
 * API(`TransactionSynchronizationManager`·`TransactionTemplate`)는 운영 코드 어디서도 부르지 않는다. 나중에 트랜잭션 수단을 바꿀 때 [TxRunner] 하나만 고치면 된다.
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

    /** 바깥 트랜잭션과 따로 커밋한다(`REQUIRES_NEW`). [Lock]이 락을 풀기 전에 커밋하려고 쓴다. */
    fun <T> newTx(action: () -> T): T = runner.runNewTx(action)

    /**
     * 호출자 트랜잭션이 없으면 `IllegalTransactionStateException`으로 거절한다. 호출자 트랜잭션에 합류해 한 커밋으로 묶여야 하는
     * 경로의 첫 줄에 둔다. 그냥 돌면 그 쓰기만 자기 트랜잭션으로 커밋되어 호출자가 묶으려던 커밋이 조용히 갈라진다.
     * 검사는 Spring API 호출이 아니라 [TxRunner]의 빈 메서드에 건 전파 속성 `MANDATORY`가 한다. 호출부는 "있어야 한다"를 이 이름으로 읽는다.
     */
    fun requireCaller() = runner.requireCaller()

    /** 호출자 트랜잭션이 있으면 `IllegalTransactionStateException`으로 거절한다. 따로 커밋하거나 트랜잭션 없이 도는 블록([Lock])의 첫 줄에 둔다. 전파 속성 `NEVER`가 검사한다. */
    fun requireNoCaller() = runner.requireNoCaller()
}

/** [Tx]가 부르는 트랜잭션 프록시. 직접 주입해 쓰지 않는다. */
@Component
class TxRunner {

    @Transactional
    fun <T> runTx(action: () -> T): T = action()

    @ReadOnlyTransactional
    fun <T> runReadOnly(action: () -> T): T = action()

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun <T> runNewTx(action: () -> T): T = action()

    /** 본문이 없다. 호출자 트랜잭션이 없으면 프록시가 `MANDATORY`로 거절하는 것이 전부다. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun requireCaller() {
    }

    /** 본문이 없다. 호출자 트랜잭션이 있으면 프록시가 `NEVER`로 거절하는 것이 전부다. */
    @Transactional(propagation = Propagation.NEVER)
    fun requireNoCaller() {
    }
}
