package me.kezhenxu94.springagent.integration.telegram.config;

import me.kezhenxu94.springagent.core.config.ConditionalOnUserModels;
import me.kezhenxu94.springagent.core.usermodels.BuiltinModels;
import me.kezhenxu94.springagent.core.usermodels.UserChatClients;
import me.kezhenxu94.springagent.core.usermodels.UserModelProbe;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramBotApi;
import me.kezhenxu94.springagent.integration.telegram.usermodels.TelegramConfigForm;
import me.kezhenxu94.springagent.integration.telegram.usermodels.TelegramConfigHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskExecutor;

/**
 * The Telegram half of letting a user choose their own chat model: {@code /config}.
 *
 * <p>Conditional on the feature's own key and on Telegram being switched on, for the reasons {@code
 * SlackUserModelsConfiguration} gives for each. Where these beans are absent, {@code /config} is
 * answered with a line saying so rather than handed to the agent — see the receive handler.
 */
@AutoConfiguration
@ConditionalOnUserModels
@ConditionalOnProperty(
    prefix = "app.telegram",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class TelegramUserModelsConfiguration {

  @Bean
  @ConditionalOnMissingBean
  TelegramConfigForm telegramConfigForm(final TelegramMessages messages) {
    return new TelegramConfigForm(messages);
  }

  @Bean
  @ConditionalOnMissingBean
  TelegramConfigHandler telegramConfigHandler(
      final TelegramBotApi telegram,
      final UserModelRegistry registry,
      final UserModelProbe probe,
      final BuiltinModels builtins,
      final UserChatClients chatClients,
      final TelegramConfigForm form,
      final TelegramMessages messages,
      @Qualifier(TelegramAutoConfiguration.TASK_EXECUTOR) final TaskExecutor taskExecutor) {
    return new TelegramConfigHandler(
        telegram, registry, probe, builtins, chatClients, form, messages, taskExecutor);
  }
}
