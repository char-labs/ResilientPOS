/**
 * 단말의 소속 매장, 활성화 상태, credential rotation 이력을 소유한다.
 * 비활성 단말은 인증할 수 없다. 단말 코드(매장 안에서 유일)는 읽는 코드가 없어 아직 두지 않는다.
 */
@ApplicationModule
package com.resilientpos.terminal;

import org.springframework.modulith.ApplicationModule;
