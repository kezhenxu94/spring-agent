package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import me.kezhenxu94.springagent.core.config.CoreMessages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves that the notes the agent writes into a conversation itself resolve in an application that
 * does not name core's bundle — which this one deliberately no longer does, MessagesDefaults
 * appending it instead.
 *
 * <p>Here rather than in core, because what is being tested is the assembly: the post processor, an
 * application's own {@code spring.messages} settings, and Boot's message source auto-configuration
 * arriving at one list. Nothing fails when this breaks — every message comes back as its own key,
 * and the model reads the key as the note — so the assertion is that the answer is not the key.
 */
@SpringBootTest
class CoreMessagesResolveTest extends AbstractIntegrationTest {

  @Autowired CoreMessages messages;

  @Test
  @DisplayName("core's messages resolve without the application naming the bundle")
  void shouldResolveCoreMessages() {
    final var key = "run-shutting-down";

    assertThat(messages.get(key)).isNotEqualTo(key).isNotBlank();
  }
}
