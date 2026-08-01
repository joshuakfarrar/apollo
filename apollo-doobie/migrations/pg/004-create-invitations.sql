CREATE TABLE invitations (
    code CHAR(32) NOT NULL,
    email VARCHAR(320) NOT NULL,
    invited_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
