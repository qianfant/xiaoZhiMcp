package me.xiaozhi.mcp.external;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.xiaozhi.mcp.external.entity.ExternalMcpConnection;
import me.xiaozhi.mcp.external.service.ExternalMcpConnectionService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.util.*;

@Service
public class OwnedConnections {
    private final ExternalMcpConnectionService service;
    private final ObjectMapper json;
    public OwnedConnections(ExternalMcpConnectionService service, ObjectMapper json) { this.service = service; this.json = json; }
    public ExternalMcpConnection require(Long userId, Long id) {
        var row = service.getOne(query(userId).eq(ExternalMcpConnection::getId, id));
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "连接不存在");
        return row;
    }
    public static LambdaQueryWrapper<ExternalMcpConnection> query(Long userId) {
        return new LambdaQueryWrapper<ExternalMcpConnection>().eq(ExternalMcpConnection::getOwnerId, userId);
    }
    public ExternalMcpProperties.ConnectionProperties properties(ExternalMcpConnection row) {
        var p = new ExternalMcpProperties.ConnectionProperties(); p.setEnabled(row.getEnabled());
        p.setUrl(row.getUrl()); p.setEndpoint(row.getEndpoint());
        p.setTransport(ExternalMcpProperties.Transport.valueOf(row.getTransport()));
        try { p.setHeaders(json.readValue(row.getHeaders() == null ? "{}" : row.getHeaders(), new TypeReference<Map<String,String>>() {})); }
        catch (Exception ex) { throw new IllegalArgumentException("连接请求头无效"); }
        return p;
    }
    public static void validateUrl(String url, boolean websocket) {
        try {
            URI uri = URI.create(url);
            var allowed = websocket ? Set.of("wss", "ws") : Set.of("http", "https");
            if (!allowed.contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || url.length() > 2048)
                throw new IllegalArgumentException("URL 无效");
        } catch (Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, websocket ? "请输入有效的 ws/wss 小智接入点" : "请输入有效的 http/https MCP 地址"); }
    }
}
