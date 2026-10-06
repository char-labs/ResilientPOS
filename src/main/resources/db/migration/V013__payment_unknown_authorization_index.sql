-- payment: 결과 불명 확정 작업(UnknownPaymentResolver)이 pod마다 매분 "모든 주문의 UNKNOWN 승인 시도"를 오래된 순으로 고르는 조회에 쓴다.
-- 지우지 않는 결제 원장 전체를 순차 스캔하지 않도록 UNKNOWN만 모은 partial 인덱스로 둔다. 조회가 kind·status를 리터럴로 렌더해 술어를 증명한다.
-- CONCURRENTLY라 트랜잭션 밖에서 돌아야 해서 이 문장만 담는다(도메인 모델 §4.7 질문 7).
create index concurrently ix_payment_attempt_unknown_authorization on t_payment_attempt (id) where kind = 'AUTHORIZATION' and status = 'UNKNOWN';
