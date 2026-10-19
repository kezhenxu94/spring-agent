package me.kezhenxu94.springagent.provider.openai;

import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.usermodels.ProviderChatClients;
import me.kezhenxu94.springagent.core.usermodels.ReasoningEfforts;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.openai.responses.OpenAiResponsesChatModel;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

/**
 * {@link ProviderChatClients} over an OpenAI-compatible endpoint.
 *
 * <p>A client per endpoint rather than per user, and cached. Both halves are load-bearing.
 *
 * <p><b>Per endpoint</b>, because {@code OpenAiChatModel} resolves {@code baseUrl}, {@code apiKey}
 * and {@code timeout} once, in {@code build()}, into an {@code OpenAIClient} it then holds final.
 * Runtime options carrying a base URL are ignored — only the model name is read per request — so a
 * different endpoint is genuinely a different client and cannot be a different set of options.
 * Keying on the endpoint rather than the user also means two users pointing at the same gateway
 * share one connection pool.
 *
 * <p><b>Cached</b>, because building one opens an HTTP client. A bean per user would be worse
 * still: a prototype-scoped bean hands back a new instance on every lookup, which here is a new
 * connection pool for every message. The cache is bounded so that a table users can write to cannot
 * turn into unbounded sockets, and an entry is dropped on eviction rather than closed — {@code
 * OpenAiChatModel} does not expose the client it built, and OkHttp retires idle connections and its
 * dispatcher threads on its own once nothing references the pool.
 *
 * <p><b>One instance per {@link Api}</b>, each a provider of its own, so that a row chooses between
 * {@code /chat/completions} and {@code /responses} with the provider select every surface already
 * draws rather than with a field of its own. They are two protocols, and choosing one is exactly
 * what a row's provider says.
 *
 * <p>Editing a model needs no invalidation: the key contains the base URL, token, model name and
 * reasoning effort, so an edited endpoint is simply a key that is not in the cache, and the entry
 * it replaces ages out.
 */
@Slf4j
public class OpenAiUserChatClients implements ProviderChatClients {

  /** As Spring AI spells it under {@code spring.ai.model.*}, so there is one vocabulary. */
  public static final String PROVIDER = "openai";

  /**
   * The Responses API, which Spring AI selects with {@code spring.ai.openai.chat.api=responses}
   * under the {@code openai} provider. A row has no second field to say that in, so the protocol is
   * named as a provider of its own.
   */
  public static final String RESPONSES_PROVIDER = "openai-responses";

  /** Which of OpenAI's two chat endpoints a client built here speaks. */
  public enum Api {
    /**
     * {@code /chat/completions}, which every OpenAI-compatible gateway serves. The default, and the
     * only one most gateways implement.
     */
    CHAT_COMPLETIONS(PROVIDER),
    /**
     * {@code /responses}, the only endpoint on which OpenAI's GPT-5.4 and later models accept
     * function tools together with a reasoning effort — {@code /chat/completions} refuses the pair
     * with a 400 and asks for the effort to be {@code none}. Spoken statelessly: every call sends
     * the whole prompt, so chat memory and the tool advisor work as they do on the other.
     */
    RESPONSES(RESPONSES_PROVIDER);

    private final String provider;

    Api(final String provider) {
      this.provider = provider;
    }
  }

  private final Api api;

  /**
   * How long an endpoint nobody has used is kept. Long enough that a user's own model is not
   * rebuilt between messages, short enough that a token they revoked stops being held.
   */
  private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

  private final UserModelRegistry registry;

  /**
   * The application's endpoint as {@link ApplicationEndpoint} resolves it, rather than the options
   * the {@code OpenAiChatModel} bean holds: those carry no base URL, no key and no timeout at all,
   * and every client built here starts from them. See that class for what copying the bean's own
   * options silently costs.
   */
  private final OpenAiChatOptions defaults;

  /**
   * The runtime's own manager, which is what shapes the tool list every client built here offers
   * the model — see {@link #build}.
   */
  private final ToolCallingManager toolCallingManager;

  private final List<OpenAiHttpClientBuilderCustomizer> httpClientCustomizers;
  private final Cache<Endpoint, ChatClient> clients;

  public OpenAiUserChatClients(
      final Api api,
      final UserModelRegistry registry,
      final OpenAiChatOptions defaults,
      final ToolCallingManager toolCallingManager,
      final List<OpenAiHttpClientBuilderCustomizer> httpClientCustomizers,
      final int cacheSize) {
    this.api = api;
    this.registry = registry;
    this.defaults = defaults;
    this.toolCallingManager = toolCallingManager;
    this.httpClientCustomizers = httpClientCustomizers;
    this.clients =
        CacheBuilder.newBuilder().maximumSize(cacheSize).expireAfterAccess(IDLE_TIMEOUT).build();
  }

  /**
   * The client for one stored row, built on first use.
   *
   * <p>A row with no base URL is a model of the application's own that the user picked off the
   * list, not an endpoint of theirs: it borrows the configured base URL and key and changes only
   * the model asked for, or not even that. That is what keeps the application's credentials out of
   * the database while still letting somebody choose among the models it already pays for.
   */
  @Override
  public String provider() {
    return api.provider;
  }

  @Override
  public String configuredEffort() {
    return defaults.getReasoningEffort();
  }

  @Override
  public ChatClient clientFor(final UserModelConfig config) {
    // Borrowing is keyed on the row carrying no credential of its own, not on it carrying no base
    // URL. The two coincided while a deployment had one provider; once a row may name a different
    // one, a row that supplies a key and a model would have had that key ignored in favour of a
    // deployment credential belonging to another protocol.
    final var own = registry.tokenOf(config);
    final var builtin = Strings.isNullOrEmpty(config.baseUrl());
    return clientFor(
        new Endpoint(
            builtin ? defaults.getBaseUrl() : config.baseUrl(),
            Strings.isNullOrEmpty(own) ? defaults.getApiKey() : own,
            // A blank model is read the same way, and only ever happens on
            // UserModelRegistry.DEFAULT_ROW: a row that says how hard the application's model
            // should think without saying which model that is, so that the answer stays whatever
            // the deployment is configured with.
            Strings.isNullOrEmpty(config.model()) ? defaults.getModel() : config.model(),
            config.reasoningEffort(),
            config.temperature(),
            Boolean.TRUE.equals(config.strictTools()),
            registry.headersOf(config)));
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

  /**
   * A client for an endpoint that has not been stored yet, so that a registration can be tested
   * before its token is written anywhere.
   *
   * <p>Goes through the same cache as everything else: the key is the endpoint, and a probe that
   * succeeds is almost always followed by the user being switched onto exactly that endpoint, so
   * the client built here is the one their next run wants.
   *
   * @param token the plaintext token, since there is nothing sealed to open yet
   */
  @Override
  public ChatClient probeClient(
      final UserModelConfig config, final String token, final Map<String, String> headers) {
    return clientFor(
        new Endpoint(
            config.baseUrl(),
            token,
            config.model(),
            config.reasoningEffort(),
            config.temperature(),
            Boolean.TRUE.equals(config.strictTools()),
            headers == null ? Map.of() : headers));
  }

  /**
   * A client onto {@code endpoint}, wired like the application's own.
   *
   * <p>The options start as a <b>copy of the application's own resolved ones</b> and override only
   * what makes this a different endpoint: where it is, what it authenticates with, which model to
   * ask for, and how hard to think. That is not tidiness. Spring AI does not merge runtime options
   * with a model's defaults — {@code buildRequestPrompt} takes the supplied ones whole when there
   * are any — so options built from scratch here would quietly drop everything under {@code
   * spring.ai.openai.chat}: the temperature, the reasoning effort and the timeout, whose absence
   * shows up not as a startup failure but as one endpoint's runs behaving unlike every other run on
   * the same deployment.
   *
   * <p>The HTTP client customizers are the context's own for the same reason {@code
   * visionChatClient} takes them: built by hand, this model would otherwise be the one endpoint
   * whose rejections stay unreadable — and it is a gateway somebody typed a URL for, which is where
   * unreadable ones come from.
   *
   * <p><b>The tool-calling manager is the context's own, and it has to be handed over here.</b> A
   * {@code ToolCallingManager} does two jobs, and only one of them has moved to the advisor: {@code
   * executeToolCalls} is the advisor's, so tool interception, file references and the call limits
   * come along by themselves whatever client a run goes through — but {@code
   * resolveToolDefinitions} is still the chat model's, called from {@code
   * OpenAiChatModel.createRequest} to build the request's {@code tools} array. {@code
   * OpenAiChatModel.Builder} substitutes a plain default for a manager nobody set, so a model built
   * without one is offered to the endpoint with the runtime's own rewrites of the definition list
   * missing: no {@link me.kezhenxu94.springagent.core.tools.DisplayDescription} parameter, so no
   * call on a card or in the CLI has a title, and no localized tool and parameter descriptions.
   * Spring AI's own auto-configuration passes the bean into the model it builds, which is why the
   * application's client has never had this problem — and why a client built by hand has to do the
   * same. It costs nothing at run time: the same singleton, resolving the same callbacks.
   *
   * <p>The setter is deprecated for removal in Spring AI 3.0.0, and taken anyway: the deprecation
   * note is about <em>internal tool execution</em>, which really has moved to the advisor, while
   * the definition list has not — and there is no other way to reach it. Spring AI's own {@code
   * OpenAiChatAutoConfiguration} calls the same setter, so whatever it becomes when the removal
   * lands, this follows it. {@code OpenAiUserModelToolsTest} reads the tool list off the wire, so a
   * removal that changes where definitions come from fails there rather than silently dropping the
   * parameter again.
   */
  @SuppressWarnings("removal")
  private ChatClient build(final Endpoint endpoint) {
    log.info("Building a {} chat client for {} at {}", api, endpoint.model(), endpoint.baseUrl());
    final var chatModel =
        switch (api) {
          case CHAT_COMPLETIONS ->
              OpenAiChatModel.builder()
                  .options(optionsFor(defaults, endpoint))
                  .toolCallingManager(toolCallingManager)
                  .httpClientBuilderCustomizers(customizersFor(endpoint))
                  .build();
          // The same manager for the same reason: OpenAiResponsesChatModel builds the request's
          // tools array from it too, and substitutes a plain one for a manager nobody set.
          case RESPONSES ->
              OpenAiResponsesChatModel.builder()
                  .options(responsesOptionsFor(optionsFor(defaults, endpoint)))
                  .toolCallingManager(toolCallingManager)
                  .httpClientBuilderCustomizers(customizersFor(endpoint))
                  .build();
        };
    return ChatClient.builder(chatModel).build();
  }

  /**
   * The application's own HTTP client customizers, with one more appended where this endpoint
   * carries extra headers of its own — an OkHttp interceptor is the only hook {@code
   * OpenAiChatModel.Builder} exposes for them, since neither its options nor a raw {@code
   * OpenAiApi} builder take a header map directly. Endpoint-scoped rather than shared, because two
   * rows with different headers must not leak one row's headers onto the other's requests.
   */
  private List<OpenAiHttpClientBuilderCustomizer> customizersFor(final Endpoint endpoint) {
    if (endpoint.headers().isEmpty()) {
      return httpClientCustomizers;
    }
    final var combined = new ArrayList<>(httpClientCustomizers);
    combined.add(
        builder ->
            builder.interceptor(
                chain -> {
                  final var request = chain.request().newBuilder();
                  endpoint.headers().forEach(request::header);
                  return chain.proceed(request.build());
                }));
    return combined;
  }

  /**
   * The application's resolved options with the endpoint's own six fields over the top.
   *
   * <p>A method of its own so that the three states of a reasoning effort can be asserted: they are
   * the part of this class most easily broken by a change that looks harmless, and the difference
   * between them is invisible from outside a built client.
   */
  static OpenAiChatOptions optionsFor(final OpenAiChatOptions defaults, final Endpoint endpoint) {
    final var builder =
        defaults
            .mutate()
            .baseUrl(endpoint.baseUrl())
            .apiKey(endpoint.apiKey())
            .model(endpoint.model());
    // Left alone where the user chose nothing, so the application's own reasoning effort survives
    // the copy above; cleared for the sentinel, which is the only way to stop the parameter being
    // sent at all to a gateway that rejects it. See ReasoningEfforts for why those are two states.
    if (ReasoningEfforts.NOT_SENT.equals(endpoint.reasoningEffort())) {
      builder.reasoningEffort(null);
    } else if (endpoint.reasoningEffort() != null) {
      builder.reasoningEffort(endpoint.reasoningEffort());
    }
    // Left alone where the user chose nothing, the same as the effort: the application's own
    // temperature survives the copy.
    if (endpoint.temperature() != null) {
      builder.temperature(endpoint.temperature());
    }
    // Only ever switched on, never off: off is what Spring AI sends by default, and a deployment
    // that turned strict mode on for its own model should not lose it on a row that said nothing.
    // On, OpenAiChatModel both sends "strict": true and rewrites every tool schema to what strict
    // mode demands, which is the half that matters to an endpoint enforcing it regardless.
    if (endpoint.strictTools()) {
      builder.strict(true);
    }
    return builder.build();
  }

  /**
   * {@code options}, already resolved for one endpoint by {@link #optionsFor}, said in the
   * Responses API's options type, so that both APIs start from the same copy of the application's
   * settings and differ only in the endpoint they reach.
   *
   * <p>Copied field by field rather than through {@code combineWith}, which carries only the
   * portable options across the two types and drops the reasoning effort — the one setting this API
   * is chosen for. What is not copied has no counterpart on {@code /responses}: {@code n}, stop
   * sequences, penalties, {@code seed} and {@code logit_bias}, which Spring AI would otherwise drop
   * with a warning on every build.
   */
  static OpenAiResponsesChatOptions responsesOptionsFor(final OpenAiChatOptions options) {
    final var maxOutputTokens =
        options.getMaxCompletionTokens() != null
            ? options.getMaxCompletionTokens()
            : options.getMaxTokens();
    return OpenAiResponsesChatOptions.builder()
        .baseUrl(options.getBaseUrl())
        .apiKey(options.getApiKey())
        .credential(options.getCredential())
        .organizationId(options.getOrganizationId())
        .deploymentName(options.getMicrosoftDeploymentName())
        .microsoftFoundryServiceVersion(options.getMicrosoftFoundryServiceVersion())
        .microsoftFoundry(options.isMicrosoftFoundry())
        .timeout(options.getTimeout())
        .maxRetries(options.getMaxRetries())
        .proxy(options.getProxy())
        .customHeaders(options.getCustomHeaders())
        .model(options.getModel())
        .temperature(options.getTemperature())
        .topP(options.getTopP())
        .maxOutputTokens(maxOutputTokens)
        .reasoningEffort(options.getReasoningEffort())
        .strict(options.getStrict())
        .parallelToolCalls(options.getParallelToolCalls())
        .build();
  }

  /**
   * What makes two clients the same client. The token is part of it because rotating it has to
   * produce a new client rather than keep authenticating with the old one, and the reasoning
   * effort, the temperature and strict tools are because they live in the options a client is built
   * with: Spring AI takes supplied per-request options whole rather than merging them, so sending
   * it per request would mean building a set from scratch and losing everything else the
   * application configured.
   */
  record Endpoint(
      String baseUrl,
      String apiKey,
      String model,
      String reasoningEffort,
      Double temperature,
      boolean strictTools,
      Map<String, String> headers) {
    Endpoint {
      headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
  }
}
