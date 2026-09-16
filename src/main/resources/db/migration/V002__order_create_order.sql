-- order 모듈: CreateOrder 영속화.
-- 합계 컬럼은 두지 않는다. 파생 값이다.

create table t_order (
    id uuid primary key,
    store_id uuid not null,
    terminal_id uuid not null,
    client_order_id uuid not null,
    currency char(3) not null,
    status varchar(32) not null,
    version bigint not null,
    unique (terminal_id, client_order_id)
);

create table t_order_line (
    order_id uuid not null references t_order(id),
    line_no integer not null,
    product_code varchar(64) not null,
    product_name varchar(255) not null,
    unit_price numeric(19,4) not null,
    quantity integer not null,
    primary key (order_id, line_no)
);
