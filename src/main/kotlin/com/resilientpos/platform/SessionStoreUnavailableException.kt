package com.resilientpos.platform

import org.springframework.dao.DataAccessException

/** 세션 저장소(Redis)에 닿지 못해 점주 요청을 처리하지 못했다. 응답 문구는 [ErrorType.SERVICE_UNAVAILABLE]의 고정 문구다. */
class SessionStoreUnavailableException(cause: DataAccessException) : RuntimeException("session store unavailable", cause)
