package com.resilientpos.platform

import com.linecorp.kotlinjdsl.dsl.jpql.Jpql
import com.linecorp.kotlinjdsl.dsl.jpql.jpql
import com.linecorp.kotlinjdsl.dsl.jpql.select.SelectQueryWhereStep
import com.linecorp.kotlinjdsl.querymodel.jpql.JpqlQueryable
import com.linecorp.kotlinjdsl.querymodel.jpql.select.SelectQuery
import com.linecorp.kotlinjdsl.render.jpql.JpqlRenderContext
import com.linecorp.kotlinjdsl.render.jpql.JpqlRenderer
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.persistence.TypedQuery
import kotlin.reflect.KClass
import org.hibernate.jpa.SpecHints
import org.springframework.stereotype.Repository

/**
 * Kotlin JDSL로 만든 JPQL을 `EntityManager`에 올려 실행한다. 조회는 모두 여기를 거친다(도메인 모델 §4.3).
 *
 * `@Repository`라 JPA 예외가 Spring 예외(`PessimisticLockingFailureException` 등)로 번역된다. Kafka 재시도 허용 목록이 이 타입으로 판정한다.
 * 트랜잭션을 선언하지 않는다. 호출자의 트랜잭션을 따른다. Spring Data 지원 모듈의 `KotlinJdslJpqlExecutor`를 쓰지 않는 이유는
 * 구현이 클래스 레벨 `@Transactional(readOnly = true)`(`REQUIRED`)라 읽기 전용 = `SUPPORTS` 규칙과 어긋나고,
 * repository에 붙이면 `update`·`delete` 대량 변경이 함께 열리며, 잠금을 걸 자리가 없어서다.
 */
@Repository
class JdslQueryExecutor(private val entityManager: EntityManager) {

    fun <T : Any> findAll(query: Jpql.() -> JpqlQueryable<SelectQuery<T>>): List<T> =
        createQuery(query).resultList

    /** 정렬한 첫 행이나 PK·unique 조건의 한 행을 읽는다. 조건에 맞는 행이 여럿이어도 예외 없이 하나를 고르므로 그 밖의 조건에 쓰지 않는다. */
    fun <T : Any> findFirst(query: Jpql.() -> JpqlQueryable<SelectQuery<T>>): T? =
        createQuery(query).setMaxResults(1).resultList.firstOrNull()

    /**
     * 행 하나를 잠그고 읽는다. PK·unique 조건에만 쓴다. PostgreSQL에서는 `select ... for no key update`다. 같은 잠금끼리 충돌하므로 같은 행의 전이가 직렬화된다.
     * 대기는 3초로 제한한다. Hibernate가 조회 앞뒤로 `set local lock_timeout`을 걸고 되돌린다.
     * 넘으면 `PessimisticLockingFailureException` 계열 예외다. 무기한 대기는 커넥션을 쥔 채 쌓여 풀을 말린다.
     */
    fun <T : Any> findFirstForUpdate(query: Jpql.() -> JpqlQueryable<SelectQuery<T>>): T? =
        createQuery(query)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .setHint(SpecHints.HINT_SPEC_LOCK_TIMEOUT, LOCK_TIMEOUT_MILLIS)
            .setMaxResults(1)
            .resultList
            .firstOrNull()

    private fun <T : Any> createQuery(init: Jpql.() -> JpqlQueryable<SelectQuery<T>>): TypedQuery<T> {
        val query = jpql(init)
        val rendered = renderer.render(query, renderContext)
        return entityManager.createQuery(rendered.query, query.returnType.java).apply {
            rendered.params.forEach { (name, value) -> setParameter(name, value) }
        }
    }

    companion object {
        private const val LOCK_TIMEOUT_MILLIS = 3000
        private val renderer = JpqlRenderer()
        private val renderContext = JpqlRenderContext()
    }
}

/** 엔티티 하나를 그대로 읽는 조회의 시작. `select(entity(X::class)).from(entity(X::class))`와 같다. */
inline fun <reified T : Any> Jpql.selectFrom(type: KClass<T>): SelectQueryWhereStep<T> = entity(type).let { select(it).from(it) }
