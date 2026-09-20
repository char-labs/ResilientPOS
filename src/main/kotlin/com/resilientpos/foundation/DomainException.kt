package com.resilientpos.foundation

/**
 * 도메인 규칙 위반 전용 예외다. JDK·라이브러리가 던지는 범용 예외와 구분해
 * web advice가 이 타입만 클라이언트 오류로 바꿀 수 있게 한다.
 */
class DomainException(val code: String, message: String) : RuntimeException(message)
