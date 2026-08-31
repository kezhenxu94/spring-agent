package me.kezhenxu94.springagent.appcli;

import java.util.Comparator;
import java.util.stream.Collectors;
import me.kezhenxu94.springagent.appcli.config.CliProperties;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.config.ConditionalOnUserModels;
import me.kezhenxu94.springagent.core.tools.AgentToolsProvider;
import me.kezhenxu94.springagent.core.usermodels.UserModelCommand;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
        .execute(
            context -> {
              session.clear();
              console.clearScreen();
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
      final OpenAiChatProperties chatProperties,
      final OpenAiCommonProperties connectionProperties,
      final CliConsole console,
      final CliMessages messages) {
    return Command.builder()
        .name("model")
        .group(messages.get("command-group"))
        .description(messages.get("command-model"))
        .execute(
            context -> {
              final var writer = context.outputWriter();
              writer.println(label(console, messages, "label-model") + chatProperties.getModel());
              // The chat properties override the connection ones when set, which is the order
              // Spring AI itself resolves them in.
              final var baseUrl =
                  chatProperties.getBaseUrl() == null || chatProperties.getBaseUrl().isBlank()
                      ? connectionProperties.getBaseUrl()
                      : chatProperties.getBaseUrl();
              writer.println(label(console, messages, "label-base") + baseUrl);
            });
  }

  @Bean
  Command cliToolsCommand(final AgentToolsProvider agentToolsProvider, final CliMessages messages) {
    return Command.builder()
        .name("tools")
        .group(messages.get("command-group"))
        .description(messages.get("command-tools"))
        // The tool sets a chat run is composed from, asked of the provider that composes them.
        // Not the individual @Tool methods: which of those a turn is offered is narrowed per
        // request by the tool-search advisor, so a fixed list of them would be a lie.
        .execute(
            context -> {
              final var names =
                  agentToolsProvider.resolveScenarioTools(BuiltInScenarios.CHAT).stream()
                      .map(tool -> tool.getClass().getSimpleName())
                      .sorted()
                      .collect(Collectors.joining("\n  "));
              context.outputWriter().println("  " + names);
            });
  }

  /**
   * The command line has no card to open, so {@code /config} is the list plus a name to switch to —
   * arguments being the natural thing at a terminal, where on a chat surface the same command opens
   * a form instead.
   *
   * <p>Registered only where users may choose a model at all, which is why it takes the command
   * through an {@link ObjectProvider}: with no encryption key configured there is nothing to
   * configure, and a command that could only report that is worse than no command.
   */
  @Bean
  @ConditionalOnUserModels
  Command cliConfigCommand(
      final UserModelCommand userModelCommand,
      final CliProperties properties,
      final CliMessages messages) {
    return Command.builder()
        .name("config")
        .group(messages.get("command-group"))
        .description(messages.get("command-config"))
        .execute(
            context -> {
              final var argument = String.join(" ", context.parsedInput().subCommands()).trim();
              context
                  .outputWriter()
                  .println(userModelCommand.handle(properties.userId(), argument));
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
