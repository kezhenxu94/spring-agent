package me.kezhenxu94.springagent.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import me.kezhenxu94.springagent.core.tools.toolsearch.StatelessVectorToolIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.toolsearch.autoconfigure.ToolSearchAdvisorAutoConfiguration;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.index.regex.RegexToolIndex;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * That the tool index in play is ours and not Spring AI's, which is decided by auto-configuration
 * ordering and would otherwise fail silently — Spring AI's index starts and answers searches, it
 * just cannot clear documents it did not write, so the only symptom is a search that fills up with
 * duplicates over time.
 *
 * <p>{@link AutoConfigurations} rather than plain {@code withUserConfiguration}, deliberately: it
 * sorts what it is given the way the real context would, so the {@code before} that decides this is
 * under test rather than assumed.
 */
class ToolSearchIndexConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  // The index bean reads two properties through @Value, which needs the
                  // placeholder configurer a Boot application always has and a runner does not.
                  PropertyPlaceholderAutoConfiguration.class,
                  ToolSearchIndexConfiguration.class,
                  ToolSearchAdvisorAutoConfiguration.class))
          .withUserConfiguration(AVectorStore.class)
          .withPropertyValues(
              "spring.ai.chat.client.tool-search-advisor.enabled=true",
              "spring.ai.chat.client.tool-search-advisor.tool-index-type=vector");

  @Test
  @DisplayName("the vector tool index is ours, and is the only one")
  void oursReplacesSpringAisVectorIndex() {
    runner.run(
        context ->
            assertThat(context.getBeansOfType(ToolIndex.class).values())
                .singleElement()
                .isInstanceOf(StatelessVectorToolIndex.class));
  }

  @Test
  @DisplayName("asking for another kind of index still gets that kind")
  void anotherIndexTypeIsLeftAlone() {
    // Ours replaces the vector index; it does not overrule the choice of index. Spring AI's regex
    // index keeps its state in the process and persists nothing, so there is nothing to replace.
    runner
        .withPropertyValues("spring.ai.chat.client.tool-search-advisor.tool-index-type=regex")
        .run(
            context ->
                assertThat(context.getBeansOfType(ToolIndex.class).values())
                    .singleElement()
                    .isInstanceOf(RegexToolIndex.class));
  }

  @Configuration(proxyBeanMethods = false)
  static class AVectorStore {
    @Bean
    VectorStore vectorStore() {
      return mock(VectorStore.class);
    }
  }
}
