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

  /** The top and bottom border rows the box draws around the options. */
  private static final int BORDER_ROWS = 2;

  private final CliConsole console;

  /**
   * Driven directly rather than through {@code ViewComponent}, which looks like the obvious way to
   * run one view and is not: it wires the view's event loop but never calls {@link
   * TerminalUIBuilder}'s {@code TerminalUI.configure}, and {@code configure} is what calls {@code
   * View.init()} — which is where {@code ListView} registers the key bindings for the arrow keys
   * and Enter. Built through it, the box drew correctly and then ignored every key, with no way out
   * but killing the process.
   */
  private final TerminalUIBuilder terminalUIBuilder;

  /**
   * The view currently on screen, so {@link #interrupt()} can take it down. Held because the loop
   * below owns the terminal while it runs: the runner's Ctrl-C handler has nothing else to act on,
   * and without this a user who did not want to answer had no way out but killing the process.
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
    // the event this method waits on only in the NOCHECK style. Under RADIO, Space ticks a box,
    // Enter does nothing, and there is no event for "the user has decided" at all — the box just
    // sits there. So the highlighted row is the answer and Enter takes it, which is the shape a
    // coding agent's selector has anyway.
    final var view = new ListView<>(labels, ItemStyle.NOCHECK);
    view.setShowBorder(true);
    view.setTitle(question.question());

    // Above the box rather than inside it: a ListView draws its items and nothing else, and a hint
    // added as an item would be selectable.
    console.writeLine(console.dim("  ↑/↓ to move, enter to choose, ctrl-c to skip"));

    final var ui = terminalUIBuilder.build();
    // Before setRect and setRoot: it is what initialises the view, and an uninitialised one has no
    // key bindings.
    ui.configure(view);
    // The size is fixed here rather than left to the framework: the box is drawn where the cursor
    // already is, so it has to claim exactly the rows it needs — full screen would scroll the
    // answer the user has just read off the top.
    view.setRect(0, 0, console.width(), Math.min(options.size() + BORDER_ROWS, console.height()));
    // false: not full screen. setRoot also gives the view focus, which is the other half of it
    // receiving keys.
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
      // Interrupted rather than answered. Say so plainly rather than returning a label nobody
      // picked; the run usually ends here anyway, since Ctrl-C cancels it in the same breath.
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
