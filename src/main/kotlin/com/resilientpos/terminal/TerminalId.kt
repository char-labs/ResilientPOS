package com.resilientpos.terminal

import java.util.UUID

/**
 * 단말 식별자. [TerminalManagement]가 소유하는 `Terminal` aggregate의 ID이고, 다른 모듈은 이 값 타입으로만 단말을 가리킨다.
 */
@JvmInline
value class TerminalId(val value: UUID)
