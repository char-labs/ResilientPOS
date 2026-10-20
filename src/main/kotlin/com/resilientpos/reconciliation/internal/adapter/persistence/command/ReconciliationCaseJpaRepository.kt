package com.resilientpos.reconciliation.internal.adapter.persistence.command

import java.util.UUID
import org.springframework.data.repository.Repository

/**
 * `t_reconciliation_case`용 Spring Data repository. 지금 조회는 `ReconciliationCasePersistenceAdapter`가 `JdslQueryExecutor`로 한다.
 *
 * `JpaRepository`를 상속하지 않는다. 그러면 `deleteAllInBatch` 같은 메서드가 공개 빈에 딸려 오는데,
 * `BaseEntity`의 soft delete 때문에 그 한 번이 모든 case를 조용히 조회에서 지운다. case와 감사 기록은 지우지 않는다.
 */
interface ReconciliationCaseJpaRepository : Repository<ReconciliationCaseEntity, UUID>
