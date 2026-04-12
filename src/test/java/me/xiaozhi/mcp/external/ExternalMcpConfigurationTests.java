package me.xiaozhi.mcp.external;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalMcpConfigurationTests {

    private final ExternalMcpConfiguration configuration = new ExternalMcpConfiguration();

    @Test
    void shouldDeriveSseEndpointFromUrlPath() {
        ExternalMcpProperties.ConnectionProperties connection = new ExternalMcpProperties.ConnectionProperties();
        connection.setUrl("http://154.193.217.93:9998/sse");
        connection.setTransport(ExternalMcpProperties.Transport.SSE);

        ExternalMcpConfiguration.ResolvedEndpoint endpoint = configuration.resolveEndpoint(connection);

        assertThat(endpoint.baseUrl()).isEqualTo("http://154.193.217.93:9998");
        assertThat(endpoint.endpoint()).isEqualTo("/sse");
    }

    @Test
    void shouldUseConfiguredEndpointWhenProvided() {
        ExternalMcpProperties.ConnectionProperties connection = new ExternalMcpProperties.ConnectionProperties();
        connection.setUrl("http://127.0.0.1:9998/ignored");
        connection.setEndpoint("custom/mcp");
        connection.setTransport(ExternalMcpProperties.Transport.SSE);

        ExternalMcpConfiguration.ResolvedEndpoint endpoint = configuration.resolveEndpoint(connection);

        assertThat(endpoint.baseUrl()).isEqualTo("http://127.0.0.1:9998");
        assertThat(endpoint.endpoint()).isEqualTo("/custom/mcp");
    }
}
