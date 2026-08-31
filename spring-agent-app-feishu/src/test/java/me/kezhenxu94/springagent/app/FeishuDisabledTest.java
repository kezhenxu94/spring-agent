package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.lark.oapi.Client;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** Proves the Feishu integration is genuinely optional rather than merely separated. */
@SpringBootTest(properties = "app.feishu.enabled=false")
class FeishuDisabledTest extends AbstractIntegrationTest {

  @Autowired ApplicationContext context;

  @Test
  @DisplayName("the application starts with no Feishu beans at all")
  void contextLoadsWithoutFeishu() {
    assertThat(context.getBeansOfType(FeishuAutoConfiguration.class)).isEmpty();
    assertThat(context.getBeansOfType(Client.class)).isEmpty();
    assertThat(context.getBeanNamesForType(Object.class))
        .filteredOn(name -> name.toLowerCase().contains("feishu"))
        .isEmpty();
  }
}
