package me.kezhenxu94.springagent.provider.googlegenai;

import java.util.LinkedHashMap;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

/**
 * Spreads the one Gemini credential across the settings that describe the same endpoint, so that a
 * deployment names it once.
 *
 * <p>Google issues a single API key and serves chat, embeddings and image generation off it. Spring
 * AI does not read it from a single place, though, and the difference is not visible from the yaml:
 *
 * <ul>
 *   <li>{@code GoogleGenAiConnectionProperties} (chat) binds {@code spring.ai.google.genai};
 *   <li>{@code GoogleGenAiImageConnectionProperties} binds {@code spring.ai.google.genai} too;
 *   <li>{@code GoogleGenAiTextEmbeddingProperties}' connection binds {@code
 *       spring.ai.google.genai.embedding} — <b>its own {@code api-key}</b>, which the one above
 *       does not reach.
 * </ul>
 *
 * <p>So a deployment that sets {@code spring.ai.google.genai.api-key} and nothing else gets working
 * chat and image models and an embedding connection that falls through to its Vertex AI branch and
 * fails startup with {@code Google GenAI project-id must be set!} — a message about a setting
 * nobody was asked for, naming neither the key that is missing nor embeddings. That is the whole of
 * what this class prevents.
 *
 * <p><b>Only a blank target is filled in</b>, which is the load-bearing part rather than
 * politeness, and the same lesson {@code DashScopeDefaults} records: contributing a
 * lowest-precedence property source would not work, because an {@code application.yaml} saying
 * {@code api-key: ${GEMINI_API_KEY:}} leaves the property present and empty, and present beats any
 * source added last. So each target is read as resolved and written only where nothing gave it a
 * real value, from a source added first. A deployment that points embeddings at a different project
 * or key still wins.
 *
 * <p>An {@code EnvironmentPostProcessor} rather than an auto-configuration because it has to run
 * before anything binds those properties, and {@link Ordered#LOWEST_PRECEDENCE} among them because
 * the values it reads to decide "already set" come from Boot's own config-data processing, which
 * has to have finished first.
 *
 * <p>Nothing here switches anything on. It fills in a credential, and only when there is one to
 * fill it in from. What selects this provider is {@code spring.ai.model.<kind>} naming {@code
 * google-genai} — see {@link GoogleGenAiProviderAutoConfiguration}.
 */
public class GoogleGenAiDefaults implements EnvironmentPostProcessor, Ordered {

  private static final String SOURCE_NAME = "googleGenAiDefaults";

  /**
   * The embedding connection's own credential, which is the only setting Spring AI does not resolve
   * from the common block. Listed as a constant so the test and this class name the same string.
   */
  static final String EMBEDDING_API_KEY = GoogleGenAiProperties.PREFIX + ".embedding.api-key";

  @Override
  public void postProcessEnvironment(
      final ConfigurableEnvironment environment, final SpringApplication application) {
    final var apiKey = environment.getProperty(GoogleGenAiProperties.API_KEY_PROPERTY);
    if (!GoogleGenAiProperties.configured(apiKey)) {
      return;
    }
    final var contributed = new LinkedHashMap<String, Object>();
    fill(environment, contributed, EMBEDDING_API_KEY, apiKey);
    if (!contributed.isEmpty()) {
      environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, contributed));
    }
  }

  /** Records {@code value} under {@code target} unless something already gave that a real value. */
  private static void fill(
      final Environment environment,
      final LinkedHashMap<String, Object> contributed,
      final String target,
      final String value) {
    if (GoogleGenAiProperties.configured(environment.getProperty(target))) {
      return;
    }
    contributed.put(target, value);
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
