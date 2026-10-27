-- order 모듈: 주문 발생 시각 occurred_at을 더한다. 단말이 알려 준 판매 시각이고 서버 수신 시각 created_at과 다르다.
-- 기존 행은 created_at으로 채우고 NOT NULL로 만든다. ADD COLUMN은 메타데이터 변경이지만 update와 SET NOT NULL이 테이블 전체를 다룬다.
-- 배포 환경이 없고 개발 데이터뿐이라 한 마이그레이션에 둔다. 배포된 큰 테이블이었다면 nullable 추가, 배치 backfill, NOT VALID check, VALIDATE 순서다.

-- 마이그레이션은 배포 중 한 번 돌고 재실행할 수 있으므로 요청 경로의 주문 행 잠금(3초)보다 길게 잡는다.
set local lock_timeout = '5s';

alter table t_order add column occurred_at timestamptz;
update t_order set occurred_at = created_at;
alter table t_order alter column occurred_at set not null;
