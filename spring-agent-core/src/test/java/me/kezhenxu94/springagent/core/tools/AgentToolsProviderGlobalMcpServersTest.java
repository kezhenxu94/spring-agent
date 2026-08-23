package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.Tools;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.Tools.AskUserQuestion;
import me.kezhenxu94.springagent.core.dao.models.McpServerConfig;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import me.kezhenxu94.springagent.core.tools.mcp.McpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * {@code buildMcpTools} always includes {@link McpServerConfig#SHARED_WITH_ALL} in the identifiers
 * passed to {@code findAccessibleTo}, so a persisted config carrying that sentinel in {@code
 * sharedWith} — regardless of who owns it — is offered to every user, on every backend, with zero
 * query changes (see {@code McpServerConfigRepo} implementations).
 */
class AgentToolsProviderGlobalMcpServersTest {

  @TempDir Path workspace;

  @Configuration
  static class NoGlobalTools {}

  @Test
  @DisplayName("findAccessibleTo is always called with the SHARED_WITH_ALL sentinel included")
  void identifiersAlwaysIncludeTheSharedWithAllSentinel() throws Exception {
    final var workspaces = mock(UserWorkspaceFactory.class);
    when(workspaces.forRequest(eq("ou_1"), nullable(String.class), nullable(String.class)))
        .thenReturn(new UserHome(workspace));
    final var repo = mock(McpServerConfigRepo.class);
    when(repo.findAccessibleTo(any(), any())).thenReturn(List.of());

    try (var context = new AnnotationConfigApplicationContext(NoGlobalTools.class)) {
      final var provider =
          new AgentToolsProvider(
              workspaces, repo, mock(McpClientFactory.class), context, properties());

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
          true);

      final var identifiers = ArgumentCaptor.forClass(java.util.Collection.class);
      verify(repo).findAccessibleTo(eq("ou_1"), identifiers.capture());
      assertThat(identifiers.getValue()).contains(McpServerConfig.SHARED_WITH_ALL, "ou_1", "oc_1");
    }
  }

  @Test
  @DisplayName("identifiers still include the sentinel when there is no chatId")
  void identifiersIncludeSentinelWithoutChatId() throws Exception {
    final var workspaces = mock(UserWorkspaceFactory.class);
    when(workspaces.forRequest(eq("ou_1"), nullable(String.class), nullable(String.class)))
        .thenReturn(new UserHome(workspace));
    final var repo = mock(McpServerConfigRepo.class);
    when(repo.findAccessibleTo(any(), any())).thenReturn(List.of());

    try (var context = new AnnotationConfigApplicationContext(NoGlobalTools.class)) {
      final var provider =
          new AgentToolsProvider(
              workspaces, repo, mock(McpClientFactory.class), context, properties());

      provider.build("ou_1", null, Map.of());

      final var identifiers = ArgumentCaptor.forClass(java.util.Collection.class);
      verify(repo).findAccessibleTo(eq("ou_1"), identifiers.capture());
      assertThat(identifiers.getValue())
          .containsExactlyInAnyOrder("ou_1", McpServerConfig.SHARED_WITH_ALL);
    }
  }

  private static SpringAgentProperties properties() {
    return new SpringAgentProperties(
        null,
        new Ai(
            null,
            Set.of(),
            Map.of(),
            null,
            new Tools(new AskUserQuestion(true, null), null),
            "You are an agent.",
            null,
            null),
        Locale.ENGLISH);
  }
}
