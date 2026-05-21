CREATE TABLE users (
    id        UUID PRIMARY KEY,
    email     TEXT NOT NULL,
    served_by TEXT NOT NULL
);

INSERT INTO users (id, email, served_by) VALUES
    ('11111111-1111-1111-1111-111111111111', 'alice@example.com', 'PRIMARY'),
    ('22222222-2222-2222-2222-222222222222', 'bob@example.com',   'PRIMARY'),
    ('33333333-3333-3333-3333-333333333333', 'carol@example.com', 'PRIMARY');
