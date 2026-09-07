package me.kezhenxu94.springagent.provider.openai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the model that answers about an image lives, when it is not the one that answers about
 * everything else.
 *
 * <p>Its own block rather than a value under {@code spring.ai.openai.chat}, because Spring AI owns
 * that namespace and binds it to its own properties class; a key added there is a key that class
 * ignores in silence.
 *
 * <p><b>Naming no model means no {@code RecognizeImage} tool at all</b>, rather than one that goes
 * through the application's own client — see {@code ModelToolsConfiguration} in core, which
 * conditions the tool on this bean existing. That is deliberate even though a GPT-class chat model
 * would answer about an image perfectly well: whether the model behind {@code
 * spring.ai.openai.base-url} can see images is something only the operator knows, and plenty of
 * gateways proxy a model that cannot. A tool the model can see is a tool it will try.
 *
 * <p>So a deployment that wants the tool names a model here, which may be the same one {@code
 * spring.ai.openai.chat.model} names. The base URL and key are then the main connection's unless
 * overridden, which is the other case this exists for: a gateway that routes vision to a different
 * host — as DashScope does, which is why {@code spring-agent-provider-dashscope} publishes the same
 * bean from its own settings.
 *
 * @param model the model to ask, and the switch: no model means no vision client and therefore no
 *     vision tool
 * @param baseUrl where to ask — <b>the whole endpoint, {@code /v1} included</b>, because that is
 *     what a {@code base-url} under {@code spring.ai.openai.*} means: Spring AI's own default for
 *     it is {@code https://api.openai.com/v1} and the OpenAI SDK appends nothing. Blank for the
 *     main connection's. Note that {@code spring.ai.dashscope.*} deliberately differs — a base URL
 *     there is a host and that module owns the paths, because it serves two APIs off one host and
 *     neither path is a deployment's choice. Each block is consistent within itself, which is what
 *     an operator reading one of them needs
 * @param apiKey what to authenticate with, blank for the main connection's
 */
@ConfigurationProperties(prefix = OpenAiVisionProperties.PREFIX)
public record OpenAiVisionProperties(String model, String baseUrl, String apiKey) {

  public static final String PREFIX = "spring.ai.openai.vision";

  /**
   * The property that decides whether there is a vision client at all. Read through
   * {@code @ConditionalOnNonBlankProperty}: the yaml names it as {@code ${OPENAI_VISION_MODEL:}},
   * so an unset variable leaves it present and empty, which {@code @ConditionalOnProperty} calls
   * configured.
   */
  public static final String MODEL_PROPERTY = PREFIX + ".model";
}
