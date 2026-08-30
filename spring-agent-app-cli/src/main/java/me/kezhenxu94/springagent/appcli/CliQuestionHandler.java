package me.kezhenxu94.springagent.appcli;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.SynchronousQuestionHandler;
import me.kezhenxu94.springagent.core.tools.QuestionNotAnsweredException;
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
 * conversation, since the answer goes straight back as the tool's result. {@link
 * SynchronousQuestionHandler} for the same reason: the answer arrives inside the call, so the turn
 * has to carry on to act on it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CliQuestionHandler implements QuestionHandler, SynchronousQuestionHandler {

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
      // Thrown: the caller counts the channels that managed to ask, and this one did not.
      log.info("Not asking {} question(s): no interactive terminal", questions.size());
      throw new IllegalStateException("No interactive terminal to draw the question on");
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
      throw new IllegalStateException("Question has no options to choose between");
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
    sizeToTerminal(view, options.size());
    // false: not full screen. setRoot also focuses the view, the other half of it receiving keys.
    ui.setRoot(view, false);

    // The framework redraws on a resize but keeps the rectangle it was given, so the options are
    // repainted at the old width into the new screen and the row repeats across it.
    ui.getEventLoop()
        .signalEvents()
        .filter("WINCH"::equals)
        .subscribe(signal -> sizeToTerminal(view, options.size()));

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
      // TerminalUI took the signals over and does not give them back.
      console.installSignalHandlers();
    }

    final var selected = chosen.get();
    if (selected == null) {
      // Interrupted rather than answered, so say so instead of returning a label nobody picked.
      // Its own note, not a failure to ask; the questions behind this one go down with it.
      throw new QuestionNotAnsweredException(messages.get("question-dismissed"));
    }
    // Back to the option's own label: what goes to the model should be the wording it offered, not
    // the line that was drawn with the description appended to it.
    final var index = labels.indexOf(selected);
    return index >= 0 ? options.get(index).label() : selected;
  }

  /**
   * Claims exactly the rows the options need, indented to line up under the question. Not full
   * screen, which would scroll the answer the user has just read off the top.
   */
  private void sizeToTerminal(final ListView<String> view, final int options) {
    view.setRect(
        INDENT.length(), 0, console.width() - INDENT.length(), Math.min(options, console.height()));
  }

  /** The option, and its explanation when it has one, on the single line a list row gives us. */
  private static String label(final Question.Option option) {
    if (option.description() == null || option.description().isBlank()) {
      return option.label();
    }
    return option.label() + " — " + option.description();
  }
}
