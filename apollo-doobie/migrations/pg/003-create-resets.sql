CREATE TABLE resets (
    user_id UUID NOT NULL REFERENCES users(id),
    code CHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);