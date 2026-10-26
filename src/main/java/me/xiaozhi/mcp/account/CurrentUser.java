package me.xiaozhi.mcp.account;

import java.io.Serializable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

public record CurrentUser(Long id, String username, String role, int authVersion) implements Serializable {
    public static CurrentUser require() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser user) return user;
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
    }
    public static void requireAdmin() {
        if (!"ADMIN".equals(require().role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
    }
}
