package me.xiaozhi.mcp.external;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "xiaozhi.external-mcp")
public class ExternalMcpProperties {

    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration requestTimeout = Duration.ofSeconds(30);
    private Duration initializationTimeout = Duration.ofSeconds(30);
    private Map<String, ConnectionProperties> connections = new LinkedHashMap<>();

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

    public Duration getInitializationTimeout() {
        return initializationTimeout;
    }

    public void setInitializationTimeout(Duration initializationTimeout) {
        this.initializationTimeout = initializationTimeout;
    }

    public Map<String, ConnectionProperties> getConnections() {
        return connections;
    }

    public void setConnections(Map<String, ConnectionProperties> connections) {
        this.connections = connections;
    }

    public static class ConnectionProperties {

        private boolean enabled;
        private Transport transport = Transport.SSE;
        private String url;
        private String endpoint;
        private Map<String, String> headers = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Transport getTransport() {
            return transport;
        }

        public void setTransport(Transport transport) {
            this.transport = transport;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }
    }

    public enum Transport {
        SSE,
        STREAMABLE
    }
}
