package com.resilientpos.owner.internal.adapter.persistence.command

import com.resilientpos.owner.OwnerId
import com.resilientpos.owner.internal.domain.Owner
import com.resilientpos.owner.internal.domain.OwnerEmail
import com.resilientpos.platform.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/**
 * 역할: 점주 계정 하나. 이메일(정규화된 값)과 비밀번호 해시의 정본이다.
 * 흐름: 가입이 한 번 만들고 바꾸는 경로는 아직 없다. 행 갱신이 없어 `@Version`을 두지 않는다. 전체 지도는 `docs/wiki/entity-map.md`.
 *
 * `Owner`의 JPA 매핑. 전 필드는 `var`에 `protected set`이다(`OrderEntity`의 KDoc 참고).
 */
@Entity
@Table(name = "t_owner")
class OwnerEntity private constructor(
    id: UUID,
    email: String,
    passwordHash: String,
) : BaseEntity() {

    @Id
    var id: UUID = id
        protected set

    var email: String = email
        protected set

    var passwordHash: String = passwordHash
        protected set

    fun toDomain(): Owner = Owner.restore(OwnerId(id), OwnerEmail.of(email), passwordHash)

    companion object {
        fun from(owner: Owner): OwnerEntity = OwnerEntity(owner.id.value, owner.email.value, owner.passwordHash)
    }
}
