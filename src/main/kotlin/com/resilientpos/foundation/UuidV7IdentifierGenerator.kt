package com.resilientpos.foundation

import com.github.f4b6a3.uuid.UuidCreator
import java.util.UUID

/**
 * UUID version 7 식별자를 만든다. 시간 성분은 epoch milliseconds다.
 * 동기화의 순서 증거는 이 시간 성분이 아니라 단말의 durable sequence다.
 */
class UuidV7IdentifierGenerator : IdentifierGenerator {
    override fun next(): UUID = UuidCreator.getTimeOrderedEpoch()
}
