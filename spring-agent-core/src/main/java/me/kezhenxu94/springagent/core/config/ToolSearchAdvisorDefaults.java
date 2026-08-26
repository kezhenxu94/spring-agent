package me.kezhenxu94.springagent.core.config;

import java.util.Locale;
import java.util.Map;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Defaults {@code spring.ai.chat.client.tool-search-advisor.session-id-key-name} to {@link
 * SpringAgent#TOOL_INDEX_KEY}, so that an application depending on core does not have to name in
 * its configuration a key that core alone decides.
 *
 * <p>The advisor reads its index key out of the advisor context under that name, and {@link
 * SpringAgent} is what puts it there; left at the advisor's own default the advisor would look for
 * the conversation id instead, and core's key would sit in the context unread. Which matters
 * because the index is keyed per user, not per conversation: it holds the descriptions of the tools
 * that user's MCP servers offer, the same in every conversation they have, so keyed by conversation
 * every new thread pays to embed the same few hundred descriptions again.
 *
 * <p>Defaults {@code system-message-suffix} in the same breath, to core's own {@code
 * tool-search-suffix} prompt in the workspace's language. The advisor appends that text to the end
 * of the system message, which makes it the last thing the model reads and so the strongest single
 * influence on how it works — and upstream's default is one English sentence saying a tool search
 * exists. It says nothing about what the search answers with (names, not definitions), nor when
 * those tools arrive (the next message, not this one), so a model told to use a tool it cannot see
 * spends its reasoning working the protocol out from scratch, in the language that sentence was
 * written in.
 *
 * <p>Contributed as the lowest-precedence property source, so an application that does set either
 * property still wins — including one that points the advisor back at the conversation id.
 */
public class ToolSearchAdvisorDefaults implements EnvironmentPostProcessor, Ordered {

  private static final String SESSION_ID_KEY_NAME =
      "spring.ai.chat.client.tool-search-advisor.session-id-key-name";

  private static final String SYSTEM_MESSAGE_SUFFIX =
      "spring.ai.chat.client.tool-search-advisor.system-message-suffix";

  /** The prompt file, without its locale suffix or extension. */
  static final String SUFFIX_PROMPT = "tool-search-suffix";

  @Override
  public void postProcessEnvironment(
      final ConfigurableEnvironment environment, final SpringApplication application) {
    environment
        .getPropertySources()
        .addLast(
            new MapPropertySource(
                "springAgentToolSearchAdvisorDefaults",
                Map.of(
                    SESSION_ID_KEY_NAME,
                    SpringAgent.TOOL_INDEX_KEY,
                    SYSTEM_MESSAGE_SUFFIX,
                    LocalizedPrompt.text(
                        SUFFIX_PROMPT, environment.getProperty("app.locale", Locale.class)))));
  }

  /**
   * Last, so that the property sources this appends after include the ones config data loaded from
   * {@code application.yaml} — which is also what makes {@code app.locale} readable here.
   */
  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
