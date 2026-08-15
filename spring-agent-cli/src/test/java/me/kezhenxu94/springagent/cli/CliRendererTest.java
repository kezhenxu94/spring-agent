package me.kezhenxu94.springagent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.util.List;
import me.kezhenxu94.springagent.core.agent.AgentOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos.Status;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos.TodoItem;

// Lenient because the console's styling helpers are stubbed once in setUp for every test, and most
// tests exercise only some of them.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CliRendererTest {

  @Mock CliConsole console;

  // The real English bundle, not a mock: these assert what a user sees, and a key that went missing
  // from it should fail them.
  private final CliMessages messages = CliMessagesTest.english();

  private final StringBuilder written = new StringBuilder();
  private CliRenderer renderer;

  @BeforeEach
  void setUp() {
    doAnswer(
            invocation -> {
              written.append(invocation.<String>getArgument(0));
              return null;
            })
        .when(console)
        .write(anyString());
    // Styling is the console's business and it is mocked here, so every helper returns its
    // argument: what these tests assert is the text and its layout, not the escape sequences.
    when(console.green(anyString())).thenAnswer(passThrough());
    when(console.width()).thenReturn(80);
    renderer = new CliRenderer(console, messages);
  }

  private static org.mockito.stubbing.Answer<String> passThrough() {
    return invocation -> invocation.getArgument(0);
  }

  @Test
  void writesOnlyWhatIsNewOnEachTick() {
    renderer.onContent("Hello");
    renderer.onContent("Hello there");
    renderer.onContent("Hello there world");
    renderer.onFinished(AgentOutcome.COMPLETED);

    // Once, not three times: onContent hands over the whole answer so far, so a renderer that
    // printed its argument would print "Hello" three times over.
    assertThat(written.toString()).containsOnlyOnce("Hello there world");
  }

  @Test
  void ignoresATickThatAddsNothing() {
    renderer.onContent("Same");
    final var afterFirst = written.length();
    renderer.onContent("Same");

    assertThat(written.length()).isEqualTo(afterFirst);
  }

  @Test
  void keepsTheGutterDownAWrappedParagraph() {
    when(console.width()).thenReturn(40);

    renderer.onContent("aaaa bbbb cccc dddd eeee ffff gggg hhhh iiii jjjj kkkk");
    renderer.onFinished(AgentOutcome.COMPLETED);

    final var lines = written.toString().lines().filter(line -> !line.isBlank()).toList();
    assertThat(lines).hasSizeGreaterThan(1);
    assertThat(lines).allSatisfy(line -> assertThat(line).hasSizeLessThanOrEqualTo(40));
    // The first carries the bullet; every other line is indented under it.
    assertThat(lines.subList(1, lines.size()))
        .allSatisfy(line -> assertThat(line).startsWith("  "));
  }

  @Test
  void wrapsCjkWhereThereAreNoSpacesToBreakAt() {
    when(console.width()).thenReturn(20);

    // Nine characters, eighteen columns: without wide-character handling this is one unbreakable
    // "word" that never wraps, and every character counts as one column so the limit never trips.
    renderer.onContent("向量数据库是一种数据库向量数据库是一种数据库");
    renderer.onFinished(AgentOutcome.COMPLETED);

    final var lines = written.toString().lines().filter(line -> !line.isBlank()).toList();
    assertThat(lines).hasSizeGreaterThan(1);
    assertThat(lines).allSatisfy(line -> assertThat(displayWidth(line)).isLessThanOrEqualTo(20));
  }

  private static int displayWidth(final String text) {
    var width = 0;
    for (var i = 0; i < text.length(); ) {
      final var codePoint = text.codePointAt(i);
      width += Math.max(0, org.jline.utils.WCWidth.wcwidth(codePoint));
      i += Character.charCount(codePoint);
    }
    return width;
  }

  @Test
  void doesNotBreakUpALongWord() {
    when(console.width()).thenReturn(20);
    final var path = "/a/very/long/path/that/exceeds/the/width";

    renderer.onContent("see " + path + " ok");
    renderer.onFinished(AgentOutcome.COMPLETED);

    assertThat(written.toString()).contains(path);
  }

  @Test
  void startsANewBlockAfterAToolCall() {
    when(console.dim(anyString())).thenAnswer(passThrough());
    when(console.bold(anyString())).thenAnswer(passThrough());

    renderer.onContent("Looking.");
    renderer.onToolCall("Bash", "{\"command\": \"ls\"}");
    renderer.onContent("Looking.Done.");
    renderer.onFinished(AgentOutcome.COMPLETED);

    final var output = written.toString();
    assertThat(output).contains("Bash").contains("ls");
    // The answer resumed under its own bullet rather than continuing the tool line.
    assertThat(output.indexOf("Done.")).isGreaterThan(output.indexOf("Bash"));
    assertThat(output).containsOnlyOnce("Looking.");
  }

  @Test
  void showsOnlyTheStartOfALongToolResult() {
    when(console.dim(anyString())).thenAnswer(passThrough());

    renderer.onToolResult("1\n2\n3\n4\n5\n6\n7\n8");

    assertThat(written.toString()).contains("3 more lines").doesNotContain("\n8");
  }

  @Test
  void printsTheTodoList() {
    when(console.dim(anyString())).thenAnswer(passThrough());
    when(console.bold(anyString())).thenAnswer(passThrough());

    renderer.handle(
        new Todos(
            List.of(
                new TodoItem("read the events", Status.completed, "reading the events"),
                new TodoItem("check the pods", Status.in_progress, "checking the pods"),
                new TodoItem("report", Status.pending, "reporting"))));

    final var output = written.toString();
    assertThat(output).contains("read the events");
    // An item in flight is named by what is happening, not by what will have happened.
    assertThat(output).contains("checking the pods").doesNotContain("check the pods\n");
    assertThat(output).contains("report");
  }

  @Test
  void printsTheReasonAndTheTraceOnFailure() {
    when(console.red(anyString())).thenAnswer(passThrough());
    when(console.dim(anyString())).thenAnswer(passThrough());

    renderer.onError(new IllegalStateException("the model refused"));
    renderer.onFinished(AgentOutcome.FAILED);

    assertThat(written.toString()).contains("the model refused").contains("IllegalStateException");
  }

  @Test
  void saysSoWhenTheRunWasStopped() {
    when(console.yellow(anyString())).thenAnswer(passThrough());

    renderer.onContent("half an ans");
    renderer.onFinished(AgentOutcome.CANCELLED);

    assertThat(written.toString()).contains("Stopped.");
    assertThat(renderer.outcome()).isEqualTo(AgentOutcome.CANCELLED);
  }

  @Test
  void releasesTheRunnerExactlyWhenTheRunEnds() {
    assertThat(renderer.finished().getCount()).isEqualTo(1);

    renderer.onFinished(AgentOutcome.COMPLETED);

    assertThat(renderer.finished().getCount()).isZero();
  }

  @Test
  void usesPlainMarkersWithoutATerminal() {
    when(console.interactive()).thenReturn(false);
    when(console.green(anyString())).thenAnswer(passThrough());
    final var plain = new CliRenderer(console, messages);

    plain.onContent("hello");
    plain.onFinished(AgentOutcome.COMPLETED);

    assertThat(written.toString()).contains("* hello").doesNotContain("●");
  }
}
