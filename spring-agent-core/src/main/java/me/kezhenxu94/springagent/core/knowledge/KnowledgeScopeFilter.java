package me.kezhenxu94.springagent.core.knowledge;

import lombok.experimental.UtilityClass;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder.Op;

/**
 * Turns a {@link KnowledgeScope} into the filter deciding what a run may read. This is the only
 * thing standing between one user's knowledge base and another's, so it is deliberately small and
 * has a test asserting the exact expression for every shape.
 *
 * <p>The filter is a disjunction over the three scope fields, which is why they are stored as three
 * independent keys rather than one packed string: a reader reaches a document through any scope it
 * belongs to.
 *
 * <p><b>A clause is emitted only for a non-blank identity, all three of them included.</b> This is
 * the correctness requirement, not a tidiness one. Documents carry blank strings for the scopes
 * that do not apply to them, so a request with no tenant that emitted {@code tenant == ''} would
 * match every user-scoped document in the deployment — every other user's knowledge, handed to
 * whoever asked — and a scope narrowed to one target, whose owner is blank, would do the same
 * through {@code owner == ''} for everything a group or a tenant owns. A scope carrying none of the
 * three is refused rather than answered. The tests cover each case by name.
 *
 * <p>Only {@code eq}, {@code or}, {@code and} and {@code group} are built here. Milvus' filter
 * converter has no case for {@code ISNULL}/{@code ISNOTNULL} and throws when it meets one, so "this
 * field does not apply" is expressed as a blank string rather than as a null check. A caller may
 * hand in an expression of its own to narrow a read further, and that one may use whatever the
 * store understands — see {@link #readableBy(KnowledgeScope, Filter.Expression)}.
 */
@UtilityClass
public class KnowledgeScopeFilter {

  /**
   * What {@code scope} may read: its own documents, plus its group's and tenant's if it has any.
   */
  public static Filter.Expression readableBy(final KnowledgeScope scope) {
    return readableBy(scope, null);
  }

  /**
   * The same, narrowed further by {@code extra} — a caller that wants some of what a scope may read
   * rather than all of it, such as a triage run reading only the documents its source's playbook
   * names.
   *
   * <p>{@code extra} narrows and can never widen, because it is {@code and}-ed onto the scope
   * disjunction rather than replacing it. That is the whole reason this is a parameter here instead
   * of each caller composing its own expression: an expression assembled elsewhere could be
   * assembled wrongly, and the failure would be silent and would hand over somebody else's
   * knowledge.
   *
   * <p><b>Both sides are parenthesised, and both have to be.</b> A converter renders {@code AND} as
   * {@code left && right} and adds parentheses for nothing but a {@link Filter.Group} — see {@code
   * AbstractFilterExpressionConverter#doExpression} — so precedence here is a matter of what the
   * tree says explicitly. Left ungrouped, the scope disjunction would bind {@code extra} to only
   * its last branch, handing over every one of a group's documents whether or not {@code extra}
   * held of them; and an {@code extra} that is itself a disjunction, which {@code docId in [...]}
   * written out longhand is, would lose its own last branch to the {@code and} the same way.
   * Grouping a single comparison is redundant, and cheaper than deciding case by case which shapes
   * need it.
   */
  public static Filter.Expression readableBy(
      final KnowledgeScope scope, final Filter.Expression extra) {
    final var b = new FilterExpressionBuilder();
    if (extra == null) {
      return disjunction(b, scope).build();
    }
    return b.and(grouped(b, scope), b.group(new FilterExpressionBuilder.Op(extra))).build();
  }

  /**
   * One document, but only where it is owned by exactly {@code owning} — every scope field matched,
   * including the blank ones.
   *
   * <p><b>This is the only way to name one document</b>, and an exact match rather than "one the
   * reader may reach" on purpose. A {@code docId} is unique within a knowledge base and not across
   * them: the same file, wiki token or URL indexed into a private base and into the company's is
   * two documents wearing one id, which is the normal outcome of somebody filing what they had
   * already filed. A filter that matched a reader's whole reach would then name both of them, so a
   * delete would take the company's copy along with the user's own, and a read would hand back the
   * two documents' chunks interleaved. The caller therefore has to say which base it means, and a
   * mismatch is a document not found rather than a different document acted on.
   *
   * <p>It is also what keeps a re-index from destroying somebody else's document: a user who may
   * *read* the tenant knowledge base must not be able to overwrite a document in it by indexing
   * that id as their own, and here a mismatched id simply matches nothing.
   *
   * <p>Note this cannot be expressed as {@code readableBy(owningScope)}. A document owned by a
   * group stores a blank owner, so that disjunction would begin {@code owner == ""} and match every
   * group-owned and tenant-owned document in the deployment — the same blank-as-wildcard trap the
   * read filter avoids by omitting blank clauses, arriving here from the opposite direction.
   */
  public static Filter.Expression documentOwnedBy(final KnowledgeScope owning, final String docId) {
    if (!owning.hasOwner() && !owning.hasGroup() && !owning.hasTenant()) {
      // Every stored chunk carries exactly one non-blank scope field, so an all-blank owning scope
      // matches nothing — which is safe and unreadable. It only ever arises from asking for a base
      // the requester has no identity for, a group document from a p2p chat say, and the caller
      // that did so needs to hear about it rather than be told the document does not exist.
      throw new IllegalArgumentException(
          "A knowledge scope with no owner, group or tenant owns nothing");
    }
    final var b = new FilterExpressionBuilder();
    return b.and(
            b.eq(KnowledgeMetadata.DOC_ID, docId),
            b.and(
                b.eq(KnowledgeMetadata.OWNER, owning.owner()),
                b.and(
                    b.eq(KnowledgeMetadata.GROUP, owning.group()),
                    b.eq(KnowledgeMetadata.TENANT, owning.tenant()))))
        .build();
  }

  /**
   * The zero-th chunk of each readable document — one row per document, which is what lets a
   * store's own offset and limit paginate documents rather than chunks.
   */
  public static Filter.Expression firstChunks(final KnowledgeScope scope) {
    final var b = new FilterExpressionBuilder();
    return b.and(grouped(b, scope), b.eq(KnowledgeMetadata.CHUNK, 0)).build();
  }

  /**
   * One {@code or} branch per identity the scope actually carries.
   *
   * <p>The owner clause is conditional for exactly the reason the other two are. A scope with a
   * blank owner is not hypothetical — it is what narrowing a read to one target produces, see
   * {@link KnowledgeScope#owning} — and {@code owner == ''} would match every group-owned and
   * tenant-owned document in the deployment, other tenants' included, because a document stores a
   * blank in the two scopes that do not apply to it.
   *
   * <p>A scope carrying no identity at all reaches nothing, and asking what it may read is a
   * mistake in the caller rather than a query with an empty answer. It throws instead of returning
   * a match-nothing expression, which would answer "your knowledge base is empty" and be believed.
   */
  private static Op disjunction(final FilterExpressionBuilder b, final KnowledgeScope scope) {
    Op op = null;
    if (scope.hasOwner()) {
      op = b.eq(KnowledgeMetadata.OWNER, scope.owner());
    }
    if (scope.hasGroup()) {
      final var group = b.eq(KnowledgeMetadata.GROUP, scope.group());
      op = op == null ? group : b.or(op, group);
    }
    if (scope.hasTenant()) {
      final var tenant = b.eq(KnowledgeMetadata.TENANT, scope.tenant());
      op = op == null ? tenant : b.or(op, tenant);
    }
    if (op == null) {
      throw new IllegalArgumentException(
          "A knowledge scope with no owner, group or tenant reaches nothing");
    }
    return op;
  }

  /**
   * The disjunction, parenthesised when it has more than one branch so that {@code and}-ing a
   * further condition onto it cannot bind to only the last branch of the {@code or}.
   */
  private static Op grouped(final FilterExpressionBuilder b, final KnowledgeScope scope) {
    final var op = disjunction(b, scope);
    return branches(scope) > 1 ? b.group(op) : op;
  }

  private static int branches(final KnowledgeScope scope) {
    return (scope.hasOwner() ? 1 : 0) + (scope.hasGroup() ? 1 : 0) + (scope.hasTenant() ? 1 : 0);
  }
}
