package me.kezhenxu94.springagent.cli;

import java.io.PrintWriter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import me.kezhenxu94.springagent.cli.config.CliProperties;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp;
import org.springframework.stereotype.Component;

/**
 * The one thing allowed to write to the terminal.
 *
 * <p>A run's output arrives on the reactor thread driving it, the spinner ticks on a scheduler, and
 * a question view takes the terminal over entirely — so every write funnels through the {@code
 * synchronized} methods here, as {@code FeishuCardUpdater} funnels every card write through one
 * sequence counter.
 *
 * <p>It also decides once whether this terminal gets colour and glyphs, so no caller has to.
 */
@Component
public class CliConsole {

  private static final int DEFAULT_WIDTH = 80;
  private static final int DEFAULT_HEIGHT = 24;

  private static final String[] SPINNER_FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
  private static final long SPINNER_INTERVAL_MS = 80;

  private final Terminal terminal;
  private final boolean styled;

  /**
   * Only ever one spinner, and only while a run has produced nothing to show yet. Single-threaded
   * because a second tick concurrent with the first would each erase the other's frame.
   */
  private final ScheduledExecutorService spinnerExecutor =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            final var thread = new Thread(runnable, "cli-spinner");
            thread.setDaemon(true);
            return thread;
          });

  private volatile Runnable interruptAction;

  private ScheduledFuture<?> spinner;
  private int spinnerWidth;

  public CliConsole(final Terminal terminal, final CliProperties properties) {
    this.terminal = terminal;
    // The property can only take styling away, never add it: on a dumb terminal, or when output is
    // a pipe, the escape sequences would be printed as literal rubbish rather than interpreted.
    this.styled = properties.color() && interactive();
    installSignalHandlers();
  }

  /** What Ctrl-C should do. Set once by {@link CliShellRunner}, which owns the run it cancels. */
  public void onInterrupt(final Runnable action) {
    this.interruptAction = action;
    installSignalHandlers();
  }

  /**
   * (Re)claims the signals this application handles.
   *
   * <p>Called again after anything that runs its own terminal loop, because {@code Terminal.handle}
   * replaces the handler rather than adding to it and Spring Shell's {@code TerminalUI} installs
   * its own without putting the previous ones back. Left alone, the first question the agent asked
   * would cost the rest of the session its Ctrl-C and its resize handling.
   */
  public void installSignalHandlers() {
    // JLine only tracks the window size while something handles the signal; without this, width()
    // keeps returning whatever the terminal was when the process started.
    terminal.handle(Terminal.Signal.WINCH, signal -> terminal.getSize());
    terminal.handle(
        Terminal.Signal.INT,
        signal -> {
          if (interruptAction != null) {
            interruptAction.run();
          }
        });
  }

  /**
   * Whether there is a real terminal on the other end. False when output is piped or redirected,
   * which is what the spinner, the redrawn todo list and the question views all have to know.
   */
  public boolean interactive() {
    return terminal.getType() != null && !Terminal.TYPE_DUMB.equals(terminal.getType());
  }

  public Terminal terminal() {
    return terminal;
  }

  /**
   * Whether colour and glyphs are on. Decided once in the constructor, so anything drawing its own
   * escape sequences — {@link CliHighlighter}, which JLine calls rather than this class — asks here
   * instead of consulting the property and the terminal again.
   */
  public boolean styled() {
    return styled;
  }

  /**
   * The terminal's current width, re-read on every call so a window resized mid-answer wraps the
   * rest of it to the new width. A terminal that cannot say how big it is reports 0, which would
   * make every layout calculation negative.
   */
  public int width() {
    final var width = terminal.getWidth();
    return width > 0 ? width : DEFAULT_WIDTH;
  }

  public int height() {
    final var height = terminal.getHeight();
    return height > 0 ? height : DEFAULT_HEIGHT;
  }

  /** Wipes the screen and puts the cursor back at the top. */
  public synchronized void clearScreen() {
    if (!interactive()) {
      return;
    }
    stopSpinnerLocked();
    terminal.puts(InfoCmp.Capability.clear_screen);
    terminal.flush();
  }

  /** Writes {@code text} as it stands, with no trailing newline of its own. */
  public synchronized void write(final String text) {
    stopSpinnerLocked();
    final PrintWriter writer = terminal.writer();
    writer.write(text);
    writer.flush();
  }

  public synchronized void writeLine(final String text) {
    write(text + "\n");
  }

  /** Dimmed, for anything the user is not meant to read closely — tool output, timings, traces. */
  public String dim(final String text) {
    return style(text, AttributedStyle.DEFAULT.faint());
  }

  public String bold(final String text) {
    return style(text, AttributedStyle.DEFAULT.bold());
  }

  public String green(final String text) {
    return style(text, AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN));
  }

  public String red(final String text) {
    return style(text, AttributedStyle.DEFAULT.foreground(AttributedStyle.RED));
  }

  public String yellow(final String text) {
    return style(text, AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW));
  }

  private String style(final String text, final AttributedStyle style) {
    if (!styled) {
      return text;
    }
    return new AttributedStringBuilder().style(style).append(text).toAnsi(terminal);
  }

  /**
   * Starts the "still working" indicator. Does nothing without a terminal to erase on: piped output
   * would otherwise collect a frame of animation per tick.
   */
  public synchronized void startSpinner(final String label) {
    if (!interactive() || spinner != null) {
      return;
    }
    final var frame = new AtomicInteger();
    spinner =
        spinnerExecutor.scheduleAtFixedRate(
            () -> tick(label, frame), 0, SPINNER_INTERVAL_MS, TimeUnit.MILLISECONDS);
  }

  public synchronized void stopSpinner() {
    stopSpinnerLocked();
  }

  private synchronized void tick(final String label, final AtomicInteger frame) {
    if (spinner == null) {
      return;
    }
    final var text =
        " " + SPINNER_FRAMES[frame.getAndIncrement() % SPINNER_FRAMES.length] + " " + label;
    final var writer = terminal.writer();
    writer.write("\r" + dim(text));
    writer.flush();
    // The visible width, not the styled one: the erase below writes that many spaces, and counting
    // the escape sequences would leave a trail of blanks across the line.
    spinnerWidth = text.length();
  }

  /**
   * Erases the spinner and stops it. Called from {@link #write} rather than left to the caller, so
   * there is no path on which output lands on top of a half-drawn frame.
   */
  private void stopSpinnerLocked() {
    if (spinner == null) {
      return;
    }
    spinner.cancel(false);
    spinner = null;
    if (spinnerWidth > 0) {
      final var writer = terminal.writer();
      writer.write("\r" + " ".repeat(spinnerWidth) + "\r");
      writer.flush();
      spinnerWidth = 0;
    }
  }
}
