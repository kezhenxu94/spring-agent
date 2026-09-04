package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockingDetails;

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
        // Except the suite's own stand-ins: a @MockitoBean is registered even where there is no
        // definition to override, so the mock keeping every other test off Feishu's network is
        // here too, named after the class it replaces. Only a real one would be a failure.
        .filteredOn(name -> !mockingDetails(context.getBean(name)).isMock())
        .isEmpty();
  }
}
