package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import me.kezhenxu94.springagent.integration.telegram.api.TelegramBotApi;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramAutoConfiguration;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramUpdatePoller;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** Proves the Telegram integration is genuinely optional rather than merely separated. */
@org.springframework.context.annotation.Import(AbstractIntegrationTest.TelegramStub.class)
@SpringBootTest(properties = "app.telegram.enabled=false")
class TelegramDisabledTest extends AbstractIntegrationTest {

  /** Where every bean this module contributes lives, and nothing else does. */
  private static final String MODULE_PACKAGE = "me.kezhenxu94.springagent.integration.telegram";

  @Autowired ApplicationContext context;

  @Test
  @DisplayName("the application starts with no Telegram beans at all")
  void contextLoadsWithoutTelegram() {
    assertThat(context.getBeansOfType(TelegramAutoConfiguration.class)).isEmpty();

    // By the bean's own package rather than by its name — but the two this suite registers itself,
    // the stubbed client and the mocked poll, are from that package and are supposed to be there.
    // Asking where the class comes from, less those, is the question actually being asked: did
    // this module contribute anything?
    assertThat(context.getBeanDefinitionNames())
        .filteredOn(
            name -> {
              final var type = context.getType(name);
              return type != null
                  && type.getName().startsWith(MODULE_PACKAGE)
                  && !type.equals(TelegramBotApi.class)
                  && !type.equals(TelegramUpdatePoller.class);
            })
        .isEmpty();
  }
}
