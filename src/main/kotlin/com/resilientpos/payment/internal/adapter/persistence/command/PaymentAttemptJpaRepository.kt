package com.resilientpos.payment.internal.adapter.persistence.command

import java.util.UUID
import org.springframework.data.repository.Repository

/**
 * `t_payment_attempt`용 Spring Data repository. 지금 조회는 `PaymentAttemptPersistenceAdapter`가 `JdslQueryExecutor`로 한다.
 *
 * `JpaRepository`를 상속하지 않는다. 그러면 `deleteAllInBatch` 같은 메서드가 공개 빈에 딸려 오는데,
 * `BaseEntity`의 soft delete 때문에 그 한 번이 모든 결제 시도를 조용히 조회에서 지운다.
 * 결제 원장에서 그런 메서드가 열려 있을 이유가 없다.
 */
interface PaymentAttemptJpaRepository : Repository<PaymentAttemptEntity, UUID>
