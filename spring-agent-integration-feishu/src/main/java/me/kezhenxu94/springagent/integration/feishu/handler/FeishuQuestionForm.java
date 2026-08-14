package me.kezhenxu94.springagent.integration.feishu.handler;

import com.google.common.base.Strings;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns the questions the agent asked into the card form the user answers them on, and turns what
 * they submitted back into answers.
 *
 * <p>Both directions live here because they are one agreement expressed twice: the names this class
 * writes onto the form elements are the keys it reads back out of {@code form_value}. Split across
 * two classes, a change to either would be a silent mismatch rather than a compile error.
 *
 * <p>Nothing here talks to Feishu — it is all JSON in and out — which is what makes the layout and
 * the parsing testable without a tenant.
 */
@Component
@RequiredArgsConstructor
public class FeishuQuestionForm {

  private final JsonMapper om;
  private final FeishuProperties feishuProperties;

  // Not final, matching FeishuCardListener#feishuReplyCard: @Value on a field is an injection point
  // in its own right, and AOT generates a plain field assignment for it, which cannot target a
  // final field the way the JVM's reflective injection can.
  @Value("${app.feishu.question-form:classpath:/feishu/question-form.json}")
  Resource questionForm;

  /**
   * What every id and name in one form is built from, so that two forms on one card cannot clash.
   *
   * <p>Element ids and form names both have to be unique across a whole card, and a card can be
   * asked on more than once — the run streams into a single card however many times the agent stops
   * to ask. Fixed names made the second ask fail outright with {@code Duplicate ID}.
   *
   * <p>Derived from the pending question rather than stored, so reaching a form again needs nothing
   * but the id already in hand. Feishu allows 20 characters, beginning with a letter and holding
   * only letters, digits and underscores; six from the id leaves ample room for the suffixes below
   * and makes a clash between two forms on one card a non-issue.
   */
  static String prefix(final String pendingQuestionId) {
    final var cleaned = Strings.nullToEmpty(pendingQuestionId).replaceAll("[^A-Za-z0-9]", "");
    return "a" + cleaned.substring(0, Math.min(6, cleaned.length()));
  }

  /** The form's own id, which the answer handler names to replace the form once it is answered. */
  static String formElementId(final String pendingQuestionId) {
    return prefix(pendingQuestionId) + "_f";
  }

  /** The name of the dropdown, and the {@code form_value} key its answer comes back under. */
  static String selectName(final String prefix, final int question) {
    return prefix + "_q" + question;
  }

  static String checkerName(final String prefix, final int question, final int option) {
    return prefix + "_q" + question + "o" + option;
  }

  static String otherName(final String prefix, final int question) {
    return prefix + "_q" + question + "x";
  }

  /**
   * The form, as the JSON array the card element API takes.
   *
   * @param pendingQuestionId travels in the submit button's callback payload, and is the only thing
   *     tying the press back to the conversation that asked — the run is gone by then.
   */
  public String build(final List<Question> questions, final String pendingQuestionId) {
    final var fragments = fragments();
    final var prefix = prefix(pendingQuestionId);
    final var form = fragments.get("form").deepCopy();
    form.put("element_id", formElementId(pendingQuestionId));
    form.put("name", formElementId(pendingQuestionId));
    final var elements = form.withArrayProperty("elements");

    for (var i = 0; i < questions.size(); i++) {
      final var question = questions.get(i);
      final var options =
          question.options() == null ? List.<Question.Option>of() : question.options();
      final var multiSelect = Boolean.TRUE.equals(question.multiSelect());

      final var text = fragments.get("questionText").deepCopy();
      text.put("content", prompt(question, options, multiSelect));
      elements.add(text);

      if (multiSelect) {
        // A checker per option, each carrying its own label: with the options as components rather
        // than as a list in the prompt, there is nothing to cross-reference while ticking them.
        for (var j = 0; j < options.size(); j++) {
          final var checker = fragments.get("checker").deepCopy();
          checker.put("name", checkerName(prefix, i, j));
          checker.withObjectProperty("text").put("content", label(options.get(j)));
          elements.add(checker);
        }
      } else if (!options.isEmpty()) {
        // Each entry names its own option, so choosing needs nothing but the open dropdown. The
        // value stays the index, which is what resolves back to the label on submission.
        final var select = fragments.get("select").deepCopy();
        select.put("name", selectName(prefix, i));
        final var optionNodes = select.withArrayProperty("options");
        for (var j = 0; j < options.size(); j++) {
          final var option = fragments.get("selectOption").deepCopy();
          option.withObjectProperty("text").put("content", options.get(j).label());
          option.put("value", String.valueOf(j));
          optionNodes.add(option);
        }
        elements.add(select);
      }

      // Offered on every question, including one with no options at all: the tool promises the user
      // can always answer in their own words.
      final var other = fragments.get("other").deepCopy();
      other.put("name", otherName(prefix, i));
      elements.add(other);
    }

    final var submit = fragments.get("submit").deepCopy();
    ((ObjectNode) submit.withArrayProperty("behaviors").get(0))
        .withObjectProperty("value")
        .put("pq", pendingQuestionId);
    elements.add(submit);

    final var wrapper = om.createArrayNode();
    wrapper.add(form);
    return om.writeValueAsString(wrapper);
  }

  /**
   * Puts the form onto the run's card, returning whether it landed.
   *
   * <p>The pending question's id doubles as the insert's idempotency key: it is unique to this one
   * ask and identical across a retry, which is exactly what stops a retried insert from leaving two
   * forms on the card.
   */
  public boolean insert(
      final FeishuCardUpdater cardUpdater,
      final List<Question> questions,
      final String pendingQuestionId) {
    return cardUpdater.insertBeforeFooter(build(questions, pendingQuestionId), pendingQuestionId);
  }

  /**
   * What replaces the form once it has been answered, so the card goes on saying what was asked and
   * chosen instead of offering controls that would start a second run.
   */
  public String answered(
      final List<Question> questions,
      final Map<String, String> answers,
      final String pendingQuestionId) {
    final var summary = new StringBuilder();
    for (final var question : questions) {
      final var answer = answers.get(question.question());
      if (Strings.isNullOrEmpty(answer)) {
        continue;
      }
      if (!summary.isEmpty()) {
        summary.append('\n');
      }
      summary.append("**").append(question.header()).append("** ").append(question.question());
      summary.append("\n→ ").append(answer);
    }
    return replacement(summary.toString(), pendingQuestionId);
  }

  /**
   * What replaces the form when a later message in the conversation overtook the questions — the
   * user said what they wanted in their own words instead of using the controls.
   *
   * <p>Without this the card would go on offering a form that no longer does anything: the row is
   * closed the moment that message arrives, so a press could only be refused, and refused with a
   * toast the reader has no way to have predicted. So the form goes, and what stays says why.
   */
  public String superseded(final List<Question> questions, final String pendingQuestionId) {
    final var summary = new StringBuilder();
    for (final var question : questions) {
      summary.append("**").append(question.header()).append("** ").append(question.question());
      summary.append('\n');
    }
    summary.append(feishuProperties.questionText().superseded());
    return replacement(summary.toString(), pendingQuestionId);
  }

  /**
   * A plain markdown element carrying {@code content}, under the id the form went in with, so an
   * element update puts it exactly where the form was.
   */
  private String replacement(final String content, final String pendingQuestionId) {
    final var element = fragments().get("questionText").deepCopy();
    element.put("element_id", formElementId(pendingQuestionId));
    element.put("content", content);
    return om.writeValueAsString(element);
  }

  /** The questions as they were stored when they were asked. */
  public List<Question> questions(final String questionsJson) {
    return om.readValue(questionsJson, new TypeReference<List<Question>>() {});
  }

  /**
   * The answers a submission carries, keyed by question text — the shape {@code
   * AskUserQuestionTool} hands back to the model.
   *
   * <p>A question nobody answered is left out rather than given an empty value, so the caller can
   * tell an empty result from a partial one and say so instead of waking the agent for nothing.
   */
  public Map<String, String> answers(
      final List<Question> questions,
      final Map<String, Object> formValue,
      final String pendingQuestionId) {
    final var values = formValue == null ? Map.<String, Object>of() : formValue;
    final var prefix = prefix(pendingQuestionId);
    final var answers = new LinkedHashMap<String, String>();

    for (var i = 0; i < questions.size(); i++) {
      final var question = questions.get(i);
      final var options =
          question.options() == null ? List.<Question.Option>of() : question.options();

      // Free text wins over a selection: someone who typed an answer as well as picking one meant
      // the words, or the option would have been enough on its own. Trimmed before it is judged
      // empty, so a box holding only spaces does not count as having been filled in.
      final var typed = Strings.nullToEmpty(string(values.get(otherName(prefix, i)))).trim();
      if (!typed.isEmpty()) {
        answers.put(question.question(), typed);
        continue;
      }

      if (Boolean.TRUE.equals(question.multiSelect())) {
        final var chosen = new ArrayList<String>();
        for (var j = 0; j < options.size(); j++) {
          if (Boolean.TRUE.equals(values.get(checkerName(prefix, i, j)))) {
            chosen.add(options.get(j).label());
          }
        }
        if (!chosen.isEmpty()) {
          // Comma-separated, the format the tool documents for a multi-select answer.
          answers.put(question.question(), String.join(", ", chosen));
        }
        continue;
      }

      final var selected = index(string(values.get(selectName(prefix, i))), options.size());
      if (selected >= 0) {
        answers.put(question.question(), options.get(selected).label());
      }
    }
    return answers;
  }

  /**
   * The prompt above a question's controls: what was asked, and whatever the controls themselves
   * cannot carry.
   *
   * <p>A checker holds its option's label and description both, so a multi-select needs nothing
   * more. A dropdown entry is plain text with room for the label only, so the descriptions — which
   * the tool requires of every option, and which are often what decides the choice — are listed
   * here instead.
   */
  private static String prompt(
      final Question question, final List<Question.Option> options, final boolean multiSelect) {
    final var prompt = new StringBuilder();
    prompt.append("**").append(question.header()).append("** ").append(question.question());
    if (multiSelect) {
      return prompt.toString();
    }
    for (final var option : options) {
      prompt.append("\n- ").append(label(option));
    }
    return prompt.toString();
  }

  private static String label(final Question.Option option) {
    final var label = new StringBuilder("**").append(option.label()).append("**");
    if (!Strings.isNullOrEmpty(option.description())) {
      label.append(" — ").append(option.description());
    }
    return label.toString();
  }

  private static String string(final Object value) {
    return value instanceof String text ? text : null;
  }

  /** The chosen option's index, or {@code -1} if nothing usable came back under that name. */
  private static int index(final String value, final int optionCount) {
    if (Strings.isNullOrEmpty(value)) {
      return -1;
    }
    try {
      final var index = Integer.parseInt(value.trim());
      return index >= 0 && index < optionCount ? index : -1;
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /**
   * The template, re-read per call rather than cached: it is one small file, this happens only when
   * the agent asks something, and reading it each time means editing the file does not need a
   * restart.
   */
  private Map<String, ObjectNode> fragments() {
    final String json;
    try {
      json = questionForm.getContentAsString(StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to read the question form template", e);
    }
    final var root = (ObjectNode) om.readTree(feishuProperties.questionText().render(json));
    final var fragments = new LinkedHashMap<String, ObjectNode>();
    root.propertyStream()
        .filter(entry -> entry.getValue() instanceof ObjectNode)
        .forEach(entry -> fragments.put(entry.getKey(), (ObjectNode) entry.getValue()));
    return fragments;
  }
}
