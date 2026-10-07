-- owner 모듈: 점주(SaaS 고객)의 앱 자체 계정. 비밀번호는 `{bcrypt}` 접두어가 붙은 해시만 저장한다.
-- email은 앱이 앞뒤 공백을 지우고 소문자로 바꿔 넣는다. ux_owner_email이 같은 이메일 중복 가입의 마지막 방어선이고 사전 조회는 하지 않는다.
-- 로그인 실패 횟수는 시한이 있는 제한 장치라 DB에 두지 않는다(Redis).
-- 새 표라 기존 표에 락을 잡지 않고, 행이 없어 인덱스 빌드가 즉시 끝난다. 이전 버전은 새 표를 매핑하지 않아 validate가 통과한다.

create table t_owner (
    id uuid primary key,
    email varchar(254) not null,
    password_hash varchar(255) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz,
    constraint ux_owner_email unique (email)
);
