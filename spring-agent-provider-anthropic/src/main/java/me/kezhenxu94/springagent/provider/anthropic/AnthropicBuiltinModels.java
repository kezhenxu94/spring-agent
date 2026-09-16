package me.kezhenxu94.springagent.provider.anthropic;

import com.google.common.base.Strings;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.usermodels.BuiltinModels;
import org.springframework.ai.anthropic.AnthropicSetup;

/**
 * {@link BuiltinModels} over Anthropic's model listing.
 *
 * <p>The easiest of the three, because {@code GET /v1/models} answers with ids and display names
 * and Anthropic serves nothing but chat models from it — so there is neither the OpenAI provider's
 * heuristic over model-name stems nor Gemini's filter on supported actions. Everything listed is
 * offerable.
 *
 * <p><b>Nothing is listed on the Vertex backend, and no request is made to find that out.</b>
 * {@code VertexBackend} rewrites a request's path into Vertex's {@code publishers/anthropic/models}
 * shape and accepts only the message-sending services, refusing anything else outright with {@code
 * "Service is not supported for Vertex: models"}. A listing call there is a guaranteed exception
 * every time the cache expires, which is litter rather than best effort — so the backend is asked
 * first, and the caller falls back to showing the configured model alone. Whoever is running Claude
 * through a GCP project knows which models that project has enabled; this listing could not tell
 * them anyway.
 *
 * <p>Best-effort by contract, which this honours literally: every failure is an empty list and a
 * log line at info, never an exception. A card that opens is worth more than a complete one that
 * does not, especially this card — it is what somebody reaches for when their chosen model has
 * stopped answering.
 */
@Slf4j
@RequiredArgsConstructor
public class AnthropicBuiltinModels implements BuiltinModels {

  /** How long a listing is reused. The set of models an endpoint serves changes on release days. */
  private static final Duration TTL = Duration.ofMinutes(10);

  /** What a listing call is given before it is abandoned; this is a menu, not a run. */
  private static final Duration LIST_TIMEOUT = Duration.ofSeconds(5);

  /**
   * A ceiling on how much of a listing is read. {@code list} is paged, and a gateway re-serving
   * many models can page for a long time; a menu nobody can scroll is not worth the wait.
   */
  private static final int MAX_MODELS = 200;

  private final String baseUrl;
  private final String apiKey;
  private final String defaultModel;

  /** Whether this deployment is served by Vertex, in which case there is nothing to ask. */
  private final boolean vertexBacked;

  private final AtomicReference<Cached> cached = new AtomicReference<>();

  @Override
  public String defaultModel() {
    return defaultModel;
  }

  @Override
  public List<String> list() {
    final var now = Instant.now();
    final var current = cached.get();
    if (current != null && now.isBefore(current.until())) {
      return current.models();
    }
    final var fetched = fetch();
    cached.set(new Cached(fetched, now.plus(TTL)));
    return fetched;
  }

  private List<String> fetch() {
    if (vertexBacked) {
      log.debug(
          "Not listing models: Vertex serves no model listing, so only the configured model {} is"
              + " offered",
          defaultModel);
      return List.of();
    }
    if (Strings.isNullOrEmpty(apiKey)) {
      return List.of();
    }
    final var startedAt = System.nanoTime();
    try {
      final var client =
          AnthropicSetup.setupSyncClient(
              baseUrl, apiKey, LIST_TIMEOUT, 0, null, java.util.Map.of());
      final var ids = new ArrayList<String>();
      var seen = 0;
      for (final var model : client.models().list().autoPager()) {
        if (++seen > MAX_MODELS) {
          log.info("Stopped listing Claude models at {}; the menu is long enough", MAX_MODELS);
          break;
        }
        if (!Strings.isNullOrEmpty(model.id())) {
          ids.add(model.id());
        }
      }
      final var models = ids.stream().distinct().sorted().toList();
      log.info(
          "Anthropic offers {} chat model(s), took {} ms",
          models.size(),
          (System.nanoTime() - startedAt) / 1_000_000);
      log.debug("Claude models: {}", models);
      return models;
    } catch (Exception e) {
      // Info rather than warn: an endpoint that will not enumerate is a normal thing for a gateway
      // to be, not a fault, and the caller has a working answer without this.
      log.info(
          "Could not list what the Claude endpoint serves; offering only the configured model {}",
          defaultModel,
          e);
      return List.of();
    }
  }

  private record Cached(List<String> models, Instant until) {}
}
