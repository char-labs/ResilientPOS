package com.resilientpos.foundation

/**
 * 도메인 규칙 위반 전용 예외다. JDK·라이브러리가 던지는 범용 예외와 구분해
 * web advice가 이 타입만 클라이언트 오류로 바꿀 수 있게 한다.
 */
class DomainException(
    val code: String,
    override val message: String,
    val kind: DomainErrorKind = DomainErrorKind.INVALID,
) : RuntimeException(message)

/**
 * 규칙 위반의 업무 의미. HTTP 숫자를 담지 않는다. 상태 코드나 sync item 결과로 바꾸는 일은 각 adapter가 맡는다.
 */
enum class DomainErrorKind {
    /** 요청 내용 자체가 규칙에 어긋난다. 언제 보내도 거절된다. */
    INVALID,

    /** 가리킨 대상이 없다. */
    NOT_FOUND,

    /** 요청은 올바르지만 대상의 현재 상태와 충돌한다. 다른 시점에는 성공할 수 있다. */
    CONFLICT,

    /** 형식과 상태는 맞지만 업무 의미상 처리할 수 없다. */
    UNPROCESSABLE,
}
