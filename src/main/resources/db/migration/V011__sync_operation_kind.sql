-- t_sync_operation에 operation 종류를 더한다. 업무 코드는 읽지 않고 사람의 조사와 테스트가 읽는 기록이다.
-- 그래도 지금 더하는 이유는 두 종류가 섞인 뒤에는 기존 행의 종류를 다른 컬럼에서 알아낼 수 없어 나중에 채울 수 없기 때문이다.
-- 지금은 모든 행이 CREATE_ORDER라 상수 기본값이 정확한 값이다. 상수 기본값이라 테이블을 다시 쓰지 않는 메타데이터 변경이고,
-- ACCESS EXCLUSIVE를 잡는 동안 긴 트랜잭션 뒤에서 뒤 쿼리를 세우지 않도록 lock_timeout을 둔다.
-- 이전 버전 인스턴스는 kind를 적지 않고 INSERT하는데 기본값이 CREATE_ORDER를 채우고, 이전 버전은 그 종류만 만든다.

set local lock_timeout = '3s';

alter table t_sync_operation
    add column kind varchar(32) not null default 'CREATE_ORDER';
