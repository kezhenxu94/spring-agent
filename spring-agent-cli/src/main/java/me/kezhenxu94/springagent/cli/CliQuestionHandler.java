package me.kezhenxu94.springagent.cli;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springaicommunity.agent.tools.AskUserQuestionTool.QuestionHandler;
import org.springframework.shell.jline.tui.component.ViewComponentBuilder;
import org.springframework.shell.jline.tui.component.view.control.ListView;
import org.springframework.shell.jline.tui.component.view.control.ListView.ItemStyle;
import org.springframework.shell.jline.tui.component.view.control.ListView.ListViewOpenSelectedItemEvent;
import org.springframework.stereotype.Component;

/**
 * Puts the agent's questions to the user and waits for the answers.
 *
 * <p>Blocking, which is the opposite of what {@code FeishuQuestionHandler} does — and its javadoc
 * explains why it must not: a chat user may take an hour, and a thread held that long costs a card,
 * a pool slot, and the question itself if the process restarts. None of that holds here. The user
 * is at the keyboard the run is printing to, there is one run at a time, and if they walk away the
 * session ends with the terminal. Blocking is what keeps the question, the answer and the work that
 * follows it in a single turn, instead of ending the run and asking the user to start it again.
 *
 * <p>A bean rather than per-run: unlike the Feishu handler it captures nothing about the
 * conversation, since the answer goes straight back as the tool's result.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CliQuestionHandler implements QuestionHandler {

  /**
   * Returned when there is no terminal to draw on — output is piped, or input is a file. Worded the
   * way {@code FeishuQuestionHandler} words its failure: the agent has to be told plainly that no
   * answer is coming, or it waits for one that cannot arrive.
   */
  private static final String CANNOT_ASK =
      "COULD NOT ASK. There is no interactive terminal on this session, so the question could not"
          + " be shown and no answer is coming. Carry on with what you know, and say which"
          + " assumption you made.";

  /** Leaves room for the border, the title and the hint line around the options themselves. */
  private static final int CHROME_ROWS = 5;

  private final CliConsole console;
  private final ViewComponentBuilder viewComponentBuilder;

  @Override
  public Map<String, String> handle(final List<Question> questions) {
    if (questions == null || questions.isEmpty()) {
      return Map.of();
    }
    if (!console.interactive()) {
      log.info("Not asking {} question(s): no interactive terminal", questions.size());
      return answerEach(questions, CANNOT_ASK);
    }

    final var answers = new LinkedHashMap<String, String>();
    for (final var question : questions) {
      answers.put(question.question(), ask(question));
    }
    log.info("Answered {} question(s) at the terminal", answers.size());
    return answers;
  }

  private String ask(final Question question) {
    final var options =
        question.options() == null ? List.<Question.Option>of() : question.options();
    if (options.isEmpty()) {
      // The tool's own validation should have rejected this, but a question with nothing to choose
      // between would otherwise draw an empty box the user cannot get out of.
      return CANNOT_ASK;
    }

    final var labels = options.stream().map(CliQuestionHandler::label).toList();
    final var view = new ListView<>(labels, ItemStyle.RADIO);
    view.setShowBorder(true);
    view.setTitle(question.question());
    // Height is fixed here rather than left to the component: the view is drawn inline, above the
    // prompt, so it has to claim exactly the rows it needs and no more — a full-screen box would
    // scroll the answer the user has just read off the top.
    view.setRect(0, 0, console.width(), Math.min(options.size() + CHROME_ROWS, console.height()));

    final var component = viewComponentBuilder.build(view);
    component.setUseTerminalWidth(true);

    final var chosen = new AtomicReference<String>();
    component
        .getEventLoop()
        .viewEvents(ListViewOpenSelectedItemEvent.class, view)
        .subscribe(
            event -> {
              chosen.set(String.valueOf(event.args().item()));
              component.exit();
            });
    component.runBlocking();

    final var selected = chosen.get();
    if (selected == null) {
      // The only way out of the view without choosing is a signal, and the run is about to be torn
      // down anyway; say so rather than returning a label nobody picked.
      return CANNOT_ASK;
    }
    // Back to the option's own label: what goes to the model should be the wording it offered, not
    // the line that was drawn with the description appended to it.
    final var index = labels.indexOf(selected);
    return index >= 0 ? options.get(index).label() : selected;
  }

  /** The option, and its explanation when it has one, on the single line a list row gives us. */
  private static String label(final Question.Option option) {
    if (option.description() == null || option.description().isBlank()) {
      return option.label();
    }
    return option.label() + " — " + option.description();
  }

  /**
   * The tool validates that every question it was given comes back with a value, so each gets the
   * same one rather than a single note that would fail that check.
   */
  private static Map<String, String> answerEach(
      final List<Question> questions, final String value) {
    final var answers = new LinkedHashMap<String, String>();
    for (final var question : questions) {
      answers.put(question.question(), value);
    }
    return answers;
  }
}
