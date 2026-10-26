-- Fresh MySQL installation. For an existing database use multi_user_migration.sql.
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
CREATE TABLE IF NOT EXISTS external_mcp_connections (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NULL,
    connection_name VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    transport VARCHAR(32) NOT NULL DEFAULT 'SSE',
    url VARCHAR(2048) NOT NULL,
    endpoint VARCHAR(255) NULL,
    headers TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_owner_connection (owner_id, connection_name),
    CONSTRAINT fk_connection_owner FOREIGN KEY (owner_id) REFERENCES app_users(id)
);
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
