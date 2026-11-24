package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.advisors.toolsearch.ToolGroupsAdvisor;
import me.kezhenxu94.springagent.core.advisors.toolsearch.ToolGroupsAdvisor.ToolGroup;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai;
import me.kezhenxu94.springagent.core.config.TenantWrites;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeBase;
import me.kezhenxu94.springagent.core.storage.FileSystemStorageProperties;
import me.kezhenxu94.springagent.core.support.TestI18n;
import me.kezhenxu94.springagent.core.tools.mcp.McpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * Which group the tool search's catalogue puts each MCP tool in, and what it says the group is for.
 * Only the composition can tell: an app-wide server's tools carry no prefix at all.
 */
class AgentToolsProviderToolGroupsTest {

  @TempDir Path location;

  @Test
  @DisplayName(
      "a server's tools are one group under its name, with its prefix when they share it, and a"
          + " configured description before what the server says of itself")
  void groupsByServer() throws Exception {
    // A user's own server: named after its prefix, described at registration.
    final var own = client("git", "Git", "issues and pull requests", null, null);
    // App-wide, described in configuration and by itself: configuration wins.
    final var configured = client("app", "weather", null, "server says forecasts", null);
    // App-wide, described only by itself.
    final var described = client("app", "tickets", null, null, "Open and close tickets.\nMore.");
    // App-wide, described by nothing.
    final var silent = client("app", "plain", null, null, null);

    final var groups =
        groupsFor(
            List.of(
                tool(own, "git_search"),
                tool(own, "git_close"),
                tool(configured, "forecast"),
                tool(described, "open_ticket"),
                tool(silent, "ping")),
            Map.of("app.ai.tools.mcp-descriptions.weather", "forecasts by city"));

    assertThat(groups.get("git_search"))
        .isEqualTo(new ToolGroup("Git", "git_", "issues and pull requests"))
        .isEqualTo(groups.get("git_close"));
    assertThat(groups.get("forecast")).isEqualTo(new ToolGroup("weather", "", "forecasts by city"));
    assertThat(groups.get("open_ticket").purpose()).startsWith("Open and close tickets.");
    assertThat(groups.get("ping")).isEqualTo(new ToolGroup("plain", "", ""));
  }

  private static McpSyncClient client(
      final String name,
      final String title,
      final String registered,
      final String serverDescription,
      final String instructions) {
    final var client = mock(McpSyncClient.class);
    when(client.getClientInfo())
        .thenReturn(
            McpSchema.Implementation.builder(name, "1")
                .title(title)
                .description(registered)
                .build());
    when(client.getServerInfo())
        .thenReturn(
            McpSchema.Implementation.builder("server", "1").description(serverDescription).build());
    when(client.getServerInstructions()).thenReturn(instructions);
    return client;
  }

  private static ToolCallback tool(final McpSyncClient client, final String name) {
    return SyncMcpToolCallback.builder()
        .mcpClient(client)
        .tool(McpSchema.Tool.builder().name(name).inputSchema(Map.of("type", "object")).build())
        .prefixedToolName(name)
        .build();
  }

  @SuppressWarnings("unchecked")
  private Map<String, ToolGroup> groupsFor(
      final List<ToolCallback> callbacks, final Map<String, Object> properties) throws Exception {
    final var context = new AnnotationConfigApplicationContext();
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(new MapPropertySource("test", properties));
    context.refresh();
    final ObjectProvider<KnowledgeBase> bases = mock(ObjectProvider.class);
    final var provider =
        new AgentToolsProvider(
            new UserWorkspaceFactory(
                FileSystemStorageProperties.builder().location(location.toString()).build()),
            mock(McpServerConfigRepo.class),
            mock(McpClientFactory.class),
            context,
            properties(),
            new Admins(properties()),
            new TenantWrites(properties(), new Admins(properties())),
            TestI18n.english(),
            bases);
    return provider
        .compose(
            AgentRequest.builder()
                .scenario(BuiltInScenarios.CHAT)
                .userId("ou_1")
                .chatId("oc_1")
                .toolCallbacks(callbacks)
                .userMessage(user -> user.text("hi"))
                .build(),
            Map.of(),
            todos -> {},
            null,
            false,
            references -> {})
        .advisors()
        .stream()
        .filter(ToolGroupsAdvisor.class::isInstance)
        .map(ToolGroupsAdvisor.class::cast)
        .findFirst()
        .orElseThrow()
        .groups();
  }

  private static SpringAgentProperties properties() {
    return new SpringAgentProperties(
        new Ai(Set.of(), null, Map.of(), null, null, null, "You are an agent.", null, null, null),
        Locale.ENGLISH,
        null,
        null);
  }
}
