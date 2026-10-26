package me.xiaozhi.mcp.external;

import java.time.LocalDateTime;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.xiaozhi.mcp.account.*;
import me.xiaozhi.mcp.bridge.*;
import me.xiaozhi.mcp.external.entity.ExternalMcpConnection;
import me.xiaozhi.mcp.external.mapper.AccountMapper;
import me.xiaozhi.mcp.external.service.ExternalMcpConnectionService;
import me.xiaozhi.mcp.tool.TenantToolDispatcher;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static me.xiaozhi.mcp.account.AuthController.ok;

@RestController
@RequestMapping("/api/external-mcp")
public class ExternalMcpAdminController {
    private final ExternalMcpConnectionService service;
    private final OwnedConnections owned;
    private final TenantMcpRuntime runtime;
    private final TenantToolDispatcher tools;
    private final TenantNodeManager nodes;
    private final ObjectMapper json;
    private final AccountMapper accounts;
    public ExternalMcpAdminController(ExternalMcpConnectionService service, OwnedConnections owned, TenantMcpRuntime runtime,
            TenantToolDispatcher tools, TenantNodeManager nodes, ObjectMapper json, AccountMapper accounts) {
        this.service = service; this.owned = owned; this.runtime = runtime; this.tools = tools; this.nodes = nodes; this.json = json; this.accounts = accounts;
    }
    @GetMapping("/connections") public Map<String,Object> list() {
        return ok(service.list(OwnedConnections.query(CurrentUser.require().id()).orderByDesc(ExternalMcpConnection::getId).last("LIMIT 100")));
    }
    @PostMapping("/connections")
    @org.springframework.transaction.annotation.Transactional
    public Map<String,Object> create(@RequestBody SaveConnectionRequest request) {
        Long owner = CurrentUser.require().id();
        accounts.lockAdministration();
        if (service.count(OwnedConnections.query(owner)) >= 100) AccountService.bad("每个用户最多 100 个外部连接");
        ExternalMcpConnection row = new ExternalMcpConnection(); row.setOwnerId(owner); apply(row, request);
        row.setCreatedAt(LocalDateTime.now()); service.save(row); return ok(row);
    }
    @PutMapping("/connections/{id}") public Map<String,Object> update(@PathVariable Long id, @RequestBody SaveConnectionRequest request) {
        Long owner = CurrentUser.require().id(); var row = owned.require(owner, id); apply(row, request);
        service.update(row, OwnedConnections.query(owner).eq(ExternalMcpConnection::getId, id));
        runtime.invalidate(id); nodes.toolsChanged(owner); return ok(row);
    }
    @DeleteMapping("/connections/{id}") public Map<String,Object> delete(@PathVariable Long id) {
        Long owner = CurrentUser.require().id(); owned.require(owner, id);
        service.remove(OwnedConnections.query(owner).eq(ExternalMcpConnection::getId, id));
        runtime.invalidate(id); nodes.toolsChanged(owner); return ok("已删除，节点绑定中的失效连接将被忽略");
    }
    @PostMapping("/connections/refresh") public Map<String,Object> refresh() {
        Long owner = CurrentUser.require().id(); runtime.invalidateUser(owner); nodes.toolsChanged(owner); return ok("已刷新当前用户的客户端和工具缓存");
    }
    @PostMapping("/connections/{id}/test") public Map<String,Object> test(@PathVariable Long id) {
        var row = owned.require(CurrentUser.require().id(), id);
        if (!Boolean.TRUE.equals(row.getEnabled())) {
            var result = runtime.test(owned.properties(row));
            if (!result.success()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "连接测试失败，请检查配置");
            return ok(Map.of("toolCount", result.toolCount(), "success", true));
        }
        var result = runtime.tools(row);
        return ok(Map.of("toolCount", result.path("tools").size(), "success", true));
    }
    @PostMapping("/connections/test") public Map<String,Object> testDraft(@RequestBody SaveConnectionRequest request) {
        var row = new ExternalMcpConnection(); row.setOwnerId(CurrentUser.require().id()); apply(row, request);
        var result = runtime.test(owned.properties(row));
        if (!result.success()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "连接测试失败，请检查地址、协议和请求头");
        return ok(Map.of("success", true, "toolCount", result.toolCount()));
    }
    @GetMapping("/connections/{id}/tools") public Map<String,Object> remoteTools(@PathVariable Long id) {
        return ok(runtime.tools(owned.require(CurrentUser.require().id(), id)));
    }
    @GetMapping("/tools/local") public Map<String,Object> localTools() { return ok(tools.localTools()); }
    @PostMapping("/tools/local/invoke") public Map<String,Object> localInvoke(@RequestBody InvokeToolRequest request) {
        validateInvoke(request); return ok(tools.invokeLocal(CurrentUser.require().id(), request.toolName(), arguments(request)));
    }
    @PostMapping("/connections/{id}/tools/invoke") public Map<String,Object> remoteInvoke(@PathVariable Long id, @RequestBody InvokeToolRequest request) {
        validateInvoke(request); return ok(tools.invokeRemote(CurrentUser.require().id(), id, request.toolName(), arguments(request)));
    }
    private Map<String,Object> arguments(InvokeToolRequest r) { return r.arguments() == null ? Map.of() : r.arguments(); }
    private void validateInvoke(InvokeToolRequest request) {
        if (request.toolName() == null || request.toolName().isBlank() || request.toolName().length() > 256) AccountService.bad("工具名称无效");
        try { if (json.writeValueAsBytes(arguments(request)).length > 262144) AccountService.bad("参数不能超过 256KB"); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { AccountService.bad("参数无效"); }
    }
    private void apply(ExternalMcpConnection row, SaveConnectionRequest request) {
        if (request.connectionName() == null || request.connectionName().isBlank() || request.connectionName().length() > 128) AccountService.bad("连接名称需为 1–128 个字符");
        OwnedConnections.validateUrl(request.url(), false);
        String transport = request.transport() == null ? "SSE" : request.transport().toUpperCase(Locale.ROOT);
        if (!Set.of("SSE", "STREAMABLE").contains(transport)) AccountService.bad("传输协议必须是 SSE 或 STREAMABLE");
        if (request.endpoint() != null && request.endpoint().length() > 255) AccountService.bad("端点路径过长");
        var headers = request.headers() == null ? Map.<String,String>of() : request.headers();
        if (headers.size() > 32 || headers.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getValue() == null || !e.getKey().matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+") || e.getValue().contains("\r") || e.getValue().contains("\n") || e.getValue().length() > 4096)) AccountService.bad("请求头格式无效或超过限制");
        try { row.setHeaders(json.writeValueAsString(headers)); } catch (Exception ex) { AccountService.bad("请求头无效"); }
        row.setConnectionName(request.connectionName().trim()); row.setUrl(request.url()); row.setTransport(transport);
        row.setEnabled(!Boolean.FALSE.equals(request.enabled())); row.setEndpoint(request.endpoint()); row.setUpdatedAt(LocalDateTime.now());
    }
    public record SaveConnectionRequest(String connectionName, Boolean enabled, String transport, String url, String endpoint, Map<String,String> headers) {}
    public record InvokeToolRequest(String toolName, Map<String,Object> arguments) {}
}
