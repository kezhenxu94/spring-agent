package me.kezhenxu94.springagent.integration.slack.config;

import com.slack.api.methods.MethodsClient;
import me.kezhenxu94.springagent.core.config.ConditionalOnUserModels;
import me.kezhenxu94.springagent.core.usermodels.BuiltinModels;
import me.kezhenxu94.springagent.core.usermodels.UserModelProbe;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import me.kezhenxu94.springagent.integration.slack.usermodels.SlackConfigForm;
import me.kezhenxu94.springagent.integration.slack.usermodels.SlackConfigHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskExecutor;

/**
 * The Slack half of letting a user choose their own chat model: the {@code /config} form.
 *
 * <p>Conditional on the same key as the feature itself, so that a deployment which cannot store an
 * API token sealed never offers a form asking for one. Where these beans are absent, {@code
 * /config} is not a command and the message reaches the agent like any other.
 */
@AutoConfiguration
@ConditionalOnUserModels
public class SlackUserModelsConfiguration {

  @Bean
  @ConditionalOnMissingBean
  SlackConfigForm slackConfigForm(final SlackMessages messages) {
    return new SlackConfigForm(messages);
  }

  @Bean
  @ConditionalOnMissingBean
  SlackConfigHandler slackConfigHandler(
      final MethodsClient slack,
      final UserModelRegistry registry,
      final UserModelProbe probe,
      final BuiltinModels builtins,
      final SlackConfigForm form,
      final SlackMessages messages,
      @Qualifier("applicationTaskExecutor") final TaskExecutor taskExecutor) {
    return new SlackConfigHandler(slack, registry, probe, builtins, form, messages, taskExecutor);
  }
}
