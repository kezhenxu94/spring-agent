package me.kezhenxu94.springagent.integration.github.config;

import me.kezhenxu94.springagent.events.config.EventsAutoConfiguration;
import me.kezhenxu94.springagent.events.config.EventsProperties;
import me.kezhenxu94.springagent.events.source.WebhookSource;
import me.kezhenxu94.springagent.integration.github.GitHubWebhookSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Offers GitHub webhooks as something the agent can be told about.
 *
 * <p>All this module contributes is one {@link WebhookSource}: how to tell a genuine GitHub
 * webhooks delivery from a forged one, and how to read it. Everything after that — recording it,
 * correlating it with what came before, deciding whether any of it is worth waking the agent for —
 * belongs to {@code spring-agent-events} and is the same whatever the source.
 *
 * <p>Conditional on the same property the engine is, because a source with no receiver behind it
 * can do nothing at all: {@code app.events.enabled} is what puts the endpoint there. Taking this
 * module therefore decides nothing on its own, which is why the application can depend on all three
 * vendors and watch none of them.
 *
 * <p>A single {@code @Bean} rather than a component scan. There is one class to register, and
 * naming it here is what makes the dependency on the engine's {@code Clock} visible at the point it
 * is taken.
 */
@AutoConfiguration(after = EventsAutoConfiguration.class)
@ConditionalOnProperty(prefix = EventsProperties.PREFIX, name = "enabled", havingValue = "true")
public class GitHubAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  WebhookSource githubWebhookSource() {
    return new GitHubWebhookSource();
  }
}
