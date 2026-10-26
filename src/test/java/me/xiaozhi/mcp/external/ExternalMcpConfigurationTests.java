package me.xiaozhi.mcp.external;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalMcpConfigurationTests {

    private final ExternalMcpConfiguration configuration = new ExternalMcpConfiguration();

    @Test
    void rootEndpointMustRetainQueryCredentials() {
        var connection = new ExternalMcpProperties.ConnectionProperties();
        connection.setUrl("https://example.test/?token=example");
        assertThat(configuration.resolveEndpoint(connection).endpoint()).isEqualTo("/mcp?token=example");
    }

    @Test
    void shouldDeriveSseEndpointFromUrlPath() {
        ExternalMcpProperties.ConnectionProperties connection = new ExternalMcpProperties.ConnectionProperties();
        connection.setUrl("http://localhost:9998/sse");
        connection.setTransport(ExternalMcpProperties.Transport.SSE);

        ExternalMcpConfiguration.ResolvedEndpoint endpoint = configuration.resolveEndpoint(connection);

        assertThat(endpoint.baseUrl()).isEqualTo("http://localhost:9998");
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
