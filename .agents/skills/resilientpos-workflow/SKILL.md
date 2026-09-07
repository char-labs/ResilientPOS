---
name: resilientpos-workflow
description: Use when auditing or updating ResilientPOS agent instructions, skills, conventions, or SDD/TDD workflow gaps. Not for ordinary feature implementation or Git status checks.
---

# ResilientPOS workflow

fleta의 코드 근거 분석·지침 분리·워크플로우 리뷰를 차용했다. [출처와 변경점](../../../docs/wiki/borrowed-workflows.md).

## 요청 범위에 맞춰 읽기
- 입력: 요청한 지침/컨벤션 문제, 대상 경로, 해당 PR spec, 변경 기준 ref.
- 먼저 루트 `AGENTS.md`와 대상 파일을 확인한다. 스택 관련이면 `build.gradle.kts`, Gradle Wrapper, 해당 `src` 파일만 추가로 확인한다.
- 현재 선언/구현과 계획을 구분한다. 없는 도메인에 규칙 파일을 미리 만들지 않는다.
- 점검 요청이면 읽기와 제안으로 끝낸다. 수정 요청이면 기존 PR spec을 먼저 갱신하거나 새 PR spec 독립 커밋으로 시작한다.

## 분석 → 최소 변경 → 검증
1. 문제를 누락/중복/충돌/과도한 로딩/실행 불가 참조로 구분하고 파일·근거·영향을 적는다.
2. 프로젝트 공통 계약은 짧은 AGENTS.md, 작업 절차는 해당 스킬, 현재 지식은 wiki에 둔다. Claude는 공통 지침을 import하고 스킬은 탐색용 진입점에서 연결한다.
3. 경로별 규칙이 실제로 필요하면 해당 도구의 범위 지정 방식과 실제 매칭 파일을 확인한다. Claude와 Codex의 로딩 동작을 동일하다고 가정하지 않는다.
4. 승인된 수정 범위만 반영한다. 일반 개발은 기존 resilientpos-tdd/tdd-driver로 연결하고 이 스킬에 테스트 절차를 복제하지 않는다.
5. PR spec·기준 ref·관련 diff·실제 수정 파일을 `workflow-reviewer`에게 전달해 읽기 전용으로 검토시킨다. 주 에이전트는 링크/문법/로컬 PR 검사를 진행하고 결과를 합친다. 리뷰어가 없으면 [리뷰 기준](../../../docs/wiki/workflow-review.md)으로 자체 검토했다고 명시한다.
6. wiki와 spec에 결정·검증·미해결 사항을 남긴다. 결과는 발견 사항, 변경 파일, 검증, 남은 범위 순서로 짧게 보고한다.

## 유지할 프로젝트 계약
PR spec은 `docs/spec/yyyy-mm-dd-slug.md` 한 개, 상세 내용은 관련 wiki만 읽는다.
작은 PR 분할과 Repository 테스트 제외를 유지한다. 별도 plan·계층 spec·CI workflow를 도입하지 않는다.
원본의 전체 위임 의무, 도구 개수 기준, 전역 hook/CLI 설치, 단계마다 승인받는 절차는 가져오지 않는다.
