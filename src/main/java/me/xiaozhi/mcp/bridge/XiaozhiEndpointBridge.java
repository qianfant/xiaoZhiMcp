package me.xiaozhi.mcp.bridge;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@EnableConfigurationProperties(XiaozhiBridgeProperties.class)
@ConditionalOnProperty(prefix = "xiaozhi.bridge", name = "enabled", havingValue = "true", matchIfMissing = true)
public class XiaozhiEndpointBridge {

    private static final Logger log = LoggerFactory.getLogger(XiaozhiEndpointBridge.class);
    private static final String JSON_RPC_VERSION = "2.0";
    private static final String LOCAL_PROTOCOL_VERSION = "2025-03-26";
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private final XiaozhiBridgeProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final AtomicInteger reconnectAttempt = new AtomicInteger();
    private final AtomicInteger localRequestId = new AtomicInteger(10_000);
    private final Object localLifecycleMonitor = new Object();

    private volatile WebSocket webSocket;
    private volatile String localSessionId;
    private volatile JsonNode cachedInitializeResult;
    private volatile JsonNode cachedToolsListResult;

    public XiaozhiEndpointBridge(XiaozhiBridgeProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "xiaozhi-mcp-bridge");
            thread.setDaemon(true);
            return thread;
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void connectWhenReady() {
        if (!StringUtils.hasText(properties.getEndpoint())) {
            log.warn("未配置 xiaozhi.bridge.endpoint 或 XIAOZHI_MCP_ENDPOINT，跳过接入点桥接");
            return;
        }
        scheduleReconnect(Duration.ZERO);
    }

    @PreDestroy
    public void shutdown() {
        WebSocket current = this.webSocket;
        if (current != null) {
            current.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }
        scheduler.shutdownNow();
    }

    private void scheduleReconnect(Duration delay) {
        long delayMillis = Math.max(0, delay.toMillis());
        scheduler.schedule(this::connectInternal, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void connectInternal() {
        URI endpoint = URI.create(properties.getEndpoint());
        log.info("正在连接小智 MCP 接入点: {}", endpoint);

        httpClient.newWebSocketBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .buildAsync(endpoint, new BridgeWebSocketListener())
                .whenComplete((socket, throwable) -> {
                    if (throwable != null) {
                        log.error("连接小智 MCP 接入点失败: {}", throwable.getMessage());
                        scheduleReconnect(nextDelay());
                        return;
                    }
                    this.webSocket = socket;
                    this.localSessionId = null;
                    reconnectAttempt.set(0);
                    log.info("已连接小智 MCP 接入点");
                });
    }

    private Duration nextDelay() {
        long initial = properties.getInitialReconnectDelay().toMillis();
        long max = properties.getMaxReconnectDelay().toMillis();
        int attempt = Math.max(0, reconnectAttempt.getAndIncrement());
        long delay = initial * (1L << Math.min(attempt, 6));
        return Duration.ofMillis(Math.min(delay, max));
    }

    private void handleWebSocketMessage(String payload) {
        JsonNode message = null;
        try {
            message = objectMapper.readTree(payload);
            String method = message.path("method").asText();

            if (!StringUtils.hasText(method)) {
                log.debug("忽略非请求类消息: {}", payload);
                return;
            }

            switch (method) {
                case "initialize" -> handleInitialize(message);
                case "notifications/initialized" -> handleInitialized();
                case "tools/list" -> handleToolsList(message);
                case "ping" -> handlePing(message);
                default -> forwardRequestToLocalServer(message);
            }
        }
        catch (Exception ex) {
            log.error("处理 WebSocket 消息失败: {}", ex.getMessage(), ex);
            sendError(message == null ? null : message.get("id"), -32603, ex.getMessage());
        }
    }

    private HttpResponse<String> forwardToLocalServer(String payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(properties.getLocalMcpUrl()))
                .timeout(properties.getRequestTimeout())
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json");

        if (StringUtils.hasText(localSessionId)) {
            builder.header(SESSION_HEADER, localSessionId);
        }

        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void handleInitialize(JsonNode message) throws Exception {
        JsonNode localInitialize = initializeLocalServerIfNecessary();

        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", requestedProtocolVersion(message, localInitialize));
        result.set("capabilities", safeObjectNode(localInitialize.path("result").path("capabilities")));
        result.set("serverInfo", safeObjectNode(localInitialize.path("result").path("serverInfo")));

        sendResponse(message.get("id"), result);
        warmUpToolsListCache();
    }

    private void handleInitialized() throws Exception {
        initializeLocalServerIfNecessary();
        warmUpToolsListCache();
    }

    private void handleToolsList(JsonNode message) throws Exception {
        initializeLocalServerIfNecessary();
        JsonNode toolsList = warmUpToolsListCache();
        sendResponse(message.get("id"), safeObjectNode(toolsList.path("result")));
    }

    private void handlePing(JsonNode message) {
        sendResponse(message.get("id"), objectMapper.createObjectNode());
    }

    private void forwardRequestToLocalServer(JsonNode message) throws Exception {
        initializeLocalServerIfNecessary();
        HttpResponse<String> localResponse = forwardToLocalServer(objectMapper.writeValueAsString(message));
        captureSessionId(localResponse.headers());
        relayLocalResponse(message, localResponse);
    }

    private JsonNode initializeLocalServerIfNecessary() throws Exception {
        if (cachedInitializeResult != null) {
            return cachedInitializeResult;
        }

        synchronized (localLifecycleMonitor) {
            if (cachedInitializeResult != null) {
                return cachedInitializeResult;
            }

            ObjectNode initializeRequest = objectMapper.createObjectNode();
            initializeRequest.put("jsonrpc", JSON_RPC_VERSION);
            initializeRequest.put("id", nextLocalRequestId());
            initializeRequest.put("method", "initialize");

            ObjectNode params = initializeRequest.putObject("params");
            params.put("protocolVersion", LOCAL_PROTOCOL_VERSION);
            params.putObject("capabilities");
            ObjectNode clientInfo = params.putObject("clientInfo");
            clientInfo.put("name", "xiaozhi-spring-ai-bridge");
            clientInfo.put("version", "0.0.1");

            JsonNode initializeResponse = sendLocalRequest(initializeRequest);
            this.cachedInitializeResult = initializeResponse;

            ObjectNode initializedNotification = objectMapper.createObjectNode();
            initializedNotification.put("jsonrpc", JSON_RPC_VERSION);
            initializedNotification.put("method", "notifications/initialized");
            sendLocalNotification(initializedNotification);

            log.info("本地 MCP 服务初始化完成");
            return initializeResponse;
        }
    }

    private JsonNode warmUpToolsListCache() throws Exception {
        if (cachedToolsListResult != null) {
            return cachedToolsListResult;
        }

        synchronized (localLifecycleMonitor) {
            if (cachedToolsListResult != null) {
                return cachedToolsListResult;
            }

            ObjectNode toolsListRequest = objectMapper.createObjectNode();
            toolsListRequest.put("jsonrpc", JSON_RPC_VERSION);
            toolsListRequest.put("id", nextLocalRequestId());
            toolsListRequest.put("method", "tools/list");
            toolsListRequest.putObject("params");

            JsonNode toolsListResponse = sendLocalRequest(toolsListRequest);
            this.cachedToolsListResult = toolsListResponse;

            int toolCount = toolsListResponse.path("result").path("tools").isArray()
                    ? toolsListResponse.path("result").path("tools").size()
                    : 0;
            log.info("已从本地 MCP 服务加载 {} 个工具", toolCount);

            sendToolListChangedNotification();
            return toolsListResponse;
        }
    }

    private JsonNode sendLocalRequest(JsonNode requestBody) throws Exception {
        HttpResponse<String> response = forwardToLocalServer(objectMapper.writeValueAsString(requestBody));
        captureSessionId(response.headers());

        List<String> outgoingMessages = extractOutgoingMessages(response);
        if (outgoingMessages.isEmpty()) {
            throw new IllegalStateException("本地 MCP 服务未返回响应");
        }

        return objectMapper.readTree(outgoingMessages.getFirst());
    }

    private void sendLocalNotification(JsonNode requestBody) throws Exception {
        HttpResponse<String> response = forwardToLocalServer(objectMapper.writeValueAsString(requestBody));
        captureSessionId(response.headers());
        if (response.statusCode() != 202 && StringUtils.hasText(response.body())) {
            log.debug("本地 MCP 通知返回内容: {}", response.body());
        }
    }

    private void sendToolListChangedNotification() {
        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("jsonrpc", JSON_RPC_VERSION);
        notification.put("method", "notifications/tools/list_changed");
        sendToWebSocket(notification.toString());
    }

    private String requestedProtocolVersion(JsonNode message, JsonNode localInitialize) {
        String requested = message.path("params").path("protocolVersion").asText();
        if (StringUtils.hasText(requested)) {
            return requested;
        }
        String local = localInitialize.path("result").path("protocolVersion").asText();
        if (StringUtils.hasText(local)) {
            return local;
        }
        return LOCAL_PROTOCOL_VERSION;
    }

    private int nextLocalRequestId() {
        return localRequestId.incrementAndGet();
    }

    private JsonNode safeObjectNode(JsonNode node) {
        return node != null && node.isObject() ? node.deepCopy() : objectMapper.createObjectNode();
    }

    private void captureSessionId(HttpHeaders headers) {
        Optional<String> sessionId = headers.firstValue(SESSION_HEADER);
        sessionId.ifPresent(value -> {
            if (!value.equals(this.localSessionId)) {
                this.localSessionId = value;
                log.info("已获取本地 MCP 会话: {}", value);
            }
        });
    }

    private void relayLocalResponse(JsonNode sourceMessage, HttpResponse<String> response) {
        if (response.statusCode() == 202 || !StringUtils.hasText(response.body())) {
            return;
        }

        List<String> outgoingMessages = extractOutgoingMessages(response);
        if (outgoingMessages.isEmpty()) {
            if (sourceMessage.has("id")) {
                sendToWebSocket(buildServerError(sourceMessage.get("id")));
            }
            return;
        }

        outgoingMessages.forEach(this::sendToWebSocket);
    }

    private List<String> extractOutgoingMessages(HttpResponse<String> response) {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (contentType.contains("text/event-stream")) {
            return parseSseMessages(response.body());
        }

        List<String> messages = new ArrayList<>();
        messages.add(response.body());
        return messages;
    }

    private List<String> parseSseMessages(String body) {
        List<String> messages = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(body))) {
            String line;
            StringBuilder currentData = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    appendSseMessage(messages, currentData);
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (!currentData.isEmpty()) {
                        currentData.append('\n');
                    }
                    currentData.append(line.substring(5).trim());
                }
            }
            appendSseMessage(messages, currentData);
        }
        catch (Exception ex) {
            log.error("解析 SSE 响应失败: {}", ex.getMessage(), ex);
        }
        return messages;
    }

    private void appendSseMessage(List<String> messages, StringBuilder currentData) {
        if (currentData.isEmpty()) {
            return;
        }
        messages.add(currentData.toString());
        currentData.setLength(0);
    }

    private String buildServerError(JsonNode idNode) {
        try {
            String idValue = objectMapper.writeValueAsString(objectMapper.treeToValue(idNode, Object.class));
            return """
                    {"jsonrpc":"2.0","id":%s,"error":{"code":-32603,"message":"Local MCP server returned an empty response"}}
                    """.formatted(idValue);
        }
        catch (Exception ex) {
            return """
                    {"jsonrpc":"2.0","id":null,"error":{"code":-32603,"message":"Local MCP server returned an empty response"}}
                    """;
        }
    }

    private void sendToWebSocket(String message) {
        WebSocket current = this.webSocket;
        if (current == null) {
            log.warn("WebSocket 尚未建立，忽略返回消息");
            return;
        }
        current.sendText(message, true)
                .exceptionally(throwable -> {
                    log.error("发送消息到小智接入点失败: {}", throwable.getMessage());
                    return null;
                });
    }

    private void handleDisconnect(int statusCode, String reason) {
        this.localSessionId = null;
        this.cachedInitializeResult = null;
        this.cachedToolsListResult = null;
        this.webSocket = null;
        log.warn("与小智 MCP 接入点断开连接，status={}, reason={}", statusCode, reason);
        scheduleReconnect(nextDelay());
    }

    private void sendResponse(JsonNode idNode, JsonNode resultNode) {
        if (idNode == null || idNode.isMissingNode()) {
            return;
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", JSON_RPC_VERSION);
        response.set("id", idNode.deepCopy());
        response.set("result", resultNode == null ? objectMapper.createObjectNode() : resultNode);
        sendToWebSocket(response.toString());
    }

    private void sendError(JsonNode idNode, int code, String message) {
        if (idNode == null || idNode.isMissingNode()) {
            return;
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", JSON_RPC_VERSION);
        response.set("id", idNode.deepCopy());

        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", StringUtils.hasText(message) ? message : "Unexpected bridge error");
        sendToWebSocket(response.toString());
    }

    private final class BridgeWebSocketListener implements WebSocket.Listener {

        private final StringBuilder textBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String payload = textBuffer.toString();
                textBuffer.setLength(0);
                CompletableFuture.runAsync(() -> handleWebSocketMessage(payload), scheduler);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            handleDisconnect(statusCode, reason);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("小智接入点 WebSocket 异常: {}", error.getMessage(), error);
            handleDisconnect(-1, error.getMessage());
        }
    }
}
