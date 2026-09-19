package me.kezhenxu94.springagent.integration.websocket.web;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.McpServerConfig;
import me.kezhenxu94.springagent.core.tools.mcp.McpClientFactory;
import me.kezhenxu94.springagent.core.tools.mcp.McpRegistration;
import me.kezhenxu94.springagent.core.tools.mcp.McpRegistryException;
import me.kezhenxu94.springagent.core.tools.mcp.McpServerRegistry;
import me.kezhenxu94.springagent.core.tools.mcp.McpServerSpec;
import me.kezhenxu94.springagent.integration.websocket.config.WebMessages;
import me.kezhenxu94.springagent.integration.websocket.security.WebUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The MCP servers a person has, without going through the model to reach them.
 *
 * <p>The third of the things {@code SkillController} and {@code MemoryController} make available
 * this way, and the one with the strongest case: registering a server means typing a URL and a
 * bearer token, and dictating a credential to a model that will echo it into a transcript is not a
 * reasonable way to configure anything. Everything here is something {@code
 * McpServerManagementTools} already does; the decisions are all {@link McpServerRegistry}'s, and
 * this is the half that speaks HTTP instead of prose.
 *
 * <p><b>Ownership is the authenticated principal's, never the request's.</b> There is no owner
 * parameter, and an administrator reaches nothing extra here — unlike the knowledge base, where an
 * admin may read somebody else's. A server row holds a credential, so there is no view of anybody
 * else's worth the door it opens. There is no scope either: an MCP server belongs to the person who
 * registered it, and the way it reaches anybody else is a share.
 *
 * <p><b>Headers never come back.</b> A row's headers are exactly where the token lives, and the
 * list this page draws is readable by anything that can reach the session. So a response carries
 * their <em>names</em> only, which is enough to say the authentication is set and which header
 * carries it, and a save that leaves the field out keeps what is stored — an empty object is how
 * the page clears it. Getting this backwards would make {@code GET /api/mcp} the shortest path
 * between a stolen session and every token the person has pasted in.
 *
 * <p>Three kinds of server are reported and only one of them is the caller's. What others have
 * shared carries a name and an owner and no connection detail, which is the rule {@code
 * ListMcpServers} already keeps: a share grants the use of a server's tools, never its
 * configuration. What this application configures under {@code spring.ai.mcp.client.*} is reported
 * because it is part of what the agent can reach, and is read-only because no user owns it.
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
public class McpController {

  private final McpServerRegistry registry;
  private final WebMessages messages;

  // ─────────────────────────────────────── reading ───────────────────────────────────────

  @GetMapping
  public Map<String, Object> list(@AuthenticationPrincipal final OAuth2User principal) {
    final var user = ChatController.user(principal);

    final var owned = registry.owned(user.id()).stream().map(McpController::asOwned).toList();
    // No chat id: a browser session is not in a Feishu chat, so only servers shared with this
    // person directly or with everyone reach them here. A server shared with a group they are in
    // is still theirs to use in that group, and saying so on a page that cannot show the group
    // would be describing an access this session does not have.
    final var shared =
        registry.sharedWith(user.id(), null).stream().map(McpController::asShared).toList();
    final var configured =
        registry.applicationConfigured().stream()
            .map(
                server ->
                    new Configured(
                        server.name(),
                        server.url(),
                        McpServerConfig.Transport.STREAMABLE_HTTP.name()))
            .toList();
    return Map.of("owned", owned, "shared", shared, "configured", configured);
  }

  // ─────────────────────────────────────── writing ───────────────────────────────────────

  /**
   * Registers a server, or replaces one of the same name.
   *
   * <p>Slow on purpose: the registry probes the endpoint and lists its tools before storing
   * anything, so this is a request that takes as long as the server takes to answer. The page draws
   * a waiting state for it, and the answer carries the tool names — which is the only evidence
   * anybody gets that the credential they pasted is the right one.
   *
   * <p>Overwriting by name is how a token is rotated and a URL corrected, and is what the tool does
   * too. It is not a hazard the way overwriting a file would be: the row is entirely re-stated by
   * the request, and the page fills the form from what is already there.
   */
  @PostMapping
  public ResponseEntity<Map<String, Object>> add(
      @AuthenticationPrincipal final OAuth2User principal, @RequestBody final NewServer body) {

    final var user = ChatController.user(principal);
    final var name = trimmed(body == null ? null : body.name());
    final var url = trimmed(body == null ? null : body.url());
    if (name.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, messages.get("mcp-name-required"));
    }
    if (url.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, messages.get("mcp-url-required"));
    }

    final McpRegistration registered =
        guarding(
            () ->
                registry.register(
                    user.id(),
                    null,
                    new McpServerSpec(
                        name,
                        url,
                        headersFor(user, name, body.headers()),
                        body.title(),
                        body.version(),
                        body.description(),
                        body.websiteUrl(),
                        body.toolPrefix()),
                    Map.of()));

    log.info("{} registered the MCP server {}", user.id(), name);
    // The tool names go beside the server rather than on it: they are what the probe found at one
    // moment, and the listing has no business carrying a copy that ages. Owned, not Registered
    // with a tools field, so that the one shape the page draws a row from stays one shape.
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Map.of("server", asOwned(registered.config()), "tools", registered.toolNames()));
  }

  /**
   * The headers to store, given what the request said about them.
   *
   * <p>Null means "leave what is there", which is what the page sends when nobody touched the field
   * — it never had the values to send back, because {@link #asOwned} does not hand them out. An
   * empty map means "clear them", which somebody has to be able to ask for. Anything else replaces
   * them wholesale.
   *
   * <p>Without the first of those three, opening a server to change its URL and pressing save would
   * quietly drop its authentication, and the next run would get a 401 from a server that had been
   * working — with nothing on the page having said so.
   */
  private Map<String, String> headersFor(
      final WebUser user, final String name, final Map<String, String> sent) {
    if (sent != null) {
      return sent;
    }
    final var existing = registry.find(user.id(), name);
    return existing == null ? null : existing.headers();
  }

  @PostMapping("/share")
  public ResponseEntity<Void> share(
      @AuthenticationPrincipal final OAuth2User principal, @RequestBody final Share body) {

    final var user = ChatController.user(principal);
    final var name = required(body == null ? null : body.name(), "mcp-name-required");
    final var target = required(body == null ? null : body.target(), "mcp-target-required");

    guarding(() -> registry.share(user.id(), name, target));
    log.info("{} shared the MCP server {} with {}", user.id(), name, target);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/share")
  public ResponseEntity<Void> unshare(
      @AuthenticationPrincipal final OAuth2User principal,
      @RequestParam("name") final String name,
      @RequestParam("target") final String target) {

    final var user = ChatController.user(principal);
    final var server = required(name, "mcp-name-required");
    final var revoked = required(target, "mcp-target-required");

    guarding(() -> registry.unshare(user.id(), server, revoked));
    log.info("{} revoked the MCP server {} from {}", user.id(), server, revoked);
    return ResponseEntity.noContent().build();
  }

  /**
   * Turns a server's tools off without forgetting how to reach it.
   *
   * <p>The one operation on this page that the chat tools cannot do at all — the field has always
   * been stored and honoured and nothing has ever set it. Worth having because the alternative to
   * muting a server that is misbehaving is removing it, which throws away a URL, a prefix the model
   * has learnt, and a credential somebody has to go and find again.
   */
  @PatchMapping("/enabled")
  public Map<String, Object> enabled(
      @AuthenticationPrincipal final OAuth2User principal, @RequestBody final Enabled body) {

    final var user = ChatController.user(principal);
    final var name = required(body == null ? null : body.name(), "mcp-name-required");
    final var wanted = body.enabled() != null && body.enabled();

    final var server = guarding(() -> registry.setEnabled(user.id(), name, wanted));
    log.info("{} {} the MCP server {}", user.id(), wanted ? "enabled" : "disabled", name);
    return Map.of("server", asOwned(server));
  }

  @DeleteMapping
  public ResponseEntity<Void> remove(
      @AuthenticationPrincipal final OAuth2User principal,
      @RequestParam("name") final String name) {

    final var user = ChatController.user(principal);
    final var server = required(name, "mcp-name-required");

    guarding(() -> registry.remove(user.id(), server));
    log.info("{} removed the MCP server {}", user.id(), server);
    return ResponseEntity.noContent().build();
  }

  // ─────────────────────────────────────── the shared parts ───────────────────────────────

  /**
   * A server the caller owns, as the page reads it — everything but the secrets.
   *
   * <p>{@code toolPrefix} is the <em>effective</em> one, from {@link McpClientFactory#toolPrefix},
   * not the stored field. The stored field is blank far more often than not, and what a person
   * needs to see is the prefix their tools actually wear — which when nothing was chosen is a hash
   * nothing else would ever tell them. {@code headerNames} is sorted, so a row does not reorder
   * itself between two reads of the same server.
   */
  private static Owned asOwned(final McpServerConfig server) {
    return new Owned(
        server.name(),
        server.url(),
        server.transport() == null ? null : server.transport().name(),
        server.title(),
        server.version(),
        server.description(),
        server.websiteUrl(),
        McpClientFactory.toolPrefix(server),
        server.toolPrefix() != null && !server.toolPrefix().isBlank(),
        server.enabled(),
        server.headers() == null
            ? List.of()
            : List.copyOf(new TreeSet<>(server.headers().keySet())),
        server.sharedWith() == null ? List.of() : List.copyOf(server.sharedWith()));
  }

  /**
   * A server somebody else shared, as the caller may see it: that it exists, and who from.
   *
   * <p>No URL, no headers, no prefix, no share list. A share grants the use of the tools and
   * nothing about the server itself — the rule {@code ListMcpServers} states, kept here because a
   * page is exactly where it would be easiest to leak by drawing one row template for both.
   */
  private static Shared asShared(final McpServerConfig server) {
    return new Shared(
        server.name(),
        server.transport() == null ? null : server.transport().name(),
        server.ownerId(),
        server.enabled());
  }

  /**
   * Turns a registry refusal into a status and a sentence.
   *
   * <p>The split is what the person can do next. A URL this runtime will not accept, a prefix
   * already taken and a missing field are all 400 — something in the form is wrong and the form is
   * where it gets fixed. A name that is not theirs is 404, and one this application configures is
   * 409: it exists, it is simply not a row anybody owns. A server that did not answer is 502,
   * because nothing here was wrong and something out there was.
   */
  private <T> T guarding(final java.util.function.Supplier<T> work) {
    try {
      return work.get();
    } catch (final McpRegistryException e) {
      final var arguments = e.arguments();
      throw switch (e.reason()) {
        case INVALID ->
            new ResponseStatusException(
                HttpStatus.BAD_REQUEST, messages.get("mcp-invalid", arguments), e);
        case PREFIX_TAKEN ->
            new ResponseStatusException(
                HttpStatus.BAD_REQUEST, messages.get("mcp-prefix-taken", arguments), e);
        case UNREACHABLE ->
            new ResponseStatusException(
                HttpStatus.BAD_GATEWAY, messages.get("mcp-unreachable", arguments), e);
        case SAVE_FAILED ->
            new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, messages.get("mcp-save-failed", arguments), e);
        case UNKNOWN ->
            new ResponseStatusException(
                HttpStatus.NOT_FOUND, messages.get("mcp-unknown", arguments), e);
        case APPLICATION_CONFIGURED ->
            new ResponseStatusException(
                HttpStatus.CONFLICT, messages.get("mcp-application-configured", arguments), e);
        case ALREADY_SHARED ->
            new ResponseStatusException(
                HttpStatus.CONFLICT, messages.get("mcp-already-shared", arguments), e);
        case NOT_SHARED ->
            new ResponseStatusException(
                HttpStatus.NOT_FOUND, messages.get("mcp-not-shared", arguments), e);
      };
    }
  }

  private void guarding(final Runnable work) {
    guarding(
        () -> {
          work.run();
          return null;
        });
  }

  private String required(final String value, final String key) {
    final var trimmed = trimmed(value);
    if (trimmed.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, messages.get(key));
    }
    return trimmed;
  }

  private static String trimmed(final String value) {
    return value == null ? "" : value.trim();
  }

  /**
   * What the page sends to register or replace a server.
   *
   * @param headers null leaves the stored ones alone, empty clears them — see {@code headersFor}
   */
  public record NewServer(
      String name,
      String url,
      Map<String, String> headers,
      String title,
      String version,
      String description,
      String websiteUrl,
      String toolPrefix) {}

  /**
   * @param target an open_id, a chat_id, or {@code *} for everyone
   */
  public record Share(String name, String target) {}

  public record Enabled(String name, Boolean enabled) {}

  /**
   * A server the caller owns, on the wire.
   *
   * <p>A record and not a map, because <b>what is not here is the point</b>: the headers hold the
   * bearer token, and this type has no field that could carry one. A map built with {@code put}
   * calls documents that rule in a comment and lets one more line break it; a record makes adding
   * the field the deliberate act it should be, and makes the whole contract visible in one place
   * for whoever is reading the page's code beside it.
   *
   * @param toolPrefix the effective prefix, derived from the name where none was chosen
   * @param toolPrefixChosen whether that prefix is somebody's choice rather than a hash of the name
   * @param headerNames which headers are set, never their values
   * @param sharedWith open_ids, chat_ids, and {@code *} for everyone
   */
  public record Owned(
      String name,
      String url,
      String transport,
      String title,
      String version,
      String description,
      String websiteUrl,
      String toolPrefix,
      boolean toolPrefixChosen,
      boolean enabled,
      List<String> headerNames,
      List<String> sharedWith) {}

  /** A server somebody else shared, on the wire. Its configuration is its owner's alone. */
  public record Shared(String name, String transport, String owner, boolean enabled) {}

  /** A server this deployment configures for everyone, on the wire. Nobody owns it. */
  public record Configured(String name, String url, String transport) {}
}
