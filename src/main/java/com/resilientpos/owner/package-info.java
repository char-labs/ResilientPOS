/**
 * 점주(SaaS 고객)의 앱 자체 계정을 소유한다. 이메일과 비밀번호 해시, 가입, 로그인 자격 확인, 로그인 실패 제한이 여기 있다.
 * 세션·Security 체인은 이 모듈이 아니라 웹 입구가 맡고, 매장 소속은 store 모듈이 가진다.
 */
@ApplicationModule
package com.resilientpos.owner;

import org.springframework.modulith.ApplicationModule;
