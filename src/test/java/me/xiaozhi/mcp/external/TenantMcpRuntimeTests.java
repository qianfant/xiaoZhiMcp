package me.xiaozhi.mcp.external;

import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import me.xiaozhi.mcp.external.entity.ExternalMcpConnection;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TenantMcpRuntimeTests {
    final OwnedConnections owned = mock(OwnedConnections.class);
    final ExternalMcpConnectivityService factory = mock(ExternalMcpConnectivityService.class);
    final McpSyncClient client = mock(McpSyncClient.class);
    final ExternalMcpConnection row = new ExternalMcpConnection();
    TenantMcpRuntime runtime;
    @BeforeEach void setup() {
        row.setId(1L); row.setOwnerId(10L); row.setEnabled(true); row.setUrl("http://localhost/mcp"); row.setTransport("STREAMABLE"); row.setHeaders("{}");
        when(owned.require(10L,1L)).thenReturn(row);
        when(factory.openClient(any(),any(),anyString())).thenReturn(client);
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(),null));
        runtime = new TenantMcpRuntime(owned,factory,new ExternalMcpProperties(),new ObjectMapper(),2);
    }
    @AfterEach void close() { runtime.shutdown(); }
    @Test void concurrentRequestsReuseOneClientAndCacheToolDiscovery() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<?>>();
            for (int i=0;i<16;i++) tasks.add(executor.submit(() -> runtime.tools(row)));
            for (var t:tasks) t.get(5,TimeUnit.SECONDS);
        }
        verify(client,times(1)).initialize(); verify(client,times(1)).listTools(); verify(factory,times(1)).openClient(any(),any(),anyString());
        runtime.invalidate(1L); verify(client).close();
        runtime.tools(row); verify(factory,times(2)).openClient(any(),any(),anyString());
    }
    @Test void deletedOrDisabledRowsCannotReuseAnExistingClient() {
        runtime.tools(row); row.setEnabled(false);
        assertThatThrownBy(() -> runtime.tools(row)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        row.setEnabled(true);
        when(owned.require(10L,1L)).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> runtime.tools(row)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(client,times(1)).listTools();
    }
}
