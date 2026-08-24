package me.kezhenxu94.springagent.integration.feishu.handler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question.Option;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The names written onto the form are the keys read back out of {@code form_value}, and nothing at
 * runtime would report a mismatch: a renamed element simply comes back unanswered, which reads as
 * the user having skipped the question. So both directions are pinned here, together.
 */
class FeishuQuestionFormTest {

  /** A realistic pending question id — a UUID, as {@code FeishuQuestionHandler} mints them. */
  private static final String PQ = "3f9b1c2d-4e5f-6071-8293-a4b5c6d7e8f9";

  private static final String PREFIX = FeishuQuestionForm.prefix(PQ);

  private final JsonMapper om = new JsonMapper();

  private FeishuQuestionForm form() {
    final var messages =
        new FeishuMessages(
            new FeishuProperties(
                null, null, null, null, null, null, null, Locale.ENGLISH, null, null));
    final var form = new FeishuQuestionForm(om, messages);
    form.questionForm = new ClassPathResource("feishu/question-form.json");
    return form;
  }

  private static Question single() {
    return new Question(
        "Which database should we use?",
        "Database",
        List.of(
            new Option("Postgres", "Battle-tested, ops you already run"),
            new Option("SQLite", "Zero ops, single writer")),
        false);
  }

  private static Question multi() {
    return new Question(
        "Which features should be enabled?",
        "Features",
        List.of(new Option("Auth", "Sign-in"), new Option("Search", "Full text")),
        true);
  }

  /** The card's own elements, unwrapped from the single-element array the API takes. */
  private JsonNode elements(final String built) {
    final var wrapper = om.readTree(built);
    assertThat(wrapper.size()).isOne();
    final var formNode = wrapper.get(0);
    assertThat(formNode.get("tag").asString()).isEqualTo("form");
    return formNode.get("elements");
  }

  private static JsonNode firstWithTag(final JsonNode elements, final String tag) {
    for (final var element : elements) {
      if (tag.equals(element.path("tag").asString())) {
        return element;
      }
    }
    throw new AssertionError("no element tagged " + tag);
  }

  private static List<JsonNode> allWithTag(final JsonNode elements, final String tag) {
    return elements.valueStream().filter(e -> tag.equals(e.path("tag").asString())).toList();
  }

  @Test
  @DisplayName("every id and name is derived from the pending question, so two forms cannot clash")
  void namesAreScopedToTheAsk() {
    // Element ids and form names have to be unique across a card, and one card can be asked on more
    // than once. Naming these off a constant made the second ask fail outright with Duplicate ID.
    assertThat(FeishuQuestionForm.prefix(PQ)).isEqualTo("a3f9b1c");
    assertThat(FeishuQuestionForm.formElementId(PQ)).isEqualTo("a3f9b1c_f");
    assertThat(FeishuQuestionForm.formElementId("other-question-id"))
        .isNotEqualTo(FeishuQuestionForm.formElementId(PQ));

    final var formNode = om.readTree(form().build(List.of(single()), PQ)).get(0);
    assertThat(formNode.get("element_id").asString())
        .isEqualTo(FeishuQuestionForm.formElementId(PQ));
    // The form's name has to be card-unique too, not only its element id.
    assertThat(formNode.get("name").asString()).isEqualTo(FeishuQuestionForm.formElementId(PQ));
  }

  @Test
  @DisplayName("a single-choice question offers its options by label, and prints the descriptions")
  void singleChoiceSelectsByLabel() {
    final var elements = elements(form().build(List.of(single()), PQ));

    // The dropdown has nowhere to put a description, so those stay in the prompt.
    final var prompt = firstWithTag(elements, "markdown").get("content").asString();
    assertThat(prompt)
        .contains("**Database**", "Which database should we use?")
        .contains("- **Postgres** — Battle-tested")
        .contains("- **SQLite** — Zero ops");

    final var select = firstWithTag(elements, "select_static");
    assertThat(select.get("name").asString()).isEqualTo(FeishuQuestionForm.selectName(PREFIX, 0));
    // Named by label so the menu can be read on its own, valued by index so the answer resolves
    // straight back to the option the model wrote.
    assertThat(select.get("options")).hasSize(2);
    assertThat(select.get("options").get(0).get("text").get("content").asString())
        .isEqualTo("Postgres");
    assertThat(select.get("options").get(0).get("value").asString()).isEqualTo("0");
    assertThat(select.get("options").get(1).get("text").get("content").asString())
        .isEqualTo("SQLite");
    assertThat(select.get("options").get(1).get("value").asString()).isEqualTo("1");
  }

  @Test
  @DisplayName("a multi-select question uses a checker per option, each carrying its own label")
  void multiSelectUsesCheckersCarryingLabels() {
    final var elements = elements(form().build(List.of(multi()), PQ));

    final var checkers = allWithTag(elements, "checker");
    assertThat(checkers).hasSize(2);
    assertThat(checkers.get(0).get("name").asString())
        .isEqualTo(FeishuQuestionForm.checkerName(PREFIX, 0, 0));
    assertThat(checkers.get(0).get("text").get("content").asString())
        .contains("**Auth**", "Sign-in");
    assertThat(checkers.get(1).get("name").asString())
        .isEqualTo(FeishuQuestionForm.checkerName(PREFIX, 0, 1));

    // Nothing to cross-reference, so the prompt does not repeat the labels.
    final var prompt = firstWithTag(elements, "markdown").get("content").asString();
    assertThat(prompt).contains("**Features**").doesNotContain("Sign-in");
    assertThat(allWithTag(elements, "select_static")).isEmpty();
  }

  @Test
  @DisplayName("the submit button carries the pending question id, and every name is card-legal")
  void submitCarriesTheIdAndNamesAreLegal() {
    final var elements = elements(form().build(List.of(single(), multi()), PQ));

    final var submit = firstWithTag(elements, "button");
    final var value = submit.get("behaviors").get(0).get("value");
    assertThat(value.get("button").asString()).isEqualTo(FeishuQuestionAnswerHandler.ACTION);
    // The whole id, not the shortened prefix: this is what finds the row when the button is
    // pressed.
    assertThat(value.get("pq").asString()).isEqualTo(PQ);

    // Feishu rejects a name that is empty, repeated, over 20 characters, or not a letter followed
    // by
    // letters, digits and underscores — and rejects the whole card with it.
    final var names =
        elements
            .valueStream()
            .map(e -> e.path("name").asString(""))
            .filter(name -> !name.isEmpty())
            .toList();
    assertThat(names).doesNotHaveDuplicates().isNotEmpty();
    assertThat(names).allSatisfy(name -> assertThat(name).matches("[A-Za-z][A-Za-z0-9_]{0,19}"));
  }

  @Test
  @DisplayName("a chosen option and ticked checkers come back as the labels the model wrote")
  void selectionsResolveToLabels() {
    final var questions = List.of(single(), multi());
    final var answers =
        form()
            .answers(
                questions,
                Map.of(
                    FeishuQuestionForm.selectName(PREFIX, 0), "1",
                    FeishuQuestionForm.checkerName(PREFIX, 1, 0), Boolean.TRUE,
                    FeishuQuestionForm.checkerName(PREFIX, 1, 1), Boolean.TRUE),
                PQ);

    assertThat(answers)
        .containsEntry("Which database should we use?", "SQLite")
        // Comma-separated, the format the tool documents for a multi-select answer.
        .containsEntry("Which features should be enabled?", "Auth, Search");
  }

  @Test
  @DisplayName("typing an answer overrides whatever was selected for that question")
  void freeTextWins() {
    final var questions = List.of(single(), multi());
    final var answers =
        form()
            .answers(
                questions,
                Map.of(
                    FeishuQuestionForm.selectName(PREFIX, 0), "0",
                    FeishuQuestionForm.otherName(PREFIX, 0), "  MySQL, actually  ",
                    FeishuQuestionForm.checkerName(PREFIX, 1, 0), Boolean.TRUE,
                    FeishuQuestionForm.otherName(PREFIX, 1), "neither"),
                PQ);

    assertThat(answers)
        .containsEntry("Which database should we use?", "MySQL, actually")
        .containsEntry("Which features should be enabled?", "neither");
  }

  @Test
  @DisplayName("a question nobody answered is left out rather than answered emptily")
  void unansweredQuestionsAreOmitted() {
    final var questions = List.of(single(), multi());
    final var select = FeishuQuestionForm.selectName(PREFIX, 0);

    // Blank free text and an unticked checker are both "not answered", and an out-of-range or
    // unparseable selection cannot name an option either.
    assertThat(
            form()
                .answers(
                    questions,
                    Map.of(
                        FeishuQuestionForm.otherName(PREFIX, 0),
                        "   ",
                        FeishuQuestionForm.checkerName(PREFIX, 1, 0),
                        Boolean.FALSE),
                    PQ))
        .isEmpty();
    assertThat(form().answers(questions, Map.of(select, "7"), PQ)).isEmpty();
    assertThat(form().answers(questions, Map.of(select, "not a number"), PQ)).isEmpty();
    assertThat(form().answers(questions, null, PQ)).isEmpty();

    // A submission naming another ask's form answers nothing here, rather than crossing the two.
    assertThat(form().answers(questions, Map.of("aaaaaaa_q0", "0"), PQ)).isEmpty();

    // Answering one of two is partial, not nothing — the caller can tell those apart.
    assertThat(form().answers(questions, Map.of(select, "0"), PQ))
        .containsExactly(Map.entry("Which database should we use?", "Postgres"));
  }

  @Test
  @DisplayName("the answered card states what was asked and chosen, under the form's own id")
  void answeredSummaryReplacesTheForm() {
    final var questions = List.of(single());
    final var summary =
        om.readTree(
            form().answered(questions, Map.of("Which database should we use?", "Postgres"), PQ));

    // The same id the form went in under, so the update replaces it rather than missing it.
    assertThat(summary.get("element_id").asString())
        .isEqualTo(FeishuQuestionForm.formElementId(PQ));
    assertThat(summary.get("tag").asString()).isEqualTo("markdown");
    assertThat(summary.get("content").asString())
        .contains("Which database should we use?")
        .contains("Postgres");
  }

  @Test
  @DisplayName("a superseded form is replaced by what was asked and why it is closed")
  void supersededSummaryReplacesTheForm() {
    // A message in the conversation closes the row behind the form, so the form itself has to go —
    // left standing it is a control that can only be pressed to be refused.
    final var summary = om.readTree(form().superseded(List.of(single(), multi()), PQ));

    assertThat(summary.get("element_id").asString())
        .isEqualTo(FeishuQuestionForm.formElementId(PQ));
    assertThat(summary.get("tag").asString()).isEqualTo("markdown");
    assertThat(summary.get("content").asString())
        .contains("Which database should we use?")
        .contains("Which features should be enabled?")
        .contains("so this form is closed");
  }

  @Test
  @DisplayName("the shipped template has every placeholder the labels can fill, and no others")
  void shippedTemplatePlaceholdersAllResolve() {
    final var built = form().build(List.of(single()), PQ);

    assertThat(built).doesNotContain("{selectHint}", "{otherHint}", "{submitText}");
    assertThat(built).contains("Pick an option", "Other — type your own answer", "Submit");
  }
}
