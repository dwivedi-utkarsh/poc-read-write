CREATE TABLE users (
    id        UUID PRIMARY KEY,
    email     TEXT NOT NULL,
    served_by TEXT NOT NULL
);

INSERT INTO users (id, email, served_by) VALUES
    ('11111111-1111-1111-1111-111111111111', 'alice@example.com', 'REPLICA'),
    ('22222222-2222-2222-2222-222222222222', 'bob@example.com',   'REPLICA'),
    ('33333333-3333-3333-3333-333333333333', 'carol@example.com', 'REPLICA');

-- ShardingSphere connects to the replica container as this read-only role.
-- A REVOKE on the default user is useless because that user owns the table,
-- and table owners bypass REVOKE. A separate role with only SELECT grants
-- gives us a real permission boundary, so any misrouted write throws
-- "permission denied for table users" — the loud failure signal we need.
CREATE ROLE readonly_app WITH LOGIN PASSWORD 'readonly';
GRANT SELECT ON users TO readonly_app;
