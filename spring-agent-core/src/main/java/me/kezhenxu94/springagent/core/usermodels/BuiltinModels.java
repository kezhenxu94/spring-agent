package me.kezhenxu94.springagent.core.usermodels;

import com.openai.client.OpenAIClient;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatOptions;
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

  /**
   * How a model id is cut into words — on anything that is not a letter or a digit, which covers
   * the hyphens, dots, underscores and the vendor prefix's slash that ids are built from. A stem is
   * then matched at the start of a word rather than anywhere in the id, so that a short one cannot
   * fire inside an unrelated name.
   */
  private static final Pattern SEGMENTS = Pattern.compile("[^a-z0-9]+");

  /**
   * What a segment starting with one of these says the model is for, and it is not chatting. Stems
   * rather than whole words so that a plural or a suffix is covered too ({@code embedding}, {@code
   * embeddings}, {@code reranker}). See {@link #chatModelsAmong} for why the list is short.
   */
  private static final List<String> NON_CHAT_STEMS =
      List.of(
          "embed",
          "rerank",
          "moderation",
          "whisper",
          "tts",
          "stt",
          "asr",
          "speech",
          "transcribe",
          "diffusion",
          "dall",
          "image",
          "video",
          "realtime",
          "bge",
          "gte",
          "m3e",
          "text2vec");

  /**
   * The application's endpoint as {@link ApplicationEndpoint} resolves it, rather than the options
   * the {@code OpenAiChatModel} bean holds: those carry no base URL and no key at all, which is
   * exactly the listing failure this class logs. See that class.
   */
  private final OpenAiChatOptions options;

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
      final OpenAiChatOptions options,
      final List<OpenAiHttpClientBuilderCustomizer> httpClientCustomizers,
      final ObservationRegistry observationRegistry) {
    this.options = options;
    this.httpClientCustomizers = httpClientCustomizers;
    this.observationRegistry =
        observationRegistry == null ? ObservationRegistry.NOOP : observationRegistry;
  }

  /** The model the application is configured to use, which is what "the built-in model" means. */
  public String defaultModel() {
    return options.getModel();
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
      final var offered =
          client().models().list().data().stream()
              .map(model -> model.id())
              .filter(id -> id != null && !id.isBlank())
              .distinct()
              .sorted()
              .toList();
      final var models = chatModelsAmong(offered);
      log.info(
          "The application endpoint offers {} model(s), {} of them usable for chat",
          offered.size(),
          models.size());
      return models;
    } catch (Exception e) {
      // At info, not warn: an endpoint that does not serve /models is an ordinary configuration,
      // not a fault, and this runs every time somebody opens the card.
      log.info("Could not list the models the application endpoint offers: {}", e.toString());
      return List.of();
    }
  }

  /**
   * The ones somebody could actually chat with, as far as a name can tell.
   *
   * <p>An endpoint that serves this application serves its embedding model too, and typically a
   * reranker, a speech model and an image model beside it. None of those can answer a chat
   * completion, so every one of them in the dropdown is a way to break your own runs — and, since
   * the surfaces cut the list short at a card's worth of options, one that pushes a real model off
   * the end of it.
   *
   * <p>Decided by name, because there is nothing else to decide it with: {@code GET /models}
   * answers with an id, a creation time and an owner, and the OpenAI API defines no field saying
   * what a model does. Gateways that add one all add a different one. So this is a guess, and it is
   * deliberately a conservative one — a stem here must be a word that never appears in a chat
   * model's name, which is why {@code vision}, {@code audio} and {@code ocr} are absent: those are
   * chat models on several gateways.
   *
   * <p>Being a guess is survivable in both directions. A non-chat model this fails to recognise is
   * an entry in the dropdown, as today. A chat model it wrongly drops is still reachable, by naming
   * it in the form's model field with no base URL and no token — the same way a model past the
   * card's cap is reached. And a listing that this filter would empty is returned whole: a gateway
   * whose every name looks non-chat to us is one we have understood nothing about, and an
   * unfiltered menu beats an empty one.
   */
  static List<String> chatModelsAmong(final List<String> offered) {
    final var chat = offered.stream().filter(BuiltinModels::looksLikeChatModel).toList();
    return chat.isEmpty() ? offered : chat;
  }

  private static boolean looksLikeChatModel(final String id) {
    for (final var segment : SEGMENTS.split(id.toLowerCase(Locale.ROOT))) {
      for (final var stem : NON_CHAT_STEMS) {
        if (segment.startsWith(stem)) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * A client onto the application's own endpoint, built once.
   *
   * <p>Built here rather than taken from {@code OpenAiChatModel}, which keeps its own private and
   * exposes no accessor. The endpoint it dials is resolved from the same configuration, though, so
   * this is the same gateway with the same credentials and the same HTTP customizers — only the
   * timeout is this class's own, since a listing that hangs must not hold a card open for the
   * half-hour a reasoning turn is allowed.
   */
  private OpenAIClient client() {
    final var existing = client.get();
    if (existing != null) {
      return existing;
    }
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
