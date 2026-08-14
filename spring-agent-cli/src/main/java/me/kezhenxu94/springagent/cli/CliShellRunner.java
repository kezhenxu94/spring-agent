package me.kezhenxu94.springagent.cli;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.cli.config.CliProperties;
import me.kezhenxu94.springagent.core.agent.AgentOutcome;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.AgentResponseListener;
import me.kezhenxu94.springagent.core.agent.AgentScenario;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.springframework.context.annotation.Primary;
import org.springframework.shell.core.ShellRunner;
import org.springframework.shell.core.command.CommandContext;
import org.springframework.shell.core.command.CommandParser;
import org.springframework.shell.core.command.CommandRegistry;
import org.springframework.shell.core.utils.Utils;
import org.springframework.stereotype.Component;

/**
 * The read-eval-print loop, and the reason this integration does not simply use Spring Shell's own.
 *
 * <p>A shell reads a command name and its options. What a user says to an agent is a sentence, and
 * Spring Shell has no catch-all or default command to route one to — so a plain interactive shell
 * would make every question look like {@code chat "what is failing in prod"}, quoting and all. The
 * loop below reads a line instead: anything starting with {@code /} is handed to Spring Shell's
 * parser and registry, and everything else goes to the agent.
 *
 * <p>{@link Primary} because Spring Shell's own runner bean is conditional on properties rather
 * than on a missing bean, so both exist; this is the one {@code springShellApplicationRunner} picks
 * up, and the other is never run.
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class CliShellRunner implements ShellRunner {

  private static final String PROMPT = "> ";
  private static final String COMMAND_PREFIX = "/";

  private final LineReader lineReader;
  private final Terminal terminal;
  private final CommandParser commandParser;
  private final CommandRegistry commandRegistry;
  private final SpringAgent springAgent;
  private final CliConsole console;
  private final CliSession session;
  private final CliProperties properties;
  private final CliQuestionHandler questionHandler;

  @Override
  public void run(final String[] args) {
    // Ctrl-C while the agent is working cancels that run rather than killing the process, which is
    // what a user pressing it means: the answer is going the wrong way, not the session is over.
    // At an idle prompt JLine raises UserInterruptException instead and nothing arrives here.
    terminal.handle(Terminal.Signal.INT, signal -> cancelActiveRun());

    // Spring Shell registers this one itself rather than declaring it as a conditional bean, so
    // unlike the other built-ins (see spring.shell.command.* in application.yaml) there is no
    // property that takes it away. Two commands answering to `exit` is the problem: the built-in
    // ends the shell by throwing, which this loop would report as a failed command.
    commandRegistry.unregisterCommand(Utils.QUIT_COMMAND);

    greet();
    while (!session.quitting()) {
      final String line;
      try {
        line = lineReader.readLine(PROMPT);
      } catch (UserInterruptException e) {
        // Ctrl-C with nothing running. Discards the half-typed line, as every shell does.
        continue;
      } catch (EndOfFileException e) {
        // Ctrl-D, or stdin running out because the input was piped in.
        break;
      }
      if (line == null || line.isBlank()) {
        continue;
      }
      final var input = line.strip();
      if (input.startsWith(COMMAND_PREFIX)) {
        runCommand(input.substring(COMMAND_PREFIX.length()).strip());
      } else {
        ask(input);
      }
    }
    console.writeLine("");
  }

  private void greet() {
    console.writeLine(console.dim("spring-agent · type your question, or /help for commands"));
  }

  /** Hands the line to Spring Shell, so its registry, parser and completion do the work. */
  private void runCommand(final String input) {
    if (input.isEmpty()) {
      return;
    }
    final var parsed = commandParser.parse(input);
    final var command = commandRegistry.getCommandByName(parsed.commandName());
    if (command == null) {
      console.writeLine(
          console.red("Unknown command: " + COMMAND_PREFIX + parsed.commandName())
              + console.dim("  (try " + COMMAND_PREFIX + "help)"));
      return;
    }
    try {
      command.execute(new CommandContext(parsed, commandRegistry, terminal.writer(), null));
      terminal.writer().flush();
    } catch (Exception e) {
      log.error("Command {} failed", parsed.commandName(), e);
      console.writeLine(console.red(COMMAND_PREFIX + parsed.commandName() + ": " + e.getMessage()));
    }
  }

  private void ask(final String text) {
    if (!springAgent.isAccepting()) {
      console.writeLine(console.yellow("Shutting down."));
      session.quit();
      return;
    }
    final var runId = UUID.randomUUID().toString();
    final var done = new CountDownLatch(1);
    session.runStarted(runId);
    try {
      springAgent.fire(
          AgentRequest.builder()
              .requestId(runId)
              .scenario(AgentScenario.CHAT)
              .userId(properties.userId())
              // No chat and no message to reply to: a terminal session is the whole conversation,
              // and CliRunListener attaches to a run without needing either.
              .chatId(session.conversationId())
              .chatType("cli")
              .conversationId(session.conversationId())
              .userMessage(user -> user.text(text))
              .listener(new TurnEnd(done))
              .build());
      // The prompt must not come back over a streaming answer, so this waits rather than looping.
      // fire() is fire-and-forget and reports only through listeners, hence the latch.
      done.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      session.runEnded();
    }
  }

  private void cancelActiveRun() {
    // First, because a question on screen owns the terminal and blocks the run's own thread inside
    // the tool call: cancelling alone would leave the box up with nothing behind it to end.
    questionHandler.interrupt();
    final var runId = session.activeRunId();
    if (runId == null) {
      return;
    }
    if (springAgent.cancel(runId)) {
      log.info("Cancelled run {} on interrupt", runId);
    }
  }

  /** Releases the loop when the run ends, however it ends. */
  @RequiredArgsConstructor
  private static final class TurnEnd implements AgentResponseListener {
    private final CountDownLatch done;

    @Override
    public void onFinished(final AgentOutcome outcome) {
      done.countDown();
    }
  }
}
