package me.kezhenxu94.springagent.core.tools.credentials;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.AgentTool;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Lets a user keep a token or API key in their shell sandbox without pasting it into a command.
 *
 * <p>Written against {@link ShellCredentialStore} rather than any one backend, so both shells
 * expose the same three tools. What differs between them — where the values live, and which tool
 * restarts the sandbox — is constructor state, because a {@code @Tool} description is a
 * compile-time constant and cannot say "Pod" to one deployment and "container" to another.
 *
 * @param restartToolName the sandbox-restarting tool the model should call to pick up a change.
 */
@Slf4j
@AgentTool
@RequiredArgsConstructor
public class CredentialTools {

  private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");
  private static final int MAX_VALUE_BYTES = 64 * 1024;

  private final ShellCredentialStore store;
  private final String restartToolName;

  // @formatter:off
  @Tool(
      name = "SetCredential",
      description =
"""
- Stores a credential (token, API key, password) in the user's shell sandbox.
- The credential is exposed inside the sandbox as environment variable $NAME and as a
  read-only file named NAME under the sandbox's credentials directory.
- NAME must match ^[A-Za-z_][A-Za-z0-9_]{0,63}$ to be a valid POSIX env-var name.
- Values are never echoed back; use ListCredentials to see which credentials are stored.
""")
  public String setCredential(
      @ToolParam(description = "Credential name (env-var-safe identifier)") final String name,
      @ToolParam(description = "Credential value") final String value,
      final ToolContext toolContext) {
    // @formatter:on

    final var userId = userIdFrom(toolContext);
    if (userId == null) {
      return "Error: credential store unavailable: no userId in tool context";
    }
    if (name == null || !NAME_PATTERN.matcher(name).matches()) {
      return "Error: invalid credential name. Must match ^[A-Za-z_][A-Za-z0-9_]{0,63}$";
    }
    if (value == null) {
      return "Error: value must not be null";
    }
    final var valueBytes = value.getBytes(StandardCharsets.UTF_8);
    if (valueBytes.length > MAX_VALUE_BYTES) {
      return "Error: value too large ("
          + valueBytes.length
          + " bytes, max "
          + MAX_VALUE_BYTES
          + ")";
    }

    try {
      store.put(userId, name, value);
      return "Credential "
          + name
          + " stored. Run "
          + restartToolName
          + " to expose it as $"
          + name
          + ".";
    } catch (final Exception e) {
      log.error("SetCredential failed user={} name={}", userId, name, e);
      return "Error storing credential: " + e.getMessage();
    }
  }

  // @formatter:off
  @Tool(
      name = "ListCredentials",
      description =
"""
- Lists the names of credentials the user has stored in the shell sandbox.
- Returns each credential's name and last-updated timestamp.
- Values are never returned.
""")
  public String listCredentials(final ToolContext toolContext) {
    // @formatter:on

    final var userId = userIdFrom(toolContext);
    if (userId == null) {
      return "Error: credential store unavailable: no userId in tool context";
    }

    try {
      final var entries = store.list(userId);
      if (entries.isEmpty()) {
        return "No credentials stored.";
      }
      final var out = new StringBuilder("Credentials:\n");
      entries.stream()
          .sorted(Comparator.comparing(ShellCredentialStore.Entry::name))
          .forEach(
              entry -> {
                final var updated =
                    entry.updatedAt() == null ? "unknown" : entry.updatedAt().toString();
                out.append("- ")
                    .append(entry.name())
                    .append("  (lastUpdated=")
                    .append(updated)
                    .append(")\n");
              });
      return out.toString().stripTrailing();
    } catch (final Exception e) {
      log.error("ListCredentials failed user={}", userId, e);
      return "Error listing credentials: " + e.getMessage();
    }
  }

  // @formatter:off
  @Tool(
      name = "DeleteCredential",
      description =
"""
- Removes a credential from the user's shell sandbox.
- Idempotent: returns success even if the credential does not exist.
- Restart the shell sandbox afterwards so the env var disappears from the running sandbox.
""")
  public String deleteCredential(
      @ToolParam(description = "Credential name to remove") final String name,
      final ToolContext toolContext) {
    // @formatter:on

    final var userId = userIdFrom(toolContext);
    if (userId == null) {
      return "Error: credential store unavailable: no userId in tool context";
    }
    if (name == null || !NAME_PATTERN.matcher(name).matches()) {
      return "Error: invalid credential name. Must match ^[A-Za-z_][A-Za-z0-9_]{0,63}$";
    }

    try {
      if (!store.delete(userId, name)) {
        return "Credential " + name + " not found (nothing to delete).";
      }
      return "Credential "
          + name
          + " removed. Run "
          + restartToolName
          + " to drop $"
          + name
          + " from the sandbox.";
    } catch (final Exception e) {
      log.error("DeleteCredential failed user={} name={}", userId, name, e);
      return "Error deleting credential: " + e.getMessage();
    }
  }

  private static String userIdFrom(final ToolContext context) {
    return ToolContexts.get(context, ToolContexts.USER_ID);
  }
}
