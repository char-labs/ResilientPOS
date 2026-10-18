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
- **범위 조정(2026-10-18, 첫 구현 커밋 뒤).** 사용자가 아래 두 가지를 이 PR에 함께 넣기로 했다. 새 아키텍처 절과 모순되는 README 문장이 공개 독자에게 그대로 보이기 때문이다.
  - README 기존 절의 낡은 문장 정정. `build.gradle.kts`, `docker/docker-compose.yaml`, `application.yaml`, `PostgresIntegrationTest`와 대조한다. 기술 스택 표(H2 제거, Redis·Security·Session·Kafka·Modulith·Kotlin JDSL·Flyway·테스트 도구 추가)와 그 아래 문단("도메인 구현 미완료", "Kafka 미도입", Redis 용도), `make up`과 로컬 실행 표(PostgreSQL만 뜬다는 설명), Testcontainers가 띄우는 것, TDD 절의 "의존성 설치는 최초 기능 PR에서", Engineering Principles의 모듈 목록·Kafka 도입 조건·Redis 용도. `make help`가 출력하는 `Makefile`의 `up` 설명도 같은 문장이라 함께 고친다.
  - GitHub에서 열리지 않는 링크 정리. `README.md` 1건과 `docs/wiki/README.md` 13건이 커밋되지 않는 볼트 링크(`docs/wiki/current-state.md`, `docs/wiki/modulith-kafka-keywords.md`, `.claude/skills/**`)를 가리킨다. 링크를 경로 표기(코드 스팬)로 바꾸고, wiki 인덱스에 그 경로가 `make bootstrap` 뒤에만 생긴다는 한 줄을 둔다. 링크를 지우면 에이전트가 찾아갈 경로가 사라지므로 경로는 남긴다.
- **범위 조정 2(2026-10-18, PR #95 생성 뒤).** 사용자가 남은 볼트 링크도 이 PR에서 정리하기로 했다. 공개 저장소에서 열리지 않는 링크를 없앤다는 같은 목표다. `AGENTS.md` 9건, `docs/wiki/development-workflow.md` 11건, `docs/wiki/borrowed-workflows.md`·`docs/wiki/entity-map.md` 각 2건, `docs/wiki/testing-strategy.md` 1건을 같은 방식으로 경로 표기로 바꾼다. `AGENTS.md`는 링크가 경로 표기로 바뀌어도 에이전트가 읽을 경로가 같다. 하네스(`scripts/test_link_claude.py`, `scripts/check_pr.py`)는 이 링크 형식을 검사하지 않는다.
- **범위 조정 3(2026-10-18).** 사용자가 `docs/wiki/domain-model.md`의 볼트 링크 3건(16·311·323번째 줄)도 이 PR에 넣기로 했다. #94는 같은 파일의 150~193번째 줄만 바꾸므로 hunk가 겹치지 않는다. 고치는 것은 링크 표기뿐이고 문장은 그대로 둔다.
- **범위 조정 4(2026-10-18).** 사용자가 남은 두 가지도 이 PR에 넣기로 했다.
  - `docs/wiki/domain-model.md`에서 코드와 어긋난 문장 정정(결과 절의 "코드와 어긋난 문서 문장"). §1의 "`Money`·`IdentifierGenerator`뿐", §2의 `contract`가 "다른 업무 모듈의 타입을 들지 않는다(raw `UUID`)", §4.6의 "`sync` → `order`/`payment`"와 이어지는 "`order`/`payment` Facade 합류" 문장이다. `contract`의 세 이벤트와 `OrderPaid` KDoc, `ModuleStructureTest`, `TerminalOwnerController`, `StoreManagement.requireOwned`와 대조해 고친다. §4.6 제목은 그대로 둔다. `architecture.md`가 그 앵커를 링크하고 볼트 문서가 절 이름으로 부른다. 고치는 줄은 #94의 hunk(150~193번째 줄)와 겹치지 않는다.
  - 과거 PR spec 45개의 링크 103건을 경로 표기로 바꾼다. [drop-changes-log](2026-09-21-drop-changes-log.md)는 제외 절에서 "머지된 spec 안의 `changes.md` 링크와 문장은 고치지 않는다(나중에 손대면 기록이 아니게 된다)"고 정했다. 이 PR은 그 결정을 링크 표기에 한해 바꾼다. 저장소 공개를 앞두고 GitHub에서 깨진 링크를 없애려는 것이고, 사용자가 "spec은 기록이라 두었다"는 설명을 듣고 진행을 정했다. 문장·결정·날짜는 바꾸지 않고 링크 문법만 바꾼다. `changes.md`는 "`docs/wiki/changes.md`, 2026-09-21 삭제"로 적어 당시 가리킨 파일을 남긴다.
- 근거 원칙: 의존과 호출 순서는 문서가 아니라 origin/main 808aed9의 코드에서 확인한다. 모듈 의존은 `ApplicationModules.of(ResilientPosApplication::class.java)`로 출력한 결과를 그래프로 옮긴다. 이벤트 스토밍은 [도메인 청사진](../references/05_Domain_Development_Blueprint.md) §2·§3·§9, [문서 맵](../references/00_Document_Index.md) 변경 이력, [도메인 모델](../wiki/domain-model.md)의 용어집과 결정 표에 있는 것만 쓴다. 근거에 없는 진행 방식(참가자·워크숍 형식 등)은 쓰지 않고 "기록이 없다"고 적는다.

## 제외
- `docs/wiki/domain-model.md`에서 볼트 링크 3건과 범위 조정 4의 문장 밖의 수정. 열린 PR #94가 같은 파일을 바꾼다.
- 볼트 rules(`.claude/rules/domain-modeling-kotlin.md` §6)에 있는 같은 문장("`sync` intake → `order`/`payment`")은 볼트가 정본이라 이 저장소 PR에서 고치지 않는다.
- README 기존 절 가운데 코드와 어긋나지 않는 문장의 문체·구성 변경. 범위 조정에 적은 문장만 고친다.
- 코드·테스트 변경. 의존 출력에 쓴 임시 테스트는 커밋하지 않는다. 모듈 문서를 빌드마다 생성하는 테스트(Modulith `Documenter`)도 더하지 않는다. README 도식은 사람이 읽고 고치는 문서이고, 생성 산출물을 커밋하면 빌드마다 diff가 생긴다.
- Obsidian 볼트에 있는 wiki(`current-state.md`, `modulith-kafka-keywords.md`) 수정과 링크. 저장소에 커밋되지 않는 링크라 공개 저장소에서 열리지 않는다.

## 관련 문서
없음

## 변경 경로
- docs/spec/2026-10-18-readme-architecture.md
- README.md
- docs/wiki/architecture.md
- docs/wiki/README.md
- Makefile
- AGENTS.md
- docs/wiki/development-workflow.md
- docs/wiki/borrowed-workflows.md
- docs/wiki/entity-map.md
- docs/wiki/testing-strategy.md
- docs/wiki/domain-model.md
- docs/spec/2026-09-*.md
- docs/spec/2026-10-0*.md

## 인수 조건
1. README에 `## 아키텍처` 절이 있고, 요청에서 Kafka consumer까지의 흐름 도식, 모듈 의존 그래프, 모듈 사이 이벤트 표, 이벤트 스토밍 요약, `docs/wiki/architecture.md` 링크가 있다.
2. wiki 페이지에 범위 절의 1~6이 있고, 시퀀스 셋(sync 배치, 주문 → 결제 → fulfillment, `UNKNOWN` 확정)이 Mermaid `sequenceDiagram`이다.
3. 모듈 의존 그래프의 간선이 `ApplicationModules` 출력과 일치한다. 출력에 없는 간선을 그리지 않고, 출력에 있는 모듈 사이 간선을 빠뜨리지 않는다(`foundation`·`platform`으로 가는 간선은 README에서 묶어 적을 수 있다).
4. 이벤트 스토밍 절의 모든 사실에 근거 문서 링크가 있다.
5. Mermaid 블록이 모두 문법 오류 없이 렌더링된다.
6. 새 문서와 README의 상대 링크가 모두 저장소에 추적되는 파일을 가리킨다.
7. README의 기술 스택 표·로컬 실행·TDD·Engineering Principles 문장이 `build.gradle.kts`·`docker/docker-compose.yaml`·`application.yaml`과 어긋나지 않는다. H2, "Kafka 미도입", "도메인 구현 미완료", "PostgreSQL만 뜬다"는 문장이 남아 있지 않다.
8. `README.md`, `AGENTS.md`, `docs/wiki/`·`docs/spec/` 아래 문서에 추적되지 않는 파일을 가리키는 상대 링크가 없다.
9. `python3 scripts/check_pr.py --base origin/main --worktree`가 통과하고, 제출 전 `--worktree` 없는 실행도 통과한다.
10. `docs/wiki/domain-model.md` §1·§2·§4.6의 문장이 코드와 어긋나지 않는다. 과거 spec의 diff에는 링크 문법 말고 바뀐 글자가 없다.

## 검증
- 모듈 의존: 임시 테스트로 `ApplicationModules.of(...)`의 모듈 목록과 `getDirectDependencies`를 출력해 그래프와 대조한다. 실행 뒤 임시 테스트를 지운다.
- Mermaid: `mmdc`가 없으면 GitHub이 쓰는 Mermaid 11과 같은 메이저 버전을 브라우저에서 불러 블록마다 렌더링하고 오류가 없는지 본다.
- 링크: 새 문서와 README의 상대 링크 대상을 `git ls-files`로 확인한다.
- `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전에는 `--worktree` 없이.

## Wiki 영향
- 신설 [architecture.md](../wiki/architecture.md). [wiki README](../wiki/README.md)에 진입점을 더한다.
- 요구사항·설계 변화는 없다. 이미 머지된 구조를 그림으로 옮기는 문서 PR이다.
- [도메인 모델](../wiki/domain-model.md) §1·§2·§4.6은 이미 머지된 코드에 맞춰 문장을 고친다. 새 결정은 없다.

## 분할 계획
개발 컨텍스트는 "외부 독자용 아키텍처 설명" 하나다. origin/main 808aed9에서 시작했다. 열린 PR은 #94(`build.gradle.kts`, `docs/wiki/domain-model.md`, `EventPublicationResubmitter`와 그 테스트, #94의 spec)이고 이 PR의 변경 경로와 겹치지 않는다. README 기존 절 정정과 두 파일의 볼트 링크 정리는 범위 조정으로 이 PR에 넣었다. 공개 독자가 같은 README에서 모순을 보지 않게 하는 같은 목표라서다. 남은 볼트 링크 정리도 같은 목표라 범위 조정 2로 넣었고, 새 경로(`AGENTS.md`와 wiki 네 파일)도 #94와 겹치지 않는다. 범위 조정 3으로 `docs/wiki/domain-model.md`가 #94와 경로가 겹친다. 바꾸는 줄(16·311·323)이 #94의 hunk(150~193번째 줄)와 떨어져 있어 push 전에 #94 head와 시험 merge로 충돌이 없는지 확인한다. 범위 조정 4의 domain-model.md 문장 정정과 과거 spec 링크도 "공개 독자가 코드와 맞지 않는 문서나 깨진 링크를 보지 않게 한다"는 같은 목표라 넣었다. `Makefile`도 #94와 겹치지 않는다.

## TDD 검증 기록
적용하지 않는다. 문서만 바꾸고 업무 동작과 테스트 대상 코드가 없다.

## 결과
범위 절을 모두 반영했다. 2026-10-18 실행 결과와 확인한 사실은 아래와 같다.

- **배치.** README에는 전체 흐름 flowchart, 모듈 사이 이벤트 표, 모듈 의존 그래프, 이벤트 스토밍 요약만 두고(82줄 추가), 시퀀스 셋·실패 경로·이벤트 스토밍 과정·모듈 표는 [architecture.md](../wiki/architecture.md)에 두었다. 같은 그림을 두 곳에 두지 않으려고 의존 그래프는 README에만, 간선별 대표 타입 표는 wiki에만 있다.
- **모듈 의존 출력.** 임시 테스트로 `ApplicationModules.of(ResilientPosApplication::class.java)`의 `getDirectDependencies`를 출력했다(808aed9, Spring Modulith 2.1.1). 모듈 사이 간선은 `sync → order`(USES_COMPONENT, `OrderCommandFacade`), `terminal → store`(USES_COMPONENT, `StoreManagement`), `order·payment·fulfillment → contract`, `contract·order·payment·sync → store·terminal`, `store·terminal → owner`, 그리고 `foundation`·`platform`으로 가는 간선이다. `EVENT_LISTENER` 종류는 없다. consumer가 `@KafkaListener`라 Modulith가 이벤트 수신을 의존으로 보지 못한다. 출력과 그래프를 대조한 뒤 임시 테스트를 지웠다. 다시 뽑는 코드는 wiki §6.5에 있다.
- **그래프 배치.** 처음 그린 그래프는 점선 8개가 겹쳐 읽히지 않았다. `contract`·`order`·`payment`·`sync`가 모두 `store`와 `terminal` 둘 다에 의존하므로, 둘을 `단말·매장` 상자로 묶고 상자로 점선 하나씩을 그었다. 간선의 뜻은 범례에 적었다.
- **Mermaid.** `mmdc`가 없어 Mermaid 11.4.1(GitHub과 같은 메이저 버전)을 브라우저에서 불러 6개 블록을 `parse`·`render`했다. 모두 오류가 없었고 화면으로 확인해 단어 중간에서 줄이 바뀌는 라벨 넷을 고쳤다.
- **링크.** 새 문서와 README·wiki README에 추가한 상대 링크와 앵커(`#아키텍처`, `#5-이벤트-스토밍`, 도메인 모델 §3·§4.1·§4.3·§4.4·§4.6)가 모두 추적 파일과 실재 제목을 가리킨다. 기존 줄의 링크 14건은 추적되지 않는 볼트 심볼릭 링크(`docs/wiki/current-state.md`, `docs/wiki/modulith-kafka-keywords.md`, `.claude/skills/**`)를 가리켜 공개 저장소에서 열리지 않았다. 아래 범위 조정에서 경로 표기로 바꿨다.
- `python3 scripts/check_pr.py --base origin/main --worktree`: PASS(일반 4개/474줄, 범위 조정 전).

**범위 조정 반영(README 낡은 문장과 볼트 링크).**
- 기술 스택 표를 `build.gradle.kts`에 맞췄다. H2를 지우고 Spring Modulith, Spring Security, Spring Session Data Redis, Kotlin JDSL, Flyway, Redis, Kafka, kotlin-logging, Testcontainers·Fixture Monkey·REST Docs·ArchUnit을 더했다. Modulith는 버전을 적지 않았다. #94가 2.1.1을 2.1.2로 올리므로 적으면 그 머지와 함께 낡는다.
- 표 아래 문단: "도메인 구현 미완료"와 "Kafka 미도입"을 지우고, Redis 용도를 분산 락(fail-open)과 점주 세션·로그인 실패 카운터 둘로 적었다(`docker/docker-compose.yaml`의 redis 주석, [관측성](../wiki/observability.md)). 볼트 링크였던 "구현 현황"은 추적되는 [관측성](../wiki/observability.md) 링크로 바꿨다.
- `make up`과 로컬 실행 표: compose의 profile 없는 서비스가 PostgreSQL 18·Redis 8·Kafka 4.1이라 셋을 적었다. Testcontainers도 셋을 띄운다(`PostgresIntegrationTest`). `Makefile`의 `up` 설명도 같이 고쳤다.
- TDD 절: Fixture Monkey·REST Docs는 이미 빌드에 있어 "최초 기능 적용 PR에서 설치" 문장을 지웠다.
- Engineering Principles: 모듈 목록에 `owner`·`store`·`terminal`을 더했다. Kafka 문장을 "모듈 사이 이벤트는 커밋 뒤 Kafka로, 재시도·DLT는 브로커 쪽"으로 바꾸고 이유는 [도메인 모델 §8](../wiki/domain-model.md#8-참고-사례와-다르게-가는-결정) 링크로 두었다. Redis 용도에 점주 세션 저장을 더했다([operator-authentication](2026-10-06-operator-authentication.md)에서 정한 것).
- 볼트 링크: `README.md` 1건과 `docs/wiki/README.md` 13건을 경로 표기로 바꾸고 wiki 인덱스 머리에 이유 한 줄을 두었다. 새 문서 포함 네 파일의 상대 링크와 앵커 위반이 0건이다.
- 확인: 위 낡은 문장(`H2`, "현재 의존성에는 없", "아직 완료되지 않았", "PostgreSQL을 띄웁니다", "로컬 PostgreSQL 기동", "최초 기능 적용 PR", "실제로 생길 때만 도입")을 `grep`해 남은 것이 없다. `python3 -m unittest discover -s scripts -p 'test_*.py'` 132개 통과, `make help`의 `up` 설명이 바뀐 것을 확인했다. `check_pr.py --base origin/main`은 `--worktree` 있는 실행과 없는 실행 모두 PASS다(일반 5개).

**범위 조정 2 반영(남은 볼트 링크).**
- `AGENTS.md` 9건, `docs/wiki/development-workflow.md` 11건, `docs/wiki/borrowed-workflows.md`·`docs/wiki/entity-map.md` 각 2건, `docs/wiki/testing-strategy.md` 1건을 "이름(저장소 루트 기준 경로)" 형식으로 바꿨다. `current-state.md`·`modulith-kafka-keywords.md`에는 wiki 인덱스와 같이 "볼트"를 붙였다. `entity-map.md`의 괄호 안 링크 한 곳은 괄호가 겹치지 않게 문장을 고쳤다.
- 확인: 과거 spec과 `docs/references/`를 뺀 추적 Markdown 전체에서, 추적되지 않는 파일을 가리키는 상대 링크는 그때 제외한 `docs/wiki/domain-model.md` 3건만 남았다. `python3 -m unittest discover -s scripts -p 'test_*.py'` 132개 통과, `check_pr.py --base origin/main --worktree` PASS(일반 10개).
- 과거 spec 45개의 링크 103건은 기록이라 두었다.

**범위 조정 3 반영(domain-model 링크).**
- `docs/wiki/domain-model.md` 16·311·323번째 줄의 `current-state.md` 링크 3건을 같은 형식으로 바꿨다. 문장은 바꾸지 않았다.
- #94 head(`512298b`)와 `git merge-tree --write-tree`로 시험 merge했고 충돌이 없었다.
- 확인: 과거 spec과 `docs/references/`를 뺀 추적 Markdown 전체에서 추적되지 않는 파일을 가리키는 상대 링크가 0건이다.

**코드와 어긋난 문서 문장(고치지 않음, 후속 PR 후보).**
- [도메인 모델](../wiki/domain-model.md) §4.6은 "허용되는 동기 호출은 `sync` → `order`/`payment`의 공개 Command Facade뿐"이라고 적는다. 코드에서 `sync`는 `payment`를 부르지 않고(같은 절 본문도 `createAttempt`가 sync 경로에 없다고 적는다), 이 절이 다루지 않는 `terminal → store` 동기 호출(`TerminalOwnerController`의 `StoreManagement.requireOwned`, 매장 소유 확인 읽기)이 있다.
- 도메인 모델 §2는 `contract`가 "다른 업무 모듈의 타입을 들지 않는다(식별자를 raw `UUID`로 든다)"고 적는다. 코드의 세 이벤트는 `StoreId`·`TerminalId`를 든다. `OrderPaid` KDoc이 근거(`contract`에 의존하지 않는 모듈의 타입이라 순환이 없다)를 적고 있고, `ModuleStructureTest`도 `store`·`terminal`·`owner`를 금지 목록에 두지 않는다. 문장이 코드보다 넓다.
- 도메인 모델 §1의 "지금 저장소에 있는 타입은 `foundation`의 `Money`·`IdentifierGenerator`뿐"은 낡았다.
