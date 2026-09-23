package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
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
import me.kezhenxu94.springagent.core.config.TenantWrites;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import me.kezhenxu94.springagent.core.support.TestI18n;
import me.kezhenxu94.springagent.core.tools.mcp.McpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agent.tools.GlobTool;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.ListDirectoryTool;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * The three tools that find a file rather than read one, and the one thing that has to be true of
 * them: they take a path from the model, so they are confined to the homes the request reaches,
 * exactly as {@code FileSystemTools} is.
 *
 * <p>Worth a test of its own rather than a line in the composition tests because the confinement is
 * the library's and was not always there — {@code spring-ai-agent-utils} gained it in 0.12.0, and
 * before that these three read every path this application's operating system user could reach. A
 * version downgrade would compile, start, and quietly answer about {@code /etc}; this is what
 * notices.
 */
class AgentToolsProviderSearchToolsTest {

  @TempDir Path home;

  @TempDir Path elsewhere;

  @Configuration
  static class NoGlobalTools {}

  @Test
  @DisplayName("Glob, Grep and ListDirectory are offered, and see only the request's homes")
  void searchToolsAreComposedAndConfined() throws Exception {
    Files.writeString(home.resolve("note.md"), "the needle is here\n");
    Files.writeString(elsewhere.resolve("secret.md"), "the needle is here too\n");

    final var workspaces = mock(UserWorkspaceFactory.class);
    when(workspaces.forRequest(eq("ou_1"), nullable(String.class), nullable(String.class)))
        .thenReturn(new UserHome(home));
    when(workspaces.forOwner(eq("ou_1"))).thenReturn(new UserHome(home));

    try (var context = new AnnotationConfigApplicationContext(NoGlobalTools.class)) {
      final var provider =
          new AgentToolsProvider(
              workspaces,
              mock(McpServerConfigRepo.class),
              mock(McpClientFactory.class),
              context,
              properties(),
              new Admins(properties()),
              new TenantWrites(properties(), new Admins(properties())),
              TestI18n.english(),
              mock(org.springframework.beans.factory.ObjectProvider.class));

      final var composition =
          provider.compose(
              AgentRequest.builder()
                  .scenario(BuiltInScenarios.CHAT)
                  .userId("ou_1")
                  .chatId("oc_1")
                  .userMessage(user -> user.text("Where is the needle?"))
                  .build(),
              Map.of(),
              todos -> {},
              questions -> Map.of(),
              true,
              references -> {});

      assertThat(composition.tools())
          .hasAtLeastOneElementOfType(GlobTool.class)
          .hasAtLeastOneElementOfType(GrepTool.class)
          .hasAtLeastOneElementOfType(ListDirectoryTool.class);

      final var glob = only(composition.tools(), GlobTool.class);
      final var grep = only(composition.tools(), GrepTool.class);
      final var list = only(composition.tools(), ListDirectoryTool.class);

      // Omitting the path is the common call, and it lands in the requester's own home rather than
      // wherever this JVM was started — which is what makes the tool useful at all, since the
      // process's directory would be refused by the very check below.
      assertThat(glob.glob("*.md", null)).contains("note.md").doesNotContain("secret.md");
      assertThat(
              grep.grep(
                  "needle", null, null, null, null, null, null, null, null, null, null, null, null))
          .contains("note.md")
          .doesNotContain("secret.md");
      assertThat(list.listDirectory(null, null, null)).contains("note.md");

      // And a path the model names for itself is answered about only where it is one of the
      // request's homes. The library's wording is its own; what this pins is that it refused.
      assertThat(glob.glob("*.md", elsewhere.toString())).contains("Access denied");
      assertThat(
              grep.grep(
                  "needle",
                  elsewhere.toString(),
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null))
          .contains("Access denied");
      assertThat(list.listDirectory(elsewhere.toString(), null, null)).contains("Access denied");
    }
  }

  private static <T> T only(final Object[] tools, final Class<T> type) {
    return java.util.Arrays.stream(tools)
        .filter(type::isInstance)
        .map(type::cast)
        .findFirst()
        .orElseThrow();
  }

  private static SpringAgentProperties properties() {
    return new SpringAgentProperties(
        new Ai(
            Set.of(),
            null,
            Map.of(),
            null,
            null,
            new Tools(new AskUserQuestion(true, null), null, null, null, null),
            "You are an agent.",
            null,
            null,
            null),
        Locale.ENGLISH,
        null,
        null);
  }
}
