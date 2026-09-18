package com.resilientpos.order

import java.util.UUID

/**
 * 주문의 서버 식별자(UUID v7). 저장 전에 `IdentifierGenerator`로 만든다.
 */
@JvmInline
value class OrderId(val value: UUID)

/**
 * 단말이 부여하는 주문 생성 요청 식별자(UUID v7). 재시도 시 같은 결과를 돌려주는 판별 키다.
 */
@JvmInline
value class ClientOrderId(val value: UUID)

/**
 * 주문 line의 식별자(UUID v7). 주문 안의 위치는 line이 빠지면 당겨지므로 식별자가 아니다.
 */
@JvmInline
value class OrderLineId(val value: UUID)
