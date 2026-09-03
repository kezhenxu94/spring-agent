package me.kezhenxu94.springagent.integration.feishu.config;

import com.lark.oapi.Client;
import me.kezhenxu94.springagent.core.config.ConditionalOnUserModels;
import me.kezhenxu94.springagent.core.usermodels.BuiltinModels;
import me.kezhenxu94.springagent.core.usermodels.UserModelProbe;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import me.kezhenxu94.springagent.integration.feishu.usermodels.FeishuConfigForm;
import me.kezhenxu94.springagent.integration.feishu.usermodels.FeishuConfigHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.task.TaskExecutor;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Feishu half of letting a user choose their own chat model: the {@code /config} card.
 *
 * <p>Conditional on the same key as the feature itself, so that a deployment which cannot store an
 * API token sealed never offers a card asking for one. Where these beans are absent, {@code
 * /config} is not a command and the message reaches the agent like any other.
 *
 * <p>And on Feishu being switched on, which is not the same condition and is not implied by being
 * on the classpath: everything here is built from beans {@link FeishuAutoConfiguration} contributes
 * — the Lark client, this module's message source — and that configuration is gated on {@code
 * app.feishu.enabled}. An auto-configuration is still an auto-configuration on a classpath the
 * surface has been switched off on, so without this a deployment that seals API tokens and does not
 * talk to Feishu fails to start, asking for a {@code FeishuMessages} bean nothing declares.
 */
@AutoConfiguration
@ConditionalOnUserModels
@ConditionalOnProperty(
    prefix = "app.feishu",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class FeishuUserModelsConfiguration {

  @Bean
  @ConditionalOnMissingBean
  FeishuConfigForm feishuConfigForm(
      final JsonMapper objectMapper,
      final FeishuMessages messages,
      @Value("${app.feishu.config-form:classpath:/feishu/config-form.json}")
          final Resource configForm) {
    return new FeishuConfigForm(objectMapper, messages, configForm);
  }

  @Bean
  @ConditionalOnMissingBean
  FeishuConfigHandler feishuConfigHandler(
      final Client feishu,
      final UserModelRegistry registry,
      final UserModelProbe probe,
      final BuiltinModels builtins,
      final FeishuConfigForm form,
      final JsonMapper objectMapper,
      final FeishuMessages messages,
      @Qualifier("applicationTaskExecutor") final TaskExecutor taskExecutor) {
    return new FeishuConfigHandler(
        feishu, registry, probe, builtins, form, objectMapper, messages, taskExecutor);
  }
}
