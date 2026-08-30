package me.kezhenxu94.springagent.appcli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import me.kezhenxu94.springagent.core.tools.QuestionNotAnsweredException;
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
  void saysItCouldNotAskWhenThereIsNoTerminal() {
    when(console.interactive()).thenReturn(false);
    final var questions =
        List.of(
            question("Which namespace?", "prod-a", "prod-b"),
            question("Restart now?", "yes", "no"));

    // Thrown rather than answered: SpringAgent counts the channels that managed to put the
    // questions somewhere, and this one did not.
    assertThatThrownBy(() -> handler.handle(questions))
        .isInstanceOf(IllegalStateException.class)
        .isNotInstanceOf(QuestionNotAnsweredException.class);
  }

  @Test
  void doesNotBlockWhenThereIsNoTerminal() {
    when(console.interactive()).thenReturn(false);

    // The point of the check: without it the handler would try to draw on a pipe and wait forever
    // for a keypress that cannot come, taking the run with it.
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThatThrownBy(() -> handler.handle(List.of(question("Go?", "y", "n"))))
                    .isInstanceOf(IllegalStateException.class));
  }

  @Test
  void saysSoWhenAQuestionOffersNothingToChoose() {
    when(console.interactive()).thenReturn(true);

    // Otherwise this draws a box with no rows, which cannot be answered and cannot be left.
    assertThatThrownBy(
            () -> handler.handle(List.of(new Question("Empty?", "Empty", List.of(), false))))
        .isInstanceOf(IllegalStateException.class)
        .isNotInstanceOf(QuestionNotAnsweredException.class);
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
