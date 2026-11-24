package me.kezhenxu94.springagent.core.memory;

import com.google.common.base.Strings;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.PromptVariablesContributor;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.TenantWrites;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import org.springframework.stereotype.Component;

/**
 * Tells the model which memories this run reaches, where each one is and whether it may write
 * there, in the turn context at the top of the user's message rather than in the system prompt.
 *
 * <p>The block names the user's and the group's own directories, so in the system prompt it made
 * every chat's system prompt different from every other's, and everything after it — the tool
 * search's instructions and catalogue — was never shared between two chats. {@code
 * MemoryToolsAdvisor} keeps the part that is the same for everybody, how memory works.
 *
 * <p>Empty for a run with no user id, which has no memory to write into, and for one offered no
 * tools, which could not use one.
 */
@Component
@RequiredArgsConstructor
public class MemoryScopesPromptVariables implements PromptVariablesContributor {
  public static final String VARIABLE = "memoryScopes";

  private final UserWorkspaceFactory userWorkspaceFactory;
  private final Admins admins;
  private final TenantWrites tenantWrites;
  private final CoreMessages messages;

  @Override
  public Map<String, Object> variables(final AgentRequest request) {
    if (Strings.isNullOrEmpty(request.userId()) || !request.scenario().tools()) {
      return Map.of();
    }
    final var block =
        MemoryScopes.forRequest(
                userWorkspaceFactory,
                admins.isAdmin(request.userId()),
                tenantWrites.openToEveryone(),
                request.userId(),
                request.groupId(),
                request.tenantId())
            .describe(messages);
    return block.isBlank() ? Map.of() : Map.of(VARIABLE, block);
  }
}
