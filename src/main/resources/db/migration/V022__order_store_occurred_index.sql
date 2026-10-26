-- order: 점주 주문 목록(매장의 주문을 occurred_at desc, id desc keyset으로 한 페이지씩 읽는 조회)에 쓴다.
-- 오름차순 인덱스의 역방향 스캔이 정렬을 내고 keyset의 시각 상한이 범위 조건이 된다.
-- 주문 1,000만 건(250매장 x 4만 x 90일)에서 필터 없는 첫 페이지가 인덱스 없이 240ms, 있으면 0.25ms였다(order-query spec).
-- CONCURRENTLY라 트랜잭션 밖에서 돌아야 해서 이 문장만 담는다(도메인 모델 §4.7 질문 7).
create index concurrently ix_order_store_occurred on t_order (store_id, occurred_at, id);
