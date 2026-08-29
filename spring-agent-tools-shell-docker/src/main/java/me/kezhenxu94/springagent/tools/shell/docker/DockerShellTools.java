package me.kezhenxu94.springagent.tools.shell.docker;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.AgentTool;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;

/**
 * The agent's shell, run in the user's sandbox container.
 *
 * <p>Tool names, descriptions and the background-shell bookkeeping under {@code /tmp/.bg} are the
 * Kubernetes module's, unchanged: the model sees one shell whichever backend a deployment picked,
 * and a conversation moved between them behaves the same.
 */
@Slf4j
@AgentTool
@RequiredArgsConstructor
public class DockerShellTools implements AutoCloseable {

  private final UserContainerManager userContainerManager;
  private final DockerShellProperties properties;

  /**
   * Virtual threads, one per exec: {@code execInContainer} blocks until the command finishes, and
   * the only way to impose a timeout on it is to wait on it from somewhere else.
   */
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  // @formatter:off
  @Tool(
      name = "Bash",
      description =
"""
Execute a bash command for terminal operations like npm, docker, make, mvn, python.

Usage notes:
- The command argument is required.
- Optional timeout in milliseconds (max 600000ms / 10 minutes). Default: 120000ms (2 minutes).
- Use run_in_background for long-running commands.
- Quote file paths with spaces in double quotes.
- Chain dependent commands with &&. Use ; if earlier failures are acceptable.
- Prefer absolute paths over cd.
""")
  public String bash(
      @ToolParam(description = "The command to execute") final String command,
      @ToolParam(description = "Optional timeout in milliseconds (max 600000)", required = false)
          final Long timeout,
      @ToolParam(
              description =
                  "Clear, concise description of what this command does in 5-10 words, in active"
                      + " voice.",
              required = false)
          final String description,
      @ToolParam(
              description =
                  "Set to true to run this command in the background. Use BashOutput to read the"
                      + " output later.",
              required = false)
          final Boolean runInBackground,
      final ToolContext toolContext) {
    // @formatter:on

    final var userId = userIdFrom(toolContext);

    final var bashId = "shell_" + System.currentTimeMillis();
    log.info(
        "Bash invoked user={} bashId={} background={} description={}",
        userId,
        bashId,
        Boolean.TRUE.equals(runInBackground),
        description);

    try {
      final var container = userContainerManager.ensureContainerFor(userId);
      if (Boolean.TRUE.equals(runInBackground)) {
        return runBackground(container, bashId, command);
      }
      return runForeground(container, bashId, command, timeout);
    } catch (final Exception e) {
      log.error("Bash execution failed user={} bashId={}", userId, bashId, e);
      return "bash_id: " + bashId + "\n\nError executing command: " + e.getMessage();
    }
  }

  // @formatter:off
  @Tool(
      name = "BashOutput",
      description =
"""
- Retrieves output from a running or completed background bash shell
- Takes a bash_id parameter identifying the shell
- Always returns only new output since the last check
- Returns stdout and stderr output along with shell status
- Supports optional regex filtering to show only lines matching a pattern
- Use this tool when you need to monitor or check the output of a long-running shell
""")
  public String bashOutput(
      @ToolParam(description = "The ID of the background shell to retrieve output from")
          final String bash_id,
      @ToolParam(
              description =
                  "Optional regular expression to filter the output lines. Only lines matching this"
                      + " regex will be included in the result.",
              required = false)
          final String filter,
      final ToolContext toolContext) {
    // @formatter:on

    final var userId = userIdFrom(toolContext);

    if (!isSafeBashId(bash_id)) {
      return "Error: invalid bash_id";
    }

    try {
      final var container = userContainerManager.ensureContainerFor(userId);
      // Everything written since the last call, however much that is. What a result costs the
      // model's context is not decided here: LargeResponseInterceptor sees every tool's result and
      // spills an oversized one to the user's workspace, so a cap of our own would only truncate
      // output it could otherwise have handed over in full.
      final var script =
          String.join(
              "\n",
              "touch /tmp/.last_activity 2>/dev/null || true",
              "BG=/tmp/.bg/" + bash_id,
              "if [ ! -f \"$BG.pid\" ]; then echo NOT_FOUND; exit 0; fi",
              "OFF=$(cat \"$BG.offset\" 2>/dev/null || echo 0)",
              "SIZE=$(stat -c %s \"$BG.out\" 2>/dev/null || echo 0)",
              "NEW=$((SIZE-OFF))",
              "if [ \"$NEW\" -gt 0 ]; then",
              "  dd if=\"$BG.out\" bs=1 skip=$OFF count=$NEW 2>/dev/null",
              "  echo $((OFF+NEW)) > \"$BG.offset\"",
              "fi",
              "echo --SPRING-AGENT-STATUS--",
              "if kill -0 $(cat \"$BG.pid\") 2>/dev/null; then echo Running; else echo Completed;"
                  + " fi");
      final var result = execSync(container, script, properties.defaultTimeoutMs());
      final var stdout = result.stdout();

      final var marker = "--SPRING-AGENT-STATUS--";
      final var idx = stdout.lastIndexOf(marker);
      String newOutput;
      String status;
      if (idx >= 0) {
        newOutput = stdout.substring(0, idx).stripTrailing();
        status = stdout.substring(idx + marker.length()).trim();
      } else {
        newOutput = stdout;
        status = "Unknown";
      }

      if ("NOT_FOUND".equals(newOutput.trim())) {
        return "Error: No background shell found with ID: " + bash_id;
      }

      if (filter != null && !filter.isBlank()) {
        newOutput = applyRegexFilter(newOutput, filter);
      }

      final var out = new StringBuilder();
      out.append("Shell ID: ").append(bash_id).append('\n');
      out.append("Status: ").append(status).append('\n');
      if (!newOutput.isEmpty()) {
        out.append("\nNew output:\n").append(newOutput);
      } else {
        out.append("\nNo new output since last check.");
      }
      return out.toString();
    } catch (final Exception e) {
      log.error("BashOutput failed user={} bashId={}", userId, bash_id, e);
      return "Error retrieving output: " + e.getMessage();
    }
  }

  // @formatter:off
  @Tool(
      name = "KillShell",
      description =
"""
- Kills a running background bash shell by its ID
- Takes a bash_id parameter identifying the shell to kill
- Returns a success or failure status
""")
  public String killShell(
      @ToolParam(description = "The ID of the background shell to kill") final String bash_id,
      final ToolContext toolContext) {
    // @formatter:on

    final var userId = userIdFrom(toolContext);
    if (!isSafeBashId(bash_id)) {
      return "Error: invalid bash_id";
    }

    try {
      final var container = userContainerManager.ensureContainerFor(userId);
      final var script =
          String.join(
              "\n",
              "touch /tmp/.last_activity 2>/dev/null || true",
              "BG=/tmp/.bg/" + bash_id,
              "if [ ! -f \"$BG.pid\" ]; then echo NOT_FOUND; exit 0; fi",
              "PID=$(cat \"$BG.pid\")",
              "if kill -0 $PID 2>/dev/null; then",
              "  kill -TERM -$PID 2>/dev/null || kill -TERM $PID 2>/dev/null || true",
              "  sleep 1",
              "  kill -KILL -$PID 2>/dev/null || kill -KILL $PID 2>/dev/null || true",
              "  STATUS=killed",
              "else",
              "  STATUS=already_terminated",
              "fi",
              "rm -f \"$BG.pid\" \"$BG.out\" \"$BG.offset\" \"$BG.cmd\"",
              "echo $STATUS");
      final var result = execSync(container, script, properties.defaultTimeoutMs());
      final var status = result.stdout().trim();
      if ("NOT_FOUND".equals(status)) {
        return "Error: No background shell found with ID: " + bash_id;
      }
      if ("already_terminated".equals(status)) {
        return "Shell " + bash_id + " was already terminated. Removed from active shells.";
      }
      return "Successfully killed shell: " + bash_id;
    } catch (final Exception e) {
      log.error("KillShell failed user={} bashId={}", userId, bash_id, e);
      return "Error killing shell: " + e.getMessage();
    }
  }

  // @formatter:off
  @Tool(
      name = "RestartShellContainer",
      description =
"""
- Restarts the user's shell sandbox container
- Use after SetCredential / DeleteCredential to expose updated values as environment variables
- The next Bash call will create a fresh container with the latest credentials
- Any background shells and any files outside the working directory are lost
""")
  public String restartShellContainer(final ToolContext toolContext) {
    // @formatter:on

    final var userId = userIdFrom(toolContext);
    try {
      final var deleted = userContainerManager.deleteContainerFor(userId);
      if (!deleted) {
        return "No running shell container was found. The next Bash call will create one.";
      }
      return "Shell container restarted. The next Bash call will create a fresh container with"
          + " updated credentials.";
    } catch (final Exception e) {
      log.error("RestartShellContainer failed user={}", userId, e);
      return "Error restarting shell container: " + e.getMessage();
    }
  }

  private String runForeground(
      final GenericContainer<?> container,
      final String bashId,
      final String command,
      final Long timeoutMs) {
    final var effectiveTimeout =
        clamp(timeoutMs == null ? properties.defaultTimeoutMs() : timeoutMs);
    final var script = "touch /tmp/.last_activity 2>/dev/null || true\n" + command;

    final Result result;
    try {
      result = execSync(container, script, effectiveTimeout);
    } catch (final TimeoutException e) {
      return "bash_id: " + bashId + "\n\nCommand timed out after " + effectiveTimeout + "ms";
    } catch (final Exception e) {
      return "bash_id: " + bashId + "\n\nError executing command: " + e.getMessage();
    }

    final var out = new StringBuilder();
    out.append("bash_id: ").append(bashId).append("\n\n");
    if (!result.stdout().isEmpty()) {
      out.append(result.stdout());
    }
    if (!result.stderr().isEmpty()) {
      if (!result.stdout().isEmpty()) out.append('\n');
      out.append("STDERR:\n").append(result.stderr());
    }
    if (result.exitCode() != null && result.exitCode() != 0) {
      if (out.length() > 0) out.append('\n');
      out.append("Exit code: ").append(result.exitCode());
    }
    return out.toString();
  }

  /**
   * Copies the command in as a file rather than piping it through the shell that launches it.
   *
   * <p>The Kubernetes module feeds it to {@code cat} over the exec channel's stdin; Testcontainers
   * has no stdin on exec, so the command travels as a file over the Docker API instead. That is the
   * better of the two anyway — the user's command text is never part of a script that a shell
   * parses, so there is nothing to quote or escape.
   */
  private String runBackground(
      final GenericContainer<?> container, final String bashId, final String command)
      throws Exception {
    execSync(container, "mkdir -p /tmp/.bg", 30_000);
    container.copyFileToContainer(Transferable.of(command), "/tmp/.bg/" + bashId + ".cmd");

    final var script =
        String.join(
            "\n",
            "set -e",
            "touch /tmp/.last_activity 2>/dev/null || true",
            "setsid bash /tmp/.bg/"
                + bashId
                + ".cmd > /tmp/.bg/"
                + bashId
                + ".out 2>&1 < /dev/null &",
            "PID=$!",
            "echo $PID > /tmp/.bg/" + bashId + ".pid",
            "echo 0 > /tmp/.bg/" + bashId + ".offset");
    execSync(container, script, 30_000);
    return "bash_id: "
        + bashId
        + "\n\nBackground shell started with ID: "
        + bashId
        + "\nUse BashOutput tool with bash_id='"
        + bashId
        + "' to retrieve output.";
  }

  /**
   * {@code execInContainer} with a deadline, which it does not offer itself.
   *
   * <p>Note what cancelling does not do: the command inside the container keeps running, because
   * nothing here holds its process. A timeout abandons the exec rather than killing it, the same as
   * the Kubernetes module's behaviour when its watch expires.
   */
  private Result execSync(
      final GenericContainer<?> container, final String script, final long timeoutMs)
      throws Exception {
    final var future =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return container.execInContainer("bash", "-c", script);
              } catch (final Exception e) {
                throw new IllegalStateException(e);
              }
            },
            executor);
    final ExecResult result;
    try {
      result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (final TimeoutException e) {
      future.cancel(true);
      throw e;
    }

    // Nothing bounds this, and nothing here can: the Kubernetes module caps the exec stream as it
    // is written, whereas Testcontainers' execInContainer buffers the whole command's output and
    // hands back a String that is already in this heap by the time we see it. Truncating it now
    // would lose text without having saved any memory — and would lose it for good, since
    // LargeResponseInterceptor could otherwise have written it to the user's workspace. A command
    // that writes more than this JVM can hold takes it down; that is the price of this backend,
    // which is the laptop and single-host one.
    return new Result(
        nullSafe(result.getStdout()), nullSafe(result.getStderr()), result.getExitCode());
  }

  private static String nullSafe(final String value) {
    return value == null ? "" : value;
  }

  private long clamp(final long timeoutMs) {
    return Math.min(Math.max(timeoutMs, 1L), properties.maxTimeoutMs());
  }

  private String applyRegexFilter(final String output, final String regex) {
    try {
      final var pattern = Pattern.compile(regex);
      final var filtered = new StringBuilder();
      for (final var line : output.split("\n")) {
        if (pattern.matcher(line).find()) {
          filtered.append(line).append('\n');
        }
      }
      return filtered.toString();
    } catch (final PatternSyntaxException e) {
      return output;
    }
  }

  private static String userIdFrom(final ToolContext context) {
    return ToolContexts.require(context, ToolContexts.USER_ID);
  }

  private static boolean isSafeBashId(final String bashId) {
    if (bashId == null || bashId.isBlank() || bashId.length() > 64) return false;
    for (int i = 0; i < bashId.length(); i++) {
      final var c = bashId.charAt(i);
      if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-')) return false;
    }
    return true;
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }

  private record Result(String stdout, String stderr, Integer exitCode) {}
}
