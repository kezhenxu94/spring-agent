package me.kezhenxu94.springagent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Map;
import java.util.Properties;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.Tools;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.Tools.AskUserQuestion;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.Tools.Subagent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The point of these settings living in an environment post processor is that the value is stated
 * once: the last time one was stated twice, raising it in the server's yaml left the command line
 * running the old number.
 */
class ToolDefaultsTest {

  private final ToolDefaults defaults = new ToolDefaults();

  @Test
  @DisplayName("an application that configures none of them binds every value")
  void shouldDefaultEverySetting() {
    final var environment = new StandardEnvironment();

    defaults.postProcessEnvironment(environment, null);

    // Bound rather than read one property at a time, since binding is what the application does
    // and it is the step that says the written form is one the binder accepts.
    final var tools =
        new Binder(
                ConfigurationPropertySources.get(environment),
                null,
                ApplicationConversionService.getSharedInstance())
            .bind("app.ai.tools", Tools.class)
            .get();

    assertThat(tools.askUserQuestion().enabled()).isEqualTo(AskUserQuestion.DEFAULT_ENABLED);
    assertThat(tools.askUserQuestion().ttl()).isEqualTo(AskUserQuestion.DEFAULT_TTL);
    assertThat(tools.subagent().maxConcurrent()).isEqualTo(Subagent.DEFAULT_MAX_CONCURRENT);
    assertThat(tools.subagent().waitPoll()).isEqualTo(Subagent.DEFAULT_WAIT_POLL);
    assertThat(tools.subagent().waitTimeout()).isEqualTo(Subagent.DEFAULT_WAIT_TIMEOUT);
    assertThat(tools.maxResultChars()).isEqualTo(Tools.DEFAULT_MAX_RESULT_CHARS);
  }

  @Test
  @DisplayName("the durations are written in a form the binder reads back as a Duration")
  void shouldWriteDurationsTheBinderUnderstands() {
    // ISO-8601 rather than the "60s" the yaml uses, since the constants are handed over as
    // Duration.toString(). Spring reads both, but only one of them is what a Duration writes.
    final var environment = new StandardEnvironment();

    defaults.postProcessEnvironment(environment, null);

    assertThat(environment.getProperty(ToolDefaults.SUBAGENT_WAIT_POLL)).isEqualTo("PT1M");
    assertThat(environment.getProperty(ToolDefaults.SUBAGENT_WAIT_TIMEOUT)).isEqualTo("PT30M");
  }

  @Test
  @DisplayName("anything the application does say wins")
  void shouldLetTheApplicationOverrideThem() {
    final var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "test",
                Map.of(
                    ToolDefaults.ASK_USER_QUESTION_ENABLED,
                    "false",
                    ToolDefaults.SUBAGENT_MAX_CONCURRENT,
                    "1")));

    defaults.postProcessEnvironment(environment, null);

    assertThat(environment.getProperty(ToolDefaults.ASK_USER_QUESTION_ENABLED)).isEqualTo("false");
    assertThat(environment.getProperty(ToolDefaults.SUBAGENT_MAX_CONCURRENT)).isEqualTo("1");
  }

  @Test
  @DisplayName("it is registered, which is the whole of how a consumer gets the defaults")
  void shouldBeRegisteredAsAnEnvironmentPostProcessor() throws Exception {
    // Nothing else says so if the registration is missing: the settings would simply fall back to
    // the record's own fallbacks again, which is the divergence this class exists to remove.
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

    assertThat(registered).anyMatch(names -> names.contains(ToolDefaults.class.getName()));
  }
}
