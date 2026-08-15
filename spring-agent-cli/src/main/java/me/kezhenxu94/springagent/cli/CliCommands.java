package me.kezhenxu94.springagent.cli;

import java.util.Comparator;
import java.util.stream.Collectors;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.tools.AgentTool;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.shell.core.command.Command;
import org.springframework.shell.core.command.CommandRegistry;

/**
 * The commands a line beginning with {@code /} can name.
 *
 * <p>Declared as {@code Command} beans rather than with {@code @Command} methods: Spring Shell 4
 * does not support annotation-based registration under GraalVM, and this module is built as a
 * native image.
 */
@Configuration(proxyBeanMethods = false)
public class CliCommands {

  @Bean
  Command cliHelpCommand(
      final CommandRegistry commandRegistry, final CliConsole console, final CliMessages messages) {
    return Command.builder()
        .name("help")
        .group(messages.get("command-group"))
        .description(messages.get("command-help"))
        .execute(
            context -> {
              final var writer = context.outputWriter();
              writer.println();
              writer.println(console.dim("  " + messages.get("command-help-hint")));
              writer.println();
              commandRegistry.getCommands().stream()
                  .filter(command -> !command.isHidden())
                  .sorted(Comparator.comparing(Command::getName))
                  .forEach(
                      command ->
                          writer.printf(
                              "  %-12s %s%n",
                              "/" + command.getName(), console.dim(command.getDescription())));
              writer.println();
            });
  }

  @Bean
  Command cliClearCommand(
      final CliSession session, final CliConsole console, final CliMessages messages) {
    return Command.builder()
        .name("clear")
        .group(messages.get("command-group"))
        .description(messages.get("command-clear"))
        // Not the terminal's scrollback, which the user may still want to read: what this clears is
        // the chat memory the next turn would have replayed.
        .execute(
            context -> {
              session.clear();
              context.outputWriter().println(console.dim(messages.get("new-conversation")));
            });
  }

  @Bean
  Command cliSessionCommand(
      final CliSession session, final CliConsole console, final CliMessages messages) {
    return Command.builder()
        .name("session")
        .group(messages.get("command-group"))
        .description(messages.get("command-session"))
        .execute(
            context -> {
              final var writer = context.outputWriter();
              final var runId = session.activeRunId();
              writer.println(
                  label(console, messages, "label-conversation") + session.conversationId());
              writer.println(label(console, messages, "label-run") + (runId == null ? "-" : runId));
            });
  }

  @Bean
  Command cliModelCommand(
      final Environment environment, final CliConsole console, final CliMessages messages) {
    return Command.builder()
        .name("model")
        .group(messages.get("command-group"))
        .description(messages.get("command-model"))
        .execute(
            context -> {
              final var writer = context.outputWriter();
              writer.println(
                  label(console, messages, "label-model")
                      + environment.getProperty("spring.ai.openai.chat.model", "-"));
              writer.println(
                  label(console, messages, "label-base")
                      + environment.getProperty("spring.ai.openai.base-url", "-"));
            });
  }

  @Bean
  Command cliToolsCommand(final ApplicationContext applicationContext, final CliMessages messages) {
    return Command.builder()
        .name("tools")
        .group(messages.get("command-group"))
        .description(messages.get("command-tools"))
        // The bean names, not the individual @Tool methods: which of those a turn is offered is
        // decided per request by the tool-search advisor, so a fixed list here would be a lie.
        .execute(
            context -> {
              final var names =
                  applicationContext.getBeansWithAnnotation(AgentTool.class).keySet().stream()
                      .sorted()
                      .collect(Collectors.joining("\n  "));
              context.outputWriter().println("  " + names);
            });
  }

  @Bean
  Command cliStopCommand(
      final SpringAgent springAgent,
      final CliSession session,
      final CliConsole console,
      final CliMessages messages) {
    return Command.builder()
        .name("stop")
        .group(messages.get("command-group"))
        .description(messages.get("command-stop"))
        // Reachable only from another terminal or a script, since the prompt is not read while a
        // run is in flight — Ctrl-C is what a user presses.
        .execute(
            context -> {
              final var runId = session.activeRunId();
              final var stopped = runId != null && springAgent.cancel(runId);
              context
                  .outputWriter()
                  .println(console.dim(messages.get(stopped ? "stopping" : "nothing-running")));
            });
  }

  @Bean
  Command cliExitCommand(final CliSession session, final CliMessages messages) {
    return Command.builder()
        .name("exit")
        .group(messages.get("command-group"))
        .description(messages.get("command-exit"))
        .aliases("quit")
        .execute(
            context -> {
              session.quit();
            });
  }

  /** A label padded to a common width, so the values under it line up. */
  private static String label(
      final CliConsole console, final CliMessages messages, final String key) {
    return console.dim(String.format("%-14s", messages.get(key)));
  }
}
