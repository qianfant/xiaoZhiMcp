package me.xiaozhi.mcp;

import java.util.*;
import com.fasterxml.jackson.databind.*;
import me.xiaozhi.mcp.external.mapper.AccountMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSupport.class)
class MultiUserIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountMapper accounts;
    class Browser {
        MockHttpSession session;
        String csrf;
        void token() throws Exception {
            var req = get("/api/auth/csrf"); if (session != null) req.session(session);
            var result = mvc.perform(req).andExpect(status().isOk()).andReturn();
            session = (MockHttpSession) result.getRequest().getSession(); csrf = read(result).path("data").path("token").asText();
        }
        ResultActions call(String method, String url, Object body) throws Exception {
            if (csrf == null) token();
            MockHttpServletRequestBuilder req = switch (method) { case "POST" -> post(url); case "PUT" -> put(url); case "PATCH" -> patch(url); case "DELETE" -> delete(url); default -> get(url); };
            req.session(session).header("X-CSRF-TOKEN", csrf).contentType("application/json");
            if (body != null) req.content(json.writeValueAsBytes(body));
            return mvc.perform(req);
        }
        void login(String username, String password) throws Exception {
            var r = call("POST", "/api/auth/login", Map.of("username", username, "password", password)).andExpect(status().isOk()).andReturn();
            session = (MockHttpSession) r.getRequest().getSession(); token();
        }
    }
    JsonNode read(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsByteArray()); }
    String unique(String prefix) { return prefix + UUID.randomUUID().toString().substring(0,8); }
    Browser user(String name) throws Exception {
        Browser b = new Browser(); b.call("POST","/api/auth/register",Map.of("username", name, "password", "safe-user-password")).andExpect(status().isOk());
        b.login(name,"safe-user-password"); return b;
    }
    Map<String,Object> connection(String name) { return Map.of("connectionName",name,"url","http://127.0.0.1:1/mcp","transport","STREAMABLE","headers",Map.of("Authorization","Bearer private"),"enabled",false); }
    @Test void connectionsAndNodeBindingsAreIsolated() throws Exception {
        Browser alice = user(unique("alice")), bob = user(unique("bob"));
        long connection = read(alice.call("POST","/api/external-mcp/connections",connection("same-name")).andExpect(status().isOk()).andReturn()).path("data").path("id").asLong();
        bob.call("POST","/api/external-mcp/connections",connection("same-name")).andExpect(status().isOk());
        var list = read(bob.call("GET","/api/external-mcp/connections",null).andExpect(status().isOk()).andReturn()).path("data");
        assertThat(list).hasSize(1); assertThat(list.get(0).path("id").asLong()).isNotEqualTo(connection);
        for (String suffix : List.of("/test","/tools","/tools/invoke")) {
            bob.call(suffix.equals("/tools") ? "GET" : "POST","/api/external-mcp/connections/"+connection+suffix, Map.of("toolName","x")).andExpect(status().isNotFound());
        }
        bob.call("PUT","/api/external-mcp/connections/"+connection, connection("stolen")).andExpect(status().isNotFound());
        bob.call("DELETE","/api/external-mcp/connections/"+connection,null).andExpect(status().isNotFound());
        bob.call("POST","/api/nodes",Map.of("name","stolen","endpoint","wss://example.invalid/?token="+unique("t"),"enabled",false,"connectionIds",List.of(connection))).andExpect(status().isNotFound());
        var node = read(alice.call("POST","/api/nodes",Map.of("name","room","endpoint","wss://example.invalid/?token="+unique("t"),"enabled",false,"connectionIds",List.of(connection))).andExpect(status().isOk()).andReturn()).path("data");
        bob.call("DELETE","/api/nodes/"+node.path("id").asLong(),null).andExpect(status().isNotFound());
        assertThat(read(bob.call("GET","/api/nodes",null).andExpect(status().isOk()).andReturn()).path("data")).isEmpty();
    }
    @Test void csrfSessionRotationAndPrivilegeBoundaries() throws Exception {
        Browser b = new Browser(); b.token(); String originalId = b.session.getId();
        mvc.perform(post("/api/auth/login").session(b.session).contentType("application/json").content("{\"username\":\"admin\",\"password\":\"test-admin-password\"}")).andExpect(status().isForbidden());
        b.login("admin","test-admin-password"); assertThat(b.session.getId()).isNotEqualTo(originalId);
        b.call("GET","/mcp",null).andExpect(status().isForbidden());
        Browser ordinary = user(unique("user")); ordinary.call("GET","/api/admin/users",null).andExpect(status().isForbidden());
        ordinary.call("POST","/api/admin/users",Map.of("username","attacker","password","long-password-test","role","ADMIN")).andExpect(status().isForbidden());
        ordinary.call("POST","/api/auth/logout",null).andExpect(status().isOk());
    }
    @Test void adminCanDisableResetAndCreateButCannotRemoveLastAdmin() throws Exception {
        Browser admin = new Browser(); admin.login("admin","test-admin-password");
        String username = unique("managed");
        var account = read(admin.call("POST","/api/admin/users",Map.of("username",username,"password","safe-user-password","role","USER")).andExpect(status().isOk()).andReturn()).path("data");
        assertThat(account.has("passwordHash")).isFalse();
        Browser managed = new Browser(); managed.login(username,"safe-user-password"); long id = account.path("id").asLong();
        admin.call("PATCH","/api/admin/users/"+id,Map.of("enabled",false)).andExpect(status().isOk());
        managed.call("GET","/api/nodes",null).andExpect(status().isUnauthorized());
        admin.call("PATCH","/api/admin/users/"+id,Map.of("enabled",true,"password","new-safe-password")).andExpect(status().isOk());
        Browser retry = new Browser(); retry.call("POST","/api/auth/login",Map.of("username",username,"password","safe-user-password")).andExpect(status().isUnauthorized());
        retry.login(username,"new-safe-password");
        long adminId = read(admin.call("GET","/api/auth/me",null).andExpect(status().isOk()).andReturn()).path("data").path("id").asLong();
        admin.call("PATCH","/api/admin/users/"+adminId,Map.of("enabled",false)).andExpect(status().isBadRequest());
    }
    @Test void localToolsUseScopedDispatcher() throws Exception {
        Browser b = user(unique("local"));
        b.call("POST","/api/nodes",Map.of("name","local-only","endpoint","wss://example.invalid/?token="+unique("t"),"enabled",false)).andExpect(status().isOk());
        var tools = read(b.call("GET","/api/external-mcp/tools/local",null).andExpect(status().isOk()).andReturn()).path("data").path("tools");
        assertThat(tools.findValuesAsText("name")).contains("获取今天星期几","记录一下");
        var result = read(b.call("POST","/api/external-mcp/tools/local/invoke",Map.of("toolName","获取今天星期几","arguments",Map.of())).andExpect(status().isOk()).andReturn());
        assertThat(result.path("data").path("content").get(0).path("text").asText()).contains("星期");
    }
}
