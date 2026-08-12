package me.kezhenxu94.springagent.integration.feishu.config;

import com.lark.oapi.Client;
import me.kezhenxu94.springagent.integration.feishu.dao.FeishuMessageRepo;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Wires the Feishu integration: the Lark client plus every {@code @Component} under {@code
 * me.kezhenxu94.springagent.integration.feishu}.
 *
 * <p>Set {@code app.feishu.enabled=false} to leave all of it out — importantly including {@link
 * FeishuEventHandler}, which opens a websocket to Feishu as soon as it is created. The switch is a
 * dedicated flag rather than a check on {@code app.feishu.app-id} because conditions are evaluated
 * against raw property values, and the credentials are configured as {@code ${FEISHU_APP_ID}}
 * placeholders that fail to resolve precisely when Feishu is not set up.
 */
@AutoConfiguration
@ComponentScan(
    basePackages = "me.kezhenxu94.springagent.integration.feishu",
    // This class lives inside the scanned package; without the exclude it would be registered
    // both as a scanned @Configuration and as an imported auto-configuration.
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = FeishuAutoConfiguration.class))
@EnableMongoRepositories(basePackageClasses = FeishuMessageRepo.class)
@EnableConfigurationProperties(FeishuProperties.class)
@ConditionalOnProperty(
    prefix = "app.feishu",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class FeishuAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  Client feishuClient(final FeishuProperties feishuProperties) {
    return new Client.Builder(feishuProperties.appId(), feishuProperties.appSecret()).build();
  }
}
