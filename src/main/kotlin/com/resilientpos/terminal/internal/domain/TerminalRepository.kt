package com.resilientpos.terminal.internal.domain

import com.resilientpos.terminal.TerminalId

/**
 * `Terminal`과 그 credential을 저장·조회하는 port. 시그니처에 aggregate와 원시 값만 쓴다.
 * 쓰는 메서드는 트랜잭션 안에서만 부른다.
 */
interface TerminalRepository {

    /** 해시가 같고 폐기되지 않은 credential의 `ACTIVE` 단말. 한 조회로 읽고 잠그지 않는다. */
    fun findActiveByTokenHash(tokenHash: String): Terminal?

    /**
     * 단말 행을 잠그고 읽는다. 회전·비활성화가 같은 단말에서 겹치지 않게 한다. 그 트랜잭션에서 이 단말의 첫 접촉이어야 한다.
     * 대기는 3초로 제한하고 넘으면 `PessimisticLockingFailureException` 계열 예외다.
     */
    fun findByIdForUpdate(terminalId: TerminalId): Terminal?

    /** 그 단말의 유효(폐기되지 않은) credential. 없을 수 있다. */
    fun findActiveCredential(terminalId: TerminalId): TerminalCredential?

    fun save(terminal: Terminal): Terminal

    fun save(credential: TerminalCredential): TerminalCredential

    /** 상태가 바뀐 단말을 반영한다. 이미 저장된 행만 다룬다. */
    fun update(terminal: Terminal): Terminal

    /** 폐기된 credential을 반영한다. 같은 트랜잭션의 새 발급과 한 커밋에 들어가고 `ux_terminal_credential_active`는 커밋 때 검사한다. */
    fun update(credential: TerminalCredential): TerminalCredential
}
