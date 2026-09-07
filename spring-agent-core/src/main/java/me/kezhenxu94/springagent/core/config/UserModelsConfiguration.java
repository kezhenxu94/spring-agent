package me.kezhenxu94.springagent.core.config;

import me.kezhenxu94.springagent.core.dao.repo.UserModelConfigRepo;
import me.kezhenxu94.springagent.core.security.AesGcmSealer;
import me.kezhenxu94.springagent.core.tools.AgentTool;
import me.kezhenxu94.springagent.core.usermodels.BuiltinModels;
import me.kezhenxu94.springagent.core.usermodels.UserChatClients;
import me.kezhenxu94.springagent.core.usermodels.UserModelCommand;
import me.kezhenxu94.springagent.core.usermodels.UserModelProbe;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import me.kezhenxu94.springagent.core.usermodels.UserModelTools;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Lets a user run their own chat model, but only where their API tokens can be stored sealed.
 *
 * <p>The two beans that actually reach an endpoint — {@link UserChatClients} and {@link
 * BuiltinModels} — are not here: they name a wire protocol, so a {@code spring-agent-provider-*}
 * module publishes them. This class holds what is the same whoever serves the model: the registry
 * that seals the tokens, the tools and command a person drives it with, and the probe.
 *
 * <p>They are injected rather than looked up optionally, so a deployment that sets the encryption
 * key with no provider module on its classpath fails to start. That is right: it is a
 * misconfiguration, not a mode. The key being unset is the mode.
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

  @Bean
  @ConditionalOnMissingBean
  UserModelCommand userModelCommand(final UserModelRegistry registry, final CoreMessages messages) {
    return new UserModelCommand(registry, messages);
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
