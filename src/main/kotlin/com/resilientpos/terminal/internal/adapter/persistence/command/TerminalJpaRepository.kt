package com.resilientpos.terminal.internal.adapter.persistence.command

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

/**
 * `t_terminal`용 Spring Data repository. `TerminalPersistenceAdapter`가 저장과 갱신할 행 찾기에 쓰고, 그 밖의 조회는 `JdslQueryExecutor`로 한다.
 */
interface TerminalJpaRepository : JpaRepository<TerminalEntity, UUID>
