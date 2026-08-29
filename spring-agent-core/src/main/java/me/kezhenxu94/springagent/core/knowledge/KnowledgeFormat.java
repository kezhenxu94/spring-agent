package me.kezhenxu94.springagent.core.knowledge;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import org.springframework.ai.document.Document;

/**
 * How a listing and a search result are written out, shared by every tool that produces one.
 *
 * <p>Here rather than in {@link KnowledgeBaseTools} because {@link KnowledgeAdminTools} shows the
 * same two things about a knowledge base that is not the caller's own. Two spellings of the row
 * format would drift, and the drift would show up as the same document looking like two different
 * things depending on which tool asked.
 */
final class KnowledgeFormat {

  /** Enough to be useful in one result without turning a listing into the whole turn's context. */
  static final int MAX_PAGE_SIZE = 100;

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

  private KnowledgeFormat() {}

  /**
   * One row per document: what it is, and enough about it to decide whether to open or delete it.
   *
   * <p>{@code withScope} is off for a listing of a single identity's own knowledge base, where
   * every row would carry the same label and that label — "your own" — would be a lie about
   * somebody else's documents.
   */
  static String rows(
      final KnowledgePage page, final CoreMessages messages, final boolean withScope) {
    final var result = new StringBuilder();
    for (final var entry : page.entries()) {
      result.append(entry.docId()).append("  ").append(entry.title()).append("  [");
      if (withScope) {
        result.append(messages.get(scopeLabel(entry.scope()))).append(", ");
      }
      result
          .append(messages.get("knowledge-chunks", entry.chunkCount()))
          .append(", ")
          .append(entry.createdAt() == null ? "" : DATE.format(entry.createdAt()))
          .append("]\n");
      if (entry.source() != null && !entry.source().isBlank()) {
        result.append("    ").append(entry.source()).append('\n');
      }
    }
    return result.toString();
  }

  /** The passages a search matched, each with what it scored. */
  static String passages(final List<Document> found) {
    final var result = new StringBuilder();
    for (final var document : found) {
      final var metadata = document.getMetadata();
      result
          .append("--- ")
          .append(metadata.getOrDefault(KnowledgeMetadata.TITLE, ""))
          .append(" (")
          .append(metadata.getOrDefault(KnowledgeMetadata.DOC_ID, ""))
          // The similarity that got this passage past app.ai.rag.similarity-threshold. Shown
          // because that threshold is a raw cosine score whose useful value depends on the
          // embedding model, and these numbers are the only way to tell what it should be: if
          // passages nobody would call relevant are scoring above it, it is set too low.
          .append(
              document.getScore() == null ? "" : String.format(", score %.3f", document.getScore()))
          .append(")\n")
          .append(document.getText())
          .append("\n\n");
    }
    return result.toString();
  }

  static String scopeLabel(final KnowledgeScope.Target target) {
    return switch (target) {
      case OWN -> "knowledge-scope-own";
      case GROUP -> "knowledge-scope-group";
      case TENANT -> "knowledge-scope-tenant";
    };
  }
}
