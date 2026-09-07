package me.kezhenxu94.springagent.core.tools;

/**
 * The keys {@link ImageGenerationTools} puts on an {@code ImageMessage}'s metadata, and the whole
 * of what a {@code spring-agent-provider-*} module has to know to serve {@code GenerateImage}.
 *
 * <p>Metadata rather than a subtype of {@code ImageOptions}, and that is what keeps this project
 * from needing an image SPI of its own. Spring AI's {@code ImageOptions} carries {@code n}, {@code
 * model}, {@code width}, {@code height}, {@code responseFormat} and {@code style} — it cannot say
 * "2K", cannot say "16:9", and has nowhere to put a thinking budget. An options subtype could, but
 * only a provider can define one, so core's tool would then have to name a provider's type to build
 * a request, which is the coupling this module family exists to remove. {@code ImageMessage}
 * already carries a {@code Map<String, Object>} for exactly this, alongside the {@code List<Media>}
 * that carries the reference images.
 *
 * <p>So the contract is: core states what the model asked for, each provider reads the keys it
 * understands and ignores the rest. A provider that cannot honour a key it <em>does</em> understand
 * should say so as a tool error rather than answer with something else — see {@code
 * OpenAiAgentImageModel} for the reference-image case, which is where a silent difference would do
 * real damage.
 */
public final class ImageGenerationMetadata {

  private ImageGenerationMetadata() {}

  /**
   * The size, as the model typed it and unparsed. Providers do not share a vocabulary here —
   * DashScope takes {@code 2K} and {@code 16:9}, OpenAI takes {@code 1024x1024} — so translating it
   * is the provider's job, and one that does not recognise a value uses its endpoint's default
   * rather than guessing.
   */
  public static final String SIZE = "size";

  /**
   * Whether to think before generating, as a {@link Boolean}. Absent or false on most endpoints;
   * only a provider whose image model reasons reads it.
   */
  public static final String THINKING_MODE = "thinkingMode";
}
