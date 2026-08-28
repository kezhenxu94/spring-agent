package me.kezhenxu94.springagent.core.tools.toolsearch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

/**
 * What a cold tool index costs is decided here: a store embeds a batch at a time and waits for each
 * one, so a few hundred tool descriptions are a few dozen round trips end to end unless something
 * runs them at once.
 */
class ParallelAddVectorStoreTest {

  @Test
  @DisplayName("every document is written, in chunks of the batch size")
  void everythingIsWrittenOnce() {
    final var delegate = new RecordingVectorStore();
    final var store = new ParallelAddVectorStore(delegate, 10, 4);

    store.add(documents(25));

    assertThat(delegate.adds).hasSize(3);
    assertThat(delegate.adds.stream().flatMap(List::stream).map(Document::getId))
        .containsExactlyInAnyOrderElementsOf(documents(25).stream().map(Document::getId).toList());
  }

  @Test
  @DisplayName("a write no larger than one chunk is handed over as it came")
  void oneChunkIsNotSplit() {
    final var delegate = new RecordingVectorStore();

    new ParallelAddVectorStore(delegate, 10, 4).add(documents(10));

    assertThat(delegate.adds).hasSize(1);
  }

  @Test
  @DisplayName("chunks are in flight together, which is the whole point")
  void chunksRunConcurrently() throws Exception {
    // Every chunk blocks until as many of them as the concurrency allows have arrived, so this
    // finishes only if they really do run at once and hangs — rather than passing slowly — if the
    // adds went one after another.
    final var together = new CountDownLatch(4);
    final var delegate =
        new RecordingVectorStore() {
          @Override
          public void add(final List<Document> documents) {
            super.add(documents);
            together.countDown();
            try {
              assertThat(together.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        };

    new ParallelAddVectorStore(delegate, 10, 4).add(documents(40));

    assertThat(delegate.adds).hasSize(4);
  }

  private static List<Document> documents(final int count) {
    final var documents = new ArrayList<Document>(count);
    for (int i = 0; i < count; i++) {
      documents.add(new Document("doc-" + i, "tool number " + i, java.util.Map.of()));
    }
    return documents;
  }

  private static class RecordingVectorStore implements VectorStore {
    final List<List<Document>> adds = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void add(final List<Document> documents) {
      adds.add(List.copyOf(documents));
    }

    @Override
    public void delete(final List<String> idList) {}

    @Override
    public void delete(final Filter.Expression filterExpression) {}

    @Override
    public List<Document> similaritySearch(final SearchRequest request) {
      return List.of();
    }
  }
}
