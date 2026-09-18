/**
 * 모듈 공용 인프라 구성을 소유한다. 식별자 생성기와 시계처럼 여러 bounded context가 같은 구현을 써야 하는
 * 빈을 등록한다. 업무 규칙과 업무 테이블은 두지 않는다.
 */
@ApplicationModule
package com.resilientpos.platform;

import org.springframework.modulith.ApplicationModule;
