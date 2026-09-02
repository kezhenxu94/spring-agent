package me.kezhenxu94.springagent.core.knowledge;

/**
 * One stored document with the text that was indexed, for showing somebody what is actually in
 * there.
 *
 * <p>The entry travels with the text rather than being looked up separately, because the two are
 * read from the same rows: a caller holding a search hit has a title and a score but no chunk count
 * and no date, and a second query to fill those in would ask the store for what it just returned.
 *
 * <p>The text is the chunks joined back together in their own order. It is not byte-for-byte what
 * was indexed — a splitter cut it and the joins are where it cut — but it is what a search matches
 * and what retrieval hands the model, which is the question somebody opening a document has.
 */
public record KnowledgeDocument(KnowledgeEntry entry, String text) {}
