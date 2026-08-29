package me.kezhenxu94.springagent.integration.grafana.config;

import java.time.Clock;
import me.kezhenxu94.springagent.events.config.EventsAutoConfiguration;
import me.kezhenxu94.springagent.events.config.EventsProperties;
import me.kezhenxu94.springagent.events.source.WebhookSource;
import me.kezhenxu94.springagent.integration.grafana.GrafanaWebhookSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Offers Grafana alert notifications as something the agent can be told about.
 *
 * <p>All this module contributes is one {@link WebhookSource}: how to tell a genuine Grafana alert
 * notifications delivery from a forged one, and how to read it. Everything after that — recording
 * it, correlating it with what came before, deciding whether any of it is worth waking the agent
 * for — belongs to {@code spring-agent-events} and is the same whatever the source.
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
public class GrafanaAutoConfiguration {

  /**
   * @param clock the engine's, because Grafana sends no delivery id and this source has to mint one
   *     from the body and the minute it arrived in — the one thing here that depends on what time
   *     it is, and so the one thing a test has to be able to move.
   */
  @Bean
  @ConditionalOnMissingBean
  WebhookSource grafanaWebhookSource(final Clock clock) {
    return new GrafanaWebhookSource(clock);
  }
}
