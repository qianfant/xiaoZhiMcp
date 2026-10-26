package me.xiaozhi.mcp.account;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.xiaozhi.mcp.external.mapper.AccountMapper;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(10); }
    @Bean HttpSessionSecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean
    SecurityFilterChain security(HttpSecurity http, AccountMapper accounts, ObjectMapper json,
            HttpSessionSecurityContextRepository repository) throws Exception {
        var csrf = new HttpSessionCsrfTokenRepository();
        csrf.setHeaderName("X-CSRF-TOKEN");
        http.securityContext(c -> c.securityContextRepository(repository))
            .csrf(c -> c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/", "/mcp-admin.html", "/login.html", "/app.css", "/app.js", "/error", "/api/auth/csrf", "/api/auth/login", "/api/auth/register", "/api/auth/options").permitAll()
                // Raw Spring MCP sessions have no tenant identity. Only the scoped dispatcher is exposed.
                .requestMatchers("/mcp", "/mcp/**", "/sse", "/sse/**").denyAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .requestCache(c -> c.disable()).formLogin(c -> c.disable()).httpBasic(c -> c.disable()).logout(c -> c.disable())
            .exceptionHandling(c -> c
                .authenticationEntryPoint((req, res, ex) -> error(res, 401, "请先登录", json))
                .accessDeniedHandler((req, res, ex) -> error(res, 403, "权限不足或请求校验已过期，请刷新页面", json)))
            .addFilterBefore(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
                    var auth = SecurityContextHolder.getContext().getAuthentication();
                    if (auth != null && auth.getPrincipal() instanceof CurrentUser principal) {
                        Account user = accounts.selectById(principal.id());
                        if (user == null || !Boolean.TRUE.equals(user.getEnabled()) || user.getAuthVersion() != principal.authVersion()) {
                            SecurityContextHolder.clearContext();
                            if (request.getSession(false) != null) request.getSession(false).invalidate();
                            error(response, 401, "账号状态已变更，请重新登录", json); return;
                        }
                    }
                    chain.doFilter(request, response);
                }
            }, AnonymousAuthenticationFilter.class);
        return http.build();
    }
    private static void error(HttpServletResponse response, int status, String message, ObjectMapper json) throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getWriter(), Map.of("success", false, "message", message));
    }
}
