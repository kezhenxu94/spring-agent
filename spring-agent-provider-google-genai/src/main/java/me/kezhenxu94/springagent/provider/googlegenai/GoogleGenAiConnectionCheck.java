package me.kezhenxu94.springagent.provider.googlegenai;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;

/**
 * Says at startup what would otherwise be said by the first run to fail, and says it about the
 * configuration rather than about the endpoint.
 *
 * <p>There are two ways to get this wrong and they look nothing alike in the logs. Naming {@code
 * google-genai} for a kind of model with no key set means {@link
 * GoogleGenAiAutoConfigurationFilter} has removed every Google GenAI auto-configuration, so the
 * application starts with no model of that kind at all and fails much later with something about a
 * missing bean. Setting a key but naming no model means a request for a model called {@code ""},
 * which the endpoint answers with a message that reads like a broken gateway.
 *
 * <p>Both become an {@code IllegalStateException} here, naming the environment variable and the
 * switch, because those are the two things whoever deployed this can actually change. The same job
 * {@code OpenAiConnectionCheck} does for that provider.
 *
 * <p>Vertex AI is checked for explicitly rather than ignored. Spring AI's properties bind {@code
 * project-id}, {@code location} and {@code credentials-uri}, so a deployment can set them and get a
 * working Vertex connection out of Spring AI's own beans — but nothing in this module is written
 * for it: {@link GoogleGenAiAutoConfigurationFilter} keys off the API key alone and would filter
 * those beans away, leaving a deployment that configured Vertex correctly with no models and no
 * explanation. Saying so is better than half-supporting it.
 */
@Slf4j
@RequiredArgsConstructor
public class GoogleGenAiConnectionCheck {

  private static final String API_KEY_HINT =
      "Set GEMINI_API_KEY, or set that switch to another provider";

  private final GoogleGenAiProperties properties;
  private final Environment environment;

  @PostConstruct
  void check() {
    final var selected = new ArrayList<String>();
    if (selects(GoogleGenAiProperties.CHAT_PROVIDER_PROPERTY)) {
      selected.add(GoogleGenAiProperties.CHAT_PROVIDER_PROPERTY);
    }
    if (selects(GoogleGenAiProperties.EMBEDDING_PROVIDER_PROPERTY)) {
      selected.add(GoogleGenAiProperties.EMBEDDING_PROVIDER_PROPERTY);
    }
    if (selects(GoogleGenAiProperties.IMAGE_PROVIDER_PROPERTY)) {
      selected.add(GoogleGenAiProperties.IMAGE_PROVIDER_PROPERTY);
    }

    if (selected.isEmpty()) {
      log.info(
          "Google GenAI is configured but serves nothing: none of {}, {}, {} names it. Set one of"
              + " them to '{}' to route that kind of model here",
          GoogleGenAiProperties.CHAT_PROVIDER_PROPERTY,
          GoogleGenAiProperties.EMBEDDING_PROVIDER_PROPERTY,
          GoogleGenAiProperties.IMAGE_PROVIDER_PROPERTY,
          GoogleGenAiProperties.PROVIDER);
      return;
    }

    if (!GoogleGenAiProperties.configured(properties.apiKey())) {
      throw new IllegalStateException(
          ("%s names '%s' but %s is not set, so no Google GenAI model was built at all. %s.")
              .formatted(
                  String.join(" and ", selected),
                  GoogleGenAiProperties.PROVIDER,
                  GoogleGenAiProperties.API_KEY_PROPERTY,
                  API_KEY_HINT));
    }

    if (usesVertexAi()) {
      throw new IllegalStateException(
          ("%s is set, which selects Vertex AI, but this module serves only the Gemini Developer"
                  + " API. Unset spring.ai.google.genai.vertex-ai, project-id and location, and"
                  + " authenticate with %s instead.")
              .formatted(
                  "spring.ai.google.genai.vertex-ai/project-id/location",
                  GoogleGenAiProperties.API_KEY_PROPERTY));
    }

    requireModel(
        GoogleGenAiProperties.CHAT_PROVIDER_PROPERTY,
        GoogleGenAiProperties.PREFIX + ".chat.model",
        properties.chat().model(),
        "GEMINI_CHAT_MODEL");
    requireModel(
        GoogleGenAiProperties.EMBEDDING_PROVIDER_PROPERTY,
        GoogleGenAiProperties.PREFIX + ".embedding.text.model",
        properties.embedding().text().model(),
        "GEMINI_EMBEDDING_MODEL");
    // Not the image model: GoogleGenAiImageOptions defaults it to gemini-2.5-flash-image, so a
    // deployment that names none still has a working one, unlike chat and embeddings where the
    // endpoint has no default and an empty name is refused.

    log.info(
        "Google GenAI is serving {} (chat model={}, embedding model={}, image model={})",
        String.join(", ", selected),
        orDefault(properties.chat().model()),
        orDefault(properties.embedding().text().model()),
        orDefault(properties.image().model()));
  }

  private void requireModel(
      final String switchProperty,
      final String modelProperty,
      final String model,
      final String variable) {
    if (!selects(switchProperty) || GoogleGenAiProperties.configured(model)) {
      return;
    }
    throw new IllegalStateException(
        ("%s names '%s' but %s is not set. Gemini has no default model for this and refuses an"
                + " empty name with a message that reads like a broken endpoint. Set %s.")
            .formatted(switchProperty, GoogleGenAiProperties.PROVIDER, modelProperty, variable));
  }

  private boolean selects(final String property) {
    return GoogleGenAiProperties.PROVIDER.equals(environment.getProperty(property));
  }

  /**
   * Whether anything asks for Vertex AI. {@code vertex-ai} is a boolean flag, but Spring AI also
   * falls back to Vertex when a project and a location are both present, so both spellings count.
   */
  private boolean usesVertexAi() {
    if (Boolean.parseBoolean(
        environment.getProperty(GoogleGenAiProperties.PREFIX + ".vertex-ai"))) {
      return true;
    }
    return GoogleGenAiProperties.configured(
            environment.getProperty(GoogleGenAiProperties.PREFIX + ".project-id"))
        && GoogleGenAiProperties.configured(
            environment.getProperty(GoogleGenAiProperties.PREFIX + ".location"));
  }

  private static String orDefault(final String model) {
    return GoogleGenAiProperties.configured(model) ? model : "<the endpoint's default>";
  }
}
