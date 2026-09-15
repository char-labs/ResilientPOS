/**
 * 단말 watermark, command receipt와 중복 제거, gap과 conflict를 소유한다.
 * lastAckedSequence는 단조 증가하며 gap을 건너뛰어 전진하지 않는다.
 */
@ApplicationModule
package com.resilientpos.sync;

import org.springframework.modulith.ApplicationModule;
