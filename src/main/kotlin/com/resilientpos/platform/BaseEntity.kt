package com.resilientpos.platform

import jakarta.persistence.MappedSuperclass
import java.time.Instant
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.SoftDelete
import org.hibernate.annotations.SoftDeleteType
import org.hibernate.annotations.SourceType
import org.hibernate.annotations.UpdateTimestamp

/**
 * JPA 엔티티의 공통 컬럼(감사 시각, soft delete). 생성·수정 시각은 Hibernate가 JVM에서 만든다.
 * `SourceType.DB`로 두면 Hibernate 7.4.5가 UPDATE의 generated 값을 읽느라 갱신 행 수 검사를 건너뛰고, 버전 충돌이
 * 낙관적 잠금 예외가 아니라 "no natively generated values"(`JpaSystemException`)로 나온다. 한 INSERT의 두 시각은 같지 않을 수 있다.
 * 삭제는 Hibernate가 `deleted_at` 기록으로 바꾸고 모든 조회에서 삭제된 행을 거른다. `deleted_at`은 필드로 매핑하지 않는다.
 * `id`는 두지 않는다. 도메인 ID는 UUID v7을 저장 전에 만들어 각 엔티티가 가진다.
 * `@Version`도 두지 않는다. 낙관적 잠금이 필요한 엔티티가 직접 선언한다. 선언이 있는 곳이 곧 필요한 곳이다.
 */
@MappedSuperclass
@SoftDelete(strategy = SoftDeleteType.TIMESTAMP, columnName = "deleted_at")
abstract class BaseEntity {

    @CreationTimestamp(source = SourceType.VM)
    lateinit var createdAt: Instant
        protected set

    @UpdateTimestamp(source = SourceType.VM)
    lateinit var updatedAt: Instant
        protected set
}
