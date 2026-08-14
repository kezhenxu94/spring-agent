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
 * native image. The programmatic form is the one that survives.
 */
@Configuration(proxyBeanMethods = false)
public class CliCommands {

  private static final String GROUP = "Agent";

  @Bean
  Command cliHelpCommand(final CommandRegistry commandRegistry, final CliConsole console) {
    return Command.builder()
        .name("help")
        .group(GROUP)
        .description("List the commands.")
        .execute(
            context -> {
              final var writer = context.outputWriter();
              writer.println();
              writer.println(console.dim("  Type a question to talk to the agent."));
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
  Command cliClearCommand(final CliSession session, final CliConsole console) {
    return Command.builder()
        .name("clear")
        .group(GROUP)
        .description("Forget the conversation so far and start a new one.")
        // Not the terminal's scrollback, which the user may still want to read: what this clears is
        // the chat memory the next turn would have replayed.
        .execute(
            context -> {
              session.clear();
              context.outputWriter().println(console.dim("New conversation."));
            });
  }

  @Bean
  Command cliSessionCommand(final CliSession session, final CliConsole console) {
    return Command.builder()
        .name("session")
        .group(GROUP)
        .description("Show the current conversation id.")
        .execute(
            context -> {
              final var writer = context.outputWriter();
              writer.println(console.dim("conversation  ") + session.conversationId());
              final var runId = session.activeRunId();
              writer.println(console.dim("run           ") + (runId == null ? "-" : runId));
            });
  }

  @Bean
  Command cliModelCommand(final Environment environment, final CliConsole console) {
    return Command.builder()
        .name("model")
        .group(GROUP)
        .description("Show which model the agent is talking to.")
        .execute(
            context -> {
              final var writer = context.outputWriter();
              writer.println(
                  console.dim("model   ")
                      + environment.getProperty("spring.ai.openai.chat.model", "-"));
              writer.println(
                  console.dim("base    ")
                      + environment.getProperty("spring.ai.openai.base-url", "-"));
            });
  }

  @Bean
  Command cliToolsCommand(final ApplicationContext applicationContext, final CliConsole console) {
    return Command.builder()
        .name("tools")
        .group(GROUP)
        .description("List the tool sets the agent can draw on.")
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
      final SpringAgent springAgent, final CliSession session, final CliConsole console) {
    return Command.builder()
        .name("stop")
        .group(GROUP)
        .description("Stop the run in flight.")
        // Reachable only from another terminal or a script, since the prompt is not read while a
        // run is in flight — Ctrl-C is what a user presses. It exists so the ability is not
        // Ctrl-C's alone.
        .execute(
            context -> {
              final var runId = session.activeRunId();
              final var stopped = runId != null && springAgent.cancel(runId);
              context
                  .outputWriter()
                  .println(console.dim(stopped ? "Stopping." : "Nothing running."));
            });
  }

  @Bean
  Command cliExitCommand(final CliSession session) {
    return Command.builder()
        .name("exit")
        .group(GROUP)
        .description("Leave.")
        .aliases("quit")
        .execute(
            context -> {
              session.quit();
            });
  }
}
