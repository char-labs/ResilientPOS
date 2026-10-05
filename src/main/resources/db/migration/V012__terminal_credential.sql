-- terminal 모듈: 단말(소속 매장·상태)과 인증 credential 이력. 평문 토큰은 어디에도 저장하지 않고 SHA-256 hex만 둔다.
-- t_terminal.store_id는 단말의 매장 정본이고 불변이다. 매장 존재는 확인하지 않으며 store 모듈에 표가 없어 FK도 두지 않는다.
-- 단말 코드 컬럼은 두지 않는다. 읽는 코드가 없다. 운영자가 단말을 고르는 화면·API가 생길 때 그 PR이 넣고 기존 행을 채운다.
-- token_hash는 256bit 난수의 SHA-256이라 느린 해시가 필요 없고, 해시로 바로 조회한다(요청마다 행 하나).
-- ux_terminal_credential_token_hash는 해시 하나가 두 단말을 가리키지 않게 하는 방어선이다. 조회가 한 행을 전제한다.
-- ux_terminal_credential_active는 "유효 credential은 단말당 하나"의 마지막 방어선이다. 평소에는 t_terminal 행 잠금이
-- 회전을 직렬화하고, 잠금 밖에서 들어온 행(수동 수정)을 여기서 막는다. 폐기된 행은 revoked_at을 가진 채 남는다.
-- 두 unique는 soft delete된 행에도 걸린다. 지우는 명령이 없고 이력 삭제 금지가 불변식이다. 지우는 명령이 생기면
-- 조회가 삭제된 행도 보게 해야 한다(BaseEntity의 @SoftDelete는 조회만 거르고 제약은 남는다).
-- 두 표 모두 version 컬럼이 없다. 갱신 경로(회전·비활성화)는 t_terminal 행을 잠그고 시작하므로 낙관적 잠금이 필요 없다.
-- status는 varchar다. 정수로 저장하면 값을 하나 끼워 넣을 때 기존 행의 뜻이 바뀐다. 길이는 t_order.status 선례(32)를 따랐다.
-- 새 표라 기존 표에 락을 잡지 않고, 행이 없어 인덱스 빌드가 즉시 끝난다. 이전 버전은 새 표를 매핑하지 않아 validate가 통과한다.

create table t_terminal (
    id uuid primary key,
    store_id uuid not null,
    status varchar(32) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz
);

create table t_terminal_credential (
    id uuid primary key,
    terminal_id uuid not null,
    token_hash varchar(64) not null,
    revoked_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz,
    constraint ux_terminal_credential_token_hash unique (token_hash)
);

create unique index ux_terminal_credential_active on t_terminal_credential (terminal_id) where revoked_at is null;
