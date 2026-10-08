/**
 * 단말의 소속 매장, 단말 코드(매장 안에서 유일), 활성화 상태, credential rotation 이력을 소유한다.
 * 비활성 단말은 인증할 수 없다. 점주는 자기 매장 경로 아래에서 단말을 등록·회전·비활성화하고 코드 순 목록을 본다.
 */
@ApplicationModule
package com.resilientpos.terminal;

import org.springframework.modulith.ApplicationModule;
