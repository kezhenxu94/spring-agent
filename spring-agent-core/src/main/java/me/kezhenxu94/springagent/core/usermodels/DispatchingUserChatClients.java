package me.kezhenxu94.springagent.core.usermodels;

import com.google.common.base.Strings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import org.springframework.ai.chat.client.ChatClient;

/**
 * The one {@link UserChatClients} a deployment has, built over every {@link ProviderChatClients} on
 * its classpath and choosing between them per row.
 *
 * <p>This class is core's rather than a provider's because none of what it does is protocol work.
 * Resolving which row is active, falling back to the application's own client, and never throwing
 * were written twice — once per provider — and were identical both times; the only provider-shaped
 * part was building the client, which is what {@link ProviderChatClients} kept.
 *
 * <p><b>The default provider is the one that built the application's chat model</b>, and a row
 * whose {@code provider} is null means exactly that. Rows written before the field existed are
 * therefore unchanged, which is the whole reason null is the default rather than a literal name.
 *
 * <p>Never throws and never returns null from {@link #forUser}: a user whose stored endpoint cannot
 * be read — a rotated encryption key, a provider module that has since been dropped, a row naming a
 * protocol nobody serves — gets the application's model and a line in the log. Failing here would
 * fail the very run they would use to fix it, and this is the class most able to be handed a row
 * that no longer makes sense.
 */
@Slf4j
public class DispatchingUserChatClients implements UserChatClients {

  private final ChatClient defaultChatClient;
  private final UserModelRegistry registry;

  /** Keyed by {@link ProviderChatClients#provider()}, in the order the context published them. */
  private final Map<String, ProviderChatClients> byProvider;

  /**
   * Whose protocol a row that names none is spoken in.
   *
   * <p>Taken from {@code spring.ai.model.chat} rather than from the order the beans arrived in, and
   * that is load-bearing: a provider publishes its {@code ProviderChatClients} even when it did not
   * build the application's chat model — that is what lets somebody register an OpenAI endpoint on
   * a Gemini deployment — so bean order says nothing about which protocol the deployment itself
   * speaks. The property does, it is the same word the rows hold, and it is what the operator
   * already set.
   *
   * <p>Falls back to the first published where the property names a provider nobody serves, so a
   * deployment that never set it still works.
   */
  private final ProviderChatClients fallback;

  public DispatchingUserChatClients(
      final ChatClient defaultChatClient,
      final UserModelRegistry registry,
      final List<ProviderChatClients> providers,
      final String configuredProvider) {
    this.defaultChatClient = defaultChatClient;
    this.registry = registry;
    final var map = new LinkedHashMap<String, ProviderChatClients>();
    for (final var provider : providers) {
      final var previous = map.putIfAbsent(provider.provider(), provider);
      if (previous != null) {
        log.warn(
            "Two modules claim to serve user models for provider {}; keeping {} and ignoring {}",
            provider.provider(),
            previous.getClass().getSimpleName(),
            provider.getClass().getSimpleName());
      }
    }
    this.byProvider = Map.copyOf(map);
    final var configured = map.get(Strings.nullToEmpty(configuredProvider));
    this.fallback =
        configured != null ? configured : (providers.isEmpty() ? null : providers.get(0));
    if (configured == null && !Strings.isNullOrEmpty(configuredProvider) && !providers.isEmpty()) {
      log.warn(
          "spring.ai.model.chat names {}, which serves no user models here; rows naming no provider"
              + " will be spoken to as {}",
          configuredProvider,
          fallback.provider());
    }
    log.info(
        "Bring-your-own-model serves {}, and a row naming none is {}",
        byProvider.keySet(),
        fallback == null ? "<nothing>" : fallback.provider());
  }

  /** Which protocols a person may choose between, for a form to draw a select from. */
  public List<String> providers() {
    return List.copyOf(byProvider.keySet());
  }

  /** What a row naming no provider is spoken in, or null where nothing serves user models. */
  public String defaultProvider() {
    return fallback == null ? null : fallback.provider();
  }

  @Override
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

  @Override
  public String effortInForce(final String userId) {
    final var configured = fallback == null ? null : fallback.configuredEffort();
    if (Strings.isNullOrEmpty(userId)) {
      return configured;
    }
    try {
      final var active = registry.active(userId);
      if (active.isEmpty()) {
        return configured;
      }
      final var chosen = active.get().reasoningEffort();
      if (chosen == null) {
        // No effort of their own, so whatever the provider serving them is configured with — which
        // is not necessarily the deployment's own, once a row can name a different protocol.
        final var serving = resolve(active.get());
        return serving == null ? configured : serving.configuredEffort();
      }
      return ReasoningEfforts.NOT_SENT.equals(chosen) ? null : chosen;
    } catch (Exception e) {
      log.warn("Could not resolve the reasoning effort {} chose", userId, e);
      return configured;
    }
  }

  @Override
  public ChatClient clientFor(final UserModelConfig config) {
    final var provider = resolve(config);
    if (provider == null) {
      log.warn(
          "No provider module serves '{}', which model '{}' was registered for; falling back to the"
              + " application's own model",
          Strings.nullToEmpty(config.provider()),
          config.name());
      return defaultChatClient;
    }
    try {
      return provider.clientFor(config);
    } catch (Exception e) {
      log.warn(
          "The {} provider could not build a client for model '{}'; falling back to the"
              + " application's own",
          provider.provider(),
          config.name(),
          e);
      return defaultChatClient;
    }
  }

  /**
   * Unlike the rest, this one throws rather than falling back: a probe exists to tell somebody
   * whether what they typed works, and quietly testing a different endpoint would answer the wrong
   * question. {@code UserModelProbe} turns the failure into a message they can act on.
   */
  @Override
  public ChatClient probeClient(final UserModelConfig config, final String token) {
    final var provider = resolve(config);
    if (provider == null) {
      throw new IllegalArgumentException(
          "No provider serves '%s'. This deployment offers: %s"
              .formatted(Strings.nullToEmpty(config.provider()), providers()));
    }
    return provider.probeClient(config, token);
  }

  /** The module serving {@code config}, or null where the row names one nobody publishes. */
  private ProviderChatClients resolve(final UserModelConfig config) {
    if (config == null || Strings.isNullOrEmpty(config.provider())) {
      return fallback;
    }
    return byProvider.get(config.provider());
  }
}
