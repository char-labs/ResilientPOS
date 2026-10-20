package com.resilientpos.platform

import java.sql.SQLException

private const val UNIQUE_VIOLATION = "23505"
private const val IDENTIFIER_CHAR = "[A-Za-z0-9_]"

// shortcut: 메시지에 제약 이름이 실린다는 데 기댄다, 그렇지 않은 번역이 생기면 pgjdbc `ServerErrorMessage.constraint`로 바꾼다
/**
 * 원인 사슬에 [constraint]를 어긴 unique 위반이 있는지 본다.
 * Hibernate의 constraintName은 영어 메시지 템플릿에서 꺼내서 서버 lc_messages가 다르면 비어 있다.
 * 번역마다 따옴표 형식은 달라도(`"…"`, `»…«`, `« … »`) 제약 이름은 그대로 실리므로
 * SQLState와, 메시지 안에서 식별자 경계로 떨어진 제약 이름으로 가린다(`ux_ab`는 `ux_a`가 아니다).
 */
fun Throwable.isUniqueViolationOf(constraint: String): Boolean {
    val name = Regex("(?<!$IDENTIFIER_CHAR)${Regex.escape(constraint)}(?!$IDENTIFIER_CHAR)")
    return generateSequence(this) { it.cause }.filterIsInstance<SQLException>()
        .any { it.sqlState == UNIQUE_VIOLATION && name.containsMatchIn(it.message.orEmpty()) }
}
