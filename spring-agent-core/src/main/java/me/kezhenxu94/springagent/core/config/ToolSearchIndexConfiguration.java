package me.kezhenxu94.springagent.core.config;

import me.kezhenxu94.springagent.core.tools.toolsearch.StatelessVectorToolIndex;
import org.springframework.ai.chat.client.advisor.toolsearch.autoconfigure.ToolSearchAdvisorAutoConfiguration;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.vectorstore.VectorStore;
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
  StatelessVectorToolIndex statelessVectorToolIndex(final VectorStore vectorStore) {
    return new StatelessVectorToolIndex(vectorStore);
  }
}
