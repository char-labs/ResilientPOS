-- catalog 모듈: t_menu는 매장 메뉴의 정본이고 매장당 한 행이다. 메뉴는 {currency, items:[{code, name, price, category}]} 문서 하나(jsonb)라
-- 코드 중복과 항목 상한은 문서를 통째로 검사하는 도메인 규칙이고 DB 제약이 없다. 문서의 필드 이름이 저장 형식이다.
-- store_id는 t_store.id를 가리키는 참조이고 도메인 모델 규칙대로 FK를 두지 않는다.
-- t_menu_pkey가 "매장당 메뉴 하나"와 생성 경합의 마지막 방어선이고, version(@Version)이 수정 경합의 방어선이다.
-- 지우는 명령이 없고 삭제 행에도 PK가 걸리므로 이 표의 행은 SQL로도 soft delete하지 않는다.
-- 새 표라 기존 표에 락을 잡지 않는다. 이전 버전은 새 표를 매핑하지 않아 validate가 통과하고 쓰지도 않는다.

create table t_menu (
    store_id uuid not null,
    menu jsonb not null,
    version bigint not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz,
    constraint t_menu_pkey primary key (store_id)
);
