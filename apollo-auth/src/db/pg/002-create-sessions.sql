CREATE TABLE sessions (
    user_id UUID NOT NULL REFERENCES users(id),
    token VARCHAR(256) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL
);