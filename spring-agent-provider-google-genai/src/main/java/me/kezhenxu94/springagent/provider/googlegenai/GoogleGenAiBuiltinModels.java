package me.kezhenxu94.springagent.provider.googlegenai;

import com.google.common.base.Strings;
import com.google.genai.Client;
import com.google.genai.types.ListModelsConfig;
import com.google.genai.types.Model;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.usermodels.BuiltinModels;

/**
 * {@link BuiltinModels} over Gemini's model listing.
 *
 * <p>Easier than the OpenAI provider's, and for one reason worth recording: {@code GET /models}
 * there answers with ids and nothing else, so {@code OpenAiBuiltinModels} has to guess from the
 * name whether something is a chat model and carries a list of stems to do it with. Gemini says so
 * outright — {@link Model#supportedActions()} names {@code generateContent} on the models that can
 * hold a conversation — so the filter here is a fact rather than a heuristic.
 *
 * <p>Best-effort by contract, which this honours literally: every failure is an empty list and a
 * log line at info, never an exception. The caller then shows the single {@link #defaultModel()}
 * entry it would have shown anyway. A card that opens is worth more than a complete one that does
 * not, especially this card — it is what somebody reaches for when their chosen model has stopped
 * answering.
 */
@Slf4j
@RequiredArgsConstructor
public class GoogleGenAiBuiltinModels implements BuiltinModels {

  /** How long a listing is reused. The set of models an endpoint serves changes on release days. */
  private static final Duration TTL = Duration.ofMinutes(10);

  /**
   * What a model must say it can do to be offered as a chat model. The listing also carries
   * embedding models, and on a Vertex-backed gateway a good deal else.
   */
  private static final String GENERATE_CONTENT = "generateContent";

  /**
   * A ceiling on how much of a listing is read. {@code list} is paged and a gateway re-serving many
   * tuned models can page for a long time; a menu nobody can scroll is not worth the wait.
   */
  private static final int MAX_MODELS = 200;

  private final String apiKey;
  private final String defaultModel;

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
    if (Strings.isNullOrEmpty(apiKey)) {
      return List.of();
    }
    final var startedAt = System.nanoTime();
    try {
      final var client = Client.builder().apiKey(apiKey).build();
      final var names = new ArrayList<String>();
      var seen = 0;
      for (final var model : client.models.list(ListModelsConfig.builder().build())) {
        if (++seen > MAX_MODELS) {
          log.info("Stopped listing Gemini models at {}; the menu is long enough", MAX_MODELS);
          break;
        }
        if (!chatModel(model)) {
          continue;
        }
        model
            .name()
            .map(GoogleGenAiBuiltinModels::bareName)
            .filter(n -> !n.isBlank())
            .ifPresent(names::add);
      }
      final var models = names.stream().distinct().sorted().toList();
      log.info(
          "Gemini offers {} chat model(s) of {} listed, took {} ms",
          models.size(),
          seen,
          (System.nanoTime() - startedAt) / 1_000_000);
      log.debug("Gemini chat models: {}", models);
      return models;
    } catch (Exception e) {
      // Info rather than warn: an endpoint that will not enumerate is a normal thing for a gateway
      // to be, not a fault, and the caller has a working answer without this.
      log.info(
          "Could not list what the Gemini endpoint serves; offering only the configured model {}",
          defaultModel,
          e);
      return List.of();
    }
  }

  private static boolean chatModel(final Model model) {
    // A model that lists no actions at all is kept rather than dropped: some gateways fill none of
    // this in, and dropping everything would leave an empty menu where a listing did succeed.
    return model.supportedActions().map(actions -> actions.contains(GENERATE_CONTENT)).orElse(true);
  }

  /**
   * The name a person would type. The API answers {@code models/gemini-2.5-pro}, while everything
   * that takes a model name here — configuration, a stored row, the menu — spells it without the
   * prefix.
   */
  static String bareName(final String name) {
    final var slash = name.lastIndexOf('/');
    return slash < 0 ? name : name.substring(slash + 1);
  }

  private record Cached(List<String> models, Instant until) {}
}
