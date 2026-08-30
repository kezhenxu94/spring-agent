package me.kezhenxu94.springagent.appcli;

import org.jline.reader.LineReader;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.springframework.context.annotation.Primary;
import org.springframework.shell.core.command.CommandRegistry;
import org.springframework.shell.jline.CommandHighlighter;
import org.springframework.stereotype.Component;

/**
 * Colours the line being typed, and the reason Spring Shell's own highlighter cannot.
 *
 * <p>Its highlighter reddens any line that does not begin with a registered command name — sound
 * for a shell, where that is all a line can be. Here most lines are questions for the agent, so
 * every prompt was drawn as an error while it was typed.
 *
 * <p>This follows the split {@link CliShellRunner} reads the line by: a line starting with {@code
 * /} is a command, bold when the registry knows it and red when it does not, and anything else is a
 * prompt and left as the user typed it.
 *
 * <p>{@link Primary} because Spring Shell's {@code commandHighlighter} bean is unconditional, so
 * both exist and the {@code lineReader} bean asks for the type rather than the name.
 */
@Primary
@Component
public class CliHighlighter extends CommandHighlighter {

  private static final String COMMAND_PREFIX = "/";

  private final CommandRegistry commandRegistry;
  private final CliConsole console;

  public CliHighlighter(final CommandRegistry commandRegistry, final CliConsole console) {
    super(commandRegistry);
    this.commandRegistry = commandRegistry;
    this.console = console;
  }

  @Override
  public AttributedString highlight(final LineReader reader, final String buffer) {
    if (!console.styled() || !buffer.startsWith(COMMAND_PREFIX)) {
      return new AttributedString(buffer);
    }
    final var name = commandName(buffer);
    if (name.isEmpty()) {
      return new AttributedString(buffer);
    }
    if (commandRegistry.getCommandByName(name) == null) {
      return new AttributedString(buffer, AttributedStyle.DEFAULT.foreground(AttributedStyle.RED));
    }
    final var command = COMMAND_PREFIX + name;
    return new AttributedStringBuilder(buffer.length())
        .append(command, AttributedStyle.BOLD)
        .append(buffer.substring(command.length()))
        .toAttributedString();
  }

  /** The word after the {@code /}, which is what the registry is keyed by. */
  private static String commandName(final String buffer) {
    final var rest = buffer.substring(COMMAND_PREFIX.length());
    for (var i = 0; i < rest.length(); i++) {
      if (Character.isWhitespace(rest.charAt(i))) {
        return rest.substring(0, i);
      }
    }
    return rest;
  }
}
