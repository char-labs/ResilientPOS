package com.resilientpos.platform

import jakarta.persistence.PersistenceException
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UniqueViolationTest {

    private fun violation(sqlState: String, constraint: String) =
        SQLException("duplicate key value violates unique constraint \"$constraint\"", sqlState)

    @Test
    fun `SQLState가 23505이고 메시지에 제약 이름이 있으면 참이다`() {
        assertTrue(violation("23505", "ux_a").isUniqueViolationOf("ux_a"))
    }

    @Test
    fun `다른 제약 이름이면 거짓이다`() {
        assertFalse(violation("23505", "ux_a").isUniqueViolationOf("ux_b"))
    }

    @Test
    fun `다른 SQLState면 거짓이다`() {
        assertFalse(violation("23503", "ux_a").isUniqueViolationOf("ux_a"))
    }

    @Test
    fun `원인 사슬에 SQLException이 없으면 거짓이다`() {
        assertFalse(PersistenceException("ux_a", IllegalStateException("\"ux_a\"")).isUniqueViolationOf("ux_a"))
    }

    @Test
    fun `원인 사슬 깊이 있어도 찾는다`() {
        val e = PersistenceException(RuntimeException(violation("23505", "ux_a")))

        assertTrue(e.isUniqueViolationOf("ux_a"))
    }

    private fun withMessage(message: String) = SQLException(message, "23505")

    @Test
    fun `독일어 번역 메시지의 »« 따옴표 안 제약 이름도 찾는다`() {
        val e = withMessage("FEHLER: doppelter Schlüsselwert verletzt Unique-Constraint »ux_a«")

        assertTrue(e.isUniqueViolationOf("ux_a"))
    }

    @Test
    fun `프랑스어 번역 메시지의 « » 따옴표 안 제약 이름도 찾는다`() {
        val e = withMessage("ERREUR: la valeur d'une clé dupliquée rompt la contrainte unique « ux_a »")

        assertTrue(e.isUniqueViolationOf("ux_a"))
    }

    @Test
    fun `앞부분만 같은 다른 제약 이름이면 거짓이다`() {
        assertFalse(violation("23505", "ux_ab").isUniqueViolationOf("ux_a"))
    }

    @Test
    fun `제약 이름 뒤에 식별자 문자 $가 이어지면 거짓이다`() {
        assertFalse(violation("23505", "ux_a\$1").isUniqueViolationOf("ux_a"))
    }

    @Test
    fun `제약 이름 앞에 식별자 문자가 이어지면 거짓이다`() {
        assertFalse(violation("23505", "pk_ux_a").isUniqueViolationOf("ux_a"))
    }

    @Test
    fun `제약 이름 뒤에 비ASCII 글자가 이어지면 거짓이다`() {
        assertFalse(violation("23505", "ux_a한").isUniqueViolationOf("ux_a"))
    }
}
