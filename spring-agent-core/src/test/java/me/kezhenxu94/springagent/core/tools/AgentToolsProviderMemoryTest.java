package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.advisors.MemoryToolsAdvisor;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import me.kezhenxu94.springagent.core.memory.MemoryTools;
import me.kezhenxu94.springagent.core.storage.FileSystemStorageProperties;
import me.kezhenxu94.springagent.core.support.TestI18n;
import me.kezhenxu94.springagent.core.tools.mcp.McpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * What a run is told about its memories, which is decided at composition and cannot be corrected
 * afterwards: the paragraph is rendered once, so a request whose scopes it describes wrongly
 * describes them wrongly for the whole turn.
 */
class AgentToolsProviderMemoryTest {

  @TempDir Path location;

  @Test
  @DisplayName(
      "a one-to-one chat is told about its own memory and the tenant, and that one is read only")
  void p2pDescribesTwoScopes() throws Exception {
    final var block = memoryBlock(null, "t_3");
    assertThat(block.lines()).hasSize(2);
    assertThat(block)
        .contains(location.resolve("ou_1").resolve("memories").toString())
        .contains(location.resolve("tenant/t_3").resolve("memories").toString())
        .contains("read only in this one-to-one chat");
    assertThat(block).doesNotContain("groups");
  }

  @Test
  @DisplayName("a group chat is told about all three, every one of them writable")
  void groupDescribesThreeScopes() throws Exception {
    final var block = memoryBlock("oc_9", "t_3");
    assertThat(block.lines()).hasSize(3);
    assertThat(block).contains(location.resolve("groups/oc_9").resolve("memories").toString());
    assertThat(block).doesNotContain("read only");
  }

  @Test
  @DisplayName("describing the memories creates none of their directories")
  void describingCreatesNothing() throws Exception {
    memoryBlock("oc_9", "t_3");
    // Composing a run used to create the user's memories/ every single time, through
    // HomeDir.folder(). It must not now do that for a group's or the tenant's on top.
    assertThat(location.resolve("ou_1")).doesNotExist();
    assertThat(location.resolve("groups")).doesNotExist();
    assertThat(location.resolve("tenant")).doesNotExist();
  }

  @Test
  @DisplayName("a scenario offered no tools is told nothing about memory either")
  void oneOffGetsNeither() throws Exception {
    final var composition = compose(BuiltInScenarios.ONE_OFF, "oc_9", "t_3");
    assertThat(composition.advisors()).noneMatch(MemoryToolsAdvisor.class::isInstance);
    assertThat(composition.tools()).noneMatch(MemoryTools.class::isInstance);
  }

  @Test
  @DisplayName("an ordinary run is offered the memory tools as a bean, not as advisor callbacks")
  void memoryToolsAreABean() throws Exception {
    final var composition = compose(BuiltInScenarios.CHAT, "oc_9", "t_3");
    assertThat(composition.tools()).anyMatch(MemoryTools.class::isInstance);
    assertThat(composition.advisors()).anyMatch(MemoryToolsAdvisor.class::isInstance);
  }

  /** The rendered block the advisor was built with, read back the way the advisor holds it. */
  private String memoryBlock(final String groupId, final String tenantId) throws Exception {
    final var advisor =
        compose(BuiltInScenarios.CHAT, groupId, tenantId).advisors().stream()
            .filter(MemoryToolsAdvisor.class::isInstance)
            .map(MemoryToolsAdvisor.class::cast)
            .findFirst()
            .orElseThrow();
    final var field = MemoryToolsAdvisor.class.getDeclaredField("memoryPrompt");
    field.setAccessible(true);
    return ((String) field.get(advisor))
        .lines()
        .filter(
            line ->
                line.startsWith("- own")
                    || line.startsWith("- group")
                    || line.startsWith("- tenant"))
        .reduce((a, b) -> a + "\n" + b)
        .orElse("");
  }

  private AgentToolsProvider.AgentComposition compose(
      final BuiltInScenarios scenario, final String groupId, final String tenantId)
      throws Exception {
    // A real factory over a temp directory rather than a mock: what these assert is which paths the
    // model is handed, and a mock would only give back whatever the test already decided.
    final var workspaces =
        new UserWorkspaceFactory(
            FileSystemStorageProperties.builder().location(location.toString()).build());
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(
          MemoryTools.class,
          () -> new MemoryTools(workspaces, new Admins(properties()), TestI18n.english()));
      context.refresh();
      final var provider =
          new AgentToolsProvider(
              workspaces,
              mock(McpServerConfigRepo.class),
              mock(McpClientFactory.class),
              context,
              properties(),
              new Admins(properties()),
              TestI18n.english(),
              mock(ObjectProvider.class));
      return provider.compose(
          AgentRequest.builder()
              .scenario(scenario)
              .userId("ou_1")
              .chatId("oc_1")
              .groupId(groupId)
              .tenantId(tenantId)
              .userMessage(user -> user.text("remember this"))
              .build(),
          Map.of(),
          todos -> {},
          null,
          false,
          references -> {});
    }
  }

  private static SpringAgentProperties properties() {
    return new SpringAgentProperties(
        new Ai(Set.of(), Map.of(), null, null, null, "You are an agent.", null, null),
        Locale.ENGLISH,
        null,
        null);
  }
}
