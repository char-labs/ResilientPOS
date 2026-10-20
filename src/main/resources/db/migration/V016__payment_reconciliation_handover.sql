-- payment 모듈: 조회로도 확정되지 않는 시도를 reconciliation case로 넘기기 위한 컬럼 셋.
-- query_count는 상한 판정과 넘김 사실이, unknown_reason은 넘김 사실과 case 근거가, reconciliation_requested_at은 주기 작업의 대상 조회와 확정 허용 판정이 읽는다.
-- 도출할 수 있는 값이 없어 컬럼으로 둔다(조회 횟수와 금액 불일치 여부는 어디에도 저장되지 않는다).
-- add column 셋 모두 메타데이터 변경이다(query_count는 상수 기본값, 나머지는 nullable). 이전 버전의 INSERT는 기본값 덕에 실패하지 않는다.
-- unknown_reason은 기존 UNKNOWN 행만 RESULT_UNCONFIRMED로 채운다. 이 변경 전에는 금액을 대조하지 않아 불명 사유가 그것 하나였다.
-- update의 술어는 V013 partial 인덱스의 술어와 같아야 그 인덱스가 대상 행만 고른다. status만 쓰면 alter가 잡은 ACCESS EXCLUSIVE를 쥔 채 원장 전체를 훑는다.
-- 긴 트랜잭션 뒤에서 락을 기다리며 뒤 쿼리를 세우지 않도록 lock_timeout을 둔다(V005·V008·V011·V014와 같다).

set local lock_timeout = '5s';

alter table t_payment_attempt
    add column query_count integer not null default 0,
    add column unknown_reason varchar(32),
    add column reconciliation_requested_at timestamptz;

update t_payment_attempt set unknown_reason = 'RESULT_UNCONFIRMED' where kind = 'AUTHORIZATION' and status = 'UNKNOWN';
