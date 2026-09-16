package me.kezhenxu94.springagent.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The web search the agent may reach for, and the credential that is what turns it on.
 *
 * <p>Its own {@code @ConfigurationProperties} rather than a component of {@link
 * SpringAgentProperties.Ai.Tools}, for the reason {@code ShellToolsProperties} and {@code
 * McpProperties} are: this names a service outside the deployment, and only the module that offers
 * the tool has any business binding the settings of it.
 *
 * @param brave Brave Search, the one provider {@code spring-ai-agent-utils} ships a tool for.
 */
@ConfigurationProperties(prefix = "app.ai.tools.web-search")
public record WebSearchProperties(Brave brave) {

  public WebSearchProperties {
    if (brave == null) {
      brave = new Brave(null, 0);
    }
  }

  /**
   * @param apiKey the Brave Search subscription token. <b>This is the switch</b> — see {@link
   *     WebSearchToolsConfiguration} for why there is no separate {@code enabled} flag beside it.
   * @param resultCount how many results one search asks Brave for. Every result is paid for and
   *     every result is read into the context window, so this is the knob that decides what a
   *     search costs in both currencies. Zero and below are read as "not configured" and replaced
   *     by {@link #DEFAULT_RESULT_COUNT}.
   */
  public record Brave(String apiKey, int resultCount) {

    /**
     * The library's own default, restated so that a deployment that configures nothing gets the
     * same number as one that configures this. Brave's own ceiling is twenty.
     */
    public static final int DEFAULT_RESULT_COUNT = 10;

    public Brave {
      if (resultCount <= 0) {
        resultCount = DEFAULT_RESULT_COUNT;
      }
    }
  }
}
