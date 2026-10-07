package com.resilientpos.owner.internal.domain

/** `Owner`를 저장·조회하는 port. 쓰는 메서드는 트랜잭션 안에서만 부른다. */
interface OwnerRepository {

    /** 사전 조회 없이 넣는다. 같은 이메일이면 [Owner.emailDuplicated]를 던진다. */
    fun save(owner: Owner): Owner

    /** 정규화된 이메일로 찾는다. */
    fun findByEmail(email: OwnerEmail): Owner?
}
