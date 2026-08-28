package me.kezhenxu94.springagent.integration.feishu.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * A card written on every chunk the model produces costs the run a round trip per chunk, so how
 * fast it may be written is not something an application can be left to discover.
 */
class FeishuCardDefaultsTest {

  private final FeishuCardDefaults defaults = new FeishuCardDefaults();

  @Test
  @DisplayName("an application that configures neither still gets a rate")
  void shouldDefaultBoth() {
    final var environment = new StandardEnvironment();

    defaults.postProcessEnvironment(environment, null);

    assertThat(environment.getProperty(FeishuCardDefaults.CARD_STREAM_INTERVAL)).isEqualTo("1s");
    assertThat(environment.getProperty(FeishuCardDefaults.CARD_STREAM_CHARACTERS)).isEqualTo("400");
  }

  @Test
  @DisplayName("it is registered, which is the whole of how a consumer gets the default")
  void shouldBeRegisteredAsAnEnvironmentPostProcessor() throws Exception {
    // Nothing else says so if the registration is missing: the module would simply start refusing
    // to resolve the two properties, in whichever application first depended on it without setting
    // them itself. Read out of this module's own file rather than the first one on the classpath,
    // which under test is another module's.
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

    assertThat(registered).anyMatch(names -> names.contains(FeishuCardDefaults.class.getName()));
  }

  @Test
  @DisplayName("anything the application does say wins, including turning the buffering off")
  void shouldLetTheApplicationOverrideThem() {
    final var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "test",
                Map.of(
                    FeishuCardDefaults.CARD_STREAM_INTERVAL,
                    "0",
                    FeishuCardDefaults.CARD_STREAM_CHARACTERS,
                    "0")));

    defaults.postProcessEnvironment(environment, null);

    assertThat(environment.getProperty(FeishuCardDefaults.CARD_STREAM_INTERVAL)).isEqualTo("0");
    assertThat(environment.getProperty(FeishuCardDefaults.CARD_STREAM_CHARACTERS)).isEqualTo("0");
  }
}
