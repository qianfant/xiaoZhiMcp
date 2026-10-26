package me.xiaozhi.mcp.external;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import com.fasterxml.jackson.databind.*;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import me.xiaozhi.mcp.external.entity.ExternalMcpConnection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Bounded lazy client pool. No network operation is performed under the pool lock. */
@Service
public class TenantMcpRuntime {
    private final Map<Long, Entry> entries = new HashMap<>();
    private final Semaphore concurrent = new Semaphore(32);
    private final OwnedConnections owned;
    private final ExternalMcpConnectivityService factory;
    private final ExternalMcpProperties properties;
    private final ObjectMapper json;
    private final int capacity;
    public TenantMcpRuntime(OwnedConnections owned, ExternalMcpConnectivityService factory, ExternalMcpProperties properties,
            ObjectMapper json, @Value("${xiaozhi.runtime.max-clients:256}") int capacity) {
        this.owned = owned; this.factory = factory; this.properties = properties; this.json = json; this.capacity = capacity;
    }
    public JsonNode tools(ExternalMcpConnection row) {
        return use(row, e -> {
            if (e.tools == null || System.nanoTime() - e.toolsAt > Duration.ofSeconds(30).toNanos()) {
                e.tools = json.valueToTree(e.client.listTools()); e.toolsAt = System.nanoTime();
            }
            return e.tools.deepCopy();
        });
    }
    public ExternalMcpConnectivityService.TestResult test(ExternalMcpProperties.ConnectionProperties connection) {
        if (!concurrent.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "MCP 请求繁忙，请稍后重试");
        try { return factory.testConnection(connection, properties); } finally { concurrent.release(); }
    }
    public JsonNode invoke(ExternalMcpConnection row, String name, Map<String,Object> args) {
        return use(row, e -> json.valueToTree(e.client.callTool(new McpSchema.CallToolRequest(name, args))));
    }
    private <T> T use(ExternalMcpConnection row, Function<Entry,T> action) {
        if (!Boolean.TRUE.equals(row.getEnabled())) throw new ResponseStatusException(HttpStatus.CONFLICT, "连接已禁用");
        if (!concurrent.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "MCP 请求繁忙，请稍后重试");
        Entry entry;
        try {
            synchronized (entries) {
                entry = entries.get(row.getId());
                if (entry == null) {
                    if (entries.size() >= capacity) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "MCP 客户端容量已满，请稍后重试");
                    entry = new Entry(row.getOwnerId()); entries.put(row.getId(), entry);
                }
            }
            try { entry.lock.lockInterruptibly(); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "请求已取消"); }
            try {
                if (entry.closed) throw new ResponseStatusException(HttpStatus.CONFLICT, "连接配置已变更，请重试");
                // Re-read after obtaining the client lock: in-flight edits/deletes must not resurrect stale credentials.
                row = owned.require(row.getOwnerId(), row.getId());
                if (!Boolean.TRUE.equals(row.getEnabled())) throw new ResponseStatusException(HttpStatus.CONFLICT, "连接已禁用");
                String fingerprint = row.getUrl() + "\n" + row.getTransport() + "\n" + row.getEndpoint() + "\n" + row.getHeaders();
                if (!fingerprint.equals(entry.fingerprint) && entry.client != null) {
                    try { entry.client.close(); } catch (Exception ignored) {}
                    entry.client = null; entry.tools = null;
                }
                entry.fingerprint = fingerprint;
                entry.lastUsed = System.nanoTime();
                try {
                    if (entry.client == null) {
                        entry.client = factory.openClient(owned.properties(row), properties, "tenant-" + row.getOwnerId() + "-" + row.getId());
                        entry.client.initialize();
                    }
                    return action.apply(entry);
                } catch (Exception ex) {
                    close(entry);
                    synchronized (entries) { entries.remove(row.getId(), entry); }
                    if (ex instanceof ResponseStatusException status) throw status;
                    // Do not return endpoint tokens, request headers or remote exception URLs to the browser.
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "远程 MCP 请求失败，请检查连接配置");
                } finally { entry.lastUsed = System.nanoTime(); }
            } finally { entry.lock.unlock(); }
        } finally { concurrent.release(); }
    }
    public void invalidate(Long id) {
        Entry e; synchronized (entries) { e = entries.remove(id); }
        if (e != null) { e.lock.lock(); try { close(e); } finally { e.lock.unlock(); } }
    }
    public void invalidateUser(Long userId) {
        List<Long> ids;
        synchronized (entries) { ids = entries.entrySet().stream().filter(e -> e.getValue().owner.equals(userId)).map(Map.Entry::getKey).toList(); }
        ids.forEach(this::invalidate);
    }
    @Scheduled(fixedDelay = 60000)
    public void evictIdle() {
        List<Long> ids;
        synchronized (entries) { ids = entries.entrySet().stream().filter(e -> System.nanoTime() - e.getValue().lastUsed > Duration.ofMinutes(5).toNanos()).map(Map.Entry::getKey).toList(); }
        ids.forEach(this::invalidate);
    }
    @PreDestroy public void shutdown() { List<Long> ids; synchronized (entries) { ids = List.copyOf(entries.keySet()); } ids.forEach(this::invalidate); }
    private void close(Entry e) {
        e.closed = true;
        if (e.client != null) { try { e.client.close(); } catch (Exception ignored) {} e.client = null; }
    }
    private static class Entry {
        final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        final Long owner; String fingerprint; McpSyncClient client; JsonNode tools; long toolsAt; volatile long lastUsed = System.nanoTime(); boolean closed;
        Entry(Long owner) { this.owner = owner; }
    }
}
