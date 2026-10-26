package me.xiaozhi.mcp.tool;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import me.xiaozhi.mcp.account.*;
import me.xiaozhi.mcp.bridge.XiaozhiNode;
import me.xiaozhi.mcp.external.*;
import me.xiaozhi.mcp.external.mapper.AccountMapper;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TenantToolDispatcher {
    private final Map<String, ToolCallback> local = new LinkedHashMap<>();
    private final ObjectMapper json;
    private final OwnedConnections owned;
    private final TenantMcpRuntime runtime;
    private final AccountMapper accounts;
    private final Semaphore calls = new Semaphore(64);
    private final java.util.concurrent.ExecutorService discovery = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore discoverySlots = new Semaphore(32);
    // Fixed stripes bound memory and impose a per-user concurrency ceiling.
    private final Semaphore[] userCalls = new Semaphore[256];
    public TenantToolDispatcher(DemoMcpTools tools, ObjectMapper json, OwnedConnections owned, TenantMcpRuntime runtime, AccountMapper accounts) {
        for (ToolCallback callback : ToolCallbacks.from(tools)) local.put(callback.getToolDefinition().name(), callback);
        Arrays.setAll(userCalls, i -> new Semaphore(8));
        this.json = json; this.owned = owned; this.runtime = runtime; this.accounts = accounts;
    }
    public JsonNode localTools() {
        ArrayNode result = json.createArrayNode();
        local.forEach((name, callback) -> {
            var def = callback.getToolDefinition();
            ObjectNode t = result.addObject(); t.put("name", name); t.put("description", def.description());
            try { t.set("inputSchema", json.readTree(def.inputSchema())); } catch (Exception ex) { throw new IllegalStateException(ex); }
        });
        return json.createObjectNode().set("tools", result);
    }
    public JsonNode invokeLocal(Long owner, String name, Map<String,Object> args) {
        return limited(owner, () -> {
            ToolCallback callback = local.get(name);
            if (callback == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在");
            try {
                String text = ToolUserContext.call(owner, () -> {
                    try { return callback.call(json.writeValueAsString(args)); } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalArgumentException(ex); }
                });
                ObjectNode result = json.createObjectNode(); result.put("isError", false);
                result.putArray("content").addObject().put("type", "text").put("text", text);
                return result;
            } catch (ResponseStatusException ex) { throw ex; }
            catch (Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工具执行失败，请检查参数和服务配置"); }
        });
    }
    public JsonNode invokeRemote(Long owner, Long id, String name, Map<String,Object> args) {
        return limited(owner, () -> runtime.invoke(owned.require(owner, id), name, args));
    }
    public List<Long> connectionIds(XiaozhiNode node) {
        try { return json.readValue(node.getConnectionIds(), new TypeReference<List<Long>>() {}); }
        catch (Exception ex) { throw new IllegalArgumentException("节点绑定配置无效"); }
    }
    public JsonNode nodeTools(XiaozhiNode node) {
        return limited(node.getOwnerId(), () -> discoverNodeTools(node));
    }
    private JsonNode discoverNodeTools(XiaozhiNode node) {
        ArrayNode tools = json.createArrayNode();
        if (Boolean.TRUE.equals(node.getLocalTools())) tools.addAll((ArrayNode) localTools().path("tools"));
        var pending = new ArrayList<java.util.concurrent.Future<ArrayNode>>();
        for (Long id : connectionIds(node)) {
            pending.add(discovery.submit(() -> {
              if (!discoverySlots.tryAcquire()) return json.createArrayNode();
              try {
                ArrayNode remote = json.createArrayNode();
                var row = owned.require(node.getOwnerId(), id);
                if (!Boolean.TRUE.equals(row.getEnabled())) return remote;
                for (JsonNode tool : runtime.tools(row).path("tools")) {
                    if (remote.size() >= 1000) break;
                    ObjectNode copy = tool.deepCopy();
                    copy.put("name", alias(id, tool.path("name").asText()));
                    copy.put("description", "[" + row.getConnectionName() + "] " + tool.path("description").asText()); remote.add(copy);
                }
                return remote;
              } finally { discoverySlots.release(); }
            }));
        }
        // One aggregate deadline instead of N sequential connection timeouts.
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
        for (var task : pending) {
            try { tools.addAll(task.get(Math.max(1, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS)); }
            catch (Exception ex) { task.cancel(true); }
        }
        return json.createObjectNode().set("tools", tools);
    }
    @jakarta.annotation.PreDestroy public void shutdown() { discovery.shutdownNow(); }
    public JsonNode invokeNode(XiaozhiNode node, String name, Map<String,Object> args) {
        if (Boolean.TRUE.equals(node.getLocalTools()) && local.containsKey(name)) return invokeLocal(node.getOwnerId(), name, args);
        for (Long id : connectionIds(node)) {
            // Route directly to the source connection; unrelated failed connections cannot block a call.
            if (!name.startsWith("c" + id + "_")) continue;
            var row = owned.require(node.getOwnerId(), id);
            if (!Boolean.TRUE.equals(row.getEnabled())) continue;
            for (JsonNode tool : runtime.tools(row).path("tools")) {
                String original = tool.path("name").asText();
                if (alias(id, original).equals(name)) return invokeRemote(node.getOwnerId(), id, original, args);
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前节点未绑定此工具");
    }
    private void checkAccount(Long owner) {
        Account user = accounts.selectById(owner);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已禁用");
    }
    private <T> T limited(Long owner, java.util.function.Supplier<T> action) {
        checkAccount(owner);
        Semaphore user = userCalls[Math.floorMod(owner.hashCode(), userCalls.length)];
        if (!user.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "当前用户请求繁忙");
        if (!calls.tryAcquire()) { user.release(); throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "服务繁忙"); }
        try { return action.get(); } finally { calls.release(); user.release(); }
    }
    static String alias(Long id, String name) {
        try { return "c" + id + "_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8))).substring(0, 24); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
