package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.Tools;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.Tools.AskUserQuestion;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import me.kezhenxu94.springagent.core.support.TestI18n;
import me.kezhenxu94.springagent.core.tools.mcp.McpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * A skill is a tool of its own now, and a skill is a file the user writes — so part of what a
 * composition offers the model is, for the first time, named by a user. That makes two things worth
 * asserting about the run rather than about {@link SkillsTool}: a skill reaches the model as a
 * tool, and a skill that has named itself after one of this repository's own tools collides with
 * nothing, because the tool it becomes is prefixed out of that namespace.
 */
class AgentToolsProviderSkillToolsTest {

  @TempDir Path home;

  @Configuration
  static class NoGlobalTools {}

  @Test
  @DisplayName("a skill reaches the run as a tool of its own, named after the skill")
  void aSkillIsATool() throws Exception {
    writeSkill(
        "fill-a-pdf",
        """
        ---
        name: fill-a-pdf
        description: fills in a PDF form
        ---
        Open the form.
        """);

    assertThat(toolNames()).contains("skill_fill-a-pdf");
  }

  @Test
  @DisplayName("a skill named after one of the run's own tools collides with neither of them")
  void aSkillCannotShadowATool() throws Exception {
    writeSkill(
        "read",
        """
        ---
        name: Read
        description: a skill that has named itself after the file tool
        ---
        Which is allowed, because the tool it becomes is skill_Read.
        """);
    writeSkill(
        "fill-a-pdf",
        """
        ---
        name: fill-a-pdf
        description: fills in a PDF form
        ---
        Open the form.
        """);

    final var names = toolNames();

    assertThat(names)
        .as("the file tool is still the only Read, and the skill is offered beside it")
        .containsOnlyOnce("Read")
        .contains("skill_Read", "skill_fill-a-pdf");
  }

  private void writeSkill(final String directory, final String skillMd) throws IOException {
    final var skillDir = home.resolve("skills").resolve(directory);
    Files.createDirectories(skillDir);
    Files.writeString(skillDir.resolve("SKILL.md"), skillMd);
  }

  /** Every tool name the composed run offers the model. */
  @SuppressWarnings("unchecked")
  private List<String> toolNames() throws Exception {
    final var workspaces = mock(UserWorkspaceFactory.class);
    when(workspaces.forRequest(eq("ou_1"), nullable(String.class), nullable(String.class)))
        .thenReturn(new UserHome(home));
    when(workspaces.forOwner(eq("ou_1"))).thenReturn(new UserHome(home));
    final var repo = mock(McpServerConfigRepo.class);
    when(repo.findAccessibleTo(any(), any())).thenReturn(List.of());

    try (var context = new AnnotationConfigApplicationContext(NoGlobalTools.class)) {
      final var provider =
          new AgentToolsProvider(
              workspaces,
              repo,
              mock(McpClientFactory.class),
              context,
              properties(),
              new Admins(properties()),
              TestI18n.english(),
              mock(ObjectProvider.class));

      final var composition =
          provider.compose(
              AgentRequest.builder()
                  .scenario(BuiltInScenarios.CHAT)
                  .userId("ou_1")
                  .chatId("oc_1")
                  .userMessage(user -> user.text("hi"))
                  .build(),
              Map.of(),
              todos -> {},
              questions -> Map.of(),
              true,
              references -> {});
      try {
        // Named the way Spring AI will name them: a callback says so itself, and anything else is
        // its @Tool methods.
        final var names = new java.util.ArrayList<String>();
        for (final var tool : composition.tools()) {
          if (tool instanceof ToolCallback callback) {
            names.add(callback.getToolDefinition().name());
            continue;
          }
          for (final var method : tool.getClass().getMethods()) {
            final var annotation =
                method.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);
            if (annotation != null) {
              names.add(annotation.name().isBlank() ? method.getName() : annotation.name());
            }
          }
        }
        return List.copyOf(names);
      } finally {
        composition.mcpTools().close();
      }
    }
  }

  private static SpringAgentProperties properties() {
    return new SpringAgentProperties(
        new Ai(
            Set.of(),
            Map.of(),
            null,
            null,
            new Tools(new AskUserQuestion(true, null), null, null, null, null),
            "You are an agent.",
            null,
            null),
        Locale.ENGLISH,
        null,
        null);
  }
}
