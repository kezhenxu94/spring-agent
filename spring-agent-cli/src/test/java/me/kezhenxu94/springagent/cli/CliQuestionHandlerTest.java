package me.kezhenxu94.springagent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question.Option;
import org.springframework.shell.jline.tui.component.view.TerminalUIBuilder;

class CliQuestionHandlerTest {

  private final CliConsole console = mock(CliConsole.class);
  private final TerminalUIBuilder terminalUIBuilder = mock(TerminalUIBuilder.class);
  private final CliMessages messages = mock(CliMessages.class);
  private final CliQuestionHandler handler =
      new CliQuestionHandler(console, messages, terminalUIBuilder);

  @Test
  void answersEveryQuestionWhenThereIsNoTerminal() {
    when(console.interactive()).thenReturn(false);
    final var questions =
        List.of(
            question("Which namespace?", "prod-a", "prod-b"),
            question("Restart now?", "yes", "no"));

    final var answers = handler.handle(questions);

    // The tool rejects a result that does not answer every question it was given, so a single note
    // would fail its validation however truthful it is.
    assertThat(answers).hasSize(2).containsKeys("Which namespace?", "Restart now?");
    assertThat(answers.values()).allSatisfy(value -> assertThat(value).startsWith("COULD NOT ASK"));
  }

  @Test
  void doesNotBlockWhenThereIsNoTerminal() {
    when(console.interactive()).thenReturn(false);

    // The point of the check: without it the handler would try to draw on a pipe and wait forever
    // for a keypress that cannot come, taking the run with it.
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> assertThat(handler.handle(List.of(question("Go?", "y", "n")))).hasSize(1));
  }

  @Test
  void saysSoWhenAQuestionOffersNothingToChoose() {
    when(console.interactive()).thenReturn(true);

    final var answers = handler.handle(List.of(new Question("Empty?", "Empty", List.of(), false)));

    // Otherwise this draws a box with no rows, which cannot be answered and cannot be left.
    assertThat(answers).hasSize(1);
    assertThat(answers.get("Empty?")).startsWith("COULD NOT ASK");
  }

  @Test
  void interruptingWithNothingOnScreenDoesNothing() {
    handler.interrupt();
  }

  @Test
  void handlesAnEmptyQuestionList() {
    assertThat(handler.handle(List.of())).isEmpty();
  }

  private static Question question(final String text, final String... options) {
    return new Question(
        text,
        text,
        List.of(options).stream()
            .map(option -> new Option(option, option + " description"))
            .toList(),
        false);
  }
}
