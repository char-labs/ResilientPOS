# PR spec: 도메인 모델 용어와 유비쿼터스 언어 용어집

## 목적
DDD 용어(Bounded Context, Aggregate, Entity, Value Object, Policy, Facade, Service, Command, Event, Repository)의 정의와 이 프로젝트에서의 예시가 정본 문서에 없다. [README](../../README.md#engineering-principles)와 원문 [도메인 청사진](../references/05_Domain_Development_Blueprint.md)에 역할 표만 흩어져 있고, 원문은 규칙상 참고 자료라 정본이 아니다. 이미 내린 어휘 결정(`terminal` 유지, `operation`, `watermark`, `checkout`)은 저장소 어디에도 기록되지 않았다. 첫 도메인 PR 전에 용어 정의·용어집·설계 체크리스트를 wiki 한 페이지로 고정해 Claude·Codex·Gemini와 사람이 같은 말을 쓰게 한다.

## 범위
- `docs/wiki/domain-model.md` 신설: 용어 정의 표(각 용어에 한 줄 정의와 ResilientPOS 예시), bounded context 7개의 aggregate·소유·불변식 요약(원문 blueprint 링크), 유비쿼터스 언어 용어집(채택어·기각어·이유), 도메인 설계 체크리스트, 참고 사례와 다르게 가는 결정.
- 설계 결정 두 가지를 정본으로 확정한다. (1) Aggregate 상태 전이는 불변 객체가 새 인스턴스를 반환한다. (2) Aggregate와 JPA 엔티티는 별개 클래스이고 변환은 persistence adapter가 소유한다.
- 참고 사례의 도식 6장(컨텍스트 맵, 납부 도메인 정의, 승인 시퀀스, 레이어 구조, 가입 Application, Biz-component)을 읽고 세 절을 더한다: aggregate 안의 구성(루트와 이력 자식 엔티티), 레이어 경계를 넘는 타입, DESIGN·flow 도식 규약. 사례와 다르게 가는 결정에 트랜잭션 범위와 `snapshot` 어휘 행을 추가한다. 볼트 spec-write 템플릿(DESIGN·flow)에는 도식 규약 링크 한 줄만 둔다.
- 포인터: `AGENTS.md`의 비즈니스·도메인 개발 항목, wiki README, README Engineering Principles에서 새 페이지를 링크한다. 역할 표를 네 번째로 복제하지 않는다.
- 소유 모듈: 없음(전 모듈 공통 지식). 계약/불변식/복구/관측성: 문서 PR이라 실행 경로가 없다. 문서가 고정하는 불변식은 "Aggregate 상태 전이는 새 인스턴스, JPA 엔티티는 adapter 안"이며, 첫 도메인 PR의 ArchUnit 도메인 레이어 규칙이 후자를 검사한다.

## 제외
- 코드 변경. ArchUnit 도메인 레이어 규칙 추가는 [dependency-direction-guard](2026-09-15-dependency-direction-guard.md)의 결정대로 첫 도메인 PR에서 한다.
- 원문 `docs/references/**` 수정.
- 볼트 `rules/domain-modeling-kotlin.md`(Aggregate/Value Object/Event/Repository를 Kotlin으로 쓰는 형태). 저장소 밖 볼트 파일이라 이 PR diff에 없다. 같은 작업에서 함께 작성하며 wiki 페이지는 그 존재와 역할만 적는다.
- 상태 열거값 전체 목록(시스템 설계 §5 상태 모델). 각 aggregate PR의 spec이 다룬다.
- `inventory`처럼 아직 비목표인 bounded context.

## 관련 문서
없음. DESIGN 계층은 아직 없다. 원문 근거는 [도메인 청사진 §1·§2·§6.1](../references/05_Domain_Development_Blueprint.md), [개발 가이드 §1.1·§3.3](../references/03_Development_Guide.md), [시스템 설계 §1.2·§5](../references/02_System_Design.md)다. 외부 참고는 [카카오페이 여신코어 DDD 사례](https://tech.kakaopay.com/post/backend-domain-driven-design/)다.

## 변경 경로
- docs/spec/2026-09-15-domain-model-glossary.md
- docs/wiki/domain-model.md
- docs/wiki/README.md
- docs/wiki/changes.md
- AGENTS.md
- README.md

## 인수 조건
1. `docs/wiki/domain-model.md`에 용어 정의 표가 있고, 각 용어에 이 프로젝트의 실제 이름(bounded context 7개의 aggregate·command·event)이 예시로 붙어 있다.
2. 용어집에 이미 결정한 어휘 4건(`terminal`, `operation`, `watermark`, `checkout`)이 채택어·기각어·이유와 함께 있다.
3. 상태 전이 스타일과 JPA 분리 결정이 명시되고, 기존 rules(`effective-java-for-kotlin.md` Item 17)와 blueprint 예시 중 어느 쪽을 따르는지 적혀 있다.
4. `AGENTS.md`·wiki README·README에서 새 페이지로 가는 링크가 있고, README의 역할 분리 절은 복제하지 않는다.
5. 새 페이지의 상대 링크가 모두 실재 파일을 가리킨다.
6. `python3 scripts/check_pr.py --base main --worktree`가 통과한다.

## 검증
```sh
python3 scripts/check_pr.py --base main --worktree
```
- 상대 링크: 새 페이지의 링크 대상 경로가 존재하는지 확인한다.
- `workflow-reviewer`에게 spec·wiki·포인터를 읽기 전용으로 검토시킨다.
- 실패 시나리오: 링크 대상이 없거나 README 역할 표가 중복되면 수정 후 재확인한다.

## Wiki 영향
- 신설 [domain-model.md](../wiki/domain-model.md). [wiki README](../wiki/README.md) 인덱스에 진입점 추가.
- [변경 기록](../wiki/changes.md)에 이 spec 링크와 요구사항 영향을 남긴다. 제품 요구사항은 바뀌지 않는다. 원문 blueprint의 모델을 정본 지식으로 옮기고 설계 결정 두 가지를 확정한 것이다.

## 분할 계획
예상 규모: wiki 본문 약 250줄(도식 분석 반영 후 약 200줄), spec 약 100줄, 포인터 약 10줄, 변경 기록 약 15줄로 총 350줄 내외. 경고 한도(600줄) 안이다.
초과하면 용어집의 Command/Event 이름 절을 후속 PR로 옮기고 정의 표·결정·체크리스트만 남긴다.

## TDD 검증 기록
문서 전용 변경이라 적용하지 않는다. 업무 동작과 테스트 대상 코드가 없다.

## 결과
`python3 scripts/check_pr.py --base main --worktree` PASS(일반 6개/213줄). 새 페이지와 포인터의 상대 링크는 모두 실재한다. 인수 조건 6개를 만족했다.

`workflow-reviewer` 검토(CRITICAL 0, HIGH 2, MEDIUM 5, LOW 4)에서 반영한 것:
1. 원문이 `PaymentConfirmed`와 `PaymentApproved`를 섞어 쓰는 것을 확인하고 provider 승인은 `PaymentApproved`로 용어집에 고정했다. reconciliation 해결 결과의 이벤트 이름은 payment PR로 미뤘다.
2. 전이 결과 타입 `Transition`과 `DomainEvent`를 `foundation`에 두려던 초안은 "foundation은 공유 도메인 모델을 두지 않는다"와 부딪혀 위치를 미확정(§4.5)으로 올렸다. 볼트 규칙은 형태만 정한다.
3. wiki 용어 정의 표에서 README 역할 표를 다시 쓰던 규칙 문장을 줄이고 링크로 바꿨다. 타입 이름이 목표 모델임을 표 앞에 밝혔다.
4. 볼트 규칙에만 있던 결정(outbox 테이블 없음, `NUMERIC(19,4)`·`uuid`·`t_` 접두어)을 wiki §4.3으로 올렸다. `Money` 생성자가 public인 실제 코드에 맞게 값 객체 규칙 문장을 고쳤다.

반영하지 않은 것: README·`AGENTS.md`의 낡은 스택 문장(Spring Modulith 미도입, H2, 모듈 5개)은 사실과 다르지만 이 PR의 목표 밖이라 별도 문서 PR로 뺐다.

볼트 `rules/domain-modeling-kotlin.md`(98줄)는 저장소 밖에 작성했고 `.claude/rules` 링크로 로드되는 것을 확인했다.

2차 — 참고 사례의 도식 6장 분석 반영. `check_pr.py --base main --worktree` PASS(일반 6개/273줄), 링크 실재 확인.
도식에서만 읽히는 근거 네 가지를 규약으로 옮겼다.
1. 컨텍스트 맵: 컨텍스트 사이 화살표가 없고 자식 엔티티 대부분이 이력·기록이다 → §2 "aggregate 안의 구성" 표와 §6 컨텍스트 맵 규약.
2. 승인 시퀀스: 전제 조건 조회와 실패 분기를 트랜잭션 상자 앞에, 상자 안은 쓰기 2건, 응답 뒤 Kafka 소비자 → §6 시퀀스 규약. 볼트 spec-write 템플릿(DESIGN·flow)에 링크 한 줄씩.
3. 레이어 도식: 화살표마다 타입 접미사 → §5 "레이어 경계를 넘는 타입" 표.
4. 가입 Application: 도메인 모듈에 use case가 없고 `core_app`이 세 도메인을 한 트랜잭션으로 묶는다 → §8에 "우리는 Facade가 컨텍스트 안, 교차는 이벤트, 동기 호출은 즉시 결과 필요 시만" 행과 이유(독립 배포 경로). 볼트 규칙의 Facade 호출 문장도 같은 뜻으로 고쳤다.
납부를 Recovery로 부른 어휘 결정은 §3의 "영어 이름은 업무 의미" 원칙으로, `snapshot` 자식 엔티티는 §8의 차이 행으로 남겼다.

2차 `workflow-reviewer` 검토(HIGH 3, MEDIUM 9, LOW 8)에서 반영한 것: 컨텍스트 사이 Facade 동기 호출은 청사진 §1(금지)과 시스템 설계 §1.3(조건부 허용)이 충돌하는 지점이라 §4.6 미확정으로 올리고 작업 가정만 적었다. §5 표에 `*Command`와 Query/View를 넣고 Repository 경계를 Facade로 고쳤다. 취소는 [외부 연동 전략](../references/04_External_Integration_Strategy.md)대로 별도 attempt로 바로잡고, provider idempotency 기록의 위치와 주문 상태 전이 이력은 해당 PR로 미뤘다. 저장소에 없는 회의를 출처로 쓴 문장을 "이 PR에서 정리한 설계안"으로 바꿨다. 청사진 §1.1의 `CheckoutSnapshot`을 `Checkout`으로 대체한다고 §3에 명시했다. 볼트 템플릿의 규약 요약은 링크 한 줄로 줄였다.

3차 — 미확정 3건 중 2건을 사용자 결정으로 확정했다. 공개 타입은 모듈 루트(§4.4), `Transition`·`DomainEvent`는 `foundation`(§4.5). §4.6(컨텍스트 사이 동기 호출)은 사용자가 커밋 분리의 정합성을 우려해 EPR 이벤트 처리를 제안했고, 원문(기획 PRD 3.2·PR-02, 개발 가이드 §4.3, 청사진 §3.2, 시스템 설계 §1.3·§6.2)을 대조해 "sync intake 한 곳만 동기 호출, 항목당 한 트랜잭션, 이벤트는 같은 커밋에 EPR 기록 후 AFTER_COMMIT 처리"로 확정했다. 순수 이벤트(`ACCEPTED` 응답)는 `order`/`payment` 분리 시점에 §6.2 playbook으로 전환한다. 미확정 항목은 남지 않았다.
