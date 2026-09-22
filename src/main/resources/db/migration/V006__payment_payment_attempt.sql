-- payment 모듈: 결제 시도. 한 주문에 여러 행이 달린다(거절 뒤 재시도, 승인 뒤 취소·환불).
-- id 단독 primary key다. (order_id, ...) 복합 자연 키를 두지 않는다. 그런 키는 한 주문의 시도 수를
-- 제한하는 쪽으로 작동해서 1-to-many를 막는다. 중복 생성은 이벤트 전달 계층이 막을 일이다.
-- DB FK를 두지 않는다. payment와 order는 서로의 테이블을 읽지 않는 별개 컨텍스트다.
-- currency를 직접 가진다. t_order에서 읽으면 그 경계를 넘게 되고, 승인 금액 검증에는 통화가 금액과 같은 행에 있어야 한다.
-- kind와 status는 varchar다. 정수로 저장하면 값을 하나 끼워 넣을 때 기존 행의 뜻이 바뀐다.

create table t_payment_attempt (
    id uuid primary key,
    order_id uuid not null,
    store_id uuid not null,
    terminal_id uuid not null,
    checkout_no integer not null,
    kind varchar(20) not null,
    amount numeric(19,4) not null,
    currency char(3) not null,
    status varchar(20) not null,
    provider_reference varchar(255),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz
);

-- 한 주문의 시도를 모두 읽는 것이 기본 조회다.
create index idx_payment_attempt_order on t_payment_attempt (order_id);
