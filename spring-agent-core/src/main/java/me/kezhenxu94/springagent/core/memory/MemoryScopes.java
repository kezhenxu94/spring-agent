package me.kezhenxu94.springagent.core.memory;

import com.google.common.base.Strings;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeScope.Target;
import me.kezhenxu94.springagent.core.tools.HomeDir;
import me.kezhenxu94.springagent.core.tools.HomeDir.Folder;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.core.tools.UserHome;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import org.springframework.ai.chat.model.ToolContext;

/**
 * The memory roots one request reaches, and which of them it may write to.
 *
 * <p>Three roots rather than one because a run has three homes — see {@code UserWorkspaceFactory} —
 * and what a person is like belongs somewhere different from what a group has decided and from what
 * is true of the whole company. A null root is the representation of "does not apply": a p2p chat
 * has no group, and an integration with no tenant concept has no tenant. That is the same
 * convention {@code KnowledgeScope} keeps with blank strings, in the shape this one needs.
 *
 * <p>Constructing this touches no filesystem. Every root is resolved through {@link
 * HomeDir#folderPath} rather than {@code folder}, so naming a group's memories in a refusal or in a
 * prompt cannot create a directory in shared storage on behalf of somebody who only read. Creating
 * is the write path's job, and {@code MemoryTools} does it in the write that needs it.
 */
public record MemoryScopes(Path own, Path group, Path tenant, boolean admin) {

  /**
   * The scopes of the request a tool call belongs to, and whether an admin is behind it.
   *
   * <p>Read from the tool context rather than taken from the caller, because {@code
   * SpringAgent.toolContextFor} overwrites the identity keys after an integration has had its say —
   * so these ids are the run's own, and a tool has no business assembling that list itself.
   */
  public static MemoryScopes forRequest(
      final UserWorkspaceFactory factory, final Admins admins, final ToolContext context) {
    return forRequest(
        factory,
        admins.isAdmin(context),
        ToolContexts.require(context, ToolContexts.USER_ID),
        ToolContexts.get(context, ToolContexts.GROUP_ID),
        ToolContexts.get(context, ToolContexts.TENANT_ID));
  }

  /**
   * The same, for a caller holding an {@code AgentRequest} rather than a {@code ToolContext} — the
   * provider, which has to describe these scopes in the prompt before any call exists. Both
   * overloads exist for the reason {@code UserWorkspaceFactory} has both.
   */
  public static MemoryScopes forRequest(
      final UserWorkspaceFactory factory,
      final boolean admin,
      final String userId,
      final String groupId,
      final String tenantId) {
    return new MemoryScopes(
        Strings.isNullOrEmpty(userId) ? null : memoriesOf(factory.forOwner(userId)),
        Strings.isNullOrEmpty(groupId) ? null : memoriesOf(factory.forGroup(groupId)),
        Strings.isNullOrEmpty(tenantId) ? null : memoriesOf(factory.forTenant(tenantId)),
        admin);
  }

  private static Path memoriesOf(final UserHome home) {
    return home.folderPath(Folder.MEMORIES);
  }

  /** Whether this request has an identity for {@code target} at all. */
  public boolean has(final Target target) {
    return root(target) != null;
  }

  /** That scope's memories root, or null where the request has no such identity. */
  public Path root(final Target target) {
    return switch (target) {
      case OWN -> own;
      case GROUP -> group;
      case TENANT -> tenant;
    };
  }

  /**
   * Every scope this request may read, in the order a read answers in: the requester's own first,
   * then what is shared with fewer people, then with more.
   */
  public List<Target> readable() {
    final var targets = new ArrayList<Target>();
    if (own != null) targets.add(Target.OWN);
    if (group != null) targets.add(Target.GROUP);
    if (tenant != null) targets.add(Target.TENANT);
    return List.copyOf(targets);
  }

  /**
   * Whether a write may land in {@code target}.
   *
   * <p>Own is always writable. A shared scope needs somewhere to be shared with, and the tenant
   * needs one thing more: a group chat, or an admin. A p2p chat is a room with one person in it and
   * no witness, so a fact the agent was talked into believing there must not become what the whole
   * company remembers — and the only person who would find out is the same person who put it there.
   * In a group chat the write happens in front of everyone it affects, which is the only review
   * this has. An admin is exempt because this deployment already trusts that set with everybody
   * else's work; see {@link Admins}, which also refuses the reverse pairing, so an identity that
   * reads text written by strangers can never take this exemption.
   *
   * <p>Keyed on the group root being present, not on {@code chatType}: chatType is a free-form
   * string whichever integration is in play picks, while a blank groupId is what every other scoped
   * decision here already keys on — {@code UserWorkspaceFactory.forRequest}, {@code
   * KnowledgeScopeFilter}, {@code HomeDirsPromptVariables}. A second definition of "is this a group
   * chat" would eventually disagree with those, and the disagreement would be a silent write into a
   * directory a whole company reads.
   */
  public boolean writable(final Target target) {
    return switch (target) {
      case OWN -> own != null;
      case GROUP -> group != null;
      case TENANT -> tenant != null && (group != null || admin);
    };
  }

  /**
   * The {@code {MEMORY_SCOPES}} block of the memory prompt: one line per scope this request
   * reaches, in read order, each naming the word that addresses it, where it is, who else reads it
   * and whether this request may write there.
   *
   * <p>Assembled here rather than as three prompt placeholders because the prompt is rendered once
   * per request while the number of scopes varies: a template with a slot per scope would render a
   * blank into whatever prose surrounds it, and a p2p run would read a sentence about a group chat
   * that does not exist. Through the bundle for the reason {@code HomeDirsPromptVariables} gives —
   * this lands inside a prompt an application may have written in another language.
   */
  public String describe(final CoreMessages messages) {
    final var lines = new ArrayList<String>();
    for (final var target : readable()) {
      lines.add(
          messages.get(
              "memory-scope",
              word(target),
              root(target),
              messages.get(audienceKey(target)),
              messages.get(writable(target) ? "memory-scope-writable" : "memory-scope-read-only")));
    }
    return String.join("\n", lines);
  }

  /**
   * The word a tool call addresses this scope by. Lower case and never localized: it is an argument
   * the model passes back, parsed by {@code Target.named}, not prose for a person to read.
   */
  public static String word(final Target target) {
    return switch (target) {
      case OWN -> "own";
      case GROUP -> "group";
      case TENANT -> "tenant";
    };
  }

  private static String audienceKey(final Target target) {
    return switch (target) {
      case OWN -> "home-dir-own";
      case GROUP -> "home-dir-group";
      case TENANT -> "home-dir-tenant";
    };
  }
}
