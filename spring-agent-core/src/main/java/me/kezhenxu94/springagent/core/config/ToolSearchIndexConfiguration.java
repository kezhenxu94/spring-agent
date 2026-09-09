package me.kezhenxu94.springagent.core.config;

import me.kezhenxu94.springagent.core.tools.toolsearch.ParallelAddVectorStore;
import me.kezhenxu94.springagent.core.tools.toolsearch.StatelessVectorToolIndex;
import org.springframework.ai.chat.client.advisor.toolsearch.autoconfigure.ToolSearchAdvisorAutoConfiguration;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link StatelessVectorToolIndex} as the index the tool search reads and writes, in
 * place of Spring AI's own — see that class for what differs and why.
 *
 * <p>{@code before} is load-bearing rather than tidiness. Spring AI's index bean is guarded by
 * {@code @ConditionalOnMissingBean(ToolIndex.class)}, and a condition is answered against the beans
 * registered by the time it is evaluated; an auto-configuration contributing one afterwards loses
 * silently, leaving the store growing exactly as before with nothing to say so.
 *
 * <p>Conditioned on {@code tool-index-type} the same way Spring AI conditions its own, so that
 * selecting {@code lucene} or {@code regex} still selects them: this replaces the vector index, it
 * does not overrule the choice of index.
 *
 * <p>And on {@code enabled}, which upstream carries on the auto-configuration as a whole rather
 * than on its index. Without it a deployment that turns the tool search off but leaves {@code
 * tool-index-type} where it was gets an index nothing reads — and, where the store is the one thing
 * it was also turning off, a context that fails to start on the exception below. A replacement has
 * to be conditioned on everything the thing it replaces was, or it outlives it.
 *
 * <p>The store is taken as an {@link ObjectProvider} and read when the index is built, for the same
 * reason and with the same consequence. {@code @ConditionalOnBean(VectorStore.class)} would be
 * answered while this auto-configuration is processed, and every auto-configuration that
 * contributes a store is processed after it — Spring AI's {@code
 * MilvusVectorStoreAutoConfiguration} and core's own {@link VectorStoreConfiguration} both sort
 * later by class name. So the condition never held in a real application: this index backed off,
 * upstream's was registered in its place, and the store went on accumulating the documents of every
 * earlier deployment.
 */
@AutoConfiguration(before = ToolSearchAdvisorAutoConfiguration.class)
@ConditionalOnBooleanProperty(
    prefix = "spring.ai.chat.client.tool-search-advisor",
    name = "enabled")
@ConditionalOnProperty(
    prefix = "spring.ai.chat.client.tool-search-advisor",
    name = "tool-index-type",
    havingValue = "vector")
public class ToolSearchIndexConfiguration {

  @Bean
  @ConditionalOnMissingBean(ToolIndex.class)
  StatelessVectorToolIndex statelessVectorToolIndex(
      final ObjectProvider<VectorStore> vectorStores,
      @Value("${app.ai.embedding.batch-size:10}") final int batchSize,
      @Value("${app.ai.embedding.concurrency:8}") final int concurrency) {
    final var vectorStore = vectorStores.getIfAvailable();
    if (vectorStore == null) {
      // The same failure Spring AI's own vector index raises, for the same reason: asking for a
      // vector index with no store to keep it in is a misconfiguration, not a reason to run
      // without a tool search.
      throw new IllegalStateException(
          "'spring.ai.chat.client.tool-search-advisor.tool-index-type=vector' requires a"
              + " VectorStore bean in the application context, but none was found");
    }
    return new StatelessVectorToolIndex(indexing(vectorStore, batchSize, concurrency));
  }

  /**
   * The store the index writes through, which for everything but the simple store is {@link
   * ParallelAddVectorStore}: building an index is a few hundred documents at once and a
   * server-backed store embeds them a batch at a time, one call after another. See that class.
   *
   * <p>The simple store is left alone because it parallelizes its own adds already — it embeds a
   * document at a time and consults no batching strategy at all, so {@code
   * VectorStoreConfiguration.ConcurrentSimpleVectorStore} spreads the documents over a pool of its
   * own. Chunking on top of that would nest one pool per chunk inside the other, and the burst of
   * embedding calls both exist to bound would be the product of the two.
   */
  private static VectorStore indexing(
      final VectorStore vectorStore, final int batchSize, final int concurrency) {
    if (vectorStore instanceof SimpleVectorStore || batchSize <= 0 || concurrency <= 1) {
      return vectorStore;
    }
    return new ParallelAddVectorStore(vectorStore, batchSize, concurrency);
  }
}
