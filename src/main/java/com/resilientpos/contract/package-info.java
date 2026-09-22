/**
 * 모듈을 건너가는 이벤트만 둔다. 두 모듈이 서로의 사실을 주고받으면 타입 의존이 양방향이 되어
 * Modulith의 순환 검사에 걸리므로, 양쪽이 함께 의존하는 이곳에 그 사실을 둔다.
 *
 * bounded context가 아니다. 업무 규칙도 상태도 없고 계약만 있다.
 * 다른 업무 모듈의 타입을 들지 않는다. 들면 그 방향으로 간선이 생겨 순환이 되돌아온다.
 */
@ApplicationModule
package com.resilientpos.contract;

import org.springframework.modulith.ApplicationModule;
