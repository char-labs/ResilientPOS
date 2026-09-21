-- order 모듈: 자식 테이블의 낙관적 잠금 version 컬럼을 없앤다.
-- 낙관적 잠금은 aggregate 루트인 t_order만 쓴다. t_order_line은 루트를 통해서만 바뀌고 t_order_checkout은 넣기만 한다.
-- DROP COLUMN은 테이블을 재작성하지 않지만 ACCESS EXCLUSIVE를 잡는다. 긴 트랜잭션 뒤에서 무기한 기다리지 않게 한다.

-- 마이그레이션은 배포 중 한 번 돌고 재실행할 수 있으므로 요청 경로의 주문 행 잠금(3초)보다 길게 잡는다.
set local lock_timeout = '5s';

alter table t_order_line drop column version;
alter table t_order_checkout drop column version;
