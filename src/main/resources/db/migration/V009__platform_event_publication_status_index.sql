-- platform: 발행 기록 재전송 작업과 Modulith가 매분 pod마다 status로 거르는 조회에 쓴다.
-- countByStatus(RESUBMITTED)·findFailedPublications(FAILED)·완료된 재전송 행 정리(RESUBMITTED)가 대상이다.
-- 완료 행 1,000만 건에서 인덱스가 없으면 각각 Seq Scan으로 0.4~0.9초, 있으면 수 ms였다(event-publication-resubmit spec).
-- partial 인덱스는 Modulith가 파라미터로 거는 countByStatus의 generic plan에서 쓰이지 않아 전체 인덱스로 둔다.
-- CONCURRENTLY라 트랜잭션 밖에서 돌아야 해서 이 문장만 담는다(도메인 모델 §4.7 질문 7).
create index concurrently event_publication_status_idx on event_publication (status);
