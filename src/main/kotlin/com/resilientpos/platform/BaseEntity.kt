package com.resilientpos.platform

import jakarta.persistence.MappedSuperclass
import jakarta.persistence.Version
import java.time.Instant
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.SoftDelete
import org.hibernate.annotations.SoftDeleteType
import org.hibernate.annotations.SourceType
import org.hibernate.annotations.UpdateTimestamp

/**
 * JPA 엔티티의 공통 컬럼(낙관적 잠금 버전, 감사 시각, soft delete). 시각은 모두 DB `current_timestamp`다.
 * 삭제는 Hibernate가 `deleted_at` 기록으로 바꾸고 모든 조회에서 삭제된 행을 거른다. `deleted_at`은 필드로 매핑하지 않는다.
 * `id`는 두지 않는다. 도메인 ID는 UUID v7을 저장 전에 만들어 각 엔티티가 가진다. 그래서 `version`은 nullable이다.
 * primitive면 Spring Data가 ID가 채워진 새 엔티티를 기존 행으로 보고 `merge`해 SELECT와 UPDATE가 더 나간다.
 */
@MappedSuperclass
@SoftDelete(strategy = SoftDeleteType.TIMESTAMP, columnName = "deleted_at")
abstract class BaseEntity {

    @Version
    var version: Long? = null
        protected set

    @CreationTimestamp(source = SourceType.DB)
    lateinit var createdAt: Instant
        protected set

    @UpdateTimestamp(source = SourceType.DB)
    lateinit var updatedAt: Instant
        protected set
}
