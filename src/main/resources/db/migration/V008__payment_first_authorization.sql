-- payment 모듈: 같은 checkout 회차에 사실(OrderPaymentRequested)로 만든 승인 시도를 하나로 묶는다.
-- ux_payment_attempt_inflight_authorization은 DECLINED를 빼고 본다. 같은 사실이 동시에 두 번 오고 앞 시도가 거절된 뒤
-- 뒤쪽이 커밋하면 그 인덱스를 통과해 사용자가 요청하지 않은 재결제가 된다. 첫 시도를 행 자신에서 알아보도록
-- 재결제로 만든 시도에 원래 시도를 적고(retried_from), 그 값이 없는 승인 시도를 (order_id, checkout_no)당 하나로 묶는다.
-- 멱등 키라서 soft delete된 행에도 걸린다(deleted_at 술어 없음). DB FK는 두지 않는다(V006과 같은 테이블 안 참조일 뿐이다).
--
-- backfill하지 않는다. 재결제 명령은 HTTP endpoint가 없어 운영에서 불린 적이 없고 재발행도 꺼져 있었으므로
-- 한 회차에 승인 시도가 둘 이상인 행은 없어야 한다. 있으면 재결제인지 경합 흔적인지 데이터로 가릴 수 없으므로
-- 인덱스 생성이 실패해 기동을 막고 사람이 확인한다.

-- 마이그레이션은 배포 중 한 번 돌고 재실행할 수 있으므로 요청 경로의 행 잠금(3초)보다 길게 잡는다(V005와 같다).
set local lock_timeout = '5s';

alter table t_payment_attempt add column retried_from uuid;

-- 행이 커지면 CONCURRENTLY 별도 마이그레이션으로 나눈다(도메인 모델 §4.7 질문 7).
create unique index ux_payment_attempt_first_authorization
    on t_payment_attempt (order_id, checkout_no)
    where kind = 'AUTHORIZATION'
      and retried_from is null;
