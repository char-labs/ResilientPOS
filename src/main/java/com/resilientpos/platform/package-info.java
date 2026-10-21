/**
 * 모듈 공용 인프라 구성을 소유한다. 식별자 생성기와 시계처럼 여러 bounded context가 같은 구현을 써야 하는
 * 빈을 등록한다. 업무 규칙과 업무 테이블은 두지 않는다.
 * 운영 기록 표는 둔다. 플랫폼 운영자의 DLT 재투입 기록(`t_dead_letter_redrive`)이 그것이고 업무 규칙 없이 감사와 멱등 키만 맡는다.
 */
@ApplicationModule
package com.resilientpos.platform;

import org.springframework.modulith.ApplicationModule;
