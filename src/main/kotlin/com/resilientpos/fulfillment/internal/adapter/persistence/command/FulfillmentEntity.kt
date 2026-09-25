package com.resilientpos.fulfillment.internal.adapter.persistence.command

import com.resilientpos.fulfillment.internal.domain.FulfillmentId
import com.resilientpos.fulfillment.internal.domain.Fulfillment
import com.resilientpos.platform.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/**
 * `Fulfillment`의 JPA 매핑. 연관관계 매핑 없이 `orderId` 컬럼만 가진 독립 엔티티다.
 * 바뀌는 값이 없어 두 필드 모두 `var`에 `protected set`이다. `val`로 두지 않는 이유는 `OrderEntity`의
 * KDoc에 있다. 생성은 `from`만 한다(도메인 규칙 §5).
 */
@Entity
@Table(name = "t_fulfillment")
class FulfillmentEntity private constructor(
    id: UUID,
    orderId: UUID,
) : BaseEntity() {

    @Id
    var id: UUID = id
        protected set

    var orderId: UUID = orderId
        protected set

    fun toDomain(): Fulfillment = Fulfillment.restore(id = FulfillmentId(id), orderId = orderId)

    companion object {
        fun from(fulfillment: Fulfillment): FulfillmentEntity = FulfillmentEntity(
            id = fulfillment.id.value,
            orderId = fulfillment.orderId,
        )
    }
}
