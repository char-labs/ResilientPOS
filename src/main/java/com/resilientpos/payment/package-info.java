/**
 * provider 참조, provider idempotency, 승인과 취소 시도, 결제 정본 상태를 소유한다.
 * UNKNOWN은 실패가 아니며, provider 결과는 한 번만 적용한다.
 */
@ApplicationModule
package com.resilientpos.payment;

import org.springframework.modulith.ApplicationModule;
