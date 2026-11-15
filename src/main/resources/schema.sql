CREATE TABLE IF NOT EXISTS users
(
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(50)              UNIQUE NOT NULL,
    password    VARCHAR(255),
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 소셜 로그인 지원: password 필수 해제, provider 컬럼 추가 (idempotent)
ALTER TABLE users ALTER COLUMN password DROP NOT NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS provider    VARCHAR(20);
ALTER TABLE users ADD COLUMN IF NOT EXISTS provider_id VARCHAR(255);

-- 업로드 문서 관리: 문서 목록/삭제 및 인덱싱 상태 추적용
-- user_id는 벡터 메타데이터의 user_id와 동일하게 username을 저장한다 (멀티테넌트 격리 기준)
CREATE TABLE IF NOT EXISTS documents
(
    id            BIGSERIAL PRIMARY KEY,
    user_id       VARCHAR(50)              NOT NULL,
    filename      VARCHAR(255)             NOT NULL,
    status        VARCHAR(20)              NOT NULL,
    chunk_count   INTEGER,
    error_message VARCHAR(500),
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_documents_user_id_created_at ON documents (user_id, created_at DESC);
