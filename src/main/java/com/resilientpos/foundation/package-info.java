/**
 * 모든 모듈이 공유하는 프레임워크 없는 값 타입을 소유한다.
 * Spring, JPA, Web, 브로커 의존성을 두지 않으며 업무 규칙은 각 bounded context가 판단한다.
 */
@ApplicationModule
package com.resilientpos.foundation;

import org.springframework.modulith.ApplicationModule;
