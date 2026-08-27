package me.kezhenxu94.springagent.core.knowledge;

import com.google.common.base.Strings;
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
   * Which of the three a write lands in. A document is owned by exactly one of them, even though a
   * reader may reach it through any it belongs to.
   */
  public enum Target {
    OWN,
    GROUP,
    TENANT;

    public static Target of(final String value) {
      if (Strings.isNullOrEmpty(value)) return OWN;
      return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
        case "group" -> GROUP;
        case "tenant", "company" -> TENANT;
        default -> OWN;
      };
    }
  }
}
