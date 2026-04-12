package me.xiaozhi.mcp;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "xiaozhi.bridge.enabled=false"
})
class LocalMcpEndpointIntegrationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void toolsListShouldContainRegisteredDemoTools() throws Exception {
        HttpHeaders headers = baseHeaders();

        ResponseEntity<String> initializeResponse = post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"integration-test","version":"1.0.0"}}}
                """, headers);

        assertThat(initializeResponse.getStatusCode().is2xxSuccessful()).isTrue();
        String sessionId = initializeResponse.getHeaders().getFirst("Mcp-Session-Id");
        assertThat(sessionId).isNotBlank();
        headers.set("Mcp-Session-Id", sessionId);

        ResponseEntity<String> initializedResponse = post("""
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """, headers);
        assertThat(initializedResponse.getStatusCode().value()).isIn(200, 202);

        ResponseEntity<String> toolsListResponse = post("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """, headers);

        JsonNode responseBody = objectMapper.readTree(toolsListResponse.getBody());
        JsonNode tools = responseBody.path("result").path("tools");
        assertThat(tools.isArray()).isTrue();
        assertThat(tools).hasSizeGreaterThan(0);
        assertThat(toolNames(tools)).contains("add_numbers", "divide_numbers", "current_time", "uppercase_text");
    }

    private ResponseEntity<String> post(String body, HttpHeaders headers) {
        return restTemplate.postForEntity("http://127.0.0.1:" + port + "/mcp", new HttpEntity<>(body, headers), String.class);
    }

    private HttpHeaders baseHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private List<String> toolNames(JsonNode tools) {
        return tools.findValuesAsText("name");
    }
}
