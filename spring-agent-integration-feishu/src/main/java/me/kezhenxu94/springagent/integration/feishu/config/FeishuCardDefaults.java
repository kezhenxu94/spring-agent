package me.kezhenxu94.springagent.integration.feishu.config;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Supplies how fast a card may be written to, so that an application which merely puts this module
 * on its classpath gets a rate the Feishu API and a reader can both live with rather than nothing
 * at all.
 *
 * <p>Which it has to be given, because the alternative — a card written on every chunk the model
 * produces — is not a cosmetic default. Each chunk carries the whole answer so far and used to
 * become an HTTP call made on the thread consuming the model's stream, under the card's lock, so a
 * turn cost a round trip per chunk and every subagent writing to that card queued behind it. See
 * {@code FeishuCard#stream} for what the two values do.
 *
 * <p>Here rather than as a default on the {@code @Value} that reads them, so that the values are
 * stated once: an application in this repository sets them in its {@code application.yaml} to name
 * the environment variables that override them, and a consumer of this module as a library sets
 * nothing and is covered by this.
 *
 * <p>Contributed as the lowest-precedence property source, so anything set anywhere else — a yaml,
 * an environment variable, a command line — still wins.
 */
public class FeishuCardDefaults implements EnvironmentPostProcessor, Ordered {

  static final String CARD_STREAM_INTERVAL = "app.feishu.card-stream-interval";

  static final String CARD_STREAM_CHARACTERS = "app.feishu.card-stream-characters";

  /**
   * A second between writes. Chosen for what a reader is doing rather than for what the API will
   * take: an answer that redraws once a second reads as it being written, and shortening it buys
   * smoothness the eye does not register while costing the run a round trip each time.
   */
  static final String DEFAULT_CARD_STREAM_INTERVAL = "1s";

  /**
   * How far behind the card may fall before it is written early anyway, so that a burst of text
   * does not sit unsent for the rest of the interval. Roughly a paragraph.
   */
  static final String DEFAULT_CARD_STREAM_CHARACTERS = "400";

  @Override
  public void postProcessEnvironment(
      final ConfigurableEnvironment environment, final SpringApplication application) {
    environment
        .getPropertySources()
        .addLast(
            new MapPropertySource(
                "springAgentFeishuCardDefaults",
                Map.of(
                    CARD_STREAM_INTERVAL,
                    DEFAULT_CARD_STREAM_INTERVAL,
                    CARD_STREAM_CHARACTERS,
                    DEFAULT_CARD_STREAM_CHARACTERS)));
  }

  /**
   * Last, so that the property sources this appends after include the ones config data loaded from
   * {@code application.yaml}.
   */
  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
