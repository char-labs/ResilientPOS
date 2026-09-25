-- fulfillment 모듈: 결제된 주문 하나를 이행하는 단위. 원본 주문당 한 번만 만든다.
-- id와 order_id만 가진다. status·store_id·품목 snapshot을 읽는 명령이나 조회가 아직 없어서 두지 않는다.
-- 다시 볼 조건: status는 상수 기본값을 가진 컬럼 추가로 기존 행을 채운다. type(이행 방식)도 지금의 유일한 종류를 뜻하는 상수 기본값으로
-- 더하고 unique를 (order_id, type)으로 바꾼다. 행 수가 주문 수를 따라가므로 그때는
-- CREATE UNIQUE INDEX CONCURRENTLY로 나눈다. store_id 컬럼은 매장별 보드 조회를 만드는 PR이 더한다. 값은
-- OrderPaid.storeId로 이미 실려 오고 기존 행은 같은 DB의 t_order에서 채운다. 품목은 그 PR이 OrderPaid에
-- nullable(미상) 필드로 더하고 기존 행은 t_order_line에서 채운다. 빈 목록은 "품목 없음"과 구분되지 않아
-- 기본값이 될 수 없으므로, 그 전에 저장된 미완료·FAILED OrderPaid 기록을 배포 전에 비운다.
-- DB FK를 두지 않는다. order_id는 t_order.id를 가리키는 참조일 뿐 사본이 아니고,
-- fulfillment와 order는 서로의 테이블을 읽지 않는 별개 컨텍스트다(t_payment_attempt가 같은 선례다).

-- unique (order_id)는 멱등 생성의 마지막 방어선이다. Event Publication Registry의 완료 표시는
-- 리스너 커밋과 다른 트랜잭션이라 그 사이에 죽으면 재배달이 온다. 멱등 키이므로 soft delete된 행에도
-- 걸리는 전체 unique로 둔다. fulfillment를 지우는 명령이 생기면 멱등 조회가 삭제된 행도 보게 해야 한다.

create table t_fulfillment (
    id uuid primary key,
    order_id uuid not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz,
    constraint ux_fulfillment_order unique (order_id)
);
