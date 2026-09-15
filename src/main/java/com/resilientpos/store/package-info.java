/**
 * 판매 정책, 통화와 반올림 규칙, 매장 설정을 소유한다.
 * store policy는 버전으로 관리하며 과거 주문 snapshot을 사후에 바꾸지 않는다.
 */
@ApplicationModule
package com.resilientpos.store;

import org.springframework.modulith.ApplicationModule;
