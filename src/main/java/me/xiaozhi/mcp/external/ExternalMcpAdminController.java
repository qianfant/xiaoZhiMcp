package me.xiaozhi.mcp.external;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.xiaozhi.mcp.external.entity.ExternalMcpConnection;
import me.xiaozhi.mcp.external.service.ExternalMcpConnectionService;
import me.xiaozhi.mcp.tool.DemoMcpTools;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/external-mcp")
public class ExternalMcpAdminController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String LOCAL_CONNECTION_ID = "LOCAL";
    private static final String LOCAL_CONNECTION_NAME = "local";

    private final ExternalMcpConnectionService connectionService;
    private final ExternalMcpConnectivityService connectivityService;
    private final ExternalMcpConfiguration externalMcpConfiguration;
    private final ExternalMcpClients externalMcpClients;
    private final ExternalMcpProperties externalMcpProperties;
    private final String localMcpUrl;
    private final DemoMcpTools demoMcpTools;

    public ExternalMcpAdminController(
            ExternalMcpConnectionService connectionService,
            ExternalMcpConnectivityService connectivityService,
            ExternalMcpConfiguration externalMcpConfiguration,
            ExternalMcpClients externalMcpClients,
            ExternalMcpProperties externalMcpProperties,
            DemoMcpTools demoMcpTools,
            @Value("${xiaozhi.bridge.local-mcp-url:http://127.0.0.1:9998/mcp}") String localMcpUrl) {
        this.connectionService = connectionService;
        this.connectivityService = connectivityService;
        this.externalMcpConfiguration = externalMcpConfiguration;
        this.externalMcpClients = externalMcpClients;
        this.externalMcpProperties = externalMcpProperties;
        this.demoMcpTools = demoMcpTools;
        this.localMcpUrl = localMcpUrl;
    }

    @GetMapping("/connections")
    public ApiResponse<List<ConnectionDto>> listConnections() {
        List<ExternalMcpConnection> rows = connectionService.list();
        List<ConnectionDto> result = new ArrayList<>(rows.size());
        for (ExternalMcpConnection row : rows) {
            result.add(toDto(row));
        }
        return ApiResponse.ok(result);
    }

    @PostMapping("/connections")
    public ApiResponse<ConnectionDto> createConnection(@RequestBody SaveConnectionRequest request) {
        String connectionName = request.connectionName() == null ? null : request.connectionName().trim();
        if (!StringUtils.hasText(connectionName)) {
            return ApiResponse.fail("connectionName 不能为空");
        }
        if (!StringUtils.hasText(request.url())) {
            return ApiResponse.fail("url 不能为空");
        }

        LambdaQueryWrapper<ExternalMcpConnection> query = new LambdaQueryWrapper<ExternalMcpConnection>()
                .eq(ExternalMcpConnection::getConnectionName, connectionName);
        if (connectionService.count(query) > 0) {
            return ApiResponse.fail("连接名称已存在，请更换 connectionName");
        }

        ExternalMcpConnection row = new ExternalMcpConnection();
        row.setConnectionName(connectionName);
        row.setEnabled(request.enabled() == null || request.enabled());
        row.setTransport(normalizeTransport(request.transport()));
        row.setUrl(request.url().trim());
        row.setEndpoint(StringUtils.hasText(request.endpoint()) ? request.endpoint().trim() : null);
        row.setHeaders(toHeadersJson(request.headers()));
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        connectionService.save(row);

        externalMcpConfiguration.refreshExternalMcpClients(externalMcpClients, externalMcpProperties);
        return ApiResponse.ok(toDto(row));
    }

    @PostMapping("/connections/test")
    public ApiResponse<TestConnectionResponse> testConnection(@RequestBody TestConnectionRequest request) {
        if (!StringUtils.hasText(request.url())) {
            return ApiResponse.fail("url 不能为空");
        }
        ExternalMcpProperties.ConnectionProperties connection = new ExternalMcpProperties.ConnectionProperties();
        connection.setEnabled(true);
        connection.setTransport(transportOf(request.transport()));
        connection.setUrl(request.url().trim());
        connection.setEndpoint(StringUtils.hasText(request.endpoint()) ? request.endpoint().trim() : null);
        connection.setHeaders(request.headers() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.headers()));

        ExternalMcpConnectivityService.TestResult testResult = connectivityService.testConnection(connection, externalMcpProperties);
        TestConnectionResponse response = new TestConnectionResponse(
                null,
                testResult.success(),
                testResult.serverName(),
                testResult.toolCount(),
                testResult.message());
        if (!testResult.success()) {
            return ApiResponse.fail("连通性测试失败", response);
        }
        return ApiResponse.ok(response);
    }

    @PostMapping("/connections/{id}/test")
    public ApiResponse<TestConnectionResponse> testSavedConnection(@PathVariable("id") Long id) {
        ExternalMcpConnection row = connectionService.getById(id);
        if (row == null) {
            return ApiResponse.fail("连接不存在");
        }
        ExternalMcpProperties.ConnectionProperties connection = toConnectionProperties(row);
        ExternalMcpConnectivityService.TestResult testResult = connectivityService.testConnection(connection, externalMcpProperties);
        TestConnectionResponse response = new TestConnectionResponse(
                row.getConnectionName(),
                testResult.success(),
                testResult.serverName(),
                testResult.toolCount(),
                testResult.message());
        if (!testResult.success()) {
            return ApiResponse.fail("连通性测试失败", response);
        }
        return ApiResponse.ok(response);
    }

    @GetMapping("/tools/local")
    public ApiResponse<ToolListResponse> listLocalTools() {
        ExternalMcpConnectivityService.ToolListResult result = connectivityService.listTools(localConnection(), externalMcpProperties);
        if (!result.success()) {
            return ApiResponse.fail("获取本地工具列表失败: " + result.message());
        }
        List<ExternalMcpConnectivityService.ToolMeta> localTools = filterLocalTools(result.tools());
        return ApiResponse.ok(new ToolListResponse(
                LOCAL_CONNECTION_ID,
                LOCAL_CONNECTION_NAME,
                result.serverName(),
                localTools));
    }

    @GetMapping("/connections/{id}/tools")
    public ApiResponse<ToolListResponse> listSavedConnectionTools(@PathVariable("id") Long id) {
        ExternalMcpConnection row = connectionService.getById(id);
        if (row == null) {
            return ApiResponse.fail("连接不存在");
        }
        ExternalMcpConnectivityService.ToolListResult result = connectivityService.listTools(toConnectionProperties(row), externalMcpProperties);
        if (!result.success()) {
            return ApiResponse.fail("获取工具列表失败: " + result.message());
        }
        return ApiResponse.ok(new ToolListResponse(
                String.valueOf(id),
                row.getConnectionName(),
                result.serverName(),
                result.tools()));
    }

    @PostMapping("/tools/local/invoke")
    public ApiResponse<InvokeToolResponse> invokeLocalTool(@RequestBody InvokeToolRequest request) {
        return invokeByConnection(localConnection(), request, LOCAL_CONNECTION_ID, LOCAL_CONNECTION_NAME);
    }

    @PostMapping("/connections/{id}/tools/invoke")
    public ApiResponse<InvokeToolResponse> invokeSavedConnectionTool(@PathVariable("id") Long id, @RequestBody InvokeToolRequest request) {
        ExternalMcpConnection row = connectionService.getById(id);
        if (row == null) {
            return ApiResponse.fail("连接不存在");
        }
        return invokeByConnection(toConnectionProperties(row), request, String.valueOf(id), row.getConnectionName());
    }

    @DeleteMapping("/connections/{id}")
    public ApiResponse<String> deleteConnection(@PathVariable("id") Long id) {
        ExternalMcpConnection row = connectionService.getById(id);
        if (row == null) {
            return ApiResponse.fail("连接不存在");
        }
        connectionService.removeById(id);
        externalMcpConfiguration.refreshExternalMcpClients(externalMcpClients, externalMcpProperties);
        return ApiResponse.ok("删除成功并已刷新运行中 MCP 客户端");
    }

    @PostMapping("/connections/refresh")
    public ApiResponse<String> refreshConnections() {
        externalMcpConfiguration.refreshExternalMcpClients(externalMcpClients, externalMcpProperties);
        return ApiResponse.ok("刷新成功");
    }

    private ConnectionDto toDto(ExternalMcpConnection row) {
        return new ConnectionDto(
                row.getId(),
                row.getConnectionName(),
                row.getEnabled(),
                row.getTransport(),
                row.getUrl(),
                row.getEndpoint(),
                parseHeaders(row.getHeaders()),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    private String normalizeTransport(String transport) {
        return transportOf(transport).name();
    }

    private ExternalMcpProperties.ConnectionProperties toConnectionProperties(ExternalMcpConnection row) {
        ExternalMcpProperties.ConnectionProperties connection = new ExternalMcpProperties.ConnectionProperties();
        connection.setEnabled(Boolean.TRUE.equals(row.getEnabled()));
        connection.setTransport(transportOf(row.getTransport()));
        connection.setUrl(row.getUrl());
        connection.setEndpoint(row.getEndpoint());
        connection.setHeaders(parseHeaders(row.getHeaders()));
        return connection;
    }

    private ExternalMcpProperties.ConnectionProperties localConnection() {
        ExternalMcpProperties.ConnectionProperties connection = new ExternalMcpProperties.ConnectionProperties();
        connection.setEnabled(true);
        connection.setTransport(ExternalMcpProperties.Transport.STREAMABLE);
        connection.setUrl(localMcpUrl);
        connection.setHeaders(new LinkedHashMap<>());
        return connection;
    }

    private List<ExternalMcpConnectivityService.ToolMeta> filterLocalTools(List<ExternalMcpConnectivityService.ToolMeta> tools) {
        Set<String> localNames = localToolNameSet();
        List<ExternalMcpConnectivityService.ToolMeta> result = new ArrayList<>();
        for (ExternalMcpConnectivityService.ToolMeta tool : tools) {
            if (localNames.contains(tool.name())) {
                result.add(tool);
            }
        }
        return result;
    }

    private Set<String> localToolNameSet() {
        return java.util.Arrays.stream(demoMcpTools.getClass().getMethods())
                .map(method -> method.getAnnotation(Tool.class))
                .filter(annotation -> annotation != null && StringUtils.hasText(annotation.name()))
                .map(Tool::name)
                .collect(Collectors.toSet());
    }

    private ApiResponse<InvokeToolResponse> invokeByConnection(
            ExternalMcpProperties.ConnectionProperties connection,
            InvokeToolRequest request,
            String connectionId,
            String connectionName) {
        if (request == null || !StringUtils.hasText(request.toolName())) {
            return ApiResponse.fail("toolName 不能为空");
        }
        Map<String, Object> arguments = request.arguments() == null ? new LinkedHashMap<>() : request.arguments();
        ExternalMcpConnectivityService.InvokeResult result = connectivityService.invokeTool(
                connection,
                externalMcpProperties,
                request.toolName().trim(),
                arguments);
        InvokeToolResponse response = new InvokeToolResponse(
                connectionId,
                connectionName,
                result.success(),
                result.toolName(),
                result.serverName(),
                result.textResult(),
                result.rawResultJson(),
                result.message());
        if (!result.success()) {
            return ApiResponse.fail("工具调用失败", response);
        }
        return ApiResponse.ok(response);
    }

    private ExternalMcpProperties.Transport transportOf(String transport) {
        if (!StringUtils.hasText(transport)) {
            return ExternalMcpProperties.Transport.SSE;
        }
        String normalized = transport.trim().toUpperCase();
        if ("STREAMABLE_HTTP".equals(normalized) || "STREAMABLE-HTTP".equals(normalized) || "STREAMABLEHTTP".equals(normalized)) {
            return ExternalMcpProperties.Transport.STREAMABLE;
        }
        if ("STREAMABLE".equals(normalized)) {
            return ExternalMcpProperties.Transport.STREAMABLE;
        }
        return ExternalMcpProperties.Transport.SSE;
    }

    private String toHeadersJson(Map<String, String> headers) {
        try {
            Map<String, String> value = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
            return OBJECT_MAPPER.writeValueAsString(value);
        }
        catch (Exception ex) {
            return "{}";
        }
    }

    private Map<String, String> parseHeaders(String headersJson) {
        if (!StringUtils.hasText(headersJson)) {
            return new LinkedHashMap<>();
        }
        try {
            return OBJECT_MAPPER.readValue(headersJson, new TypeReference<>() {
            });
        }
        catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    public record SaveConnectionRequest(
            String connectionName,
            Boolean enabled,
            String transport,
            String url,
            String endpoint,
            Map<String, String> headers) {
    }

    public record TestConnectionRequest(
            String transport,
            String url,
            String endpoint,
            Map<String, String> headers) {
    }

    public record InvokeToolRequest(
            String toolName,
            Map<String, Object> arguments) {
    }

    public record ConnectionDto(
            Long id,
            String connectionName,
            Boolean enabled,
            String transport,
            String url,
            String endpoint,
            Map<String, String> headers,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    public record TestConnectionResponse(
            String connectionName,
            boolean success,
            String serverName,
            int toolCount,
            String message) {
    }

    public record InvokeToolResponse(
            String connectionId,
            String connectionName,
            boolean success,
            String toolName,
            String serverName,
            String textResult,
            String rawResultJson,
            String message) {
    }

    public record ToolListResponse(
            String connectionId,
            String connectionName,
            String serverName,
            List<ExternalMcpConnectivityService.ToolMeta> tools) {
    }

    public record ApiResponse<T>(boolean success, String message, T data) {
        public static <T> ApiResponse<T> ok(T data) {
            return new ApiResponse<>(true, "OK", data);
        }

        public static <T> ApiResponse<T> fail(String message) {
            return new ApiResponse<>(false, message, null);
        }

        public static <T> ApiResponse<T> fail(String message, T data) {
            return new ApiResponse<>(false, message, data);
        }
    }
}
