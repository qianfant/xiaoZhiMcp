package me.xiaozhi.mcp.account;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import me.xiaozhi.mcp.external.mapper.AccountMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountService implements ApplicationRunner {
    private final AccountMapper mapper;
    private final PasswordEncoder encoder;
    private final String bootstrapUsername;
    private final String bootstrapPassword;
    private final LegacyClaim mapperClaim;
    public AccountService(AccountMapper mapper, PasswordEncoder encoder,
            @Value("${xiaozhi.security.admin-username:admin}") String username,
            @Value("${xiaozhi.security.admin-password:}") String password, LegacyClaim mapperClaim) {
        this.mapper = mapper; this.encoder = encoder;
        this.bootstrapUsername = username; this.bootstrapPassword = password;
        this.mapperClaim = mapperClaim;
    }
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        mapper.lockAdministration();
        if (mapper.selectCount(null) != 0) return;
        if (bootstrapPassword.isBlank()) throw new IllegalStateException("首次启动请设置 XIAOZHI_ADMIN_PASSWORD（至少 12 位）并执行数据库迁移");
        Account admin = create(bootstrapUsername, bootstrapPassword, "ADMIN");
        // Only the first administrator can claim the legacy single-user data.
        // This migration is deliberately never applied to later registrations.
        claimLegacy(admin.getId());
    }
    private void claimLegacy(Long id) { mapperClaim.claim(id); }

    @Transactional
    public Account create(String username, String password, String role) {
        mapper.lockAdministration();
        if (!"ADMIN".equals(role) && mapper.selectCount(null) == 0) bad("管理员尚未初始化");
        if (username == null || !username.matches("[a-zA-Z0-9_.-]{3,64}")) bad("用户名需为 3–64 位字母、数字、下划线、点或短横线");
        validatePassword(password);
        if (!"ADMIN".equals(role) && !"USER".equals(role)) bad("角色无效");
        Account user = new Account();
        user.setUsername(username); user.setPasswordHash(encoder.encode(password)); user.setRole(role);
        user.setEnabled(true); user.setAuthVersion(0); mapper.insert(user);
        return user;
    }
    public Account authenticate(String username, String password) {
        Account user = username == null ? null : mapper.selectOne(new LambdaQueryWrapper<Account>().eq(Account::getUsername, username));
        String hash = user == null ? DUMMY_HASH : user.getPasswordHash();
        boolean valid = password != null && password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72 && encoder.matches(password, hash);
        if (!valid || user == null || !Boolean.TRUE.equals(user.getEnabled()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误，或账号已禁用");
        return user;
    }
    // Valid BCrypt hash so unknown accounts still perform the password cost.
    private static final String DUMMY_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @Transactional
    public Account update(Long id, String role, Boolean enabled, String password) {
        mapper.lockAdministration();
        Account user = mapper.selectById(id);
        if (user == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        String nextRole = role == null ? user.getRole() : role;
        boolean nextEnabled = enabled == null ? user.getEnabled() : enabled;
        if (!"ADMIN".equals(nextRole) && !"USER".equals(nextRole)) bad("角色无效");
        if ("ADMIN".equals(user.getRole()) && user.getEnabled() && (!"ADMIN".equals(nextRole) || !nextEnabled)) {
            if (mapper.selectCount(new LambdaQueryWrapper<Account>().eq(Account::getRole, "ADMIN").eq(Account::getEnabled, true)) <= 1)
                bad("至少保留一个启用的管理员");
        }
        if (password != null && !password.isBlank()) { validatePassword(password); user.setPasswordHash(encoder.encode(password)); }
        user.setRole(nextRole); user.setEnabled(nextEnabled); user.setAuthVersion(user.getAuthVersion() + 1);
        mapper.updateById(user);
        return user;
    }
    public static void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            bad("密码至少 12 位，且 UTF-8 编码不超过 72 字节");
    }
    public static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
