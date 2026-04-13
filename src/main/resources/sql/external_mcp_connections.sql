CREATE TABLE IF NOT EXISTS external_mcp_connections (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    connection_name VARCHAR(128) NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    transport VARCHAR(32) NOT NULL DEFAULT 'SSE',
    url VARCHAR(1024) NOT NULL,
    endpoint VARCHAR(255) NULL,
    headers JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_connection_name (connection_name)
);

INSERT INTO external_mcp_connections (
    connection_name,
    enabled,
    transport,
    url,
    endpoint,
    headers
) VALUES (
    'remote-sse-1',
    1,
    'SSE',
    'https://dashscope.aliyuncs.com/api/v1/mcps/zhipu-websearch/sse',
    NULL,
    JSON_OBJECT(
        'Authorization', 'Bearer sk-d06e47e55f6c4b8f881f2c9d61b68500',
        'key', 'sk-'
    )
)
ON DUPLICATE KEY UPDATE
    enabled = VALUES(enabled),
    transport = VALUES(transport),
    url = VALUES(url),
    endpoint = VALUES(endpoint),
    headers = VALUES(headers),
    updated_at = CURRENT_TIMESTAMP;
