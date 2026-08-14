package me.kezhenxu94.springagent.integration.feishu.config;

import com.lark.oapi.Client;
import me.kezhenxu94.springagent.integration.feishu.aot.FeishuRuntimeHints;
import me.kezhenxu94.springagent.integration.feishu.aot.LarkSdkRuntimeHints;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.ImportRuntimeHints;

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
    // Without this, the scan would also register this class, which is already imported.
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = FeishuAutoConfiguration.class))
@EnableConfigurationProperties(FeishuProperties.class)
@ImportRuntimeHints({FeishuRuntimeHints.class, LarkSdkRuntimeHints.class})
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
