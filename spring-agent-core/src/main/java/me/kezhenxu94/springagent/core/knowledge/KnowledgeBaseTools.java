package me.kezhenxu94.springagent.core.knowledge;

import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Managing the knowledge base from a conversation: what is in it, adding to it, searching it and
 * removing from it.
 *
 * <p>Shaped after {@code SkillManagementTools}, down to returning a message rather than throwing
 * when something is not allowed — the model reads the result and can correct itself, which it
 * cannot do with an exception.
 *
 * <p>Registered only where a {@link KnowledgeBase} implementation exists; see {@code
 * KnowledgeToolsConfiguration}. Automatic retrieval already puts relevant knowledge in front of the
 * model on every turn, so these are for the cases that need intent: writing something down,
 * checking what is stored, or searching again with different words when the automatic pass missed.
 */
@RequiredArgsConstructor
public class KnowledgeBaseTools {

  private final KnowledgeBase knowledgeBase;
  private final UserWorkspaceFactory userWorkspaceFactory;
  private final SpringAgentProperties properties;
  private final CoreMessages messages;

  @Tool(
      name = "ListKnowledgeBase",
      description =
"""
List the documents stored in the knowledge base.

Returns one row per document — not per chunk — with its id, title, where it came from, how many
chunks it was split into, and when it was added. Each row says which knowledge base it belongs to:
the current user's own, the current group's shared one, or the tenant-wide one.

Usage:
- Call with no arguments for the first page.
- The result says whether more documents remain; if so, call again with offset advanced by limit.
- Use the returned document id with DeleteKnowledge or UpdateKnowledgeScope, together with the
  knowledge base the row says it is in: an id is unique inside one knowledge base and not across
  them, so both are needed to name a document.
""")
  public String listKnowledgeBase(
      @ToolParam(required = false, description = "How many documents to skip; 0 for the first page")
          Integer offset,
      @ToolParam(required = false, description = "How many documents to return; defaults to 20")
          Integer limit,
      final ToolContext context) {

    final var scope = KnowledgeScope.forRequest(context);
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
          ? messages.get("knowledge-empty")
          : messages.get("knowledge-page-empty", from);
    }

    final var result = KnowledgeFormat.rows(page, messages, true);

    return page.hasMore()
        ? messages.get("knowledge-listed-more", page.entries().size(), result, from + size)
        : messages.get("knowledge-listed", page.entries().size(), result);
  }

  @Tool(
      name = "IndexKnowledge",
      description =
"""
Add a document to the knowledge base, or update one already there, so it can be recalled in later
conversations.

Usage:
- scope selects which knowledge base it goes into: "own" (default, only this user), "group" (shared
  with everyone in the current group chat), or "tenant" (shared with the whole company).
- title is what the document is called in listings; make it descriptive enough to recognise later.
- source is where the document came from, and is what a reader is later shown so they can go and
  read the original. It is either an absolute file path inside the user's, group's or tenant's
  workspace, or a URL — a wiki page, a ticket, a pull request.
- text is the content itself. Leave it out and the source is read as a file; pass it and the source
  only says where the content came from, which is what you want for a page you fetched and are
  storing the text of.
- Always pass source when you have one. Text carries no origin of its own, so without it the stored
  document cannot be traced back to the page it came from and the reader is shown a title with
  nothing to open. Leave it out only for something the user simply told you, in which case the
  message you are answering is recorded as the origin.
- docId is required, and is what this document *is*. Indexing the same thing again under the same
  id replaces it; a different id stores a second copy that matches searches alongside the first and
  contradicts it. So make the best id you can out of what you were given — something about this
  content that will be the same the next time you are handed it:
  - A Feishu document, wiki page, sheet or base: the token in its link — the part after /docx/,
    /wiki/, /sheets/ or /base/. Take it from the link as it stands; a wiki link and the document
    behind it are two links, and count as two documents.
  - A page, ticket or pull request fetched from the web: its URL.
  - A file in a workspace: its absolute path.
  - Something the user told you in this conversation: the id of the message they told you in.
  - A document you stored before and are revising: its id from ListKnowledgeBase.
- If none of those fit — content with no origin you can name, or two things you cannot tell apart —
  ask the user what to file it under, with AskUserQuestionTool, rather than making an id up. An
  invented id looks like it worked and duplicates the document the next time this subject comes
  round, which nobody finds out about until the two copies contradict each other in an answer.
""")
  public String indexKnowledge(
      @ToolParam(description = "A short descriptive title for the document") String title,
      @ToolParam(
              required = false,
              description = "\"own\", \"group\" or \"tenant\"; defaults to own")
          String scope,
      @ToolParam(
              required = false,
              description =
                  "Where it came from: an absolute file path to read, or the URL it was fetched"
                      + " from. Pass this whenever you have one")
          String source,
      @ToolParam(
              required = false,
              description =
                  "The content itself; leave out to read the source as a file. Takes a file"
                      + " reference: @file:<path> to file away a saved tool result unchanged, or"
                      + " @file:<path>#/json/pointer for one part of it")
          String text,
      @ToolParam(
              description =
                  "What this document is: a Feishu document token, a URL, an absolute file path,"
                      + " the id of the message it was told to you in, or the id of the document"
                      + " being updated. Indexing again under the same id replaces it")
          String docId,
      final ToolContext context) {

    if (title == null || title.isBlank()) {
      return messages.get("knowledge-title-required");
    }
    if (docId == null || docId.isBlank()) {
      // Refused rather than filled in with an id of our own. A generated id is a document that can
      // never be indexed over, so the next call storing the same source stores a second copy —
      // which is exactly the duplication this parameter exists to prevent. The model is the only
      // party that knows what it is holding, so it is the one asked.
      return messages.get("knowledge-doc-id-missing");
    }
    final var hasText = text != null && !text.isBlank();
    final var hasSource = source != null && !source.isBlank();
    if (!hasText && !hasSource) {
      // Nothing to store and nowhere to read it from. Saying so is what lets the model fix the
      // call rather than retry it unchanged.
      return messages.get("knowledge-source-missing");
    }

    final var readable = KnowledgeScope.forRequest(context);
    final var target = KnowledgeScope.Target.of(scope);
    final var refusal = refuseUnreachableTarget(readable, target);
    if (refusal != null) {
      return refusal;
    }

    final KnowledgeSource knowledge;
    if (hasText) {
      knowledge =
          KnowledgeSource.ofText(
              readable,
              target,
              title,
              text,
              hasSource ? source : conversationOrigin(context),
              docId);
    } else {
      // No content, so the source is a file to read — and only then does it have to be one this
      // request may reach. A URL passed as the attribution of text is nobody's file.
      final var accessError = validateWorkspacePath(source, context);
      if (accessError != null) {
        return accessError;
      }
      knowledge = KnowledgeSource.ofPath(readable, target, title, source, docId);
    }

    try {
      final var storedId = knowledgeBase.index(knowledge);
      return messages.get(
          "knowledge-indexed", title, messages.get(KnowledgeFormat.scopeLabel(target)), storedId);
    } catch (RuntimeException e) {
      return messages.get("knowledge-index-failed", e.getMessage());
    }
  }

  @Tool(
      name = "SearchKnowledge",
      description =
"""
Search the knowledge base for passages relevant to a question.

The knowledge base is already consulted automatically on every message, so use this when that did
not surface what you needed and you want to search again with different wording, or when you want
to check what is stored about a topic before answering.

Searches the current user's own knowledge base, the current group's when in a group chat, and the
tenant-wide one, all at once.
""")
  public String searchKnowledge(
      @ToolParam(description = "What to search for, in natural language") String query,
      @ToolParam(required = false, description = "How many passages to return; defaults to 4")
          Integer topK,
      final ToolContext context) {

    if (query == null || query.isBlank()) {
      return messages.get("knowledge-query-required");
    }
    final var scope = KnowledgeScope.forRequest(context);
    final var limit = topK == null || topK <= 0 ? properties.ai().rag().topK() : topK;

    final java.util.List<org.springframework.ai.document.Document> found;
    try {
      found = knowledgeBase.search(scope, query, limit);
    } catch (RuntimeException e) {
      return messages.get("knowledge-search-failed", e.getMessage());
    }
    if (found.isEmpty()) {
      return messages.get("knowledge-search-empty", query);
    }

    return messages.get("knowledge-search-found", found.size(), KnowledgeFormat.passages(found));
  }

  @Tool(
      name = "UpdateKnowledgeScope",
      description =
"""
Move a document into a different knowledge base: keep it to yourself, share it with this group, or
share it with the whole company.

Usage:
- docId comes from ListKnowledgeBase.
- from is which knowledge base it is in now, which ListKnowledgeBase says for every row: "own",
  "group" or "tenant". A document id is unique inside one knowledge base and not across them, so
  the same file or page filed twice is two documents wearing one id — without this the wrong one
  would be moved. An id that is not in the base you name is reported as not found.
- to is where it should end up: "own", "group" or "tenant".
- The document keeps its id, title, origin and content; only who can read it changes. To change
  what a document *says*, index it again under the same id with IndexKnowledge.
- Sharing something with a group or the whole company is a decision for the person who stored it,
  not for you. Ask before widening a document's scope unless they have just asked you to.
""")
  public String updateKnowledgeScope(
      @ToolParam(description = "The document id, as shown by ListKnowledgeBase") String docId,
      @ToolParam(
              description =
                  "Which knowledge base it is in now, as shown by ListKnowledgeBase: \"own\","
                      + " \"group\" or \"tenant\"")
          String from,
      @ToolParam(
              description =
                  "Which knowledge base to move it into: \"own\", \"group\" or \"tenant\"")
          String to,
      final ToolContext context) {

    if (docId == null || docId.isBlank()) {
      return messages.get("knowledge-doc-id-required");
    }
    // Neither of the two is defaulted to "own" the way indexing does. Indexing without a scope
    // means the caller had no opinion; a scope left out or misspelt here means they did, and
    // reading it as "own" would take a document out of the company knowledge base — or move the
    // private copy of an id and report the company's as moved — because of a typo.
    final var current = KnowledgeScope.Target.named(from);
    if (current.isEmpty()) {
      return messages.get("knowledge-current-scope-unknown", from);
    }
    final var requested = KnowledgeScope.Target.named(to);
    if (requested.isEmpty()) {
      return messages.get("knowledge-scope-unknown", to);
    }
    final var owning = current.get();
    final var target = requested.get();

    final var readable = KnowledgeScope.forRequest(context);
    final var refusal = refuseUnreachableTarget(readable, owning);
    if (refusal != null) {
      return refusal;
    }
    final var targetRefusal = refuseUnreachableTarget(readable, target);
    if (targetRefusal != null) {
      return targetRefusal;
    }

    final java.util.Optional<KnowledgeEntry> moved;
    try {
      moved = knowledgeBase.move(readable, owning, docId, target);
    } catch (RuntimeException e) {
      return messages.get("knowledge-move-failed", e.getMessage());
    }
    return moved
        .map(
            entry ->
                messages.get(
                    "knowledge-moved",
                    entry.title(),
                    messages.get(KnowledgeFormat.scopeLabel(target))))
        .orElseGet(
            () ->
                messages.get(
                    "knowledge-move-not-found",
                    docId,
                    messages.get(KnowledgeFormat.scopeLabel(owning))));
  }

  @Tool(
      name = "DeleteKnowledge",
      description =
"""
Remove a document and all of its chunks from the knowledge base.

Usage:
- docId comes from ListKnowledgeBase.
- scope is which knowledge base to remove it from, which ListKnowledgeBase says for every row:
  "own", "group" or "tenant". A document id is unique inside one knowledge base and not across
  them — the same file or page filed both privately and company-wide is two documents wearing one
  id — so without this it would be unanswerable which of them a delete meant, and deleting all of
  them would throw away a shared document to tidy up a private one.
- Only documents the current user can reach can be deleted; a document belonging to another user,
  or an id that is not in the knowledge base you name, is reported as not found.
- This operation is irreversible. Deleting from the group's or the company's knowledge base takes
  it away from everybody, so ask first unless they have just asked you to.
""")
  public String deleteKnowledge(
      @ToolParam(description = "The document id, as shown by ListKnowledgeBase") String docId,
      @ToolParam(
              description =
                  "Which knowledge base to remove it from, as shown by ListKnowledgeBase:"
                      + " \"own\", \"group\" or \"tenant\"")
          String scope,
      final ToolContext context) {

    if (docId == null || docId.isBlank()) {
      return messages.get("knowledge-doc-id-required");
    }
    // Not defaulted, for the same reason the move's is not: a delete that fell back to "own" would
    // report a document deleted while the copy the user was looking at, the company's, is still
    // there — and the reverse default would delete a shared document on a typo.
    final var owning = KnowledgeScope.Target.named(scope);
    if (owning.isEmpty()) {
      return messages.get("knowledge-current-scope-unknown", scope);
    }
    final var readable = KnowledgeScope.forRequest(context);
    final var refusal = refuseUnreachableTarget(readable, owning.get());
    if (refusal != null) {
      return refusal;
    }
    try {
      knowledgeBase.delete(readable, owning.get(), docId);
    } catch (RuntimeException e) {
      return messages.get("knowledge-delete-failed", e.getMessage());
    }
    return messages.get(
        "knowledge-deleted", docId, messages.get(KnowledgeFormat.scopeLabel(owning.get())));
  }

  /**
   * Where content the model simply typed came from: the message being answered.
   *
   * <p>Something learned in conversation still has an origin — someone said it, in a message that
   * can be gone back to. Falling back to that leaves every stored document traceable to something,
   * rather than a class of them attributable only to their own title. It is what the caller would
   * have to pass otherwise, and would have no way of knowing.
   *
   * <p>Blank where the surface has no such id, which is a surface where there is nothing to go back
   * to anyway — an origin is not worth inventing for its own sake.
   */
  private static String conversationOrigin(final ToolContext context) {
    return ToolContexts.get(context, ToolContexts.REPLY_MESSAGE_ID);
  }

  /**
   * Refuses naming a knowledge base the request has no identity for — a group scope from a
   * one-to-one chat, or a tenant scope from an integration with no tenant concept.
   *
   * <p>On a write, without this the document would be stamped with a blank owning scope, which no
   * reader's filter matches: it would be stored, reported as stored, and never seen again. On a
   * read, a delete or a move it is the scope naming which document is meant, and an all-blank one
   * names nothing — {@code KnowledgeScopeFilter.documentOwnedBy} throws on it rather than quietly
   * matching no rows, so this is what turns that into an answer saying which word was wrong.
   */
  private String refuseUnreachableTarget(
      final KnowledgeScope scope, final KnowledgeScope.Target target) {
    if (target == KnowledgeScope.Target.GROUP && !scope.hasGroup()) {
      return messages.get("knowledge-no-group");
    }
    if (target == KnowledgeScope.Target.TENANT && !scope.hasTenant()) {
      return messages.get("knowledge-no-tenant");
    }
    return null;
  }

  /**
   * Keeps indexing inside the workspaces this request can already read, reusing the same composite
   * home the file tools are bounded by rather than inventing a second notion of what is reachable.
   */
  private String validateWorkspacePath(final String filePath, final ToolContext context) {
    final Path resolved;
    try {
      resolved = Path.of(filePath).toAbsolutePath().normalize();
    } catch (RuntimeException e) {
      return messages.get("knowledge-path-invalid", filePath);
    }
    final var home = userWorkspaceFactory.forRequest(context);
    return home.contains(resolved) ? null : messages.get("knowledge-path-denied");
  }
}
