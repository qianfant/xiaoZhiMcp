package me.xiaozhi.mcp.tool;

import java.util.*;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.*;
import me.xiaozhi.mcp.account.ToolUserContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PersonalRecordIsolationTests {
    @Test void recordWritesAndSearchFiltersCarryOnlyTheExecutingUser() {
        OpenAiEmbeddingModel model = mock(OpenAiEmbeddingModel.class);
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        Embedding embedding = Embedding.from(new float[]{1,2});
        when(model.embed(any(TextSegment.class))).thenReturn(Response.from(embedding));
        when(model.embed(anyString())).thenReturn(Response.from(embedding));
        when(store.search(any())).thenReturn(new EmbeddingSearchResult<>(List.of()));
        var tools = new DemoMcpTools(model,store,mock(JavaMailSender.class));
        assertThat(ToolUserContext.call(42L, () -> tools.saveMessage("hello","note"))).isEqualTo("记录成功");
        ArgumentCaptor<TextSegment> segment = ArgumentCaptor.forClass(TextSegment.class);
        verify(store).add(eq(embedding),segment.capture());
        assertThat(segment.getValue().metadata().getString("owner_id")).isEqualTo("42");
        assertThat(ToolUserContext.call(42L, () -> tools.getMessage("hello"))).isEqualTo("暂无相关记录");
        ArgumentCaptor<EmbeddingSearchRequest> search = ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(store).search(search.capture());
        assertThat(search.getValue().filter().test(Metadata.from(Map.of("owner_id","42")))).isTrue();
        assertThat(search.getValue().filter().test(Metadata.from(Map.of("owner_id","99")))).isFalse();
        assertThat(search.getValue().filter().test(Metadata.from(Map.of("author",0L)))).isFalse();
        assertThatThrownBy(ToolUserContext::requireId).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tools.getMessage("unscoped")).isInstanceOf(IllegalStateException.class);
    }
}
