package me.kezhenxu94.springagent.core.knowledge;

import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Reading somebody else's knowledge base, for the administrator who has to know what is in it.
 *
 * <p>{@link KnowledgeBaseTools} is scoped to the run — {@link KnowledgeScope#forRequest} — which is
 * right for everybody and leaves one thing unreachable: the knowledge base of an identity nobody
 * ever logs in as. An event source's {@code owner.user-id} is exactly that, and {@code
 * PlaybookTools} writes the playbooks steering every unattended triage into it. Without these tools
 * a playbook could be written and never read back: not by its author, who is not that identity, and
 * not by that identity, which is not a person. Verifying what a triage will actually be shown is
 * then impossible, which is the same as not knowing.
 *
 * <p>Read-only, and one scope wide: the {@code owner}'s own knowledge base, no group and no tenant.
 * That is precisely what {@code SituationSweeper} retrieves a playbook from — see {@code
 * SituationSweeper#playbookFor}, which builds the same scope — so what an administrator sees here
 * is what an unattended run sees, rather than a superset of it that hides a document being out of
 * reach.
 *
 * <p><b>Declared {@code @AgentTool(admin = true)}, and that is the whole of the safety story</b>,
 * as it is for {@code PlaybookTools}. These read a knowledge base the run has no claim on, so who
 * may call them is the entire question and nothing is checked here a second time. Only somebody
 * named in {@code app.ai.admins} is offered them at all, and an unattended run — which has no user
 * id — never is.
 */
@RequiredArgsConstructor
public class KnowledgeAdminTools {

  private final KnowledgeBase knowledgeBase;
  private final SpringAgentProperties properties;
  private final CoreMessages messages;

  @Tool(
      name = "ListOwnerKnowledgeBase",
      description =
"""
List the documents stored in another identity's own knowledge base. Administrators only.

Use this to see what is stored under an identity nobody logs in as — an event source's owner, whose
knowledge base holds the playbooks its triage runs are steered by. ListPlaybooks names that owner.

Only that identity's own knowledge base is listed, which is the same scope an unattended run reads:
nothing a group or the tenant shares with it appears here. For your own, use ListKnowledgeBase.

Usage:
- Call with the owner alone for the first page.
- The result says whether more documents remain; if so, call again with offset advanced by limit.
""")
  public String listOwnerKnowledgeBase(
      @ToolParam(description = "The user id whose knowledge base to list") String owner,
      @ToolParam(required = false, description = "How many documents to skip; 0 for the first page")
          Integer offset,
      @ToolParam(required = false, description = "How many documents to return; defaults to 20")
          Integer limit) {

    if (owner == null || owner.isBlank()) {
      return messages.get("knowledge-owner-required");
    }
    final var scope = ownScopeOf(owner);
    final var from = offset == null || offset < 0 ? 0 : offset;
    final var size =
        Math.min(
            KnowledgeFormat.MAX_PAGE_SIZE,
            limit == null || limit <= 0 ? properties.ai().rag().listPageSize() : limit);

    final KnowledgePage page;
    try {
      page = knowledgeBase.list(scope, from, size);
    } catch (RuntimeException e) {
      return messages.get("knowledge-list-failed", e.getMessage());
    }

    if (page.entries().isEmpty()) {
      return from == 0
          ? messages.get("knowledge-owner-empty", owner)
          : messages.get("knowledge-page-empty", from);
    }

    // Without the per-row scope label the other listing carries: every row here is the owner's own
    // by construction, and the label reads "your own", which is false about somebody else's.
    final var rows = KnowledgeFormat.rows(page, messages, false);
    return page.hasMore()
        ? messages.get(
            "knowledge-owner-listed-more", page.entries().size(), owner, rows, from + size)
        : messages.get("knowledge-owner-listed", page.entries().size(), owner, rows);
  }

  @Tool(
      name = "SearchOwnerKnowledge",
      description =
"""
Search another identity's own knowledge base for passages relevant to a question. Administrators
only.

Use this to read back what is stored under an identity nobody logs in as — to check what a
playbook actually says, or whether the query a source retrieves it with matches it at all. Passing
that source's playbook query verbatim answers the second question: what comes back is what its
triage runs will be shown.

Only that identity's own knowledge base is searched, which is the same scope an unattended run
reads. For your own, use SearchKnowledge.
""")
  public String searchOwnerKnowledge(
      @ToolParam(description = "The user id whose knowledge base to search") String owner,
      @ToolParam(description = "What to search for, in natural language") String query,
      @ToolParam(required = false, description = "How many passages to return; defaults to 4")
          Integer topK) {

    if (owner == null || owner.isBlank()) {
      return messages.get("knowledge-owner-required");
    }
    if (query == null || query.isBlank()) {
      return messages.get("knowledge-query-required");
    }
    final var limit = topK == null || topK <= 0 ? properties.ai().rag().topK() : topK;

    final java.util.List<org.springframework.ai.document.Document> found;
    try {
      found = knowledgeBase.search(ownScopeOf(owner), query, limit);
    } catch (RuntimeException e) {
      return messages.get("knowledge-search-failed", e.getMessage());
    }
    if (found.isEmpty()) {
      return messages.get("knowledge-owner-search-empty", query, owner);
    }
    return messages.get(
        "knowledge-owner-search-found", found.size(), owner, KnowledgeFormat.passages(found));
  }

  /**
   * The owner's own knowledge base and nothing else — the scope stated rather than derived from a
   * request, since the run this call belongs to is somebody else's.
   *
   * <p>Group and tenant are left blank on purpose, and blank is "does not apply" rather than "any":
   * {@link KnowledgeScopeFilter} emits no clause for a blank identity, so this reaches exactly the
   * documents stamped with this owner.
   */
  private static KnowledgeScope ownScopeOf(final String owner) {
    return new KnowledgeScope(owner, "", "");
  }
}
