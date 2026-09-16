package me.kezhenxu94.springagent.provider.anthropic;

import java.util.List;
import me.kezhenxu94.springagent.core.config.ConditionalOnUserModels;
import me.kezhenxu94.springagent.core.config.UserModelsProperties;
import me.kezhenxu94.springagent.core.usermodels.BuiltinModels;
import me.kezhenxu94.springagent.core.usermodels.ProviderChatClients;
import me.kezhenxu94.springagent.core.usermodels.ReasoningEffortInForce;
import me.kezhenxu94.springagent.core.usermodels.UserChatClients;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import me.kezhenxu94.springagent.provider.anthropic.aot.AnthropicRuntimeHints;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.http.okhttp.AnthropicHttpClientBuilderCustomizer;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicConnectionProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.env.Environment;

/**
 * The Anthropic model provider: Claude chat, and what this project needs on top of it, whichever
 * backend built the model.
 *
 * <p>Chat and nothing else, which is an absence rather than an omission. Anthropic serves no
 * embeddings, no image generation and no transcription, so a deployment on Claude always names
 * another provider for those kinds — supported rather than a workaround, since {@code
 * spring.ai.model.*} is per kind. There is no {@code visionChatClient} either: Claude is natively
 * multimodal, so {@code RecognizeImage} goes through the application's own client, exactly the case
 * {@code spring-agent-provider-openai}'s README calls the normal one.
 *
 * <p>Which host serves it is {@code spring.ai.anthropic.backend}, and that switch <em>is</em> one
 * of this project's — unlike {@code spring.ai.model.*}, which is Spring AI's. The distinction is
 * worth keeping straight: Spring AI's chooses which provider serves a kind of model, this one
 * chooses which host the same provider's protocol is spoken to. Spring AI has no property for the
 * second question because its auto-configuration hard wires {@code AnthropicBackend}, which is why
 * {@link AnthropicVertexAutoConfiguration} publishes the chat model itself on the Vertex backend.
 *
 * <p>Named by {@code ModelToolsConfiguration} in core as a string, so core can order itself after a
 * provider without depending on one. Renaming this class silently changes that ordering.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration",
      "me.kezhenxu94.springagent.provider.anthropic.AnthropicVertexAutoConfiguration"
    })
@EnableConfigurationProperties(AnthropicProperties.class)
@ImportRuntimeHints(AnthropicRuntimeHints.class)
public class AnthropicProviderAutoConfiguration {

  /**
   * Rescues the body of a rejected request before the SDK destroys it.
   *
   * <p>Unconditional, because it only ever fires on a request that already failed. A bean rather
   * than something applied by hand, because that is the seam Spring AI's own auto-configuration
   * collects — {@code AnthropicChatAutoConfiguration} takes every {@code
   * AnthropicHttpClientBuilderCustomizer} in the context. The Vertex path cannot use it and
   * attaches the same interceptor itself; {@code AnthropicErrorBodyCustomizerWiringTest} pins both
   * halves, because the two paths reach it by different mechanisms and only one of them is Spring
   * AI's.
   */
  @Bean
  @ConditionalOnMissingBean(name = "anthropicErrorBodyLoggingCustomizer")
  AnthropicHttpClientBuilderCustomizer anthropicErrorBodyLoggingCustomizer() {
    return builder -> builder.interceptor(new AnthropicErrorBodyLoggingInterceptor());
  }

  /**
   * Registered whenever the module is on the classpath, rather than only where a model was built:
   * its whole job is to explain a deployment whose model was <em>not</em> built, which is the case
   * a condition on a model bean would exclude.
   */
  @Bean
  @ConditionalOnMissingBean
  AnthropicConnectionCheck anthropicConnectionCheck(
      final AnthropicProperties properties, final Environment environment) {
    return new AnthropicConnectionCheck(properties, environment);
  }

  /**
   * Unconditional, because it is asked about every failure of every run and answers only about the
   * Anthropic SDK's own exception type. Core collects every {@code ProviderRejection} bean and
   * takes the first non-empty answer, so this one costs a deployment on another provider an {@code
   * instanceof} and nothing else.
   */
  @Bean
  @ConditionalOnMissingBean
  AnthropicProviderRejection anthropicProviderRejection() {
    return new AnthropicProviderRejection();
  }

  /**
   * How hard this deployment's model is asked to think, said in core's vocabulary — which here is a
   * translation rather than a copy, since Anthropic configures a thinking <em>budget</em> and core
   * names rungs. See {@link AnthropicThinking}.
   *
   * <p>Unconditional on user models, unlike the two beans below, and that is the point of the
   * contract: a deployment where nobody may register a model of their own still has an effort in
   * force — the configured one, for everybody.
   */
  @Bean
  @ConditionalOnBean(AnthropicChatModel.class)
  @ConditionalOnMissingBean
  ReasoningEffortInForce reasoningEffortInForce(
      final AnthropicChatModel chatModel, final ObjectProvider<UserChatClients> userChatClients) {
    final var configured = AnthropicThinking.effortOf(chatModel.getOptions());
    return userId -> {
      final var clients = userChatClients.getIfAvailable();
      if (clients == null || userId == null) {
        return configured;
      }
      return clients.effortInForce(userId);
    };
  }

  /**
   * How a user's own Anthropic endpoint is reached.
   *
   * <p><b>Deliberately not conditional on this module having built the application's chat
   * model</b>, for the reason the OpenAI provider's equivalent states: core's {@code
   * DispatchingUserChatClients} picks between providers per row, so this one has to exist wherever
   * it might be named, not only where it won. Which is why every Anthropic-specific input is an
   * {@code ObjectProvider} — with {@code spring.ai.model.chat} naming somebody else, Spring AI's
   * Anthropic auto-configuration backs off and takes its properties beans with it.
   *
   * <p>Anthropic's API only, never Vertex — see {@link AnthropicUserChatClients} for why that
   * boundary is the honest one.
   */
  @Bean
  @ConditionalOnUserModels
  @ConditionalOnMissingBean(name = "anthropicProviderChatClients")
  ProviderChatClients anthropicProviderChatClients(
      final ObjectProvider<AnthropicChatModel> chatModel,
      final UserModelRegistry registry,
      final ToolCallingManager toolCallingManager,
      final List<AnthropicHttpClientBuilderCustomizer> httpClientCustomizers,
      final UserModelsProperties userModelsProperties) {
    return new AnthropicUserChatClients(
        registry,
        applicationDefaults(chatModel),
        // The model itself, not its credential: a row with nothing of its own borrows the built
        // clients, which works on both backends. See the field's javadoc for why lending the API
        // key
        // was wrong.
        chatModel,
        toolCallingManager,
        httpClientCustomizers,
        userModelsProperties.cacheSize());
  }

  @Bean
  @ConditionalOnUserModels
  @ConditionalOnBean(AnthropicChatModel.class)
  @ConditionalOnMissingBean
  BuiltinModels builtinModels(
      final AnthropicChatModel chatModel,
      final ObjectProvider<AnthropicConnectionProperties> connectionProperties,
      final AnthropicProperties properties) {
    return new AnthropicBuiltinModels(
        baseUrlOf(connectionProperties),
        keyOf(connectionProperties),
        chatModel.getOptions().getModel(),
        properties.vertexBacked());
  }

  /**
   * The options a user's endpoint starts from: the application's own where this module built the
   * chat model, and null where it did not.
   *
   * <p>Copying the application's matters because Spring AI takes supplied options whole rather than
   * merging, so anything not copied is silently dropped — which {@code
   * AnthropicUserChatClients#optionsFor} sets out. Where the application is on another provider
   * there is nothing to copy and nothing that would be right to copy.
   */
  private static AnthropicChatOptions applicationDefaults(
      final ObjectProvider<AnthropicChatModel> chatModel) {
    final var model = chatModel.getIfAvailable();
    return model == null ? null : model.getOptions();
  }

  private static String keyOf(final ObjectProvider<AnthropicConnectionProperties> properties) {
    final var connection = properties.getIfAvailable();
    return connection == null ? null : connection.getApiKey();
  }

  private static String baseUrlOf(final ObjectProvider<AnthropicConnectionProperties> properties) {
    final var connection = properties.getIfAvailable();
    return connection == null ? null : connection.getBaseUrl();
  }
}
