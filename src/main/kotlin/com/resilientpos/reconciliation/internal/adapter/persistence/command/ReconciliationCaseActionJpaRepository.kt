package com.resilientpos.reconciliation.internal.adapter.persistence.command

import java.util.UUID
import org.springframework.data.repository.Repository

/**
 * `t_reconciliation_case_action`용 Spring Data repository. 조회는 `ReconciliationCasePersistenceAdapter`가 `JdslQueryExecutor`로 한다.
 * 감사 기록은 지우지 않으므로 `JpaRepository`를 상속하지 않는다(`ReconciliationCaseJpaRepository`와 같다).
 */
interface ReconciliationCaseActionJpaRepository : Repository<ReconciliationCaseActionEntity, UUID>
