package me.xiaozhi.mcp.external;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ExternalMcpConnectivityService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public TestResult testConnection(ExternalMcpProperties.ConnectionProperties connection, ExternalMcpProperties properties) {
        McpSyncClient client = null;
        try {
            client = openClient(connection, properties, "xiaozhi-mcp-connectivity-test");
            McpSchema.InitializeResult initializeResult = client.initialize();
            McpSchema.ListToolsResult listToolsResult = client.listTools();
            int toolCount = listToolsResult.tools() == null ? 0 : listToolsResult.tools().size();
            String serverName = initializeResult.serverInfo() == null ? "unknown" : initializeResult.serverInfo().name();
            return new TestResult(true, serverName, toolCount, null);
        }
        catch (Exception ex) {
            return new TestResult(false, null, 0, ex.getMessage());
        }
        finally {
            if (client != null) {
                try {
                    client.closeGracefully();
                }
                catch (Exception ignored) {
                }
            }
        }
    }

    public ToolListResult listTools(ExternalMcpProperties.ConnectionProperties connection, ExternalMcpProperties properties) {
        McpSyncClient client = null;
        try {
            client = openClient(connection, properties, "xiaozhi-mcp-list-tools");
            McpSchema.InitializeResult initializeResult = client.initialize();
            McpSchema.ListToolsResult listToolsResult = client.listTools();
            String serverName = initializeResult.serverInfo() == null ? "unknown" : initializeResult.serverInfo().name();
            List<ToolMeta> tools = new ArrayList<>();
            if (listToolsResult.tools() != null) {
                for (McpSchema.Tool tool : listToolsResult.tools()) {
                    Map<String, Object> inputSchema = parseInputSchema(tool);
                    List<String> required = parseRequired(inputSchema);
                    tools.add(new ToolMeta(tool.name(), tool.description(), inputSchema, required));
                }
            }
            return new ToolListResult(true, serverName, tools, null);
        }
        catch (Exception ex) {
            return new ToolListResult(false, null, List.of(), ex.getMessage());
        }
        finally {
            closeClient(client);
        }
    }

    public InvokeResult invokeTool(
            ExternalMcpProperties.ConnectionProperties connection,
            ExternalMcpProperties properties,
            String toolName,
            Map<String, Object> arguments) {
        McpSyncClient client = null;
        try {
            client = openClient(connection, properties, "xiaozhi-mcp-call-tool");
            McpSchema.InitializeResult initializeResult = client.initialize();
            McpSchema.CallToolResult callResult = client.callTool(new McpSchema.CallToolRequest(toolName, arguments));

            String serverName = initializeResult.serverInfo() == null ? "unknown" : initializeResult.serverInfo().name();
            String text = extractCallText(callResult);
            String raw = OBJECT_MAPPER.writeValueAsString(callResult);
            return new InvokeResult(true, serverName, toolName, text, raw, null);
        }
        catch (Exception ex) {
            return new InvokeResult(false, null, toolName, null, null, ex.getMessage());
        }
        finally {
            closeClient(client);
        }
    }

    private McpSyncClient openClient(
            ExternalMcpProperties.ConnectionProperties connection,
            ExternalMcpProperties properties,
            String clientName) {
        ResolvedEndpoint resolvedEndpoint = resolveEndpoint(connection);
        McpClientTransport transport = switch (connection.getTransport()) {
            case SSE -> HttpClientSseClientTransport.builder(resolvedEndpoint.baseUrl())
                    .sseEndpoint(resolvedEndpoint.endpoint())
                    .connectTimeout(properties.getConnectTimeout())
                    .customizeRequest(builder -> applyHeaders(builder, connection.getHeaders()))
                    .build();
            case STREAMABLE -> HttpClientStreamableHttpTransport.builder(resolvedEndpoint.baseUrl())
                    .endpoint(resolvedEndpoint.endpoint())
                    .connectTimeout(properties.getConnectTimeout())
                    .customizeRequest(builder -> applyHeaders(builder, connection.getHeaders()))
                    .build();
        };

        return McpClient.sync(transport)
                .requestTimeout(properties.getRequestTimeout())
                .initializationTimeout(properties.getInitializationTimeout())
                .clientInfo(new McpSchema.Implementation(clientName, "0.0.1"))
                .build();
    }

    private void closeClient(McpSyncClient client) {
        if (client == null) {
            return;
        }
        try {
            client.closeGracefully();
        }
        catch (Exception ignored) {
        }
    }

    private String extractCallText(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null || result.content().isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent textContent) {
                parts.add(textContent.text());
                continue;
            }
            if (content instanceof McpSchema.ImageContent imageContent) {
                parts.add("[image:" + imageContent.mimeType() + "]");
                continue;
            }
            if (content instanceof McpSchema.AudioContent audioContent) {
                parts.add("[audio:" + audioContent.mimeType() + "]");
                continue;
            }
            try {
                parts.add(OBJECT_MAPPER.writeValueAsString(content));
            }
            catch (Exception ex) {
                parts.add(String.valueOf(content));
            }
        }
        return String.join("\n", parts);
    }

    private Map<String, Object> parseInputSchema(McpSchema.Tool tool) {
        try {
            Object schema = tool.inputSchema();
            if (schema == null) {
                return new LinkedHashMap<>();
            }
            Map<String, Object> map = OBJECT_MAPPER.convertValue(schema, Map.class);
            return map == null ? new LinkedHashMap<>() : map;
        }
        catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    private List<String> parseRequired(Map<String, Object> schema) {
        Object required = schema.get("required");
        if (!(required instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private void applyHeaders(HttpRequest.Builder builder, Map<String, String> headers) {
        headers.forEach(builder::header);
    }

    private ResolvedEndpoint resolveEndpoint(ExternalMcpProperties.ConnectionProperties connection) {
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

    public record TestResult(boolean success, String serverName, int toolCount, String message) {
    }

    public record ToolMeta(
            String name,
            String description,
            Map<String, Object> inputSchema,
            List<String> requiredParams) {
    }

    public record ToolListResult(
            boolean success,
            String serverName,
            List<ToolMeta> tools,
            String message) {
    }

    public record InvokeResult(
            boolean success,
            String serverName,
            String toolName,
            String textResult,
            String rawResultJson,
            String message) {
    }

    private record ResolvedEndpoint(String baseUrl, String endpoint) {
    }
}
