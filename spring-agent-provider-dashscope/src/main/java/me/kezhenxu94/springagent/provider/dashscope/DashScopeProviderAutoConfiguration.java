package me.kezhenxu94.springagent.provider.dashscope;

import com.google.common.base.Strings;
import java.util.List;
import me.kezhenxu94.springagent.core.config.ConditionalOnNonBlankProperty;
import me.kezhenxu94.springagent.provider.dashscope.aot.DashScopeRuntimeHints;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.model.SpringAIModelProperties;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestTemplate;

/**
 * The DashScope model provider: two beans, and nothing else.
 *
 * <p>Everything DashScope serves over the OpenAI wire protocol — chat, embeddings, and the per-user
 * endpoints a person may register — is served by {@code spring-agent-provider-openai}, pointed here
 * by {@link DashScopeDefaults}. What is genuinely DashScope's own is what this class publishes: its
 * image API, which is a different API rather than a different host, and a vision model that answers
 * somewhere else.
 *
 * <p>Named by {@code ModelToolsConfiguration} in core as a string, so core can order itself after a
 * provider without depending on one. Renaming this class silently costs the deployment its image
 * and vision tools.
 */
@AutoConfiguration
@EnableConfigurationProperties(DashScopeProperties.class)
@ImportRuntimeHints(DashScopeRuntimeHints.class)
public class DashScopeProviderAutoConfiguration {

  /**
   * DashScope's image API, selected with Spring AI's own switch — {@code
   * spring.ai.model.image=dashscope}, which in the same breath makes Spring AI's {@code
   * OpenAiImageAutoConfiguration} back off, since its condition is the same property with {@code
   * openai}. So the two providers' image models cannot both be present, whatever is on the
   * classpath, and there is no switch of ours for them to disagree with.
   *
   * <p>{@code @Primary} because {@code spring-agent-provider-openai} marks its own decorator so:
   * with that provider's image model absent this annotation changes nothing, and it keeps the two
   * modules' beans interchangeable from core's side.
   */
  @Bean
  @Primary
  @ConditionalOnProperty(name = SpringAIModelProperties.IMAGE_MODEL, havingValue = "dashscope")
  @ConditionalOnMissingBean(name = "dashscopeImageModel")
  ImageModel dashscopeImageModel(
      final RestTemplate restTemplate, final DashScopeProperties properties) {
    // At startup rather than at the first call. Selecting this provider and configuring nothing for
    // it is a misconfiguration, not a mode — and the alternative is a GenerateImage tool that the
    // model is offered, reaches for, and gets a 401 from, which reads to it as an endpoint to
    // retry.
    // Choosing `none` is how a deployment says it wants no image generation.
    if (Strings.isNullOrEmpty(properties.apiKey())) {
      throw new IllegalStateException(
          "spring.ai.model.image is dashscope but spring.ai.dashscope.api-key is not set. Set"
              + " DASHSCOPE_API_KEY, or set IMAGE_MODEL_PROVIDER to openai or none.");
    }
    if (Strings.isNullOrEmpty(properties.image().model())) {
      throw new IllegalStateException(
          "spring.ai.model.image is dashscope but spring.ai.dashscope.image.model is not set. Set"
              + " DASHSCOPE_IMAGE_MODEL, or set IMAGE_MODEL_PROVIDER to openai or none.");
    }
    return new DashScopeImageModel(restTemplate, properties);
  }

  /**
   * The model {@code RecognizeImage} asks about an image.
   *
   * <p>A client of its own rather than the application's, because on DashScope vision is a
   * different model — {@code qwen3-vl-*} — than the one a run's turns go to. It is the same
   * endpoint, though: still the OpenAI wire protocol, still {@link OpenAiChatModel}, on the same
   * {@code compatible-mode} URL. Only the model name differs.
   *
   * <p>Naming no vision model means no bean, and therefore no {@code RecognizeImage} tool at all —
   * see core's {@code ModelToolsConfiguration}. That is deliberately not the same as a tool that
   * refuses: a tool the model can see is a tool it will try, and one that fails on configuration it
   * cannot change reads to it as an endpoint to retry rather than a feature that is switched off.
   *
   * <p>{@code @ConditionalOnNonBlankProperty} and not {@code @ConditionalOnProperty}, which would
   * call {@code ${DASHSCOPE_VISION_MODEL:}} configured when nobody set the variable and build this
   * client with an empty model name — answered by DashScope with {@code The length of model should
   * be between 1 and 512}.
   *
   * <p>The bean name is the contract core conditions on; the qualifier is what {@code VisionTools}
   * is wired through.
   */
  @Bean
  @ConditionalOnNonBlankProperty(DashScopeProperties.VISION_MODEL_PROPERTY)
  @ConditionalOnMissingBean(name = "visionChatClient")
  @Qualifier("vision")
  ChatClient visionChatClient(
      final DashScopeProperties properties,
      // Asked for rather than assumed: Spring AI applies these to the models its own
      // auto-configuration builds, and this one is built by hand, so without them this would be the
      // single endpoint here whose rejections stay unreadable — and it is a gateway, which is where
      // unreadable ones come from.
      final List<OpenAiHttpClientBuilderCustomizer> httpClientCustomizers) {
    final var chatModel =
        OpenAiChatModel.builder()
            .options(
                OpenAiChatOptions.builder()
                    .baseUrl(properties.visionUrl())
                    .apiKey(properties.apiKey())
                    .model(properties.vision().model())
                    .build())
            .httpClientBuilderCustomizers(httpClientCustomizers)
            .build();
    return ChatClient.builder(chatModel).build();
  }
}
