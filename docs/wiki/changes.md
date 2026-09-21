# Wiki 변경 기록

각 PR에 항목을 추가한다. 이전 기록은 유지한다.

## 2026-09-07 — 개발 하네스
- Spec: [development-harness](../spec/2026-09-07-development-harness.md)
- 요구사항 영향: 개발 절차에 spec 선작성, PR 크기 제한, wiki 동기화를 추가했다. 제품 요구사항은 변경하지 않았다.
- 갱신: [개발 규칙](development-workflow.md), [현재 상태](current-state.md), [진입점](README.md).
- 첨부 자료는 실행 지침으로 취급하지 않고 원문 보관했다.
- 요구사항 영향: 추가 요청에 따라 GitHub Actions workflow를 제거하고 공통 LLM 지침과 로컬 검사로 운영하도록 개발 규칙과 현재 상태를 갱신했다.
- 요구사항 영향: 공식 Claude/Codex 문서를 근거로 공통 지침을 정리하고 작업별 wiki 읽기로 변경했다. 현재 기술 스택과 미도입 기술을 구분했으며, [지침 설계 근거](agent-instructions.md)에 출처와 적용 판단을 기록했다.
- 요구사항 영향: README를 SDD 적용 방식·현재 기술 스택·설계 및 검증 방법론 중심으로 정리했다. 상세 실행 절차는 wiki에 유지하며, 제품 요구사항과 의존성은 변경하지 않았다.

## 2026-09-07 — TDD 스킬과 에이전트
- Spec: [tdd-agent](../spec/2026-09-07-tdd-agent.md)
- 요구사항 영향: 비즈니스·도메인·Controller 개발에 단위/통합 TDD를 적용하고 Repository 자체 테스트는 제외한다. REST Docs와 Fixture Monkey를 채택하며 의존성 설치는 최초 기능 PR에서 수행한다.
- 갱신: [테스트 전략](testing-strategy.md), [개발 규칙](development-workflow.md), [현재 상태](current-state.md), spec 템플릿과 README.

## 2026-09-07 — fleta 워크플로우 차용
- Spec: [fleta-workflow-adoption](../spec/2026-09-07-fleta-workflow-adoption.md)
- 요구사항 영향: workflow 점검, commit/push 범위 분리, 읽기 전용 지침 리뷰를 추가했다. 기존 SDD/TDD와 작은 PR 원칙은 유지한다.
- 갱신: [차용 내역](borrowed-workflows.md), [리뷰 기준](workflow-review.md), [개발 규칙](development-workflow.md), 공통 지침과 도구별 진입점.
- 요구사항 영향: 다른 PC에서의 개발을 위해 차용 문서의 개인 절대 경로를 제거했다. 출처 식별자는 패키지 내부 경로와 해시로 유지하며 실행 참조는 저장소 내부로 한정한다.
- 요구사항 영향: commit-push가 변경 의도별로 spec·wiki·개발 도구를 분리하고, 도메인 개발은 요구사항별 구현과 테스트를 묶도록 보완했다.

## 2026-09-08 — 스킬 정본을 볼트로 이전
- Spec: [vault-canonical-skills](../spec/2026-09-08-vault-canonical-skills.md)
- 요구사항 영향: 제품 요구사항은 변경하지 않았다. 스킬·에이전트 정본의 보관 위치를 Obsidian 볼트로 옮기고 저장소는 심볼릭 링크로 연결하도록 개발 환경 구성만 바꿨다. 새 머신에서는 clone 후 `make bootstrap`을 실행해야 스킬이 붙는다.
- 요구사항 영향: `.agents`, `.claude`, `.codex` 세 경로를 통째로 추적에서 제외했다. 지침 변경이 제품 PR diff에 나타나지 않으면서, 링크가 저장소 안에 있어 스킬은 이 저장소에서만 적용된다. Codex 에이전트 toml도 볼트로 옮기고 `.codex/agents`를 링크로 연결한다.
- 요구사항 영향: 개발 명령 진입점을 `Makefile`로 모으고 README에 명령 표와 자동완성 안내를 추가했다. 기존 명령의 동작은 바뀌지 않았다.
- 갱신: [지침 설계 근거](agent-instructions.md), [현재 상태](current-state.md), [개발 규칙](development-workflow.md), 공통 지침, README, Makefile.

## 2026-09-13 — 볼트 rules 링크와 워크플로우 산출물 추적 제외
- Spec: [rules-link-and-ignores](../spec/2026-09-13-rules-link-and-ignores.md)
- 요구사항 영향: 제품 요구사항은 변경하지 않았다. `make bootstrap`이 `.claude/rules`도 볼트 `rules/`로 링크해 항상 적용되는 규칙 파일이 자동 로드되고, 이식된 워크플로우 스킬의 휘발성 산출물 경로(`.develop-workflow/`, `.review/`, `.review-output/`, `.orchestrator/`, `.plans/`)를 추적에서 제외했다.
- 갱신: [지침 설계 근거](agent-instructions.md), 공통 지침, `.gitignore`, `scripts/link_claude.py`와 테스트.
## 2026-09-13 — 볼트 전체 이식에 맞춘 wiki·공통 지침 진입점 갱신
- Spec: [wiki-vault-migration-sync](../spec/2026-09-13-wiki-vault-migration-sync.md)
- 요구사항 영향: 제품 요구사항은 변경하지 않았다. `Global-claude-code` 공통 노하우 전체(스킬 21개·에이전트 19개·rules 2개)가 볼트에 들어온 사실과 원본 방식 우선 결정, 역할별 모델 배정(opus/sonnet/haiku), 스킬 선행 규칙(합의 전이면 grill 먼저)을 wiki와 공통 지침에 반영했다. 2026-09-07의 보류 항목은 모두 도입되었고 개발 규칙 조정은 후속 spec으로 연결했다.
- 갱신: [차용 내역](borrowed-workflows.md) 전면, [지침 설계 근거](agent-instructions.md), [리뷰 기준](workflow-review.md), [wiki 인덱스](README.md), 공통 지침.
## 2026-09-13 — 원본 워크플로우 우선 개발 규칙 조정
- Spec: [development-rules-original-first](../spec/2026-09-13-development-rules-original-first.md)
- 요구사항 영향: 제품 요구사항은 변경하지 않았다. 개발 절차 규칙을 이식된 원본 워크플로우에 맞췄다: grill 선행, 장기 스펙 계층(`docs/spec/DESIGN.md`·`concerns/`·`flows/`) 위치, epic 브랜치와 "요청 1건 = PR 1개"(크기 한도는 유지, 초과 시 stacked PR), rebase 허용과 force-push 금지, 커밋 형식(`type(scope): 한국어 description`), PR 본문 4단, Contract/Checkpoint는 spec 안에만 기록, `.develop-workflow.yaml` 미도입.
- 갱신: [개발 규칙](development-workflow.md), [spec 템플릿](../spec-template.md)(관련 문서·Checkpoint 선택 항목), 공통 지침, `scripts/test_check_pr.py`(비날짜형 spec 파일 회귀 테스트).

## 2026-09-13 — 어드바이저 문서 기준 모델 배정 근거 갱신
- Spec: [advisor-model-policy](../spec/2026-09-13-advisor-model-policy.md)
- 요구사항 영향: 제품 요구사항은 변경하지 않았다. Anthropic 어드바이저 도구 문서의 측정("값싼 실행자 + 상위 어드바이저 ≈ 상위 단독 품질, 비용은 같거나 낮음")을 근거로 구현 실행자 `tdd-driver`를 `opus`에서 `sonnet`으로 내렸다. 설계 검증·코드 리뷰 게이트가 어드바이저 역할이며, 게이트 없는 단독 호출은 dispatch 시 `opus`로 올린다. 볼트의 자문 경로는 단발 자문 허용·권고 길이 상한·메인 tier 조건부 시작/종료 자문으로 조정했다.
- 갱신: [지침 설계 근거](agent-instructions.md), [차용 내역](borrowed-workflows.md).

## 2026-09-14 — Codex 에이전트 도입과 외부 CLI 가용성 정정
- Spec: [codex-agents-and-cli-correction](../spec/2026-09-14-codex-agents-and-cli-correction.md)
- 요구사항 영향: 제품 요구사항은 변경하지 않았다. 볼트 `codex/agents/`에 Codex 에이전트 6개(`tdd_driver`, `test_oracle`, `workflow_reviewer`, `code_reviewer`, `spec_validator`, `complexity_reducer`)를 만들어 저장소 `.codex/agents` 링크로 연결했고, 실제 스폰으로 로드를 검증했다. 2026-09-07 spec이 만들었다가 볼트 이전 중 유실된 2개를 포함한다.
- 요구사항 영향: 2026-09-13 이식 당시 `codex` CLI를 "없음"으로 적은 것이 오판이었다. 실제로는 0.154.0이 설치되어 있어 `*-codex` 래퍼 3개가 동작하며, Multi-LLM 경로는 Claude 단독이 아니라 Claude + Codex 2개로 돈다. `gemini`만 여전히 없다. 래퍼의 `command -v` 확인 로직은 머신 이식성을 위해 그대로 둔다.
- 갱신: [차용 내역](borrowed-workflows.md), [지침 설계 근거](agent-instructions.md).

## 2026-09-15 — Spring Modulith 모듈 골격과 경계 검증
- Spec: [module-skeleton](../spec/2026-09-15-module-skeleton.md)
- 요구사항 영향: 제품 요구사항은 변경하지 않았다. [개발 가이드 1.1 Public API rule](../references/03_Development_Guide.md)과
  [도메인 청사진 2절](../references/05_Domain_Development_Blueprint.md)의 bounded context 7개를 패키지 경계로 선언하고,
  타 모듈 `internal` 참조 금지를 `ApplicationModules.verify()` 테스트로 강제했다. 도메인 타입·스키마·API는 없다.
- 결정: Modulith starter는 `core`와 `test` 2개로 시작한다. 원문 2.2절의 baseline은 완성 시점의 조립 상태이므로
  EPR(`starter-jpa`)은 도메인 이벤트가 생기는 checkout PR에서, `starter-insight`는 관측성 단계에서 추가한다.
- 갱신: [현재 상태](current-state.md).

## 2026-09-15 — foundation 값 타입 Money와 UUID v7 식별자
- Spec: [foundation-value-types](../spec/2026-09-15-foundation-value-types.md)
- 요구사항 영향: 제품 요구사항은 변경하지 않았다. [개발 가이드 3.1 IDs / 3.2 Money](../references/03_Development_Guide.md)의
  규약을 코드로 옮겼다. 금액은 통화 표준 자릿수를 canonical scale로 쓰고 암묵적 반올림과 통화 불일치 연산을 거절한다.
  식별자는 UUID v7이며 애플리케이션이 저장 전에 만든다.
- 결정: `CurrencyScalePolicy`는 만들지 않았다. `java.util.Currency`의 표준 자릿수로 충분하며, 매장·국가별 예외
  반올림이 실제 요구가 될 때 `store` 정책으로 만든다. UUID의 단조 증가도 요구하지 않는다. 동기화 순서의 증거는
  단말의 durable sequence다.
- 갱신: [현재 상태](current-state.md).

## 2026-09-15 — 의존성 방향 가드와 Gradle 분할 조건 기록
- Spec: [dependency-direction-guard](../spec/2026-09-15-dependency-direction-guard.md)
- 요구사항 영향: 제품 요구사항은 변경하지 않았다. [개발 가이드 2.1 의존성 governance](../references/03_Development_Guide.md)의
  `foundation` 행을 ArchUnit 테스트로 강제했다. Modulith 경계 검증은 모듈 간 internal 참조와 순환만 보고 프레임워크
  의존 방향은 보지 않아 생긴 빈틈이다.
- 결정: Gradle subproject 분할은 하지 않는다. 가이드 1절의 조건(패키지 경계 안정, 독립 build/test/dependency 필요,
  외부 contract artifact 필요)이 하나도 충족되지 않았고 모듈 7개가 비어 있다. 트리거 조건을 현재 상태 문서에 적어
  조건부로 미룬 결정으로 남겼다.
- 갱신: [현재 상태](current-state.md).

## 2026-09-15 — PostgreSQL 단일 엔진과 Flyway 스키마 소유
- Spec: [postgres-flyway-baseline](../spec/2026-09-15-postgres-flyway-baseline.md)
- 요구사항 영향: 제품 요구사항은 변경하지 않았다. [시스템 설계 2.1](../references/02_System_Design.md)의 "PostgreSQL이 주문·결제의 정본"과
  [개발 가이드 2.5 Migration ownership](../references/03_Development_Guide.md)을 실행 기반으로 옮겼다. H2를 제거하고
  통합 테스트를 실제 PostgreSQL 컨테이너에서 돌린다.
- 결정: 테스트 DB는 H2 호환 모드가 아니라 Testcontainers의 실제 PostgreSQL을 쓴다. 요구사항이 DB unique 제약을
  정확성의 마지막 방어선으로 규정하므로 엔진이 다르면 그 방어선을 검증할 수 없다. 대신 `./gradlew test`에 Docker가
  필요해졌다.
- 갱신: [현재 상태](current-state.md).

## 2026-09-15 — Flyway 마이그레이션 버전 충돌 검사
- Spec: [flyway-version-guard](../spec/2026-09-15-flyway-version-guard.md)
- 요구사항 영향: 제품 요구사항은 변경하지 않았다. 개발 하네스가 같은 Flyway 버전의 마이그레이션 중복을 막는다.
  각 PR은 혼자 보면 정상이라 리뷰로는 잡히지 않고, 합쳐진 뒤 배포 시점에 기동 실패로 드러나는 종류의 문제다.
- 한계: 판정은 검사를 실행한 시점의 기준 브랜치 기준이다. 병렬 PR에서 뒤에 머지되는 쪽은 예전 통과 결과가
  낡을 수 있다. 브랜치 보호로 재실행을 강제할 수 없는 환경이라 머지 직전 재실행을 규칙으로 정했다.
- 갱신: [개발 규칙](development-workflow.md).

## 2026-09-15 — 도메인 모델 용어와 유비쿼터스 언어 용어집
- Spec: [domain-model-glossary](../spec/2026-09-15-domain-model-glossary.md)
- 요구사항 영향: 제품 요구사항은 변경하지 않았다. [도메인 청사진](../references/05_Domain_Development_Blueprint.md)과
  [시스템 설계](../references/02_System_Design.md)에 흩어진 모델을 정본 wiki 한 페이지로 옮기고, DB 설계 리뷰에서 정한
  어휘(`terminal` 유지, `operation`, `watermark`, `checkout`)를 처음 저장소에 기록했다.
- 결정: Aggregate 상태 전이는 불변 객체가 새 인스턴스를 반환한다(청사진 §1.1의 제자리 변경 예시를 대체). Aggregate와
  JPA 엔티티는 별개 클래스이고 변환은 persistence adapter가 소유한다. Kotlin 형태 규칙은 볼트 `rules/domain-modeling-kotlin.md`에
  두었다(저장소 밖, `.claude/rules` 링크로 로드).
- 결정(기록): 모듈 안 이벤트 전달은 Event Publication Registry이고 outbox·processed-event 테이블은 만들지 않는다. 금액 `NUMERIC(19,4)`,
  식별자 `uuid`, 테이블 `t_` + 단수 명사. 모두 원문과 DB 설계 리뷰에서 정한 것을 처음 wiki에 적었다.
- 결정: 공개 타입은 모듈 루트(ADR-002, 청사진 §6.1의 `api/`는 쓰지 않음). `Transition<A, E>`와 `DomainEvent{eventId, occurredAt}`는 `foundation`.
- 결정: 컨텍스트 사이 동기 호출은 `sync` intake → `order`/`payment` 한 곳만 허용하고 항목당 로컬 트랜잭션 하나로 묶는다(호출된 Facade는
  `REQUIRED` 합류). 이벤트는 같은 커밋에 EPR로 기록되고 AFTER_COMMIT 리스너·향후 Kafka relay가 후속 처리한다. 청사진 §1의 무조건 금지와
  시스템 설계 §1.3의 조건부 허용 사이에서 §1.3을 따르되 범위를 좁힌 것이다. 응답 계약은 청사진 §3.2의 `APPLIED`를 유지한다.
- 참고 사례의 도식 6장에서 가져온 규약: 상태 변경과 이력을 한 aggregate에 두는 기준(aggregate 안의 구성 표), 경계마다 타입
  접미사를 고정하는 표, DESIGN 컨텍스트 맵·flow 시퀀스 도식 규약. 사례와 달리 use case는 컨텍스트 안에 두고 트랜잭션이
  컨텍스트를 넘지 않게 한 이유(독립 배포 경로)를 적었다. 볼트 spec-write 템플릿(DESIGN·flow)에 규약 링크 한 줄씩 추가.
- 갱신: [도메인 모델](domain-model.md) 신설, [wiki 인덱스](README.md), `AGENTS.md`, README 설계 절.

## 2026-09-16 — order 도메인 모델과 CreateOrder 계약
- Spec: [order-domain-model](../spec/2026-09-16-order-domain-model.md)
- 요구사항 영향: 제품 요구사항은 변경하지 않았다. [도메인 청사진 §1.1·§2](../references/05_Domain_Development_Blueprint.md)의
  `Order` aggregate와 `CreateOrder → OrderCreated` 계약을 첫 코드로 옮겼다. 생성 불변식(line 1개 이상, 수량 1 이상, 단가 0 이상,
  통화 일치)은 원문에 흩어진 규칙을 이 PR에서 명시한 것이다.
- 결정: `CreateOrder`의 첫 상태는 `OPEN`이고 오프라인·동기화 여부는 order 상태가 아니다. [시스템 설계 §5](../references/02_System_Design.md)의
  `OFFLINE_CREATED → SYNCED`는 이 결정으로 대체한다. 청사진 §1.1의 `OrderService`는 만들지 않았다. 생성 규칙이 전부 `Order` 안에 있어
  조율할 대상이 없다.
- 결정: CreateOrder 수직 절단은 규모 한도 때문에 stacked PR로 나눈다. 이 PR은 도메인·계약까지이고 Facade 구현·JPA adapter·
  `V002` 마이그레이션·재시도 판정·Fixture Monkey는 다음 PR `order-create-order-persistence`, REST Controller는 그다음이다.
- 갱신: [도메인 모델](domain-model.md) §8, [현재 상태](current-state.md).

## 2026-09-16 — CreateOrder 영속화와 Facade 구현
- Spec: [order-create-order-persistence](../spec/2026-09-16-order-create-order-persistence.md)
- 요구사항 영향: 제품 요구사항은 변경하지 않았다. [기획 PRD 3.2](../references/01_Product_Planning_PRD.md)의 "exact retry는 이전 결과
  반환"과 [개발 가이드 §6.2](../references/05_Domain_Development_Blueprint.md)의 "`(terminalId, clientOrderId)` unique가 마지막 방어선"을
  Facade와 `V002` 마이그레이션으로 옮겼다.
- 결정: `platform` 모듈을 만들어 `IdentifierGenerator`·`Clock` 빈을 둔다(개발 가이드 §1의 자리). `OrderCreated`는 plain Spring
  이벤트로 발행하고 EPR은 첫 리스너 PR로 미룬다. `Order.restore`는 저장된 주문 재구성 전용이며 불변식을 다시 묻지 않는다.
  주문 시각 컬럼은 `sync` intake가 envelope의 `occurredAt`을 넘기는 PR에서 Command와 함께 추가한다.
- 리뷰 반영: `OrderCommandFacade`를 인터페이스 없는 구체 클래스로 바꿨다(`Default*` 구현 제거). JPA 엔티티 이름에서 `Jpa`를 뺀다.
  `platform.BaseEntity`는 Hibernate `@CreationTimestamp`·`@UpdateTimestamp`·`@SoftDelete(TIMESTAMP)`로 만들고 활성 상태 컬럼은 두지 않는다.
  JPA 연관관계 매핑과 `@ElementCollection`을 쓰지 않는다. line은 `OrderLineId`를 가진 독립 엔티티·독립 repository이고 adapter가 조립한다. DB FK 제약도 두지 않는다.
  `@Version`을 `Long?`로 바꿔 `BaseEntity`에 두어 신규 저장이 `merge`로 빠져 SELECT·UPDATE가 붙던 결함을 고쳤다.
- 갱신: [현재 상태](current-state.md), [도메인 모델](domain-model.md) §4.2·§4.4.

## 2026-09-19 — 볼트 지침 중복 제거와 세션 컨텍스트 축소
- Spec: [vault-context-trim](../spec/2026-09-19-vault-context-trim.md)
- 요구사항 영향: 제품 요구사항은 변경하지 않았다. 볼트 지침이 매 세션 싣는 분량을 줄였다(항상 로드 rule 10.0KB → 4.8KB, 긴 description 3개 축소, 사용자 전용 스킬 2개 description 제외).
- 결정: 본문이 같던 `outline-*` 에이전트 3개는 `architect-*`로 합쳤다. `develop-auto`는 `develop` references를 정본으로 삼아 차이만 적는다. 윤문 스킬은 `resilientpos-humanizer` 하나만 쓴다. 이식 원본 `Global-claude-code`는 볼트 `ResilientPOS/archive/`에 보관한다.
- 갱신: [지침 설계 근거](agent-instructions.md), [차용 내역](borrowed-workflows.md).

## 2026-09-19 — 공통 API 응답 envelope와 전역 예외 advice
- Spec: [api-response-advice](../spec/2026-09-19-api-response-advice.md)
- 요구사항 영향: 제품 요구사항은 변경하지 않았다. 첫 Controller PR보다 먼저 HTTP 응답 모양(`result`·`data`·`error{errorCode, message}`)을 정했다. 구조는 ppossilaegi 프로젝트의 전역 API 클래스를 옮겨 온 것이다.
- 결정: `platform` 기본 패키지에 둔다(`foundation`은 `HttpStatus`에 의존할 수 없다). 원본에서 `CoreException`과 도메인별 `ErrorType` 항목은 옮기지 않았다. 도메인 규칙 위반은 `foundation-domain-exception` PR의 `DomainException`이 맡는다. `ErrorKind`는 `HttpStatus`, `ErrorLevel`은 slf4j `Level`로 대체했다. Swagger `@Schema`, validation·인증 handler는 해당 의존성이 없어 뺐다.
- 결정: `ApiControllerAdvice`에는 `basePackages`를 걸지 않는다. handler가 정해지기 전에 나는 404·405는 `basePackages`가 걸린 advice에 닿지 않는다. 본문을 감싸는 `ApiResponseBodyAdvice`에만 걸어 Actuator 응답을 그대로 둔다. 응답 메시지는 `ErrorType` 고정 문구만 쓰고 예외 메시지는 로그에만 남긴다.

## 2026-09-19 — foundation 도메인 예외 DomainException
- Spec: [foundation-domain-exception](../spec/2026-09-19-foundation-domain-exception.md)
- 요구사항 영향: 제품 요구사항은 변경하지 않았다. 도메인 규칙 위반을 알리는 예외 타입을 `IllegalArgumentException`에서 `foundation.DomainException(code, message)`으로 바꿨다. 검증 조건과 코드 값 5개는 그대로다.
- 결정: `IllegalArgumentException`은 JDK와 라이브러리도 던지므로 web advice가 이것을 400으로 바꾸면 버그까지 클라이언트 오류가 된다. 전용 타입만 400으로 바꾸고 나머지는 500으로 둔다. 사람이 읽는 `message`는 규칙을 던지는 자리에 한국어로 적는다. 코드별 하위 타입, 오류 코드 enum, `MessageSource`는 만들지 않는다.
- 범위 조정: 개발 도중 #22 api-response-advice가 main에 머지되어 rebase했다. 응답 계약은 #22를 따른다(`ApiResponse`의 `error{errorCode, message}`, 형식 오류는 `BAD_REQUEST`). 두 타입이 한 브랜치에 모였으므로 `ApiControllerAdvice`의 `DomainException` → 400 handler를 이 PR에 넣었다. `DomainException`의 `message`는 응답용 문구라서 그대로 나가고, 그 밖의 예외는 고정 문구만 나간다.
- 범위 조정: `DomainErrorKind`(`INVALID`·`NOT_FOUND`·`CONFLICT`·`UNPROCESSABLE`)를 추가했다. 상태 전이 위반이나 멱등 충돌은 요청이 올바르고 대상의 현재 상태와 충돌하는 것이라 409가 맞고, 단말은 400(재시도 무의미)과 409(최신 상태를 다시 읽는다)를 다르게 처리해야 한다. 예외에 HTTP 숫자를 담지 않고 업무 의미만 담아 advice가 상태로 바꾼다. sync intake처럼 HTTP가 아닌 소비자도 같은 `kind`를 해석할 수 있다. 새 4xx는 enum 항목 하나와 `when` 분기 한 줄로 더한다. `INVALID`가 아닌 `kind`를 던지는 도메인 코드는 아직 없다.
- 합의된 후속 계약(다음 PR `order-create-order-api`): 같은 `(terminalId, clientOrderId)` 재시도는 항상 201과 같은 본문, `storeId`·`terminalId`는 요청 본문으로 받는다. 같은 키에 다른 payload가 오는 경우의 `409 IDEMPOTENCY_CONFLICT`는 그 뒤 PR `order-idempotency-conflict`가 맡는다.
- 갱신: [현재 상태](current-state.md).

## 2026-09-20 — 주문 생성 REST API POST /v1/orders
- Spec: [order-create-order-api](../spec/2026-09-20-order-create-order-api.md)
- 요구사항 영향: 첫 공개 endpoint `POST /v1/orders`를 추가했다. [시스템 설계 §3.1](../references/02_System_Design.md)이 나열한 endpoint 중 하나이며 제품 요구사항 자체는 바꾸지 않았다.
- 결정: 같은 `(terminalId, clientOrderId)` 재시도는 항상 201과 같은 본문이다. 응답을 잃은 단말이 원래 응답을 그대로 받고 `CreateOrderResult`를 고치지 않아도 된다. `terminal` 인증이 없어서 `storeId`·`terminalId`는 요청 본문으로 받는다.
- 결정: Bean Validation을 쓰지 않는다. 형식 오류는 타입이 있는 DTO의 Jackson 역직렬화가 거절해 `400 BAD_REQUEST`가 되고, 업무 규칙은 도메인의 `DomainException`이 거절한다. 같은 규칙을 DTO와 도메인 두 곳에 두면 같은 위반에 계층마다 다른 `errorCode`가 나간다.
- 결정: REST Docs는 스니펫 생성까지만 한다. Asciidoctor HTML 빌드는 문서를 내보낼 곳이 생기면 추가한다.
- 알려진 빈틈: 같은 키에 다른 payload가 와도 기존 결과와 201을 돌려준다(`order-idempotency-conflict`). 빈 `productCode`·`productName`을 거절하지 않는다(후속 도메인 규칙 PR).
- 갱신: [현재 상태](current-state.md), [AGENTS.md](../../AGENTS.md).

## 2026-09-20 — AGENTS.md를 현재 빌드 상태에 맞게 갱신
- Spec: [agents-md-currency](../spec/2026-09-20-agents-md-currency.md)
- 요구사항 영향: 제품 요구사항과 설계는 변경하지 않았다. 이미 머지된 PR들이 만든 사실(H2 제거와 PostgreSQL 전용, Flyway 스키마 소유, Spring Modulith 2.1.1 도입, REST Docs·Testcontainers·ArchUnit·Fixture Monkey)을 매 세션 로드되는 인덱스 문서에 옮겨 적었다. [현재 상태](current-state.md)는 이미 같은 사실을 담고 있어 갱신하지 않았다.
- 결정: 틀린 문장을 고치는 데서 멈추지 않고 반복 비용이 큰 규약 두 가지를 한 줄씩 남겼다. 도메인 규칙 위반은 `foundation.DomainException(code, message, kind)`으로 던지고, Controller는 DTO만 돌려주며 envelope와 오류 변환은 `platform`의 advice가 맡는다. 두 규약은 #22·#23이 정했지만 AGENTS.md에는 없어 세션마다 코드를 다시 읽어야 했다.
- 결정: 함정 두 가지를 검증 명령 옆에 적었다. `./gradlew test`는 Testcontainers 때문에 Docker가 필요하고, `check_pr.py`는 spec 필수 절에 부등호 여는 문자가 있으면 미작성으로 판정한다. 판정 근거는 명령 결과만 보고는 알기 어렵다.
- 결정: [지침 설계 근거](agent-instructions.md)는 갱신하지 않았다. 그 문서는 AGENTS.md의 배치와 우선순위 근거를 다루고 기술 스택 사실이나 코드 규약을 담지 않는다.
- 범위 조정: "개발 계약" 절에 main 기준 새 작업을 Orca CLI로 만든 새 worktree·새 Claude 세션에 넘긴다는 규칙을 한 줄 더했다. 이 PR이 근거다. 처음 받은 전제는 PR #24가 열려 있다는 것이었으나 실제로는 이미 머지되어 있었고, 그 차이로 기술 스택 요약에 REST Docs를 적을지가 뒤집혔다. 세션을 이어 쓰면 브랜치가 만들어진 시점의 main을 계속 전제하게 되므로, 새 세션이 origin/main을 처음부터 다시 읽게 하고 넘기기 직전 `git fetch`로 한 번 더 확인한다. 진행 중인 브랜치의 후속 작업과 설명·조회 요청은 제외한다.
- 범위 조정: 루트 `CLAUDE.md`를 지우고 프로젝트 지침 정본을 `AGENTS.md` 하나로 합쳤다. Claude Code 2.1.277부터 `CLAUDE.md`가 없으면 `AGENTS.md`를 프로젝트 지침으로 읽고 하위 디렉터리의 `AGENTS.md`도 그 위치의 지침이 되므로, 제목 한 줄과 `@AGENTS.md` import 한 줄뿐이던 스텁을 유지할 이유가 없다([출처](https://news.hada.io/topic?id=33925), 이 worktree의 Claude Code는 2.1.278이다). 남는 위험은 2.1.277 미만 버전과 `/config`의 Project instructions를 CLAUDE.md 전용으로 바꾼 환경에서 지침이 실리지 않는 것이라 최소 버전을 AGENTS.md에 적었다.
- 갱신: [AGENTS.md](../../AGENTS.md), [지침 설계 근거](agent-instructions.md).

## 2026-09-20 — 핸드오프 worktree의 브랜치를 작업 유형 prefix로 만들기
- Spec: [handoff-branch-type-prefix](../spec/2026-09-20-handoff-branch-type-prefix.md)
- 요구사항 영향: 제품 요구사항과 설계는 변경하지 않았다. 개발 절차 중 핸드오프 worktree의 브랜치 이름을 정하는 단계만 바뀐다. Orca는 앱 설정(`branchPrefix = git-username`) 때문에 브랜치를 `char-yb/작업이름`으로 만들어 git 스킬의 브랜치 명명 표에서 벗어났다. 이제 넘기는 세션이 작업 프롬프트에서 유형을 추론해 create 직후 push 전에 `git branch -m 유형/작업이름`으로 바꾼다.
- 결정: Orca 앱 설정은 바꾸지 않았다. prefix를 없애도 유형 prefix를 붙이려면 이름 변경이 어차피 필요하다. `--name`에 슬래시를 넣으면 대시로 바뀌는 것을 2026-09-20에 시험으로 확인했고, CLI에는 브랜치 이름을 지정하는 옵션이 없다.
- 결정: 이미 push되어 PR이 열린 브랜치는 이름을 바꾸지 않는다. 원격 브랜치 이름을 바꾸면 열린 PR이 닫히므로 #26과 #27의 `char-yb/` 브랜치는 그대로 두었다.
- 갱신: [AGENTS.md](../../AGENTS.md), [개발 규칙](development-workflow.md)의 "개발 시작" 2번 단계.

## 2026-09-20 — 같은 주문 생성 키에 다른 payload가 오면 409 IDEMPOTENCY_CONFLICT
- Spec: [order-idempotency-conflict](../spec/2026-09-20-order-idempotency-conflict.md)
- 요구사항 영향: `POST /v1/orders`에 409 `IDEMPOTENCY_CONFLICT` 응답이 생겼다. 같은 `(terminalId, clientOrderId)`에 payload가 같은 재시도는 계속 201과 기존 결과를 받고, payload가 다르면 `DomainException(IDEMPOTENCY_CONFLICT, kind=CONFLICT)`이 나간다. #24가 알려진 빈틈으로 남긴 항목이며 [시스템 설계 3.1절](../references/02_System_Design.md)의 "동일 key에 다른 payload면 409"를 주문 생성에 적용했다. 요청·응답 모양과 `CreateOrderCommand`·`CreateOrderResult`는 바뀌지 않았다.
- 결정: 비교 대상은 `storeId`, `currency`, line의 `productCode`·`productName`·`unitPrice`(금액과 통화)·`quantity`다. 기준은 HTTP 본문이 아니라 `CreateOrderCommand`의 값이라서 JSON 표기 차이가 영향을 주지 않고 `sync`가 같은 Facade를 불러도 같은 판정을 받는다. 금액은 `Money`의 canonical scale 덕분에 값 동등성으로 비교하고, line은 값의 개수 묶음으로 비교해 순서를 무시한다. 순서만 다른 재시도를 충돌로 판정하면 단말의 재시도가 영원히 막히고, 같은 요청으로 판정하면 내용이 같은 주문을 돌려줄 뿐이라서 순서를 무시하는 쪽을 택했다.
- 결정: 판정은 `Order.verifySamePayload`가 맡고 `OrderCommandFacade`는 호출 한 줄만 늘었다. 같은 키의 요청이 최초 요청과 같은지는 업무 규칙이고 Facade는 aggregate rule을 구현하지 않는다. `platform`은 고치지 않았다. `CONFLICT`의 409 매핑은 #23에 이미 있고 이 코드가 그 첫 사용처다.
- 범위 조정: 처음 구현은 최초 요청의 SHA-256 hash를 `t_order.payload_hash`(`V003`)에 저장했다. PR #26 리뷰에서 컬럼 없이도 검증할 수 있다는 지적이 나와 저장된 주문과의 직접 비교로 바꾸고 V003을 없앴다. 대상 필드가 이미 `t_order`·`t_order_line`에 있어서 hash는 중복 저장이었다. "line 변경 명령이 생긴 뒤에는 backfill할 수 없다"는 처음 근거도 틀렸다. 그 명령이 생기기 전까지는 현재 상태가 최초 요청과 같으므로 그 PR에서 채울 수 있다. 다시 볼 조건은 line·`storeId`·`currency`를 바꾸는 명령이 생기는 PR이다.
- 지침: 같은 실수를 막으려고 [도메인 모델](domain-model.md)에 "스키마 변경 검토" 절을 새로 두고 [AGENTS.md](../../AGENTS.md)에 한 줄로 가리켰다. 컬럼·테이블·제약·인덱스를 더하거나 빼는 PR은 spec의 `### 스키마 변경 검토` 절에서 세 관점의 질문에 답한다. 필요성은 기존 데이터로 도출·비교할 수 있는가, 지금 읽는 코드가 있는가, 나중에 채울 수 있는가다. 나중에 채울 수 있는데 기존 데이터로 판단할 수 있거나 읽는 코드가 없으면 추가하지 않는다.
- 범위 조정 2: 이후 Redis와 Kafka가 들어오고 정합성과 락을 염두에 두고 개발할 예정이라서 검토를 정합성과 반영 관점으로 넓혔다. 정합성은 unique 제약·`@Version`·멱등 키·상태·snapshot처럼 불변식의 마지막 방어선인 값을 Redis의 값이나 Kafka의 이벤트로 대신하지 않는 것, 정본이 하나인 것, check-then-act 경합을 DB가 막는 것이다. 반영은 DDL이 잡는 락과 이전 버전 애플리케이션과의 호환(expand에서 정리까지의 순서)이다. 새 원칙을 정한 것이 아니라 README의 데이터 정본 원칙, 청사진 6.2절·7.1절, 개발 가이드 2.5절을 스키마를 바꾸는 시점의 질문으로 모았다.
- 결정: 답은 구현자가 스스로 승인하지 않고 리뷰어가 실제 DDL·엔티티·코드와 대조한다. 이 PR의 `payload_hash`는 구현자가 설계하고 스스로 통과시킨 것이 원인이었다.
- 범위 조정 3: 사람이 지켜야만 동작하는 절차는 빠뜨리기 쉬워서 `check_pr.py`가 검사하게 했다. 처음에는 후속 하네스 PR로 미뤘으나 사용자가 이 PR에 넣도록 결정했다. `db/migration` 아래 `.sql`을 더하거나 고치거나 지우는 PR은 spec에 `### 스키마 변경 검토` 절이 있어야 하고, 그 절에 `- 대조 기록:` 줄이 없으면 `--worktree`에서는 경고, 제출 전 이력 검사에서는 실패다. 독립 대조는 PR 제출 직전에 하므로 개발 중 검사를 막지 않는다. `R` 접두의 반복 마이그레이션도 대상이다. `--migrations-only`와 마이그레이션이 없는 PR은 영향받지 않는다. 검사기는 빠뜨림만 막고 답의 품질과 대조의 진위는 PR 리뷰가 본다.
- 검토: 하네스 변경은 구현자가 아닌 에이전트가 임시 저장소에서 우회를 실제로 시도했다. 첫 구현은 Flyway가 읽는 `.SQL`·하위 디렉터리·Java·Kotlin 마이그레이션을 놓쳤고, `docs/` 아래 예시 SQL을 잘못 잡았으며, 빈 대조 기록과 코드 블록 안의 제목·기록을 인정했고, 템플릿 안내문만 있어도 통과했다. 모두 테스트로 고정하고 고쳤다. 템플릿의 절은 필수 절 밖의 독립 `##` 절로 옮겨 placeholder를 두었다. 스키마를 바꾸지 않는 PR에는 마찰이 없다.
- 알려진 빈틈(하네스): 버전 중복 검사의 `MIGRATION` 정규식은 이 PR 이전부터 하위 디렉터리와 대문자 확장자를 보지 못한다. PR 워크플로가 자동으로 막는 유일한 검사라서 후속 하네스 PR에서 고친다. `spring.flyway.locations` 변경과 패키지 밖 `JavaMigration` 빈은 경로로 알 수 없다.
- 검토: 이 절은 구현자가 아닌 두 검토를 거쳤다. `workflow-reviewer`는 필요성 판정이 질문 4보다 먼저 읽혀 `@Version` 같은 값을 빼게 만들 수 있다는 점, 의도적인 사본(캐시·read model)의 허용 여부가 없다는 점, 리뷰 절차에 시점·주체·증거가 없다는 점을 지적했다. 공식 문서 대조에서는 기술 서술 8개 중 6개를 고쳤다. 기준은 "상수 기본값"이 아니라 non-volatile이고, `SET NOT NULL`·타입 변경·unique 제약 추가는 쓰기뿐 아니라 읽기도 막으며, `CREATE INDEX CONCURRENTLY`는 Flyway의 `transactional-lock` 기본값과 부딪히고 실패하면 INVALID 인덱스가 남는다. 컬럼 삭제는 이전 버전의 기동뿐 아니라 떠 있는 인스턴스의 쿼리도 깨고, Kafka가 정본이 될 수 없는 이유는 유실이 아니라 PostgreSQL 트랜잭션과 함께 commit되지 않는다는 점이다. soft delete와 unique 제약의 상호작용, 기존 행의 제약 위반, `@Version`이 그 행만 지킨다는 점을 질문에 더했다.
- 알려진 빈틈: 같은 키의 두 요청이 동시에 도착하면 unique 제약이 한쪽을 거절한다. 주문을 soft delete하면 같은 키의 재시도가 삭제된 주문을 찾지 못하고 unique 제약에 걸린다(지금은 주문을 삭제하는 코드가 없다). 빈 `productCode`·`productName`을 거절하지 않는다(다음 PR).
- 갱신: [현재 상태](current-state.md), [도메인 모델](domain-model.md), [개발 규칙](development-workflow.md), [AGENTS.md](../../AGENTS.md), spec 템플릿, `scripts/check_pr.py`.

## 2026-09-20 — PR 템플릿을 의사결정 문서 형태로 개편
- Spec: [pr-template-decision-doc](../spec/2026-09-20-pr-template-decision-doc.md)
- 요구사항 영향: 제품 요구사항과 설계는 변경하지 않았다. 개발 절차 중 PR 본문 작성 규칙만 바뀐다. PR 본문이 spec 링크와 체크박스에서 의사결정 문서 8절(왜 / 무엇을·어떻게 / 한계와 트레이드오프 / 기존 기능 영향 / Edge case와 실패 시나리오 / 검토한 대안 / 리뷰 포인트 / 확인 방법)로 늘어난다. 참고한 글은 카카오페이손해보험 기술 블로그의 [PR을 더 느리게 만들기 위한 고민](https://tech.kakaopay.com/post/kakaopayins-slow-pr-fast-dev/)이다.
- 결정: 기존 템플릿의 체크박스 다섯 개는 "확인 방법" 절로 옮겨 그대로 유지했다. "위험 / 후속 PR" 절은 없애고 "한계와 트레이드오프"가 흡수했다. communicate 스킬의 네 가지 질문은 새 템플릿의 골격으로 남는다.
- 결정: 해당 사항이 없는 절은 `해당 없음: 근거` 한 줄로 채우도록 허용했다. 절을 지우게 두면 "검토했는데 없음"과 "검토하지 않음"을 구분할 수 없고, 빈 절을 강제하면 문서 전용 PR의 부담이 커진다.
- 결정: 작성 가이드와 Risk 판정 기준(🔴/🟡/🟢)은 템플릿의 HTML 주석에 넣었다. git 스킬이 PR 생성 전에 템플릿을 먼저 읽고 그 구조를 따르므로 볼트의 스킬은 수정하지 않았다.
- 결정: AI 리뷰 봇(GitHub Action)은 도입하지 않았다. 최근 PR은 작성자가 한 명이고 기록된 리뷰가 0건이라 봇이 줄여 줄 대기 시간이 없다. 대신 리뷰 포인트에 🔴 항목이 있는 PR만 제출 전에 `resilientpos-multi-review`를 실행한다. 리뷰 코멘트 심각도 태그도 도입하지 않았다. `resilientpos-review` 스킬이 이미 Critical / Important / Nice-to-have로 표기한다.
- 기존 불일치 해소: [개발 규칙](development-workflow.md)은 PR 본문을 "4단"이라고 적었지만 실제 템플릿은 그 구조가 아니었다. 이번에 두 문서를 맞췄다.
- 범위 조정: PR을 올린 뒤에 실행한 독립 에이전트 리뷰(Spring 백엔드 스페셜리스트 관점)의 지적 중 여섯 건을 반영했다. Risk 기준에 diff로 확인하는 🔴 사실 기준을 두고 🟢의 리네임·메서드 추출에 단서를 붙였다. 조건부 multi-review 체크박스는 실행 여부를 적는 서술 한 줄로 바꿨다. "사람의 리뷰가 필요하다" 체크박스에는 머지 보류와 사용자 알림을 연결했다. 볼트 스킬의 "4단 고정" 선언과 충돌한다는 사실과 템플릿 우선 원칙을 개발 규칙에 적었다. 규칙을 강제하는 장치가 없다는 점을 사실대로 적었다. 템플릿 파일명을 볼트 git 스킬이 읽는 표기인 `PULL_REQUEST_TEMPLATE.md`로 바꿨다. 근거와 이번에 고치지 않은 항목은 spec의 범위 조정에 있다.
- 범위 조정: 🔴로 다시 판정한 뒤 규칙대로 multi-review를 실행했다(Claude와 Codex, Gemini는 CLI가 없어 미실행). 두 리뷰어가 합의한 지적을 반영해서 🔴 사실 기준을 `src/main/resources/**`, JPA·Hibernate 매핑 어노테이션이나 `@Transactional`이 있는 파일, 검사 하네스(`.github/workflows/**`, `scripts/check_pr.py`), 응답 본문에 값으로 나가는 상수로 넓혔다. 통합 테스트가 datasource를 주입받아 읽지 않는 설정, `@Entity`가 아닌 매핑(`@MappedSuperclass`, `@SoftDelete`), 실패하는 테스트가 없는 하네스 변경이 기존 기준에서 빠져 있었다. 개발 규칙에 복제했던 기준 목록은 지우고 템플릿의 주석을 정본으로 가리키게 했다. 영속화 어댑터 경로 기준과 `build.gradle.kts`는 넣지 않았고 이유는 spec에 있다.
- 갱신: [개발 규칙](development-workflow.md)의 "PR 본문" 절, [차용 내역](borrowed-workflows.md)의 계약 조정 표, `.github/PULL_REQUEST_TEMPLATE.md`.

## 2026-09-20 — 새 에이전트 spring-backend-specialist를 wiki에 등록
- Spec: [register-spring-backend-specialist](../spec/2026-09-20-register-spring-backend-specialist.md)
- 요구사항 영향: 제품 요구사항과 설계는 변경하지 않았다. 개발 도구인 리뷰 에이전트가 하나 늘었다. 볼트 `ResilientPOS/agents/spring-backend-specialist.md`는 Kotlin/Spring Boot 백엔드 변경을 운영 장애·데이터 손상·보안 사고의 관점에서 열세 개 렌즈(정합성과 트랜잭션, 멱등성, 이벤트 전달, 오프라인 동기화와 시각, 결제 연동, JPA와 Kotlin, PostgreSQL과 Flyway 운영, 응답 속도, API 하위 호환, 보안, 관측성, 모듈 경계, AOP 리팩토링 여지)로 점검해 P0~P4로 보고한다. 이식분이 아니라 이 프로젝트에서 새로 만든 에이전트이고, 웹 조사와 코드베이스 전체를 대상으로 한 시험 실행 1회, `workflow-reviewer`의 검토를 거쳐 정의를 다듬었다.
- 결정: 모델은 `opus`다. [지침 설계 근거](agent-instructions.md#에이전트-모델-배정과-스킬-선행-규칙)의 배정표에서 `opus` 행의 기준("판단 오류의 대가가 큰 검토·설계·게이트")에 그대로 해당해서 기준 문구는 바꾸지 않고 목록에만 더했다.
- 결정: 구현 코드 검토의 기본은 계속 `code-reviewer`·`resilientpos-review`다. `spring-backend-specialist`는 같은 운영 리스크를 더 깊게 보지만 사용자가 명시적으로 요청할 때만 쓰고, 그런 요청이 없으면 `code-reviewer`가 "잠재적 문제" 항목에서 계속 본다. 두 에이전트의 description이 같은 요청에 경합할 수 있어서 선행 규칙에 이 문장을 더했다.
- 결정: 렌즈는 2026-09-20의 웹 조사에 근거한다. 출처는 Spring·Hibernate·PostgreSQL·OWASP 공식 문서와 Stripe·토스페이먼츠·Airbnb·카카오페이의 기술 블로그 등이고, 목록은 에이전트 정의의 "렌즈의 근거" 절에 있다. 버전별 동작은 기억으로 단정하지 않고 "미확인"으로 적게 했다.
- 결정: 읽기 전용은 본문 서술이 아니라 도구 목록으로 보장한다. `tools`를 `Read, Glob, Grep`으로 두고 Bash를 뺐다. 본문에 "수정하지 않는다"고 적는 것만으로는 셸을 통한 쓰기를 막지 못한다. 대가로 에이전트가 diff를 직접 만들지 못하므로 diff와 빌드·테스트 결과는 호출자가 전달한다.
- 결정: description을 명시 호출 전용("명시적으로 요청할 때 사용")으로 좁혔다. PR 템플릿이나 multi-review 절차에 이 에이전트를 부르는 경로가 없고 이 PR에서도 추가하지 않았다. 자동 발동을 허용하면 일반 코드 검토 요청에서 `code-reviewer`와 경합한다.
- 볼트 수정: 리뷰 에이전트들은 서로를 열거해 역할 경계를 양방향으로 고정한다. 새 에이전트의 정의는 이미 `code-reviewer`를 열거하므로, 볼트 `ResilientPOS/agents/code-reviewer.md`의 "역할 겹침:" 문단에 "`spring-backend-specialist`는 사용자가 명시적으로 요청할 때만 쓰는 별도 에이전트로, 백엔드 운영 리스크(정합성, 멱등성, 응답 속도, 보안 등)를 깊게 봅니다."를 더했다. 볼트는 git으로 관리되지 않아 저장소 diff에 나타나지 않는다. 볼트 `ResilientPOS/README.md`의 "에이전트" 표에도 `spring-backend-specialist` 행(모델 `opus`, 명시 요청 시에만 사용)을 더했다. 그 표는 볼트에 있는 에이전트의 현재 목록이라서 빠뜨리면 wiki의 배정표와 어긋난다. 수정 전 사본은 `~/Documents/code-reviewer.md.2026-09-20.before-spring-backend-specialist.bak`과 `~/Documents/vault-README.md.2026-09-20.before-spring-backend-specialist.bak`에 있다.
- 범위 밖: [차용한 워크플로우](borrowed-workflows.md)의 "도입한 에이전트 (16)와 규칙 (3)" 절은 2026-09-13 전체 이식의 이력이라서 숫자와 목록을 고치지 않았다. 에이전트 총수를 현재 사실로 단정하는 문장이 저장소의 wiki·`AGENTS.md`·`README.md`에 없다는 것은 grep으로 확인했다. `AGENTS.md`는 "전체 스킬·에이전트 목록과 모델 배정"이 차용 내역과 지침 설계 근거에 있다고 두 문서를 함께 가리키고, 배정표가 새 에이전트를 포함하므로 그대로 두었다. Codex용 미러(`codex/agents/*.toml`)는 만들지 않았다. 시험 실행에서 나온 코드 결함(입력 길이 검증 부재, 단말 발생 시각 계약, 멱등 경합 복구 등)은 이 PR에서 다루지 않는다.
- 검토: wiki 커밋 뒤에 `workflow-reviewer`가 읽기 전용으로 검토했고 판정은 WARN이었다. 지적 네 건 중 두 건을 반영했다. 볼트 README의 표에 새 에이전트가 빠져 있었고(처음의 grep이 저장소만 대상으로 했다), 선행 규칙의 처음 문구 "`spring-backend-specialist`가 맡되"는 점검의 소유가 `code-reviewer`에서 옮겨 간 것으로 읽혔다. 나머지 두 건은 고치지 않았다. 볼트 `reviewer-claude.md`의 "역할 겹침:" 문단은 `reviewer-claude`가 multi-review 안에서만 호출되는 내부용 에이전트라서 새 에이전트와 경합하는 경로가 없고, 그 경로를 추가하는 PR에서 양방향 열거를 맞춘다. `AGENTS.md`의 목록 참조는 위에 적은 이유로 여전히 참이다.
- 갱신: [지침 설계 근거](agent-instructions.md).

## 2026-09-21 — 빈 상품 코드·상품명을 가진 주문 line 거절
- Spec: [order-line-blank-product](../spec/2026-09-21-order-line-blank-product.md)
- 요구사항 영향: `POST /v1/orders`의 입력 규칙이 하나 늘었다. `productCode`나 `productName`이 빈 문자열이거나 공백뿐인 line은 `OrderLine`이 `BLANK_PRODUCT_CODE`·`BLANK_PRODUCT_NAME`(`DomainErrorKind.INVALID`, 400)으로 거절한다. 요청·응답의 모양과 스키마는 바뀌지 않았다. [현재 상태](current-state.md)의 알려진 빈틈 하나를 닫았다.
- 결정: 판정은 `isBlank()`이고 값은 다듬지 않는다. trim을 넣으면 `verifySamePayload`의 같은 payload 판정이 함께 바뀌므로 따로 다룬다.
- 결정: DB `check` 제약은 두지 않았다. 방어선은 도메인 생성자이고 `OrderLine`을 거치지 않고 line을 쓰는 경로가 없다.
- 발견: `productCode` 64자·`productName` 255자를 넘는 입력은 DB가 거절해 `500 INTERNAL_ERROR`가 된다. 임시 테스트로 65자 `productCode`의 응답을 확인했고 알려진 빈틈에 적었다. 상한 검증은 후속 PR이다.
- 검토: 제출 전 multi-review(Claude 86점, Codex 92점, Gemini는 CLI가 없어 미실행)를 실행했다. 두 리뷰어가 함께 지적한 것을 반영했다. `Order.restore`의 KDoc이 line 불변식의 재실행을 빠뜨렸고, 이미 주문이 있는 키로 blank line이 오면 400이 아니라 `409 IDEMPOTENCY_CONFLICT`라는 순서가 기록에 없었다. 한 리뷰어만 지적한 것(오류 응답에 line 위치 싣기, endpoint별 오류 코드 표, `BLANK_PRODUCT_NAME`의 HTTP 경로 테스트)은 반영하지 않고 PR 본문에 남겼다.
- 갱신: [현재 상태](current-state.md).

## 2026-09-21 — Order의 RequestCheckout 전이와 OrderPaymentRequested
- Spec: [order-request-checkout-domain](../spec/2026-09-21-order-request-checkout-domain.md)
- 요구사항 영향: 주문 lifecycle에 `CHECKOUT_REQUESTED`가 생겼다. `Order`가 결제를 요청하는 시점의 합계를 회차별 `Checkout`으로 확정하고 `OrderPaymentRequested`를 만든다. 도메인만 있고 Facade·`t_order_checkout`·endpoint는 다음 두 PR이다. API와 스키마는 바뀌지 않았다.
- 결정: store 정책, `CheckoutPolicy`, 정책 버전을 미뤘다. 반올림할 계산이 없어서 읽는 코드가 없는 값이 된다([도메인 모델](domain-model.md) 4.7절 질문 2·3). 그래서 전이 인자가 처음 설계의 `policy`가 아니라 `(ids, now)`다.
- 결정: 이미 요청된 주문의 재요청은 같은 주문과 빈 이벤트를 돌려준다. 요청 키는 두지 않았다. 결제 실패 뒤 재-checkout(2회차)을 넣는 PR이 늦게 도착한 재시도 문제를 다시 본다.
- 결정: 0원 주문을 거절하지 않는다. 전액 할인·프로모션 주문의 고객 경험이 먼저이고 주문·결제 기록이 둘 다 남아야 조회가 쉽다. provider 호출을 건너뛰는 판단은 `payment`가 맡는다.
- 결정: `OPEN`·`CHECKOUT_REQUESTED` 밖의 상태가 없어서 `INVALID_ORDER_STATE` 거절은 넣지 않았다. 분기가 else 없는 `when`이라 상태를 더하는 PR이 컴파일 오류로 이 전이의 처리를 함께 정한다.
- 검토: 제출 전 multi-review(Claude 84점, Codex 93점, Gemini는 CLI가 없어 미실행)를 실행했고 둘 다 막는 결함은 없다고 판정했다. 재요청이 같은 인스턴스를 돌려주는지와 소수 자리 통화의 확정 금액을 테스트로 고정했다. `(orderId, checkoutNo)`는 `payment`의 attempt 키가 아니라 같은 checkout의 승인 attempt 중복을 막는 근거라고 문장을 좁혔다. 동시 요청에서 진 쪽의 응답, checkout 왕복 복원, `t_order_checkout`의 키는 영속화 PR이 정할 것으로 spec의 분할 계획에 옮겼다.
- 볼트 수정: 볼트 `ResilientPOS/rules/domain-modeling-kotlin.md` 2절을 이 PR의 구현에 맞췄다. 예시가 `check`로 시작하는 전이, 단수 `checkout`, `requestCheckout(policy)`였다. `requestCheckout(ids, now)`, 회차 목록 `checkouts`, else 없는 `when`으로 바꾸고 규칙 두 줄을 더했다. 거절은 `check`·`require`가 아니라 `DomainException`으로 던진다는 것(`AGENTS.md`와 같은 내용)과, 재시도가 올 수 있는 전이는 이미 전이된 상태에서 `Transition(this, emptyList())`를 돌려준다는 것(멱등 전이)이다. 같은 절 5절의 "자식도 도메인 ID를 가진다"는 고치지 않았다. 식별자 없는 `Checkout`을 예외로 둘지는 영속화 PR이 정한다. 볼트는 git으로 관리되지 않아 저장소 diff에 나타나지 않는다. 수정 전 사본은 `~/Documents/domain-modeling-kotlin.md.2026-09-21.before-request-checkout.bak`에 있다.
- 갱신: [현재 상태](current-state.md), [도메인 모델](domain-model.md).

## 2026-09-21 — RequestCheckout의 Facade와 t_order_checkout 영속화
- Spec: [order-request-checkout-persistence](../spec/2026-09-21-order-request-checkout-persistence.md)
- 요구사항 영향: checkout 요청을 서버가 저장할 수 있게 됐다. `OrderCommandFacade.requestCheckout`이 상태 전이와 회차별 확정 금액(`t_order_checkout`)을 한 트랜잭션으로 저장하고 `OrderPaymentRequested`를 발행한다. endpoint는 다음 PR이라 HTTP API는 바뀌지 않았다. 스키마에 `t_order_checkout`이 늘었다.
- 결정: `Checkout`이 도메인 ID `CheckoutId`를 갖고 PK는 `id` 하나다. `@EmbeddedId`와 복합 PK를 쓰지 않는다. 회차 중복은 unique `(order_id, checkout_no)`가 막는다.
- 결정: 같은 주문에 겹친 요청은 주문 행 잠금으로 직렬화한다. 뒤 요청은 앞 커밋을 기다린 뒤 기존 결과를 받는다. 예외를 409로 바꾸는 안과 새 트랜잭션에서 다시 읽는 안은 버렸다. 뒤의 것은 `sync`가 바깥 트랜잭션을 소유하면 동작하지 않는다.
- 결정: `POST /v1/orders` 재시도는 주문의 현재 `status`를 돌려준다. checkout을 요청한 주문이면 `CHECKOUT_REQUESTED`다. "재시도도 같은 본문"이라는 이전 서술을 고쳤다.
- 결정: 통화 컬럼을 두지 않았다. 잠금 대기 시간 제한도 두지 않았다. 주문 트랜잭션 안에서 provider 호출 같은 긴 작업을 하지 않는다는 원칙이 전제다.
- 발견: 잠금이 없으면 뒤 요청이 기다리지 않고 먼저 커밋한다. 앞 트랜잭션은 flush 전이라 DB에 아무것도 쓰지 않은 상태이기 때문이다. 통합 테스트가 이 순서를 고정한다.
- 검토: 제출 전 multi-review(Claude 86점, Codex 84점, Gemini는 CLI가 없어 미실행)와 구현자가 아닌 `code-reviewer`의 스키마 대조(불일치 없음)를 실행했다. 두 리뷰어가 함께 짚은 "같은 트랜잭션에서 먼저 일반 조회한 뒤 잠금 조회" 경로는 임시 테스트로 확인했다. Hibernate 7.4.5가 낡은 값을 돌려주지 않고 `ObjectOptimisticLockingFailureException`으로 실패했고 checkout 행은 하나였다. port의 KDoc에 호출 순서를 적었다. 동시성 테스트는 1초 타임아웃 대신 `pg_stat_activity`의 잠금 대기를 확인하도록 바꿨고, 잠금을 빼면 그 테스트가 실패하는 것을 확인했다.
- 결정(범위 조정 2): 잠금 대기를 3초로 제한했다. 처음에는 다음 PR로 넘겼다가 이 PR이 잠금을 도입했으므로 그 위험의 완화도 여기서 하기로 했다. 대기하는 요청이 커넥션을 쥐고 있어서 무제한 대기는 한 주문의 경합을 풀 전체로 번지게 한다. 커넥션 전체가 아니라 잠금 조회 한 곳에만 걸어서 Flyway와 다른 쿼리의 동작은 바뀌지 않는다. 넘으면 `PessimisticLockingFailureException` 계열 예외이고 단말이 다시 요청하면 기존 결과를 받는다.
- 발견: Hibernate 7.4.5는 PostgreSQL에서 `jakarta.persistence.lock.timeout` 힌트를 지원한다. 현재 값을 읽고 `set local lock_timeout = 3000`을 건 뒤 조회하고 되돌린다(잠금 조회 한 번에 문장이 셋 더 나간다). `PESSIMISTIC_WRITE`의 SQL은 `for update`가 아니라 `for no key update`다. 같은 잠금끼리 충돌하므로 직렬화는 그대로 성립한다.
- 결정: 이 PR의 잠금은 DB 락뿐이다. 애플리케이션 락(분산 락)은 Redis를 도입할 때 Redis로 하고 그 전에는 만들지 않는다. Redis 락은 경합을 앞에서 줄이는 수단이고 DB의 행 잠금·unique 제약·`@Version`은 마지막 방어선으로 남는다.
- 다음 PR로 넘긴 것: 잠금 대기 초과를 재시도 가능한 HTTP 응답으로 바꾸는 매핑, 인가가 없다는 알려진 빈틈의 기록.
- 갱신: [현재 상태](current-state.md), [도메인 모델](domain-model.md).

## 2026-09-21 — checkout 요청 endpoint와 경합 응답
- Spec: [order-request-checkout-api](../spec/2026-09-21-order-request-checkout-api.md)
- 요구사항 영향: 단말이 checkout을 요청할 수 있게 됐다. `POST /v1/orders/{orderId}/checkout`이 200과 회차·확정 금액·통화를 돌려준다. 이것으로 `RequestCheckout`이 끝났다. 공통 오류에 `409 CONCURRENT_REQUEST`가 늘었다. 스키마는 바뀌지 않았다.
- 결정: 경로는 주문에 대한 행위를 하위 경로로 두고 성공은 첫 요청과 재요청 모두 200이다. 회차를 조회하는 endpoint가 없고 상태를 바꾸는 요청이라 201보다 200이 맞다.
- 결정: 잠금 대기 초과와 버전 충돌은 `409 CONCURRENT_REQUEST`다. 원인이 단말의 중복·동시 요청이라 4xx다. `503`은 단순 중복 요청까지 5xx로 집계되고 프록시가 인스턴스 이상으로 볼 수 있어 버렸다. `platform`의 advice가 받으므로 모든 endpoint에 적용된다.
- 결정: unique 제약 위반은 일반 매핑하지 않았다. 어떤 제약인지에 따라 뜻이 달라서다. `POST /v1/orders`의 같은 키 동시 요청은 계속 500이다.
- 알려진 빈틈: 인가가 없다. endpoint가 열리면서 `orderId`만 알면 다른 매장의 주문을 전이시킬 수 있게 됐다. 단말 인증을 넣는 작업이 닫는다.
- 검토: 제출 전 multi-review(Claude 88점, Codex 90점, Gemini는 CLI가 없어 미실행)를 실행했고 둘 다 막는 결함은 없다고 판정했다. `CONCURRENT_REQUEST`의 고정 문구에서 재시도 약속을 뺐다. 경합 예외는 DB 경합을 뜻할 뿐이고 endpoint가 멱등인지는 보장하지 않아서다. 재시도 안내는 checkout의 REST Docs에만 뒀다. 409 안에서 `errorCode`로 분기해야 한다는 점, deadlock·직렬화 실패의 관측, 인가 빈틈의 복구 불가성과 "인증·소유권 검사 전에는 외부에 노출하지 않는다"는 조건을 현재 상태에 적었다.
- 발견: 커밋 시점의 버전 충돌은 낙관적 잠금 예외로 나오지 않는다. `BaseEntity`의 `@UpdateTimestamp(source = DB)` 때문에 Hibernate 7.4.5가 `UPDATE ... RETURNING`을 쓰고, 0행이 갱신되면 `JpaSystemException`("The database returned no natively generated values")이 난다. HTTP에서는 500이다. 임시 통합 테스트로 확인했고 커밋하지 않았다. 커밋은 거절되므로 정합성은 지켜지지만 단말은 재시도해도 되는 오류인지 알 수 없다. #20부터 있던 성질이고 checkout endpoint는 행 잠금을 써서 이 경로에 닿지 않는다. 별도 PR로 고친다.
- 다음 작업이 확인할 것: Facade는 커밋 전에 이벤트를 발행한다. Event Publication Registry나 리스너를 넣는 PR이 발행과 트랜잭션의 묶임을 확인한다. 지금 `CONCURRENT_REQUEST` 뒤의 재시도가 안전한 것은 리스너가 없기 때문이기도 하다.
- 갱신: [현재 상태](current-state.md).

## 2026-09-21 — Spring Modulith Event Publication Registry 도입
- Spec: [event-publication-registry](../spec/2026-09-21-event-publication-registry.md)
- 요구사항 영향: 제품 동작과 API는 바뀌지 않았다. 이벤트 발행 기록이 업무 상태와 같은 트랜잭션에 남는 기반이 들어갔다. `payment`가 `OrderPaymentRequested`를 받기 전에 필요한 전제다. 스키마에 Modulith의 `event_publication`이 늘었다. 프로덕션 리스너가 아직 없어서 지금 실제로 기록되는 이벤트는 없다.
- 결정: 완료 처리는 UPDATE다. 완료 표시만 하고 행을 남겨서 이벤트가 언제 발행되고 처리됐는지를 DB에서 확인할 수 있다. 테이블이 계속 자라므로 정리 작업이 필요하고 `payment` 이후에 정한다.
- 결정: 재기동 시 미완료 이벤트 재발행은 켜지 않았다. 인스턴스가 여럿이면 중복 재발행된다. 재전송 절차 전체를 `payment` PR이 정한다. 그때까지 실패한 이벤트는 미완료 기록으로 남기만 한다.
- 결정: 테이블 이름은 프레임워크의 `event_publication` 그대로다. `t_` 접두어와 `BaseEntity` 공통 컬럼의 예외다.
- 결정: Facade가 커밋 전에 이벤트를 발행하는 순서를 유지한다. 발행 기록이 같은 커밋에 들어가려면 그래야 한다. #33의 리뷰가 확인하라고 넘긴 항목이다.
- 발견: Modulith의 자동 구성이 `@EnableAsync`를 켠다. `@ApplicationModuleListener`는 우리가 켜지 않아도 Spring Boot의 기본 task executor에서 비동기로 실행되고, 종료 대기 2초도 Modulith가 기본값으로 바꾼다. 처음 spec은 동기 실행이라고 잘못 적었고 테스트용 리스너의 실행 스레드를 보고 jar를 확인해 고쳤다.
- 발견: JPA용 jar에는 스키마 SQL이 없다. JPA가 `validate`라서 의존성만 넣으면 `Schema validation: missing table [event_publication]`으로 기동이 막힌다. 같은 버전 JDBC 모듈의 공식 PostgreSQL 스키마 v2를 기준으로 마이그레이션을 썼다.
- 발견: 이벤트 직렬화는 기본 구성으로 왕복이 성립한다. value class ID는 UUID 문자열, `Money`는 금액 숫자와 통화 코드로 저장되고 `30.00`처럼 끝자리가 0인 금액도 자릿수를 잃지 않는다.
- 검토: 제출 전 multi-review(Claude 88점, Codex 87점, Gemini는 CLI가 없어 미실행)와 구현자가 아닌 `code-reviewer`의 스키마 대조(불일치 없음)를 실행했다. 셋 다 막는 결함은 없다고 판정했다. 리뷰어의 지적 중 사실 확인이 필요한 것은 jar와 임시 테스트로 확인하고 적었다.
- 발견: 리스너를 실행하는 기본 task executor는 코어 8, 큐 무제한이다. 종료 대기 2초 안에 시작하지 못한 작업은 미완료 기록으로 남는다.
- 발견: `listener_id`에 리스너의 클래스 정규 이름·메서드·이벤트 타입이 저장되고 완료 표시가 이 값으로 행을 찾는다. 리스너의 이름도 저장 형식이다.
- 발견: 트랜잭션 밖에서 발행하면 발행 기록은 남는데 리스너가 실행되지 않는다. 리뷰어는 "기록도 전달도 없다"고 예상했지만 실제로는 기록만 남아 미완료로 쌓인다. 이벤트 발행은 Facade의 트랜잭션 안에서만 한다는 규칙을 [도메인 모델](domain-model.md) 4.3절에 적었다.
- 결정: 합의한 기본값 둘을 `application.yaml`에 명시했다. 동작은 바뀌지 않는다.
- 결정: Modulith 버전을 올릴 때의 대응 절차를 [도메인 모델](domain-model.md) 4.3절에 적었다. 규칙만 있고 방법이 없다는 지적을 받아서다. 컬럼이 늘면 `validate`가 기동을 막고 통합 테스트가 컨텍스트를 띄우므로 CI가 먼저 잡는다(`V004`를 v1 스키마로 되돌려 확인했다). 무엇이 바뀌었는지는 Modulith가 버전으로 관리하는 `schemas/vN/schema-postgresql.sql`을 diff해서 안다. 라이브러리가 컬럼을 없애는 경우만 조용히 통과한다.
- 다음 작업이 확인할 것: 종료할 때 task executor의 대기와 DataSource 종료의 순서(확인하지 않았다), 오래 머문 발행 기록을 감시하는 `spring.modulith.events.staleness.*` 설정, 완료 표시가 UPDATE라서 생기는 테이블 팽창과 정리 작업.
- 갱신: [현재 상태](current-state.md), [도메인 모델](domain-model.md), [AGENTS.md](../../AGENTS.md).

## 2026-09-21 — 커밋 시점의 버전 충돌을 낙관적 잠금 예외로 되돌리기
- Spec: [commit-version-conflict-optimistic-lock](../spec/2026-09-21-commit-version-conflict-optimistic-lock.md)
- 요구사항 영향: 제품 요구사항은 바뀌지 않았다. 행 잠금 없이 `@Version`에 기대는 쓰기가 커밋 시점에 버전 충돌을 만나면 `JpaSystemException`이 아니라 `OptimisticLockingFailureException` 계열로 끝난다. 커밋이 거절되는 것은 전과 같다. HTTP에서는 checkout endpoint PR(#33)이 넣은 매핑을 타서 `409 CONCURRENT_REQUEST`다. #33이 [현재 상태](current-state.md)에 적은 "커밋 시점의 버전 충돌은 이 매핑에 닿지 않는다 ... 알려진 빈틈이다" 서술을 지웠다.
- 원인: `BaseEntity`의 `@CreationTimestamp`·`@UpdateTimestamp`가 `SourceType.DB`였다. Hibernate 7.4.5는 실행 시점 generated 값이 있는 엔티티의 INSERT·UPDATE에 generated 값 delegate를 붙이고, delegate가 있으면 갱신 행 수 검사(`OperationResultChecker`)를 거치지 않는다. 0행이 갱신되면 "The database returned no natively generated values"가 난다. Hibernate JIRA에서 같은 증상의 이슈를 찾지 못했고 hibernate-orm `main`도 같은 코드다. `hibernate.jdbc.use_get_generated_keys=false`로도 피할 수 없다(PostgreSQL에서는 `InsertReturningDelegate`가 대신 붙는다).
- 결정: 두 시각을 `SourceType.VM`으로 바꿨다. `created_at`·`updated_at`은 애플리케이션 시각이 됐고 `deleted_at`만 DB `current_timestamp`다. 한 INSERT의 두 시각은 같지 않을 수 있어서 갱신된 적 없는 행은 `version = 0`으로 판정한다. Hibernate는 자기 시계를 쓰므로 `Clock` 빈을 고정해도 두 컬럼은 따라오지 않는다. 인스턴스가 여럿이면 시계 오차만큼 어긋날 수 있다. 두 컬럼을 읽는 코드는 없고 순서 판정은 `version`과 sync의 sequence가 맡는다.
- 버린 안: Spring Data JPA Auditing(`Clock` 빈으로 모을 수 있지만 설정과 entity listener가 들어오고 JPA callback을 쓰지 않는다는 규칙을 뒤집는다), 컬럼 default와 트리거(테이블마다 트리거가 필요하고 엔티티 값이 채워지지 않는다), 트랜잭션마다 `select current_timestamp`를 읽는 custom generator(읽는 곳 없는 컬럼에 Hibernate SPI와 왕복 하나를 들인다), advice에서 `JpaSystemException`을 메시지로 식별하기(Hibernate 문구에 기대고 그 문구가 버전 충돌만 뜻하지 않는다).
- 발견: generated 값 delegate가 있는 엔티티는 JDBC batch에서도 빠진다(`InsertCoordinatorStandard`·`UpdateCoordinatorStandard`가 batch key를 두지 않는다). 이 제약도 함께 없어졌다. batch 설정은 켜지 않았다.
- 볼트: `rules/domain-modeling-kotlin.md` 5절의 `BaseEntity` 서술을 `SourceType.VM`으로 고치고 실행 시점 DB generated 값을 쓰지 않는다는 규칙을 더했다. 볼트는 git으로 관리되지 않아서 수정 전 사본을 `~/Documents/domain-modeling-kotlin.2026-09-21.before-vm-timestamps.md`에 남겼다.
- 검토: 제출 전 multi-review(Claude 86점, Codex 92점, Gemini는 CLI가 없어 미실행)를 실행했다. 둘 다 코드에 막는 결함은 없다고 판정했다. 시각 단언을 시계에 기대지 않게 바꾸고, 충돌이 커밋에서 드러난다는 것을 테스트로 고정하고, 과장된 서술(시각 출처가 `Clock` 빈으로 모인다)을 고쳤다.
- 갱신: [현재 상태](current-state.md), [도메인 모델](domain-model.md).

## 2026-09-21 — BaseEntity의 @Version을 걷어내고 필요한 엔티티만 직접 선언
- Spec: [base-entity-explicit-version](../spec/2026-09-21-base-entity-explicit-version.md)
- 요구사항 영향: 제품 요구사항은 바뀌지 않았다. 저장 형식과 엔티티 작성 규칙이 바뀌었다. `t_order_line`과 `t_order_checkout`에서 `version` 컬럼이 없어졌고 `t_order`는 그대로다. HTTP API와 이벤트는 바뀌지 않았다.
- 결정(사용자, 2026-09-21): `BaseEntity`에서 `@Version`을 빼고 낙관적 잠금이 실제로 필요한 엔티티만 직접 선언한다. 기반 클래스를 버전 있는 것과 없는 것 둘로 나누지 않는다. 공통 기반에 넣어 두면 "왜 모든 테이블에 version이 있는가"를 다른 개발자에게 매번 설명해야 하고, 필요 없는 곳에도 있다는 사실이 소통 비용이 된다. 필요한 곳에 명시적으로 붙어 있으면 그 선언이 의도를 말해 준다. 지금 선언하는 엔티티는 aggregate 루트인 `OrderEntity` 하나다.
- 결정: `@Version`이 없는 새 자식 행은 `OrderPersistenceAdapter`가 `EntityManager.persist`로 넣는다. 도메인 ID를 저장 전에 만들기 때문에 Spring Data의 `saveAll`에 맡기면 새 행을 기존 행으로 보고 `merge`한다. 어댑터는 이미 기존 행을 조회해 ID로 구분하고 있어서 새 정보가 필요 없다. 엔티티가 `Persistable`을 구현하는 안은 엔티티마다 transient 상태와 JPA callback이 필요하고 Kotlin에서 `getId` 시그니처가 충돌해서 버렸다. `merge`의 추가 SELECT를 받아들이는 안은 "신규 저장은 INSERT 한 번"이라는 기존 테스트의 의도와 어긋나서 버렸다.
- 확인: Hibernate 통계의 JDBC 문장 수로 쟀다(Spring Boot 4.1.1, Hibernate 7.4.5). `@Version`만 빼고 `saveAll`을 그대로 두면 새 자식 행마다 SELECT가 정확히 하나씩 더 나갔다. line 두 개짜리 새 주문 저장은 4에서 6, line 하나 추가와 checkout 하나 추가는 각각 4에서 5가 됐다. `persist`로 바꾸면 4로 돌아온다. `OrderInsertStatementCountIntegrationTest`가 이 수를 고정한다. checkout 행의 `version = 0`으로 같은 사실을 간접 확인하던 단언은 컬럼과 함께 없앴다.
- 발견과 결정(범위 조정 1): `persist`로 바꾸면 트랜잭션 없이 부른 `save`가 line 없는 주문 행을 남긴다. 주문 행은 `OrderJpaRepository.save`가 자기 트랜잭션으로 먼저 커밋하고 `persist`는 트랜잭션이 없어 `InvalidDataAccessApiUsageException`으로 실패하기 때문이다. 어댑터의 `save`에 `@Transactional`(`REQUIRED`)을 선언해 막았다. Facade의 트랜잭션이 있으면 합류하고 없으면 `save` 하나가 한 트랜잭션이다. 트랜잭션 밖 호출이 line INSERT에서 실패하면 주문 행도 남지 않는다. 전에는 이 경우 주문 행이 남았다. `MANDATORY`는 지금까지 동작하던 직접 호출을 금지로 바꿀 이유가 없어서 버렸다.
- 결정: 코드 제거와 컬럼 삭제를 한 PR로 했다. [도메인 모델](domain-model.md) 4.7절 질문 8의 원칙은 "컬럼 삭제는 그 컬럼을 쓰는 코드가 배포에서 빠진 다음 PR에서"다. 배포된 환경이 아직 없어서 보호할 이전 버전 인스턴스가 없다. 나누면 엔티티에서 `version`을 뺀 코드가 `not null` 컬럼에 INSERT할 수 없어서 default를 더하는 마이그레이션이 하나 더 필요하다. 이 판단은 배포 환경이 없는 지금에만 성립한다.
- 받아들인 것: 같은 line 행을 두 트랜잭션이 동시에 고치면 뒤에 커밋한 쪽이 조용히 이긴다. 지금 기존 주문의 line을 고치는 명령이 없고, 행 단위 version은 서로 다른 line을 고치는 두 트랜잭션을 막지 못해 aggregate의 방어선이 아니었다. line 변경 명령을 넣는 PR이 주문 행 잠금 조회를 쓰거나 루트 version 증가를 정한다는 기존 계획은 그대로다.
- 순서: PR #34(커밋 시점의 버전 충돌) 위에 stacked PR로 올렸다가 #34가 머지된 뒤 `main` 위로 rebase했다. 같은 `BaseEntity.kt`와 같은 wiki 절을 고치기 때문이다. 그 사이 Event Publication Registry PR(#35)이 먼저 머지되어 `V004`를 가져갔으므로 이 PR의 마이그레이션은 `V005`다. EPR이 문장 수 테스트의 기대값을 바꿀 것으로 봤으나 운영 코드에 `@ApplicationModuleListener`가 없어서 바뀌지 않았다. 리스너를 더하는 PR이 그 테스트를 함께 본다.
- 볼트: `rules/domain-modeling-kotlin.md` 5절에서 `BaseEntity`가 주는 것 중 `@Version`을 빼고, 필요한 엔티티만 직접 선언한다는 규칙과 `@Version` 없는 새 행은 adapter가 `persist`한다는 규칙을 더했다. 안티패턴 목록에도 두 항목을 더했다. 볼트는 git으로 관리되지 않아서 수정 전 사본을 `~/Documents/domain-modeling-kotlin.2026-09-21.before-explicit-version.md`에 남겼다.
- 갱신: [현재 상태](current-state.md), [도메인 모델](domain-model.md).

## 2026-09-21 — Money가 저장 가능한 금액 범위를 불변식으로 지킨다
- Spec: [money-amount-limit](../spec/2026-09-21-money-amount-limit.md)
- 요구사항 영향: 금액 입력 규칙이 하나 늘었다. 정수부가 15자리를 넘는 금액은 `INVALID_MONEY_AMOUNT`(400)로 거절한다. 요청·응답의 모양과 스키마는 바뀌지 않았다.
- 배경: 전체 코드 감사에서 찾아 실제 요청으로 재현한 결함이다. 단가 `999999999999999`에 수량 2로 주문을 만들면 `201`로 커밋되고, checkout에서 `t_order_checkout.amount`(`numeric(19,4)`) INSERT가 실패해 `500`이 났다. 재시도해도 같고 line을 고치거나 주문을 취소하는 명령이 없어 복구 경로가 없었다.
- 결정: 상한을 `Money`의 불변식으로 뒀다. `Order`·`OrderLine`의 업무 규칙으로 두면 같은 검사를 두 곳에 써야 하고 `payment`·`sync`가 `Money`를 쓸 때 같은 구멍이 다시 생긴다. 컬럼 precision을 키우는 안은 도메인이 상한을 모르는 상태가 그대로라 더 큰 값에서 같은 고착이 생긴다. 대가로 `foundation`이 저장 형식을 알게 되므로 근거를 `Money.MAX_INTEGER_DIGITS`의 주석에 남겼다.
- 발견: `Money`에 상한을 넣는 것만으로는 닫히지 않았다. `OrderLine.amount`와 `Order.total`이 lazy 계산이라 `Order.create`가 합계를 평가하지 않아, 합계만 넘는 주문은 여전히 `201`로 커밋됐다(상한을 넣은 뒤 확인). 달라진 것은 checkout의 응답이 `500`에서 `400`으로 바뀐 것뿐이었다. 그래서 `Order.create`가 생성 시점에 합계를 한 번 평가하게 했다.
- 범위 밖: 지수 표기 입력의 비용, 소수 자릿수가 `-1`인 통화, `Money.of`가 `NumberFormatException`을 감싸지 않는 것, `quantity`와 line 개수의 상한. 모두 같은 감사에서 나왔고 [현재 상태](current-state.md)의 알려진 빈틈에 적었다.
- 검토: 제출 전 multi-review(Claude 89점, Codex 90점, Gemini는 CLI가 없어 미실행)를 실행했고 둘 다 막는 결함은 없다고 판정했다. 상한 식에서 반례를 찾지 못했다는 것도 둘 다 같았다.
- 정정: 두 리뷰어가 함께 짚은 사실 오류를 고쳤다. spec이 "범위를 넘는 행이 저장된 적이 없다(컬럼 제약이 막아 왔다)"고 적었는데 틀렸다. **컬럼 제약은 개별 금액만 막았고 합계는 막은 적이 없다.** 합계가 범위를 넘는 주문이 이미 저장돼 있으면 이 변경 뒤에도 고착이고, 달라지는 것은 checkout 응답이 500에서 400으로 바뀌는 것뿐이다. 알려진 빈틈에 적었다.
- 반영: 상한 상수와 `numeric(19,4)`를 이어 붙이는 왕복 테스트를 더했다(15자리 주문이 실제로 저장되고 checkout까지 간다). 거절만 확인하면 상한을 14로 잘못 적어도 단위 테스트만 고쳐 통과한다는 지적이었다. `Order.create`의 합계 평가에 `requireStorableTotal`이라는 이름과 KDoc을 줘서 line을 바꾸는 명령이 생길 때 같은 경로에서 불러야 한다는 계약을 남겼다. `minus`, 소수 4자리 통화(CLF), 여러 line의 합산 경계도 고정했다.
- 정정: `Money`를 Jackson으로 역직렬화하는 경로가 지금 없어서 검사 경로 열거에서 뺐다.
- 갱신: [현재 상태](current-state.md).

## 2026-09-21 — PR 검사기가 실제로 규칙을 강제하게 한다
- Spec: [pr-harness-gaps](../spec/2026-09-21-pr-harness-gaps.md)
- 요구사항 영향: 제품 요구사항과 애플리케이션 코드는 바뀌지 않았다. 개발 하네스의 우회 경로 다섯 개를 막았다. 규칙 자체는 그대로이고, 문서에만 있던 규칙이 검사로 강제된다.
- 배경: 전체 코드 감사에서 찾아 전부 재현했다. 확장자가 대문자거나 하위 디렉터리에 있거나 Kotlin·Java인 마이그레이션은 Flyway 버전 중복 검사를 빠져나갔다. 스키마 검토를 요구하는 경로 정의와 버전을 뽑는 경로 정의가 서로 달랐던 것이 원인이다. CI가 강제하는 유일한 규칙이라 실패 비용이 가장 컸다.
- 배경: `fnmatch`의 별표가 경로 구분자를 넘어서, 변경 경로에 별표 하나만 적으면 범위 검사가 무력해졌다. 같은 이름의 절을 문서 끝에 한 번 더 쓰면 뒤엣것이 이겨서 리뷰어가 읽는 절과 검사기가 쓰는 절이 달라졌다. 하네스 테스트 파일을 직접 실행하면 `unittest.main()`이 파일 중간에 있어 96개 중 16개만 돌고 OK를 찍었다.
- 결정: 마이그레이션 인식을 `SCHEMA_CHANGE` 한 정의로 모았다. 두 판정이 갈라지면 같은 구멍이 다시 생긴다.
- 결정: glob 변환기를 직접 만들었다. 별표 하나는 한 경로 조각 안에서만, 별표 둘은 조각을 넘어 매칭한다. Python 3.13의 `PurePath.full_match`가 같은 일을 하지만 이 저장소는 3.9에서도 돌아야 한다.
- 결정: 절 이름이 중복되면 어느 쪽을 쓸지 고르지 않고 거절한다. 코드 블록 안의 제목도 절로 세지 않는다. 스키마 검토 판정과 기준을 맞췄다.
- 발견: 마이그레이션 파일명만 바꾸면 옛 이름과 새 이름이 같은 버전의 중복으로 잡혀, 회피 수단 없이 실패했다. 이 PR이 지운 경로를 판정에서 빼도록 고쳤다.
- 발견: 브랜치 보호는 쓰지 않는 것이 아니라 이 저장소의 플랜에서 켤 수 없다(API가 403을 돌려준다). 그래서 지금 자동으로 머지를 막는 장치는 하나도 없다. 코드로 고칠 수 없어 사실만 개발 규칙에 적었다.
- 발견: 마지막 절 뒤에 개행이 없어도 절을 인식하는 것은 확인 결과 이미 정상이었다. 실제 결함이 아니어서 고치지 않고 회귀 테스트만 남겼다.
- 범위 밖: CI가 spec 이력·크기·wiki 기록까지 검사하게 하는 것, 대조 기록의 주체 확인, spec 본문 내용의 품질 판정, 하네스 테스트의 중복 실행과 성능.
- 검토: 검사기 변경이라 저장소 규칙대로 우회를 따로 검증받았다. 독립 검증자가 임시 저장소에서 우회를 다시 시도했고 `reviewer-claude`(82점)가 거짓 실패 위험을 전수로 확인했다. **둘 다 잔여 우회를 찾았다.** 별표 하나를 막았더니 `- **` 한 줄로 되살아났고, 닫히지 않은 코드 블록은 절 판정과 스키마 검토 게이트를 그대로 뚫었다. 리터럴이 없는 패턴을 거절하고, 닫는 표시가 없으면 문서 끝까지 코드 블록으로 보도록 고쳤다.
- 정정: 코드 블록을 지운 문자열로 본문까지 판정해서, 명령만 적은 `검증` 절이 미작성으로 거짓 실패하는 회귀를 만들 뻔했다. 절의 위치만 코드 블록 밖에서 찾고 본문은 원문 그대로 보도록 바꿨다. 기존 spec 34개에는 해당 사례가 없었지만 앞으로 막혔을 경로다.
- 정정: `SCHEMA_CHANGE`에 종속시키면서 최상위 `src/` 밖의 마이그레이션이 버전 검사에서 빠졌다. 옛 정규식은 경로 어디에 있든 잡았다. 멀티모듈로 나누면 CI가 강제하는 유일한 규칙이 조용히 꺼졌을 것이라 앵커를 완화해 되돌렸다.
- 정정: `test_renaming_still_detects_a_real_conflict_on_base`가 이름이 말하는 것을 검증하지 않았다. 충돌하는 두 파일이 모두 변경 뒤 트리에 있어 제외 로직과 무관하게 통과했다. 기준 브랜치가 merge-base 이후로 전진하는 시나리오로 고쳤다.
- 정정: 과거 기록과의 충돌. [flyway-version-guard](../spec/2026-09-15-flyway-version-guard.md)가 "저장소 소유자가 브랜치 보호에서 상태 검사 통과와 머지 전 최신 상태 요구를 켰다"고 적었지만, 지금 확인하면 브랜치 보호 API가 `403 Upgrade to GitHub Pro`를 돌려주고 저장소는 private이다. 그 설정은 지금 켜져 있지 않고 켤 수도 없다. spec은 이력이라 고치지 않고 개발 규칙의 서술만 맞췄다.
- 범위 밖으로 남긴 한계: `V2`와 `V2.0`을 다른 버전으로 보는 것(Flyway의 실제 동작을 확인하지 못했고 이 PR 이전부터 같다), `spring.flyway.locations` 변경, glob의 문자 클래스 미지원, `**`가 디렉터리 0단계를 매칭하지 않는 것.
- 갱신: [개발 규칙](development-workflow.md).

## 2026-09-21 — Money가 받아들이는 입력의 경계를 닫는다
- Spec: [money-input-boundary](../spec/2026-09-21-money-input-boundary.md)
- 요구사항 영향: 입력 규칙이 두 개 늘었다. 금액을 표현할 수 없는 통화는 `UNSUPPORTED_CURRENCY`(400), 숫자로 읽을 수 없는 금액 문자열은 `INVALID_MONEY_FORMAT`(400)이다. 요청·응답의 모양과 스키마는 바뀌지 않았다.
- 배경: 전체 코드 감사에서 찾아 전부 재현했다. `Money.of`가 값을 canonical scale로 먼저 맞추고 그다음 불변식을 보는 순서였다. 거절할 입력에도 펴는 비용을 먼저 치러서, 단가 `1E+100000` 하나를 거절하는 데 375ms가 걸렸다. 지수를 키우면 그대로 커진다.
- 배경: `Currency.getDefaultFractionDigits()`가 `-1`을 돌려주는 통화(`XAU`·`XDR`·`XXX`)를 `Money`가 그대로 canonical scale로 썼다. `of("100", XAU)`는 `1.0E+2`를 만들어 금액을 10 단위로 양자화했고, `of("105", XAU)`는 "통화 표준 자릿수를 넘는 금액"이라는 엉뚱한 이유로 거절했다. `currency`는 요청 본문에서 오므로 클라이언트가 고를 수 있었다.
- 배경: `Money.of(String, Currency)`가 `NumberFormatException`을 감싸지 않아 숫자가 아닌 문자열이 500이 됐다. 지금은 `order`의 DTO가 `BigDecimal`이라 Jackson이 먼저 막지만, `foundation`의 공개 API라 `payment`·`sync`가 쓰면 드러난다.
- 결정: 검사 순서를 뒤집어 값을 펴기 전에 메타데이터로만 판정한다. `precision()`과 `scale()`은 `BigDecimal`이 이미 들고 있는 정수 두 개라 입력 크기와 무관하다. 뒤따르는 0은 `stripTrailingZeros()`로 떼는데, 이 비용은 유효 자릿수에 비례하고 값을 펴지 않는다.
- 결정: 표현할 수 없는 통화는 값을 만들지 않고 거절한다. 판정을 `init`과 `of`가 같은 함수로 공유해 생성자·`copy`·`zero`도 함께 막힌다. `of`만 막으면 `copy`가 우회한다.
- 결정: 요청 DTO가 `BigDecimal`을 그대로 넘기게 `of(BigDecimal, Currency)` 오버로드를 더했다. `toPlainString()` 왕복이 사라진다.
- 발견: 자릿수 계산이 `Int`로는 넘친다. `1E+2147483647`은 `precision - scale`이 `Int.MIN_VALUE`로 넘쳐 금액 상한 검사를 통과하고, 13자라 Jackson의 1000자 제한도 지나간다. 이 상태의 첫 구현은 `setScale`의 `ArithmeticException`이 새어 500이었고 main은 400이었으므로 회귀였다. 자릿수 계산을 `Long`으로 바꿔 닫았다. 이 입력군의 `errorCode`는 `INVALID_MONEY_SCALE`에서 `INVALID_MONEY_AMOUNT`로 바뀐다. 둘 다 400이고 바뀐 쪽이 의미에 맞는다.
- 반영: 이전 구현과 판정이 갈리는 입력이 있는지 경계값 33종 × 통화 자릿수 3종과 무작위 30만 건으로 대조했다. 위 지수 입력군을 빼면 불일치가 없다.
- 범위 밖: 매장이 어떤 통화로 영업할지 정하는 allow-list. 이 PR은 `Money`가 표현할 수 없는 통화만 거절한다. [현재 상태](current-state.md)의 알려진 빈틈에 적었다.
- 반영: multi-review 2차(Claude 78점, Codex 81점)가 짚은 것을 받았다. `stripTrailingZeros()`가 가드보다 앞이라 평문 표기 큰 입력(`1.` + 0 십만 개)에 41ms가 남아 있었다. 가드를 strip 앞으로 옮기고 뗄 0의 개수에 상한(`MAX_TRAILING_ZEROS = 20`)을 뒀다. 비용 상한이 Jackson의 길이 제한이 아니라 코드 자신에게 있다.
- 반영: `stripTrailingZeros()`를 지워도 테스트가 전부 초록이었다. 그 줄의 존재 이유(`1000.0000`, `0.00`, strip 불변 경계)를 테스트로 고정했다.
- 반영: 복원 경로(`OrderLineEntity`·`OrderCheckoutEntity`)가 raw `defaultFractionDigits`로 `setScale`을 불러 통화 자릿수로 떨어지지 않는 행에서 500이 났다. 둘 다 `Money.of`를 부르게 해서 판정을 실제로 한 곳으로 모았다.
- 반영: 통화 자릿수의 위쪽 상한(`MAX_FRACTION_DIGITS = 4`)도 막았다. 자릿수는 `java.util.currency.properties`로 런타임에 9까지 올릴 수 있는데 저장 컬럼의 소수부는 4다.
- 반영: 가드 순서가 바뀌면서 금액·자릿수를 동시에 어긴 입력의 `errorCode`가 `INVALID_MONEY_SCALE`에서 `INVALID_MONEY_AMOUNT`로 바뀐다. 무작위 50만 건에서 갈리는 입력을 전부 분류했고 그 밖의 차이는 0건이다.
- 범위 밖: line 개수·요청 본문 크기의 상한, 커넥션·쿼리·트랜잭션 타임아웃, 로그 속성. 같은 감사에서 나왔고 목표가 달라 뒤따르는 PR에서 닫는다.
- 갱신: [현재 상태](current-state.md).
