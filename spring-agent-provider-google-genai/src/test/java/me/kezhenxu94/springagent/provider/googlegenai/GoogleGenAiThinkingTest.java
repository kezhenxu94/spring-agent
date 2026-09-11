package me.kezhenxu94.springagent.provider.googlegenai;

import static org.assertj.core.api.Assertions.assertThat;

import me.kezhenxu94.springagent.core.usermodels.ReasoningEfforts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;

/**
 * Core's reasoning-effort vocabulary onto Gemini's, which is the part of this module most easily
 * broken by a change that looks harmless — the three states of a thinking configuration are
 * invisible from outside a built client.
 */
class GoogleGenAiThinkingTest {

  private static GoogleGenAiChatOptions applied(final String effort) {
    final var builder = GoogleGenAiChatOptions.builder().model("gemini-2.5-pro");
    GoogleGenAiThinking.apply(builder, effort);
    return builder.build();
  }

  @Test
  @DisplayName("every effort core offers is accounted for")
  void everyCoreEffortIsHandled() {
    // The assertion that notices when core's ladder grows a rung: an effort nobody mapped is left
    // to the endpoint, which is safe but silently ignores what the user chose.
    assertThat(ReasoningEfforts.VALUES)
        .allSatisfy(
            effort -> {
              final var options = applied(effort);
              assertThat(options.getThinkingLevel() != null || options.getThinkingBudget() != null)
                  .as("%s should map to a thinking level or a budget", effort)
                  .isTrue();
            });
  }

  @Test
  @DisplayName("the ladder maps onto Gemini's four levels")
  void theLadder() {
    assertThat(applied("minimal").getThinkingLevel()).isEqualTo(GoogleGenAiThinkingLevel.MINIMAL);
    assertThat(applied("low").getThinkingLevel()).isEqualTo(GoogleGenAiThinkingLevel.LOW);
    assertThat(applied("medium").getThinkingLevel()).isEqualTo(GoogleGenAiThinkingLevel.MEDIUM);
    assertThat(applied("high").getThinkingLevel()).isEqualTo(GoogleGenAiThinkingLevel.HIGH);
  }

  @Test
  @DisplayName("asking for more than Gemini has is the most it will do, not an error")
  void aboveTheLadder() {
    // core's ladder is OpenAI's and goes two rungs further; Gemini stops at HIGH.
    assertThat(applied("xhigh").getThinkingLevel()).isEqualTo(GoogleGenAiThinkingLevel.HIGH);
    assertThat(applied("max").getThinkingLevel()).isEqualTo(GoogleGenAiThinkingLevel.HIGH);
  }

  @Test
  @DisplayName("'none' is a budget of zero, because the enum has no 'off'")
  void noneIsABudget() {
    final var options = applied("none");
    // MINIMAL's own documentation says it "does not guarantee that thinking is off", so mapping
    // none onto it would quietly not do what was asked.
    assertThat(options.getThinkingBudget()).isEqualTo(GoogleGenAiThinking.NO_THINKING_BUDGET);
    assertThat(options.getThinkingLevel()).isNull();
  }

  @Test
  @DisplayName("'not-sent' sets neither field, which is a third state and not a synonym for none")
  void notSentSetsNothing() {
    final var options = applied(ReasoningEfforts.NOT_SENT);
    assertThat(options.getThinkingLevel()).isNull();
    assertThat(options.getThinkingBudget()).isNull();
  }

  @Test
  @DisplayName("choosing nothing leaves whatever the deployment configured")
  void nothingChosenChangesNothing() {
    final var configured =
        GoogleGenAiChatOptions.builder()
            .model("gemini-2.5-pro")
            .thinkingLevel(GoogleGenAiThinkingLevel.MEDIUM)
            .build();

    final var builder = configured.mutate();
    GoogleGenAiThinking.apply(builder, null);
    assertThat(builder.build().getThinkingLevel()).isEqualTo(GoogleGenAiThinkingLevel.MEDIUM);

    final var blank = configured.mutate();
    GoogleGenAiThinking.apply(blank, "");
    assertThat(blank.build().getThinkingLevel()).isEqualTo(GoogleGenAiThinkingLevel.MEDIUM);
  }

  @Test
  @DisplayName("a word from no version of this project is left to the endpoint")
  void anUnknownEffortIsIgnored() {
    // Only reachable through a row written by an older or newer build, since core normalises what
    // it stores. Leaving it alone beats guessing at what somebody meant.
    final var options = applied("enthusiastic");
    assertThat(options.getThinkingLevel()).isNull();
    assertThat(options.getThinkingBudget()).isNull();
  }

  @Test
  @DisplayName("case is the caller's business, not the mapping's")
  void caseIsIgnored() {
    assertThat(applied("HIGH").getThinkingLevel()).isEqualTo(GoogleGenAiThinkingLevel.HIGH);
    assertThat(GoogleGenAiThinking.levelOf("Medium")).isEqualTo(GoogleGenAiThinkingLevel.MEDIUM);
  }

  // --- the mapping backwards, which is what a surface printing the effort reads ------------

  @Test
  @DisplayName("a configured level answers in core's vocabulary, not Gemini's")
  void effortOfALevel() {
    // The whole reason ReasoningEffortInForce is a core contract: a card shows the word the person
    // choosing it saw, and that ladder is core's, not this provider's enum.
    assertThat(GoogleGenAiThinking.effortOf(withLevel(GoogleGenAiThinkingLevel.MINIMAL)))
        .isEqualTo("minimal");
    assertThat(GoogleGenAiThinking.effortOf(withLevel(GoogleGenAiThinkingLevel.LOW)))
        .isEqualTo("low");
    assertThat(GoogleGenAiThinking.effortOf(withLevel(GoogleGenAiThinkingLevel.MEDIUM)))
        .isEqualTo("medium");
    assertThat(GoogleGenAiThinking.effortOf(withLevel(GoogleGenAiThinkingLevel.HIGH)))
        .isEqualTo("high");
  }

  @Test
  @DisplayName("HIGH answers 'high', the word that round-trips")
  void highRoundTrips() {
    // xhigh and max also map onto HIGH, so the reverse has to pick one; picking either of those
    // would claim more was asked for than the endpoint can do.
    final var effort = GoogleGenAiThinking.effortOf(applied("xhigh"));
    assertThat(effort).isEqualTo("high");
    assertThat(applied(effort).getThinkingLevel()).isEqualTo(GoogleGenAiThinkingLevel.HIGH);
  }

  @Test
  @DisplayName("a zero budget reads back as 'none', which is how it was written")
  void effortOfNone() {
    assertThat(GoogleGenAiThinking.effortOf(applied("none"))).isEqualTo(GoogleGenAiThinking.NONE);
  }

  @Test
  @DisplayName("configuring nothing reads back as nothing")
  void effortOfNothing() {
    assertThat(GoogleGenAiThinking.effortOf(applied(ReasoningEfforts.NOT_SENT))).isNull();
    assertThat(
            GoogleGenAiThinking.effortOf(
                GoogleGenAiChatOptions.builder().model("gemini-2.5-pro").build()))
        .isNull();
    assertThat(GoogleGenAiThinking.effortOf(null)).isNull();
  }

  @Test
  @DisplayName("every effort core offers survives the round trip to a word core knows")
  void everyEffortRoundTripsIntoTheLadder() {
    // Not identity — xhigh and max collapse onto high — but the answer must always be something
    // core's own vocabulary contains, since that is what a surface renders.
    assertThat(ReasoningEfforts.VALUES)
        .allSatisfy(
            effort ->
                assertThat(GoogleGenAiThinking.effortOf(applied(effort)))
                    .as("%s should read back as a word core knows", effort)
                    .isIn(ReasoningEfforts.VALUES));
  }

  private static GoogleGenAiChatOptions withLevel(final GoogleGenAiThinkingLevel level) {
    return GoogleGenAiChatOptions.builder().model("gemini-2.5-pro").thinkingLevel(level).build();
  }
}
