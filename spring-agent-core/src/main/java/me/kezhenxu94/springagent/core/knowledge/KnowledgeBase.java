package me.kezhenxu94.springagent.core.knowledge;

import java.util.List;
import java.util.Optional;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.filter.Filter;

/**
 * Durable, searchable knowledge scoped to a user, a group and a tenant — the contract core depends
 * on, implemented by whichever {@code spring-agent-rag-*} module a deployment takes.
 *
 * <p>Core owns the policy around this: which tools are offered, how retrieval is attached to a run,
 * and {@link KnowledgeScopeFilter}, the one definition of who may read what. An implementation owns
 * storage, text extraction, chunking and enumeration. Nothing here names a vector store, because
 * enumeration is the part no portable vector-store interface offers and each implementation has to
 * reach its own database for.
 *
 * <p>There is no implementation in core, so a deployment without one of these modules simply has no
 * knowledge base: the tools are not registered and no retrieval is attached. That is a supported
 * configuration, not a degraded one.
 */
public interface KnowledgeBase {

  /**
   * Splits, embeds and stores {@code source}, returning the id its chunks share.
   *
   * <p>Every chunk is stamped with the keys in {@link KnowledgeMetadata}, including the blank scope
   * fields — see that class for why the blanks matter.
   */
  String index(KnowledgeSource source);

  /**
   * One page of the documents {@code scope} may read, newest first, as documents rather than
   * chunks.
   *
   * <p>Paginated in the contract rather than only in the caller, so an implementation can push the
   * limit down to its database. A signature returning every entry would have already paid the cost
   * of materialising the whole knowledge base by the time anything trimmed it.
   */
  KnowledgePage list(KnowledgeScope scope, int offset, int limit);

  /**
   * One document with the text it was indexed from, or empty where {@code scope} cannot reach it.
   *
   * <p>On the contract rather than left to a caller for the same reason {@link #move} is: nothing
   * outside an implementation can read a stored document's text back, because searching returns
   * what a query matched rather than what a document holds, and there is no query here.
   *
   * <p>Scoped exactly as {@link #delete} is — a document id belonging to somebody else is not found
   * rather than refused, so this cannot be used to ask whether one exists.
   */
  Optional<KnowledgeDocument> read(KnowledgeScope scope, String docId);

  /**
   * Removes a document and all its chunks, silently doing nothing if {@code scope} cannot reach it.
   */
  void delete(KnowledgeScope scope, String docId);

  /**
   * Moves a document into another knowledge base, keeping its id, title, origin and content.
   *
   * <p>Here rather than on the caller because nothing outside an implementation can read a stored
   * document's text back — searching returns what a query matched, not a document — and a move is a
   * rewrite of every chunk's scope. The caller decides who may do it; this only carries it out.
   *
   * <p>Scoped like {@link #delete}: a document {@code scope} cannot reach is not found rather than
   * moved, which is what keeps a copied id from dragging somebody else's document into a scope of
   * one's own. Moving a document to the base it is already in is a no-op that still reports it.
   *
   * @return the document as it now stands, or empty when {@code scope} cannot reach {@code docId}
   */
  Optional<KnowledgeEntry> move(KnowledgeScope scope, String docId, KnowledgeScope.Target target);

  /**
   * A retriever restricted to what {@code scope} may read, for attaching to a run's advisor chain.
   *
   * <p>Returning Spring AI's own {@link DocumentRetriever} rather than a type of ours is what lets
   * core assemble a stock {@code RetrievalAugmentationAdvisor} around it.
   */
  default DocumentRetriever retrieverFor(KnowledgeScope scope) {
    return retrieverFor(scope, null);
  }

  /**
   * The same, narrowed to the documents {@code extra} also matches — for a run that wants part of
   * what its scope may read rather than all of it, such as event triage reading only the playbook
   * documents its source names.
   *
   * <p>The narrowing is a parameter here rather than something a caller wraps around the retriever
   * because it has to be applied where the scope filter is, in the query the store executes: a
   * retriever that fetched by scope and discarded afterwards would return the configured number of
   * documents and then throw most of them away, which for a top-k search means the wanted ones are
   * usually not among them.
   *
   * <p>{@code extra} can only ever narrow — {@link KnowledgeScopeFilter#readableBy(KnowledgeScope,
   * Filter.Expression)} composes it, and it is the only thing allowed to.
   */
  DocumentRetriever retrieverFor(KnowledgeScope scope, Filter.Expression extra);

  /**
   * An on-demand search, for when the automatic retrieval on the turn did not bring back what was
   * needed — and for finding out what the automatic retrieval's threshold should be.
   *
   * <p><b>Not gated by that threshold.</b> Applying it here would make this useless in the one case
   * it is most needed: a threshold set too high returns nothing, shows no scores, and so gives no
   * evidence of what it is excluding or how far off it is. An explicit search should report the
   * best matches and what they scored, and let the caller judge.
   *
   * <p>Scoped exactly as retrieval is — this relaxes the relevance bar, never who may read what.
   *
   * <p>The returned documents carry their similarity in {@link Document#getScore()}.
   */
  List<Document> search(KnowledgeScope scope, String query, int topK);
}
