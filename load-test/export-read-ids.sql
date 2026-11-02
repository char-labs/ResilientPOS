-- S6 GET이 고를 주문 id. 매장마다 최근 1일 PAID 주문 recent개와 90일 무작위 PAID 주문 old개를 JSON 한 줄로 낸다.
-- 실행: psql -At -v recent=50 -v old=50 -f export-read-ids.sql > load-test/results/s6-order-ids.json
\set ON_ERROR_STOP on
select json_object_agg(store_id, json_build_object('recent', recent, 'old', old))
from (
  select s.id as store_id,
         (select coalesce(json_agg(id), '[]') from (select id from t_order where store_id = s.id and status = 'PAID' and occurred_at > now() - interval '1 day' order by random() limit :recent) r) as recent,
         (select coalesce(json_agg(id), '[]') from (select id from t_order where store_id = s.id and status = 'PAID' order by random() limit :old) a) as old
  from t_store s
) x;
