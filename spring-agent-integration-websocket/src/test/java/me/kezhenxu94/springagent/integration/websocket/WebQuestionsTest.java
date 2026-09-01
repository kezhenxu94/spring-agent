package me.kezhenxu94.springagent.integration.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import me.kezhenxu94.springagent.integration.websocket.web.WebQuestions;
import me.kezhenxu94.springagent.integration.websocket.web.WebQuestions.Submitted;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question.Option;

/**
 * The form goes out as indexes and comes back as indexes; the model is answered in the words it
 * wrote. Getting that mapping wrong is invisible in the UI and shows up as the model being told the
 * user chose something they did not.
 */
class WebQuestionsTest {

  private static final Question SINGLE =
      new Question(
          "Which store?",
          "Store",
          List.of(new Option("Postgres", "A server"), new Option("SQLite", "A file")),
          false);

  private static final Question MULTI =
      new Question(
          "Which of these?",
          "Extras",
          List.of(
              new Option("Metrics", "Prometheus"),
              new Option("Tracing", "OpenTelemetry"),
              new Option("Logs", "Plain text")),
          true);

  @Test
  @DisplayName("a form carries the index of each question, so the browser never echoes its text")
  void formCarriesIndexes() {
    final var json = WebQuestions.asJson(List.of(SINGLE, MULTI));

    assertThat(json).hasSize(2);
    assertThat(json.get(0)).containsEntry("index", 0).containsEntry("multiSelect", false);
    assertThat(json.get(1)).containsEntry("index", 1).containsEntry("multiSelect", true);
    assertThat((List<?>) json.get(1).get("options")).hasSize(3);
  }

  @Test
  @DisplayName("an answer is the option's label, because that is the word the model wrote")
  void answersAreLabels() {
    final var answers =
        WebQuestions.answers(List.of(SINGLE), List.of(new Submitted(0, List.of(1), null)));

    assertThat(answers).containsExactly(Map.entry("Which store?", "SQLite"));
  }

  @Test
  @DisplayName("several choices are joined, which is what the tool's contract asks for")
  void multiSelectIsJoined() {
    final var answers =
        WebQuestions.answers(List.of(MULTI), List.of(new Submitted(0, List.of(0, 2), null)));

    assertThat(answers).containsExactly(Map.entry("Which of these?", "Metrics, Logs"));
  }

  @Test
  @DisplayName("free text is an answer in its own right")
  void freeTextCounts() {
    final var answers =
        WebQuestions.answers(List.of(SINGLE), List.of(new Submitted(0, List.of(), "  Neither  ")));

    assertThat(answers).containsExactly(Map.entry("Which store?", "Neither"));
  }

  @Test
  @DisplayName("a question nobody answered is left out rather than answered emptily")
  void unansweredQuestionsAreOmitted() {
    final var answers =
        WebQuestions.answers(
            List.of(SINGLE, MULTI),
            List.of(new Submitted(0, List.of(0), null), new Submitted(1, List.of(), "")));

    // The model reads an absent key as "they did not say", which is true. A blank value would read
    // as an answer of nothing, which is not.
    assertThat(answers).containsOnlyKeys("Which store?");
  }

  @Test
  @DisplayName("an index the browser made up is ignored rather than crashing the answer")
  void outOfRangeIndexesAreIgnored() {
    final var answers =
        WebQuestions.answers(
            List.of(SINGLE),
            List.of(new Submitted(7, List.of(0), null), new Submitted(0, List.of(9), "typed")));

    assertThat(answers).containsExactly(Map.entry("Which store?", "typed"));
  }
}
