CREATE TABLE atproto_identities (
    user_id UUID NOT NULL REFERENCES users(id),
    did VARCHAR(2048) NOT NULL UNIQUE,
    handle VARCHAR(253) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
