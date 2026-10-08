package com.resilientpos.terminal

/** 단말의 활성 상태. `INACTIVE`는 인증할 수 없고 되돌리지 않는다. 문자열로 저장하므로 이름을 바꾸지 않는다. */
enum class TerminalStatus { ACTIVE, INACTIVE }
