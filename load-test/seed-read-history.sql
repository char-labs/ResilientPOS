-- S6 시드. 일회용 측정 PostgreSQL 전용이다. 공유 compose 스택의 DB에 넣지 않는다(load-test/README.md).
-- s6-prepare.js가 만든 매장마다 90일치 주문 4만 건(1%는 OPEN, 나머지는 PAID와 checkout·승인 시도), 주문마다 line 2개를 넣는다.
-- id는 occurred_at 시각의 UUIDv7이라 인덱스 지역성이 운영과 같다(PostgreSQL 18 uuidv7(interval)).
-- 실행: psql -v per_store=40000 -f seed-read-history.sql
\set ON_ERROR_STOP on
\if :{?per_store}
\else
  \set per_store 40000
\endif

begin;
create temp table seed_order on commit drop as
with store_terminals as (select store_id, array_agg(id order by id) as terminal_ids from t_terminal group by store_id)
select uuidv7(o.occurred_at - now()) as id, st.store_id, st.terminal_ids[1 + (o.n % 4)] as terminal_id,
       o.occurred_at, case when random() < 0.01 then 'OPEN' else 'PAID' end as status
from store_terminals st
cross join lateral (select n, now() - random() * interval '90 days' as occurred_at from generate_series(1, :per_store) n where st.store_id is not null) o;  -- 매장을 참조해 매장마다 다시 계산한다

insert into t_order (id, store_id, terminal_id, client_order_id, currency, status, version, created_at, updated_at, occurred_at)
select id, store_id, terminal_id, gen_random_uuid(), 'KRW', status, case status when 'PAID' then 2 else 0 end, occurred_at, occurred_at, occurred_at
from seed_order;

insert into t_order_line (id, order_id, line_no, product_code, product_name, unit_price, quantity, created_at, updated_at)
select uuidv7(o.occurred_at - now()), o.id, l.line_no, 'SKU-' || l.line_no, '아메리카노', 4500, 1, o.occurred_at, o.occurred_at
from seed_order o cross join generate_series(1, 2) l(line_no);

insert into t_order_checkout (id, order_id, checkout_no, amount, created_at, updated_at)
select uuidv7(occurred_at - now()), id, 1, 9000, occurred_at, occurred_at from seed_order where status = 'PAID';

insert into t_payment_attempt (id, order_id, store_id, terminal_id, checkout_no, kind, amount, currency, status, provider_reference, created_at, updated_at)
select uuidv7(occurred_at - now()), id, store_id, terminal_id, 1, 'AUTHORIZATION', 9000, 'KRW', 'APPROVED', 'seed-' || id, occurred_at, occurred_at
from seed_order where status = 'PAID';
commit;

vacuum analyze t_order, t_order_line, t_order_checkout, t_payment_attempt;
