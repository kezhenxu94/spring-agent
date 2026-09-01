package me.kezhenxu94.springagent.events.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The point of these settings living in an environment post processor is that each value is stated
 * once, so that an application which merely puts this module on its classpath gets the same
 * behaviour as the server in this repository — and so that raising one in a yaml cannot leave
 * another consumer running the old number.
 */
class EventsDefaultsTest {

  private final EventsDefaults defaults = new EventsDefaults();

  @Test
  @DisplayName("an application that configures none of them binds every value")
  void shouldDefaultEverySetting() {
    final var environment = new StandardEnvironment();

    defaults.postProcessEnvironment(environment, null);

    // Bound rather than read one property at a time, since binding is what the application does and
    // it is the step that says the written form is one the binder accepts.
    final var properties = bind(environment);

    assertThat(properties.enabled()).isEqualTo(EventsProperties.DEFAULT_ENABLED);
    assertThat(properties.sweepInterval()).isEqualTo(EventsProperties.DEFAULT_SWEEP_INTERVAL);
    assertThat(properties.maxConcurrentEvaluations())
        .isEqualTo(EventsProperties.DEFAULT_MAX_CONCURRENT_EVALUATIONS);
    assertThat(properties.maxEventsPerSituation())
        .isEqualTo(EventsProperties.DEFAULT_MAX_EVENTS_PER_SITUATION);
    assertThat(properties.maxEvidence()).isEqualTo(EventsProperties.DEFAULT_MAX_EVIDENCE);
    assertThat(properties.maxBodySize()).isEqualTo(EventsProperties.DEFAULT_MAX_BODY_SIZE);
    assertThat(properties.debounce()).isEqualTo(EventsProperties.DEFAULT_DEBOUNCE);
    assertThat(properties.maxDebounce()).isEqualTo(EventsProperties.DEFAULT_MAX_DEBOUNCE);
    assertThat(properties.cooldown()).isEqualTo(EventsProperties.DEFAULT_COOLDOWN);
    assertThat(properties.resolveAfterQuiet())
        .isEqualTo(EventsProperties.DEFAULT_RESOLVE_AFTER_QUIET);
    assertThat(properties.resolveAfterEvaluation())
        .isEqualTo(EventsProperties.DEFAULT_RESOLVE_AFTER_EVALUATION);
    assertThat(properties.stuckInvestigationTimeout())
        .isEqualTo(EventsProperties.DEFAULT_STUCK_INVESTIGATION_TIMEOUT);
    // Not defaulted, and deliberately: a property has one value, so a default here would pin every
    // source to one wording in one language. Unset is what lets TriagePrompts pick the file for the
    // source, in the workspace's language.
    assertThat(properties.triagePrompt()).isNull();
  }

  @Test
  @DisplayName("off by default, so a classpath alone opens no endpoint and starts no sweep")
  void shouldBeOffByDefault() {
    // The one default that is a safety property rather than a tuning number. Turning this on
    // permits an unauthenticated path and lets the agent speak unprompted.
    final var environment = new StandardEnvironment();

    defaults.postProcessEnvironment(environment, null);

    assertThat(environment.getProperty(EventsDefaults.ENABLED)).isEqualTo("false");
  }

  @Test
  @DisplayName("no default creates a source, since a configured source is one somebody asked for")
  void shouldContributeNothingUnderSources() {
    // Contributing a property under a map key would create the entry, and this module treats the
    // presence of an entry as the deployment having asked for that receiver.
    final var environment = new StandardEnvironment();

    defaults.postProcessEnvironment(environment, null);

    assertThat(bind(environment).sources()).isEmpty();
  }

  @Test
  @DisplayName("the durations are written in a form the binder reads back as a Duration")
  void shouldWriteDurationsTheBinderUnderstands() {
    final var environment = new StandardEnvironment();

    defaults.postProcessEnvironment(environment, null);

    assertThat(environment.getProperty(EventsDefaults.DEBOUNCE)).isEqualTo("PT30S");
    assertThat(environment.getProperty(EventsDefaults.COOLDOWN)).isEqualTo("PT10M");
    assertThat(environment.getProperty(EventsDefaults.RESOLVE_AFTER_QUIET)).isEqualTo("PT6H");
    assertThat(environment.getProperty(EventsDefaults.STUCK_INVESTIGATION_TIMEOUT))
        .isEqualTo("PT20M");
  }

  @Test
  @DisplayName("the body size is written in a form the binder reads back as a DataSize")
  void shouldWriteTheBodySizeTheBinderUnderstands() {
    // Bytes with the unit spelled out, rather than DataSize.toString, so what is read back does not
    // depend on how that method chooses to render itself.
    final var environment = new StandardEnvironment();

    defaults.postProcessEnvironment(environment, null);

    assertThat(environment.getProperty(EventsDefaults.MAX_BODY_SIZE)).isEqualTo("1048576B");
    assertThat(bind(environment).maxBodySize().toMegabytes()).isEqualTo(1);
  }

  @Test
  @DisplayName("anything the application does say wins")
  void shouldLetTheApplicationOverrideThem() {
    final var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "test", Map.of(EventsDefaults.ENABLED, "true", EventsDefaults.DEBOUNCE, "PT1S")));

    defaults.postProcessEnvironment(environment, null);

    assertThat(environment.getProperty(EventsDefaults.ENABLED)).isEqualTo("true");
    assertThat(environment.getProperty(EventsDefaults.DEBOUNCE)).isEqualTo("PT1S");
  }

  @Test
  @DisplayName("it is registered, which is the whole of how a consumer gets the defaults")
  void shouldBeRegisteredAsAnEnvironmentPostProcessor() throws Exception {
    // Nothing else says so if the registration is missing: the settings would fall back to the
    // record's own fallbacks again, which is the divergence this class exists to remove.
    final var registrations =
        Thread.currentThread()
            .getContextClassLoader()
            .getResources("META-INF/spring.factories")
            .asIterator();
    final var registered = new ArrayList<String>();
    while (registrations.hasNext()) {
      final var properties = new Properties();
      try (final var in = registrations.next().openStream()) {
        properties.load(in);
      }
      registered.add(properties.getProperty(EnvironmentPostProcessor.class.getName(), ""));
    }

    assertThat(registered).anyMatch(names -> names.contains(EventsDefaults.class.getName()));
  }

  private static EventsProperties bind(final StandardEnvironment environment) {
    return new Binder(
            ConfigurationPropertySources.get(environment),
            null,
            ApplicationConversionService.getSharedInstance())
        .bind(EventsProperties.PREFIX, EventsProperties.class)
        .get();
  }
}
