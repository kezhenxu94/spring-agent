package me.kezhenxu94.springagent.core.knowledge;

import java.util.List;

/**
 * A page of a listing, and whether asking again with a larger offset would return anything.
 *
 * <p>{@code hasMore} rather than a total count: a total costs an extra round trip against every
 * store worth supporting, and the only thing the caller actually does with it is decide whether to
 * ask for another page.
 */
public record KnowledgePage(List<KnowledgeEntry> entries, boolean hasMore) {

  public static final KnowledgePage EMPTY = new KnowledgePage(List.of(), false);
}
