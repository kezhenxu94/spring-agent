package me.kezhenxu94.springagent.events.config;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Supplies the settings under {@code app.events} that this module needs a value for, so that an
 * application which merely puts it on its classpath behaves like the server in this repository
 * rather than however the binder happened to leave things.
 *
 * <p>The same reasoning as {@code ToolDefaults} in core, and the same trap avoided. A default
 * written twice — once as a constant read when the property is absent, once as the literal in an
 * {@code application.yaml} naming the environment variable — is a default that silently diverges:
 * raise one and the other keeps the old value. Here every value is stated once, as a constant on
 * {@link EventsProperties}, and both readers take it from there.
 *
 * <p>The yaml entries stay, because they are what gives each setting an environment variable to
 * override it with, and they are where the reasoning behind the value is written down. They name
 * the same values as the constants; keep them that way.
 *
 * <p>Nothing under {@code app.events.sources} is contributed here, and that absence is
 * load-bearing. Contributing a property under a map key creates the entry, and a source present in
 * that map is a source the deployment asked to run — so a default here would silently configure
 * receivers nobody set up. How a shipped source differs from the general case lives in {@code
 * EventsProperties.BUILT_IN} instead, which applies to an entry only once something has created it.
 *
 * <p>Contributed as the lowest-precedence property source, so anything set anywhere else — a yaml,
 * an environment variable, a command line — still wins.
 */
public class EventsDefaults implements EnvironmentPostProcessor, Ordered {

  static final String ENABLED = EventsProperties.PREFIX + ".enabled";
  static final String SWEEP_INTERVAL = EventsProperties.PREFIX + ".sweep-interval";
  static final String MAX_CONCURRENT_EVALUATIONS =
      EventsProperties.PREFIX + ".max-concurrent-evaluations";
  static final String MAX_EVENTS_PER_SITUATION =
      EventsProperties.PREFIX + ".max-events-per-situation";
  static final String MAX_EVIDENCE = EventsProperties.PREFIX + ".max-evidence";
  static final String MAX_BODY_SIZE = EventsProperties.PREFIX + ".max-body-size";
  static final String DEBOUNCE = EventsProperties.PREFIX + ".debounce";
  static final String MAX_DEBOUNCE = EventsProperties.PREFIX + ".max-debounce";
  static final String COOLDOWN = EventsProperties.PREFIX + ".cooldown";
  static final String RESOLVE_AFTER_QUIET = EventsProperties.PREFIX + ".resolve-after-quiet";
  static final String RESOLVE_AFTER_EVALUATION =
      EventsProperties.PREFIX + ".resolve-after-evaluation";
  static final String STUCK_INVESTIGATION_TIMEOUT =
      EventsProperties.PREFIX + ".stuck-investigation-timeout";

  @Override
  public void postProcessEnvironment(
      final ConfigurableEnvironment environment, final SpringApplication application) {
    environment
        .getPropertySources()
        .addLast(
            new MapPropertySource(
                "springAgentEventsDefaults",
                Map.ofEntries(
                    Map.entry(ENABLED, String.valueOf(EventsProperties.DEFAULT_ENABLED)),
                    // ISO-8601, which is what Duration.toString writes and what Spring's duration
                    // conversion reads, so the constant can be handed over without a format of its
                    // own in between.
                    Map.entry(SWEEP_INTERVAL, EventsProperties.DEFAULT_SWEEP_INTERVAL.toString()),
                    Map.entry(
                        MAX_CONCURRENT_EVALUATIONS,
                        String.valueOf(EventsProperties.DEFAULT_MAX_CONCURRENT_EVALUATIONS)),
                    Map.entry(
                        MAX_EVENTS_PER_SITUATION,
                        String.valueOf(EventsProperties.DEFAULT_MAX_EVENTS_PER_SITUATION)),
                    Map.entry(MAX_EVIDENCE, String.valueOf(EventsProperties.DEFAULT_MAX_EVIDENCE)),
                    // Bytes with the unit spelled out, rather than DataSize.toString, so the value
                    // read back does not depend on how that method chooses to render itself.
                    Map.entry(
                        MAX_BODY_SIZE, EventsProperties.DEFAULT_MAX_BODY_SIZE.toBytes() + "B"),
                    Map.entry(DEBOUNCE, EventsProperties.DEFAULT_DEBOUNCE.toString()),
                    Map.entry(MAX_DEBOUNCE, EventsProperties.DEFAULT_MAX_DEBOUNCE.toString()),
                    Map.entry(COOLDOWN, EventsProperties.DEFAULT_COOLDOWN.toString()),
                    Map.entry(
                        RESOLVE_AFTER_QUIET,
                        EventsProperties.DEFAULT_RESOLVE_AFTER_QUIET.toString()),
                    Map.entry(
                        RESOLVE_AFTER_EVALUATION,
                        String.valueOf(EventsProperties.DEFAULT_RESOLVE_AFTER_EVALUATION)),
                    Map.entry(
                        STUCK_INVESTIGATION_TIMEOUT,
                        EventsProperties.DEFAULT_STUCK_INVESTIGATION_TIMEOUT.toString()))));
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
