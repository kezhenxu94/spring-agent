package me.kezhenxu94.springagent.provider.dashscope;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * The whole of what a DashScope deployment configures: one credential, one host, and a model name
 * per kind of model.
 *
 * <p>Under {@code spring.ai.dashscope} rather than {@code app.*} so that it sits where the rest of
 * this runtime's model configuration sits, and so that {@code spring.ai.model.image=dashscope} —
 * Spring AI's own switch — reads as naming this block.
 *
 * <p><b>A base URL here is a host, and the paths are this class's business.</b> DashScope serves
 * both APIs this project uses off the same host — the OpenAI-compatible one under {@link
 * #COMPATIBLE_MODE_PATH} and the native image one under {@link #IMAGE_GENERATION_PATH} — and that
 * stays true whether the host is the public {@code dashscope.aliyuncs.com}, the international one,
 * or a Model Studio workspace's own {@code ws-<id>.<region>.maas.aliyuncs.com}. Asking a deployment
 * for those full URLs would be asking it to write the same host repeatedly and to know paths that
 * are not its choice; getting either path wrong gives a 404 from a URL that looks right.
 *
 * <p>Each model may still name a host of its own, the way Spring AI lets each model override the
 * common connection — a Model Studio workspace that serves only the vision model is the case for
 * it. Those are empty by default and mean the same thing as the one above: <b>a host, never a full
 * endpoint</b>. One rule for the whole block, because two would be a trap worth nobody's time.
 *
 * <p>{@link #apiKey()} is that one credential, and the point of the module. DashScope issues a
 * single one and serves chat, embeddings, vision and image generation off it, so nothing here is
 * repeated per endpoint. {@link DashScopeDefaults} is what spreads it to {@code spring.ai.openai.*}
 * for the OpenAI-compatible half.
 *
 * @param apiKey the DashScope credential, used for every endpoint
 * @param baseUrl the host every model uses unless it names one of its own, with no path — a
 *     trailing slash is trimmed, and the paths below are appended to it
 * @param chat the model a run's turns go to
 * @param embedding the model the tool-search index and the knowledge base are built with
 * @param image the model DashScope's own image API generates with — see {@link DashScopeImageModel}
 * @param vision the model {@code RecognizeImage} asks, and the switch for that tool
 */
@ConfigurationProperties(prefix = DashScopeProperties.PREFIX)
public record DashScopeProperties(
    String apiKey,
    String baseUrl,
    @NestedConfigurationProperty Chat chat,
    @NestedConfigurationProperty Embedding embedding,
    @NestedConfigurationProperty Image image,
    @NestedConfigurationProperty Vision vision) {

  public static final String PREFIX = "spring.ai.dashscope";

  /**
   * Every default is a constant here and read from here, by {@link DashScopeDefaults} and by the
   * literals in an application's {@code application.yaml} alike. A default written twice — once as
   * a constant, once as a yaml literal naming an environment variable — is a default that silently
   * diverges the first time one of them is raised. The yaml entries stay because they are what give
   * each setting an environment variable and where the reasoning behind the value is written down.
   */
  public static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com";

  /**
   * Where the OpenAI-compatible API lives under {@link #baseUrl()}. The {@code /v1} is part of it:
   * {@code spring.ai.openai.base-url} means the whole endpoint and the OpenAI SDK appends nothing
   * to it — its own default for that property is {@code https://api.openai.com/v1}.
   */
  public static final String COMPATIBLE_MODE_PATH = "/compatible-mode/v1";

  /**
   * Where DashScope's own image API lives under {@link #baseUrl()}. Not under {@code
   * compatible-mode}, because it is not that API — see {@link DashScopeImageModel}.
   */
  public static final String IMAGE_GENERATION_PATH =
      "/api/v1/services/aigc/multimodal-generation/generation";

  /**
   * How many rows an embedding call may carry. DashScope's OpenAI-compatible endpoint rejects a
   * larger batch outright — {@code batch size is invalid, it should not be larger than 20} —
   * regardless of how few tokens it holds, which is why core batches by row count at all. Twenty is
   * the endpoint's limit, not a tuning choice; see core's {@code embeddingBatchingStrategy}.
   */
  public static final int EMBEDDING_BATCH_SIZE = 20;

  /**
   * The property that decides whether there is a vision client, and therefore a vision tool. Read
   * through {@code @ConditionalOnNonBlankProperty} and not {@code @ConditionalOnProperty}: the yaml
   * names it as {@code ${DASHSCOPE_VISION_MODEL:}}, so an unset variable leaves it present and
   * empty, which the latter calls configured.
   */
  public static final String VISION_MODEL_PROPERTY = PREFIX + ".vision.model";

  public DashScopeProperties {
    baseUrl = host(baseUrl);
    chat = chat == null ? new Chat(null, null) : chat;
    embedding = embedding == null ? new Embedding(null, null, null) : embedding;
    image = image == null ? new Image(null, null) : image;
    vision = vision == null ? new Vision(null, null) : vision;
  }

  /** Where a run's turns go: {@code chat.base-url} if it names a host, otherwise the common one. */
  public String chatUrl() {
    return compatibleModeUrl(hostOr(chat.baseUrl()));
  }

  /** Where embeddings go. */
  public String embeddingUrl() {
    return compatibleModeUrl(hostOr(embedding.baseUrl()));
  }

  /** Where {@code RecognizeImage} asks. */
  public String visionUrl() {
    return compatibleModeUrl(hostOr(vision.baseUrl()));
  }

  /** The native image-generation endpoint, whole. */
  public String imageGenerationUrl() {
    return hostOr(image.baseUrl()) + IMAGE_GENERATION_PATH;
  }

  /**
   * The OpenAI-compatible endpoint under {@code host}, whole, as {@code spring.ai.openai.base-url}
   * wants it. Static so that {@link DashScopeDefaults} shares the derivation: it runs before
   * anything is bound and so cannot go through the record, and a second copy of the path would be a
   * second thing to keep right.
   */
  static String compatibleModeUrl(final String host) {
    return host(host) + COMPATIBLE_MODE_PATH;
  }

  /** A model's own host where it named one, the common one otherwise. */
  private String hostOr(final String override) {
    return override == null || override.isBlank() ? baseUrl : host(override);
  }

  /**
   * {@code baseUrl} as a host with no trailing slash, defaulted. Trimmed rather than trusted
   * because a copied URL very often carries one, and the result would otherwise be a double slash
   * before the path — which some gateways route and some answer with a 404.
   */
  private static String host(final String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      return DEFAULT_BASE_URL;
    }
    var trimmed = baseUrl.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed;
  }

  /**
   * @param model the chat model, e.g. {@code qwen3.8-max}
   * @param baseUrl a host of this model's own, or blank for the common one
   */
  public record Chat(String model, String baseUrl) {}

  /**
   * @param model the embedding model, e.g. {@code text-embedding-v4}
   * @param baseUrl a host of this model's own, or blank for the common one
   * @param dimensions how many dimensions to ask for. Has to agree with what the vector store was
   *     built with — {@code spring.ai.vectorstore.milvus.embedding-dimension} and {@code
   *     app.ai.rag.milvus.dimension} — or a search finds nothing and says nothing.
   */
  public record Embedding(String model, String baseUrl, Integer dimensions) {}

  /**
   * @param model the image model, e.g. {@code wan2.7-image-pro}
   * @param baseUrl a host of this model's own, or blank for the common one
   */
  public record Image(String model, String baseUrl) {}

  /**
   * @param model the vision model, e.g. {@code qwen3-vl-plus}, and the switch: naming none means no
   *     {@code RecognizeImage} tool at all
   * @param baseUrl a host of this model's own, or blank for the common one — the case this exists
   *     for, since a model deployed through Model Studio answers on its workspace's host
   */
  public record Vision(String model, String baseUrl) {}
}
