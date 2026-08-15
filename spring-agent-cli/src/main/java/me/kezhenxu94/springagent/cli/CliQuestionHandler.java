package me.kezhenxu94.springagent.cli;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springaicommunity.agent.tools.AskUserQuestionTool.QuestionHandler;
import org.springframework.shell.jline.tui.component.message.ShellMessageBuilder;
import org.springframework.shell.jline.tui.component.view.TerminalUI;
import org.springframework.shell.jline.tui.component.view.TerminalUIBuilder;
import org.springframework.shell.jline.tui.component.view.control.ListView;
import org.springframework.shell.jline.tui.component.view.control.ListView.ItemStyle;
import org.springframework.shell.jline.tui.component.view.control.ListView.ListViewOpenSelectedItemEvent;
import org.springframework.stereotype.Component;

/**
 * Puts the agent's questions to the user and waits for the answers.
 *
 * <p>Blocking, which is the opposite of {@code FeishuQuestionHandler} and for the reasons its
 * javadoc gives: none of them hold at a keyboard, where the user is watching the run and the
 * session ends with the terminal anyway. Blocking keeps the question, the answer and the work that
 * follows it in one turn.
 *
 * <p>A bean rather than per-run: unlike the Feishu handler it captures nothing about the
 * conversation, since the answer goes straight back as the tool's result.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CliQuestionHandler implements QuestionHandler {

  /**
   * Returned when there is no terminal to draw on. The agent has to be told plainly that no answer
   * is coming, or it waits for one that cannot arrive.
   */
  private static final String CANNOT_ASK =
      "COULD NOT ASK. There is no interactive terminal on this session, so the question could not"
          + " be shown and no answer is coming. Carry on with what you know, and say which"
          + " assumption you made.";

  /** Lines the options up under the question, matching what CliRenderer indents an answer by. */
  private static final String INDENT = "  ";

  private final CliConsole console;
  private final CliMessages messages;

  /**
   * Driven directly rather than through {@code ViewComponent}, which looks like the way to run one
   * view and is not: it never calls {@code TerminalUI.configure}, and that is what calls {@code
   * View.init()} — where {@code ListView} registers its key bindings. Through it the box draws and
   * then ignores every key.
   */
  private final TerminalUIBuilder terminalUIBuilder;

  /**
   * The view currently on screen, so {@link #interrupt()} can take it down. The loop below owns the
   * terminal while it runs, so the runner's Ctrl-C handler has nothing else to act on.
   */
  private final AtomicReference<TerminalUI> onScreen = new AtomicReference<>();

  /** Takes down the question on screen, if there is one, leaving it unanswered. */
  public void interrupt() {
    final var ui = onScreen.get();
    if (ui != null) {
      ui.getEventLoop().dispatch(ShellMessageBuilder.ofInterrupt());
    }
  }

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
    // NOCHECK, though RADIO is what a one-of-many question looks like: ListView.enter() dispatches
    // the event this method waits on only in the NOCHECK style, and under RADIO there is no event
    // for "the user has decided" at all. So the highlighted row is the answer and Enter takes it.
    final var view = new ListView<>(labels, ItemStyle.NOCHECK);
    // No border, and so no title either: the framework lays a border out by counting characters,
    // which a CJK label overruns because it prints two columns wide. Both are written here instead,
    // where the terminal wraps them itself.
    view.setShowBorder(false);

    console.writeLine("");
    console.writeLine(INDENT + console.bold(question.question()));
    console.writeLine(console.dim(INDENT + messages.get("question-hint")));

    final var ui = terminalUIBuilder.build();
    // Before setRect and setRoot: this is what initialises the view.
    ui.configure(view);
    // Drawn where the cursor already is, so it claims exactly the rows it needs — full screen
    // would scroll the answer the user has just read off the top.
    view.setRect(
        INDENT.length(),
        0,
        console.width() - INDENT.length(),
        Math.min(options.size(), console.height()));
    // false: not full screen. setRoot also focuses the view, the other half of it receiving keys.
    ui.setRoot(view, false);

    final var chosen = new AtomicReference<String>();
    ui.getEventLoop()
        .viewEvents(ListViewOpenSelectedItemEvent.class, view)
        .subscribe(
            event -> {
              chosen.set(String.valueOf(event.args().item()));
              // The only way to end run() below; the loop has no notion of a view being finished.
              ui.getEventLoop().dispatch(ShellMessageBuilder.ofInterrupt());
            });
    onScreen.set(ui);
    try {
      ui.run();
    } finally {
      onScreen.set(null);
    }

    final var selected = chosen.get();
    if (selected == null) {
      // Interrupted rather than answered, so say so instead of returning a label nobody picked.
      return "NOT ANSWERED. The user dismissed the question without choosing. Do not ask again and"
          + " do not guess what they would have said: stop here and end your turn.";
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
