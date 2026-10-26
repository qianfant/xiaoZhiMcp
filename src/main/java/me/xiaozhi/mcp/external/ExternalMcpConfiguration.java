package me.xiaozhi.mcp.external;

import java.net.URI;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(ExternalMcpProperties.class)
public class ExternalMcpConfiguration {
    // Kept as compatibility beans for the retired single-user bridge. User connections are
    // created lazily by TenantMcpRuntime and never exported as globally shared callbacks.
    @Bean(destroyMethod="close")
    public ExternalMcpClients externalMcpClients(ExternalMcpProperties properties) { return new ExternalMcpClients(List.of()); }
    public void refreshExternalMcpClients(ExternalMcpClients clients, ExternalMcpProperties properties) { clients.replaceClients(List.of()); }
    @Bean
    public ToolCallbackProvider externalMcpToolCallbackProvider(ExternalMcpClients clients) { return () -> new ToolCallback[0]; }

    ResolvedEndpoint resolveEndpoint(ExternalMcpProperties.ConnectionProperties connection) {
        URI uri = URI.create(connection.getUrl());
        String base = uri.getScheme() + "://" + uri.getRawAuthority();
        String path = connection.getEndpoint();
        if (!StringUtils.hasText(path)) {
            path = uri.getRawPath();
            if (!StringUtils.hasText(path) || "/".equals(path)) path = "/mcp";
            if (StringUtils.hasText(uri.getRawQuery())) path += "?" + uri.getRawQuery();
        }
        return new ResolvedEndpoint(base, path.startsWith("/") ? path : "/" + path);
    }
    record ResolvedEndpoint(String baseUrl, String endpoint) {}
}
