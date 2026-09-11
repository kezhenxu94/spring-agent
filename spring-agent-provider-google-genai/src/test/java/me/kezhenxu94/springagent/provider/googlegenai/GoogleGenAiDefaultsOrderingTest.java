package me.kezhenxu94.springagent.provider.googlegenai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * That {@link GoogleGenAiDefaults} is actually registered and actually runs late enough, against a
 * real application rather than a context runner.
 *
 * <p>Deliberately not an {@code ApplicationContextRunner}: a runner never invokes an {@code
 * EnvironmentPostProcessor} at all, so every assertion in {@link GoogleGenAiDefaultsTest} would
 * pass with the {@code spring.factories} entry deleted. This is what notices.
 *
 * <p>Settings arrive as command-line arguments rather than through {@code properties(...)}, which
 * would make them {@code defaultProperties} — below the test {@code application.yaml} — so the
 * "already set" case would never fire and the blank-beats-nothing behaviour would go untested.
 */
class GoogleGenAiDefaultsOrderingTest {

  private static Environment run(final String... args) {
    try (var context =
        new SpringApplicationBuilder(NoBeans.class).web(WebApplicationType.NONE).run(args)) {
      return context.getEnvironment();
    }
  }

  @Test
  @DisplayName("it fills past the blank application.yaml an application actually has")
  void fillsPastABlankYaml() {
    final var environment = run("--" + GoogleGenAiProperties.API_KEY_PROPERTY + "=AIza-test");
    assertThat(environment.getProperty(GoogleGenAiDefaults.EMBEDDING_API_KEY))
        .isEqualTo("AIza-test");
  }

  @Test
  @DisplayName("a real embedding key still wins over the common one")
  void anExplicitValueWins() {
    final var environment =
        run(
            "--" + GoogleGenAiProperties.API_KEY_PROPERTY + "=AIza-common",
            "--" + GoogleGenAiDefaults.EMBEDDING_API_KEY + "=AIza-embeddings");
    assertThat(environment.getProperty(GoogleGenAiDefaults.EMBEDDING_API_KEY))
        .isEqualTo("AIza-embeddings");
  }

  @Test
  @DisplayName("with no key the blanks stay blank, and the application still starts")
  void noKeyLeavesTheBlanks() {
    final var environment = run();
    // Empty rather than null: the test application.yaml writes the blanks literally, exactly as a
    // real one does through ${GEMINI_API_KEY:}. Asserting null here would pass for the wrong
    // reason.
    assertThat(environment.getProperty(GoogleGenAiDefaults.EMBEDDING_API_KEY)).isEmpty();
  }

  @Configuration
  static class NoBeans {}
}
