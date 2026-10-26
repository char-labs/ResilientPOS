# PR spec: 매장 메뉴를 서버 정본으로 두고 단말이 받아 오프라인에서도 쓴다

## 목적
매장 메뉴의 정본이 없다. 시연 단말은 메뉴를 `index.html`에 박아 두고(295~308행) 그 값으로 판다. 점주가 아메리카노 가격을 4,000원에서 4,500원으로 바꿀 방법이 없고, 같은 매장의 단말끼리 같은 메뉴를 본다는 보장도 없다. 원문에는 메뉴 요구가 없고 `t_product`를 두지 않는다는 결정([order-line-blank-product](2026-09-21-order-line-blank-product.md))이 있다. 그 결정은 그대로다. 주문 line은 판매 시점의 상품 코드·이름·단가를 기록하고 서버는 line을 메뉴와 대조하지 않는다.

조회 영역은 Phase 넷으로 나눴고 Phase마다 PR 하나다(사용자 결정, 2026-10-26). 1 주문 조회(동시 진행), 2 상태 가시성과 폴링 계약, 3 메뉴(이 PR), 4 측정과 튜닝.

기대 결과:
- 점주가 `PUT /owner/v1/stores/{storeId}/menu`로 메뉴 전체를 저장하면 그 매장 단말이 다음 연결 때 `GET /v1/menu`로 새 메뉴를 받는다.
- 단말은 받은 메뉴를 사본으로 두고, 연결이 끊긴 동안은 그 사본으로 판다. 바뀌지 않았으면 서버는 304로 본문 없이 답한다.
- 다른 화면에서 먼저 저장된 메뉴를 옛 화면으로 덮어쓰지 못한다.

## 범위
소유 모듈은 새 `catalog`다. 의존 방향은 `catalog → store`(소유 확인, `StoreId`), `catalog → terminal`(`AuthenticatedTerminal`), `catalog → owner`(`OwnerId`)이고 `catalog`를 참조하는 모듈은 없다. `order`는 메뉴를 모른다.

**결정 1: 메뉴 항목은 코드·이름·가격·분류 넷이다(사용자 결정).**
- 시연 단말은 코드·이름·가격·분류 탭·색을 쓴다. 색은 16개 중 5개에만 있는 장식이라 서버에 두지 않고 단말이 기본색으로 그린다. "즐겨찾는 메뉴" 탭은 다른 탭 항목을 다시 모은 목록이라, 단말이 모든 항목을 보이는 "전체" 탭으로 바꾼다. 한 상품은 분류 하나에 속한다.
- 표시 순서는 항목 배열 순서, 탭 순서는 분류가 배열에서 처음 나온 순서다. 정렬 값을 따로 두지 않는다.

**결정 2: 매장당 한 행, 메뉴는 JSON 문서 하나다(사용자 결정).**
- `t_menu(store_id PK, menu jsonb, version)`. `menu`는 `{currency, items:[{code, name, price, category}]}`다.
- 점주는 메뉴 전체를 교체한다. 코드 중복과 항목 상한은 문서 하나를 검사하는 도메인 규칙이고, 표시 순서는 배열이 지킨다.
- 버린 것: 항목마다 행(`t_menu_item`). 매장 안 코드 유일을 DB unique로 걸 수 있지만 정렬 컬럼, 삭제 항목의 soft delete와 partial unique, 여러 행을 바꾸는 동시 수정의 별도 잠금이 필요하다. 행 모델에서 `max(updated_at)`과 개수로 만든 ETag는 늦게 커밋된 수정이 더 이른 시각을 가질 때 바뀌지 않아 단말이 영영 304를 받는다(`updated_at`은 JVM 시각이라 pod 사이에 단조가 아니다).
- 치르는 것: 저장소 첫 jsonb 매핑이다. SQL로 항목별 조회·제약을 걸 수 없다. 서버가 항목을 하나씩 읽는 경로가 생기면(line 대조, 재고) 그 PR이 모양을 다시 정한다.

**결정 3: 가격의 통화는 메뉴 문서에 하나 둔다(사용자 결정).**
- 모든 가격이 그 통화다. 가격은 `foundation.Money` 규칙(지원 통화, 통화 소수 자릿수, 정수부 15자리)과 0 이상을 지킨다. 단말은 이 값을 주문 `currency`로 쓴다.
- 매장 통화 정책이 `store`에 생기면 메뉴 통화와 두 곳이 되므로 그 PR이 정리한다.

**결정 4: 저장 형식은 adapter가 Spring의 `JsonMapper`로 만든다.**
- 엔티티의 `menu` 필드는 `String`이고 `@JdbcTypeCode(SqlTypes.JSON)`로 jsonb에 매핑한다. 직렬화·역직렬화는 persistence adapter가 Spring `JsonMapper` 빈으로 한다(선례 `DeadLetterReader`·`DeadLetterRedriver`). Hibernate가 어떤 JSON 라이브러리를 찾는지와 저장 형식을 떼어 두고, 요청 경로와 같은 Kotlin 모듈·constraints를 쓰려는 것이다. 도메인은 Jackson을 import하지 않는다(`DependencyDirectionTest`).
- 저장하는 것은 검증을 통과한 도메인 메뉴를 다시 직렬화한 문서다. 요청 원문을 그대로 저장하지 않으므로 모르는 필드는 남지 않는다.
- 필드 이름(`currency`, `items`, `code`, `name`, `price`, `category`)이 저장 형식이다. 리터럴 JSON으로 고정하는 테스트를 둔다. 나중에 더하는 필드는 기본값을 둔다. 없으면 저장된 문서를 읽지 못해 GET이 500이 된다.
- `price`는 응답과 문서 모두 통화 자릿수의 decimal string이다(`"4500"`, 기존 응답 금액과 같다. `RequestCheckoutResponse`). JS 숫자가 정수부 15자리와 소수를 정확히 담지 못하기 때문이다. 시연 단말은 화면 계산에서만 `Number()`로 바꾼다.

**결정 5: 점주 API는 전체 조회와 전체 교체이고, 동시 수정은 version으로 막는다.**
- ETag 값은 `"매장ID.version"`이다(예: 따옴표 안에 `0199….3`). version만 쓰면 모든 매장의 첫 메뉴가 version 0이라, 다른 매장 사본을 가진 기기가 304를 받아 남의 상품명·가격으로 판다(서버는 line을 대조하지 않는다).
- `GET /owner/v1/stores/{storeId}/menu`: 메뉴와 `ETag`. 메뉴가 없으면 404 `MENU_NOT_FOUND`이고 ETag가 없다.
- `PUT /owner/v1/stores/{storeId}/menu`: 본문은 `{currency, items}`. 처음 만들 때는 `If-None-Match: *`, 고칠 때는 받은 ETag를 `If-Match`에 싣는다. 응답은 저장된 메뉴와 새 `ETag`다.
- 조건 헤더 규칙(사용자 결정, HTTP 상태):
  - 두 헤더가 모두 없거나 둘 다 있으면 400 `MENU_PRECONDITION_REQUIRED`다(`INVALID`). 옛 화면이 마지막 저장을 조용히 덮어쓰지 못하게 하려는 것이다.
  - `If-Match`는 strong ETag 하나만 받는다. 값이 지금 ETag와 다르거나, `*`·weak·여러 값·해석할 수 없는 값이거나, 메뉴가 없으면 409 `MENU_VERSION_MISMATCH`다(`CONFLICT`). `If-None-Match`는 `*`만 받고, 메뉴가 이미 있거나 `*`가 아니면 같은 409다.
  - RFC 9110 §13.1.1은 If-Match 실패에 412를 요구한다. `DomainErrorKind`는 HTTP 숫자가 아니라 업무 의미이고(`DomainException.kt`), 저장소의 다른 버전 충돌도 409로 나간다. 소비자가 점주 화면 하나라 412에 기대는 중간 캐시가 없어 이 이탈을 받아들인다.
- 저장 절차:
  - 비교와 저장은 한 `Tx.masterTx` 블록 안에서 한다. 그 블록이 찾은 관리 엔티티의 version과 `If-Match`를 비교하고 같은 엔티티를 바꾼다. 비교용 읽기를 따로 하면 `@Version`이 지키는 것이 두 번째 읽기 값이 되어 옛 화면이 새 저장을 덮어쓴다.
  - 수정은 내용이 같아도 version을 한 번 올린다(`LockModeType.OPTIMISTIC_FORCE_INCREMENT`로 찾는다. dirty 변경과 겹쳐도 한 번만 오르는지 테스트로 확인한다). 응답 version은 "읽은 값 + 1"이다. 생성 응답은 0이다. 응답 ETag가 DB와 어긋나면 점주 화면의 다음 저장이 계속 409가 된다.
  - 엔티티는 `@Version var version: Long? = null`이고 생성은 `EntityManager.persist`다. version이 0으로 시작하면 Spring Data `save`가 merge로 가서, 생성 경합의 늦은 쪽이 먼저 커밋된 행을 읽고 덮어쓴다.
  - 경합의 마지막 방어선은 DB다. 수정 경합은 `@Version`의 조건부 UPDATE가, 생성 경합은 PK `t_menu_pkey`가 막는다. Facade가 `Tx` 블록 밖에서 `OptimisticLockingFailureException`과 `isUniqueViolationOf("t_menu_pkey")`를 잡아 둘 다 `MENU_VERSION_MISMATCH`로 바꾼다(선례 `OrderCommandFacade.create`). 바꾸지 않으면 advice가 `CONCURRENT_REQUEST`로 낸다.
- 삭제는 PUT에서 빼는 것이다. 과거 주문 line은 판매 시점 값이라 그대로다. 메뉴 자체를 지우는 명령은 두지 않는다.
- 매장 소유 확인은 `StoreManagement.requireOwned`(없는 매장과 남의 매장 모두 404 `STORE_NOT_FOUND`, 선례 `TerminalOwnerController`), CSRF와 세션은 기존 `OwnerSecurityFilterChain`이 맡는다. 저장이 커밋되면 Controller가 감사 로그 한 줄(`audit action=MENU_REPLACED ownerId storeId version items`)을 남긴다.

**결정 6: 단말 API는 `GET /v1/menu` 하나이고 ETag로 바뀜을 알린다.**
- 매장은 토큰의 `AuthenticatedTerminal.storeId`다. 경로에 매장을 받지 않아 단말은 자기 매장 메뉴만 본다.
- Controller는 `ResponseEntity`에 `ETag`와 `Cache-Control: no-cache, private`를 실어 돌려주고, 304 판정은 Spring의 조건부 요청 처리에 맡긴다(`If-None-Match`의 여러 값·weak 비교·`*`를 직접 파싱하지 않는다). 304는 본문이 없어야 하고(`ApiResponseBodyAdvice`가 envelope를 씌우지 않는지), `ETag`와 `Cache-Control`이 남아야 한다(RFC 9110 §15.4.5). 둘 다 MockMvc로 단언한다.
- 메뉴가 없으면 404 `MENU_NOT_FOUND`다.
- 304여도 PK 행 하나를 읽는다. Redis·pod 로컬 캐시는 두지 않는다. 원문은 DB read 병목을 측정했을 때 캐시를 들이라 하고(청사진 §7.1), 측정은 Phase 4다. DB가 정본이라 pod가 여럿이어도 무효화 문제가 없다.
- 기존 인터셉터 규칙을 그대로 받는다. 빈도 상한 token을 하나 쓰고, GET이라 동시 상한 자리는 잡지 않는다.
- 치르는 것: `/v1/menu`가 단말 API 오류율 SLO의 분모(`uri=~"/v1/.*"`)에 들어가 sync·주문의 5xx 비율을 묽힌다. 단말당 5분에 한 번이라 작다고 보고(추론) 규칙은 바꾸지 않는다. Phase 4에서 요청 비율을 잰다.

**결정 7: 검증은 주문 line과 같은 판정을 쓴다.**
- 코드 1~64자, 이름 1~255자(주문 line과 같다. 메뉴로 만든 line이 sync에서 영구 거절되지 않게), 분류 1~50자. `OrderLine`과 같은 판정이다. 글자 수는 code point로 세고, 공백은 Kotlin `isBlank()`(NBSP·U+3000 포함)로, NUL(U+0000)은 포함 여부로 본다. 공용 함수는 사용처가 둘뿐이라 만들지 않는다.
- 메뉴 안에서 코드가 겹치면 거절한다. 항목은 0~200개다(시연 메뉴는 16개).
- 통화는 항목 수와 무관하게 `Money.zero(currency)`로 검증한다. 항목이 0개여도 `XXX` 같은 통화가 저장되지 않는다. DTO는 통화를 `String`으로 받고 `Currency.getInstance`의 실패를 `INVALID_MENU`로 바꾼다(`Currency` 타입으로 받으면 역직렬화 실패라 `BAD_REQUEST`가 된다).
- 위반은 `DomainException(INVALID_MENU, 메시지, INVALID)` 하나로 던지고 메시지에 몇 번째 항목의 무엇인지 적는다. `Money`가 던지는 code는 그대로 나간다. 필수 필드가 빠지거나 null이면 주문과 같이 역직렬화 단계의 `BAD_REQUEST`다.

**결정 8: 시연 단말은 포스가 받아 사본으로 두고 키오스크에 넘긴다. 토큰 연동은 하지 않는다(사용자 결정).**
- 포스는 페이지를 열 때, 끊겼다 다시 연결될 때, 연결된 동안 5분마다 `GET /v1/menu`를 저장한 ETag의 `If-None-Match`와 `cache: 'no-store'`로 부른다(브라우저 캐시와 섞이지 않게). 200이면 사본을 바꾸고, 304면 그대로 쓴다. 실패(네트워크, 401, 404, 5xx)면 남은 사본을 쓰고, 사본이 없으면 내장 시연 메뉴를 쓴다.
- 사본은 단말 상태와 다른 localStorage 키(`rpos.menu.v1`)에 `{etag, currency, items}`로 둔다. 받은 본문은 모양(문자열 타입, 가격 decimal string, 개수 상한)을 확인한 뒤에만 사본으로 쓴다.
- 분류 탭은 배열이나 `Map`으로 만든다. 지금처럼 객체 키(`Object.keys(MENU)`)로 두면 `"2"` 같은 정수형 분류가 앞으로 가고 `__proto__` 분류가 사라진다. 메뉴가 바뀌면 상품 표(`PRODUCT`)도 다시 만든다.
- 서버 값(상품명·분류·코드)을 화면에 넣는 모든 자리를 `esc()`로 감싼다. 속성값도 포함한다. 이 PR부터 점주가 입력한 문자열이 포스와 키오스크 화면에 들어간다.
- 포스가 고객 단말 창에 보내는 상태에 메뉴를 싣는다. 키오스크는 그 메뉴로 그리고, 키오스크 주문을 받을 때 포스는 지금 사본에서 이름·가격을 다시 읽는다(`trustedLines`). 주문 `currency`도 사본의 통화다.
- 장바구니에 담긴 상품은 담을 때 값이다. 그 사이 메뉴가 바뀌어도 line은 판매 시점 값이라 서버가 대조하지 않는다.
- 시연 단말은 아직 토큰을 싣지 않아 실서버에서는 401이고 내장 메뉴로 판다([terminal-demo-ui](2026-10-05-terminal-demo-ui.md) 제외 절). 받기·304·실패 동작은 가짜 서버로 확인한다(검증 절).

## 제외
- 주문 조회(Phase 1), 결제 상태 표시와 폴링 규약(Phase 2), 캐시와 조회 측정(Phase 4).
- 재고, 판매 정책, 옵션, 품절 표시, 상품 이미지, 색 같은 표시 속성.
- 서버의 line·메뉴 대조. 단말이 보낸 이름·가격을 그대로 기록한다(사용자 결정).
- 항목별 CRUD, 메뉴 삭제, 메뉴 변경 이력.
- 시연 단말의 토큰 인증.
- 메뉴 변경을 단말에 밀어 주는 push. 단말이 받으러 온다.
- 한계: DB를 백업으로 되돌려 version이 내려가면, 같은 version을 가진 사본이 다른 내용인데도 304를 받을 수 있다. 복원 절차가 생기면 그 절차가 메뉴 version을 올린다.

## 관련 문서
없음

## 변경 경로
- src/main/java/com/resilientpos/catalog/**
- src/main/kotlin/com/resilientpos/catalog/**
- src/main/resources/db/migration/V021__catalog_menu.sql
- src/main/resources/static/terminal/index.html
- src/test/kotlin/com/resilientpos/catalog/**
- src/test/kotlin/com/resilientpos/ModuleStructureTest.kt
- src/test/kotlin/com/resilientpos/SchemaMigrationTest.kt
- src/docs/asciidoc/**
- docs/spec/2026-10-26-menu-catalog.md
- docs/wiki/**
- README.md

## 인수 조건
1. 점주가 `If-None-Match: *`로 PUT하면 메뉴가 생기고 응답 ETag의 version은 0이다. 같은 매장 단말의 `GET /v1/menu`가 그 메뉴를 같은 ETag로 돌려준다.
2. 받은 ETag를 `If-Match`에 실어 가격을 바꾸면 응답 ETag가 이어지는 GET의 ETag와 같다(실제 DB로 확인). 같은 내용을 다시 PUT해도 version이 하나 오른다.
3. 단말이 옛 ETag로 `If-None-Match`를 보내면 200과 새 가격, 지금 ETag로 보내면 304이고 본문이 없으며 `ETag`·`Cache-Control`이 있다. 다른 매장의 같은 version ETag로 보내면 200이다.
4. 옛 ETag로 다시 PUT하면 409 `MENU_VERSION_MISMATCH`이고 저장된 메뉴는 그대로다. "읽기 → 다른 트랜잭션 커밋 → 저장" 순서로 만든 수정 경합과 생성 경합 모두 `MENU_VERSION_MISMATCH`다.
5. 조건 헤더가 없거나 둘 다 있는 PUT은 400 `MENU_PRECONDITION_REQUIRED`다. `If-Match`가 `*`·weak·여러 값·해석할 수 없는 값이면 409 `MENU_VERSION_MISMATCH`이고 500이 아니다.
6. 남의 매장·없는 매장의 GET·PUT은 404 `STORE_NOT_FOUND`이고, CSRF 토큰 없는 PUT은 403이며, 로그인하지 않은 요청은 401이다.
7. 단말은 경로로 매장을 고를 수 없고 자기 매장 메뉴만 받는다. 메뉴가 없으면 404 `MENU_NOT_FOUND`, 토큰이 없으면 401이다.
8. 코드·이름·분류의 길이(서로게이트 쌍은 한 글자), 공백만 있는 값(NBSP·U+3000 포함), NUL, 코드 중복, 항목 201개, 모르는 통화(항목 0개 포함, 소문자 포함)는 400 `INVALID_MENU`, 음수 가격은 `INVALID_MENU`, 통화 자릿수를 넘는 가격은 `Money`의 code이고 저장되지 않는다.
9. 저장된 jsonb 문서가 리터럴 JSON(`currency`, `items[].code/name/price/category`, `price`는 decimal string)과 같다.
10. 주문 생성·sync는 메뉴가 있든 없든, 메뉴와 다른 이름·가격이든 지금처럼 동작한다.
11. 시연 단말: 서버 메뉴를 받아 탭과 타일에 그리고, 304면 사본을 유지하며, 받기가 실패하면 남은 사본(없으면 내장 메뉴)으로 판다. 키오스크 창이 포스와 같은 메뉴를 그리고, 키오스크 주문의 이름·가격·통화가 포스 사본에서 온다. img 태그와 onerror 속성이 든 상품명이 글자로 보인다. `?selftest`가 통과한다.

## 스키마 변경 검토
`V021__catalog_menu.sql`이 새 표 `t_menu` 하나를 만든다.

```
create table t_menu (
    store_id uuid not null,
    menu jsonb not null,
    version bigint not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz,
    constraint t_menu_pkey primary key (store_id)
);
```

- 질문 1(도출): 메뉴는 PostgreSQL 어디에도 없다. `t_order_line`은 판매된 값의 기록이라 메뉴가 아니다(팔리지 않은 상품, 바뀐 가격을 모른다). `store_id`는 `t_store.id`를 가리키는 참조이고 FK는 두지 않는다(도메인 모델 규칙). `version`은 `updated_at`으로 대신할 수 없다. JVM 시각이라 pod 사이에 단조가 아니다.
- 질문 2(읽는 코드): `store_id`는 두 API의 조회 키, `menu`는 두 API의 응답, `version`은 ETag와 `If-Match` 비교가 읽는다. 감사 시각 셋과 `deleted_at`은 `BaseEntity` 공통 컬럼이다(`@SoftDelete`라 validate가 `deleted_at`을 요구한다).
- 질문 3(나중에 채우기): 버린 값과 다시 볼 조건. 메뉴 변경 이력(이력 조회나 감사 요구가 생길 때), 항목별 색·이미지·품절(단말이 그 값을 읽을 때, 문서 안 필드라 기본값을 두고 컬럼 없이 더한다), 별도 `currency` 컬럼(store 통화 정책이 생길 때).
- 질문 4(마지막 방어선): PK `t_menu_pkey`가 "매장당 메뉴 하나"와 생성 경합의 방어선이다. 지우는 명령이 없고 삭제 행에도 걸린다. 그래서 이 표의 행은 SQL로도 soft delete하지 않는다. 삭제된 행이 있으면 조회는 없다고 보고 생성은 PK에 걸려 그 매장은 메뉴를 다시 만들지 못한다. `version`(`@Version`)이 수정 경합의 방어선이다. 코드 중복과 항목 상한은 문서 하나를 통째로 검사하는 도메인 규칙이라 DB 제약이 없다(교체가 문서 단위라 부분 갱신으로 규칙을 우회하는 경로가 없다).
- 질문 5(정본): 메뉴의 정본은 이 행 하나다. 단말 localStorage 사본은 사본이고, 복구 경로는 다음 받기(ETag가 다르면 200)다. ETag에 매장 ID를 넣어 다른 매장의 사본이 맞는 것으로 보이지 않게 한다. 주문 line의 이름·가격은 사본이 아니라 판매 시점 기록이라 메뉴와 어긋나도 맞다.
- 질문 6(check-then-act): PUT은 version을 읽고 비교한 뒤 쓴다. 비교와 쓰기가 한 트랜잭션의 같은 관리 엔티티라 `@Version`의 `where version = ?` 조건부 UPDATE가 비교한 값을 지킨다. 수정은 내용이 같아도 version을 올린다. 생성은 PK가 막는다. 루트 한 행만 바꾸므로 `@Version`이 실제로 그 행을 UPDATE한다. 한 행 한 쿼리로 읽으므로 READ COMMITTED에서 서로 다른 커밋이 섞이지 않는다.
- 질문 7(락·크기): 새 표 생성이라 기존 표를 잠그지 않는다.
- 질문 8(이전 버전): 이전 버전은 새 표를 매핑하지 않아 `validate`가 통과하고 쓰지도 않는다.
- 질문 9(이벤트·캐시): 이벤트를 내지 않는다. 응답은 HTTP 조건부 요청으로만 재사용되고 서버 캐시는 없다. version이 바뀌면 ETag가 바뀌므로 무효화가 따로 없다. jsonb 문서의 필드 이름이 저장 형식이라 리터럴 JSON 테스트로 고정한다.
- 대조 기록: (리뷰어가 구현 뒤 채운다)

## 검증
- `./gradlew test` 전체. 범위를 좁히면 `./gradlew test --tests 'com.resilientpos.catalog.*' --tests 'com.resilientpos.ModuleStructureTest' --tests 'com.resilientpos.SchemaMigrationTest'`.
- Controller는 MockMvc와 REST Docs, fixture는 Fixture Monkey. 경합은 `OrderVersionConflictIntegrationTest`처럼 "읽기 → 다른 트랜잭션 커밋 → 저장" 순서로 결정적으로 만든 PostgreSQL 통합 테스트로 확인한다. 응답 ETag와 DB version의 일치도 통합 테스트로 본다.
- 시연 단말: `?selftest`에 메뉴 사본 검증, 분류 순서, 메뉴 교체 뒤 `trustedLines`의 새 가격, 키오스크 메뉴 수신, 상품명 escape를 더한다. 받기는 #120과 같은 방식으로 확인한다. Python 표준 라이브러리 가짜 서버가 `src/main/resources/static`을 서빙하고 `GET /v1/menu`에 REST Docs 스니펫과 같은 envelope 모양으로 정해 둔 순서(200 → `If-None-Match` 일치 304 → 가격을 바꾼 200 → 401 → 연결 끊김)로 답하며 요청의 `If-None-Match`를 남긴다. 헤드리스 Chrome으로 `?mock` 없이 열어 화면 타일 가격과 localStorage 사본을 단계마다 읽는다. 스크립트는 scratchpad에 두고 커밋하지 않는다. 고치기 전 HEAD에서도 돌려 지금은 `GET /v1/menu`를 부르지 않는 것을 본다.
- 개발 중 `python3 scripts/check_pr.py --base origin/main --worktree`, 제출 전 `--worktree` 없이 한 번 더.

## Wiki 영향
- `docs/wiki/domain-model.md`: 용어집에 메뉴(menu)·메뉴 항목, §2 Bounded context 표에 `catalog`, §4.7 사례에 `t_menu`. Phase 1 PR과 절이 겹치지 않게 한다(그쪽은 주문 조회 관련).
- `docs/wiki/architecture.md`: 모듈 목록과 의존 방향에 `catalog`.
- `docs/wiki/entity-map.md`: `MenuEntity` 절, `OrderLineEntity` 절의 "상품 마스터 표가 없으므로"를 "메뉴는 있지만 line은 메뉴와 대조하지 않는 판매 시점 원본"으로.
- 시연 단말 관련 wiki가 있으면 메뉴 정본이 서버로 바뀐 것을 반영한다.
- README.md의 모듈 목록에 `catalog`가 있으면 더한다.

## 분할 계획
- 이 PR은 메뉴 하나의 컨텍스트다. 주문 조회(Phase 1, `feature/order-query`)와 동시에 진행한다. 겹칠 수 있는 곳은 마이그레이션 번호(V021), `SchemaMigrationTest`, `docs/wiki/domain-model.md`다. push 전에 origin/main과 그 브랜치의 마지막 번호를 확인하고 나중에 머지하는 쪽이 번호를 바꾼다. domain-model.md는 서로 다른 절을 고친다.
- 2026-10-26 확인: origin/main bb14c86, 열린 PR 없음, `feature/order-query`는 아직 push 전. main과 열린 PR에 ETag·조건부 요청 처리 헬퍼가 없다.

## 설계 검토 반영
spring-backend-specialist가 커밋 전에 검토했다(P0 없음). 반영한 것: PUT 응답 ETag를 같은 트랜잭션의 관리 엔티티 version에서 얻고 수정마다 version을 올린다(P1), ETag에 매장 ID를 넣는다(P1), 304는 Spring 조건부 처리에 맡기고 envelope가 붙지 않는지 단언한다, 조건 헤더 문법과 둘 다 있을 때, `Long? = null` version·`persist`·PK 이름 명시와 Facade의 경합 번역, 주문 line과 같은 판정(`isBlank`·NUL·code point), 시연 단말의 escape·분류 순서·`PRODUCT` 재생성·`no-store`, price decimal string, 통화 검증의 빈틈(항목 0개, `String` DTO), soft delete 금지, `SchemaMigrationTest` 경로, SLO 분모 영향. HTTP 상태는 검토 추천대로 409·400으로 정했다(사용자 결정). jsonb는 `String` 필드 + adapter의 `JsonMapper`, 수정 경합은 `@Version`으로 정했다.

## TDD 검증 기록

| 동작 / 테스트 이름 | RED 명령·실패 이유 | GREEN 명령·결과 | 리팩터링 후 회귀 명령·결과 |
|---|---|---|---|
| 개발 전에 테스트 의도를 적고 실행 후 관측 결과를 기록 | 미실행 | 미실행 | 미실행 |

## 결과
개발 전 작성.
