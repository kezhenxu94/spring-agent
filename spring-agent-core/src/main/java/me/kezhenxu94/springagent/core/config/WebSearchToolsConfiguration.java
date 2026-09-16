package me.kezhenxu94.springagent.core.config;

import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.AgentTool;
import org.springaicommunity.agent.tools.BraveWebSearchTool;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * The {@code WebSearch} tool, wherever a deployment has given the agent a Brave Search key.
 *
 * <p>Off unless the key is set, and the key is the whole of the switch — there is no {@code
 * app.ai.tools.web-search.brave.enabled} beside it, because a search with no subscription token is
 * not a mode. This is the shape {@code ConditionalOnUserModels} documents and {@link
 * ConditionalOnNonBlankProperty} generalises: every {@code application.yaml} here writes its
 * settings as {@code ${SOME_VAR:}}, so the property is <em>present and empty</em> when nobody set
 * the variable, and a plain {@code @ConditionalOnProperty} would call that configured. The tool
 * would then be offered, every call would come back {@code 401 SUBSCRIPTION_TOKEN_INVALID}, and the
 * model would read that as a broken endpoint rather than as a feature nobody turned on.
 *
 * <p>Not registered rather than registered and failing, for the reason {@link
 * ModelToolsConfiguration} gives at length: a tool the model can see is a tool it will try, and one
 * that can only fail teaches it nothing except to try again.
 *
 * <p>{@code @AgentTool} on the bean method rather than the class — {@code BraveWebSearchTool} is a
 * third-party type and cannot carry it, and subclassing to attach it does not work either, since
 * Spring AI's {@code MethodToolCallbackProvider} scans with {@code
 * ReflectionUtils.getDeclaredMethods} and so never sees an inherited {@code @Tool} method. Same as
 * {@link LocalShellToolsConfiguration}.
 *
 * <p>What the tool tells the model about itself is overridden in {@code
 * core/prompts/tools/WebSearch.md}: upstream's text addresses Claude by name and claims the search
 * is US-only, neither of which is true of every deployment here.
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(WebSearchProperties.class)
@ConditionalOnNonBlankProperty("app.ai.tools.web-search.brave.api-key")
public class WebSearchToolsConfiguration {

  @Bean
  @AgentTool
  @ConditionalOnMissingBean
  BraveWebSearchTool braveWebSearchTool(final WebSearchProperties properties) {
    final var brave = properties.brave();
    log.info("Web search is on: Brave, {} result(s) per search", brave.resultCount());
    return BraveWebSearchTool.builder(brave.apiKey()).resultCount(brave.resultCount()).build();
  }
}
