package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Locale;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.storage.FileSystemStorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.ResourceBundleMessageSource;

class HomeDirsPromptVariablesTest {

  @TempDir Path location;

  HomeDirsPromptVariables variables;

  @BeforeEach
  void setUp() {
    variables =
        new HomeDirsPromptVariables(
            new UserWorkspaceFactory(
                FileSystemStorageProperties.builder().location(location.toString()).build()),
            messagesIn(Locale.ENGLISH));
  }

  private static CoreMessages messagesIn(final Locale locale) {
    final var source = new ResourceBundleMessageSource();
    source.setBasename(CoreMessages.BASENAME);
    source.setDefaultEncoding("UTF-8");
    source.setFallbackToSystemLocale(false);
    return new CoreMessages(source, new SpringAgentProperties(null, null, locale));
  }

  private static AgentRequest.AgentRequestBuilder request() {
    return AgentRequest.builder()
        .scenario(BuiltInScenarios.CHAT)
        .userId("ou_1")
        .chatId("oc_1")
        .userMessage(user -> user.text("hi"));
  }

  private String homeDirs(final AgentRequest request) {
    return (String) variables.variables(request).get(HomeDirsPromptVariables.VARIABLE);
  }

  @Test
  @DisplayName("a request with no shared scope is told about its own home only")
  void ownHomeOnly() {
    assertThat(homeDirs(request().build()))
        .isEqualTo(
            "- "
                + location.resolve("ou_1")
                + " — yours alone, nobody else sees. Holds memories/, skills/, workspace/ and"
                + " artifacts/.");
  }

  @Test
  @DisplayName("each shared scope adds a line saying who else can read it")
  void sharedScopesSayWhoElseSees() {
    final var lines = homeDirs(request().groupId("oc_1").tenantId("t_1").build()).lines().toList();

    assertThat(lines).hasSize(3);
    assertThat(lines.get(0)).contains(location.resolve("ou_1").toString(), "yours alone");
    assertThat(lines.get(1))
        .contains(location.resolve("groups/oc_1").toString(), "everyone in this group chat");
    assertThat(lines.get(2))
        .contains(location.resolve("tenant/t_1").toString(), "across the whole company");
  }

  @Test
  @DisplayName("the block is written in the workspace's language, since the prompt around it is")
  void inTheWorkspacesLanguage() {
    variables =
        new HomeDirsPromptVariables(
            new UserWorkspaceFactory(
                FileSystemStorageProperties.builder().location(location.toString()).build()),
            messagesIn(Locale.of("zh", "CN")));

    assertThat(homeDirs(request().build()))
        .contains("只属于你，别人看不到")
        .doesNotContain("nobody else sees");
  }

  @Test
  @DisplayName("nothing is claimed about a run with no user to own a home")
  void noUser() {
    assertThat(variables.variables(request().userId(null).build())).isEmpty();
  }
}
