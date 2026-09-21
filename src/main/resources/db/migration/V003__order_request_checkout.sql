-- order 모듈: RequestCheckout 영속화. 결제 시도 회차별 확정 금액.
-- 통화 컬럼은 두지 않는다. t_order_line처럼 t_order.currency에서 읽는다.
-- DB FK를 두지 않는다. order_id의 무결성은 aggregate를 한 트랜잭션으로 저장하는 order 모듈이 지킨다.
-- unique (order_id, checkout_no)는 "한 주문에 같은 회차는 하나"의 마지막 방어선이고 주문별 조회 인덱스를 겸한다.

create table t_order_checkout (
    id uuid primary key,
    order_id uuid not null,
    checkout_no integer not null,
    amount numeric(19,4) not null,
    version bigint not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz,
    unique (order_id, checkout_no)
);
