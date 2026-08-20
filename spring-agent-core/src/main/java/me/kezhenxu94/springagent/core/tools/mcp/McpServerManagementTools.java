package me.kezhenxu94.springagent.core.tools.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.McpServerConfig;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import me.kezhenxu94.springagent.core.tools.AgentTool;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties.ConnectionParameters;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * MCP server registry, exposed to the agent as tools. The owner (and the chat the request came
 * from) is resolved per call from {@link ToolContext}; every mutating operation
 * (add/remove/share/unshare) is scoped to that owner — sharing a server does not grant the
 * recipient the ability to manage it.
 *
 * <p>Only remote streamable HTTP servers are supported; stdio and SSE are rejected.
 */
@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
public class McpServerManagementTools {
  private final McpServerConfigRepo repo;
  private final McpClientFactory clientFactory;

  /**
   * The servers this application configures for everyone under {@code
   * spring.ai.mcp.client.streamable-http}, which reach a run through {@link
   * me.kezhenxu94.springagent.core.tools.AgentToolsProvider} rather than through the repository
   * above. Listed here because a user asking what MCP servers they have means the ones the agent
   * can reach, not the ones a particular table happens to hold.
   *
   * <p>Through an {@link ObjectProvider} because the bean only exists while {@code
   * spring.ai.mcp.client.enabled} is true, and an application that turns MCP off entirely must
   * still get its MCP registry tools.
   */
  private final ObjectProvider<McpStreamableHttpClientProperties> streamableHttpProperties;

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
      final ToolContext context) {
    final var ownerId = ToolContexts.require(context, ToolContexts.USER_ID);

    if (name == null || name.isBlank()) {
      return "Error: a non-empty server name is required.";
    }
    if (url == null || url.isBlank()) {
      return "Error: a server URL is required.";
    }
    final var serverName = name.trim();
    final var serverUrl = url.trim();

    try {
      clientFactory.validateRemoteUrl(serverUrl);
    } catch (IllegalArgumentException e) {
      return "Error: " + e.getMessage();
    }

    final var existing = repo.findByOwnerIdAndName(ownerId, serverName).orElse(null);
    final var config =
        (existing != null
                ? existing.toBuilder()
                // Same as ScheduledTaskTool#newTaskId: neither backend generates an identifier, so
                // a new config has to arrive with one.
                : McpServerConfig.builder().id(UUID.randomUUID().toString().replace("-", "")))
            .ownerId(ownerId)
            .name(serverName)
            .transport(McpServerConfig.Transport.STREAMABLE_HTTP)
            .url(serverUrl)
            .headers(headers == null ? null : new LinkedHashMap<>(headers))
            .title(blankToNull(title))
            .version(blankToNull(version))
            .description(blankToNull(description))
            .websiteUrl(blankToNull(websiteUrl))
            .enabled(true)
            .build();

    final List<String> toolNames;
    McpSyncClient client = null;
    try {
      // The registration probe goes out with the same headers a run would send, contributors
      // included, so a server that only accepts the call once one is present is not rejected here.
      client = clientFactory.createAndInitialize(config, context.getContext());
      toolNames = client.listTools().tools().stream().map(t -> t.name()).toList();
    } catch (IllegalArgumentException e) {
      return "Error: " + e.getMessage();
    } catch (Exception e) {
      log.warn(
          "Failed to connect to MCP server '{}' at {} for user {}",
          serverName,
          serverUrl,
          ownerId,
          e);
      return "Error: could not connect to MCP server '" + serverName + "': " + e.getMessage();
    } finally {
      if (client != null) {
        try {
          client.close();
        } catch (Exception e) {
          log.warn("Failed to close validation MCP client for '{}'", serverName, e);
        }
      }
    }

    try {
      repo.save(config);
    } catch (Exception e) {
      log.error("Failed to persist MCP server '{}' for user {}", serverName, ownerId, e);
      return "Error: failed to save MCP server '" + serverName + "': " + e.getMessage();
    }
    log.info(
        "Registered MCP server '{}' ({}) for user {}",
        serverName,
        McpServerConfig.Transport.STREAMABLE_HTTP,
        ownerId);
    return "Successfully registered MCP server '"
        + serverName
        + "' ("
        + McpServerConfig.Transport.STREAMABLE_HTTP
        + "). Available tools: "
        + (toolNames.isEmpty() ? "(none)" : String.join(", ", toolNames));
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

    final var owned = repo.findByOwnerId(ownerId);
    final var identifiers = McpServerConfig.accessIdentifiers(ownerId, chatId);
    final var shared =
        repo.findBySharedWithIn(identifiers).stream()
            .filter(s -> !s.ownerId().equals(ownerId))
            .toList();

    final var configured = applicationConfigured();

    if (owned.isEmpty() && shared.isEmpty() && configured.isEmpty()) {
      return "No MCP servers registered or shared with you.";
    }

    final var sb = new StringBuilder();
    if (!owned.isEmpty()) {
      sb.append("Owned by you:\n");
      for (final var s : owned) {
        sb.append("- ")
            .append(s.name())
            .append(" [")
            .append(s.transport())
            .append("] ")
            .append(s.url())
            .append(s.enabled() ? "" : " (disabled)")
            .append(
                s.sharedWith() == null || s.sharedWith().isEmpty()
                    ? ""
                    : " (shared with: " + shareTargets(s.sharedWith()) + ")")
            .append("\n");
      }
    }
    if (!shared.isEmpty()) {
      sb.append("Shared with you:\n");
      for (final var s : shared) {
        sb.append("- ")
            .append(s.name())
            .append(" [")
            .append(s.transport())
            .append("] shared by ")
            .append(s.ownerId())
            .append(s.enabled() ? "" : " (disabled)")
            .append("\n");
      }
    }
    if (!configured.isEmpty()) {
      sb.append("Configured by this application, for everyone:\n");
      for (final var entry : configured.entrySet()) {
        sb.append("- ")
            .append(entry.getKey())
            .append(" [")
            .append(McpServerConfig.Transport.STREAMABLE_HTTP)
            .append("] ")
            .append(connectionUrl(entry.getValue()))
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
      return "Error: a server name is required.";
    }
    if (targetId == null || targetId.isBlank()) {
      return "Error: a target open_id or chat_id is required.";
    }
    final var serverName = name.trim();
    final var target = targetId.trim();

    if (isApplicationConfigured(serverName)) {
      return notYoursToManage(serverName, "shared");
    }
    final var config = repo.findByOwnerIdAndName(ownerId, serverName).orElse(null);
    if (config == null) {
      return "Error: no MCP server named '"
          + serverName
          + "' is registered to you. Only servers you own can be shared.";
    }
    var sharedWith = config.sharedWith();
    if (sharedWith == null) {
      sharedWith = new ArrayList<String>();
      config.sharedWith(sharedWith);
    }
    if (sharedWith.contains(target)) {
      return "'" + serverName + "' is already shared with " + target + ".";
    }
    sharedWith.add(target);
    repo.save(config);
    log.info("Shared MCP server '{}' with {} by owner {}", serverName, target, ownerId);
    return "Successfully shared '" + serverName + "' with " + target + ".";
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
      return "Error: a server name is required.";
    }
    if (targetId == null || targetId.isBlank()) {
      return "Error: a target open_id or chat_id is required.";
    }
    final var serverName = name.trim();
    final var target = targetId.trim();

    if (isApplicationConfigured(serverName)) {
      return notYoursToManage(serverName, "unshared");
    }
    final var config = repo.findByOwnerIdAndName(ownerId, serverName).orElse(null);
    if (config == null) {
      return "Error: no MCP server named '"
          + serverName
          + "' is registered to you. Only servers you own can be unshared.";
    }
    if (config.sharedWith() == null || !config.sharedWith().remove(target)) {
      return "'" + serverName + "' was not shared with " + target + ".";
    }
    repo.save(config);
    log.info("Unshared MCP server '{}' from {} by owner {}", serverName, target, ownerId);
    return "Successfully revoked access to '" + serverName + "' from " + target + ".";
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

  /**
   * The connections configured under {@code spring.ai.mcp.client.streamable-http}, keyed by name,
   * empty when none are or when MCP is disabled altogether.
   *
   * <p>Only that transport: stdio would launch a subprocess beside this application and SSE is
   * deprecated upstream, so neither is configured anywhere here — a deployment that configured one
   * regardless would not see it listed.
   */
  private Map<String, ConnectionParameters> applicationConfigured() {
    final var properties = streamableHttpProperties.getIfAvailable();
    return properties == null ? Map.of() : properties.getConnections();
  }

  private boolean isApplicationConfigured(final String name) {
    return applicationConfigured().containsKey(name);
  }

  /**
   * Where the connection's requests actually go. The endpoint is a path under the URL and defaults
   * to {@code /mcp} when unset, exactly as Spring AI's transport builds it.
   */
  private static String connectionUrl(final ConnectionParameters connection) {
    final var url = connection.url() == null ? "" : connection.url();
    final var endpoint = connection.endpoint() == null ? "/mcp" : connection.endpoint();
    return url + endpoint;
  }

  /**
   * What a mutating tool says about a name this application configures. Without it the answer is
   * that no such server is registered, which reads as "it does not exist" for a server whose tools
   * the model can see itself calling — and invites it to register one under the same name.
   */
  private static String notYoursToManage(final String name, final String verb) {
    return "Error: '"
        + name
        + "' is configured by this application, not registered by you. It is already available to"
        + " everyone here and cannot be "
        + verb
        + " through these tools.";
  }

  private static String blankToNull(final String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  @Tool(
      name = "RemoveMcpServer",
      description = "Remove a previously registered MCP server by name.")
  public String removeMcpServer(
      @ToolParam(description = "Name of the MCP server to remove") final String name,
      final ToolContext context) {
    final var ownerId = ToolContexts.require(context, ToolContexts.USER_ID);

    if (name == null || name.isBlank()) {
      return "Error: a server name is required.";
    }
    final var serverName = name.trim();
    if (isApplicationConfigured(serverName)) {
      return notYoursToManage(serverName, "removed");
    }
    if (!repo.existsByOwnerIdAndName(ownerId, serverName)) {
      return "Error: no MCP server named '" + serverName + "' is registered.";
    }
    repo.deleteByOwnerIdAndName(ownerId, serverName);
    log.info("Removed MCP server '{}' for user {}", serverName, ownerId);
    return "Successfully removed MCP server '" + serverName + "'.";
  }
}
