package me.kezhenxu94.springagent.provider.googlegenai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

/**
 * What {@link GoogleGenAiDefaults} contributes, asserted against a bound environment rather than by
 * reading the yaml — so a setting that lands at a nesting level Boot ignores in silence is caught.
 * The same reasoning as {@code DashScopeDefaultsTest}.
 */
class GoogleGenAiDefaultsTest {

  private final GoogleGenAiDefaults defaults = new GoogleGenAiDefaults();

  private static MockEnvironment environment() {
    return new MockEnvironment();
  }

  private void run(final MockEnvironment environment) {
    defaults.postProcessEnvironment(environment, new SpringApplication());
  }

  @Test
  @DisplayName("one key reaches the embedding connection, which reads a key of its own")
  void fillsInTheEmbeddingCredential() {
    final var environment =
        environment().withProperty(GoogleGenAiProperties.API_KEY_PROPERTY, "AIza-test");
    run(environment);
    assertThat(environment.getProperty(GoogleGenAiDefaults.EMBEDDING_API_KEY))
        .as(
            "without this the embedding connection falls through to its Vertex branch and fails"
                + " startup with 'Google GenAI project-id must be set!'")
        .isEqualTo("AIza-test");
  }

  @Test
  @DisplayName("an embedding key somebody named themselves still wins")
  void doesNotOverrideWhatWasSet() {
    final var environment =
        environment()
            .withProperty(GoogleGenAiProperties.API_KEY_PROPERTY, "AIza-common")
            .withProperty(GoogleGenAiDefaults.EMBEDDING_API_KEY, "AIza-embeddings");
    run(environment);
    assertThat(environment.getProperty(GoogleGenAiDefaults.EMBEDDING_API_KEY))
        .isEqualTo("AIza-embeddings");
  }

  @Test
  @DisplayName("a present-but-empty target is not a value and is filled")
  void anEmptyValueIsNotAValue() {
    // The case the whole class is shaped around: `api-key: ${GEMINI_EMBEDDING_API_KEY:}` leaves the
    // property present and empty, and present beats a lowest-precedence source. Hence addFirst and
    // a per-property check rather than a fallback source.
    final var environment =
        environment()
            .withProperty(GoogleGenAiProperties.API_KEY_PROPERTY, "AIza-test")
            .withProperty(GoogleGenAiDefaults.EMBEDDING_API_KEY, "");
    run(environment);
    assertThat(environment.getProperty(GoogleGenAiDefaults.EMBEDDING_API_KEY))
        .isEqualTo("AIza-test");
  }

  @Test
  @DisplayName("inert without a key, so a deployment on another provider is untouched")
  void inertWithoutAKey() {
    final var empty = environment();
    run(empty);
    assertThat(empty.getProperty(GoogleGenAiDefaults.EMBEDDING_API_KEY)).isNull();

    final var blank = environment().withProperty(GoogleGenAiProperties.API_KEY_PROPERTY, "   ");
    run(blank);
    assertThat(blank.getProperty(GoogleGenAiDefaults.EMBEDDING_API_KEY)).isNull();
  }

  @Test
  @DisplayName("it runs last among post-processors, after config data has been read")
  void ordering() {
    // Before Boot's own config-data processing has finished is too early: the application.yaml
    // values this class reads to decide "already set" have to be there.
    assertThat(defaults.getOrder()).isEqualTo(org.springframework.core.Ordered.LOWEST_PRECEDENCE);
  }
}
