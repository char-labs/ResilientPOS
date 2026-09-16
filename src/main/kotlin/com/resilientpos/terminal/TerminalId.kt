package com.resilientpos.terminal

import java.util.UUID

/**
 * 단말 식별자. `terminal` 모듈이 소유하는 aggregate가 아직 없어 ID 값 타입만 공개한다.
 */
@JvmInline
value class TerminalId(val value: UUID)
