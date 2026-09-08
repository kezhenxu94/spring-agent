package me.kezhenxu94.springagent.core.tools.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.McpServerConfig;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import me.kezhenxu94.springagent.core.support.TestI18n;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.beans.factory.ObjectProvider;

/**
 * What {@code AddMcpServer} does with a chosen tool prefix. Every case here is refused before the
 * registration probe dials out, which is why the mocked factory never needs to return a client.
 */
class McpServerManagementToolsPrefixTest {

  private static final String OWNER_ID = "ou_owner";
  private static final String OTHER_OWNER_ID = "ou_other";
  private static final String CHAT_ID = "oc_chat";

  private final McpServerConfigRepo repo = mock(McpServerConfigRepo.class);
  private final McpClientFactory clientFactory = mock(McpClientFactory.class);
  private final ToolContext context =
      new ToolContext(
          Map.of(ToolContexts.KEY_USER_ID, OWNER_ID, ToolContexts.KEY_CHAT_ID, CHAT_ID));
  private McpServerManagementTools tools;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    final ObjectProvider<McpStreamableHttpClientProperties> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    tools = new McpServerManagementTools(repo, clientFactory, provider, TestI18n.english());
    when(repo.findByOwnerIdAndName(any(), any())).thenReturn(Optional.empty());
    when(repo.findAccessibleTo(any(), any())).thenReturn(List.of());
  }

  @Test
  @DisplayName("an unusable prefix is refused without touching the repository")
  void refusesUnusablePrefix() {
    final var result = add("ops", "git hub");

    assertThat(result).contains("Error").contains("letters, digits, underscores and hyphens");
    verify(repo, never()).save(any());
  }

  @Test
  @DisplayName("a prefix another server the caller owns already uses is refused")
  void refusesPrefixTakenByOwnServer() {
    when(repo.findAccessibleTo(any(), any()))
        .thenReturn(List.of(server(OWNER_ID, "ops-staging", "ops")));

    final var result = add("ops-prod", "ops");

    assertThat(result).contains("Error").contains("already taken").contains("ops-staging");
    verify(repo, never()).save(any());
  }

  @Test
  @DisplayName("a prefix used by a server merely shared with the caller is refused too")
  void refusesPrefixTakenBySharedServer() {
    when(repo.findAccessibleTo(any(), any()))
        .thenReturn(List.of(server(OTHER_OWNER_ID, "their-ops", "ops")));

    final var result = add("ops-prod", "ops");

    assertThat(result).contains("Error").contains("their-ops");
    verify(repo, never()).save(any());
  }

  @Test
  @DisplayName("re-registering the same name keeps its own prefix rather than colliding with it")
  void reRegisteringTheSameServerIsNotACollision() {
    final var existing = server(OWNER_ID, "ops", "ops");
    when(repo.findByOwnerIdAndName(OWNER_ID, "ops")).thenReturn(Optional.of(existing));
    when(repo.findAccessibleTo(any(), any())).thenReturn(List.of(existing));

    // Reaches the probe, which the mocked factory answers with a null client — anything but the
    // prefix message means the collision check let it through.
    assertThat(add("ops", "ops")).doesNotContain("already taken");
  }

  @Test
  @DisplayName("a hash-derived prefix collides only with the same server name")
  void hashDerivedPrefixesDoNotCollideAcrossNames() {
    when(repo.findAccessibleTo(any(), any()))
        .thenReturn(List.of(server(OWNER_ID, "ops-staging", null)));

    assertThat(add("ops-prod", null)).doesNotContain("already taken");
  }

  private String add(final String name, final String toolPrefix) {
    return tools.addMcpServer(
        name, "https://ops.example.com/mcp", null, null, null, null, null, toolPrefix, context);
  }

  private static McpServerConfig server(
      final String ownerId, final String name, final String toolPrefix) {
    return McpServerConfig.builder().ownerId(ownerId).name(name).toolPrefix(toolPrefix).build();
  }
}
