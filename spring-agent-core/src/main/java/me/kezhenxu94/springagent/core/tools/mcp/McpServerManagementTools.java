package me.kezhenxu94.springagent.core.tools.mcp;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.dao.models.McpServerConfig;
import me.kezhenxu94.springagent.core.tools.AgentTool;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * The MCP server registry, put to the model as tools.
 *
 * <p>Everything that decides anything is {@link McpServerRegistry}'s; this is the half that talks
 * to a model. It reads the owner and the chat out of the {@link ToolContext}, hands the registry a
 * request, and turns whatever comes back — a registration or an {@link McpRegistryException} — into
 * a sentence in the workspace's language. The browser's {@code McpController} is the other caller
 * of that registry and says something different about the same refusals.
 *
 * <p>Which sentence a refusal gets is partly the operation's, not the reason's alone: "no server of
 * that name is registered to you" is the right answer to a remove and the wrong one to a share,
 * where what the model needs to hear is that only servers it owns can be shared.
 *
 * <p>Only remote streamable HTTP servers are supported; stdio and SSE are rejected.
 */
@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
public class McpServerManagementTools {

  private final McpServerRegistry registry;

  /** What this hands back to the model, in the workspace's language. */
  private final CoreMessages messages;

  @Tool(
      name = "AddMcpServer",
      description =
"""
Register a remote MCP (Model Context Protocol) server for this user, so its tools become available to
the agent. Only remote streamable HTTP servers are supported. Local/stdio servers (anything launched
via a command) and SSE servers are NOT supported.

The URL and connection are validated before saving: if the URL is disallowed, the server cannot be
reached, or it points at a private/loopback address, nothing is stored and an error is returned. Always
attempt the call with the URL you were given rather than refusing on sight — the error message will
explain why a URL is invalid. On success the server's tool names are returned. Re-adding an existing
name overwrites its configuration.

Every tool this server offers is exposed under a prefix, so pass toolPrefix when the user says what
the tools should be called; without one the prefix is a hash of the server name, which is valid but
unreadable. Two servers the same user can reach may not share a prefix.
""")
  public String addMcpServer(
      @ToolParam(
              description = "Unique name for this MCP server (used to reference/remove it later)")
          final String name,
      @ToolParam(description = "The MCP server endpoint URL") final String url,
      @ToolParam(
              required = false,
              description =
                  "Optional HTTP headers for authentication, e.g. {\"Authorization\": \"Bearer"
                      + " <token>\"}")
          final Map<String, String> headers,
      @ToolParam(
              required = false,
              description = "Optional display title reported to the server; defaults to name")
          final String title,
      @ToolParam(
              required = false,
              description = "Optional client version reported to the server; defaults to 1.0.0")
          final String version,
      @ToolParam(required = false, description = "Optional human-readable description")
          final String description,
      @ToolParam(required = false, description = "Optional website URL for this integration")
          final String websiteUrl,
      @ToolParam(
              required = false,
              description =
                  "Optional prefix every tool of this server is named after, e.g. \"github\" makes"
                      + " \"github_search_issues\". Letters, digits, underscores and hyphens only."
                      + " Must not be one another server you can reach already uses. Defaults to a"
                      + " hash of the name, which works but tells you nothing when you see the"
                      + " tool")
          final String toolPrefix,
      final ToolContext context) {
    final var ownerId = ToolContexts.require(context, ToolContexts.USER_ID);

    if (name == null || name.isBlank()) {
      return messages.get("mcp-no-name");
    }
    if (url == null || url.isBlank()) {
      return messages.get("mcp-no-url");
    }

    final McpRegistration registered;
    try {
      registered =
          registry.register(
              ownerId,
              ToolContexts.get(context, ToolContexts.CHAT_ID),
              new McpServerSpec(
                  name, url, headers, title, version, description, websiteUrl, toolPrefix),
              context.getContext());
    } catch (final McpRegistryException e) {
      return refusal(e, "mcp-verb-registered", "mcp-unknown");
    }

    final var toolNames = registered.toolNames();
    return messages.get(
        "mcp-registered",
        registered.config().name(),
        McpServerConfig.Transport.STREAMABLE_HTTP,
        toolNames.isEmpty() ? messages.get("mcp-no-tools") : String.join(", ", toolNames));
  }

  @Tool(
      name = "ListMcpServers",
      description =
          "List the MCP servers registered by this user (name, transport, URL, enabled, who it's"
              + " shared with), plus any servers others have shared with this user, the current"
              + " chat, or everyone (name and transport only — connection details stay private to"
              + " the owner), plus the servers this application configures for everyone, which are"
              + " already available to every user here and cannot be added, removed or shared from"
              + " here.")
  public String listMcpServers(final ToolContext context) {
    final var ownerId = ToolContexts.require(context, ToolContexts.USER_ID);
    final var chatId = ToolContexts.get(context, ToolContexts.CHAT_ID);

    final var owned = registry.owned(ownerId);
    final var shared = registry.sharedWith(ownerId, chatId);
    final var configured = registry.applicationConfigured();

    if (owned.isEmpty() && shared.isEmpty() && configured.isEmpty()) {
      return messages.get("mcp-none");
    }

    final var sb = new StringBuilder();
    if (!owned.isEmpty()) {
      sb.append(messages.get("mcp-owned-by-you")).append("\n");
      for (final var server : owned) {
        sb.append("- ")
            .append(server.name())
            .append(" [")
            .append(server.transport())
            .append("] ")
            .append(server.url())
            .append(server.enabled() ? "" : " " + messages.get("mcp-disabled"))
            .append(
                server.sharedWith() == null || server.sharedWith().isEmpty()
                    ? ""
                    : " " + messages.get("mcp-shared-with", shareTargets(server.sharedWith())))
            .append("\n");
      }
    }
    if (!shared.isEmpty()) {
      sb.append(messages.get("mcp-shared-with-you")).append("\n");
      for (final var server : shared) {
        sb.append("- ")
            .append(server.name())
            .append(" [")
            .append(server.transport())
            .append("] ")
            .append(messages.get("mcp-shared-by"))
            .append(' ')
            .append(server.ownerId())
            .append(server.enabled() ? "" : " " + messages.get("mcp-disabled"))
            .append("\n");
      }
    }
    if (!configured.isEmpty()) {
      sb.append(messages.get("mcp-configured-here")).append("\n");
      for (final var server : configured) {
        sb.append("- ")
            .append(server.name())
            .append(" [")
            .append(McpServerConfig.Transport.STREAMABLE_HTTP)
            .append("] ")
            .append(server.url())
            .append("\n");
      }
    }
    return sb.toString();
  }

  @Tool(
      name = "ShareMcpServer",
      description =
          "Share a server you own with another Feishu user or group chat, identified by their"
              + " open_id or chat_id. The recipient gains use of the server's tools; ownership"
              + " (editing or removing the server, or sharing it further) stays with you.")
  public String shareMcpServer(
      @ToolParam(description = "Name of the MCP server to share") final String name,
      @ToolParam(description = "Feishu open_id (user) or chat_id (group) to share with")
          final String targetId,
      final ToolContext context) {
    final var ownerId = ToolContexts.require(context, ToolContexts.USER_ID);

    if (name == null || name.isBlank()) {
      return messages.get("mcp-no-server-name");
    }
    if (targetId == null || targetId.isBlank()) {
      return messages.get("mcp-no-target");
    }
    final var serverName = name.trim();
    final var target = targetId.trim();

    try {
      registry.share(ownerId, serverName, target);
    } catch (final McpRegistryException e) {
      return refusal(e, "mcp-verb-shared", "mcp-not-yours-share");
    }
    return messages.get("mcp-share-done", serverName, target);
  }

  @Tool(
      name = "UnshareMcpServer",
      description = "Revoke a previously granted share of a server you own.")
  public String unshareMcpServer(
      @ToolParam(description = "Name of the MCP server") final String name,
      @ToolParam(description = "Feishu open_id or chat_id to revoke access from")
          final String targetId,
      final ToolContext context) {
    final var ownerId = ToolContexts.require(context, ToolContexts.USER_ID);

    if (name == null || name.isBlank()) {
      return messages.get("mcp-no-server-name");
    }
    if (targetId == null || targetId.isBlank()) {
      return messages.get("mcp-no-target");
    }
    final var serverName = name.trim();
    final var target = targetId.trim();

    try {
      registry.unshare(ownerId, serverName, target);
    } catch (final McpRegistryException e) {
      return refusal(e, "mcp-verb-unshared", "mcp-not-yours-unshare");
    }
    return messages.get("mcp-unshare-done", serverName, target);
  }

  @Tool(
      name = "RemoveMcpServer",
      description = "Remove a previously registered MCP server by name.")
  public String removeMcpServer(
      @ToolParam(description = "Name of the MCP server to remove") final String name,
      final ToolContext context) {
    final var ownerId = ToolContexts.require(context, ToolContexts.USER_ID);

    if (name == null || name.isBlank()) {
      return messages.get("mcp-no-server-name");
    }
    final var serverName = name.trim();

    try {
      registry.remove(ownerId, serverName);
    } catch (final McpRegistryException e) {
      return refusal(e, "mcp-verb-removed", "mcp-unknown");
    }
    return messages.get("mcp-removed", serverName);
  }

  /**
   * A refusal as the model should read it.
   *
   * <p>Two of the reasons have no sentence of their own, because what to say about them depends on
   * what was being attempted: {@code verbKey} is the word that finishes "cannot be … through these
   * tools" for a name this application configures, and {@code unknownKey} is the whole sentence for
   * a name the caller does not own — which for a share has to say that ownership is the point,
   * while for a remove it need only say there is no such server.
   */
  private String refusal(
      final McpRegistryException e, final String verbKey, final String unknownKey) {
    final var arguments = e.arguments();
    return switch (e.reason()) {
      case INVALID -> messages.get("mcp-error", arguments);
      case PREFIX_TAKEN -> messages.get("mcp-prefix-taken", arguments);
      case UNREACHABLE -> messages.get("mcp-unreachable", arguments);
      case SAVE_FAILED -> messages.get("mcp-save-failed", arguments);
      case ALREADY_SHARED -> messages.get("mcp-already-shared", arguments);
      case NOT_SHARED -> messages.get("mcp-not-shared", arguments);
      case UNKNOWN -> messages.get(unknownKey, arguments);
      case APPLICATION_CONFIGURED ->
          messages.get("mcp-not-yours-to-manage", arguments[0], messages.get(verbKey));
    };
  }

  /**
   * The share list as the reader should see it: {@link McpServerConfig#SHARED_WITH_ALL} is a
   * sentinel, and a bare {@code *} in the answer says nothing about who can reach the server.
   */
  private static String shareTargets(final List<String> sharedWith) {
    return sharedWith.stream()
        .map(target -> McpServerConfig.SHARED_WITH_ALL.equals(target) ? "everyone" : target)
        .collect(Collectors.joining(", "));
  }
}
