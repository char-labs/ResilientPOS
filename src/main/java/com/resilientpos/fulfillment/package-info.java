/**
 * 주문 이후 kitchen ticket과 영수증 전달 lifecycle을 소유한다.
 * 원본 주문당 natural key로 한 번만 생성하고 재시도 side effect는 멱등하다.
 */
@ApplicationModule
package com.resilientpos.fulfillment;

import org.springframework.modulith.ApplicationModule;
