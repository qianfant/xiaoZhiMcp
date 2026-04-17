package me.xiaozhi.mcp.external;

import java.util.ArrayList;
import java.util.List;

import io.modelcontextprotocol.client.McpSyncClient;

public class ExternalMcpClients implements AutoCloseable {

    private final List<McpSyncClient> clients;

    public ExternalMcpClients(List<McpSyncClient> clients) {
        this.clients = new ArrayList<>(clients);
    }

    public synchronized List<McpSyncClient> getClients() {
        return List.copyOf(clients);
    }

    public synchronized void replaceClients(List<McpSyncClient> refreshedClients) {
        List<McpSyncClient> oldClients = new ArrayList<>(this.clients);
        this.clients.clear();
        this.clients.addAll(refreshedClients);
        closeClients(oldClients);
    }

    @Override
    public synchronized void close() {
        List<McpSyncClient> oldClients = new ArrayList<>(this.clients);
        this.clients.clear();
        closeClients(oldClients);
    }

    private void closeClients(List<McpSyncClient> targetClients) {
        targetClients.forEach(client -> {
            try {
                client.closeGracefully();
            }
            catch (Exception ignored) {
            }
        });
    }
}
