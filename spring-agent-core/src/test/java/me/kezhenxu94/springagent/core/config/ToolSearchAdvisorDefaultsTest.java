package me.kezhenxu94.springagent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class ToolSearchAdvisorDefaultsTest {

  private static final String SESSION_ID_KEY_NAME =
      "spring.ai.chat.client.tool-search-advisor.session-id-key-name";

  private final ToolSearchAdvisorDefaults defaults = new ToolSearchAdvisorDefaults();

  @Test
  void shouldDefaultTheIndexKeyName() {
    final var environment = new StandardEnvironment();

    defaults.postProcessEnvironment(environment, null);

    assertThat(environment.getProperty(SESSION_ID_KEY_NAME)).isEqualTo(SpringAgent.TOOL_INDEX_KEY);
  }

  @Test
  void shouldLetTheApplicationOverrideIt() {
    final var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of(SESSION_ID_KEY_NAME, "conversationId")));

    defaults.postProcessEnvironment(environment, null);

    assertThat(environment.getProperty(SESSION_ID_KEY_NAME)).isEqualTo("conversationId");
  }
}
