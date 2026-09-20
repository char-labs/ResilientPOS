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
