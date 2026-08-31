package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import me.kezhenxu94.springagent.events.source.WebhookSource;
import me.kezhenxu94.springagent.integration.github.config.GitHubAutoConfiguration;
import me.kezhenxu94.springagent.integration.gitlab.config.GitLabAutoConfiguration;
import me.kezhenxu94.springagent.integration.grafana.config.GrafanaAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * That every {@code WebhookSource} this application ships registers, together.
 *
 * <p>Each vendor's auto-configuration is independent, and independently correct: read alone, a
 * {@code @ConditionalOnMissingBean WebhookSource xxxWebhookSource()} looks like the usual
 * single-implementation guard. It is not one here — three configurations each contribute a bean of
 * the same {@code WebhookSource} type, and an unqualified {@code @ConditionalOnMissingBean} matches
 * by type. The first one Spring processes wins the type, and the other two back off believing a
 * {@code WebhookSource} is already provided, which is a bean of the wrong vendor rather than none.
 * {@code WebhookController} then answers a real vendor's webhook the same 404 it gives a name
 * nobody configured, and nothing in a single module's own test suite can catch it — each sees only
 * its own bean, present and correct.
 *
 * <p>This application is the one place all three are on the classpath at once, so it is the one
 * place the collision is reachable.
 */
class WebhookSourceAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  GitHubAutoConfiguration.class,
                  GitLabAutoConfiguration.class,
                  GrafanaAutoConfiguration.class))
          .withBean(Clock.class, Clock::systemUTC)
          .withPropertyValues("app.events.enabled=true");

  @Test
  @DisplayName("github, gitlab and grafana each register their own WebhookSource bean")
  void everyVendorRegistersItsOwnSource() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          final var sources = context.getBeansOfType(WebhookSource.class).values();
          assertThat(sources.stream().map(WebhookSource::name))
              .as("one WebhookSource bean per vendor, not one bean total")
              .containsExactlyInAnyOrder("github", "gitlab", "grafana");
        });
  }
}
