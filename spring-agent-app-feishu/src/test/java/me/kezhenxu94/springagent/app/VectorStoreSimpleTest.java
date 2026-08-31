package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The vector store is the in-heap one unless spring.ai.vectorstore.type says otherwise, and it
 * survives a restart through its JSON file.
 */
@SpringBootTest
class VectorStoreSimpleTest extends AbstractIntegrationTest {

  @Autowired ConfigurableApplicationContext context;
  @Autowired VectorStore vectorStore;

  // The embedding endpoint in AbstractIntegrationTest is a dead address, so the round trip stubs
  // the model. Two orthogonal vectors are enough to tell the documents apart by cosine distance.
  @MockitoBean EmbeddingModel embeddingModel;

  @Test
  @DisplayName(
      "the only vector store is the simple one, and its index round trips through the file")
  void simpleBacksTheVectorStore() {
    assertThat(context.getBeansOfType(VectorStore.class)).hasSize(1);
    assertThat(vectorStore).isInstanceOf(SimpleVectorStore.class);

    given(embeddingModel.dimensions()).willReturn(2);
    given(embeddingModel.embed(any(Document.class)))
        .willAnswer(invocation -> vectorFor(invocation.getArgument(0)));
    given(embeddingModel.embed(anyString())).willReturn(new float[] {1f, 0f});

    vectorStore.add(List.of(new Document("ping"), new Document("pong")));
    assertThat(vectorStore.similaritySearch(SearchRequest.builder().query("ping").topK(1).build()))
        .extracting(Document::getText)
        .containsExactly("ping");

    // Shutdown is what persists the index. The event is published rather than the context actually
    // closed, because closing it here would evict the context every other test in the suite shares.
    // The directory did not exist before, so this covers creating it too.
    context.publishEvent(new ContextClosedEvent(context));
    assertThat(VECTOR_STORE_FILE).exists();

    final var reloaded = SimpleVectorStore.builder(embeddingModel).build();
    reloaded.load(VECTOR_STORE_FILE.toFile());
    assertThat(reloaded.similaritySearch(SearchRequest.builder().query("ping").topK(2).build()))
        .extracting(Document::getText)
        .containsExactlyInAnyOrder("ping", "pong");
  }

  /** Two orthogonal vectors, so the documents can be told apart by cosine distance. */
  private static float[] vectorFor(final Document document) {
    return "ping".equals(document.getText()) ? new float[] {1f, 0f} : new float[] {0f, 1f};
  }
}
