package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.McpServerConfig;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

class McpServerManagementToolsShareTest {

  private static final String OWNER_ID = "ou_owner";
  private static final String OTHER_OWNER_ID = "ou_other";
  private static final String TARGET_USER_ID = "ou_recipient";
  private static final String CHAT_ID = "oc_chat";

  private final McpServerConfigRepo repo = mock(McpServerConfigRepo.class);
  private final McpClientFactory clientFactory = mock(McpClientFactory.class);
  private final ToolContext context =
      new ToolContext(
          Map.of(ToolContexts.KEY_USER_ID, OWNER_ID, ToolContexts.KEY_CHAT_ID, CHAT_ID));
  private McpServerManagementTools tools;

  @BeforeEach
  void setUp() {
    tools = new McpServerManagementTools(repo, clientFactory);
  }

  private McpServerConfig sampleConfig() {
    return McpServerConfig.builder()
        .ownerId(OWNER_ID)
        .name("ops")
        .transport(McpServerConfig.Transport.STREAMABLE_HTTP)
        .url("https://ops.example.com/mcp")
        .sharedWith(new ArrayList<>())
        .build();
  }

  @Test
  @DisplayName("owner can share a server with another user's open_id")
  void ownerCanShare() {
    final var config = sampleConfig();
    when(repo.findByOwnerIdAndName(OWNER_ID, "ops")).thenReturn(Optional.of(config));

    final var result = tools.shareMcpServer("ops", TARGET_USER_ID, context);

    assertThat(result).contains("Successfully shared");
    assertThat(config.sharedWith()).contains(TARGET_USER_ID);
    // Saves the loaded entity in place (not a toBuilder() rebuild), so auditing fields
    // (createdDate/createdBy/etc., inherited from AuditingModel) are preserved.
    verify(repo).save(config);
  }

  @Test
  @DisplayName("sharing twice with the same target is a no-op")
  void sharingTwiceIsNoOp() {
    final var config = sampleConfig();
    config.sharedWith().add(TARGET_USER_ID);
    when(repo.findByOwnerIdAndName(OWNER_ID, "ops")).thenReturn(Optional.of(config));

    final var result = tools.shareMcpServer("ops", TARGET_USER_ID, context);

    assertThat(result).contains("already shared");
    verify(repo, never()).save(any());
  }

  @Test
  @DisplayName("non-owner cannot share a server they don't own")
  void nonOwnerCannotShare() {
    when(repo.findByOwnerIdAndName(OWNER_ID, "ops")).thenReturn(Optional.empty());

    final var result = tools.shareMcpServer("ops", TARGET_USER_ID, context);

    assertThat(result).contains("Error").contains("Only servers you own can be shared");
    verify(repo, never()).save(any());
  }

  @Test
  @DisplayName("unshare removes a previously granted access")
  void unshareRemovesAccess() {
    final var config = sampleConfig();
    config.sharedWith().add(TARGET_USER_ID);
    when(repo.findByOwnerIdAndName(OWNER_ID, "ops")).thenReturn(Optional.of(config));

    final var result = tools.unshareMcpServer("ops", TARGET_USER_ID, context);

    assertThat(result).contains("Successfully revoked");
    assertThat(config.sharedWith()).doesNotContain(TARGET_USER_ID);
    verify(repo).save(config);
  }

  @Test
  @DisplayName("unsharing a target that was never shared with is a no-op")
  void unsharingNonExistentTargetIsNoOp() {
    final var config = sampleConfig();
    when(repo.findByOwnerIdAndName(OWNER_ID, "ops")).thenReturn(Optional.of(config));

    final var result = tools.unshareMcpServer("ops", TARGET_USER_ID, context);

    assertThat(result).contains("was not shared");
    verify(repo, never()).save(any());
  }

  @Test
  @DisplayName(
      "listing shows owned servers with their share list, and shared-with-you servers"
          + " without connection details")
  void listingSeparatesOwnedAndShared() {
    final var owned = sampleConfig();
    owned.sharedWith().add(TARGET_USER_ID);
    when(repo.findByOwnerId(OWNER_ID)).thenReturn(List.of(owned));

    final var sharedToMe =
        McpServerConfig.builder()
            .ownerId(OTHER_OWNER_ID)
            .name("weather")
            .transport(McpServerConfig.Transport.STREAMABLE_HTTP)
            .url("https://weather.example.com/mcp")
            .headers(java.util.Map.of("Authorization", "Bearer secret"))
            .sharedWith(new ArrayList<>(List.of(CHAT_ID)))
            .build();
    when(repo.findBySharedWithIn(List.of(OWNER_ID, CHAT_ID))).thenReturn(List.of(sharedToMe));

    final var result = tools.listMcpServers(context);

    assertThat(result).contains("Owned by you:").contains("ops").contains(TARGET_USER_ID);
    assertThat(result).contains("Shared with you:").contains("weather").contains(OTHER_OWNER_ID);
    assertThat(result).doesNotContain("weather.example.com").doesNotContain("secret");
  }
}
