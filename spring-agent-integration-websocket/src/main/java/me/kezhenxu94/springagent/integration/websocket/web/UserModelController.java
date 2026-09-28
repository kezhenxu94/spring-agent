package me.kezhenxu94.springagent.integration.websocket.web;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.usermodels.ReasoningEfforts;
import me.kezhenxu94.springagent.core.usermodels.UserChatClients;
import me.kezhenxu94.springagent.core.usermodels.UserModelProbe;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import me.kezhenxu94.springagent.integration.websocket.config.WebMessages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The chat models a person has brought of their own, without going through a run to manage them.
 *
 * <p>Everything here is something {@code UserModelTools} already does; the decisions are all {@link
 * UserModelRegistry}'s and {@link UserModelProbe}'s, and this is the half that speaks HTTP instead
 * of prose. The strongest case for that split, the same one {@code McpController} makes:
 * registering an endpoint means typing a base URL and an API token, and dictating a credential to a
 * model that will echo it into a transcript is not a reasonable way to configure anything.
 *
 * <p><b>Ownership is the authenticated principal's, never the request's.</b> There is no owner
 * parameter and no admin override — a row holds a token, so there is no view of anybody else's
 * worth the door it opens. There is no scope either: a chat model belongs to the person who
 * registered it, and there is no way to share one, unlike an MCP server.
 *
 * <p><b>The token, and every header value, never come back.</b> A response reports header
 * <em>names</em> only — enough to say a header is set and which one — the same rule {@code
 * McpController} states at length. Unlike a header on an MCP server, a chat model's token has no
 * tri-state "leave it alone": {@link UserModelRegistry#save} always reseals whatever token it is
 * given, so a save that means "change nothing about the credential" still has to repeat it —
 * exactly what {@code UserModelTools#addChatModel} already asks of a person re-registering a row
 * over chat.
 *
 * <p>Registered only where {@link UserModelRegistry} exists, which is only where {@code
 * app.ai.user-models.encryption-key} is set — see {@code UserModelsConfiguration}. Without a key
 * there is nowhere safe to put a token, and a page offering to register one would fail every save.
 */
@Slf4j
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
@ConditionalOnBean(UserModelRegistry.class)
public class UserModelController {

  /** The path segment meaning "switch back to the application's own model". */
  private static final String DEFAULT = "default";

  private final UserModelRegistry registry;
  private final UserModelProbe probe;
  private final UserChatClients chatClients;
  private final WebMessages messages;

  // ─────────────────────────────────────── reading ───────────────────────────────────────

  /**
   * Every model this person has registered. Flat, unlike {@code McpController}'s three groups: a
   * chat model is never shared and never configured by the deployment for everyone, so there is
   * only ever one store to read from.
   */
  @GetMapping
  public Map<String, Object> list(@AuthenticationPrincipal final OAuth2User principal) {
    final var user = ChatController.user(principal);
    final var active = registry.active(user.id()).map(UserModelConfig::name).orElse(null);
    final var models =
        registry.list(user.id()).stream().map(config -> asOwned(config, active)).toList();
    return Map.of("models", models);
  }

  // ─────────────────────────────────────── writing ───────────────────────────────────────

  /**
   * Registers an endpoint, or replaces one of the same name.
   *
   * <p>Slow on purpose, the same way {@code McpController#add} is: the endpoint is probed with the
   * token and headers given before anything is stored, so this is a request that takes as long as
   * the endpoint takes to answer.
   */
  @PostMapping
  public ResponseEntity<Map<String, Object>> add(
      @AuthenticationPrincipal final OAuth2User principal, @RequestBody final NewModel body) {
    final var user = ChatController.user(principal);
    final var name = trimmed(body == null ? null : body.name());
    final var model = trimmed(body == null ? null : body.model());
    final var apiToken = trimmed(body == null ? null : body.apiToken());
    final var baseUrl = trimmed(body == null ? null : body.baseUrl());
    final var provider = body == null ? null : body.provider();
    final var reasoningEffort = body == null ? null : body.reasoningEffort();
    final var headers = body == null ? null : body.headers();

    if (name.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, messages.get("model-name-required"));
    }
    if (model.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, messages.get("model-model-required"));
    }
    if (apiToken.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, messages.get("model-token-required"));
    }
    // Asked of the provider rather than always required: a protocol with one well-known endpoint —
    // Gemini's Developer API, Anthropic's own — has nothing to name, and refusing the request would
    // be refusing it for want of a field with no meaningful value.
    if (baseUrl.isEmpty() && chatClients.requiresBaseUrl(provider)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, messages.get("model-baseurl-required"));
    }
    if (reasoningEffort != null
        && !reasoningEffort.isBlank()
        && !ReasoningEfforts.valid(reasoningEffort)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          messages.get("model-bad-effort", reasoningEffort, ReasoningEfforts.listed()));
    }
    if (!UserModelRegistry.validName(name)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, messages.get("model-bad-name", name));
    }
    if (registry.full(user.id(), name)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, messages.get("model-too-many", registry.maxPerUser()));
    }
    // Checked before the probe, the same order UserModelTools keeps: a protocol nobody serves
    // cannot be tested, and the endpoint's silence would be reported as the base URL being wrong.
    if (provider != null
        && !provider.isBlank()
        && !chatClients.providers().contains(provider.trim())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          messages.get(
              "model-bad-provider", provider.trim(), String.join(", ", chatClients.providers())));
    }

    final var effort = ReasoningEfforts.normalize(reasoningEffort);
    final var resolvedBaseUrl = baseUrl.isEmpty() ? null : baseUrl;
    final var failure = probe.check(provider, resolvedBaseUrl, model, apiToken, effort, headers);
    if (failure != null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, messages.get("model-unreachable", name, failure));
    }

    final var saved =
        registry.save(user.id(), name, provider, resolvedBaseUrl, model, apiToken, effort, headers);
    log.info("{} registered the chat model {}", user.id(), name);
    final var active = registry.active(user.id()).map(UserModelConfig::name).orElse(null);
    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("model", asOwned(saved, active)));
  }

  /**
   * Switches this person's conversations to a registered model, or back to the application's own
   * where {@code name} is {@value #DEFAULT} — the same word {@code UseChatModel} takes over chat.
   */
  @PatchMapping("/{name}/activate")
  public Map<String, Object> activate(
      @AuthenticationPrincipal final OAuth2User principal, @PathVariable final String name) {
    final var user = ChatController.user(principal);
    if (DEFAULT.equalsIgnoreCase(name)) {
      registry.useDefault(user.id());
      log.info("{} switched to the application's own model", user.id());
      return Map.of("active", DEFAULT);
    }
    if (!registry.activate(user.id(), name)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, messages.get("model-unknown", name));
    }
    log.info("{} switched to the chat model {}", user.id(), name);
    return Map.of("active", name);
  }

  @DeleteMapping("/{name}")
  public ResponseEntity<Void> remove(
      @AuthenticationPrincipal final OAuth2User principal, @PathVariable final String name) {
    final var user = ChatController.user(principal);
    if (!registry.delete(user.id(), name)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, messages.get("model-unknown", name));
    }
    log.info("{} removed the chat model {}", user.id(), name);
    return ResponseEntity.noContent().build();
  }

  // ─────────────────────────────────────── the shared parts ───────────────────────────────

  /**
   * A model the caller owns, as the page reads it — everything but the token and the header values.
   * {@code headerNames} is sorted, so a row does not reorder itself between two reads of the same
   * model, the same reasoning {@code McpController#asOwned} states.
   */
  private static Owned asOwned(final UserModelConfig config, final String activeName) {
    return new Owned(
        config.name(),
        config.provider(),
        config.baseUrl(),
        config.model(),
        config.reasoningEffort(),
        config.name().equals(activeName),
        config.headerCiphers() == null
            ? List.of()
            : List.copyOf(new TreeSet<>(config.headerCiphers().keySet())),
        config.updatedAt() == null ? null : config.updatedAt().toString());
  }

  private static String trimmed(final String value) {
    return value == null ? "" : value.trim();
  }

  /**
   * What the page sends to register or replace a chat model.
   *
   * @param headers null keeps whatever headers are already stored, an empty map clears them, and
   *     anything else replaces the set wholesale — see {@link UserModelRegistry#save}. Unlike
   *     headers, the token carries no such tri-state: it is always required and always reseals the
   *     row, the same as re-registering one over chat.
   */
  public record NewModel(
      String name,
      String provider,
      String baseUrl,
      String model,
      String apiToken,
      String reasoningEffort,
      Map<String, String> headers) {}

  /**
   * A model the caller owns, on the wire. A record and not a map, for the reason {@code
   * McpController.Owned} gives at length: the token and the header values are exactly what this
   * type has no field for.
   *
   * @param headerNames which headers are set, never their values
   */
  public record Owned(
      String name,
      String provider,
      String baseUrl,
      String model,
      String reasoningEffort,
      boolean activated,
      List<String> headerNames,
      String updatedAt) {}
}
