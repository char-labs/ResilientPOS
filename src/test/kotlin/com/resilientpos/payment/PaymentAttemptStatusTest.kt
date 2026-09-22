package com.resilientpos.payment

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 상태 이름은 DB에 문자열로 저장되고 원문 세 개가 서로 다른 어휘를 쓴다.
 * 집합과 이름을 고정해 두지 않으면 누군가 DECLINED를 REJECTED로 바꿔도 아무 테스트도 깨지지 않는다.
 */
class PaymentAttemptStatusTest {

    @Test
    fun `상태 이름과 개수를 고정한다`() {
        assertEquals(
            listOf("REQUESTED", "APPROVED", "DECLINED", "UNKNOWN"),
            PaymentAttemptStatus.entries.map { it.name },
        )
    }
}
