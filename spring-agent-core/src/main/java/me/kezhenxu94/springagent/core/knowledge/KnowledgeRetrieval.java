package me.kezhenxu94.springagent.core.knowledge;

import org.springframework.ai.vectorstore.filter.Filter;

/**
 * What a run's automatic retrieval should look at, for the runs where that is not simply "whatever
 * the person asking may read".
 *
 * <p>Left off an {@code AgentRequest} — which is every request a person's message produces — the
 * scope is derived from the run's own identity and the query is what the person said. This exists
 * for the other case: a run nobody started, briefed with text somebody else wrote, whose retrieval
 * should be a fixed lookup against a knowledge base chosen by whoever configured the deployment.
 * Event triage is that case, and the reason each field is here:
 *
 * <ul>
 *   <li>{@code scope} because such a run must not read whatever the group or tenant on the incoming
 *       event happens to name. Stating the scope rather than deriving it is what makes "only this
 *       identity's documents" a property of one line of code instead of a property of how a
 *       deployment filed its documents.
 *   <li>{@code filter} because one identity's knowledge base holds more than one kind of thing, and
 *       which part of it answers a given source is a deployment's decision rather than a similarity
 *       score's.
 *   <li>{@code query} because the run's own text is evidence written by whoever caused the event.
 *       Retrieving against it would let that text choose which of the deployment's own documents
 *       the model is shown, and would match a runbook against a stack trace, which is not what
 *       either is for.
 * </ul>
 *
 * <p>The query is used for retrieval only. {@code RetrievalAugmentationAdvisor} augments the
 * original user message with what was found, not the transformed one, so replacing the query here
 * does not replace what the model is asked — see {@code AgentToolsProvider#knowledgeRetrieval}.
 *
 * @param scope whose documents may be read; never null when this record is present
 * @param filter narrows that further, or null for all of it
 * @param query what to retrieve against, or blank to retrieve against the run's own text
 */
public record KnowledgeRetrieval(KnowledgeScope scope, Filter.Expression filter, String query) {

  public KnowledgeRetrieval {
    if (scope == null) {
      throw new IllegalArgumentException(
          "A KnowledgeRetrieval must state its scope: leave the whole record off the request to"
              + " derive it from the run's identity instead");
    }
  }

  /** Whether the query should replace the run's own text when retrieving. */
  public boolean hasQuery() {
    return query != null && !query.isBlank();
  }
}
