package me.kezhenxu94.springagent.provider.googlegenai;

import me.kezhenxu94.springagent.core.config.ConditionalOnNonBlankProperty;
import me.kezhenxu94.springagent.core.config.ConditionalOnUserModels;
import me.kezhenxu94.springagent.core.config.UserModelsProperties;
import me.kezhenxu94.springagent.core.usermodels.BuiltinModels;
import me.kezhenxu94.springagent.core.usermodels.ProviderChatClients;
import me.kezhenxu94.springagent.core.usermodels.ReasoningEffortInForce;
import me.kezhenxu94.springagent.core.usermodels.UserChatClients;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import me.kezhenxu94.springagent.provider.googlegenai.aot.GoogleGenAiRuntimeHints;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.image.GoogleGenAiImageModel;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestTemplate;

/**
 * The Google GenAI model provider: Gemini's chat, embedding and image models, and what this project
 * needs on top of them.
 *
 * <p>Every model bean comes from Spring AI's own starters, gated as Spring AI gates them — {@code
 * spring.ai.model.chat}, {@code spring.ai.model.embedding.text} and {@code spring.ai.model.image}
 * naming {@code google-genai}. That switch is Spring AI's and not one of this project's, for the
 * reason {@code spring-agent-provider-openai}'s README sets out: a second switch over the same
 * decision is a second thing to keep in step.
 *
 * <p><b>Whether those auto-configurations run at all is decided before this class</b>, by {@link
 * GoogleGenAiAutoConfigurationFilter} — two of them throw on a classpath with no Gemini credential,
 * so carrying this module would otherwise break every deployment that has not configured one. Read
 * that class before changing anything here.
 *
 * <p>No vision client, and that is a deliberate absence rather than an omission. Gemini's chat
 * models are natively multimodal, so {@code RecognizeImage} goes through the application's own
 * client — exactly the case the OpenAI provider's README calls the normal one. A {@code
 * visionChatClient} bean would only be right for a deployment whose vision model is a different
 * endpoint, which Gemini's is not.
 *
 * <p>No transcription either: Spring AI ships no Google GenAI {@code TranscriptionModel}, so core
 * registers no {@code TranscribeAudio}. A tool the model can see is a tool it will try, and an
 * absent one beats one that always fails.
 *
 * <p>Named by {@code ModelToolsConfiguration} in core as a string, so core can order itself after a
 * provider without depending on one. Renaming this class silently costs the deployment its image
 * tool.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.ai.model.google.genai.autoconfigure.chat"
          + ".GoogleGenAiChatAutoConfiguration",
      "org.springframework.ai.model.google.genai.autoconfigure.image"
          + ".GoogleGenAiImageAutoConfiguration"
    })
@EnableConfigurationProperties(GoogleGenAiProperties.class)
@ImportRuntimeHints(GoogleGenAiRuntimeHints.class)
public class GoogleGenAiProviderAutoConfiguration {

  /**
   * Registered whenever the module is configured at all, rather than only where a model was built:
   * its whole job is to explain a deployment whose models were <em>not</em> built, which is the
   * case a condition on a model bean would exclude.
   */
  @Bean
  @ConditionalOnNonBlankProperty(GoogleGenAiProperties.API_KEY_PROPERTY)
  @ConditionalOnMissingBean
  GoogleGenAiConnectionCheck googleGenAiConnectionCheck(
      final GoogleGenAiProperties properties, final Environment environment) {
    return new GoogleGenAiConnectionCheck(properties, environment);
  }

  /**
   * Unconditional, because it is asked about every failure of every run and answers only about
   * Gemini's own exception type. Core collects every {@code ProviderRejection} bean and takes the
   * first non-empty answer, so this one costs a deployment on another provider an {@code
   * instanceof} and nothing else.
   */
  @Bean
  @ConditionalOnMissingBean
  GoogleGenAiProviderRejection googleGenAiProviderRejection() {
    return new GoogleGenAiProviderRejection();
  }

  /**
   * Gemini's image model with this project's two translations over it — reference images as bytes,
   * and a size that is two fields here rather than one. See {@link GoogleGenAiAgentImageModel}.
   *
   * <p>{@code @Primary} because both sibling providers mark their image models so, which keeps the
   * three interchangeable from core's side. Only one of them can exist anyway: {@code
   * spring.ai.model.image} picks exactly one, and naming any provider is what makes the others back
   * off.
   */
  @Bean
  @Primary
  @ConditionalOnBean(GoogleGenAiImageModel.class)
  @ConditionalOnMissingBean(name = "googleGenAiAgentImageModel")
  ImageModel googleGenAiAgentImageModel(
      final RestTemplate restTemplate, final GoogleGenAiImageModel delegate) {
    return new GoogleGenAiAgentImageModel(restTemplate, delegate);
  }

  /**
   * How hard this deployment's Gemini model is asked to think, said in core's vocabulary.
   *
   * <p>Unconditional on user models, unlike the two beans below, and that is the point of the
   * contract: a deployment where nobody may register a model of their own still has an effort in
   * force — the configured one, for everybody — and a surface that wants to print it should not
   * have to know whether the feature is on. Where it is on, {@link UserChatClients} is consulted so
   * that a user on a model of theirs is reported honestly.
   *
   * <p>{@code ObjectProvider} rather than a second bean definition, because which of the two
   * answers is right depends on a condition evaluated elsewhere, and resolving it per call is
   * cheaper to reason about than two beans racing to be the one.
   */
  @Bean
  @ConditionalOnBean(GoogleGenAiChatModel.class)
  @ConditionalOnMissingBean
  ReasoningEffortInForce reasoningEffortInForce(
      final GoogleGenAiChatModel chatModel, final ObjectProvider<UserChatClients> userChatClients) {
    final var configured = GoogleGenAiThinking.effortOf(chatModel.getOptions());
    return userId -> {
      final var clients = userChatClients.getIfAvailable();
      if (clients == null || userId == null) {
        return configured;
      }
      return clients.effortInForce(userId);
    };
  }

  /**
   * How a user's own Gemini endpoint is reached.
   *
   * <p><b>Deliberately not conditional on this module having built the application's chat
   * model.</b> Core's {@code DispatchingUserChatClients} picks between providers per row, so
   * somebody may register a Gemini model on a deployment whose own chat model is OpenAI's — and
   * this bean has to exist wherever it might be named, not only where it won.
   *
   * <p>Hence the {@code ObjectProvider} around the chat model: with {@code spring.ai.model.chat}
   * naming another provider there is no Gemini model to copy options from, and the right default is
   * this provider's bare one. A row carries the key, the model and optionally a base URL, which is
   * everything the endpoint needs.
   *
   * <p>Its credential still comes from {@link GoogleGenAiProperties} where the deployment has one,
   * since a row with no base URL means "the application's endpoint, different model" — and where it
   * has none, such a row simply has no key to borrow and the endpoint says so.
   */
  @Bean
  @ConditionalOnUserModels
  @ConditionalOnMissingBean(name = "googleGenAiProviderChatClients")
  ProviderChatClients googleGenAiProviderChatClients(
      final ObjectProvider<GoogleGenAiChatModel> chatModel,
      final GoogleGenAiProperties properties,
      final UserModelRegistry registry,
      final ToolCallingManager toolCallingManager,
      final UserModelsProperties userModelsProperties) {
    final var model = chatModel.getIfAvailable();
    return new GoogleGenAiUserChatClients(
        registry,
        model == null
            ? GoogleGenAiChatOptions.builder().model(properties.chat().model()).build()
            : model.getOptions(),
        properties.apiKey(),
        toolCallingManager,
        userModelsProperties.cacheSize());
  }

  @Bean
  @ConditionalOnUserModels
  @ConditionalOnBean(GoogleGenAiChatModel.class)
  @ConditionalOnMissingBean
  BuiltinModels builtinModels(
      final GoogleGenAiChatModel chatModel, final GoogleGenAiProperties properties) {
    return new GoogleGenAiBuiltinModels(properties.apiKey(), chatModel.getOptions().getModel());
  }
}
