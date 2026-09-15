/**
 * 불일치 case의 근거, 규칙 버전, 승인과 조치 감사 기록, workflow를 소유한다.
 * 근거와 규칙과 감사 기록 없이 해결하지 않으며 결제 상태를 직접 고치지 않는다.
 */
@ApplicationModule
package com.resilientpos.reconciliation;

import org.springframework.modulith.ApplicationModule;
