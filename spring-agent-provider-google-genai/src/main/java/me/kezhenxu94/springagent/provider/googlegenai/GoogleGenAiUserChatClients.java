package me.kezhenxu94.springagent.provider.googlegenai;

import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.usermodels.ProviderChatClients;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;

/**
 * {@link ProviderChatClients} over Gemini, built the same way {@code OpenAiUserChatClients} is and
 * for the same reasons: a client per endpoint rather than per user, cached and bounded, because
 * building one opens an HTTP client and a table users can write to must not turn into unbounded
 * sockets.
 *
 * <p>One difference from that class is worth knowing. There, the connection lives in the chat
 * options, so every field of an endpoint is one options object. Here the credential and the base
 * URL belong to the {@link Client}, and only the model name and the thinking configuration are
 * options — so a client is built from two things, and the cache key has to cover both or two users
 * sharing a gateway at different thinking levels would share one client.
 *
 * <p>A base URL is optional and normally absent: the Gemini Developer API has one well-known
 * endpoint, and {@code HttpOptions.baseUrl} exists for the gateways that re-serve it. A row naming
 * none is a model of the application's own that the user picked off the list, borrowing the
 * deployment's own key.
 */
@Slf4j
public class GoogleGenAiUserChatClients implements ProviderChatClients {

  /** As Spring AI spells it under {@code spring.ai.model.*}, so there is one vocabulary. */
  public static final String PROVIDER = "google-genai";

  /**
   * How long an endpoint nobody has used is kept. Long enough that a user's own model is not
   * rebuilt between messages, short enough that a token they revoked stops being held.
   */
  private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

  private final UserModelRegistry registry;

  /** The application's own resolved options, which every client built here starts from. */
  private final GoogleGenAiChatOptions defaults;

  /**
   * The key the application authenticates with, lent to a row that names no endpoint of its own.
   */
  private final String defaultApiKey;

  /**
   * The runtime's own manager. Handed to every model built here for the reason {@link
   * UserChatClients} states: the advisor executes tool calls, but the tool <em>definitions</em> a
   * request carries are resolved by the chat model from the manager it was built with, so a model
   * built without it offers the endpoint a list none of the runtime's rewrites reached — no {@code
   * DisplayDescription}, so no tool call has a title on any surface, and no localized descriptions.
   *
   * <p>It is not wrapped for Gemini here, and does not need to be: {@code GoogleGenAiChatModel}'s
   * constructor wraps whatever manager it is handed in a {@code GoogleGenAiToolCallingManager}
   * unless it already is one, so the OpenAPI schema conversion Gemini needs is applied on top of
   * core's stack rather than instead of it.
   */
  private final ToolCallingManager toolCallingManager;

  private final Cache<Endpoint, ChatClient> clients;

  public GoogleGenAiUserChatClients(
      final UserModelRegistry registry,
      final GoogleGenAiChatOptions defaults,
      final String defaultApiKey,
      final ToolCallingManager toolCallingManager,
      final int cacheSize) {
    this.registry = registry;
    this.defaults = defaults;
    this.defaultApiKey = defaultApiKey;
    this.toolCallingManager = toolCallingManager;
    this.clients =
        CacheBuilder.newBuilder().maximumSize(cacheSize).expireAfterAccess(IDLE_TIMEOUT).build();
  }

  /**
   * The client for one stored row, built on first use.
   *
   * <p>A row with no base URL is a model of the application's own that the user picked off the
   * list, not an endpoint of theirs: it borrows the configured credential and changes only the
   * model asked for, or not even that. That is what keeps the application's key out of the database
   * while still letting somebody choose among the models it already pays for.
   */
  @Override
  public String provider() {
    return PROVIDER;
  }

  @Override
  public String configuredEffort() {
    return GoogleGenAiThinking.effortOf(defaults);
  }

  @Override
  public ChatClient clientFor(final UserModelConfig config) {
    final var builtin = Strings.isNullOrEmpty(config.baseUrl());
    return clientFor(
        new Endpoint(
            builtin ? null : config.baseUrl(),
            builtin ? defaultApiKey : registry.tokenOf(config),
            // A blank model only ever happens on UserModelRegistry.DEFAULT_ROW: a row saying how
            // hard the application's model should think without saying which model that is.
            Strings.isNullOrEmpty(config.model()) ? defaults.getModel() : config.model(),
            config.reasoningEffort()));
  }

  /**
   * A client for an endpoint that has not been stored yet, so a registration can be tested before
   * its token is written anywhere.
   *
   * @param token the plaintext token, since there is nothing sealed to open yet
   */
  @Override
  public ChatClient probeClient(final UserModelConfig config, final String token) {
    return clientFor(
        new Endpoint(
            Strings.emptyToNull(config.baseUrl()),
            token,
            Strings.isNullOrEmpty(config.model()) ? defaults.getModel() : config.model(),
            config.reasoningEffort()));
  }

  private ChatClient clientFor(final Endpoint endpoint) {
    var client = clients.getIfPresent(endpoint);
    if (client == null) {
      client = build(endpoint);
      // Racing callers may each build one; the loser's is discarded before it has been used.
      final var existing = clients.asMap().putIfAbsent(endpoint, client);
      if (existing != null) {
        client = existing;
      }
    }
    return client;
  }

  private ChatClient build(final Endpoint endpoint) {
    log.info(
        "Building a Gemini chat client for model={}, baseUrl={}, effort={}",
        endpoint.model(),
        endpoint.baseUrl() == null ? "<the Gemini Developer API>" : endpoint.baseUrl(),
        endpoint.reasoningEffort() == null ? "<as configured>" : endpoint.reasoningEffort());
    final var chatModel =
        GoogleGenAiChatModel.builder()
            .genAiClient(clientOnto(endpoint))
            .options(optionsFor(defaults, endpoint))
            .toolCallingManager(toolCallingManager)
            .build();
    return ChatClient.builder(chatModel).build();
  }

  private static Client clientOnto(final Endpoint endpoint) {
    final var builder = Client.builder().apiKey(endpoint.apiKey());
    if (!Strings.isNullOrEmpty(endpoint.baseUrl())) {
      builder.httpOptions(HttpOptions.builder().baseUrl(endpoint.baseUrl()).build());
    }
    return builder.build();
  }

  /**
   * The application's resolved options with the endpoint's model and thinking configuration over
   * the top.
   *
   * <p>A copy rather than a fresh object, and that is not tidiness: Spring AI does not merge
   * runtime options with a model's defaults, it takes the supplied ones whole, so options built
   * from scratch here would silently drop everything under {@code spring.ai.google.genai.chat} —
   * the temperature, the token limits, the safety settings — and show up as one endpoint's runs
   * behaving unlike every other run on the same deployment.
   *
   * <p>Package-private and static so the three states of a thinking configuration can be asserted
   * without building a client, which is where they become invisible.
   */
  static GoogleGenAiChatOptions optionsFor(
      final GoogleGenAiChatOptions defaults, final Endpoint endpoint) {
    final var builder = defaults.mutate().model(endpoint.model());
    GoogleGenAiThinking.apply(builder, endpoint.reasoningEffort());
    return builder.build();
  }

  /**
   * What makes two clients the same client.
   *
   * <p>The token is part of it because rotating one has to produce a new client rather than keep
   * authenticating with the old one, and the thinking configuration is because it lives in the
   * options a client is built with — Spring AI takes per-request options whole rather than merging
   * them, so sending it per request would mean building a set from scratch and losing everything
   * else the deployment configured.
   */
  record Endpoint(String baseUrl, String apiKey, String model, String reasoningEffort) {}
}
