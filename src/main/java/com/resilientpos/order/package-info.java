/**
 * 주문 line, 할인, checkout 금액과 통화 snapshot, 주문 lifecycle을 소유한다.
 * OPEN 상태에서만 금액과 line을 바꾸고, checkout 이후 금액과 통화는 불변이다.
 */
@ApplicationModule
package com.resilientpos.order;

import org.springframework.modulith.ApplicationModule;
