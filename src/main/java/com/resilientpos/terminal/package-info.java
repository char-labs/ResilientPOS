/**
 * 단말 코드, 활성화 상태, credential rotation 이력을 소유한다.
 * 매장 안에서 코드는 유일하고, 비활성 단말은 인증할 수 없다.
 */
@ApplicationModule
package com.resilientpos.terminal;

import org.springframework.modulith.ApplicationModule;
