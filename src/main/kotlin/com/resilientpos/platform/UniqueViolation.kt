package com.resilientpos.platform

import java.sql.SQLException

private const val UNIQUE_VIOLATION = "23505"

/**
 * 원인 사슬에 [constraint]를 어긴 unique 위반이 있는지 본다.
 * Hibernate의 constraintName은 영어 메시지 템플릿에서 꺼내서 서버 lc_messages가 다르면 비어 있다.
 * SQLState와, 어느 언어의 메시지에도 그대로 실리는 제약 이름으로 가린다.
 */
fun Throwable.isUniqueViolationOf(constraint: String): Boolean =
    generateSequence(this) { it.cause }.filterIsInstance<SQLException>()
        .any { it.sqlState == UNIQUE_VIOLATION && it.message.orEmpty().contains("\"$constraint\"") }
