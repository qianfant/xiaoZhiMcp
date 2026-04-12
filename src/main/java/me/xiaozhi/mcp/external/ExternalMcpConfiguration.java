package me.xiaozhi.mcp.external;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.DefaultMcpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(ExternalMcpProperties.class)
public class ExternalMcpConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ExternalMcpConfiguration.class);

    @Bean(destroyMethod = "close")
    public ExternalMcpClients externalMcpClients(ExternalMcpProperties properties) {
        List<McpSyncClient> clients = new ArrayList<>();
        int totalConnections = properties.getConnections().size();

        for (Map.Entry<String, ExternalMcpProperties.ConnectionProperties> entry : properties.getConnections().entrySet()) {
            String connectionName = entry.getKey();
            ExternalMcpProperties.ConnectionProperties connection = entry.getValue();

            if (!connection.isEnabled()) {
                log.info("外部 MCP [{}] 已禁用，跳过接入", connectionName);
                continue;
            }

            if (!StringUtils.hasText(connection.getUrl())) {
                log.warn("外部 MCP [{}] 未配置 url，跳过接入", connectionName);
                continue;
            }

            createClient(connectionName, connection, properties)
                    .ifPresent(clients::add);
        }

        log.info("外部 MCP 连接初始化完成: 配置 {} 个，成功接入 {} 个", totalConnections, clients.size());
        return new ExternalMcpClients(clients);
    }

    @Bean
    public ToolCallbackProvider externalMcpToolCallbackProvider(ExternalMcpClients externalMcpClients) {
        if (externalMcpClients.getClients().isEmpty()) {
            return ToolCallbackProvider.from();
        }

        return SyncMcpToolCallbackProvider.builder()
                .mcpClients(externalMcpClients.getClients())
                .toolNamePrefixGenerator(new DefaultMcpToolNamePrefixGenerator())
                .build();
    }

    private java.util.Optional<McpSyncClient> createClient(
            String connectionName,
            ExternalMcpProperties.ConnectionProperties connection,
            ExternalMcpProperties properties) {
        try {
            ResolvedEndpoint resolvedEndpoint = resolveEndpoint(connection);
            McpClientTransport transport = switch (connection.getTransport()) {
                case SSE -> buildSseTransport(resolvedEndpoint, connection, properties.getConnectTimeout());
                case STREAMABLE -> buildStreamableTransport(resolvedEndpoint, connection, properties.getConnectTimeout());
            };

            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(properties.getRequestTimeout())
                    .initializationTimeout(properties.getInitializationTimeout())
                    .clientInfo(new McpSchema.Implementation("xiaozhi-external-mcp-client-" + connectionName, "0.0.1"))
                    .build();

            McpSchema.InitializeResult initializeResult = client.initialize();
            McpSchema.ListToolsResult listToolsResult = client.listTools();

            int toolCount = listToolsResult.tools() == null ? 0 : listToolsResult.tools().size();
            log.info("已接入外部 MCP [{}]，地址={}，服务={}，发现 {} 个工具", connectionName,
                    connection.getUrl(), initializeResult.serverInfo().name(), toolCount);

            return java.util.Optional.of(client);
        }
        catch (Exception ex) {
            log.error("接入外部 MCP [{}] 失败，地址={}，原因={}", connectionName, connection.getUrl(), ex.getMessage());
            return java.util.Optional.empty();
        }
    }

    private McpClientTransport buildSseTransport(
            ResolvedEndpoint resolvedEndpoint,
            ExternalMcpProperties.ConnectionProperties connection,
            Duration connectTimeout) {
        return HttpClientSseClientTransport.builder(resolvedEndpoint.baseUrl())
                .sseEndpoint(resolvedEndpoint.endpoint())
                .connectTimeout(connectTimeout)
                .customizeRequest(builder -> applyHeaders(builder, connection))
                .build();
    }

    private McpClientTransport buildStreamableTransport(
            ResolvedEndpoint resolvedEndpoint,
            ExternalMcpProperties.ConnectionProperties connection,
            Duration connectTimeout) {
        return HttpClientStreamableHttpTransport.builder(resolvedEndpoint.baseUrl())
                .endpoint(resolvedEndpoint.endpoint())
                .connectTimeout(connectTimeout)
                .customizeRequest(builder -> applyHeaders(builder, connection))
                .build();
    }

    private void applyHeaders(HttpRequest.Builder builder, ExternalMcpProperties.ConnectionProperties connection) {
        connection.getHeaders().forEach(builder::header);
    }

    ResolvedEndpoint resolveEndpoint(ExternalMcpProperties.ConnectionProperties connection) {
        URI uri = URI.create(connection.getUrl());
        String baseUrl = uri.getScheme() + "://" + uri.getRawAuthority();
        String configuredEndpoint = StringUtils.hasText(connection.getEndpoint()) ? connection.getEndpoint() : deriveEndpoint(uri);
        String normalizedEndpoint = configuredEndpoint.startsWith("/") ? configuredEndpoint : "/" + configuredEndpoint;
        return new ResolvedEndpoint(baseUrl, normalizedEndpoint);
    }

    private String deriveEndpoint(URI uri) {
        String path = uri.getRawPath();
        String query = uri.getRawQuery();

        if (!StringUtils.hasText(path) || "/".equals(path)) {
            return "/mcp";
        }

        return StringUtils.hasText(query) ? path + "?" + query : path;
    }

    record ResolvedEndpoint(String baseUrl, String endpoint) {
    }
}
