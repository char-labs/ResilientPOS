-- platform 모듈: 플랫폼 운영자의 DLT 재투입 기록. 누가(operator_subject, 토큰의 sub) 왜(reason) 어느 DLT 레코드를 원 토픽의 어디에 넣었는지(target_*) 남긴다.
-- ux_dead_letter_redrive_source는 "한 DLT 레코드의 기록은 하나"와 멱등 응답의 유일한 방어선이다. 지우는 명령이 없어 soft delete된 행에도 걸리게 partial로 두지 않는다.
-- 좌표에 레코드 시각(dlt_timestamp, DLT로 보낼 때 producer가 찍은 값)을 더한다. DLT 토픽을 다시 만들면 offset이 0부터 다시 시작해 좌표만으로는 옛 기록과 겹친다.
-- 원 토픽(dlt_topic에서 .DLT를 뺀 값)과 재투입 시각(created_at)은 도출할 수 있어 컬럼을 두지 않는다. 기록은 바꾸거나 지우지 않는다.
-- SQL로도 soft delete하지 않는다. 조회는 deleted_at 행을 거르는데 unique는 그 행에도 걸려, 같은 레코드를 요청하면 보낸 뒤 500이 된다.
-- 새 표라 기존 표에 락을 잡지 않고, 행이 없어 인덱스 빌드가 즉시 끝난다. 이전 버전은 새 표를 매핑하지 않아 validate가 통과한다.

create table t_dead_letter_redrive (
    id uuid primary key,
    dlt_topic varchar(249) not null,
    dlt_partition integer not null,
    dlt_offset bigint not null,
    dlt_timestamp timestamptz not null,
    target_partition integer not null,
    target_offset bigint not null,
    operator_subject varchar(255) not null,
    reason varchar(500) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz,
    constraint ux_dead_letter_redrive_source unique (dlt_topic, dlt_partition, dlt_offset, dlt_timestamp)
);
