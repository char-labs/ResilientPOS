# 테스트 전략 — SDD와 TDD

SDD는 PR의 범위·인수 조건을 정하고, TDD는 그 안의 동작을 RED → GREEN → REFACTOR로 구현한다.
업무 개발을 시작할 때만 [공통 TDD 스킬](../../.agents/skills/resilientpos-tdd/SKILL.md)을 읽는다.

## 대상과 테스트 수준

| 대상 | 기본 수준 | 검증하는 것 |
|---|---|---|
| Aggregate / Value Object / Policy | Spring 없는 단위 테스트 | 상태 전이, 계산, 불변식, 경계값 |
| 비즈니스 Service / Facade | 실제 대상 + 외부 port fake/mock의 단위 테스트 | 유스케이스 결과, 도메인 협력, 실패·중복 처리 |
| 비즈니스의 트랜잭션·이벤트·모듈 연결 | 필요한 Spring 통합 테스트 | 실제 연결·커밋·롤백·이벤트 처리 계약 |
| Controller | MockMvc + REST Docs 웹 슬라이스 테스트 | HTTP 매핑, 직렬화, validation, 오류 응답, 문서 계약 |
| HTTP부터 비즈니스까지 연결이 인수 조건인 경우 | MockMvc + REST Docs 통합 테스트 | 실제 웹/비즈니스 연결의 해당 시나리오 |
| Repository 자체 | 이번 TDD 대상에서 제외 | CRUD/query/JPA 매핑 전용 테스트를 추가하지 않음 |

Controller는 직접 메서드 호출로 대체하지 않는다. 슬라이스는 Controller와 필요한 advice/configuration을 포함하고 비즈니스 협력자는 test double로 대체한다.
Repository 제외는 비즈니스 통합 테스트의 실제 persistence 사용을 금지하지 않는다. 롤백은 테스트 자체의 자동 롤백이 아니라 서비스 트랜잭션 종료 후 별도 트랜잭션의 상태로 확인한다.
도메인 동작을 mock으로 대체하거나 호출 횟수만으로 비즈니스 성공을 판단하지 않는다. 문서/설정 전용 변경에 업무 테스트를 만들지 않는다.

## 실행과 기록
1. spec 인수 조건 한 개를 관측 가능한 테스트로 작성한다. 정상·경계·실패 중 해당 변경에 필요한 사례를 선택한다.
2. 필터링한 테스트를 실행해 의도한 assertion 실패를 확인한다. 컴파일·다운로드·환경 실패는 RED가 아니다.
3. 최소 구현 후 같은 명령을 통과시키고, 리팩터링 뒤 관련 회귀 테스트를 실행한다.
4. spec의 TDD 검증 기록에 테스트 이름, 명령, RED 실패 이유와 GREEN/회귀 결과를 남긴다. 미실행을 통과로 표시하지 않는다.

기본 명령은 `./gradlew test --tests 'com.resilientpos.대상테스트'`, 관련 변경을 마친 뒤 `./gradlew test`다.
명세에 없는 다음 기능을 GREEN 단계에서 추가하지 않는다. 기존 동작을 고정하는 characterization 테스트가 바로 통과하면 그 사실을 남기고, 새 동작의 RED 증거와 구분한다.

## Controller와 REST Docs
- HTTP 상태·본문·필요한 헤더를 assertion으로 검증한 뒤 해당 요청/응답으로 `document(...)`를 수행한다.
- request/response fields와 존재하는 path/query parameter를 명시한다. `relaxed*`나 무분별한 ignored 처리로 계약 불일치를 숨기지 않는다.
- 성공뿐 아니라 적용되는 validation/비즈니스 오류 응답도 문서화한다. REST Docs 스니펫 생성만으로 동작 검증을 대체하지 않는다.
- 스니펫 출력은 `build/generated-snippets`로 구성하고 테스트 실행 후 실제 파일을 확인한다. 생성물을 수동 편집하지 않는다.
- 첫 적용 PR에서 현재 Spring Boot 버전에 맞는 REST Docs test 의존성과 테스트 설정을 확인한다. 웹 슬라이스와 통합 테스트의 annotation/import 경로를 예전 버전에서 복사하지 않는다.

## Fixture Monkey
- fixture 객체 생성은 Fixture Monkey를 기본으로 하고 공통 factory는 `src/test` 아래에 둔다. Kotlin 생성에는 Kotlin 지원을 사용한다.
- 테스트 의도를 결정하는 가격·수량·상태·시간·ID·컬렉션 크기는 builder에서 고정한다. 경계값과 실패값을 무작위 추첨에 맡기지 않는다.
- REST Docs에 표시되는 모든 값은 고정하고, 랜덤 실패는 입력값/재현 근거를 기록한다.
- 도메인 불변식 검증은 유효한 입력 fixture를 실제 생성자/팩토리에 전달한다. reflection으로 불변식을 우회한 도메인 객체를 정상 사례로 사용하지 않는다.
- validation 실패 테스트는 유효한 fixture에서 검증할 필드만 무효화한다. 단순 상수까지 불필요하게 fixture 객체로 감싸지 않는다.

## 에이전트 실행
- Claude: `/resilientpos-tdd`로 스킬을 명시 호출하거나 `tdd-driver로 이 spec의 동작을 구현해줘`라고 요청한다.
- Codex: `$resilientpos-tdd` 또는 `tdd-driver 에이전트로 이 spec을 구현해줘`라고 요청한다.
- 주 에이전트는 spec/인수 조건/소유 경로/기준 커밋을 전달하고, 하위 에이전트는 해당 구현과 테스트를 전담한다. 같은 파일에 동시에 쓰지 않는다.
- 주 에이전트가 범위 분할과 최종 spec/wiki 반영을 맡는다. 하위 에이전트는 재위임하지 않는다.
- 자동 선택은 도구 지원과 설정에 의존한다. 스킬/에이전트 탐색이 지원되지 않으면 공통 스킬 경로를 직접 읽고 동일한 절차를 수행한다.
- 새 세션에서 Claude `/agents`와 스킬 목록, Codex의 사용 가능한 에이전트/스킬 목록을 확인한다. 사용자 홈의 전역 설정·권한·모델은 바꾸지 않는다.

## 도입 상태와 근거
이번 PR은 스킬·에이전트·테스트 정책 설치다. REST Docs와 Fixture Monkey 의존성, 테스트 fixture factory, 첫 Controller 문서 테스트는 아직 없다. 최초 기능 PR에 포함해 실제 빌드로 검증한다.
- [Codex 스킬 위치](https://learn.chatgpt.com/docs/build-skills), [커스텀 에이전트](https://learn.chatgpt.com/docs/agent-configuration/subagents)
- [Claude 스킬](https://code.claude.com/docs/en/skills), [에이전트와 skills 주입](https://code.claude.com/docs/en/sub-agents)
- [Spring REST Docs](https://docs.spring.io/spring-restdocs/docs/current/reference/htmlsingle/): MockMvc 테스트에서 검증한 API로 스니펫을 생성한다.
- [Fixture Monkey 의존성](https://naver.github.io/fixture-monkey/docs/get-started/requirements/), [Kotlin 객체 생성](https://naver.github.io/fixture-monkey/docs/generating-objects/fixture-monkey/), [필드 고정](https://naver.github.io/fixture-monkey/docs/customizing-objects/quick-start-guide/)
