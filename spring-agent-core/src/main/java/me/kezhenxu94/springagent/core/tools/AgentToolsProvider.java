package me.kezhenxu94.springagent.core.tools;

import io.modelcontextprotocol.client.McpSyncClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentScenario;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springaicommunity.agent.tools.TodoWriteTool.TodoEventHandler;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentToolsProvider {

  private final UserWorkspaceFactory userWorkspaceFactory;
  private final McpServerConfigRepo mcpServerConfigRepo;
  private final McpClientFactory mcpClientFactory;
  private final ApplicationContext applicationContext;

  public record AgentTools(
      FileSystemTools fileSystemTools, Optional<ToolCallback> skillsTool, McpTools mcpTools) {}

  /**
   * Live MCP clients built for one request and the tool callbacks derived from them. Must be {@link
   * #close() closed} once the request completes to release connections.
   */
  public record McpTools(List<McpSyncClient> clients, ToolCallback[] callbacks)
      implements AutoCloseable {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(McpTools.class);

    @Override
    public void close() {
      for (final var client : clients) {
        try {
          client.close();
        } catch (Exception e) {
          log.warn("Failed to close MCP client", e);
        }
      }
    }
  }

  /**
   * Fully assembled agent inputs for one request: the raw {@link AgentTools}, the {@code tools} and
   * {@code toolCallbacks} arrays ready for {@code AgentRequest}, and the resolved memories
   * directory. Callers must close {@code agentTools().mcpTools()} once the request completes.
   */
  public record AgentComposition(
      AgentTools agentTools,
      Object[] tools,
      ToolCallback[] toolCallbacks,
      String memoriesRootDirectory) {}

  public AgentComposition compose(
      final String userId,
      final String chatId,
      final String chatType,
      final AgentScenario scenario,
      final TodoEventHandler todoEventHandler)
      throws IOException {
    final var agentTools = build(userId, chatId);
    final var memoriesRootDirectory = userWorkspaceFactory.forOwner(userId).memories().toString();

    final var tools = new ArrayList<Object>();
    tools.addAll(resolveScenarioTools(scenario));
    tools.add(agentTools.fileSystemTools());
    tools.add(TodoWriteTool.builder().todoEventHandler(todoEventHandler).build());

    final var callbacks = new ArrayList<ToolCallback>();
    agentTools.skillsTool().ifPresent(callbacks::add);
    final var mcpCallbacks = agentTools.mcpTools().callbacks();
    if (mcpCallbacks != null) {
      Collections.addAll(callbacks, mcpCallbacks);
    }

    return new AgentComposition(
        agentTools, tools.toArray(), callbacks.toArray(new ToolCallback[0]), memoriesRootDirectory);
  }

  private List<Object> resolveScenarioTools(final AgentScenario scenario) {
    return applicationContext.getBeansWithAnnotation(AgentTool.class).values().stream()
        .filter(
            bean -> {
              final var annotation =
                  AnnotationUtils.findAnnotation(bean.getClass(), AgentTool.class);
              return Arrays.asList(annotation.scenario()).contains(AgentScenario.ALL)
                  || Arrays.asList(annotation.scenario()).contains(scenario);
            })
        .toList();
  }

  public AgentTools build(String userId, String chatId) throws IOException {
    final var userWs = userWorkspaceFactory.forOwner(userId);

    final var fileSystemTools = FileSystemTools.builder().allowedDirectory(userWs.root()).build();

    final var skillsDir = userWs.skills().toString();
    final var skillsToolBuilder = SkillsTool.builder();
    skillsToolBuilder.addSkillsDirectory(skillsDir);
    Optional<ToolCallback> skillsTool;
    try {
      skillsTool = Optional.of(skillsToolBuilder.build());
    } catch (IllegalArgumentException e) {
      log.debug("No skills configured for directory: {}", skillsDir);
      skillsTool = Optional.empty();
    }

    final var mcpTools = buildMcpTools(userId, chatId);

    return new AgentTools(fileSystemTools, skillsTool, mcpTools);
  }

  private McpTools buildMcpTools(final String userId, final String chatId) {
    final var identifiers =
        chatId == null || chatId.isBlank() ? List.of(userId) : List.of(userId, chatId);
    final var configs = mcpServerConfigRepo.findAccessibleTo(userId, identifiers);
    final var clients = new ArrayList<McpSyncClient>();
    for (final var config : configs) {
      if (!config.isEnabled()) {
        continue;
      }
      try {
        clients.add(mcpClientFactory.createAndInitialize(config));
      } catch (Exception e) {
        log.warn(
            "Skipping MCP server '{}' for user {}: {}", config.getName(), userId, e.getMessage());
      }
    }
    final var callbacks =
        clients.isEmpty()
            ? new ToolCallback[0]
            : SyncMcpToolCallbackProvider.builder()
                .mcpClients(clients)
                .toolNamePrefixGenerator(new ServerNameToolPrefixGenerator())
                .build()
                .getToolCallbacks();
    return new McpTools(clients, callbacks);
  }
}
