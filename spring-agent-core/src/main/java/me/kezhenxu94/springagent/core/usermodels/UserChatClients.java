package me.kezhenxu94.springagent.core.usermodels;

import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;

/**
 * Which {@link ChatClient} a run goes through: the user's own where they have chosen one, the
 * application's otherwise.
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
 * <p>Editing a model needs no invalidation: the key contains the base URL, token and model name, so
 * an edited endpoint is simply a key that is not in the cache, and the entry it replaces ages out.
 */
@Slf4j
public class UserChatClients {

  /**
   * How long an endpoint nobody has used is kept. Long enough that a user's own model is not
   * rebuilt between messages, short enough that a token they revoked stops being held.
   */
  private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

  private final ChatClient defaultChatClient;
  private final UserModelRegistry registry;
  private final OpenAiChatModel defaultChatModel;
  private final List<OpenAiHttpClientBuilderCustomizer> httpClientCustomizers;
  private final Cache<Endpoint, ChatClient> clients;

  public UserChatClients(
      final ChatClient defaultChatClient,
      final UserModelRegistry registry,
      final OpenAiChatModel defaultChatModel,
      final List<OpenAiHttpClientBuilderCustomizer> httpClientCustomizers,
      final int cacheSize) {
    this.defaultChatClient = defaultChatClient;
    this.registry = registry;
    this.defaultChatModel = defaultChatModel;
    this.httpClientCustomizers = httpClientCustomizers;
    this.clients =
        CacheBuilder.newBuilder().maximumSize(cacheSize).expireAfterAccess(IDLE_TIMEOUT).build();
  }

  /**
   * The client {@code userId}'s runs should go through.
   *
   * <p>Never throws and never returns null. A user whose stored endpoint cannot be read — a rotated
   * encryption key, a row written by a different deployment — gets the application's model and a
   * line in the log, because failing here would fail the very run they would use to fix it.
   */
  public ChatClient forUser(final String userId) {
    if (Strings.isNullOrEmpty(userId)) {
      return defaultChatClient;
    }
    try {
      final var active = registry.active(userId);
      if (active.isEmpty()) {
        return defaultChatClient;
      }
      return clientFor(active.get());
    } catch (Exception e) {
      log.warn(
          "Could not resolve the chat model {} chose; falling back to the application's own",
          userId,
          e);
      return defaultChatClient;
    }
  }

  /**
   * The client for one stored row, built on first use.
   *
   * <p>A row with no base URL is a model of the application's own that the user picked off the
   * list, not an endpoint of theirs: it borrows the configured base URL and key and changes only
   * the model asked for. That is what keeps the application's credentials out of the database while
   * still letting somebody choose among the models it already pays for.
   */
  public ChatClient clientFor(final UserModelConfig config) {
    final var defaults = defaultChatModel.getOptions();
    final var builtin = Strings.isNullOrEmpty(config.baseUrl());
    return clientFor(
        new Endpoint(
            builtin ? defaults.getBaseUrl() : config.baseUrl(),
            builtin ? defaults.getApiKey() : registry.tokenOf(config),
            config.model()));
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
  public ChatClient probeClient(final UserModelConfig config, final String token) {
    return clientFor(new Endpoint(config.baseUrl(), token, config.model()));
  }

  /**
   * A client onto {@code endpoint}, wired like the application's own.
   *
   * <p>The options start as a <b>copy of the application's own resolved ones</b> and override only
   * the three fields that make this a different endpoint. That is not tidiness. Spring AI does not
   * merge runtime options with a model's defaults — {@code buildRequestPrompt} takes the supplied
   * ones whole when there are any — so options built from scratch here would quietly drop
   * everything under {@code spring.ai.openai.chat}: the temperature, the reasoning effort, the
   * timeout, and {@code stream-options.include-usage}, whose absence shows up not as an error but
   * as runs that report no token usage and therefore no cost.
   *
   * <p>The HTTP client customizers are the context's own for the same reason {@code
   * visionChatClient} takes them: built by hand, this model would otherwise be the one endpoint
   * whose rejections stay unreadable — and it is a gateway somebody typed a URL for, which is where
   * unreadable ones come from.
   *
   * <p>Nothing here wires a tool-calling manager, and that is correct rather than an omission.
   * Tools are called by the {@code ToolCallingAdvisor} that {@link
   * me.kezhenxu94.springagent.core.agent.SpringAgent} registers on the prompt, which is shared by
   * every run whatever client it goes through, so tool interception and localization come along on
   * their own. The model-level manager is the superseded path.
   */
  private ChatClient build(final Endpoint endpoint) {
    log.info("Building a chat client for {} at {}", endpoint.model(), endpoint.baseUrl());
    final var options =
        defaultChatModel
            .getOptions()
            .mutate()
            .baseUrl(endpoint.baseUrl())
            .apiKey(endpoint.apiKey())
            .model(endpoint.model())
            .build();
    final var chatModel =
        OpenAiChatModel.builder()
            .options(options)
            .httpClientBuilderCustomizers(httpClientCustomizers)
            .build();
    return ChatClient.builder(chatModel).build();
  }

  /**
   * What makes two clients the same client. The token is part of it because rotating it has to
   * produce a new client rather than keep authenticating with the old one.
   */
  private record Endpoint(String baseUrl, String apiKey, String model) {}
}
