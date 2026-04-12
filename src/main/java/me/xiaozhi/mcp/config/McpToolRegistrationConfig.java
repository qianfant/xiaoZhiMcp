package me.xiaozhi.mcp.config;

import java.util.Arrays;
import java.util.List;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import me.xiaozhi.mcp.tool.DemoMcpTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpToolRegistrationConfig {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistrationConfig.class);

    @Bean
    public ToolCallbackProvider demoToolCallbackProvider(DemoMcpTools demoMcpTools) {
        ToolCallback[] toolCallbacks = ToolCallbacks.from(demoMcpTools);
        return ToolCallbackProvider.from(toolCallbacks);
    }

    @Bean
    public ApplicationRunner mcpToolRegistrationLogger(List<ToolCallbackProvider> toolCallbackProviders) {
        return args -> {
            ToolCallback[] toolCallbacks = toolCallbackProviders.stream()
                    .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                    .toArray(ToolCallback[]::new);

            String toolNames = Arrays.stream(toolCallbacks)
                    .map(toolCallback -> toolCallback.getToolDefinition().name())
                    .sorted()
                    .toList()
                    .toString();
            log.info("已注册 MCP 工具 {} 个: {}", toolCallbacks.length, toolNames);
        };
    }

    @Bean
    public EmbeddingStore<TextSegment> qdEmbeddingStore() {
        return QdrantEmbeddingStore.builder()
                .host("154.193.217.93")
                .port(6334)
                .collectionName("test-qdrant")
                .build();
    }
}
