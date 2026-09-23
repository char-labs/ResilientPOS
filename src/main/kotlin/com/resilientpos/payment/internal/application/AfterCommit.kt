package com.resilientpos.payment.internal.application

import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 커밋 뒤에 [action]을 실행한다. 트랜잭션이 없으면 그 자리에서 실행한다.
 *
 * 리스너 안에서 바로 로그를 찍으면 아직 커밋되지 않은 것을 "기록했다"고 말하게 된다.
 * 경합에서 진 쪽은 정상 반환한 뒤 커밋에서 제약 위반으로 롤백되는데, 그때 존재하지 않는
 * 행을 남겼다는 로그만 남는다.
 */
internal fun afterCommit(action: () -> Unit) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
        action()
        return
    }
    TransactionSynchronizationManager.registerSynchronization(
        object : TransactionSynchronization {
            override fun afterCommit() = action()
        },
    )
}
