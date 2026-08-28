package me.kezhenxu94.springagent.core.config;

import me.kezhenxu94.springagent.core.tools.toolsearch.ParallelAddVectorStore;
import me.kezhenxu94.springagent.core.tools.toolsearch.StatelessVectorToolIndex;
import org.springframework.ai.chat.client.advisor.toolsearch.autoconfigure.ToolSearchAdvisorAutoConfiguration;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
 */
@AutoConfiguration(before = ToolSearchAdvisorAutoConfiguration.class)
@ConditionalOnProperty(
    prefix = "spring.ai.chat.client.tool-search-advisor",
    name = "tool-index-type",
    havingValue = "vector")
public class ToolSearchIndexConfiguration {

  @Bean
  @ConditionalOnBean(VectorStore.class)
  @ConditionalOnMissingBean(ToolIndex.class)
  StatelessVectorToolIndex statelessVectorToolIndex(
      final VectorStore vectorStore,
      @Value("${app.ai.embedding.batch-size:10}") final int batchSize,
      @Value("${app.ai.embedding.concurrency:8}") final int concurrency) {
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
