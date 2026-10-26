-- order: 점주 주문 목록의 status 필터 조회에 쓴다. 상태 없는 조회는 이 인덱스로 시각순을 낼 수 없어 ix_order_store_occurred가 계속 필요하다.
-- 같은 데이터에서 1건뿐인 상태를 기간 없이 읽으면 store_id 인덱스만으로 76ms(4만 행 Bitmap Heap Scan), 이 인덱스로 0.03ms였다(order-query spec).
-- CONCURRENTLY라 트랜잭션 밖에서 돌아야 해서 이 문장만 담는다(도메인 모델 §4.7 질문 7).
create index concurrently ix_order_store_status_occurred on t_order (store_id, status, occurred_at, id);
