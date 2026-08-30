package me.kezhenxu94.springagent.appcli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.shell.core.command.Command;
import org.springframework.shell.core.command.CommandRegistry;

// Lenient because styled() is stubbed once for every test and the plain-terminal one replaces it.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CliHighlighterTest {

  @Mock CliConsole console;

  private CliHighlighter highlighter;

  @BeforeEach
  void setUp() {
    final var registry = new CommandRegistry();
    registry.registerCommand(
        Command.builder().name("clear").description("clear").execute(context -> {}));
    registry.registerCommand(
        Command.builder().name("exit").description("exit").aliases("quit").execute(context -> {}));
    when(console.styled()).thenReturn(true);
    highlighter = new CliHighlighter(registry, console);
  }

  private String highlight(final String buffer) {
    return highlighter.highlight(null, buffer).toAnsi();
  }

  @Test
  void leavesAPromptAlone() {
    // The whole point: a question is not a command, and the built-in highlighter reddened it.
    assertThat(highlight("what does this project do?")).isEqualTo("what does this project do?");
  }

  @Test
  void emboldensAKnownCommand() {
    assertThat(highlight("/clear")).contains("clear").isNotEqualTo("/clear");
  }

  @Test
  void emboldensOnlyTheNameOfACommandWithArguments() {
    assertThat(highlight("/exit now")).endsWith(" now");
  }

  @Test
  void recognisesAnAlias() {
    assertThat(highlight("/quit")).isEqualTo(highlight("/exit").replace("exit", "quit"));
  }

  @Test
  void reddensAnUnknownCommand() {
    assertThat(highlight("/nope")).isNotEqualTo("/nope").isNotEqualTo(highlight("/clear"));
  }

  @Test
  void leavesABareSlashAlone() {
    assertThat(highlight("/")).isEqualTo("/");
  }

  @Test
  void writesNoEscapesWhenColourIsOff() {
    when(console.styled()).thenReturn(false);
    assertThat(highlight("/nope")).isEqualTo("/nope");
    assertThat(highlight("/clear")).isEqualTo("/clear");
  }
}
