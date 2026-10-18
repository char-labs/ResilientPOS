# PR spec: README에 아키텍처 절을 더하고 상세를 wiki로 옮긴다

## 목적
저장소를 공개로 바꿀 예정이다. 외부 독자(채용 담당자·개발자)가 README만 읽고 시스템 구조를 이해할 수 있어야 하는데, 지금 README에는 구조 그림이 없다. 요청이 서버 안에서 어디를 지나고 Kafka 메시지가 어디서 생기는지, 도메인을 어떻게 찾았는지(이벤트 스토밍), 모듈을 어떻게 나누고 서로 무엇에 의존하는지가 `docs/wiki/domain-model.md`와 spec 수십 개에 흩어져 있다.

이 PR은 README에 `아키텍처` 절을 더해 세 질문에 요약 도식으로 답하고, 상세는 새 wiki 페이지 `docs/wiki/architecture.md`에 둔다. 사용자가 "README 요약 + wiki 상세" 배치를 골랐다(2026-10-18).

## 범위
- README `## 아키텍처` 절. 요청에서 Kafka까지의 전체 흐름 flowchart 하나, 모듈 의존 그래프 flowchart 하나, 모듈 사이 이벤트 표, 이벤트 스토밍 결과 요약, wiki 링크.
- 새 `docs/wiki/architecture.md`(wiki 문체인 평서체).
  1. 요청 처리 단계: 단말 인증 interceptor → Controller → Command Facade → aggregate 전이 → 업무 행과 Event Publication Registry 행의 한 커밋 → 응답 envelope → 커밋 뒤 Kafka 발행(externalization) → 다른 모듈 consumer.
  2. Kafka 메시지가 생기는 곳: 이벤트별 발행 Facade 메서드, 토픽, consumer, consumer가 부르는 Facade.
  3. 시퀀스 셋: 오프라인 sync 배치, 주문 생성 → checkout → 결제 시도·provider 승인 → `PAID` → fulfillment, `UNKNOWN` 결제의 provider 조회 확정.
  4. 실패 경로: consumer 재시도·DLT, 브로커로 못 보낸 발행 기록의 주기 재전송(resubmit).
  5. 이벤트 스토밍: 근거 문서에 남은 과정과 결과, 저장소가 원문과 다르게 정한 것, 지금 코드에 있는 것과 없는 것.
  6. Spring Modulith 모듈: 책임, 공개 API(모듈 루트 타입)와 `internal` 경계, `contract` 모듈, 동기 호출, 의존 그래프 전체와 대표 타입, 의존 규칙을 지키는 테스트, 그래프를 다시 뽑는 방법.
- `docs/wiki/README.md` 작업별 진입점에 새 페이지 한 줄.
- 도식은 GitHub이 렌더링하는 Mermaid(sequence·flowchart)로 그린다.
- 근거 원칙: 의존과 호출 순서는 문서가 아니라 origin/main 808aed9의 코드에서 확인한다. 모듈 의존은 `ApplicationModules.of(ResilientPosApplication::class.java)`로 출력한 결과를 그래프로 옮긴다. 이벤트 스토밍은 [도메인 청사진](../references/05_Domain_Development_Blueprint.md) §2·§3·§9, [문서 맵](../references/00_Document_Index.md) 변경 이력, [도메인 모델](../wiki/domain-model.md)의 용어집과 결정 표에 있는 것만 쓴다. 근거에 없는 진행 방식(참가자·워크숍 형식 등)은 쓰지 않고 "기록이 없다"고 적는다.

## 제외
- `docs/wiki/domain-model.md` 수정. 열린 PR #94가 같은 파일을 바꾼다. 코드와 어긋난 문장을 찾으면 이 spec 결과 절에 남기고 고치지 않는다.
- README의 기존 절(기술 스택 표, Engineering Principles) 정정. 낡은 문장이 있으면 결과 절에 남기고 별도 PR로 한다.
- 코드·테스트 변경. 의존 출력에 쓴 임시 테스트는 커밋하지 않는다. 모듈 문서를 빌드마다 생성하는 테스트(Modulith `Documenter`)도 더하지 않는다. README 도식은 사람이 읽고 고치는 문서이고, 생성 산출물을 커밋하면 빌드마다 diff가 생긴다.
- Obsidian 볼트에 있는 wiki(`current-state.md`, `modulith-kafka-keywords.md`) 수정과 링크. 저장소에 커밋되지 않는 링크라 공개 저장소에서 열리지 않는다.

## 관련 문서
없음

## 변경 경로
- docs/spec/2026-10-18-readme-architecture.md
- README.md
- docs/wiki/architecture.md
- docs/wiki/README.md

## 인수 조건
1. README에 `## 아키텍처` 절이 있고, 요청에서 Kafka consumer까지의 흐름 도식, 모듈 의존 그래프, 모듈 사이 이벤트 표, 이벤트 스토밍 요약, `docs/wiki/architecture.md` 링크가 있다.
2. wiki 페이지에 범위 절의 1~6이 있고, 시퀀스 셋(sync 배치, 주문 → 결제 → fulfillment, `UNKNOWN` 확정)이 Mermaid `sequenceDiagram`이다.
3. 모듈 의존 그래프의 간선이 `ApplicationModules` 출력과 일치한다. 출력에 없는 간선을 그리지 않고, 출력에 있는 모듈 사이 간선을 빠뜨리지 않는다(`foundation`·`platform`으로 가는 간선은 README에서 묶어 적을 수 있다).
4. 이벤트 스토밍 절의 모든 사실에 근거 문서 링크가 있다.
5. Mermaid 블록이 모두 문법 오류 없이 렌더링된다.
6. 새 문서와 README의 상대 링크가 모두 저장소에 추적되는 파일을 가리킨다.
7. `python3 scripts/check_pr.py --base origin/main --worktree`가 통과하고, 제출 전 `--worktree` 없는 실행도 통과한다.

## 검증
- 모듈 의존: 임시 테스트로 `ApplicationModules.of(...)`의 모듈 목록과 `getDirectDependencies`를 출력해 그래프와 대조한다. 실행 뒤 임시 테스트를 지운다.
- Mermaid: `mmdc`가 없으면 GitHub이 쓰는 Mermaid 11과 같은 메이저 버전을 브라우저에서 불러 블록마다 렌더링하고 오류가 없는지 본다.
- 링크: 새 문서와 README의 상대 링크 대상을 `git ls-files`로 확인한다.
- `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전에는 `--worktree` 없이.

## Wiki 영향
- 신설 [architecture.md](../wiki/architecture.md). [wiki README](../wiki/README.md)에 진입점을 더한다.
- 요구사항·설계 변화는 없다. 이미 머지된 구조를 그림으로 옮기는 문서 PR이다.

## 분할 계획
개발 컨텍스트는 "외부 독자용 아키텍처 설명" 하나다. origin/main 808aed9에서 시작했다. 열린 PR은 #94(`build.gradle.kts`, `docs/wiki/domain-model.md`, `EventPublicationResubmitter`와 그 테스트, #94의 spec)이고 이 PR의 변경 경로와 겹치지 않는다. README 기존 절 정정과 domain-model.md의 어긋난 문장 정정은 별도 컨텍스트다.

## TDD 검증 기록
적용하지 않는다. 문서만 바꾸고 업무 동작과 테스트 대상 코드가 없다.

## 결과
범위 절을 모두 반영했다. 2026-10-18 실행 결과와 확인한 사실은 아래와 같다.

- **배치.** README에는 전체 흐름 flowchart, 모듈 사이 이벤트 표, 모듈 의존 그래프, 이벤트 스토밍 요약만 두고(82줄 추가), 시퀀스 셋·실패 경로·이벤트 스토밍 과정·모듈 표는 [architecture.md](../wiki/architecture.md)에 두었다. 같은 그림을 두 곳에 두지 않으려고 의존 그래프는 README에만, 간선별 대표 타입 표는 wiki에만 있다.
- **모듈 의존 출력.** 임시 테스트로 `ApplicationModules.of(ResilientPosApplication::class.java)`의 `getDirectDependencies`를 출력했다(808aed9, Spring Modulith 2.1.1). 모듈 사이 간선은 `sync → order`(USES_COMPONENT, `OrderCommandFacade`), `terminal → store`(USES_COMPONENT, `StoreManagement`), `order·payment·fulfillment → contract`, `contract·order·payment·sync → store·terminal`, `store·terminal → owner`, 그리고 `foundation`·`platform`으로 가는 간선이다. `EVENT_LISTENER` 종류는 없다. consumer가 `@KafkaListener`라 Modulith가 이벤트 수신을 의존으로 보지 못한다. 출력과 그래프를 대조한 뒤 임시 테스트를 지웠다. 다시 뽑는 코드는 wiki §6.5에 있다.
- **그래프 배치.** 처음 그린 그래프는 점선 8개가 겹쳐 읽히지 않았다. `contract`·`order`·`payment`·`sync`가 모두 `store`와 `terminal` 둘 다에 의존하므로, 둘을 `단말·매장` 상자로 묶고 상자로 점선 하나씩을 그었다. 간선의 뜻은 범례에 적었다.
- **Mermaid.** `mmdc`가 없어 Mermaid 11.4.1(GitHub과 같은 메이저 버전)을 브라우저에서 불러 6개 블록을 `parse`·`render`했다. 모두 오류가 없었고 화면으로 확인해 단어 중간에서 줄이 바뀌는 라벨 넷을 고쳤다.
- **링크.** 새 문서와 README·wiki README에 추가한 상대 링크와 앵커(`#아키텍처`, `#5-이벤트-스토밍`, 도메인 모델 §3·§4.1·§4.3·§4.4·§4.6)가 모두 추적 파일과 실재 제목을 가리킨다. 기존 줄의 링크 14건은 추적되지 않는 볼트 심볼릭 링크(`docs/wiki/current-state.md`, `docs/wiki/modulith-kafka-keywords.md`, `.claude/skills/**`)를 가리켜 공개 저장소에서 열리지 않는다. 이 PR의 범위 밖이라 그대로 두었다.
- `python3 scripts/check_pr.py --base origin/main --worktree`: PASS(일반 4개/474줄).

**코드와 어긋난 문서 문장(고치지 않음, 후속 PR 후보).**
- [도메인 모델](../wiki/domain-model.md) §4.6은 "허용되는 동기 호출은 `sync` → `order`/`payment`의 공개 Command Facade뿐"이라고 적는다. 코드에서 `sync`는 `payment`를 부르지 않고(같은 절 본문도 `createAttempt`가 sync 경로에 없다고 적는다), 이 절이 다루지 않는 `terminal → store` 동기 호출(`TerminalOwnerController`의 `StoreManagement.requireOwned`, 매장 소유 확인 읽기)이 있다.
- 도메인 모델 §2는 `contract`가 "다른 업무 모듈의 타입을 들지 않는다(식별자를 raw `UUID`로 든다)"고 적는다. 코드의 세 이벤트는 `StoreId`·`TerminalId`를 든다. `OrderPaid` KDoc이 근거(`contract`에 의존하지 않는 모듈의 타입이라 순환이 없다)를 적고 있고, `ModuleStructureTest`도 `store`·`terminal`·`owner`를 금지 목록에 두지 않는다. 문장이 코드보다 넓다.
- 도메인 모델 §1의 "지금 저장소에 있는 타입은 `foundation`의 `Money`·`IdentifierGenerator`뿐"은 낡았다.
- README 기존 절: 기술 스택 표 아래의 "PostgreSQL 연결 구성과 도메인 구현은 아직 완료되지 않았습니다", "Kafka, OpenTelemetry는 … 현재 의존성에는 없습니다", 표의 H2 드라이버, Engineering Principles의 "Kafka는 독립 consumer/replay/확장 요구가 실제로 생길 때만 도입"이 새 아키텍처 절과 어긋난다. Kafka 도입은 도메인 모델 §8에 이유가 있다.
