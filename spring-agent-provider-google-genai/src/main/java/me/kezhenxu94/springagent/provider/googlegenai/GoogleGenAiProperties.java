package me.kezhenxu94.springagent.provider.googlegenai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * The settings this module reads, bound from the same {@code spring.ai.google.genai.*} prefix
 * Spring AI's own Google GenAI auto-configuration binds.
 *
 * <p>Deliberately the same prefix rather than one of ours. Spring AI publishes every model bean
 * here from that block, and a second block naming the same endpoint would be two places for a
 * credential to go stale — the mistake {@code DashScopeDefaults} exists to avoid for DashScope.
 * What this record adds is not new configuration but a bound, typed view of it, for the two classes
 * that have to read it before or beside Spring AI: {@link GoogleGenAiAutoConfigurationFilter},
 * which cannot bind anything because it runs before binding, and {@link
 * GoogleGenAiConnectionCheck}.
 *
 * <p>Only the Gemini Developer API is served here: one {@code api-key}, no project, no location, no
 * service-account file. Spring AI's own properties bind those Vertex AI fields too, and a
 * deployment that sets them gets Vertex through Spring AI's beans — but nothing in this module
 * supports it, and {@link GoogleGenAiConnectionCheck} says so rather than letting it half-work.
 */
@ConfigurationProperties(prefix = GoogleGenAiProperties.PREFIX)
public record GoogleGenAiProperties(
    String apiKey,
    @NestedConfigurationProperty Chat chat,
    @NestedConfigurationProperty Embedding embedding,
    @NestedConfigurationProperty Image image) {

  public static final String PREFIX = "spring.ai.google.genai";

  /**
   * The one property that decides whether this module does anything at all.
   *
   * <p>Named here as a constant because three things read it and they must not drift: the filter
   * that removes Spring AI's auto-configurations when it is blank, the conditions on this module's
   * own beans, and the startup check.
   */
  public static final String API_KEY_PROPERTY = PREFIX + ".api-key";

  /**
   * What an operator sets to point a kind of model at Gemini. Spring AI's switch, not one of ours —
   * see this module's README — and spelled out here because {@link GoogleGenAiConnectionCheck} has
   * to ask about each of them.
   */
  public static final String CHAT_PROVIDER_PROPERTY = "spring.ai.model.chat";

  public static final String EMBEDDING_PROVIDER_PROPERTY = "spring.ai.model.embedding.text";

  public static final String IMAGE_PROVIDER_PROPERTY = "spring.ai.model.image";

  /** The value each of those three takes to mean this provider, as Spring AI spells it. */
  public static final String PROVIDER = "google-genai";

  public GoogleGenAiProperties {
    chat = chat == null ? new Chat(null) : chat;
    embedding = embedding == null ? new Embedding(null) : embedding;
    image = image == null ? new Image(null) : image;
  }

  public record Chat(String model) {}

  /**
   * Shaped to match Spring AI's own paths rather than tidied: the embedding model lives at {@code
   * spring.ai.google.genai.embedding.text.model}, because {@code embedding.text} is where Spring AI
   * puts text embeddings to leave room beside them for the multimodal ones. Flattening it here
   * would bind a property nothing else reads.
   */
  public record Embedding(@NestedConfigurationProperty Text text) {
    public Embedding {
      text = text == null ? new Text(null, null) : text;
    }

    public record Text(String model, Integer dimensions) {}
  }

  public record Image(String model) {}

  /**
   * Whether a setting holds something.
   *
   * <p>Blank rather than empty, which is the same rule core's {@code ConditionalOnNonBlankProperty}
   * applies and deliberately not Guava's {@code isNullOrEmpty}: a variable somebody set to a space
   * is a variable nobody set, and treating it as a credential builds a client that authenticates
   * with whitespace. The endpoint's answer to that names neither the setting nor the deployment.
   */
  public static boolean configured(final String value) {
    return value != null && !value.isBlank();
  }
}
