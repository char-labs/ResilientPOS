-- terminal 모듈: 단말 코드. 운영자가 현장에서 단말을 부르는 이름이고 매장 안에서 유일하다.
-- 도출할 수 있는 값이 없어 컬럼으로 둔다. 기존 행은 매장별 created_at, id 순번으로 LEGACY-1, LEGACY-2 ...로 채운다.
-- ux_terminal_store_code는 "매장 안에서 코드 유일"의 유일한 방어선이다. 등록은 사전 조회 없이 INSERT만 하고
-- 위반은 persistence adapter가 이 제약 이름으로 가려 TERMINAL_CODE_DUPLICATED로 바꾼다. soft delete된 행에도 걸린다.
-- 인덱스의 앞이 store_id라 매장별 목록 조회도 탄다.
-- 한 트랜잭션이라 ADD COLUMN이 잡은 ACCESS EXCLUSIVE가 커밋까지 남고 그동안 t_terminal을 읽는 단말 인증이 기다린다.
-- UPDATE·SET NOT NULL·unique 생성은 행 수에 비례하지만 배포 환경에는 등록 입구가 없어 행이 거의 없다.
-- 긴 트랜잭션 뒤에서 락을 기다리며 인증을 세우지 않도록 lock_timeout을 둔다(V005·V008·V011과 같다).
-- 이전 버전은 이 컬럼을 매핑하지 않아 validate가 통과하고, 이전 버전의 register만 NOT NULL에 걸린다(HTTP 등록 입구가 없어 받아들인다).

set local lock_timeout = '5s';

alter table t_terminal add column terminal_code varchar(32);

update t_terminal t
set terminal_code = 'LEGACY-' || s.rn
from (
    select id, row_number() over (partition by store_id order by created_at, id) as rn
    from t_terminal
) s
where t.id = s.id;

alter table t_terminal alter column terminal_code set not null;
alter table t_terminal add constraint ux_terminal_store_code unique (store_id, terminal_code);
