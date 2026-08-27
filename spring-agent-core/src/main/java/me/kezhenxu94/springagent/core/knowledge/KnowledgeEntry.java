package me.kezhenxu94.springagent.core.knowledge;

import java.time.Instant;

/**
 * One indexed source as it appears in a listing — a document, not a chunk.
 *
 * @param scope which of the reader's scopes this document was found in, so a listing can say
 *     whether a document is the user's own or something the group or tenant shares
 */
public record KnowledgeEntry(
    String docId,
    String title,
    String source,
    int chunkCount,
    Instant createdAt,
    KnowledgeScope.Target scope) {}
