-- sync 모듈: 단말 watermark와 operation 접수 기록. 오프라인 주문 생성 배치의 판정·전진이 한 트랜잭션에서 이 두 표를 쓴다.
-- t_sync_watermark는 단말당 행 하나라 terminal_id가 곧 PK다. 항목마다 이 행을 잠그고(for no key update, 대기 3초)
-- 판정·적용·기록·전진을 한 커밋으로 한다. version 컬럼은 두지 않는다 — 잠금 없이 쓰는 경로가 없다.
-- last_acked_sequence는 t_sync_operation의 max(sequence)와 같지만 집계로 대신하지 않는다. 집계는 잠글 수 없다.
-- t_sync_operation은 넣기만 하고 지우지 않는다. (terminal_id, operation_id) unique가 "같은 operation을 두 번 적용하지 않는다"의
-- 마지막 방어선이고 멱등 키라 soft delete된 행에도 걸리는 전체 unique로 둔다. (terminal_id, sequence) unique는 "순번 하나에 기록 하나"의
-- 방어선이다. 평소에는 watermark 행 잠금이 지키고, 잠금 밖에서 들어온 행(수동 수정)을 여기서 막는다. 순번으로 조사할 때의 인덱스를 겸한다.
-- 두 표 모두 지우는 명령이 없다. 생기면 멱등 조회와 watermark 조회가 삭제된 행도 보게 해야 한다(BaseEntity의 @SoftDelete는 조회만 거르고 제약은 남는다).
-- operation 종류 컬럼은 두지 않는다. 종류가 CREATE_ORDER 하나뿐이다. 둘째 종류를 더하는 PR이 상수 기본값 'CREATE_ORDER'로
-- 기존 행을 채우며 붙인다(lock_timeout을 둔 메타데이터 변경).
-- order_id는 t_order.id를 가리키는 참조일 뿐 사본이 아니다. DB FK를 두지 않는다 — sync와 order는 서로의 테이블을 읽지 않는다.
-- payload_hash는 주문 생성 명령의 SHA-256 hex(64자)다. 재전송이 같은 내용인지(duplicate) 다른 내용인지(conflict) 가르는 근거다.
-- status·error_code는 varchar다. 정수로 저장하면 값을 하나 끼워 넣을 때 기존 행의 뜻이 바뀐다. 길이는 t_order.status 선례(32)와
-- 오류 code(IDEMPOTENCY_CONFLICT처럼 긴 상수, 64)를 따랐다.

create table t_sync_watermark (
    terminal_id uuid primary key,
    last_acked_sequence bigint not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz
);

create table t_sync_operation (
    id uuid primary key,
    terminal_id uuid not null,
    operation_id uuid not null,
    sequence bigint not null,
    payload_hash varchar(64) not null,
    status varchar(32) not null,
    order_id uuid,
    error_code varchar(64),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz,
    constraint ux_sync_operation_terminal_operation unique (terminal_id, operation_id),
    constraint ux_sync_operation_terminal_sequence unique (terminal_id, sequence)
);
