---
name: resilientpos-tdd
description: Use when developing or changing ResilientPOS business, domain, or Controller behavior with unit/integration TDD, Spring REST Docs, and Fixture Monkey. Excludes Repository-only tests and documentation-only work.
---

# ResilientPOS TDD

개발을 시작하기 전에 저장소 루트의 `AGENTS.md`, 해당 PR spec, `docs/wiki/testing-strategy.md`를 읽는다.
이 스킬은 비즈니스·도메인·Controller 동작에 적용한다. 사용자 요청과 프로젝트 spec이 일반 TDD 스킬의 범위보다 우선한다.

## 한 동작씩 진행
1. spec 인수 조건 하나를 선택하고 테스트 대상·관측 결과·실패 조건을 정한다. 단위/통합 선택은 테스트 전략 표를 따른다.
2. **RED**: 테스트를 먼저 작성하고 해당 테스트를 실행한다. 의도한 assertion 실패와 명령/원인을 spec에 기록한다. 타입이 없으면 최소 선언을 추가해 컴파일한 뒤 동작 실패를 확인한다.
3. **GREEN**: 그 실패를 해결할 최소 구현만 작성하고 같은 명령을 다시 실행한다. 기대값을 구현 결과에 맞춰 바꾸지 않는다.
4. **REFACTOR**: 동작을 유지하며 정리하고 관련 회귀 테스트를 실행한다. 새 동작은 다음 RED로 시작한다.
5. spec에 테스트 이름, RED/GREEN/회귀 명령·관측 결과, wiki 영향을 기록한다. 실행하지 않은 단계는 미실행으로 표시한다.

## 테스트 경계
- 도메인: 실제 Aggregate/Value Object/Policy를 Spring 없이 검사한다.
- 비즈니스: 실제 Service/Facade와 도메인 동작을 검사하고 외부 port만 fake/mock으로 대체한다. 트랜잭션·이벤트·모듈 연결이 인수 조건이면 필요한 통합 테스트를 추가한다.
- Controller: MockMvc와 REST Docs로 HTTP 응답·검증·오류 변환 및 문서 계약을 함께 검사한다. 직접 메서드 호출은 HTTP 계약 검증이 아니다.
- Repository 자체 CRUD/query/JPA 매핑 테스트는 작성하지 않는다. 이 제외는 비즈니스 통합 테스트에서 실제 DB 사용을 금지하는 뜻은 아니다.
- fixture 객체는 Fixture Monkey를 사용하며 판단에 중요한 경계값·기대값과 REST Docs 예시는 고정한다. 불변식은 실제 생성자/팩토리로 검증한다.

## 실패 증거와 작업 소유권
컴파일·의존성 다운로드·환경 오류는 RED가 아니다. 원인을 해결하거나 차단 이유를 보고하고 TDD 완료로 표시하지 않는다.
기존 코드가 이미 테스트를 통과하면 characterization 근거로 남긴다. 실패를 만들기 위해 사용자 코드를 삭제하지 않는다.
위임받은 에이전트는 지정된 경로의 구현과 테스트만 소유하고 재위임하지 않는다. 부모는 같은 파일을 동시에 수정하지 않는다.
네이티브 에이전트가 없으면 주 에이전트가 이 절차를 수행한다. 별도 plan 파일이나 GitHub workflow는 만들지 않는다.
