package com.resilientpos.platform

import java.sql.SQLException

private const val UNIQUE_VIOLATION = "23505"
private const val IDENTIFIER_CHAR = "[\\p{L}\\p{N}_\$]"

// ponytail: 메시지에 제약 이름이 실린다는 데 기댄다. 그렇지 않은 번역이 생기거나 JDBC batch를 켜서 SQL 본문이 원인 사슬에 들어오면 pgjdbc `ServerErrorMessage.constraint`로 바꾼다
/**
 * 원인 사슬에 [constraint]를 어긴 unique 위반이 있는지 본다.
 * Hibernate의 constraintName은 영어 메시지 템플릿에서 꺼내서 서버 lc_messages가 다르면 비어 있다.
 * 그래서 SQLState와, 메시지 안에서 PostgreSQL 식별자 문자(유니코드 글자·숫자·`_`·`$`)로 이어지지 않는 제약 이름으로 가린다
 * (`ux_ab`·`ux_a$1`·`pk_ux_a`는 `ux_a`가 아니다).
 *
 * 근거: PostgreSQL 17·18의 `src/backend/po` 번역 15개는 모두 제약 이름 자리를 남기고
 * 그 앞뒤가 따옴표(`"…"`, `»…«`, `« … »`)나 공백이다(2026-10-20 확인).
 * 전제: `logServerErrorDetail=false`(`application.yaml`)라 pgjdbc 메시지가 심각도와 주 메시지뿐이고,
 * 다른 식별자(DETAIL의 키 값)가 실리지 않는다.
 */
fun Throwable.isUniqueViolationOf(constraint: String): Boolean {
    val name = Regex("(?<!$IDENTIFIER_CHAR)${Regex.escape(constraint)}(?!$IDENTIFIER_CHAR)")
    return generateSequence(this) { it.cause }.filterIsInstance<SQLException>()
        .any { it.sqlState == UNIQUE_VIOLATION && name.containsMatchIn(it.message.orEmpty()) }
}
