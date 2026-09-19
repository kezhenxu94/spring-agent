package me.kezhenxu94.springagent.core.tools.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.McpServerConfig;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import me.kezhenxu94.springagent.core.tools.mcp.McpRegistryException.Reason;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties.ConnectionParameters;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The MCP server registry itself: what is registered, and every change to it.
 *
 * <p>The half of {@link McpServerManagementTools} that is not about talking to a model, pulled out
 * because there are now two callers and only one of them answers in sentences: the tools, and the
 * browser's {@code McpController}. Registering a server is URL validation, a tool-prefix collision
 * check across everything the caller can reach, a live probe of the endpoint and only then a save —
 * four checks that are the whole of what makes a stored row trustworthy. Two copies of them is one
 * copy that will be left behind, which is the argument {@code TenantWrites} makes about a much
 * smaller decision. Exactly the split {@code core.skills.SkillFiles} and {@code
 * core.memory.MemoryStore} make for the other two things a person owns.
 *
 * <p>Nothing here is localized: a refusal is an {@link McpRegistryException} carrying a reason, and
 * what that reads like is the caller's to decide.
 *
 * <p><b>Ownership is the caller's to establish, never this class's to infer.</b> Every method takes
 * the owner id it is acting as, and every mutation is scoped to it — sharing a server grants its
 * tools and never its management. The id comes from the tool context on one side and from the
 * authenticated principal on the other, and this has no way to tell them apart, which is the point.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpServerRegistry {

  private final McpServerConfigRepo repo;
  private final McpClientFactory clientFactory;

  /**
   * The servers this application configures for everyone under {@code
   * spring.ai.mcp.client.streamable-http}, which reach a run through {@code AgentToolsProvider}
   * rather than through the repository. Known here because "what MCP servers do I have" means the
   * ones the agent can reach, not the ones a particular table happens to hold — and because every
   * mutation has to refuse a name this application already owns.
   *
   * <p>Through an {@link ObjectProvider} because the bean only exists while {@code
   * spring.ai.mcp.client.enabled} is true, and an application that turns MCP off entirely must
   * still get its registry.
   */
  private final ObjectProvider<McpStreamableHttpClientProperties> streamableHttpProperties;

  // ─────────────────────────────────────── reading ───────────────────────────────────────

  /** What this owner registered. */
  public List<McpServerConfig> owned(final String ownerId) {
    return repo.findByOwnerId(ownerId);
  }

  /**
   * What others have shared with this caller, directly, with a chat they are in, or with everyone.
   *
   * <p>The caller's own are filtered out rather than left in: a server shared with a group its
   * owner is also in would otherwise appear twice, once as theirs and once as somebody's gift to
   * them.
   */
  public List<McpServerConfig> sharedWith(final String ownerId, final String chatId) {
    final var identifiers = McpServerConfig.accessIdentifiers(ownerId, chatId);
    return repo.findBySharedWithIn(identifiers).stream()
        .filter(server -> !server.ownerId().equals(ownerId))
        .toList();
  }

  /**
   * The connections configured under {@code spring.ai.mcp.client.streamable-http}, by name, empty
   * when none are or when MCP is disabled altogether.
   *
   * <p>Only that transport: stdio would launch a subprocess beside this application and SSE is
   * deprecated upstream, so neither is configured anywhere here — a deployment that configured one
   * regardless would not see it listed.
   *
   * <p>The endpoint is a path under the URL and defaults to {@code /mcp} when unset, exactly as
   * Spring AI's transport builds it. Settled here so that no caller reassembles it.
   */
  public List<McpConfiguredServer> applicationConfigured() {
    final var properties = streamableHttpProperties.getIfAvailable();
    if (properties == null) {
      return List.of();
    }
    return properties.getConnections().entrySet().stream()
        .map(entry -> new McpConfiguredServer(entry.getKey(), connectionUrl(entry.getValue())))
        .toList();
  }

  public boolean isApplicationConfigured(final String name) {
    final var properties = streamableHttpProperties.getIfAvailable();
    return properties != null && properties.getConnections().containsKey(name);
  }

  private static String connectionUrl(final ConnectionParameters connection) {
    final var url = connection.url() == null ? "" : connection.url();
    final var endpoint = connection.endpoint() == null ? "/mcp" : connection.endpoint();
    return url + endpoint;
  }

  /** One server this owner registered, or empty. */
  public McpServerConfig find(final String ownerId, final String name) {
    return repo.findByOwnerIdAndName(ownerId, name).orElse(null);
  }

  // ─────────────────────────────────────── registering ───────────────────────────────────

  /**
   * Validates, probes and stores a server, answering with what it offers.
   *
   * <p>Re-registering a name overwrites that row, which is how a credential is rotated and a URL
   * corrected. Nothing is stored unless the endpoint answered: a row that was never reached is a
   * row whose tools a run will try to assemble and fail on, and the failure lands on a later
   * conversation rather than on whoever typed the URL.
   *
   * <p>{@code toolContext} is the run's, or empty for a caller that has none. It is passed to the
   * probe so that the same headers a run would send go out here, contributors included — a server
   * that only accepts a call once one is present must not be rejected at registration.
   */
  public McpRegistration register(
      final String ownerId,
      final String chatId,
      final McpServerSpec spec,
      final Map<String, Object> toolContext) {

    final var serverName = spec.name().trim();
    final var serverUrl = spec.url().trim();
    if (isApplicationConfigured(serverName)) {
      throw new McpRegistryException(Reason.APPLICATION_CONFIGURED, serverName);
    }

    try {
      clientFactory.validateRemoteUrl(serverUrl);
      McpClientFactory.validateToolPrefix(spec.toolPrefix());
    } catch (final IllegalArgumentException e) {
      throw new McpRegistryException(Reason.INVALID, e.getMessage());
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
            .headers(spec.headers() == null ? null : new LinkedHashMap<>(spec.headers()))
            .title(blankToNull(spec.title()))
            .version(blankToNull(spec.version()))
            .description(blankToNull(spec.description()))
            .websiteUrl(blankToNull(spec.websiteUrl()))
            .toolPrefix(blankToNull(spec.toolPrefix()))
            .enabled(true)
            .build();

    final var taken = prefixTakenBy(config, chatId);
    if (taken != null) {
      throw new McpRegistryException(
          Reason.PREFIX_TAKEN, McpClientFactory.toolPrefix(config), taken);
    }

    final List<String> toolNames;
    McpSyncClient client = null;
    try {
      client = clientFactory.createAndInitialize(config, toolContext);
      toolNames = client.listTools().tools().stream().map(tool -> tool.name()).toList();
    } catch (final IllegalArgumentException e) {
      throw new McpRegistryException(Reason.INVALID, e.getMessage());
    } catch (final Exception e) {
      log.warn(
          "Failed to connect to MCP server '{}' at {} for user {}",
          serverName,
          serverUrl,
          ownerId,
          e);
      throw new McpRegistryException(Reason.UNREACHABLE, serverName, e.getMessage());
    } finally {
      if (client != null) {
        try {
          client.close();
        } catch (final Exception e) {
          log.warn("Failed to close validation MCP client for '{}'", serverName, e);
        }
      }
    }

    try {
      repo.save(config);
    } catch (final Exception e) {
      log.error("Failed to persist MCP server '{}' for user {}", serverName, ownerId, e);
      throw new McpRegistryException(Reason.SAVE_FAILED, serverName, e.getMessage());
    }
    log.info(
        "Registered MCP server '{}' ({}) for user {}",
        serverName,
        McpServerConfig.Transport.STREAMABLE_HTTP,
        ownerId);
    return new McpRegistration(config, toolNames);
  }

  /**
   * The name of another server this caller can reach whose tools would be named the same as {@code
   * config}'s, or null when the prefix is free.
   *
   * <p>Checked against everything {@code findAccessibleTo} returns rather than only what the owner
   * registered, because that is the exact set a run assembles: a prefix free among a user's own
   * servers can still collide with one shared with them, and the collision costs the run every MCP
   * tool rather than the one call. Disabled servers count — a server is re-enabled far more easily
   * than a prefix is renamed once the model has been calling it.
   *
   * <p>Effective prefixes, from {@link McpClientFactory#toolPrefix}, so a chosen prefix that
   * happens to spell out another server's name hash is caught too.
   */
  private String prefixTakenBy(final McpServerConfig config, final String chatId) {
    final var prefix = McpClientFactory.toolPrefix(config);
    final var identifiers = McpServerConfig.accessIdentifiers(config.ownerId(), chatId);
    return repo.findAccessibleTo(config.ownerId(), identifiers).stream()
        // Re-adding a name overwrites that very row, so it is not a second server.
        .filter(
            other ->
                !(other.ownerId().equals(config.ownerId()) && other.name().equals(config.name())))
        .filter(other -> prefix.equals(McpClientFactory.toolPrefix(other)))
        .map(McpServerConfig::name)
        .findFirst()
        .orElse(null);
  }

  // ─────────────────────────────────────── changing one ───────────────────────────────────

  /** Grants the use of a server's tools to one open_id or chat_id, or to everyone. */
  public void share(final String ownerId, final String name, final String target) {
    final var config = mine(ownerId, name);
    var sharedWith = config.sharedWith();
    if (sharedWith == null) {
      sharedWith = new ArrayList<String>();
      config.sharedWith(sharedWith);
    }
    if (sharedWith.contains(target)) {
      throw new McpRegistryException(Reason.ALREADY_SHARED, name, target);
    }
    sharedWith.add(target);
    repo.save(config);
    log.info("Shared MCP server '{}' with {} by owner {}", name, target, ownerId);
  }

  /** Revokes one previously granted share. */
  public void unshare(final String ownerId, final String name, final String target) {
    final var config = mine(ownerId, name);
    if (config.sharedWith() == null || !config.sharedWith().remove(target)) {
      throw new McpRegistryException(Reason.NOT_SHARED, name, target);
    }
    repo.save(config);
    log.info("Unshared MCP server '{}' from {} by owner {}", name, target, ownerId);
  }

  /**
   * Turns a server's tools off without forgetting how to reach it.
   *
   * <p>New with the browser page: the field has always been stored and read — {@code
   * AgentToolsProvider} skips a disabled server — and nothing has ever been able to set it. Worth
   * having because the alternative to muting a server that is misbehaving is removing it, and
   * removing it throws away a URL, a prefix the model has learnt and a credential somebody has to
   * find again.
   */
  public McpServerConfig setEnabled(
      final String ownerId, final String name, final boolean enabled) {
    final var config = mine(ownerId, name);
    config.enabled(enabled);
    repo.save(config);
    log.info("{} MCP server '{}' for user {}", enabled ? "Enabled" : "Disabled", name, ownerId);
    return config;
  }

  /** Forgets a server. Its shares go with it — they are rows on the same record. */
  public void remove(final String ownerId, final String name) {
    mine(ownerId, name);
    repo.deleteByOwnerIdAndName(ownerId, name);
    log.info("Removed MCP server '{}' for user {}", name, ownerId);
  }

  /**
   * The server this caller owns under that name, or the refusal that says why there is none.
   *
   * <p>The application's own configuration is checked first and answered differently, because "no
   * such server" is a lie about a name whose tools the model can see: it would go and register one
   * of its own alongside, and then two things would answer to one name.
   */
  private McpServerConfig mine(final String ownerId, final String name) {
    if (isApplicationConfigured(name)) {
      throw new McpRegistryException(Reason.APPLICATION_CONFIGURED, name);
    }
    final var config = repo.findByOwnerIdAndName(ownerId, name).orElse(null);
    if (config == null) {
      throw new McpRegistryException(Reason.UNKNOWN, name);
    }
    return config;
  }

  private static String blankToNull(final String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
