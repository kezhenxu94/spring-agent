package me.kezhenxu94.springagent.integration.telegram.handler;

import com.google.common.base.Strings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.InlineKeyboardButton;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.InlineKeyboardMarkup;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramMessages;
import me.kezhenxu94.springagent.integration.telegram.format.TelegramText;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springframework.stereotype.Component;

/**
 * The agent's questions as something a person can answer from the message itself.
 *
 * <p><b>Buttons and nothing else, because a Telegram message has nothing else.</b> There are no
 * text inputs on a message, so an option is a button and an answer in words is an ordinary message
 * — which is what it already is on every surface here: a message arriving while a question is open
 * supersedes it and is read by the next run along with the conversation that asked.
 *
 * <p><b>Where one press is the whole answer — one question, one choice — pressing answers.</b>
 * Anywhere else a press marks the option and Submit sends what is marked. The marks live on the
 * keyboard itself, which Telegram hands back with every press, so a form half filled in survives a
 * restart, a second replica and a day's wait without anything here remembering it.
 *
 * <p>A button carries indexes rather than the option's text, which would not fit in 64 bytes; the
 * text is read back from the stored questions, which is also what makes a press naming an option
 * that was never offered fail rather than become an answer.
 */
@RequiredArgsConstructor
@Component
public class TelegramQuestionForm {

  /** Telegram shows a long button cut short anyway; past this it is unreadable on a phone. */
  private static final int BUTTON_TEXT = 60;

  private final TelegramMessages messages;

  /** A press of an option: which ask, which question in it, which option. */
  public record Press(String pendingId, int question, int option) {}

  /** A form that is on a message and waiting to be answered. */
  public record Pending(String pendingId, List<Question> questions) {}

  /** Whether one press answers the whole form. */
  public static boolean answeredByOnePress(final List<Question> questions) {
    return questions.size() == 1
        && !questions.getFirst().multiSelect()
        && questions.getFirst().options() != null
        && !questions.getFirst().options().isEmpty();
  }

  /** The questions as text, to go under whatever the run has already said. */
  public TelegramText text(final List<Question> questions) {
    final var out = TelegramText.builder();
    for (var i = 0; i < questions.size(); i++) {
      final var question = questions.get(i);
      out.text("\n\n");
      final var number = questions.size() > 1 ? (i + 1) + ". " : "";
      out.styled("bold", number + Strings.nullToEmpty(question.question()));
      if (question.options() == null) {
        continue;
      }
      for (final var option : question.options()) {
        if (Strings.isNullOrEmpty(option.description())) {
          continue;
        }
        out.text("\n• ").styled("italic", option.label()).text(" — " + option.description());
      }
    }
    out.text("\n\n")
        .styled(
            "italic",
            messages.get(
                answeredByOnePress(questions) ? "question-hint-one-press" : "question-hint"));
    return out.build();
  }

  /** The buttons, nothing marked yet. */
  public InlineKeyboardMarkup keyboard(final List<Question> questions, final String pendingId) {
    final var rows = new ArrayList<List<InlineKeyboardButton>>();
    for (var i = 0; i < questions.size(); i++) {
      final var options = questions.get(i).options();
      if (options == null) {
        continue;
      }
      final var prefix = questions.size() > 1 ? (i + 1) + ". " : "";
      for (var j = 0; j < options.size(); j++) {
        rows.add(
            List.of(
                TelegramKeyboards.button(
                    clamp(prefix + options.get(j).label()), data(pendingId, i, j))));
      }
    }
    if (!answeredByOnePress(questions)) {
      rows.add(
          List.of(
              TelegramKeyboards.button(
                  messages.get("question-submit"), TelegramKeyboards.SUBMIT + pendingId)));
    }
    return TelegramKeyboards.rows(rows);
  }

  static String data(final String pendingId, final int question, final int option) {
    return TelegramKeyboards.ANSWER + pendingId + ":" + question + ":" + option;
  }

  /** The press {@code data} describes, or empty where it is not one of these. */
  public static Optional<Press> press(final String data) {
    if (data == null || !data.startsWith(TelegramKeyboards.ANSWER)) {
      return Optional.empty();
    }
    final var parts = data.substring(TelegramKeyboards.ANSWER.length()).split(":");
    if (parts.length != 3) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new Press(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  /** Whether {@code press} names an option the questions actually offered. */
  public static boolean offered(final List<Question> questions, final Press press) {
    return press.question() >= 0
        && press.question() < questions.size()
        && questions.get(press.question()).options() != null
        && press.option() >= 0
        && press.option() < questions.get(press.question()).options().size();
  }

  /** The answer a single press is, for a form {@link #answeredByOnePress answered by one}. */
  public Map<String, String> answer(final List<Question> questions, final Press press) {
    final var question = questions.get(press.question());
    return Map.of(
        headerOf(question, press.question()), question.options().get(press.option()).label());
  }

  /**
   * {@code current} with {@code press} marked — and, on a question taking one answer, every other
   * option of it unmarked. Pressing a marked option unmarks it.
   */
  public InlineKeyboardMarkup toggle(
      final InlineKeyboardMarkup current, final List<Question> questions, final Press press) {
    final var pressed = data(press.pendingId(), press.question(), press.option());
    final var sameQuestion =
        TelegramKeyboards.ANSWER + press.pendingId() + ":" + press.question() + ":";
    final var single = !questions.get(press.question()).multiSelect();
    final var rows = new ArrayList<List<InlineKeyboardButton>>();
    for (final var row : current.inlineKeyboard()) {
      final var next = new ArrayList<InlineKeyboardButton>();
      for (final var button : row) {
        final var data = Strings.nullToEmpty(button.callbackData());
        final var marked = button.text().startsWith(TelegramKeyboards.CHOSEN);
        final var bare =
            marked ? button.text().substring(TelegramKeyboards.CHOSEN.length()) : button.text();
        final boolean mark;
        if (data.equals(pressed)) {
          mark = !marked;
        } else if (single && data.startsWith(sameQuestion)) {
          mark = false;
        } else {
          mark = marked;
        }
        next.add(TelegramKeyboards.button(mark ? TelegramKeyboards.CHOSEN + bare : bare, data));
      }
      rows.add(next);
    }
    return TelegramKeyboards.rows(rows);
  }

  /**
   * What the marks on {@code current} answer, keyed the way the tool expects. The option's text is
   * taken from the questions rather than from the button, which a client could have rewritten.
   */
  public Map<String, String> answers(
      final List<Question> questions, final String pendingId, final InlineKeyboardMarkup current) {
    final var chosen = new LinkedHashMap<Integer, List<String>>();
    if (current != null && current.inlineKeyboard() != null) {
      for (final var row : current.inlineKeyboard()) {
        for (final var button : row) {
          if (!button.text().startsWith(TelegramKeyboards.CHOSEN)) {
            continue;
          }
          press(button.callbackData())
              .filter(press -> press.pendingId().equals(pendingId))
              .filter(press -> offered(questions, press))
              .ifPresent(
                  press ->
                      chosen
                          .computeIfAbsent(press.question(), ignored -> new ArrayList<>())
                          .add(
                              questions
                                  .get(press.question())
                                  .options()
                                  .get(press.option())
                                  .label()));
        }
      }
    }
    final var answers = new LinkedHashMap<String, String>();
    chosen.forEach(
        (index, labels) ->
            answers.put(headerOf(questions.get(index), index), String.join(", ", labels)));
    return answers;
  }

  /** What the tool keys an answer by — see {@code SlackQuestionForm#headerOf}. */
  public static String headerOf(final Question question, final int index) {
    return Strings.isNullOrEmpty(question.header())
        ? "question" + (index + 1)
        : question.header().toLowerCase(Locale.ROOT);
  }

  private static String clamp(final String text) {
    return text.length() <= BUTTON_TEXT ? text : text.substring(0, BUTTON_TEXT - 1) + "…";
  }
}
