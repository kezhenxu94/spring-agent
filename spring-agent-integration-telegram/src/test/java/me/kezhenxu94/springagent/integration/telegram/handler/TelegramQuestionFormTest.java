package me.kezhenxu94.springagent.integration.telegram.handler;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.InlineKeyboardButton;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.InlineKeyboardMarkup;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramMessages;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;

/**
 * That what the form draws is what a press reads back — the failure nothing at runtime reports,
 * since a mismatch is an empty answer rather than an error.
 */
class TelegramQuestionFormTest {

  private static final String PENDING = "0123456789abcdef0123456789abcdef";

  private final TelegramQuestionForm form =
      new TelegramQuestionForm(
          new TelegramMessages(
              new TelegramProperties(
                  "1:t", null, Locale.ENGLISH, null, null, null, null, null, null)));

  private static Question question(
      final String header, final boolean multi, final String... options) {
    return new Question(
        "Which one?",
        header,
        Arrays.stream(options).map(label -> new Question.Option(label, "why " + label)).toList(),
        multi);
  }

  private static InlineKeyboardButton find(
      final InlineKeyboardMarkup keyboard, final String label) {
    return keyboard.inlineKeyboard().stream()
        .flatMap(List::stream)
        .filter(button -> button.text().endsWith(label))
        .findFirst()
        .orElseThrow();
  }

  @Test
  @DisplayName("one question with one answer is answered by one press, with no Submit button")
  void shouldAnswerOnOnePress() {
    final var questions = List.of(question("Backend", false, "Postgres", "SQLite"));

    final var keyboard = form.keyboard(questions, PENDING);
    final var press =
        TelegramQuestionForm.press(find(keyboard, "SQLite").callbackData()).orElseThrow();

    assertThat(TelegramQuestionForm.answeredByOnePress(questions)).isTrue();
    assertThat(keyboard.inlineKeyboard())
        .flatMap(row -> row)
        .noneMatch(b -> b.callbackData().startsWith(TelegramKeyboards.SUBMIT));
    assertThat(form.answer(questions, press)).containsEntry("backend", "SQLite");
  }

  @Test
  @DisplayName("marks made on the keyboard are what Submit reads, one answer per question")
  void shouldReadMarksBack() {
    final var questions =
        List.of(
            question("db", false, "Postgres", "SQLite"), question("tools", true, "Bash", "Web"));
    var keyboard = form.keyboard(questions, PENDING);

    for (final var label : List.of("Postgres", "SQLite", "Bash", "Web")) {
      final var press =
          TelegramQuestionForm.press(find(keyboard, label).callbackData()).orElseThrow();
      keyboard = form.toggle(keyboard, questions, press);
    }

    // Choosing SQLite unmarked Postgres, since that question takes one answer; both tools stay.
    assertThat(form.answers(questions, PENDING, keyboard))
        .containsEntry("db", "SQLite")
        .containsEntry("tools", "Bash, Web");
  }

  @Test
  @DisplayName("a mark on a button naming an option never offered is not an answer")
  void shouldIgnoreForgedPresses() {
    final var questions = List.of(question("db", false, "Postgres"), question("x", false, "A"));
    final var forged =
        new InlineKeyboardMarkup(
            List.of(
                List.of(
                    new InlineKeyboardButton(
                        TelegramKeyboards.CHOSEN + "rm -rf",
                        TelegramQuestionForm.data(PENDING, 0, 9)))));

    assertThat(form.answers(questions, PENDING, forged)).isEmpty();
  }

  @Test
  @DisplayName("no button carries more than Telegram's 64 bytes")
  void shouldFitTelegramsLimit() {
    final var options = new String[12];
    Arrays.setAll(options, i -> "an option with a fairly long label number " + i);
    final var keyboard =
        form.keyboard(
            List.of(question("q", false, options), question("r", true, options)), PENDING);

    assertThat(keyboard.inlineKeyboard())
        .flatMap(row -> row)
        .allSatisfy(
            b ->
                assertThat(b.callbackData().getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(64));
  }
}
