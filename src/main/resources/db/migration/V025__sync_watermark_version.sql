-- sync 모듈: t_sync_watermark에 version을 더한다. 낙관적 잠금이 아니라 새 행 판정용이다.
-- 어댑터가 새 행을 Spring Data save로 넣으면서, version이 없는 엔티티는 merge로 간다. merge는 이미 커밋된 같은 PK 행을 보면
-- INSERT 대신 UPDATE로 덮어써서, 같은 단말의 첫 배치가 겹칠 때 진 쪽이 PK 위반 대신 이긴 쪽 행을 0으로 되돌린다.
-- version이 null인 새 엔티티는 persist로 가므로 terminal_id PK가 그대로 마지막 방어선이다. 갱신은 행 잠금 아래라 version이 충돌하지 않는다.
-- 상수 기본값의 ADD COLUMN이라 메타데이터 변경이고 기존 행은 0으로 기존 행이 된다. 이전 버전은 이 컬럼을 매핑하지 않아 validate가 통과하고
-- INSERT는 기본값을 받는다. 긴 트랜잭션 뒤에서 락을 기다리며 sync를 세우지 않도록 lock_timeout을 둔다(V014와 같다).

set local lock_timeout = '5s';

alter table t_sync_watermark add column version bigint not null default 0;
