package me.kezhenxu94.springagent.core.tools.toolsearch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.index.vectorstore.VectorToolIndex;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

/**
 * Spring AI's {@link VectorToolIndex} with one method changed: clearing an index asks the store
 * what belongs to it, instead of remembering what this process put there. The tool search
 * auto-configuration registers its own index only when the application declares no {@link
 * ToolIndex}, so declaring this one replaces it.
 *
 * <p>Upstream gives each document a random id and keeps, on the heap, a map of which ids belong to
 * which index; clearing deletes exactly what that map names. Nothing persists that map, and the
 * store outlives the process that wrote it — across a restart for the mirrored file, and across
 * replicas for a Milvus everyone shares. So documents written elsewhere are unreachable to every
 * later clear, while each re-index adds a fresh set under new ids.
 *
 * <p>The cost is not only that it accumulates. Those documents carry the same index key, so they go
 * on competing in searches: identical text embeds identically, so a re-indexed tool comes back as
 * several equally good hits, and {@code max-results} is spent returning one tool repeatedly instead
 * of the several it asked for. A tool that has since been removed or reworded keeps answering too,
 * and the model calls a name that is no longer offered to it. On Milvus this needs no restart to
 * happen — one replica cannot clear what another wrote, so it builds up as they run.
 *
 * <p>Deleting by the index key the documents already carry needs no bookkeeping to be right, and
 * reaches documents this process never wrote — which is exactly the case upstream cannot handle.
 *
 * <p>Nothing else is overridden. Writing keeps its random ids, because the only caller clears an
 * index immediately before filling it and so never leaves a second copy behind; and searching is
 * inherited deliberately, being the half whose behaviour should follow upstream's.
 */
@Slf4j
public class StatelessVectorToolIndex extends VectorToolIndex {

  /**
   * Upstream's own name for the metadata field holding the index key, restated because it keeps it
   * private. It has to match, since the inherited write and search both use it.
   */
  static final String METADATA_INDEX_KEY = "sessionId";

  private final VectorStore vectorStore;

  public StatelessVectorToolIndex(final VectorStore vectorStore) {
    super(vectorStore);
    // Kept as well as passed up, because the superclass keeps its copy private.
    this.vectorStore = vectorStore;
  }

  @Override
  public void clearIndex(final String indexKey) {
    vectorStore.delete(new FilterExpressionBuilder().eq(METADATA_INDEX_KEY, indexKey).build());
    log.debug("Cleared the tool index for {}", indexKey);
  }
}
