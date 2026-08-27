package me.kezhenxu94.springagent.core.knowledge;

import lombok.experimental.UtilityClass;

/**
 * The metadata keys every knowledge chunk carries, named in one place because two very different
 * readers depend on them agreeing: the vector search filter built by {@link KnowledgeScopeFilter},
 * and whatever a {@link KnowledgeBase} implementation uses to enumerate documents natively.
 */
@UtilityClass
public class KnowledgeMetadata {

  /**
   * The three scope fields. Always written, blank where they do not apply, so a chunk's metadata
   * has the same shape whatever produced it — a missing key and a blank one behave differently
   * across stores, and only one of those is worth reasoning about.
   */
  public static final String OWNER = "owner";

  public static final String GROUP = "group";
  public static final String TENANT = "tenant";

  /** Groups the chunks of one indexed source, and the unit deletion works in. */
  public static final String DOC_ID = "docId";

  public static final String TITLE = "title";
  public static final String SOURCE = "source";
  public static final String CREATED_AT = "createdAt";

  /** This chunk's ordinal within its document, counting from zero. */
  public static final String CHUNK = "chunk";

  /**
   * How many chunks the whole document was split into, repeated on every chunk.
   *
   * <p>Redundant on purpose. Listing reads only the zero-th chunk of each document so that one row
   * comes back per document and the store's own offset/limit paginate documents rather than chunks;
   * that row therefore has to carry the count itself, because the chunks that would otherwise be
   * counted are never fetched.
   */
  public static final String CHUNK_COUNT = "chunkCount";
}
