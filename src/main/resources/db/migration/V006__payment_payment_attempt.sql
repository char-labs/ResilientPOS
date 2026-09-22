-- payment 모듈: 결제 시도. 한 주문에 여러 행이 달린다(거절 뒤 재시도, 승인 뒤 취소·환불).
-- id 단독 primary key다. 무조건적인 (order_id, ...) unique를 두지 않는다. 그런 키는 한 주문의 시도 수를
-- 제한해서 1-to-many를 막는다. 대신 상태를 술어에 넣은 partial unique로 "진행 중인 승인"만 하나로 묶는다.
-- DB FK를 두지 않는다. payment와 order는 서로의 테이블을 읽지 않는 별개 컨텍스트다.
-- currency를 직접 가진다. t_order에서 읽으면 그 경계를 넘게 되고, 승인 금액 검증에는 통화가 금액과 같은 행에 있어야 한다.
-- kind와 status는 varchar다. 정수로 저장하면 값을 하나 끼워 넣을 때 기존 행의 뜻이 바뀐다.
-- 길이 32는 t_order.status의 선례를 따랐다. Hibernate의 validate는 길이를 대조하지 않으므로
-- 짧게 잡으면 값을 늘리는 PR이 운영에서야 "value too long"을 만난다.
-- 이 테이블의 행은 지우지 않는다. 지우는 명령이 생기면 그 PR이 감사·reconciliation 영향을 함께 본다.

create table t_payment_attempt (
    id uuid primary key,
    order_id uuid not null,
    store_id uuid not null,
    terminal_id uuid not null,
    checkout_no integer not null,
    kind varchar(32) not null,
    amount numeric(19,4) not null,
    currency char(3) not null,
    status varchar(32) not null,
    provider_reference varchar(255),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz
);

-- 한 주문의 시도를 생성 순서로 읽는 것이 기본 조회다. UUID v7은 앞 48비트가 타임스탬프이고
-- PostgreSQL의 uuid 비교가 바이트 순서라서 id 정렬이 곧 생성 순서다.
create index ix_payment_attempt_order on t_payment_attempt (order_id, id);

-- 같은 checkout에 진행 중인 승인 시도는 하나뿐이다. 이벤트가 두 번 전달돼도 두 번째 INSERT가 여기서 막힌다.
-- 1-to-many를 막지 않는다. 거절된 시도는 status가 DECLINED가 되어 술어에서 빠지므로 재시도가 들어가고,
-- 취소·환불은 kind가 CANCELLATION이라 술어 밖이다. UNKNOWN을 포함하는 이유는 도메인이 이미
-- "UNKNOWN에서 새 승인 요청으로 재시도하지 않는다"를 규칙으로 정했기 때문이고, 이 인덱스가 그 마지막 방어선이다.
create unique index ux_payment_attempt_inflight_authorization
    on t_payment_attempt (order_id, checkout_no)
    where kind = 'AUTHORIZATION'
      and status in ('REQUESTED', 'APPROVED', 'UNKNOWN')
      and deleted_at is null;
