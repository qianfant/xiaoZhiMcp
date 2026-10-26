package me.xiaozhi.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSupport.class)
class LocalMcpEndpointIntegrationTests {
    @Autowired MockMvc mvc;
    @Test void rawMcpMustNotExposeUnscopedTools() throws Exception {
        mvc.perform(get("/mcp")).andExpect(status().isUnauthorized());
        mvc.perform(post("/mcp").contentType("application/json").content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
            .andExpect(status().isForbidden());
    }
    @Test void loginPageIsPublicButTenantDataRequiresLogin() throws Exception {
        mvc.perform(get("/login.html")).andExpect(status().isOk());
        mvc.perform(get("/api/external-mcp/connections")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/nodes")).andExpect(status().isUnauthorized());
    }
}
