package me.xiaozhi.mcp.external;

import java.util.ArrayList;
import java.util.List;

import io.modelcontextprotocol.client.McpSyncClient;

public class ExternalMcpClients implements AutoCloseable {

    private final List<McpSyncClient> clients;

    public ExternalMcpClients(List<McpSyncClient> clients) {
        this.clients = new ArrayList<>(clients);
    }

    public List<McpSyncClient> getClients() {
        return List.copyOf(clients);
    }

    @Override
    public void close() {
        clients.forEach(client -> {
            try {
                client.closeGracefully();
            }
            catch (Exception ignored) {
            }
        });
    }
}
