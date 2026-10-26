package me.xiaozhi.mcp;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import static org.mockito.Mockito.mock;

@TestConfiguration
public class TestSupport {
    @Bean @Primary OpenAiEmbeddingModel testEmbeddingModel() { return mock(OpenAiEmbeddingModel.class); }
    @Bean @Primary EmbeddingStore<TextSegment> testStore() { return mock(EmbeddingStore.class); }
}
