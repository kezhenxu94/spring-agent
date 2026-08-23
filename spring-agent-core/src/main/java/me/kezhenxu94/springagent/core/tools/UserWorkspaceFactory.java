package me.kezhenxu94.springagent.core.tools;

import com.google.common.base.Strings;
import java.nio.file.Path;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.core.storage.StorageProperties;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserWorkspaceFactory {
  private final StorageProperties storageProperties;

  public UserHome forOwner(String ownerId) {
    return new UserHome(Path.of(storageProperties.getLocation(), ownerId), workspaceRoot(ownerId));
  }

  /**
   * A shared home for one group (e.g. one Feishu group chat), namespaced under "groups/" so a group
   * id can never collide with a bare userId.
   */
  public UserHome forGroup(String groupId) {
    final var scopeId = Path.of("groups", groupId).toString();
    return new UserHome(Path.of(storageProperties.getLocation(), scopeId), workspaceRoot(scopeId));
  }

  /**
   * A shared home for one tenant (e.g. one Feishu enterprise/tenantKey), namespaced under "tenant/"
   * so a tenant id can never collide with a bare userId.
   */
  public UserHome forTenant(String tenantId) {
    final var scopeId = Path.of("tenant", tenantId).toString();
    return new UserHome(Path.of(storageProperties.getLocation(), scopeId), workspaceRoot(scopeId));
  }

  /**
   * The scope's workspace folder on {@link StorageProperties#getWorkspaceLocation()} instead of
   * nested under its home, or null to keep the default nesting when no override is configured.
   */
  private Path workspaceRoot(String scopeId) {
    final var workspaceLocation = storageProperties.getWorkspaceLocation();
    return Strings.isNullOrEmpty(workspaceLocation) ? null : Path.of(workspaceLocation, scopeId);
  }

  /**
   * The owner's personal home, composed with the group home (when groupId is given) and the tenant
   * home (when tenantId is given) — a single {@link HomeDir} callers can query without branching on
   * which scopes apply to this request. Both groupId and tenantId are plain, per-request ids
   * sourced from whatever integration is in play (e.g. Feishu's chat id / tenant key) — nothing
   * here assumes a single deployment-wide tenant.
   */
  public HomeDir forRequest(String ownerId, String groupId, String tenantId) {
    final var owner = forOwner(ownerId);
    final var shared = new ArrayList<HomeDir>();
    if (groupId != null && !groupId.isBlank()) shared.add(forGroup(groupId));
    if (tenantId != null && !tenantId.isBlank()) shared.add(forTenant(tenantId));
    return shared.isEmpty() ? owner : new CompositeHomeDir(owner, shared);
  }

  /**
   * The home of the request a tool call belongs to, taken from the ids the run put in its {@link
   * ToolContext}. The scopes a tool may reach are the scopes of the request that called it, so a
   * tool has no business assembling that list itself.
   */
  public HomeDir forRequest(ToolContext context) {
    return forRequest(
        ToolContexts.require(context, ToolContexts.USER_ID),
        ToolContexts.get(context, ToolContexts.GROUP_ID),
        ToolContexts.get(context, ToolContexts.TENANT_ID));
  }
}
