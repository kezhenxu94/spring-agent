package me.kezhenxu94.springagent.integration.websocket.web;

import com.google.common.base.Strings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;

/**
 * The two directions a question form travels: out to the browser as JSON, and back as answers the
 * model will recognise.
 *
 * <p>One class for both because they are one format, and the failure mode of splitting them is a
 * form whose options are rendered from one shape and read back with another — which shows up as the
 * model being told the user chose something they did not.
 *
 * <p>Nothing here talks to HTTP, which is what makes the mapping testable without a browser; the
 * same reason {@code FeishuQuestionForm} is its own class.
 */
public final class WebQuestions {

  private WebQuestions() {}

  /** What the frontend draws a form from. */
  public static List<Map<String, Object>> asJson(final List<Question> questions) {
    final var out = new ArrayList<Map<String, Object>>();
    for (var i = 0; i < questions.size(); i++) {
      final var question = questions.get(i);
      final var options = new ArrayList<Map<String, Object>>();
      if (question.options() != null) {
        for (final var option : question.options()) {
          options.add(
              Map.of(
                  "label", Strings.nullToEmpty(option.label()),
                  "description", Strings.nullToEmpty(option.description())));
        }
      }
      final var item = new LinkedHashMap<String, Object>();
      // The index, not the text: the text is the model's and can be long, and it is what the
      // answers are keyed by on the way back, so the browser should never have to echo it.
      item.put("index", i);
      item.put("question", question.question());
      item.put("header", question.header());
      item.put("multiSelect", Boolean.TRUE.equals(question.multiSelect()));
      item.put("options", options);
      out.add(item);
    }
    return out;
  }

  /**
   * Turns what the browser submitted into the map the tool returns to the model.
   *
   * <p>Keyed by question text and valued with option labels, comma-separated for a multi-select,
   * because that is the contract {@code AskUserQuestionTool.QuestionHandler} states — the model
   * recognises the words it wrote, not indexes into a list it cannot see.
   *
   * @param submitted per question index, the chosen option indexes and any free text
   */
  public static Map<String, String> answers(
      final List<Question> questions, final List<Submitted> submitted) {
    final var answers = new LinkedHashMap<String, String>();
    for (final var one : submitted) {
      if (one.index() < 0 || one.index() >= questions.size()) {
        continue;
      }
      final var question = questions.get(one.index());
      final var chosen = new ArrayList<String>();
      if (one.optionIndexes() != null) {
        for (final var index : one.optionIndexes()) {
          if (question.options() != null && index >= 0 && index < question.options().size()) {
            chosen.add(question.options().get(index).label());
          }
        }
      }
      // Free text is an answer in its own right, not a footnote to the options: the tool's contract
      // allows either, and a user who typed rather than clicked meant the typing.
      if (!Strings.isNullOrEmpty(one.text())) {
        chosen.add(one.text().trim());
      }
      if (!chosen.isEmpty()) {
        answers.put(question.question(), String.join(", ", chosen));
      }
    }
    return answers;
  }

  /** One question's worth of what the browser sent back. */
  public record Submitted(int index, List<Integer> optionIndexes, String text) {}
}
