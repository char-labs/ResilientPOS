-- terminal 모듈: 단말당 유효 credential 하나를 커밋 때 검사하게 바꾼다.
-- 회전은 한 커밋에서 옛 행을 폐기(UPDATE)하고 새 유효 행을 넣는다(INSERT). Hibernate는 한 flush에서 INSERT를 UPDATE보다 먼저 보내
-- 문장마다 검사하는 V012의 partial unique 인덱스에 걸렸으므로, 폐기를 먼저 내보내려고 flush를 불렀다. 커밋 때 검사하면 순서가 상관없다.
-- unique 제약은 술어(where revoked_at is null)를 가질 수 없고 partial unique 인덱스는 DEFERRABLE이 되지 못해서 exclusion 제약으로 옮긴다.
-- 조건과 이름은 V012와 같다. 위반 종류만 unique(23505)에서 exclusion(23P01)으로 바뀐다. 이 제약 위반을 가리는 코드는 없다.
-- 제약 추가는 t_terminal_credential에 ACCESS EXCLUSIVE를 잡지만 단말당 행이 몇 개뿐인 작은 표라 짧다.

-- 마이그레이션은 배포 중 한 번 돌고 재실행할 수 있으므로 요청 경로의 행 잠금(3초)보다 길게 잡는다(V005·V008과 같다).
set local lock_timeout = '5s';

drop index ux_terminal_credential_active;

alter table t_terminal_credential
    add constraint ux_terminal_credential_active
    exclude using btree (terminal_id with =) where (revoked_at is null)
    deferrable initially deferred;
