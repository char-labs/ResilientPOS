-- order 모듈: 같은 (terminal_id, client_order_id) 재요청의 payload 동등성 판정에 쓰는 최초 요청 해시.
-- V003 이전에 저장된 행은 최초 요청을 알 수 없어 null이고, null이면 판정에서 비교하지 않는다.
alter table t_order add column payload_hash varchar(64);
