package me.xiaozhi.mcp.bridge;

import java.util.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.xiaozhi.mcp.account.*;
import me.xiaozhi.mcp.external.OwnedConnections;
import me.xiaozhi.mcp.external.mapper.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static me.xiaozhi.mcp.account.AuthController.ok;

@RestController
@RequestMapping("/api/nodes")
public class XiaozhiNodeController {
    private final XiaozhiNodeMapper mapper;
    private final TenantNodeManager manager;
    private final OwnedConnections connections;
    private final ObjectMapper json;
    private final AccountMapper accounts;
    private final org.springframework.context.ApplicationEventPublisher events;
    public XiaozhiNodeController(XiaozhiNodeMapper mapper, TenantNodeManager manager, OwnedConnections connections, ObjectMapper json, AccountMapper accounts, org.springframework.context.ApplicationEventPublisher events) {
        this.mapper = mapper; this.manager = manager; this.connections = connections; this.json = json; this.accounts = accounts; this.events = events;
    }
    private LambdaQueryWrapper<XiaozhiNode> query() { return new LambdaQueryWrapper<XiaozhiNode>().eq(XiaozhiNode::getOwnerId, CurrentUser.require().id()); }
    private XiaozhiNode require(Long id) {
        var node = mapper.selectOne(query().eq(XiaozhiNode::getId, id));
        if (node == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "节点不存在"); return node;
    }
    @GetMapping public Map<String,Object> list() {
        return ok(mapper.selectList(query().orderByDesc(XiaozhiNode::getId).last("LIMIT 10")).stream().map(n -> Map.of("node", n, "runtime", manager.status(n.getId()))).toList());
    }
    @PostMapping
    @org.springframework.transaction.annotation.Transactional
    public Map<String,Object> create(@RequestBody NodeRequest request) {
        accounts.lockAdministration();
        if (mapper.selectCount(query()) >= 10 || mapper.selectCount(null) >= 512) AccountService.bad("节点数已达上限（每个用户 10 个，全局 512 个）");
        var node = new XiaozhiNode(); node.setOwnerId(CurrentUser.require().id()); apply(node, request); mapper.insert(node);
        events.publishEvent(new NodeChanged(node.getId())); return ok(node);
    }
    @PutMapping("/{id}") public Map<String,Object> update(@PathVariable Long id, @RequestBody NodeRequest request) {
        var node = require(id); apply(node, request); mapper.update(node, query().eq(XiaozhiNode::getId, id)); manager.restart(id); return ok(node);
    }
    @DeleteMapping("/{id}") public Map<String,Object> delete(@PathVariable Long id) {
        require(id); mapper.delete(query().eq(XiaozhiNode::getId, id)); manager.restart(id); return ok("节点已删除");
    }
    @PostMapping("/{id}/reconnect") public Map<String,Object> reconnect(@PathVariable Long id) {
        require(id); manager.restart(id); return ok("已安排重连");
    }
    @org.springframework.transaction.event.TransactionalEventListener(phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void created(NodeChanged event) { manager.restart(event.id()); }
    private void apply(XiaozhiNode node, NodeRequest r) {
        if (r.name() == null || r.name().isBlank() || r.name().length() > 128) AccountService.bad("节点名称需为 1–128 个字符");
        OwnedConnections.validateUrl(r.endpoint(), true);
        try { node.setEndpointHash(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(r.endpoint().getBytes(java.nio.charset.StandardCharsets.UTF_8)))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        var ids = r.connectionIds() == null ? List.<Long>of() : r.connectionIds().stream().distinct().toList();
        if (ids.size() > 20 || ids.stream().anyMatch(Objects::isNull)) AccountService.bad("每个节点最多绑定 20 个外部连接");
        ids.forEach(id -> connections.require(CurrentUser.require().id(), id));
        try { node.setConnectionIds(json.writeValueAsString(ids)); } catch (Exception ex) { AccountService.bad("绑定参数无效"); }
        node.setName(r.name().trim()); node.setEndpoint(r.endpoint()); node.setEnabled(!Boolean.FALSE.equals(r.enabled())); node.setLocalTools(!Boolean.FALSE.equals(r.localTools()));
    }
    public record NodeRequest(String name, String endpoint, Boolean enabled, Boolean localTools, List<Long> connectionIds) {}
    public record NodeChanged(Long id) {}
}
