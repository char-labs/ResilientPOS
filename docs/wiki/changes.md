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
- 다음 PR로 넘긴 것: 잠금 대기 시간 제한(대기하는 요청이 커넥션을 쥐고 있어 한 주문의 경합이 풀 전체로 번질 수 있다), 인가가 없다는 알려진 빈틈의 기록.
- 갱신: [현재 상태](current-state.md), [도메인 모델](domain-model.md).
