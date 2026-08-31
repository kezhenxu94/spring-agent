package me.kezhenxu94.springagent.core.config;

import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import me.kezhenxu94.springagent.core.dao.repo.UserModelConfigRepo;
import me.kezhenxu94.springagent.core.security.AesGcmSealer;
import me.kezhenxu94.springagent.core.tools.AgentTool;
import me.kezhenxu94.springagent.core.usermodels.BuiltinModels;
import me.kezhenxu94.springagent.core.usermodels.UserChatClients;
import me.kezhenxu94.springagent.core.usermodels.UserModelCommand;
import me.kezhenxu94.springagent.core.usermodels.UserModelProbe;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import me.kezhenxu94.springagent.core.usermodels.UserModelTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Lets a user run their own chat model, but only where their API tokens can be stored sealed.
 *
 * <p>Everything here hangs off {@code app.ai.user-models.encryption-key} being set. Without it
 * there is no registry, no tools, no command and no per-user client, and every run goes through the
 * application's own model exactly as before — the same shape as {@code app.ai.tools.shell.type:
 * none}. The alternative to a key is a column holding somebody's bearer token for a paid endpoint
 * in the clear, which is not a mode worth offering.
 *
 * <p>Registering the tools is deliberately conditional in the same breath. A tool the model can see
 * is a tool it will try, and {@code AddChatModel} with nowhere safe to put the token would fail
 * every time it was called, after the user had already typed the secret into a chat.
 *
 * <p>{@code @AgentTool} on the bean method rather than the class: the annotation is honoured on
 * factory methods, which is what lets a conditionally registered tool still be discovered by {@code
 * AgentToolsProvider.resolveScenarioTools}. See {@link KnowledgeToolsConfiguration}.
 */
@AutoConfiguration
@ConditionalOnUserModels
@EnableConfigurationProperties(UserModelsProperties.class)
public class UserModelsConfiguration {

  /** Names this key in the message when it is missing or unusable. */
  private static final String WHAT = "user chat models";

  @Bean
  @ConditionalOnMissingBean
  UserModelRegistry userModelRegistry(
      final UserModelConfigRepo repo, final UserModelsProperties properties) {
    return new UserModelRegistry(
        repo, new AesGcmSealer(properties.encryptionKey(), WHAT), properties.maxPerUser());
  }

  /**
   * @param defaultChatModel the application's own, which every user client is built as a variation
   *     of — see {@code UserChatClients#build} for why it is copied rather than rebuilt.
   */
  @Bean
  @ConditionalOnMissingBean
  UserChatClients userChatClients(
      @Qualifier("chatClient") final ChatClient defaultChatClient,
      final OpenAiChatModel defaultChatModel,
      final UserModelRegistry registry,
      final List<OpenAiHttpClientBuilderCustomizer> httpClientCustomizers,
      final UserModelsProperties properties) {
    return new UserChatClients(
        defaultChatClient,
        registry,
        defaultChatModel,
        httpClientCustomizers,
        properties.cacheSize());
  }

  @Bean
  @ConditionalOnMissingBean
  UserModelCommand userModelCommand(final UserModelRegistry registry, final CoreMessages messages) {
    return new UserModelCommand(registry, messages);
  }

  @Bean
  @ConditionalOnMissingBean
  BuiltinModels builtinModels(
      final OpenAiChatModel defaultChatModel,
      final List<OpenAiHttpClientBuilderCustomizer> httpClientCustomizers,
      final ObjectProvider<ObservationRegistry> observationRegistry) {
    return new BuiltinModels(
        defaultChatModel, httpClientCustomizers, observationRegistry.getIfAvailable());
  }

  @Bean
  @ConditionalOnMissingBean
  UserModelProbe userModelProbe(
      final UserChatClients chatClients,
      final CoreMessages messages,
      final UserModelsProperties properties) {
    return new UserModelProbe(chatClients, messages, properties.probeTimeout());
  }

  @Bean
  @AgentTool
  @ConditionalOnMissingBean
  UserModelTools userModelTools(
      final UserModelRegistry registry, final UserModelProbe probe, final CoreMessages messages) {
    return new UserModelTools(registry, probe, messages);
  }
}
