package me.xiaozhi.mcp.bridge;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class XiaozhiEndpointBridgeTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServer httpServer;
    private RecordingWebSocket recordingWebSocket;
    private List<JsonNode> localRequests;
    private XiaozhiEndpointBridge bridge;

    @BeforeEach
    void setUp() throws Exception {
        this.localRequests = new CopyOnWriteArrayList<>();
        this.recordingWebSocket = new RecordingWebSocket();

        this.httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        this.httpServer.createContext("/mcp", this::handleLocalMcpRequest);
        this.httpServer.start();

        XiaozhiBridgeProperties properties = new XiaozhiBridgeProperties();
        properties.setEnabled(true);
        properties.setEndpoint("wss://example.test/mcp");
        properties.setLocalMcpUrl("http://127.0.0.1:" + httpServer.getAddress().getPort() + "/mcp");

        this.bridge = new XiaozhiEndpointBridge(properties, objectMapper);
        ReflectionTestUtils.setField(bridge, "webSocket", recordingWebSocket);
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void initializeShouldWarmUpLocalServerAndExposeTools() throws Exception {
        invokeHandleWebSocketMessage("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"xiaozhi","version":"1.0.0"}}}
                """);

        assertThat(localRequests).extracting(node -> node.path("method").asText())
                .containsExactly("initialize", "notifications/initialized", "tools/list");

        List<JsonNode> outboundMessages = parseOutboundMessages();
        assertThat(outboundMessages).hasSize(2);

        JsonNode initializeResponse = outboundMessages.get(0);
        assertThat(initializeResponse.path("id").asInt()).isEqualTo(1);
        assertThat(initializeResponse.path("result").path("protocolVersion").asText()).isEqualTo("2024-11-05");
        assertThat(initializeResponse.path("result").path("capabilities").path("tools").isObject()).isTrue();

        JsonNode notification = outboundMessages.get(1);
        assertThat(notification.path("method").asText()).isEqualTo("notifications/tools/list_changed");

        recordingWebSocket.messages().clear();

        invokeHandleWebSocketMessage("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """);

        JsonNode toolsListResponse = parseOutboundMessages().getFirst();
        assertThat(toolsListResponse.path("id").asInt()).isEqualTo(2);
        assertThat(toolsListResponse.path("result").path("tools")).hasSize(1);
        assertThat(toolsListResponse.path("result").path("tools").get(0).path("name").asText()).isEqualTo("add_numbers");
    }

    private void invokeHandleWebSocketMessage(String payload) throws Exception {
        Method method = XiaozhiEndpointBridge.class.getDeclaredMethod("handleWebSocketMessage", String.class);
        method.setAccessible(true);
        method.invoke(bridge, payload);
    }

    private List<JsonNode> parseOutboundMessages() {
        List<JsonNode> messages = new ArrayList<>();
        for (String message : recordingWebSocket.messages()) {
            try {
                messages.add(objectMapper.readTree(message));
            }
            catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }
        return messages;
    }

    private void handleLocalMcpRequest(HttpExchange exchange) throws IOException {
        try (var requestBody = exchange.getRequestBody()) {
            JsonNode body = objectMapper.readTree(requestBody);
            localRequests.add(body);

            String method = body.path("method").asText();
            if ("initialize".equals(method)) {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.getResponseHeaders().add("Mcp-Session-Id", "local-session-1");
                writeResponse(exchange, 200, """
                        {"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"serverInfo":{"name":"local-spring-ai","version":"1.0.0"}}}
                        """.formatted(body.path("id").asText()));
                return;
            }

            if ("notifications/initialized".equals(method)) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }

            if ("tools/list".equals(method)) {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                writeResponse(exchange, 200, """
                        {"jsonrpc":"2.0","id":%s,"result":{"tools":[{"name":"add_numbers","description":"加法","inputSchema":{"type":"object"}}]}}
                        """.formatted(body.path("id").asText()));
                return;
            }

            exchange.getResponseHeaders().add("Content-Type", "application/json");
            writeResponse(exchange, 200, """
                    {"jsonrpc":"2.0","id":%s,"result":{"content":[{"type":"text","text":"ok"}]}}
                    """.formatted(body.path("id").asText()));
        }
        finally {
            exchange.close();
        }
    }

    private void writeResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private static final class RecordingWebSocket implements WebSocket {

        private final List<String> messages = new CopyOnWriteArrayList<>();

        List<String> messages() {
            return messages;
        }

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            messages.add(data.toString());
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {
        }

        @Override
        public String getSubprotocol() {
            return null;
        }

        @Override
        public boolean isOutputClosed() {
            return false;
        }

        @Override
        public boolean isInputClosed() {
            return false;
        }

        @Override
        public void abort() {
        }
    }
}
