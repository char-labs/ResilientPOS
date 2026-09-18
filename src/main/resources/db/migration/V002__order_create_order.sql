-- order 모듈: CreateOrder 영속화.
-- 합계 컬럼은 두지 않는다. 파생 값이다.
-- DB FK를 두지 않는다. t_order_line.order_id의 무결성은 aggregate를 한 트랜잭션으로 저장하는 order 모듈이 지킨다.

create table t_order (
    id uuid primary key,
    store_id uuid not null,
    terminal_id uuid not null,
    client_order_id uuid not null,
    currency char(3) not null,
    status varchar(32) not null,
    version bigint not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz,
    unique (terminal_id, client_order_id)
);

create table t_order_line (
    id uuid primary key,
    order_id uuid not null,
    line_no integer not null,
    product_code varchar(64) not null,
    product_name varchar(255) not null,
    unit_price numeric(19,4) not null,
    quantity integer not null,
    version bigint not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz
);

create index ix_order_line_order on t_order_line (order_id, line_no);
