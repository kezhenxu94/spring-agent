package me.kezhenxu94.springagent.core.tools.toolsearch;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

/**
 * A server-backed {@link VectorStore} whose writes are cut into chunks and sent several at a time.
 * Everything else is the delegate's, unchanged.
 *
 * <p>Why: adding documents to such a store embeds them first, and the embedding endpoint is asked
 * for one batch at a time — {@code EmbeddingModel.embed(documents, options, batchingStrategy)} is a
 * plain loop over what the {@code BatchingStrategy} produced. A batch is small because providers
 * cap it (DashScope refuses more than twenty rows per call, which is why {@code
 * SpringAgentCoreAutoConfiguration} sets a fixed size well under that), so a deployment offering a
 * few hundred tools pays a few dozen round trips end to end before the model has been asked
 * anything — on every request whose tool set has changed, since that is when the tool index is
 * rebuilt.
 *
 * <p>Those round trips are independent of each other, so the fix is to stop waiting for each one
 * before asking for the next. Each chunk is a whole {@code add}: its own embedding calls and its
 * own insert. Chunking the write rather than the embedding is what keeps this to a decorator — the
 * store owns the step between the two, and nothing here has to know how it does it.
 *
 * <p>A chunk that fails leaves the ones that succeeded written, where the undecorated store would
 * have inserted nothing: it embeds everything before it inserts anything. That is safe for the one
 * thing this decorates, the tool index, because {@link StatelessVectorToolIndex} clears an index by
 * the key its documents carry and so removes the half-written set before the next attempt refills
 * it. Do not put a corpus behind this without thinking that through.
 *
 * @param delegate the store the chunks are written to
 * @param chunkSize how many documents one chunk holds. Worth keeping equal to the embedding batch
 *     size: a larger chunk only puts back the sequential loop this exists to break up.
 * @param concurrency how many chunks may be in flight at once, which is also how many calls the
 *     embedding endpoint sees at once.
 */
@Slf4j
@RequiredArgsConstructor
public class ParallelAddVectorStore implements VectorStore {

  private final VectorStore delegate;
  private final int chunkSize;
  private final int concurrency;

  @Override
  public String getName() {
    return delegate.getName();
  }

  @Override
  public void add(final List<Document> documents) {
    if (documents == null || documents.isEmpty()) {
      return;
    }
    if (documents.size() <= chunkSize) {
      delegate.add(documents);
      return;
    }
    final var chunks = chunks(documents);
    log.debug("Adding {} documents as {} chunks", documents.size(), chunks.size());
    // A pool per call rather than a shared one: an index is rebuilt when a user's tool set changes
    // and not per chunk, so the pool costs a few threads for the length of one build and nothing
    // for the rest of the process.
    final var executor = Executors.newFixedThreadPool(Math.min(concurrency, chunks.size()));
    try {
      // join, not get: every chunk has to be waited for, and join keeps waiting through an
      // interrupt rather than returning while writes are still going out.
      CompletableFuture.allOf(
              chunks.stream()
                  .map(chunk -> CompletableFuture.runAsync(() -> delegate.add(chunk), executor))
                  .toArray(CompletableFuture[]::new))
          .join();
    } finally {
      executor.shutdown();
    }
  }

  private List<List<Document>> chunks(final List<Document> documents) {
    final var chunks = new ArrayList<List<Document>>();
    for (int i = 0; i < documents.size(); i += chunkSize) {
      chunks.add(documents.subList(i, Math.min(i + chunkSize, documents.size())));
    }
    return chunks;
  }

  @Override
  public void delete(final List<String> idList) {
    delegate.delete(idList);
  }

  @Override
  public void delete(final Filter.Expression filterExpression) {
    delegate.delete(filterExpression);
  }

  @Override
  public void delete(final String filterExpression) {
    delegate.delete(filterExpression);
  }

  @Override
  public List<Document> similaritySearch(final String query) {
    return delegate.similaritySearch(query);
  }

  @Override
  public List<Document> similaritySearch(final SearchRequest request) {
    return delegate.similaritySearch(request);
  }

  @Override
  public <T> Optional<T> getNativeClient() {
    return delegate.getNativeClient();
  }
}
