package me.kezhenxu94.springagent.core.usermodels;

import com.openai.client.OpenAIClient;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.openai.setup.OpenAiSetup;

/**
 * What the application's own endpoint says it can serve, as {@code GET {baseUrl}/models} answers
 * it.
 *
 * <p>So that choosing a model is a menu rather than a guess. Without this the only built-in choice
 * anyone can express is "whatever {@code OPENAI_MODEL} is set to", even where the same gateway
 * serves a dozen others that need no token of the user's own.
 *
 * <p>Best-effort by design, and that is the whole contract: plenty of OpenAI-compatible gateways do
 * not serve {@code /models} at all, and one that does may be slow or briefly down. Any failure is
 * an empty list, which the caller shows as the single built-in entry it would have shown anyway. A
 * card that opens is worth more than a complete one that does not, especially this card — it is
 * what somebody reaches for when their chosen model has stopped answering.
 *
 * <p>Cached for {@link #TTL}, because a card can be opened repeatedly and the answer changes about
 * as often as the gateway is redeployed.
 */
@Slf4j
public class BuiltinModels {

  private static final Duration TTL = Duration.ofMinutes(10);

  /** How long to wait for the listing before giving up and showing the single entry. */
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final OpenAiChatModel defaultChatModel;
  private final List<OpenAiHttpClientBuilderCustomizer> httpClientCustomizers;

  /**
   * Never null: {@code OpenAiSetup} dereferences it while building the client and fails with a bare
   * {@code NullPointerException: observationRegistry} rather than defaulting, so a deployment with
   * no registry of its own gets {@link ObservationRegistry#NOOP}.
   */
  private final ObservationRegistry observationRegistry;

  private final AtomicReference<Cached> cached = new AtomicReference<>();
  private final AtomicReference<OpenAIClient> client = new AtomicReference<>();

  public BuiltinModels(
      final OpenAiChatModel defaultChatModel,
      final List<OpenAiHttpClientBuilderCustomizer> httpClientCustomizers,
      final ObservationRegistry observationRegistry) {
    this.defaultChatModel = defaultChatModel;
    this.httpClientCustomizers = httpClientCustomizers;
    this.observationRegistry =
        observationRegistry == null ? ObservationRegistry.NOOP : observationRegistry;
  }

  /** The model the application is configured to use, which is what "the built-in model" means. */
  public String defaultModel() {
    return defaultChatModel.getOptions().getModel();
  }

  /**
   * Every model the application's endpoint offers, or an empty list where it will not say.
   *
   * <p>Sorted, so that a dropdown does not reorder itself between two openings of the same card
   * because the gateway returned its models in a different order.
   */
  public List<String> list() {
    final var current = cached.get();
    if (current != null && current.until().isAfter(Instant.now())) {
      return current.models();
    }
    final var fetched = fetch();
    cached.set(new Cached(fetched, Instant.now().plus(TTL)));
    return fetched;
  }

  private List<String> fetch() {
    try {
      final var models =
          client().models().list().data().stream()
              .map(model -> model.id())
              .filter(id -> id != null && !id.isBlank())
              .distinct()
              .sorted()
              .toList();
      log.info("The application endpoint offers {} model(s)", models.size());
      return models;
    } catch (Exception e) {
      // At info, not warn: an endpoint that does not serve /models is an ordinary configuration,
      // not a fault, and this runs every time somebody opens the card.
      log.info("Could not list the models the application endpoint offers: {}", e.toString());
      return List.of();
    }
  }

  /**
   * A client onto the application's own endpoint, built once.
   *
   * <p>Built here rather than taken from {@link OpenAiChatModel}, which keeps its own private and
   * exposes no accessor. The options it was built from are public, though, so this is the same
   * endpoint with the same credentials and the same HTTP customizers — only the timeout is this
   * class's own, since a listing that hangs must not hold a card open for the half-hour a reasoning
   * turn is allowed.
   */
  private OpenAIClient client() {
    final var existing = client.get();
    if (existing != null) {
      return existing;
    }
    final var options = defaultChatModel.getOptions();
    final var built =
        OpenAiSetup.setupSyncClient(
            options.getBaseUrl(),
            options.getApiKey(),
            options.getCredential(),
            options.getMicrosoftDeploymentName(),
            options.getMicrosoftFoundryServiceVersion(),
            options.getOrganizationId(),
            options.isMicrosoftFoundry(),
            options.isGitHubModels(),
            options.getModel(),
            TIMEOUT,
            options.getMaxRetries(),
            options.getProxy(),
            options.getCustomHeaders(),
            observationRegistry,
            // No meter registry: OpenAiSetup treats this one as optional, and a listing that runs
            // once per card is not worth a metric of its own.
            null,
            httpClientCustomizers);
    // Racing callers may each build one; only the first is kept.
    return client.compareAndSet(null, built) ? built : client.get();
  }

  private record Cached(List<String> models, Instant until) {}
}
