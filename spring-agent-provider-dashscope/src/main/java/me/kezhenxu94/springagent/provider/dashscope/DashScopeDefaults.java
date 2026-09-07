package me.kezhenxu94.springagent.provider.dashscope;

import com.google.common.base.Strings;
import java.util.LinkedHashMap;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

/**
 * Spreads the one DashScope credential across the {@code spring.ai.openai.*} settings that describe
 * the same endpoint, so that a deployment names it once.
 *
 * <p>DashScope's chat and embedding endpoints <em>are</em> the OpenAI wire protocol — that is what
 * {@code compatible-mode/v1} means — so Spring AI's OpenAI client is the right client for them, and
 * this module's job for those two is to say where they are rather than to speak to them. What it
 * replaces is four copies of one secret: {@code spring.ai.openai.api-key}, {@code
 * spring.ai.openai.embedding.api-key} and a key each for the image and vision endpoints were all
 * the same string in every deployment of this project.
 *
 * <p><b>Only a blank target is filled in, and that is the load-bearing part.</b> Contributing as
 * the lowest-precedence property source — the usual way, and what {@code EventsDefaults} does —
 * would not work here: an application's {@code application.yaml} says {@code api-key:
 * ${OPENAI_API_KEY:}}, which is a value that is *present but empty* when nobody set the variable,
 * and present beats any source added last. So each target is read as resolved and written only
 * where nothing gave it a real value, from a source added first. The same trap {@code
 * ConditionalOnUserModels} exists for.
 *
 * <p>Consequently an explicit {@code OPENAI_API_KEY} still wins over {@code DASHSCOPE_API_KEY},
 * which is what a deployment moving one model at a time needs.
 *
 * <p>Nothing here switches anything on. It fills in settings, and only when {@code
 * spring.ai.dashscope.api-key} says there is a DashScope to fill them in from. What actually
 * selects this provider is Spring AI's own {@code spring.ai.model.image=dashscope}, and, for the
 * vision tool, {@code spring.ai.dashscope.vision.model} naming a model — see {@link
 * DashScopeProviderAutoConfiguration}.
 *
 * <p>Deliberately absent: the transcription endpoint, which DashScope serves but under model names
 * Spring AI's OpenAI defaults do not know, so filling in only its host would produce a client that
 * looks configured and asks for a model that is not there. And {@code app.ai.embedding.batch-size},
 * whose core default of 10 is already under DashScope's ceiling of {@value
 * DashScopeProperties#EMBEDDING_BATCH_SIZE} — see that constant for what happens above it.
 */
public class DashScopeDefaults implements EnvironmentPostProcessor, Ordered {

  private static final String SOURCE_NAME = "dashscopeDefaults";

  private static final String API_KEY = DashScopeProperties.PREFIX + ".api-key";
  private static final String BASE_URL = DashScopeProperties.PREFIX + ".base-url";
  private static final String CHAT_MODEL = DashScopeProperties.PREFIX + ".chat.model";
  private static final String CHAT_BASE_URL = DashScopeProperties.PREFIX + ".chat.base-url";
  private static final String EMBEDDING_BASE_URL =
      DashScopeProperties.PREFIX + ".embedding.base-url";
  private static final String EMBEDDING_MODEL = DashScopeProperties.PREFIX + ".embedding.model";
  private static final String EMBEDDING_DIMENSIONS =
      DashScopeProperties.PREFIX + ".embedding.dimensions";

  private static final String OPENAI = "spring.ai.openai";

  @Override
  public void postProcessEnvironment(
      final ConfigurableEnvironment environment, final SpringApplication application) {
    final var apiKey = environment.getProperty(API_KEY);
    if (Strings.isNullOrEmpty(apiKey)) {
      return;
    }
    // The host as configured, with the OpenAI-compatible path appended — the same derivation the
    // bound record does, from the one place that knows the path. This class runs before anything is
    // bound, so it cannot ask the record for it.
    //
    // Per model, because either may name a host of its own: `spring.ai.dashscope.chat.base-url` and
    // `.embedding.base-url` mean a host exactly as the common one does, and fall back to it.
    final var host = environment.getProperty(BASE_URL);
    final var chatUrl =
        DashScopeProperties.compatibleModeUrl(
            firstNonBlank(environment.getProperty(CHAT_BASE_URL), host));
    final var embeddingUrl =
        DashScopeProperties.compatibleModeUrl(
            firstNonBlank(environment.getProperty(EMBEDDING_BASE_URL), host));

    final var contributed = new LinkedHashMap<String, Object>();
    fill(environment, contributed, OPENAI + ".base-url", chatUrl);
    fill(environment, contributed, OPENAI + ".api-key", apiKey);
    fill(environment, contributed, OPENAI + ".chat.model", environment.getProperty(CHAT_MODEL));
    // Spelled out rather than left to inherit the connection above: Spring AI resolves an
    // embedding's base URL and key from its own block first and only then from the common one, and
    // a deployment that pointed embeddings somewhere else has a value here already — which the
    // blank check below leaves alone.
    fill(environment, contributed, OPENAI + ".embedding.base-url", embeddingUrl);
    fill(environment, contributed, OPENAI + ".embedding.api-key", apiKey);
    fill(
        environment,
        contributed,
        OPENAI + ".embedding.model",
        environment.getProperty(EMBEDDING_MODEL));
    fill(
        environment,
        contributed,
        OPENAI + ".embedding.dimensions",
        environment.getProperty(EMBEDDING_DIMENSIONS));

    if (!contributed.isEmpty()) {
      environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, contributed));
    }
  }

  private static String firstNonBlank(final String value, final String fallback) {
    return Strings.isNullOrEmpty(value) ? fallback : value;
  }

  /** Records {@code value} under {@code target} unless something already gave that a real value. */
  private static void fill(
      final Environment environment,
      final LinkedHashMap<String, Object> contributed,
      final String target,
      final String value) {
    if (Strings.isNullOrEmpty(value)) {
      return;
    }
    if (!Strings.isNullOrEmpty(environment.getProperty(target))) {
      return;
    }
    contributed.put(target, value);
  }

  /**
   * Before Boot's own config-data processing has finished is too early — the {@code
   * application.yaml} values this class reads to decide "already set" have to be there — so this
   * runs last among the post-processors rather than first.
   */
  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
