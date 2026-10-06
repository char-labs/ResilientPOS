package com.resilientpos.platform

/**
 * 여러 인스턴스 사이에서 같은 키의 일을 줄 세우는 블록. 블록이 곧 락 범위다.
 *
 * 최선 노력이다. 잠금 저장소가 응답하지 않거나 대기 한도를 넘으면 잠금 없이 블록을 실행한다(fail-open).
 * 정합성은 DB 제약이 지키고, 이 락은 늦게 온 같은 요청이 제약 위반으로 실패하는 대신 앞 결과를 받게 하는 완화 수단이다.
 *
 * 한 경로에서 기다리는 수단은 하나만 쓴다. 블록 안에서 행 잠금(`findByIdForUpdate`)을 기다리지 않는다 —
 * 락을 쥔 쪽이 행을, 행을 쥔 쪽이 락을 기다리면 둘 다 대기 한도까지 멈춘다. 잠글 행이 이미 있으면 행 잠금을 쓴다.
 *
 * ponytail: 전역 락이라 한 JVM에 컨텍스트가 여럿 뜨면 [Tx]와 같은 한계가 있다. 테스트는 `TxRebindingTestExecutionListener`가 다시 묶는다.
 */
object Lock {
    private lateinit var distributedLock: DistributedLock

    fun initialize(distributedLock: DistributedLock) {
        this.distributedLock = distributedLock
    }

    /**
     * 블록은 새 트랜잭션([Tx.newTx], `REQUIRES_NEW`)에서 돌고, 커밋한 뒤에 락을 푼다. 바깥 트랜잭션에 합류하면
     * 바깥이 커밋하기 전에 락이 풀려 뒤 요청이 커밋되지 않은 상태를 읽는다. 그래서 바깥이 있어도 따로 커밋한다.
     * 바깥이 롤백돼도 블록의 커밋은 남고, 바깥이 있으면 커넥션을 둘 쓴다. 한 커밋으로 묶어야 하는 흐름(§4.6 sync intake) 안에서 부르지 않고,
     * 그 흐름은 락 없는 합류 경로(`OrderCommandFacade.createInCallerTransaction`)를 부른다. 바깥 트랜잭션을 거절하는 검사는 두지 않는다(계약은 이 KDoc이다).
     */
    fun <T> executeWithLock(key: String, action: () -> T): T = locked(key) { Tx.newTx(action) }

    /**
     * 트랜잭션 없이 줄만 세운다. 블록 안의 쓰기는 각자 자기 트랜잭션에서 곧바로 커밋한다.
     * 한 커밋으로 묶을 쓰기가 없는 주기 작업 둘이 쓴다. [EventPublicationResubmitter]는 블록 동안 행 잠금을 쥐면 안 된다.
     * payment의 결과 불명 확정 작업(`UnknownPaymentResolver`)은 블록 안에서 행 잠금을 기다리는 예외이고, 근거는 그 작업의 KDoc에 있다.
     * 바깥 트랜잭션 안에서 부르지 않는다. 합류하면 바깥이 커밋하기 전에 락이 풀린다. Facade는 쓰지 않는다. 검사는 두지 않는다(계약은 이 KDoc이다).
     */
    fun <T> executeWithLockNoTx(key: String, action: () -> T): T = locked(key, action)

    private fun <T> locked(key: String, action: () -> T): T {
        val token = distributedLock.tryAcquire(key)
        try {
            return action()
        } finally {
            token?.let { distributedLock.release(key, it) }
        }
    }
}
