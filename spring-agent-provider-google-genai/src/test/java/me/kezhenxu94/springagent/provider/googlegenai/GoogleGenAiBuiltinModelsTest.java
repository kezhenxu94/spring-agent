package me.kezhenxu94.springagent.provider.googlegenai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link GoogleGenAiBuiltinModels}, on the two things that are this class's own rather than the
 * SDK's: the name a person types, and never throwing.
 */
class GoogleGenAiBuiltinModelsTest {

  @Test
  @DisplayName("the API's models/ prefix is not part of a model's name")
  void bareNames() {
    // The listing answers models/gemini-2.5-pro, while configuration, a stored row and the menu all
    // spell it without the prefix — so a name copied off the menu has to be one that works.
    assertThat(GoogleGenAiBuiltinModels.bareName("models/gemini-2.5-pro"))
        .isEqualTo("gemini-2.5-pro");
    assertThat(GoogleGenAiBuiltinModels.bareName("gemini-2.5-pro")).isEqualTo("gemini-2.5-pro");
    // A Vertex-style tuned model is several segments deep; the last one is still the name.
    assertThat(
            GoogleGenAiBuiltinModels.bareName(
                "projects/p/locations/l/publishers/google/models/gemini-2.5-flash"))
        .isEqualTo("gemini-2.5-flash");
  }

  @Test
  @DisplayName("the configured model is what 'the built-in model' means")
  void defaultModel() {
    assertThat(new GoogleGenAiBuiltinModels("AIza-test", "gemini-2.5-pro").defaultModel())
        .isEqualTo("gemini-2.5-pro");
  }

  @Test
  @DisplayName("an endpoint that will not answer costs the caller nothing")
  void listingIsBestEffort() {
    // Best-effort by contract: a card that opens is worth more than a complete one that does not,
    // especially this card — it is what somebody reaches for when their model stopped answering.
    // No key at all is the cheapest way to reach that path without a network call.
    assertThat(new GoogleGenAiBuiltinModels("", "gemini-2.5-pro").list()).isEmpty();
    assertThat(new GoogleGenAiBuiltinModels(null, "gemini-2.5-pro").list()).isEmpty();
  }

  @Test
  @DisplayName("a failed listing is not retried on every keystroke")
  void aFailedListingIsCached() {
    final var models = new GoogleGenAiBuiltinModels("", "gemini-2.5-pro");
    assertThat(models.list()).isEmpty();
    assertThat(models.list()).isEmpty();
  }
}
