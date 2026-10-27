package com.resilientpos.reconciliation.internal.adapter.persistence.command

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

/**
 * `t_reconciliation_case`용 Spring Data repository. `ReconciliationCasePersistenceAdapter`가 저장과 기존 행 찾기에 쓰고, 그 밖의 조회는 `JdslQueryExecutor`로 한다.
 */
interface ReconciliationCaseJpaRepository : JpaRepository<ReconciliationCaseEntity, UUID>
