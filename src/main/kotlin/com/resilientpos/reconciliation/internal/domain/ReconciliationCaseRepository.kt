package com.resilientpos.reconciliation.internal.domain

import com.resilientpos.payment.PaymentId
import com.resilientpos.reconciliation.ReconciliationCaseId
import com.resilientpos.store.StoreId

/** `ReconciliationCase`를 저장·조회하는 port. 시그니처에 aggregate와 ID 값 타입만 쓴다. */
interface ReconciliationCaseRepository {

    /** 시도 하나의 case를 읽는다. 잠그지 않는다. 같은 사실이 다시 와도 case를 두 번 만들지 않기 위한 조회다. */
    fun findByPaymentId(paymentId: PaymentId): ReconciliationCase?

    /**
     * `id`와 `storeId`가 모두 맞는 case 행을 잠그고 읽는다. 트랜잭션 안에서만 부른다. 대기는 3초로 제한한다.
     * 매장이 다른 행은 잠기지도 반환되지도 않아 다른 매장의 요청은 없는 case와 같은 `null`을 받는다.
     */
    fun findByIdAndStoreIdForUpdate(id: ReconciliationCaseId, storeId: StoreId): ReconciliationCase?

    /** aggregate를 저장한다. 새 case면 넣고 기존 case면 바뀐 값과 새 감사 줄을 반영한다. 트랜잭션 안에서 부른다. */
    fun save(reconciliationCase: ReconciliationCase): ReconciliationCase
}
