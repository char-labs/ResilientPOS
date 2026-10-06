package com.resilientpos.payment.internal.domain

import com.resilientpos.payment.PaymentId
import com.resilientpos.store.StoreId
import java.util.UUID

/**
 * `PaymentAttempt`를 저장·조회하는 port. 시그니처에 aggregate와 원시 값만 쓴다.
 */
interface PaymentAttemptRepository {

    /**
     * 그 checkout의 승인 시도를 **결말과 무관하게** 읽는다. 여럿이면 가장 먼저 만든 것이다.
     * 같은 사실이 다시 와도 시도를 두 번 만들지 않기 위한 조회다.
     * 동시에 들어온 요청까지 막지는 못한다. 그 창은 사실로 만든 첫 승인 시도의 unique가 닫는다.
     */
    fun findAuthorization(orderId: UUID, checkoutNo: Int): PaymentAttempt?

    /**
     * 그 checkout의 승인 시도 중 **가장 나중에** 만든 것. 재결제가 거절된 시도를 다시 시도할 수 있는지,
     * 이미 다시 시도했는지를 가리는 조회다.
     */
    fun findLatestAuthorization(orderId: UUID, checkoutNo: Int): PaymentAttempt?

    /** 시도 하나를 식별자로 읽는다. 잠그지 않는다. */
    fun findById(paymentId: PaymentId): PaymentAttempt?

    /**
     * 결과를 모른 채 남은 승인 시도(kind=AUTHORIZATION, status=UNKNOWN)의 식별자를 오래된 순(`id` 오름차순)으로 최대 [limit]건 읽는다. 잠그지 않는다.
     * [after]가 있으면 그 `id`보다 큰 것만 읽는다(keyset). 읽은 마지막 `id`를 다음 호출의 [after]로 넘겨 돌아가며 읽는다.
     */
    fun findUnknownAuthorizationIds(after: PaymentId?, limit: Int): List<PaymentId>

    /** 결과를 모른 채 남은 승인 시도(kind=AUTHORIZATION, status=UNKNOWN)의 수. */
    fun countUnknownAuthorizations(): Long

    /**
     * 시도 행을 잠그고 읽는다. 결과를 적용하는 전이가 같은 시도에서 겹치지 않게 한다. 트랜잭션 안에서만 부른다.
     * 그 트랜잭션에서 이 시도의 첫 접촉이어야 한다 — 먼저 읽어 둔 것이 있으면 영속성 컨텍스트가 그 낡은 값을 돌려준다.
     * 대기는 3초로 제한하고 넘으면 `PessimisticLockingFailureException` 계열 예외다.
     */
    fun findByIdForUpdate(paymentId: PaymentId): PaymentAttempt?

    /**
     * [findByIdForUpdate]에 매장 조건을 더한다. 매장이 다른 행은 잠기지도 반환되지도 않아서 다른 매장의 요청은
     * 그 행을 쥔 트랜잭션을 기다리지 않고 없는 시도와 같은 `null`을 받는다. 나머지 계약은 [findByIdForUpdate]와 같다.
     */
    fun findByIdAndStoreIdForUpdate(paymentId: PaymentId, storeId: StoreId): PaymentAttempt?

    /**
     * 결말이 바뀐 시도를 반영한다. 트랜잭션 안에서만 부른다.
     * 이미 저장된 행만 다루므로 새 행을 만들지 않는다.
     */
    fun update(attempt: PaymentAttempt): PaymentAttempt

    /**
     * 새 시도를 넣는다. 트랜잭션 안에서만 부른다.
     * 같은 checkout에 진행 중인 승인이나 사실로 만든 첫 승인 시도가 이미 있으면 DB 제약이 거절한다.
     * 실패는 이 호출이 아니라 삽입이 flush될 때(보통 커밋) 난다.
     */
    fun save(attempt: PaymentAttempt): PaymentAttempt
}
