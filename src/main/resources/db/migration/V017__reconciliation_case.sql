-- reconciliation 모듈: 조회로도 확정되지 않아 payment가 넘긴 시도의 대조 case와 그 해소 기록.
-- ux_reconciliation_case_payment는 "시도 하나에 case 하나"의 마지막 방어선이다. 같은 사실이 재전달되거나 겹쳐 처리돼도 case가 둘이 되지 않는다.
-- 해소 기록(resolved_at 이하)은 case 행의 컬럼이다. case당 해소는 한 번이라 자식 표를 두지 않는다. 도메인 모델 규칙대로 FK를 두지 않는다.
-- 새 표라 기존 표에 락을 잡지 않고, 행이 없어 인덱스 빌드가 즉시 끝난다. 이전 버전은 새 표를 매핑하지 않아 validate가 통과한다.

create table t_reconciliation_case (
    id uuid primary key,
    payment_id uuid not null,
    order_id uuid not null,
    store_id uuid not null,
    terminal_id uuid not null,
    checkout_no integer not null,
    reason varchar(32) not null,
    status varchar(32) not null,
    rule_version integer not null,
    amount numeric(19,4) not null,
    currency char(3) not null,
    unknown_reason varchar(32) not null,
    query_count integer not null,
    reported_amount numeric(19,4),
    reported_currency char(3),
    reported_provider_reference varchar(255),
    opened_at timestamptz not null,
    resolved_at timestamptz,
    resolved_by_owner_id uuid,
    decision varchar(16),
    provider_reference varchar(255),
    approved_amount numeric(19,4),
    approved_currency char(3),
    note varchar(500),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz,
    constraint ux_reconciliation_case_payment unique (payment_id)
);
create index ix_reconciliation_case_store on t_reconciliation_case (store_id, status, id);
