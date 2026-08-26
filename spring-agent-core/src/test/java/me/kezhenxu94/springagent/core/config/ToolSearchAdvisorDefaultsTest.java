package me.kezhenxu94.springagent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class ToolSearchAdvisorDefaultsTest {

  private static final String SESSION_ID_KEY_NAME =
      "spring.ai.chat.client.tool-search-advisor.session-id-key-name";

  private static final String SYSTEM_MESSAGE_SUFFIX =
      "spring.ai.chat.client.tool-search-advisor.system-message-suffix";

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

  @Test
  @DisplayName("the suffix is core's own prompt, in the language the workspace is configured for")
  void shouldDefaultTheSuffixInTheWorkspacesLanguage() {
    final var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of("app.locale", "zh_CN")));

    defaults.postProcessEnvironment(environment, null);

    assertThat(environment.getProperty(SYSTEM_MESSAGE_SUFFIX))
        .contains("toolSearchTool")
        .contains("用你即将作答的语言来思考");
  }

  @Test
  @DisplayName("a locale core ships no translation for still gets a suffix, rather than none")
  void shouldFallBackToTheBaseSuffix() {
    final var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of("app.locale", "fr_FR")));

    defaults.postProcessEnvironment(environment, null);

    assertThat(environment.getProperty(SYSTEM_MESSAGE_SUFFIX))
        .contains("Think in the language you are going to reply in");
  }

  @Test
  void shouldLetTheApplicationOverrideTheSuffix() {
    final var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of(SYSTEM_MESSAGE_SUFFIX, "ours")));

    defaults.postProcessEnvironment(environment, null);

    assertThat(environment.getProperty(SYSTEM_MESSAGE_SUFFIX)).isEqualTo("ours");
  }
}
