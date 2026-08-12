package me.kezhenxu94.springagent.core.tools;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.ai.tools.shell-pod", name = "enabled", havingValue = "true")
public class KubernetesShellTools {

  private final KubernetesClient kubernetesClient;
  private final UserPodManager userPodManager;
  private final ShellPodProperties properties;

  // @formatter:off
  @Tool(
      name = "Bash",
      description =
"""
Execute a bash command for terminal operations like npm, docker, make, mvn, python.
DO NOT use for file operations — use specialized tools instead:
- File search: Use Glob (NOT find or ls)
- Content search: Use Grep (NOT grep or rg)
- Read files: Use Read (NOT cat/head/tail)
- Edit files: Use Edit (NOT sed/awk)
- Write files: Use Write (NOT echo >/cat <<EOF)

Usage notes:
- The command argument is required.
- Optional timeout in milliseconds (max 600000ms / 10 minutes). Default: 120000ms (2 minutes).
- Output truncated at 30000 characters.
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
      final var podName = userPodManager.ensurePodFor(userId);
      if (Boolean.TRUE.equals(runInBackground)) {
        return runBackground(podName, bashId, command);
      }
      return runForeground(podName, bashId, command, timeout);
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
      final var podName = userPodManager.ensurePodFor(userId);
      final var maxBytes = properties.maxOutputBytes();
      final var script =
          String.join(
              "\n",
              "touch /tmp/.last_activity 2>/dev/null || true",
              "BG=/tmp/.bg/" + bash_id,
              "if [ ! -f \"$BG.pid\" ]; then echo NOT_FOUND; exit 0; fi",
              "OFF=$(cat \"$BG.offset\" 2>/dev/null || echo 0)",
              "SIZE=$(stat -c %s \"$BG.out\" 2>/dev/null || echo 0)",
              "MAX=" + maxBytes,
              "AVAIL=$((SIZE-OFF))",
              "if [ \"$AVAIL\" -gt \"$MAX\" ]; then NEW=$MAX; TRUNC=1; else NEW=$AVAIL; TRUNC=0;"
                  + " fi",
              "if [ \"$NEW\" -gt 0 ]; then",
              "  dd if=\"$BG.out\" bs=1 skip=$OFF count=$NEW 2>/dev/null",
              "  echo $((OFF+NEW)) > \"$BG.offset\"",
              "fi",
              "if [ \"$TRUNC\" = 1 ]; then",
              "  echo",
              "  echo \"... (output truncated at $MAX bytes; call BashOutput again for more)\"",
              "fi",
              "echo --SPRING-AGENT-STATUS--",
              "if kill -0 $(cat \"$BG.pid\") 2>/dev/null; then echo Running; else echo Completed;"
                  + " fi");
      final var result = execSync(podName, script, properties.defaultTimeoutMs());
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
      final var podName = userPodManager.ensurePodFor(userId);
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
      final var result = execSync(podName, script, properties.defaultTimeoutMs());
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
      name = "RestartShellPod",
      description =
"""
- Restarts the user's shell sandbox pod
- Use after SetCredential / DeleteCredential to expose updated values as environment variables
- The next Bash call will create a fresh pod with the latest credentials
- Files under the credentials mount path auto-refresh and do not require a restart
""")
  public String restartShellPod(final ToolContext toolContext) {
    // @formatter:on

    final var userId = userIdFrom(toolContext);
    try {
      final var deleted = userPodManager.deletePodFor(userId);
      if (!deleted) {
        return "No running shell pod was found. The next Bash call will create one.";
      }
      return "Shell pod restarted. The next Bash call will create a fresh pod with updated"
          + " credentials.";
    } catch (final Exception e) {
      log.error("RestartShellPod failed user={}", userId, e);
      return "Error restarting shell pod: " + e.getMessage();
    }
  }

  private String runForeground(
      final String podName, final String bashId, final String command, final Long timeoutMs) {
    final var effectiveTimeout =
        clamp(timeoutMs == null ? properties.defaultTimeoutMs() : timeoutMs);
    final var script = "touch /tmp/.last_activity 2>/dev/null || true\n" + command;

    final ExecResult result;
    try {
      result = execSync(podName, script, effectiveTimeout);
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
    if (result.truncated()) {
      out.append("\n... (output truncated at ")
          .append(properties.maxOutputBytes())
          .append(" bytes)");
    }
    return truncate(out.toString(), properties.maxOutputBytes() + 256);
  }

  private String runBackground(final String podName, final String bashId, final String command)
      throws IOException, InterruptedException, ExecutionException, TimeoutException {
    final var script =
        String.join(
                "\n",
                "set -e",
                "mkdir -p /tmp/.bg",
                "touch /tmp/.last_activity 2>/dev/null || true",
                "cat > /tmp/.bg/" + bashId + ".cmd",
                "setsid bash /tmp/.bg/"
                    + bashId
                    + ".cmd > /tmp/.bg/"
                    + bashId
                    + ".out 2>&1 < /dev/null &",
                "PID=$!",
                "echo $PID > /tmp/.bg/" + bashId + ".pid",
                "echo 0 > /tmp/.bg/" + bashId + ".offset")
            + "\n";
    execWithStdin(podName, script, command, 30_000);
    return "bash_id: "
        + bashId
        + "\n\nBackground shell started with ID: "
        + bashId
        + "\nUse BashOutput tool with bash_id='"
        + bashId
        + "' to retrieve output.";
  }

  private ExecResult execSync(final String podName, final String script, final long timeoutMs)
      throws InterruptedException, ExecutionException, TimeoutException {
    final var out = new BoundedByteArrayOutputStream(properties.maxOutputBytes());
    final var err = new BoundedByteArrayOutputStream(properties.maxOutputBytes());
    try (var watch =
        kubernetesClient
            .pods()
            .inNamespace(userPodManager.namespace())
            .withName(podName)
            .writingOutput(out)
            .writingError(err)
            .exec("bash", "-c", script)) {
      final Integer exit = waitForExit(watch, timeoutMs);
      return new ExecResult(
          out.toString(StandardCharsets.UTF_8),
          err.toString(StandardCharsets.UTF_8),
          exit,
          out.isTruncated() || err.isTruncated());
    }
  }

  private void execWithStdin(
      final String podName, final String script, final String stdin, final long timeoutMs)
      throws IOException, InterruptedException, ExecutionException, TimeoutException {
    final var out = new ByteArrayOutputStream();
    final var err = new ByteArrayOutputStream();
    try (var watch =
        kubernetesClient
            .pods()
            .inNamespace(userPodManager.namespace())
            .withName(podName)
            .redirectingInput()
            .writingOutput(out)
            .writingError(err)
            .exec("bash", "-c", script)) {
      watch.getInput().write(stdin.getBytes(StandardCharsets.UTF_8));
      watch.getInput().close();
      waitForExit(watch, timeoutMs);
    }
  }

  private Integer waitForExit(final ExecWatch watch, final long timeoutMs)
      throws InterruptedException, ExecutionException, TimeoutException {
    return watch.exitCode().get(timeoutMs, TimeUnit.MILLISECONDS);
  }

  private long clamp(final long timeoutMs) {
    return Math.min(Math.max(timeoutMs, 1L), properties.maxTimeoutMs());
  }

  private String truncate(final String output, final int maxBytes) {
    if (output.length() <= maxBytes) return output;
    final var headerEnd = output.indexOf("\n\n");
    if (headerEnd <= 0) {
      return output.substring(0, maxBytes) + "\n... (output truncated)";
    }
    final var header = output.substring(0, headerEnd + 2);
    final var body = output.substring(headerEnd + 2);
    final var room = maxBytes - header.length();
    if (room <= 0) return output.substring(0, maxBytes) + "\n... (output truncated)";
    return header + body.substring(0, Math.min(body.length(), room)) + "\n... (output truncated)";
  }

  private String applyRegexFilter(final String output, final String regex) {
    try {
      final var pattern = java.util.regex.Pattern.compile(regex);
      final var filtered = new StringBuilder();
      for (final var line : output.split("\n")) {
        if (pattern.matcher(line).find()) {
          filtered.append(line).append('\n');
        }
      }
      return filtered.toString();
    } catch (final java.util.regex.PatternSyntaxException e) {
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

  private record ExecResult(String stdout, String stderr, Integer exitCode, boolean truncated) {}

  /** Output stream that caps its size at the configured limit and discards subsequent bytes. */
  private static final class BoundedByteArrayOutputStream extends OutputStream {
    private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
    private final int max;
    private boolean truncated;

    BoundedByteArrayOutputStream(final int max) {
      this.max = max;
    }

    @Override
    public void write(final int b) {
      if (delegate.size() >= max) {
        truncated = true;
        return;
      }
      delegate.write(b);
    }

    @Override
    public void write(final byte[] b, final int off, final int len) {
      final var room = max - delegate.size();
      if (room <= 0) {
        truncated = true;
        return;
      }
      final var toWrite = Math.min(len, room);
      delegate.write(b, off, toWrite);
      if (toWrite < len) truncated = true;
    }

    String toString(final Charset cs) {
      return delegate.toString(cs);
    }

    boolean isTruncated() {
      return truncated;
    }
  }
}
