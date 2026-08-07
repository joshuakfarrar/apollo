CREATE TABLE atproto_oauth_requests (
    state VARCHAR(64) NOT NULL UNIQUE,
    identifier VARCHAR(320) NOT NULL,
    did VARCHAR(2048) NOT NULL,
    handle VARCHAR(253) NOT NULL,
    issuer VARCHAR(2048) NOT NULL,
    token_endpoint VARCHAR(2048) NOT NULL,
    pkce_verifier VARCHAR(128) NOT NULL,
    dpop_private_key TEXT NOT NULL,
    dpop_public_jwk TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
