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
 */
@AutoConfiguration
@ConditionalOnUserModels
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
