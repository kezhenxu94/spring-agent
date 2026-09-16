package me.kezhenxu94.springagent.provider.anthropic;

import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.usermodels.ProviderChatClients;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.http.okhttp.AnthropicHttpClientBuilderCustomizer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link ProviderChatClients} over Anthropic's own API, built the same way {@code
 * OpenAiUserChatClients} and {@code GoogleGenAiUserChatClients} are and for the same reasons: a
 * client per endpoint rather than per user, cached and bounded, because building one opens an HTTP
 * client and a table users can write to must not turn into unbounded sockets.
 *
 * <p><b>Anthropic's API only, never Vertex, and that boundary is the point rather than a gap.</b>
 * Bring-your-own-model is somebody pasting a URL and a token into a chat; Vertex is a GCP project,
 * a region and a Google credential, which is not a thing a person holds in that form and not a
 * thing this deployment should store per user. A deployment whose own chat model is Vertex-backed
 * still serves every ordinary run from it — this class only ever answers for a row somebody
 * registered. That is why a client built here always speaks to Anthropic's own API rather than
 * through {@link VertexAnthropicClients}, and why it is registered whether or not this module built
 * the application's chat model.
 *
 * <p>A base URL is optional and normally absent: Anthropic has one well-known host, and a value
 * here is for a gateway re-serving the protocol. A row naming none is a model of the application's
 * own that the user picked off a list.
 */
@Slf4j
public class AnthropicUserChatClients implements ProviderChatClients {

  /** As Spring AI spells it under {@code spring.ai.model.*}, so there is one vocabulary. */
  public static final String PROVIDER = "anthropic";

  /**
   * How long an endpoint nobody has used is kept. Long enough that a user's own model is not
   * rebuilt between messages, short enough that a token they revoked stops being held.
   */
  private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

  /**
   * What a client built here waits for one request, and how often it retries. Not read from {@code
   * spring.ai.anthropic.*}: those settings belong to the deployment's own endpoint, and this class
   * has to work on a deployment whose chat model is somebody else's entirely — the contract {@link
   * ProviderChatClients} states. Generous rather than tuned, since a user's own gateway is the case
   * this exists for.
   */
  private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);

  private static final int MAX_RETRIES = 2;

  private final UserModelRegistry registry;

  /**
   * The application's own resolved options, which every client built here starts from — or null on
   * a deployment where this module built no chat model, in which case a row is served from this
   * provider's own defaults.
   */
  private final AnthropicChatOptions defaults;

  /**
   * The application's own chat model, or null where this module did not build it.
   *
   * <p><b>What a row with no credential borrows.</b> Not the deployment's API key, which was the
   * first design and was wrong: a key only exists on the {@code anthropic} backend, so on a
   * Vertex-backed deployment there was nothing to lend and the commonest row of all — {@code
   * UserModelRegistry.DEFAULT_ROW}, written whenever somebody picks a reasoning effort for the
   * application's own model — could not be served. The dispatcher caught the refusal and fell back,
   * so runs kept working; but the effort the person chose was dropped on every one of them, and
   * each logged a stack trace saying so.
   *
   * <p>Borrowing the built clients instead is right on both backends and cheaper on each: the
   * connection is already open, already carries the deployment's error-body interceptor, and on
   * Vertex already holds a Google credential no row could have supplied. Only the options differ,
   * which is the whole of what such a row asks for.
   */
  private final ObjectProvider<AnthropicChatModel> applicationModel;

  /**
   * The runtime's own manager. Handed to every model built here for the reason {@code
   * UserChatClients} states: the advisor executes tool calls, but the tool <em>definitions</em> a
   * request carries are resolved by the chat model from the manager it was built with, so a model
   * built without it offers the endpoint a list none of the runtime's rewrites reached — no {@code
   * DisplayDescription}, so no tool call has a title on any surface, and no localized descriptions.
   */
  private final ToolCallingManager toolCallingManager;

  /**
   * The deployment's own error-body interceptors, so a user's endpoint refusing a request logs the
   * same way the application's own does. Passed rather than pre-applied because {@code
   * AnthropicChatModel.Builder} refuses customizers beside a pre-built client, which is why this
   * class builds no client of its own.
   */
  private final List<AnthropicHttpClientBuilderCustomizer> customizers;

  private final Cache<Endpoint, ChatClient> clients;

  public AnthropicUserChatClients(
      final UserModelRegistry registry,
      final AnthropicChatOptions defaults,
      final ObjectProvider<AnthropicChatModel> applicationModel,
      final ToolCallingManager toolCallingManager,
      final List<AnthropicHttpClientBuilderCustomizer> customizers,
      final int cacheSize) {
    this.registry = registry;
    this.defaults = defaults;
    this.applicationModel = applicationModel;
    this.toolCallingManager = toolCallingManager;
    this.customizers = customizers == null ? List.of() : List.copyOf(customizers);
    this.clients =
        CacheBuilder.newBuilder().maximumSize(cacheSize).expireAfterAccess(IDLE_TIMEOUT).build();
  }

  @Override
  public String provider() {
    return PROVIDER;
  }

  @Override
  public String configuredEffort() {
    return AnthropicThinking.effortOf(defaults);
  }

  @Override
  public ChatClient clientFor(final UserModelConfig config) {
    return clientFor(endpointFor(registry, defaults, config));
  }

  /**
   * Which endpoint a stored row names, as its four fields.
   *
   * <p>Package-private and static so the rule can be asserted without building a client, which is
   * where it becomes invisible.
   *
   * <p><b>Borrowing is keyed on the row carrying no credential, not on it carrying no base URL</b>,
   * for the reason {@code GoogleGenAiUserChatClients} documents at length: Anthropic has a single
   * well-known host, so an ordinary row here is a key and a model and no URL at all, and reading
   * that as "the application's own endpoint" would ignore the key the person typed.
   *
   * <p>A row carrying no credential <em>and</em> reaching a deployment that borrowed nothing to
   * lend — this module serving user models beside somebody else's chat model, or a Vertex-backed
   * deployment, which holds a Google credential rather than an Anthropic key — is the case {@link
   * #clientFor(Endpoint)} refuses, so that {@code DispatchingUserChatClients} falls back to the
   * application's own client and says why.
   */
  static Endpoint endpointFor(
      final UserModelRegistry registry,
      final AnthropicChatOptions defaults,
      final UserModelConfig config) {
    final var own = registry.tokenOf(config);
    return new Endpoint(
        // Blank is the normal case and means Anthropic's own host; a value here is an override, for
        // a gateway re-serving the protocol.
        Strings.emptyToNull(config.baseUrl()),
        // Null where the row carries none, which is what marks it as borrowing the application's
        // own clients rather than opening a connection of its own.
        Strings.emptyToNull(own),
        // A blank model only ever happens on DEFAULT_ROW: a row saying how hard the application's
        // model should think without saying which model that is.
        Strings.isNullOrEmpty(config.model())
            ? (defaults == null ? null : defaults.getModel())
            : config.model(),
        config.reasoningEffort());
  }

  /**
   * False: Anthropic has one well-known host, so there is nothing for a person to type and asking
   * would be asking them to invent something.
   */
  @Override
  public boolean requiresBaseUrl() {
    return false;
  }

  @Override
  public ChatClient probeClient(final UserModelConfig config, final String token) {
    return clientFor(
        new Endpoint(
            Strings.emptyToNull(config.baseUrl()),
            token,
            Strings.isNullOrEmpty(config.model())
                ? (defaults == null ? null : defaults.getModel())
                : config.model(),
            config.reasoningEffort()));
  }

  private ChatClient clientFor(final Endpoint endpoint) {
    if (Strings.isNullOrEmpty(endpoint.apiKey()) && applicationModel.getIfAvailable() == null) {
      // Nothing to authenticate with and nothing to borrow: this module built no chat model here,
      // so the deployment's own credential belongs to another provider entirely. Throwing is the
      // contract — the dispatcher turns it into the application's own client and a warning — and is
      // much better than building a client that 401s on every message.
      throw new IllegalStateException(
          "This row has no Anthropic API key of its own, and this deployment serves chat from"
              + " another provider, so there is nothing to lend it. Register the endpoint with a"
              + " key.");
    }
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
        "Building an Anthropic chat client for model={}, baseUrl={}, effort={}",
        endpoint.model(),
        endpoint.baseUrl() == null ? "<Anthropic's own API>" : endpoint.baseUrl(),
        endpoint.reasoningEffort() == null ? "<as configured>" : endpoint.reasoningEffort());
    final var borrowed = applicationModel.getIfAvailable();
    if (Strings.isNullOrEmpty(endpoint.apiKey())) {
      // A row with nothing of its own: the application's own model, with this row's options over
      // the top. Its two clients are reused rather than rebuilt, which is what makes this work on
      // the Vertex backend — where the credential is a Google one that no row could carry — and
      // what keeps one connection pool rather than one per effort somebody picks.
      //
      // No httpClientBuilderCustomizers here: the builder refuses them beside pre-built clients,
      // and those clients already carry the deployment's interceptors.
      return ChatClient.builder(
              AnthropicChatModel.builder()
                  .anthropicClient(borrowed.getAnthropicClient())
                  // Both, for the reason VertexAnthropicClients sets out at length: one alone
                  // leaves streaming to be rebuilt from the options, which here carry no
                  // credential.
                  .anthropicClientAsync(borrowed.getAnthropicClientAsync())
                  .options(optionsFor(defaults, endpoint))
                  .toolCallingManager(toolCallingManager)
                  .build())
          .build();
    }

    // An endpoint of the person's own. No pre-built client here, deliberately, and this is the
    // opposite choice from VertexAnthropicClients: AnthropicChatOptions carries the connection —
    // apiKey, baseUrl, timeout, maxRetries — and AnthropicChatModel builds *both* its clients from
    // it, so putting the endpoint in the options is what keeps the streaming client pointed at the
    // same place as the blocking one.
    final var chatModel =
        AnthropicChatModel.builder()
            .options(optionsFor(defaults, endpoint))
            .toolCallingManager(toolCallingManager)
            .httpClientBuilderCustomizers(customizers)
            .build();
    return ChatClient.builder(chatModel).build();
  }

  /**
   * The application's resolved options with the endpoint's model and thinking configuration over
   * the top.
   *
   * <p>A copy rather than a fresh object, and that is not tidiness: Spring AI does not merge
   * runtime options with a model's defaults, it takes the supplied ones whole, so options built
   * from scratch here would silently drop everything under {@code spring.ai.anthropic.chat} — the
   * token limit, the temperature, the cache strategy — and show up as one endpoint's runs behaving
   * unlike every other run on the same deployment.
   *
   * <p>Where this module built no chat model there is nothing to copy, and a fresh set is right:
   * the deployment configured nothing here to preserve.
   *
   * <p>Package-private and static so the states of a thinking configuration can be asserted without
   * building a client, which is where they become invisible.
   */
  static AnthropicChatOptions optionsFor(
      final AnthropicChatOptions defaults, final Endpoint endpoint) {
    final var builder = defaults == null ? AnthropicChatOptions.builder() : defaults.mutate();
    if (!Strings.isNullOrEmpty(endpoint.model())) {
      builder.model(endpoint.model());
    }
    // The connection, which is what makes this a client for somebody else's endpoint rather than a
    // second client for the deployment's own. Only where the row has one: a borrowed row reuses the
    // application's already-built clients, and stamping a blank key into the options there would
    // make AnthropicChatModel rebuild them from nothing.
    if (!Strings.isNullOrEmpty(endpoint.apiKey())) {
      builder.apiKey(endpoint.apiKey());
      if (!Strings.isNullOrEmpty(endpoint.baseUrl())) {
        builder.baseUrl(endpoint.baseUrl());
      }
      builder.timeout(REQUEST_TIMEOUT).maxRetries(MAX_RETRIES);
    }
    AnthropicThinking.apply(builder, endpoint.reasoningEffort());
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
