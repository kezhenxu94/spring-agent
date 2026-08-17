package me.kezhenxu94.springagent.core.config;

import io.micrometer.observation.ObservationRegistry;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.SpringAIVectorStoreTypes;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.io.Resource;

/**
 * The {@code simple} branch of {@code spring.ai.vectorstore.type}: the index lives in the heap and
 * is mirrored to a local JSON file, so a single-node or offline deployment needs no vector
 * database.
 *
 * <p>Milvus stays with Spring AI's own auto-configuration, which already conditions on {@code
 * spring.ai.vectorstore.type} being {@code milvus} (matching when the property is missing) and so
 * backs off on its own — no import filter is needed here, unlike the MongoDB chat memory in {@link
 * PersistenceAutoConfigurationFilter}. Reusing Spring AI's property rather than adding one of ours
 * is what buys that.
 *
 * <p>What this store is asked to hold is the tool search index: {@link
 * me.kezhenxu94.springagent.core.tools.toolsearch.StatelessVectorToolIndex}, one per user. That is
 * a cache of tool-description embeddings, not a corpus, which sets the trade-offs below.
 *
 * <p>Its limits, all acceptable for that cache and none of them for a real corpus: the whole index
 * sits in the heap and every search scans it linearly; the file is written on a graceful shutdown
 * only, so a {@code kill -9} loses whatever accumulated since startup; and a file that cannot be
 * read is discarded rather than fatal. In each case the cost is re-embedding tool descriptions,
 * which eviction would have forced eventually anyway.
 */
@Slf4j
@AutoConfiguration
@ConditionalOnProperty(name = SpringAIVectorStoreTypes.TYPE, havingValue = "simple")
public class VectorStoreConfiguration {

  @Bean
  SimpleVectorStore simpleVectorStore(
      final SpringAgentProperties properties,
      final EmbeddingModel embeddingModel,
      final ObjectProvider<ObservationRegistry> observationRegistry) {
    final var builder = SimpleVectorStore.builder(embeddingModel);
    observationRegistry.ifAvailable(builder::observationRegistry);
    final var store = new ConcurrentSimpleVectorStore(builder);

    final var file = new File(properties.ai().vectorstore().simple().file());
    if (file.isFile()) {
      try {
        store.load(file);
        log.info("Loaded the vector store index from {}", file);
      } catch (RuntimeException e) {
        // Truncated by an abrupt shutdown, or written by an embedding model of another dimension.
        // Rebuilding the index costs embedding calls; refusing to start costs the whole deployment.
        log.warn("Ignoring the vector store index at {}, starting with an empty one", file, e);
      }
    }
    return store;
  }

  /**
   * Writes the index back on shutdown. {@code server.shutdown: graceful} means this runs once the
   * in-flight requests have drained, so nothing is indexing by the time the file is serialised.
   */
  @Bean
  ApplicationListener<ContextClosedEvent> vectorStorePersister(
      final SpringAgentProperties properties, final SimpleVectorStore simpleVectorStore) {
    return event -> {
      final var file = new File(properties.ai().vectorstore().simple().file());
      try {
        createParentDirectory(file);
        simpleVectorStore.save(file);
        log.info("Saved the vector store index to {}", file);
      } catch (RuntimeException e) {
        // The index is rebuildable, so failing to keep it must not fail the shutdown.
        log.warn("Could not save the vector store index to {}", file, e);
      }
    };
  }

  /** File-based stores refuse to create their file under a missing directory. */
  private static void createParentDirectory(final File file) {
    final var directory = file.toPath().toAbsolutePath().getParent();
    try {
      Files.createDirectories(directory);
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Cannot create the directory holding the vector store index: " + directory, e);
    }
  }

  /**
   * Two corrections to {@link SimpleVectorStore}, both of which only matter because concurrent
   * agent runs index a few hundred tool descriptions at a time into it.
   *
   * <p>The first is the concurrent map that {@link SimpleVectorStore#load} drops. The store starts
   * life with a {@link ConcurrentHashMap}, but {@code load} assigns the deserialised {@code
   * HashMap} straight over it, and none of the store's methods synchronise. From the first load
   * onwards those writes would race on a map that cannot take them, so re-wrapping restores the
   * invariant the constructor established.
   *
   * <p>The second is that the superclass adds documents one at a time, waiting for each embedding
   * before asking for the next — and unlike every server-backed store it consults no {@code
   * BatchingStrategy} to do otherwise. A tool set is a few hundred documents, so a cold index costs
   * a few hundred round trips end to end, which is most of a minute before the model has been asked
   * anything. Running them concurrently is all that is done about it: the store is a concurrent
   * map, so the superclass's own add is safe to call from several threads at once.
   */
  static class ConcurrentSimpleVectorStore extends SimpleVectorStore {

    /**
     * Enough to make a cold index build quick, low enough not to arrive at the embedding endpoint
     * as a burst of hundreds. Batching would be gentler still, but the superclass embeds and stores
     * in one step and what it stores is package private, so there is no batch for us to hand it.
     */
    private static final int CONCURRENCY = 8;

    ConcurrentSimpleVectorStore(final SimpleVectorStoreBuilder builder) {
      super(builder);
    }

    @Override
    public void doAdd(final List<Document> documents) {
      if (documents.isEmpty()) {
        // Returning rather than throwing the IllegalArgumentException the superclass would: an
        // empty tool set is a deployment with no tools, not a programming error.
        return;
      }
      final var executor = Executors.newFixedThreadPool(CONCURRENCY);
      try {
        // join, not get: every document has to be waited for, and join keeps waiting through an
        // interrupt rather than leaving the store half filled.
        CompletableFuture.allOf(
                documents.stream()
                    .map(
                        document ->
                            CompletableFuture.runAsync(
                                () -> super.doAdd(List.of(document)), executor))
                    .toArray(CompletableFuture[]::new))
            .join();
      } finally {
        executor.shutdown();
      }
    }

    @Override
    public void load(final File file) {
      super.load(file);
      this.store = new ConcurrentHashMap<>(this.store);
    }

    @Override
    public void load(final Resource resource) {
      super.load(resource);
      this.store = new ConcurrentHashMap<>(this.store);
    }
  }
}
