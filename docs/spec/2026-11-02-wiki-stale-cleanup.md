# PR spec: README와 wiki의 낡은 문장을 지금 코드에 맞춘다

## 목적
README와 `docs/wiki`에 지금 main(fc36227)의 코드와 다른 문장이 남아 있다. 가장 큰 것은 reconciliation이다. [payment-reconciliation-case](2026-10-20-payment-reconciliation-case.md)(#100)가 모듈을 채웠는데 [아키텍처](../wiki/architecture.md)와 README는 아직 "모듈이 비어 있다"고 적는다. 모듈 의존 그래프에도 #100·#122가 만든 간선이 빠져 있다. 이 PR은 코드·설정을 그대로 두고 문장만 코드와 머지 커밋에 맞춘다. 사용자가 직접 요청했고 GitHub 이슈는 없다.

## 범위
문장마다 코드 위치나 Modulith 출력과 대조한 사실만 고친다. 모듈 의존은 [아키텍처 §6.5](../wiki/architecture.md#65-그래프를-다시-뽑는-방법)의 임시 테스트를 fc36227(Spring Modulith 2.1.2)에서 돌린 출력이 근거다.

**고칠 곳**
- [아키텍처](../wiki/architecture.md)
  - §2 모듈 사이 이벤트: 넷이 아니라 다섯이다. `contract.PaymentReconciliationRequested`(`PaymentManagement.resolveUnknown`이 발행, 토픽 `payment.reconciliation-requested`, `PaymentReconciliationRequestedListener` → `ReconciliationManagement.open`)를 더한다. `PaymentApproved` 발행 지점에 `resolveFromReconciliation`(대조 해소로 승인)을 더한다.
  - §3.3 결과 불명 결제: 조회로도 모르면 조회 횟수를 올리고 상한(`resilientpos.payment.unknown-query-limit`)에 닿으면 case로 넘긴다. 조회가 다른 금액의 승인을 알려 주면 바로 넘긴다. "reconciliation case는 아직 없다" 문장을 이 사실로 바꾼다.
  - §5.3·§5.4: `PaymentUnknown → [OpenReconciliationCase]` 줄과 "reconciliation의 `OpenCase` 등 | 없다. 모듈은 비어 있다" 줄을 지금 구현(case 열기, 점주 해소, 자동 조치 없음)으로 바꾼다.
  - §6.1 모듈 표: `reconciliation` 책임과 공개 API, `order`의 조회 공개 타입(#122), `contract`의 넘김 이벤트 타입.
  - §6.2·§6.3: `contract`를 쓰는 모듈에 `reconciliation`을 더한다. 간선 `reconciliation → payment`(동기 호출), `order`·`reconciliation → store`(매장 소유 확인), `reconciliation → contract`·`store`·`terminal`·`owner`, `order → owner`를 더한다. `foundation`·`platform` 줄의 "`reconciliation` 제외"를 지운다. "`payment`에 의존하는 모듈은 없다", "동기 호출은 세 줄뿐"을 고친다. 그래프 기준 커밋을 fc36227로 바꾼다.
- [README](../../README.md) 아키텍처 절: 이벤트 표, 모듈 의존 그래프와 설명, `UNKNOWN` 흐름 설명, "reconciliation case는 아직 없습니다", 그림 기준 커밋.
- [도메인 모델](../wiki/domain-model.md) §4.6: 매장 소유 확인(`StoreManagement.requireOwned`)을 부르는 곳에 `catalog`의 `MenuManagement`를 더한다(#121).
- [차용한 스킬과 사용법](../wiki/borrowed-workflows.md): GitHub Actions 줄. PR 게이트가 빌드·Flyway 검사만이 아니라 Dependency submission·Dependency review도 돈다(#119).
- [테스트 전략](../wiki/testing-strategy.md) 도입 상태: "REST Docs와 Fixture Monkey 의존성, 테스트 fixture factory, 첫 Controller 문서 테스트는 아직 없다"를 지금 위치로 바꾼다.
- [wiki 인덱스](../wiki/README.md): JPA 엔티티는 14개가 아니라 15개다(#121의 `MenuEntity`).

**확인했지만 고칠 것이 없던 것**
- `persist`·`EntityManager`로 저장한다는 문장: README와 위 wiki에 없다. [엔티티 지도](../wiki/entity-map.md)의 `persist`는 Spring Data `save`가 새 엔티티를 `persist`로 보낸다는 설명이라 #123 뒤의 코드와 같다.
- `catalog`가 빠진 모듈 목록: README Engineering Principles와 도메인 모델은 이미 9개를 적는다.
- GitHub Actions가 없다는 문장: README와 위 wiki에는 없다. [개발 규칙](../wiki/development-workflow.md)은 #119에서 이미 고쳤다.

## 제외
- 지난 PR spec(`docs/spec/2026-*.md`). 그 당시 기록이다.
- 원문 참고 자료 `docs/references/`.
- 볼트 링크·복사본: `docs/wiki/current-state.md`, `docs/wiki/modulith-kafka-keywords.md`, `.claude/rules`, `.claude/skills`. 여기서 찾은 낡은 문장은 PR 설명에 파일·행·지금 사실로 남기고 사용자가 볼트에서 고친다.
- 사실로 확인할 수 없는 판단 문장. 고치지 않고 결과 절에 목록으로 남긴다.
- 코드·설정·테스트 변경.

## 관련 문서
없음

## 변경 경로
- README.md
- docs/wiki/architecture.md
- docs/wiki/domain-model.md
- docs/wiki/borrowed-workflows.md
- docs/wiki/testing-strategy.md
- docs/wiki/README.md

## 인수 조건
- 위 문서에 "reconciliation이 비어 있다", "reconciliation case는 아직 없다" 같은 문장이 남아 있지 않다.
- 아키텍처 §6.3 표와 README 그래프의 모듈 간선이 fc36227의 Modulith 출력과 같다.
- 바꾼 문장마다 코드 위치나 머지 커밋으로 대조할 수 있다.
- 코드 diff가 없다(`git diff origin/main --stat -- src build.gradle.kts`가 비어 있다).
- 바꾼 문서의 상대 링크와 앵커가 깨지지 않는다.
- `python3 scripts/check_pr.py --base origin/main --worktree`가 통과하고, 제출 전 `--worktree` 없는 실행도 통과한다.

## 검증
- `grep -rn "비어 있\|아직 없" README.md docs/wiki`로 남은 문장을 다시 본다.
- 바꾼 파일의 상대 링크 대상 파일과 `#앵커`가 있는지 확인한다.
- `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전에는 `--worktree` 없이.
- 문서만 바뀌므로 `./gradlew test`는 돌리지 않는다. 그래프 대조용 임시 테스트만 돌리고 지운다.

## Wiki 영향
- 위 고칠 곳의 wiki를 지금 코드에 맞춘다. 요구사항 변화는 없다. 이미 머지된 #100·#119·#121·#122의 사실을 옮길 뿐이고 새로 정하는 규칙·동작이 없다.

## 분할 계획
개발 컨텍스트는 "README·wiki 문장을 main의 코드에 맞춘다" 하나다. origin/main fc36227에서 시작했고 시작 때 열린 PR이 없었다. 함께 진행 중인 feature/order-status-polling(Phase 2)과 perf/query-measurement(Phase 4)는 아직 PR이 없다. Phase 2가 고칠 수 있는 domain-model·architecture의 조회 절과 Phase 4의 capacity·observability는 이 PR에서 건드리지 않는다. push·PR 생성·머지 직전에 다시 확인한다.

## TDD 검증 기록
적용하지 않는다. 동작을 바꾸지 않는 문서 변경이라 새 테스트가 없다. `check_pr.py`와 링크 확인으로 검증한다.

## 결과
개발 전 작성. 개발 후 실행 결과와 변경 이유를 추가한다.
