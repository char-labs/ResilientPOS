package com.resilientpos.payment

import java.util.UUID

/**
 * 결제 시도의 서버 식별자(UUID v7). 저장 전에 `IdentifierGenerator`로 만든다.
 * 시도를 가르는 것은 이 ID 하나다. `orderId`는 어느 주문의 시도인지 가리키는 참조일 뿐이고
 * 식별에 참여하지 않는다. 그래야 한 주문에 시도가 몇 건이든 제약이 생기지 않는다.
 */
@JvmInline
value class PaymentId(val value: UUID)
