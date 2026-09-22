package com.resilientpos.payment

import java.util.UUID

/**
 * 결제 시도의 서버 식별자(UUID v7). 저장 전에 `IdentifierGenerator`로 만든다.
 * 회차 번호(`attemptNo`)는 업무 값이고 행의 식별은 이 ID가 한다.
 */
@JvmInline
value class PaymentId(val value: UUID)
