package me.kezhenxu94.springagent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingProperties;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Spring AI's limits are library defaults meant for a chat assistant, and a turn that hits one ends
 * mid-thought rather than answering worse, which is a failure nobody reads as a limit being hit.
 */
class ToolCallingDefaultsTest {

  private final ToolCallingDefaults defaults = new ToolCallingDefaults();

  @Test
  @DisplayName("an application that configures neither limit binds both raised")
  void shouldRaiseBothLimits() {
    final var environment = new StandardEnvironment();

    defaults.postProcessEnvironment(environment, null);

    // Bound rather than read a property at a time, since binding is what the application does and
    // it is the step that says the key is one ToolCallingProperties actually reads.
    final var limits =
        new Binder(
                ConfigurationPropertySources.get(environment),
                null,
                ApplicationConversionService.getSharedInstance())
            .bind(ToolCallingProperties.CONFIG_PREFIX, ToolCallingProperties.class)
            .get()
            .getLimits();

    assertThat(limits.getMaxCallsPerToolDefault())
        .isEqualTo(ToolCallingDefaults.MAX_CALLS_PER_TOOL);
    assertThat(limits.getMaxTotalToolCalls()).isEqualTo(ToolCallingDefaults.MAX_TOTAL_TOOL_CALLS);
  }

  @Test
  @DisplayName("the per-tool limit is not left above the total, which would make it unreachable")
  void shouldNotLeaveThePerToolLimitAboveTheTotal() {
    // The one way to get this wrong: raise the per-tool limit alone and the total, being the
    // smaller of the two, goes on ending the turn where it always did.
    assertThat(ToolCallingDefaults.MAX_CALLS_PER_TOOL)
        .isLessThanOrEqualTo(ToolCallingDefaults.MAX_TOTAL_TOOL_CALLS);
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
                    ToolCallingDefaults.MAX_CALLS_PER_TOOL_DEFAULT_KEY,
                    "5",
                    ToolCallingDefaults.MAX_TOTAL_TOOL_CALLS_KEY,
                    "-1")));

    defaults.postProcessEnvironment(environment, null);

    assertThat(environment.getProperty(ToolCallingDefaults.MAX_CALLS_PER_TOOL_DEFAULT_KEY))
        .isEqualTo("5");
    assertThat(environment.getProperty(ToolCallingDefaults.MAX_TOTAL_TOOL_CALLS_KEY))
        .isEqualTo("-1");
  }

  @Test
  @DisplayName("it is registered, which is the whole of how a consumer gets the defaults")
  void shouldBeRegisteredAsAnEnvironmentPostProcessor() throws Exception {
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

    assertThat(registered).anyMatch(names -> names.contains(ToolCallingDefaults.class.getName()));
  }
}
