package me.kezhenxu94.springagent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.autoconfigure.ToolSearchAdvisorAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * That the tool-search advisor builder in play is ours and not Spring AI's, which is decided by
 * auto-configuration ordering the same way {@link ToolSearchIndexConfigurationTest} pins it for the
 * tool index — and would otherwise fail just as silently: both builders build a working advisor, so
 * the only symptom of upstream's winning is the system-message suffix landing on the first
 * SystemMessage of a run instead of the last.
 *
 * <p>{@link AutoConfigurations} rather than plain {@code withUserConfiguration}, deliberately: it
 * sorts what it is given the way the real context would, so the {@code before} that decides this is
 * under test rather than assumed.
 */
class SpringAgentToolSearchAdvisorConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  PropertyPlaceholderAutoConfiguration.class,
                  ToolCallingAutoConfiguration.class,
                  SpringAgentToolSearchAdvisorConfiguration.class,
                  ToolSearchAdvisorAutoConfiguration.class))
          .withPropertyValues("spring.ai.chat.client.tool-search-advisor.enabled=true");

  @Test
  @DisplayName("the tool-search advisor builder is ours, and is the only one")
  void oursReplacesSpringAisBuilder() {
    runner.run(
        context ->
            assertThat(context.getBeansOfType(ToolCallingAdvisor.Builder.class).values())
                .singleElement()
                .isInstanceOf(
                    me.kezhenxu94.springagent.core.advisors.toolsearch.ToolSearchToolCallingAdvisor
                        .Builder.class));
  }

  @Test
  @DisplayName("no tool search means no tool-search builder of ours, upstream's, or otherwise")
  void backsOffWhereTheToolSearchIsOff() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                PropertyPlaceholderAutoConfiguration.class,
                ToolCallingAutoConfiguration.class,
                SpringAgentToolSearchAdvisorConfiguration.class,
                ToolSearchAdvisorAutoConfiguration.class))
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(ToolCallingAdvisor.Builder.class));
  }
}
