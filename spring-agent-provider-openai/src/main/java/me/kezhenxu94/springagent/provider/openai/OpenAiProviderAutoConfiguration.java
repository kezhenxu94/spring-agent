package me.kezhenxu94.springagent.provider.openai;

import com.google.common.base.Strings;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import me.kezhenxu94.springagent.core.config.ConditionalOnNonBlankProperty;
import me.kezhenxu94.springagent.core.config.ConditionalOnUserModels;
import me.kezhenxu94.springagent.core.config.UserModelsProperties;
import me.kezhenxu94.springagent.core.usermodels.BuiltinModels;
import me.kezhenxu94.springagent.core.usermodels.UserChatClients;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import me.kezhenxu94.springagent.provider.openai.aot.OpenAiSdkRuntimeHints;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingProperties;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.context.annotation.Primary;

/**
 * The OpenAI-compatible model provider.
 *
 * <p>Almost all of what a provider has to offer this runtime is offered by Spring AI's own OpenAI
 * auto-configuration, which this module depends on rather than reimplements: the {@code ChatModel},
 * {@code EmbeddingModel}, {@code TranscriptionModel} and {@code ImageModel} beans all come from
 * there, bound to {@code spring.ai.openai.*} and each gated on {@code spring.ai.model.<kind>} being
 * {@code openai}. So <b>a deployment switches provider with Spring AI's switch</b>, not one of
 * ours, and this module contributes only the four things Spring AI does not know to build.
 *
 * <p>This class is named by {@code ModelToolsConfiguration} in core as a string, so that core can
 * order itself after whichever provider is present without depending on one. Renaming it silently
 * costs this deployment its image, vision and transcription tools.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
      "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration"
    })
@EnableConfigurationProperties(OpenAiVisionProperties.class)
@ImportRuntimeHints(OpenAiSdkRuntimeHints.class)
public class OpenAiProviderAutoConfiguration {

  /**
   * Puts the provider's own words back into the log when it rejects a request.
   *
   * <p>Spring AI applies every customizer bean of this type to the OkHttp client behind each OpenAI
   * model, which is the only seam that still sees the response bytes — see {@link
   * OpenAiErrorBodyLoggingInterceptor} for why they are otherwise unrecoverable. Not behind a
   * property: it only ever fires on a request that already failed, and a 4xx nobody can explain is
   * the reason it exists.
   */
  @Bean
  @ConditionalOnMissingBean(name = "openAiErrorBodyLoggingCustomizer")
  OpenAiHttpClientBuilderCustomizer openAiErrorBodyLoggingCustomizer() {
    final var interceptor = new OpenAiErrorBodyLoggingInterceptor();
    return builder -> builder.interceptor(interceptor);
  }

  /**
   * Fails the context when nothing said where the model is — see {@link OpenAiConnectionCheck}.
   *
   * <p>Conditional on Spring AI having actually built a chat model here, so a deployment that
   * selected a different provider for chat is not held to this one's settings.
   */
  @Bean
  @ConditionalOnBean(OpenAiChatModel.class)
  @ConditionalOnMissingBean
  OpenAiConnectionCheck openAiConnectionCheck(
      final OpenAiCommonProperties commonProperties,
      final OpenAiChatProperties chatProperties,
      final ObjectProvider<OpenAiEmbeddingProperties> embeddingProperties) {
    return new OpenAiConnectionCheck(commonProperties, chatProperties, embeddingProperties);
  }

  /**
   * What core logs beside a failed run's id when the endpoint refused it — see {@link
   * OpenAiProviderRejection}. Not behind a property, for the same reason the interceptor above is
   * not: it only ever fires on a request that already failed.
   */
  @Bean
  @ConditionalOnMissingBean
  OpenAiProviderRejection openAiProviderRejection() {
    return new OpenAiProviderRejection();
  }

  /**
   * The model {@code VisionTools} asks about an image, where the deployment named one.
   *
   * <p>Built by hand rather than by Spring AI because it is a second endpoint on a runtime that
   * already has a chat model — see {@link OpenAiVisionProperties} for when that is wanted, and
   * {@link OpenAiUserChatClients} for the same reasoning applied to a user's own.
   *
   * <p>The bean name is the contract: core registers {@code RecognizeImage} on
   * {@code @ConditionalOnBean(name = "visionChatClient")}, because a vision model is a {@code
   * ChatClient} like the application's own and a type condition would be answered by the wrong one.
   */
  @Bean
  @ConditionalOnNonBlankProperty(OpenAiVisionProperties.MODEL_PROPERTY)
  @ConditionalOnMissingBean(name = "visionChatClient")
  @Qualifier("vision")
  ChatClient visionChatClient(
      final OpenAiVisionProperties vision,
      final OpenAiChatModel defaultChatModel,
      final OpenAiCommonProperties commonProperties,
      final OpenAiChatProperties chatProperties,
      // Spring AI applies these to the models its own auto-configuration builds; this model is
      // built here, so it has to ask for them itself or it would be the one endpoint whose
      // rejections stay unreadable — and it is a gateway, which is where unreadable ones come from.
      final List<OpenAiHttpClientBuilderCustomizer> httpClientCustomizers) {
    // Starting from the application's own resolved options rather than from nothing, for the reason
    // ApplicationEndpoint spells out: options built from scratch here would carry no timeout and no
    // credential, and the OpenAI SDK handed no credential goes looking in the environment — on a
    // machine exporting OPENAI_API_KEY it would build a client onto *that* endpoint and send
    // somebody's images there.
    final var defaults =
        ApplicationEndpoint.resolve(
            defaultChatModel.getOptions(), commonProperties, chatProperties);
    final var options = defaults.mutate().model(vision.model());
    if (!Strings.isNullOrEmpty(vision.baseUrl())) {
      options.baseUrl(vision.baseUrl());
    }
    if (!Strings.isNullOrEmpty(vision.apiKey())) {
      options.apiKey(vision.apiKey());
    }
    final var chatModel =
        OpenAiChatModel.builder()
            .options(options.build())
            .httpClientBuilderCustomizers(httpClientCustomizers)
            .build();
    return ChatClient.builder(chatModel).build();
  }

  /**
   * Spring AI's image model with this project's two additions — see {@link OpenAiAgentImageModel},
   * which is where the reasoning is.
   *
   * <p>{@code @Primary} rather than replacing the bean, because Spring AI's is what this one
   * delegates to and suppressing it would leave nothing to delegate to. Two {@code ImageModel}
   * beans therefore exist; the annotation is what makes core's tool resolve to this one.
   */
  @Bean
  @Primary
  @ConditionalOnBean(OpenAiImageModel.class)
  @ConditionalOnMissingBean(name = "openAiAgentImageModel")
  ImageModel openAiAgentImageModel(final OpenAiImageModel delegate) {
    return new OpenAiAgentImageModel(delegate);
  }

  /**
   * The application's own chat options, endpoint included, which every client this module builds is
   * a variation of — see {@link ApplicationEndpoint} for why the {@link OpenAiChatModel} bean's own
   * options are not enough, and {@code OpenAiUserChatClients#build} for why they are copied rather
   * than rebuilt.
   *
   * <p>Not a bean: an {@code OpenAiChatOptions} bean in the context is a type Spring AI itself
   * looks up, and this one describes the application's endpoint rather than any single request.
   */
  private static OpenAiChatOptions resolvedOptions(
      final OpenAiChatModel defaultChatModel,
      final OpenAiCommonProperties commonProperties,
      final OpenAiChatProperties chatProperties) {
    return ApplicationEndpoint.resolve(
        defaultChatModel.getOptions(), commonProperties, chatProperties);
  }

  @Bean
  @ConditionalOnUserModels
  @ConditionalOnMissingBean
  UserChatClients userChatClients(
      @Qualifier("chatClient") final ChatClient defaultChatClient,
      final OpenAiChatModel defaultChatModel,
      final OpenAiCommonProperties commonProperties,
      final OpenAiChatProperties chatProperties,
      final UserModelRegistry registry,
      final List<OpenAiHttpClientBuilderCustomizer> httpClientCustomizers,
      final UserModelsProperties properties) {
    return new OpenAiUserChatClients(
        defaultChatClient,
        registry,
        resolvedOptions(defaultChatModel, commonProperties, chatProperties),
        httpClientCustomizers,
        properties.cacheSize());
  }

  @Bean
  @ConditionalOnUserModels
  @ConditionalOnMissingBean
  BuiltinModels builtinModels(
      final OpenAiChatModel defaultChatModel,
      final OpenAiCommonProperties commonProperties,
      final OpenAiChatProperties chatProperties,
      final List<OpenAiHttpClientBuilderCustomizer> httpClientCustomizers,
      final ObjectProvider<ObservationRegistry> observationRegistry) {
    return new OpenAiBuiltinModels(
        resolvedOptions(defaultChatModel, commonProperties, chatProperties),
        httpClientCustomizers,
        observationRegistry.getIfAvailable());
  }
}
