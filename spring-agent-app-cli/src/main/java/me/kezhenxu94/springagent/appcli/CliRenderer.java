package me.kezhenxu94.springagent.appcli;

import com.google.common.base.Strings;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CountDownLatch;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentOutcome;
import me.kezhenxu94.springagent.core.agent.AgentResponseListener;
import me.kezhenxu94.springagent.core.agent.AgentResponseListener.SubagentEvent;
import me.kezhenxu94.springagent.core.tools.ToolContextKey;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import org.jline.utils.WCWidth;
import org.springaicommunity.agent.tools.TodoWriteTool.TodoEventHandler;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos;
import org.springframework.ai.chat.metadata.Usage;

/**
 * Shows one agent run in the terminal. The counterpart of {@code FeishuCardUpdater}: per run rather
 * than a bean, because everything it tracks — how much of the answer has been printed, whether a
 * bullet is owed — belongs to a single run.
 *
 * <p>The shape follows Claude Code: a marker in the left gutter starts each thing the agent says,
 * its content is indented under it, and a tool call is a smaller indented block in between.
 */
@Slf4j
public class CliRenderer implements AgentResponseListener, TodoEventHandler {

  /**
   * How the tool-call interceptor finds the renderer for the run it is intercepting. The same
   * device as {@code FeishuCardUpdater.TOOL_CONTEXT_KEY}: a tool call knows its {@code ToolContext}
   * and nothing else about the run it belongs to.
   */
  public static final ToolContextKey<CliRenderer> TOOL_CONTEXT_KEY =
      new ToolContexts.Key<>("CliRenderer", CliRenderer.class);

  private static final int MAX_TOOL_INPUT = 120;
  private static final int MAX_TOOL_RESULT_LINES = 5;

  /** Lines up with the bullet and its trailing space, so content sits in one column. */
  private static final String INDENT = "  ";

  private final CliConsole console;
  private final CliMessages messages;
  private final boolean glyphs;

  /** Released by {@link #onFinished}, so the prompt does not come back mid-answer. */
  private final CountDownLatch finished = new CountDownLatch(1);

  /**
   * How much of the answer has already reached the terminal. {@code onContent} hands over the whole
   * response so far rather than the latest delta (see {@code SpringAgent}, which accumulates into a
   * {@code StringBuilder}), so without this the answer would be reprinted on every tick.
   */
  private int printed;

  /** Set when something else has written since, so the next content chunk starts its own bullet. */
  private boolean bulletOwed = true;

  /** Whether the cursor sits at the start of a line, so blocks do not run into each other. */
  private boolean atLineStart = true;

  /** How far along the current line the answer has got, carried between chunks by {@link #wrap}. */
  private int column;

  /** The word currently being typed, held until its end arrives. See {@link #wrap}. */
  private final StringBuilder word = new StringBuilder();

  private volatile AgentOutcome outcome;

  public CliRenderer(final CliConsole console, final CliMessages messages) {
    this.console = console;
    this.messages = messages;
    this.glyphs = console.interactive();
  }

  public CountDownLatch finished() {
    return finished;
  }

  public AgentOutcome outcome() {
    return outcome;
  }

  @Override
  public void onSubscribe() {
    console.startSpinner(messages.get("thinking"));
  }

  @Override
  public void onContent(final String contentSoFar) {
    if (contentSoFar == null || contentSoFar.length() <= printed) {
      return;
    }
    final var chunk = contentSoFar.substring(printed);
    printed = contentSoFar.length();
    if (bulletOwed) {
      newBlock();
      write(console.green(marker("●", "*")) + " ");
      column = INDENT.length();
      bulletOwed = false;
    }
    write(wrap(chunk));
  }

  /**
   * Indents continuation lines under the bullet and breaks the ones that would run past the right
   * edge, so the gutter holds all the way down a paragraph. The terminal's own wrapping knows
   * nothing about the indent and would return every wrapped line to column zero.
   *
   * <p>Text arrives a few characters at a time but a break needs the whole of the next word, so
   * {@link #word} holds it until its end arrives — output lags the model by at most one word — and
   * {@link #column} survives between chunks so a word split across two is measured against the line
   * it will land on.
   */
  private String wrap(final String chunk) {
    final var out = new StringBuilder(chunk.length() + 8);
    for (var i = 0; i < chunk.length(); ) {
      final var codePoint = chunk.codePointAt(i);
      i += Character.charCount(codePoint);
      if (codePoint == '\n') {
        place(out);
        out.append('\n').append(INDENT);
        column = INDENT.length();
      } else if (codePoint == ' ') {
        place(out);
        // Not at the start of a line: a break has just consumed the space that would go here, and
        // leading spaces would push the text out of the column the indent set.
        if (column > INDENT.length()) {
          out.append(' ');
          column++;
        }
      } else if (WCWidth.wcwidth(codePoint) > 1) {
        // A wide character is a unit of its own: Chinese and Japanese are written without spaces,
        // so waiting for one would make a whole paragraph a single unbreakable word.
        place(out);
        word.appendCodePoint(codePoint);
        place(out);
      } else {
        word.appendCodePoint(codePoint);
      }
    }
    return out.toString();
  }

  /** Writes the buffered word, moving to the next line first if it would not fit on this one. */
  private void place(final StringBuilder out) {
    if (word.isEmpty()) {
      return;
    }
    final var limit = console.width() - 1;
    final var wordWidth = displayWidth(word);
    // Only when the line already has something on it, so a word wider than the terminal is left
    // over-long rather than broken up: a path or a URL is what tends to be that long, and it is
    // what a user copies whole.
    if (column > INDENT.length() && column + 1 + wordWidth > limit) {
      // Take back the previous word's trailing space rather than wrap it onto the next line.
      if (!out.isEmpty() && out.charAt(out.length() - 1) == ' ') {
        out.setLength(out.length() - 1);
      }
      out.append('\n').append(INDENT);
      column = INDENT.length();
    }
    out.append(word);
    column += wordWidth;
    word.setLength(0);
  }

  /**
   * How many columns {@code text} occupies, which is not how many characters it has: a CJK
   * character takes two and a combining mark none. The agent answers in whatever language it was
   * asked in, so the difference is not an edge case.
   */
  private static int displayWidth(final CharSequence text) {
    var width = 0;
    for (var i = 0; i < text.length(); ) {
      final var codePoint = Character.codePointAt(text, i);
      width += Math.max(0, WCWidth.wcwidth(codePoint));
      i += Character.charCount(codePoint);
    }
    return width;
  }

  /** Called by {@link CliToolCallInterceptor} as a tool starts. */
  public void onToolCall(final String toolName, final String toolInput) {
    newBlock();
    write(
        "  "
            + console.dim(marker("⏿", ">") + " ")
            + console.bold(toolName)
            + console.dim("(" + truncate(oneLine(toolInput), MAX_TOOL_INPUT) + ")")
            + "\n");
    bulletOwed = true;
  }

  /**
   * Called by {@link CliToolCallInterceptor} once a tool has returned. Only the first few lines: a
   * shell command or a file read can return thousands, and the agent is about to say what they
   * meant anyway.
   */
  public void onToolResult(final String toolResult) {
    if (toolResult == null || toolResult.isBlank()) {
      return;
    }
    final var lines = toolResult.strip().lines().toList();
    for (final var line : lines.subList(0, Math.min(lines.size(), MAX_TOOL_RESULT_LINES))) {
      write("    " + console.dim(truncate(line, Math.max(8, console.width() - 6))) + "\n");
    }
    if (lines.size() > MAX_TOOL_RESULT_LINES) {
      write(
          "    "
              + console.dim(messages.get("more-lines", lines.size() - MAX_TOOL_RESULT_LINES))
              + "\n");
    }
    bulletOwed = true;
  }

  @Override
  public void handle(final Todos todos) {
    if (todos == null || todos.todos() == null || todos.todos().isEmpty()) {
      return;
    }
    newBlock();
    for (final var item : todos.todos()) {
      write("  " + formatTodoItem(item) + "\n");
    }
    bulletOwed = true;
  }

  private String formatTodoItem(final Todos.TodoItem item) {
    return switch (item.status()) {
      case completed -> console.dim(marker("☒", "[x]") + " " + item.content());
      case in_progress -> console.bold(marker("☐", "[>]") + " " + item.activeForm());
      case pending -> console.dim(marker("☐", "[ ]") + " " + item.content());
    };
  }

  /**
   * A subagent of this run starting or ending. One line each way, in the tool-call column. What it
   * says as it goes is deliberately not shown: it comes back as the result of the call that waited
   * for it, and two answers written into one gutter — several, with several subagents — would be
   * unreadable. The lines are here to account for the time the run spends saying nothing.
   */
  @Override
  public void onSubagent(final SubagentEvent event) {
    // Only the two ends of it: what it says as it goes comes back as the result of the call that
    // waited for it, and what it spends is already in this run's own token count.
    if (event.said() || event.spent()) {
      return;
    }
    newBlock();
    final var label =
        Strings.isNullOrEmpty(event.description()) ? event.subagentId() : event.description();
    write("  " + console.dim(marker("⏿", ">") + " " + messages.get(key(event), label)) + "\n");
    bulletOwed = true;
  }

  /** One key per way a subagent can end, so the word for it is translated rather than passed in. */
  private static String key(final SubagentEvent event) {
    if (event.outcome() == null) {
      return "subagent-started";
    }
    return switch (event.outcome()) {
      case COMPLETED -> "subagent-completed";
      case CANCELLED -> "subagent-cancelled";
      case FAILED -> "subagent-failed";
    };
  }

  @Override
  public void onError(final Throwable error) {
    final var message =
        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    newBlock();
    write(console.red(marker("●", "!") + " " + message) + "\n");
    // Dimmed and indented rather than left out: the message alone is rarely enough to act on, and
    // the user has to know the log file exists before its copy is any use to them.
    final var trace = new StringWriter();
    error.printStackTrace(new PrintWriter(trace));
    write(console.dim(trace.toString().strip().indent(4).stripTrailing()) + "\n");
    bulletOwed = true;
  }

  @Override
  public void onUsage(final String model, final Usage usage) {
    log.info("Usage: model={}, tokens={}", model, usage == null ? null : usage.getTotalTokens());
  }

  @Override
  public void onFinished(final AgentOutcome outcome) {
    this.outcome = outcome;
    console.stopSpinner();
    // The last word of the answer has no space or newline after it to push it out.
    flushWord();
    // Only the ending that is not self-evident gets said out loud. A completed run has just printed
    // its answer, and a failed one has had onError print the reason; announcing either under it
    // would be noise on every turn.
    if (outcome == AgentOutcome.CANCELLED) {
      newBlock();
      write(console.yellow(marker("●", "*") + " " + messages.get("stopped")) + "\n");
    } else if (!atLineStart) {
      write("\n");
    }
    // A blank line between the answer and the next prompt, so a conversation reads as turns rather
    // than as one block of text.
    write("\n");
    finished.countDown();
  }

  /** Ends whatever was being written and leaves a blank line before the next block. */
  private void newBlock() {
    console.stopSpinner();
    flushWord();
    if (!atLineStart) {
      write("\n");
    }
    write("\n");
  }

  /**
   * Writes out a word still held back, for the paths that end the answer rather than continue it.
   */
  private void flushWord() {
    if (word.isEmpty()) {
      return;
    }
    final var out = new StringBuilder();
    place(out);
    write(out.toString());
  }

  private void write(final String text) {
    if (text.isEmpty()) {
      return;
    }
    console.write(text);
    atLineStart = text.endsWith("\n");
  }

  private String marker(final String glyph, final String ascii) {
    return glyphs ? glyph : ascii;
  }

  private static String oneLine(final String text) {
    return text == null ? "" : text.replaceAll("\\s+", " ").strip();
  }

  private static String truncate(final String text, final int max) {
    if (text == null) {
      return "";
    }
    if (max <= 1 || text.length() <= max) {
      return text;
    }
    return text.substring(0, max - 1) + "…";
  }
}
