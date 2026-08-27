package me.kezhenxu94.springagent.core.knowledge;

import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.ai.document.Document;

/**
 * Where a passage the model was given came from, for a surface that wants to show its workings.
 *
 * <p>One per document rather than per chunk: two chunks of the same file are one reference to a
 * reader, and listing the file twice would say something untrue about how much was consulted.
 *
 * @param score the best score among the chunks this reference stands for. Retrieval is ranked, so
 *     the strongest chunk is the one that explains why the document is here at all
 */
public record KnowledgeReference(
    String docId, String title, String source, KnowledgeScope.Target scope, Double score) {

  /**
   * Folds retrieved chunks into one reference per document, keeping the order they were retrieved
   * in — which is relevance order, and so the order worth showing them in.
   */
  public static List<KnowledgeReference> of(final List<Document> documents) {
    final var byDoc = new LinkedHashMap<String, KnowledgeReference>();
    for (final var document : documents) {
      final var metadata = document.getMetadata();
      final var docId = string(metadata.get(KnowledgeMetadata.DOC_ID));
      final var score = document.getScore();
      byDoc.merge(
          docId.isEmpty() ? String.valueOf(System.identityHashCode(document)) : docId,
          new KnowledgeReference(
              docId,
              string(metadata.get(KnowledgeMetadata.TITLE)),
              string(metadata.get(KnowledgeMetadata.SOURCE)),
              targetOf(metadata),
              score),
          KnowledgeReference::betterScored);
    }
    return List.copyOf(byDoc.values());
  }

  private static KnowledgeReference betterScored(
      final KnowledgeReference first, final KnowledgeReference second) {
    final var a = first.score() == null ? Double.NEGATIVE_INFINITY : first.score();
    final var b = second.score() == null ? Double.NEGATIVE_INFINITY : second.score();
    return b > a ? second : first;
  }

  /**
   * Which knowledge base it came from, read back from whichever scope field it was stamped with.
   */
  private static KnowledgeScope.Target targetOf(final java.util.Map<String, Object> metadata) {
    if (!string(metadata.get(KnowledgeMetadata.GROUP)).isEmpty()) {
      return KnowledgeScope.Target.GROUP;
    }
    if (!string(metadata.get(KnowledgeMetadata.TENANT)).isEmpty()) {
      return KnowledgeScope.Target.TENANT;
    }
    return KnowledgeScope.Target.OWN;
  }

  private static String string(final Object value) {
    return value == null ? "" : String.valueOf(value);
  }
}
