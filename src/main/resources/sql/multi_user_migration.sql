-- Existing single-user MySQL database: back up first, then run this migration ONCE.
-- Fresh database: use external_mcp_connections.sql instead; do not run both.
CREATE TABLE IF NOT EXISTS app_locks (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO app_locks (id) VALUES (1);
CREATE TABLE IF NOT EXISTS app_users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'USER',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    auth_version INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_username (username)
);
ALTER TABLE external_mcp_connections
    ADD COLUMN owner_id BIGINT NULL AFTER id,
    MODIFY COLUMN url VARCHAR(2048) NOT NULL,
    DROP INDEX uk_connection_name,
    ADD UNIQUE KEY uk_owner_connection (owner_id, connection_name),
    ADD CONSTRAINT fk_connection_owner FOREIGN KEY (owner_id) REFERENCES app_users(id);
CREATE TABLE IF NOT EXISTS xiaozhi_nodes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    endpoint VARCHAR(2048) NOT NULL,
    endpoint_hash CHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    local_tools BOOLEAN NOT NULL DEFAULT TRUE,
    connection_ids TEXT NOT NULL,
    UNIQUE KEY uk_owner_node (owner_id, name),
    UNIQUE KEY uk_node_endpoint (endpoint_hash),
    KEY idx_enabled (enabled, id),
    CONSTRAINT fk_node_owner FOREIGN KEY (owner_id) REFERENCES app_users(id)
);
-- On first startup, the initial administrator claims external rows with NULL owner_id.
-- Legacy vector records lack reliable ownership and are deliberately excluded from searches.
