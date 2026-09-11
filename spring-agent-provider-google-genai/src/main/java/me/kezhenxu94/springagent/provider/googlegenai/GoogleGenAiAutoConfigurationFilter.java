package me.kezhenxu94.springagent.provider.googlegenai;

import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * Keeps Spring AI's Google GenAI auto-configurations out of a deployment that has no Gemini
 * credential, so that carrying this module costs nothing until somebody configures it.
 *
 * <p>Without this, adding the module to an application breaks every existing deployment on the
 * spot. Two of the five auto-configurations it brings — {@code
 * GoogleGenAiImageConnectionAutoConfiguration} and {@code
 * GoogleGenAiEmbeddingConnectionAutoConfiguration} — carry no {@code spring.ai.model.*} condition
 * at all, only {@code @ConditionalOnClass}, and their {@code @Bean} methods throw when neither an
 * API key nor a project and location are set:
 *
 * <pre>
 *   Incomplete Google GenAI configuration: Provide 'api-key' for Gemini API
 *   or 'project-id' and 'location' for Vertex AI.
 * </pre>
 *
 * <p>They are eager singletons, so that is a startup failure, not a lazy one — and it happens to a
 * deployment that never asked for Gemini and only ever wanted the OpenAI provider that was already
 * on its classpath. The other three are gated on {@code spring.ai.model.<kind>} and so are harmless
 * alone, but they are filtered with the rest: keeping them would leave a context where the chat
 * model is present and the connection details it needs are not, which fails later and less clearly.
 *
 * <p><b>An import filter rather than a condition</b>, because a condition cannot help here: the
 * beans that throw belong to Spring AI's classes and are annotated
 * {@code @ConditionalOnMissingBean}, not with anything this module can satisfy. {@link
 * AutoConfigurationImportFilter} is Boot's own extension point for exactly this — its own {@code
 * OnClassCondition} is one — and it runs over the candidate list before any of them is evaluated.
 * The alternative, contributing {@code spring.autoconfigure.exclude}, would silently fight a
 * deployment that sets that property itself.
 *
 * <p>The check is on a <em>non-blank</em> value, not on presence, for the reason core's {@code
 * ConditionalOnNonBlankProperty} exists: every {@code application.yaml} here spells the setting
 * {@code ${GEMINI_API_KEY:}}, so an unset variable leaves the property present and empty. Treating
 * that as configured is how the whole class of bug this guards against gets in.
 *
 * <p>Filtering everything out is not the same as saying nothing. A deployment that names {@code
 * google-genai} under {@code spring.ai.model.*} and sets no key would otherwise start with no model
 * at all and fail somewhere unrelated; {@link GoogleGenAiConnectionCheck} is what turns that into a
 * startup error naming both the variable and the switch.
 */
@Slf4j
public class GoogleGenAiAutoConfigurationFilter
    implements AutoConfigurationImportFilter, EnvironmentAware {

  /**
   * Named as strings rather than as classes, and that is deliberate: naming the class would load
   * it, and the point is to keep it from being loaded. It also means this module does not fail to
   * start if Spring AI renames one — it silently stops filtering instead, which is why {@code
   * GoogleGenAiAutoConfigurationFilterTest} asserts against the real names on the classpath.
   */
  static final Set<String> FILTERED =
      Set.of(
          "org.springframework.ai.model.google.genai.autoconfigure.chat"
              + ".GoogleGenAiChatAutoConfiguration",
          "org.springframework.ai.model.google.genai.autoconfigure.embedding"
              + ".GoogleGenAiEmbeddingConnectionAutoConfiguration",
          "org.springframework.ai.model.google.genai.autoconfigure.embedding"
              + ".GoogleGenAiTextEmbeddingAutoConfiguration",
          "org.springframework.ai.model.google.genai.autoconfigure.image"
              + ".GoogleGenAiImageConnectionAutoConfiguration",
          "org.springframework.ai.model.google.genai.autoconfigure.image"
              + ".GoogleGenAiImageAutoConfiguration");

  private Environment environment;

  @Override
  public void setEnvironment(final Environment environment) {
    this.environment = environment;
  }

  @Override
  public boolean[] match(
      final String[] autoConfigurationClasses, final AutoConfigurationMetadata metadata) {
    final var configured = configured();
    final var matches = new boolean[autoConfigurationClasses.length];
    var filtered = 0;
    for (var i = 0; i < autoConfigurationClasses.length; i++) {
      final var candidate = autoConfigurationClasses[i];
      // Boot pads the candidate array with nulls as filters remove entries, so a null here means
      // "already dropped by somebody else" rather than anything about this module.
      final var ours = candidate != null && FILTERED.contains(candidate);
      matches[i] = !ours || configured;
      if (ours && !configured) {
        filtered++;
        log.debug("Skipping {}: {} is not set", candidate, GoogleGenAiProperties.API_KEY_PROPERTY);
      }
    }
    if (filtered > 0) {
      log.info(
          "Google GenAI is on the classpath but {} is not set, so its {} auto-configuration(s) are"
              + " skipped and this deployment's models are unchanged",
          GoogleGenAiProperties.API_KEY_PROPERTY,
          filtered);
    } else if (configured) {
      log.info(
          "Google GenAI is configured; {} selects which kinds of model it serves",
          String.join(
              ", ",
              GoogleGenAiProperties.CHAT_PROVIDER_PROPERTY,
              GoogleGenAiProperties.EMBEDDING_PROVIDER_PROPERTY,
              GoogleGenAiProperties.IMAGE_PROVIDER_PROPERTY));
    }
    return matches;
  }

  private boolean configured() {
    return environment != null
        && GoogleGenAiProperties.configured(
            environment.getProperty(GoogleGenAiProperties.API_KEY_PROPERTY));
  }
}
