package me.xiaozhi.mcp.bridge;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import me.xiaozhi.mcp.account.AuthController.AccountChanged;
import me.xiaozhi.mcp.external.TenantMcpRuntime;
import me.xiaozhi.mcp.external.mapper.*;
import me.xiaozhi.mcp.tool.TenantToolDispatcher;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TenantNodeManager {
    private final XiaozhiNodeMapper nodes;
    private final AccountMapper users;
    private final TenantToolDispatcher dispatcher;
    private final TenantMcpRuntime runtime;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(2, Thread.ofPlatform().daemon().name("node-reconnect-", 0).factory());
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore workSlots = new Semaphore(128);
    private final Map<Long, NodeSocket> sockets = new HashMap<>();
    private volatile boolean stopping;
    public TenantNodeManager(XiaozhiNodeMapper nodes, AccountMapper users, TenantToolDispatcher dispatcher, TenantMcpRuntime runtime, ObjectMapper json) {
        this.nodes = nodes; this.users = users; this.dispatcher = dispatcher; this.runtime = runtime; this.json = json;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void ready() { reconcile(); }
    @EventListener
    public void accountChanged(AccountChanged event) {
        runtime.invalidateUser(event.userId()); reconcile();
    }
    @Scheduled(fixedDelay=60000, initialDelay=60000)
    public synchronized void reconcile() {
        if (stopping) return;
        List<XiaozhiNode> activeNodes = nodes.selectList(new LambdaQueryWrapper<XiaozhiNode>().eq(XiaozhiNode::getEnabled, true)
                .orderByAsc(XiaozhiNode::getId).last("LIMIT 512"));
        Set<Long> owners = new HashSet<>(); activeNodes.forEach(n -> owners.add(n.getOwnerId()));
        var enabledUsers = new HashSet<Long>();
        if (!owners.isEmpty()) users.selectList(new LambdaQueryWrapper<me.xiaozhi.mcp.account.Account>()
                .in(me.xiaozhi.mcp.account.Account::getId, owners).eq(me.xiaozhi.mcp.account.Account::getEnabled, true))
                .forEach(u -> enabledUsers.add(u.getId()));
        Map<Long, XiaozhiNode> desired = new HashMap<>();
        activeNodes.stream().filter(n -> enabledUsers.contains(n.getOwnerId())).forEach(n -> desired.put(n.getId(), n));
        for (Long id : List.copyOf(sockets.keySet())) {
            NodeSocket current = sockets.get(id); XiaozhiNode next = desired.get(id);
            if (next == null || !next.equals(current.node)) { sockets.remove(id); current.stop(); }
        }
        desired.forEach((id, n) -> { if (!sockets.containsKey(id)) { var s = new NodeSocket(n); sockets.put(id, s); s.schedule(0); } });
    }
    public synchronized void restart(Long id) {
        NodeSocket s = sockets.remove(id); if (s != null) s.stop();
        reconcile();
    }
    public synchronized Map<String, Object> status(Long id) {
        NodeSocket s = sockets.get(id);
        return s == null ? Map.of("state", "STOPPED", "message", "节点未启用或账号已禁用") : Map.of("state", s.state, "message", s.message);
    }
    public synchronized void toolsChanged(Long owner) {
        sockets.values().stream().filter(s -> s.node.getOwnerId().equals(owner)).forEach(s -> s.send(json.createObjectNode().put("jsonrpc", "2.0").put("method", "notifications/tools/list_changed")));
    }
    @PreDestroy public synchronized void shutdown() {
        stopping = true; sockets.values().forEach(NodeSocket::stop); sockets.clear();
        timer.shutdownNow(); workers.shutdownNow(); http.close();
    }
    private final class NodeSocket {
        final XiaozhiNode node;
        volatile String state = "CONNECTING", message = "等待连接";
        WebSocket socket;
        CompletableFuture<WebSocket> connecting;
        ScheduledFuture<?> retry;
        boolean stopped;
        long generation;
        int failures;
        int queuedSends;
        CompletableFuture<?> sends = CompletableFuture.completedFuture(null);
        NodeSocket(XiaozhiNode node) { this.node = node; }
        synchronized void schedule(long delay) {
            if (stopped || stopping || (retry != null && !retry.isDone())) return;
            retry = timer.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
        }
        synchronized void connect() {
            retry = null; if (stopped || stopping) return;
            long gen = ++generation; state = "CONNECTING"; message = "正在连接";
            try {
                connecting = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(node.getEndpoint()), new Listener(gen));
                connecting.whenComplete((ws, error) -> { if (error != null) disconnected(gen); });
            } catch (Exception ex) { disconnected(gen); }
        }
        synchronized void disconnected(long gen) {
            if (stopped || gen != generation) return;
            ++generation;
            if (socket != null) socket.abort(); socket = null;
            state = "RECONNECTING"; message = "连接断开，自动重试中";
            long delay = Math.min(30000, 2000L << Math.min(failures++, 4));
            schedule(delay + ThreadLocalRandom.current().nextLong(1000));
        }
        synchronized void stop() {
            stopped = true; ++generation;
            if (retry != null) retry.cancel(false);
            if (connecting != null) connecting.cancel(true);
            if (socket != null) socket.abort(); socket = null;
            state = "STOPPED"; message = "已停止";
        }
        synchronized CompletableFuture<?> send(JsonNode payload) {
            if (stopped || socket == null) return CompletableFuture.completedFuture(null);
            if (queuedSends >= 8) { disconnected(generation); return CompletableFuture.failedFuture(new IllegalStateException("发送队列已满")); }
            queuedSends++;
            WebSocket target = socket; long gen = generation;
            sends = sends.handle((v, ex) -> null).thenCompose(v -> target.sendText(payload.toString(), true)).orTimeout(10,TimeUnit.SECONDS);
            sends.whenComplete((v, ex) -> { synchronized (NodeSocket.this) { queuedSends--; if (ex != null) disconnected(gen); } });
            return sends;
        }
        JsonNode process(JsonNode request) {
            String method = request.path("method").asText();
            return switch (method) {
                case "initialize" -> {
                    ObjectNode result = json.createObjectNode();
                    String version = request.path("params").path("protocolVersion").asText("2024-11-05");
                    if (!Set.of("2024-11-05", "2025-03-26", "2025-06-18").contains(version)) version = "2024-11-05";
                    result.put("protocolVersion", version);
                    result.putObject("capabilities").putObject("tools").put("listChanged", true);
                    result.putObject("serverInfo").put("name", "xiaozhi-multi-user").put("version", "1.0.0"); yield result;
                }
                case "ping" -> json.createObjectNode();
                case "notifications/initialized", "notifications/cancelled" -> null;
                case "tools/list" -> dispatcher.nodeTools(node);
                case "tools/call" -> {
                    JsonNode args = request.path("params").path("arguments");
                    if (!args.isMissingNode() && !args.isObject()) throw new IllegalArgumentException("arguments 必须为对象");
                    Map<String,Object> values = args.isObject() ? json.convertValue(args, new TypeReference<>() {}) : Map.of();
                    yield dispatcher.invokeNode(node, request.path("params").path("name").asText(), values);
                }
                default -> throw new UnsupportedOperationException("不支持的方法");
            };
        }
        void handle(String payload, WebSocket source, long gen) {
            try {
                synchronized (this) { if (stopped || gen != generation) return; }
                JsonNode request;
                try { request = json.readTree(payload); } catch (Exception ex) { send(error(null, -32700, "JSON 格式无效")); return; }
                if (!request.isObject() || !"2.0".equals(request.path("jsonrpc").asText()) || !request.path("method").isTextual()) {
                    send(error(request.get("id"), -32600, "请求格式无效")); return;
                }
                // JSON-RPC notifications never generate a response or execute a tool call.
                if (!request.has("id")) return;
                try {
                    JsonNode result = process(request);
                    if (result != null) {
                        ObjectNode response = json.createObjectNode().put("jsonrpc", "2.0");
                        response.set("id", request.get("id")); response.set("result", result); send(response).join();
                    }
                } catch (Exception ex) {
                    int code = ex instanceof UnsupportedOperationException ? -32601 : ex instanceof IllegalArgumentException ? -32602 : -32603;
                    send(error(request.get("id"), code, code == -32601 ? "不支持的方法" : "工具请求失败，请检查绑定、参数及服务状态")).join();
                }
            } finally { workSlots.release(); source.request(1); }
        }
        ObjectNode error(JsonNode id, int code, String text) {
            ObjectNode result = json.createObjectNode().put("jsonrpc", "2.0");
            result.set("id", id == null ? json.nullNode() : id); result.putObject("error").put("code", code).put("message", text); return result;
        }
        private final class Listener implements WebSocket.Listener {
            final long gen;
            final StringBuilder buffer = new StringBuilder();
            Listener(long gen) { this.gen = gen; }
            @Override public void onOpen(WebSocket ws) {
                synchronized (NodeSocket.this) {
                    if (stopped || gen != generation) { ws.abort(); return; }
                    socket = ws; failures = 0; state = "CONNECTED"; message = "已连接";
                }
                ws.request(1);
            }
            @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                if (buffer.length() + data.length() > 1_048_576) { ws.abort(); disconnected(gen); return null; }
                buffer.append(data);
                if (!last) { ws.request(1); return null; }
                String payload = buffer.toString(); buffer.setLength(0);
                if (!workSlots.tryAcquire()) { ws.abort(); disconnected(gen); return null; }
                try { workers.submit(() -> handle(payload, ws, gen)); }
                catch (RejectedExecutionException ex) { workSlots.release(); ws.abort(); }
                return null;
            }
            @Override public CompletionStage<?> onClose(WebSocket ws, int status, String reason) { disconnected(gen); return null; }
            @Override public void onError(WebSocket ws, Throwable error) { disconnected(gen); }
        }
    }
}
