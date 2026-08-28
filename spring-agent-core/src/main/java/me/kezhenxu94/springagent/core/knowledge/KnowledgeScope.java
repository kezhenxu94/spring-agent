package me.kezhenxu94.springagent.core.knowledge;

import com.google.common.base.Strings;
import java.util.Optional;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Who a piece of knowledge belongs to, and therefore who may read it back.
 *
 * <p>The three identities are the same opaque strings the rest of the runtime carries — see {@link
 * ToolContexts} — and are never interpreted here. They are independent rather than a single packed
 * "scope" string so that a document is reachable from every scope it legitimately belongs to, and
 * so that the read filter is a plain disjunction rather than a set of prefix comparisons.
 *
 * <p>Blank is the representation of "does not apply": a p2p chat has no group, and an integration
 * with no tenant concept has no tenant. That distinction is load-bearing on the read path — see
 * {@link KnowledgeScopeFilter}, which must not emit a clause for a blank identity.
 */
public record KnowledgeScope(String owner, String group, String tenant) {

  public KnowledgeScope {
    // Normalised on the way in so that null and "" are the same thing everywhere downstream, the
    // way SpringAgent already nullToEmpty's these values into the tool context.
    owner = Strings.nullToEmpty(owner).trim();
    group = Strings.nullToEmpty(group).trim();
    tenant = Strings.nullToEmpty(tenant).trim();
  }

  /** The scope of the run a tool call belongs to: everything that run is allowed to read. */
  public static KnowledgeScope forRequest(final ToolContext context) {
    return new KnowledgeScope(
        ToolContexts.require(context, ToolContexts.USER_ID),
        ToolContexts.get(context, ToolContexts.GROUP_ID),
        ToolContexts.get(context, ToolContexts.TENANT_ID));
  }

  public boolean hasGroup() {
    return !group.isEmpty();
  }

  public boolean hasTenant() {
    return !tenant.isEmpty();
  }

  /**
   * The scope fields a document owned in {@code target} is stamped with: the one identity that
   * applies, blank for the other two.
   *
   * <p>Here rather than on the caller because it is read from both sides of a write — what {@link
   * KnowledgeSource#owningScope()} stamps a new document with, and what a move has to match to find
   * the copy it is replacing. Two spellings of it could disagree, and the failure would be a
   * document left in the scope it was moved out of.
   */
  public KnowledgeScope owning(final Target target) {
    return switch (target) {
      case OWN -> new KnowledgeScope(owner, "", "");
      case GROUP -> new KnowledgeScope("", group, "");
      case TENANT -> new KnowledgeScope("", "", tenant);
    };
  }

  /**
   * Which of the three a write lands in. A document is owned by exactly one of them, even though a
   * reader may reach it through any it belongs to.
   */
  public enum Target {
    OWN,
    GROUP,
    TENANT;

    /** The default for a write that did not say, and for a word that is not one of these. */
    public static Target of(final String value) {
      return named(value).orElse(OWN);
    }

    /**
     * The one this word names, or empty if it names none of them.
     *
     * <p>Separate from {@link #of} because falling back to {@code OWN} is right for a write that
     * left the scope out and wrong for one that asked for a scope and misspelt it — moving a
     * company document into a private knowledge base is not a reasonable reading of a typo. A
     * caller that has to tell the two apart asks this one.
     */
    public static Optional<Target> named(final String value) {
      if (Strings.isNullOrEmpty(value)) return Optional.empty();
      return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
        case "own" -> Optional.of(OWN);
        case "group" -> Optional.of(GROUP);
        case "tenant", "company" -> Optional.of(TENANT);
        default -> Optional.empty();
      };
    }
  }
}
