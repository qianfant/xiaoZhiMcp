package me.xiaozhi.mcp.bridge;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "xiaozhi.bridge")
public class XiaozhiBridgeProperties {

    private boolean enabled = true;
    private String endpoint;
    private String localMcpUrl = "http://127.0.0.1:8080/mcp";
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration requestTimeout = Duration.ofSeconds(30);
    private Duration initialReconnectDelay = Duration.ofSeconds(2);
    private Duration maxReconnectDelay = Duration.ofSeconds(30);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getLocalMcpUrl() {
        return localMcpUrl;
    }

    public void setLocalMcpUrl(String localMcpUrl) {
        this.localMcpUrl = localMcpUrl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration getInitialReconnectDelay() {
        return initialReconnectDelay;
    }

    public void setInitialReconnectDelay(Duration initialReconnectDelay) {
        this.initialReconnectDelay = initialReconnectDelay;
    }

    public Duration getMaxReconnectDelay() {
        return maxReconnectDelay;
    }

    public void setMaxReconnectDelay(Duration maxReconnectDelay) {
        this.maxReconnectDelay = maxReconnectDelay;
    }
}
