package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.slack.api.bolt.App;
import me.kezhenxu94.springagent.integration.slack.config.SlackAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** Proves the Slack integration is genuinely optional rather than merely separated. */
@org.springframework.context.annotation.Import(AbstractIntegrationTest.SlackStub.class)
@SpringBootTest(properties = "app.slack.enabled=false")
class SlackDisabledTest extends AbstractIntegrationTest {

  /** Where every bean this module contributes lives, and nothing else does. */
  private static final String MODULE_PACKAGE = "me.kezhenxu94.springagent.integration.slack";

  @Autowired ApplicationContext context;

  @Test
  @DisplayName("the application starts with no Slack beans at all")
  void contextLoadsWithoutSlack() {
    assertThat(context.getBeansOfType(SlackAutoConfiguration.class)).isEmpty();
    assertThat(context.getBeansOfType(App.class)).isEmpty();

    // By the bean's own package rather than by its name. A bean name is generated from the type
    // when nothing else names it, so the SocketModeApp this suite mocks is registered as
    // "com.slack.api.bolt.jakarta_socket_mode.SocketModeApp#0" — which contains "slack" and is
    // supposed to be there. Asking where the class comes from is the question actually being
    // asked: did this module contribute anything?
    assertThat(context.getBeanDefinitionNames())
        .filteredOn(
            name -> {
              final var type = context.getType(name);
              return type != null && type.getName().startsWith(MODULE_PACKAGE);
            })
        .isEmpty();
  }
}
