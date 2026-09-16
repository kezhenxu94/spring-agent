package me.kezhenxu94.springagent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.BraveWebSearchTool;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Whether the agent is offered {@code WebSearch}, which is decided by whether the deployment gave
 * it a Brave key and by nothing else.
 *
 * <p>The blank case is the one worth a test. Every {@code application.yaml} here writes the key as
 * {@code ${BRAVE_API_KEY:}}, so on a deployment that never heard of Brave the property is present
 * and empty rather than absent — and that is exactly the value {@code @ConditionalOnProperty} would
 * have called configured. Getting it wrong fails nowhere near here: the tool is offered, every
 * search comes back unauthorised, and the model reads that as an endpoint having a bad day.
 *
 * <p>It also covers being listed in {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}: an
 * auto-configuration missing from that file contributes nothing at all, with no error anywhere.
 */
class WebSearchToolsConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(WebSearchToolsConfiguration.class));

  @Test
  @DisplayName("no key means no tool, rather than a tool that fails every call")
  void noKeyNoTool() {
    runner.run(context -> assertThat(context).doesNotHaveBean(BraveWebSearchTool.class));
  }

  @Test
  @DisplayName("a key nobody set — present and empty — is still no key")
  void aBlankKeyIsNoKey() {
    runner
        .withPropertyValues("app.ai.tools.web-search.brave.api-key=")
        .run(context -> assertThat(context).doesNotHaveBean(BraveWebSearchTool.class));
  }

  @Test
  @DisplayName("a key is the whole of the switch")
  void aKeyRegistersTheTool() {
    runner
        .withPropertyValues("app.ai.tools.web-search.brave.api-key=brave-token")
        .run(context -> assertThat(context).hasSingleBean(BraveWebSearchTool.class));
  }

  @Test
  @DisplayName("how many results a search asks for is configurable, and defaulted when it is not")
  void resultCountIsBound() {
    runner
        .withPropertyValues("app.ai.tools.web-search.brave.api-key=brave-token")
        .run(
            context ->
                assertThat(context.getBean(WebSearchProperties.class).brave().resultCount())
                    .isEqualTo(WebSearchProperties.Brave.DEFAULT_RESULT_COUNT));

    runner
        .withPropertyValues(
            "app.ai.tools.web-search.brave.api-key=brave-token",
            "app.ai.tools.web-search.brave.result-count=20")
        .run(
            context ->
                assertThat(context.getBean(WebSearchProperties.class).brave().resultCount())
                    .isEqualTo(20));
  }
}
