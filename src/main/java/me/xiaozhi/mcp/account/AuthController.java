package me.xiaozhi.mcp.account;

import java.util.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import me.xiaozhi.mcp.external.mapper.AccountMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.*;

@RestController
public class AuthController {
    private final AccountService service;
    private final AccountMapper mapper;
    private final HttpSessionSecurityContextRepository repository;
    private final ApplicationEventPublisher events;
    private final boolean registration;
    private final Map<String, Window> attempts = new LinkedHashMap<>();
    public AuthController(AccountService service, AccountMapper mapper, HttpSessionSecurityContextRepository repository,
            ApplicationEventPublisher events, @Value("${xiaozhi.security.registration-enabled:false}") boolean registration) {
        this.service = service; this.mapper = mapper; this.repository = repository; this.events = events; this.registration = registration;
    }
    @GetMapping("/api/auth/options") public Map<String, Object> options() { return ok(Map.of("registrationEnabled", registration)); }
    @GetMapping("/api/auth/csrf") public Map<String, Object> csrf(CsrfToken token) { return ok(Map.of("token", token.getToken())); }
    @PostMapping("/api/auth/login")
    public Map<String, Object> login(@RequestBody Credentials body, HttpServletRequest request, HttpServletResponse response) {
        throttle(request.getRemoteAddr(), 20, 60_000);
        Account account = service.authenticate(body.username(), body.password());
        request.getSession(); request.changeSessionId();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal(account), null, List.of(new SimpleGrantedAuthority("ROLE_" + account.getRole()))));
        SecurityContextHolder.setContext(context); repository.saveContext(context, request, response);
        new HttpSessionCsrfTokenRepository().saveToken(null, request, response);
        return ok(account);
    }
    @PostMapping("/api/auth/register")
    public Map<String, Object> register(@RequestBody Credentials body, HttpServletRequest request) {
        if (!registration) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "注册未开放，请联系管理员创建账号");
        throttle("register:" + request.getRemoteAddr(), 5, 3_600_000);
        return ok(service.create(body.username(), body.password(), "USER"));
    }
    @GetMapping("/api/auth/me") public Map<String, Object> me() { return ok(principal(mapper.selectById(CurrentUser.require().id()))); }
    @PostMapping("/api/auth/logout") public Map<String, Object> logout(HttpServletRequest request) {
        if (request.getSession(false) != null) request.getSession(false).invalidate();
        SecurityContextHolder.clearContext(); return ok("已退出");
    }
    @PostMapping("/api/auth/password") public Map<String, Object> password(@RequestBody PasswordChange body) {
        var user = CurrentUser.require(); service.authenticate(user.username(), body.oldPassword());
        if (body.newPassword() == null || body.newPassword().isBlank()) AccountService.bad("新密码不能为空");
        service.update(user.id(), null, null, body.newPassword());
        events.publishEvent(new AccountChanged(user.id()));
        return ok("密码已更新，请重新登录");
    }
    @GetMapping("/api/admin/users") public Map<String, Object> users(@RequestParam(defaultValue="1") int page) {
        CurrentUser.requireAdmin(); int offset = (Math.max(1, Math.min(page, 100000)) - 1) * 50;
        return ok(Map.of("items", mapper.selectList(new LambdaQueryWrapper<Account>().orderByAsc(Account::getId).last("LIMIT 50 OFFSET " + offset)), "total", mapper.selectCount(null)));
    }
    @PostMapping("/api/admin/users") public Map<String, Object> create(@RequestBody UserCreate body) {
        CurrentUser.requireAdmin();
        Account user = service.create(body.username(), body.password(), body.role() == null ? "USER" : body.role());
        if (Boolean.FALSE.equals(body.enabled())) user = service.update(user.getId(), null, false, null);
        return ok(user);
    }
    @PatchMapping("/api/admin/users/{id}") public Map<String, Object> update(@PathVariable Long id, @RequestBody UserUpdate body) {
        CurrentUser.requireAdmin(); Account result = service.update(id, body.role(), body.enabled(), body.password());
        events.publishEvent(new AccountChanged(id)); return ok(result);
    }
    private synchronized void throttle(String key, int limit, long duration) {
        long now = System.currentTimeMillis(); Window window = attempts.get(key);
        if (window == null || now >= window.until) {
            if (attempts.size() >= 10000) attempts.remove(attempts.keySet().iterator().next());
            window = new Window(now + duration); attempts.put(key, window);
        }
        if (++window.count > limit) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "操作过于频繁，请稍后重试");
    }
    public static Map<String, Object> ok(Object data) { return Map.of("success", true, "data", data); }
    private static CurrentUser principal(Account user) { return new CurrentUser(user.getId(), user.getUsername(), user.getRole(), user.getAuthVersion()); }
    private static class Window { final long until; int count; Window(long until) { this.until = until; } }
    public record Credentials(String username, String password) {}
    public record PasswordChange(String oldPassword, String newPassword) {}
    public record UserCreate(String username, String password, String role, Boolean enabled) {}
    public record UserUpdate(String role, Boolean enabled, String password) {}
    public record AccountChanged(Long userId) {}
}
