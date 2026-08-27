package me.kezhenxu94.springagent.core.knowledge;

/**
 * Something to index, and where it should land.
 *
 * <p>{@code source} is one field and not two because a document has one origin, whether or not the
 * agent had to open it to get the content. A file path is where the text was read from *and* where
 * a reader is sent to find it again; a wiki URL is the same thing for content handed over as text.
 * Splitting them into a path to read and an origin to display would have made every caller state
 * the same string twice for the commonest case, and let the two disagree.
 *
 * <p>So it means: read this, unless {@code text} was supplied, in which case it only says where the
 * text came from. Both may be absent — a note the user simply told the agent came from nowhere but
 * the conversation.
 *
 * @param docId the document to replace, or null to store a new one under a generated id. Naming an
 *     existing id is how a document that changes — a design doc revised through the day — stays one
 *     document rather than accumulating a copy per revision, each of them still matching searches
 *     and contradicting the others. Replacement is scoped to {@code target}, so an id belonging to
 *     a scope not being written is not replaced but simply not found
 */
public record KnowledgeSource(
    KnowledgeScope scope,
    KnowledgeScope.Target target,
    String title,
    String text,
    String source,
    String docId) {

  public KnowledgeSource {
    text = blankToNull(text);
    source = blankToNull(source);
  }

  public static KnowledgeSource ofText(
      final KnowledgeScope scope,
      final KnowledgeScope.Target target,
      final String title,
      final String text,
      final String source,
      final String docId) {
    return new KnowledgeSource(scope, target, title, text, source, docId);
  }

  /** Content the agent has to go and read, from the place that is also its attribution. */
  public static KnowledgeSource ofPath(
      final KnowledgeScope scope,
      final KnowledgeScope.Target target,
      final String title,
      final String path,
      final String docId) {
    return new KnowledgeSource(scope, target, title, null, path, docId);
  }

  /**
   * Whether the content has to be read from {@link #source()} rather than being supplied already.
   */
  public boolean mustBeRead() {
    return text == null;
  }

  /** Where a reader is sent to find this document, or blank when it came from the conversation. */
  public String attribution() {
    return source == null ? "" : source;
  }

  /** Whether this replaces an existing document rather than storing a new one. */
  public boolean replaces() {
    return docId != null && !docId.isBlank();
  }

  /** The scope values a chunk of this source is stamped with: only the owning one is set. */
  public KnowledgeScope owningScope() {
    return switch (target) {
      case OWN -> new KnowledgeScope(scope.owner(), "", "");
      case GROUP -> new KnowledgeScope("", scope.group(), "");
      case TENANT -> new KnowledgeScope("", "", scope.tenant());
    };
  }

  private static String blankToNull(final String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
