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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskExecutor;

/**
 * The Slack half of letting a user choose their own chat model: the {@code /config} form.
 *
 * <p>Conditional on the same key as the feature itself, so that a deployment which cannot store an
 * API token sealed never offers a form asking for one. Where these beans are absent, {@code
 * /config} is not a command and the message reaches the agent like any other.
 *
 * <p>And on Slack being switched on, which is not the same condition and is not implied by being on
 * the classpath: everything here is built from beans {@link SlackAutoConfiguration} contributes —
 * the Slack client, this module's message source — and that configuration is gated on {@code
 * app.slack.enabled}. An auto-configuration is still an auto-configuration on a classpath the
 * surface has been switched off on, so without this a deployment that seals API tokens and does not
 * talk to Slack fails to start, asking for a {@code SlackMessages} bean nothing declares.
 */
@AutoConfiguration
@ConditionalOnUserModels
@ConditionalOnProperty(
    prefix = "app.slack",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
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
      @Qualifier(SlackAutoConfiguration.TASK_EXECUTOR) final TaskExecutor taskExecutor) {
    return new SlackConfigHandler(slack, registry, probe, builtins, form, messages, taskExecutor);
  }
}
