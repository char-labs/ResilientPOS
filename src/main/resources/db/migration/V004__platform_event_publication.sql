-- platform: Spring Modulith Event Publication Registry.
-- 테이블과 컬럼 이름은 Modulith의 JPA 엔티티(DefaultJpaEventPublication)가 정한다. t_ 접두어 규칙과 BaseEntity 공통 컬럼의 예외다.
-- 기준은 spring-modulith-events-jdbc 2.1.1의 schemas/v2/schema-postgresql.sql이고 JPA 엔티티의 매핑과 대조했다.
-- 발행 기록은 업무 상태와 같은 트랜잭션에 저장된다. completion_date가 비어 있으면 아직 처리되지 않은 이벤트다.

create table event_publication (
    id uuid primary key,
    listener_id text not null,
    event_type text not null,
    serialized_event text not null,
    publication_date timestamptz not null,
    completion_date timestamptz,
    status text,
    completion_attempts integer,
    last_resubmission_date timestamptz
);

create index event_publication_serialized_event_hash_idx on event_publication using hash (serialized_event);
create index event_publication_by_completion_date_idx on event_publication (completion_date);
