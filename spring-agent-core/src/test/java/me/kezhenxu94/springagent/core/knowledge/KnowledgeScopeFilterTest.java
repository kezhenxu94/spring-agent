package me.kezhenxu94.springagent.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.ai.vectorstore.filter.converter.PrintFilterExpressionConverter;

/**
 * The scope filter, which is the whole of the isolation between one user's knowledge and another's.
 *
 * <p>Asserted two ways on purpose. Printing pins the exact shape, so a refactor that quietly drops
 * a clause fails here rather than in production. Searching a real {@link SimpleVectorStore} then
 * checks the meaning, because a filter can have the right shape and the wrong logic.
 *
 * <p>The store is given an embedding model that returns the same vector for everything, so every
 * document is equally similar to every query and the filter is the only thing deciding what comes
 * back. That is what makes an assertion about retrieval an assertion about the filter — and it goes
 * through exactly the machinery production uses, rather than a reimplementation of it in the test.
 */
class KnowledgeScopeFilterTest {

  /** Constant embeddings: similarity is uniform, so only the filter can exclude anything. */
  private static final EmbeddingModel CONSTANT_EMBEDDING =
      new EmbeddingModel() {
        @Override
        public float[] embed(final Document document) {
          return new float[] {1f, 0f, 0f};
        }

        @Override
        public EmbeddingResponse call(final EmbeddingRequest request) {
          final var embeddings = new java.util.ArrayList<Embedding>();
          for (var i = 0; i < request.getInstructions().size(); i++) {
            embeddings.add(new Embedding(new float[] {1f, 0f, 0f}, i));
          }
          return new EmbeddingResponse(embeddings);
        }
      };

  private static String print(final Filter.Expression expression) {
    return new PrintFilterExpressionConverter().convertExpression(expression);
  }

  private static String printReadable(final KnowledgeScope scope) {
    return print(KnowledgeScopeFilter.readableBy(scope));
  }

  /**
   * A chunk as the indexer writes it: all three scope keys present, blank where they do not apply.
   */
  private static Document chunk(
      final String id, final String owner, final String group, final String tenant) {
    return new Document(
        id,
        "content of " + id,
        Map.of(
            KnowledgeMetadata.OWNER, owner,
            KnowledgeMetadata.GROUP, group,
            KnowledgeMetadata.TENANT, tenant));
  }

  private static SimpleVectorStore storeOf(final Document... documents) {
    final var store = SimpleVectorStore.builder(CONSTANT_EMBEDDING).build();
    store.add(List.of(documents));
    return store;
  }

  private static List<String> readableIds(
      final SimpleVectorStore store, final KnowledgeScope reader) {
    return store
        .similaritySearch(
            SearchRequest.builder()
                .query("anything")
                .topK(100)
                .similarityThresholdAll()
                .filterExpression(KnowledgeScopeFilter.readableBy(reader))
                .build())
        .stream()
        .map(Document::getId)
        .sorted()
        .toList();
  }

  @Nested
  class Shape {

    @Test
    @DisplayName("a user with no group and no tenant is filtered on owner alone")
    void ownerOnly() {
      assertThat(printReadable(new KnowledgeScope("u1", "", ""))).isEqualTo("owner EQ \"u1\"");
    }

    @Test
    @DisplayName("a tenant adds one disjunct")
    void ownerOrTenant() {
      assertThat(printReadable(new KnowledgeScope("u1", "", "t1")))
          .isEqualTo("owner EQ \"u1\" OR tenant EQ \"t1\"");
    }

    @Test
    @DisplayName("a group chat reaches own, group and tenant knowledge")
    void ownerOrGroupOrTenant() {
      assertThat(printReadable(new KnowledgeScope("u1", "g1", "t1")))
          .isEqualTo("owner EQ \"u1\" OR group EQ \"g1\" OR tenant EQ \"t1\"");
    }

    @Test
    @DisplayName("null and blank identities are the same thing")
    void nullIsBlank() {
      assertThat(printReadable(new KnowledgeScope("u1", null, null)))
          .isEqualTo(printReadable(new KnowledgeScope("u1", "", "")));
    }

    @Test
    @DisplayName("no null checks, which Milvus cannot convert")
    void noNullChecks() {
      // MilvusFilterExpressionConverter has no case for ISNULL/ISNOTNULL and throws when it meets
      // one, so a null check here would fail at query time rather than at compile time.
      assertThat(
              List.of(
                  printReadable(new KnowledgeScope("u", "", "")),
                  printReadable(new KnowledgeScope("u", "g", "t")),
                  print(KnowledgeScopeFilter.firstChunks(new KnowledgeScope("u", "g", "t"))),
                  print(KnowledgeScopeFilter.document(new KnowledgeScope("u", "g", "t"), "d"))))
          .allSatisfy(printed -> assertThat(printed).doesNotContain("NULL"));
    }
  }

  @Nested
  class BlankIdentitiesAreNeverClauses {

    @Test
    @DisplayName(
        "a blank tenant emits no clause, so it cannot match documents storing a blank tenant")
    void blankTenantEmitsNoClause() {
      assertThat(printReadable(new KnowledgeScope("u1", "", ""))).doesNotContain("tenant");
    }

    @Test
    @DisplayName("a user with no tenant does not read every other user's documents")
    void blankTenantIsNotAWildcard() {
      // The leak this guards. Every user-scoped document stores tenant="", so a `tenant == ""`
      // clause would match all of them and hand one user the entire deployment's knowledge.
      final var store =
          storeOf(
              chunk("alice-own", "alice", "", ""),
              chunk("bob-own", "bob", "", ""),
              chunk("carol-own", "carol", "", ""));

      assertThat(readableIds(store, new KnowledgeScope("alice", "", "")))
          .containsExactly("alice-own");
    }

    @Test
    @DisplayName("a user in no group does not read every group's documents")
    void blankGroupIsNotAWildcard() {
      final var store =
          storeOf(chunk("alice-own", "alice", "", ""), chunk("eng-shared", "", "eng", ""));

      assertThat(readableIds(store, new KnowledgeScope("alice", "", "")))
          .containsExactly("alice-own");
    }
  }

  @Nested
  class WhoReadsWhat {

    private final SimpleVectorStore store =
        storeOf(
            chunk("alice-own", "alice", "", ""),
            chunk("bob-own", "bob", "", ""),
            chunk("eng-shared", "", "eng", ""),
            chunk("sales-shared", "", "sales", ""),
            chunk("acme-wide", "", "", "acme"),
            chunk("globex-wide", "", "", "globex"));

    @Test
    @DisplayName("a one-to-one chat with no tenant reads only the user's own knowledge")
    void p2pNoTenant() {
      assertThat(readableIds(store, new KnowledgeScope("alice", "", "")))
          .containsExactly("alice-own");
    }

    @Test
    @DisplayName("the tenant knowledge base is always wired in, group knowledge is not")
    void p2pWithTenant() {
      assertThat(readableIds(store, new KnowledgeScope("alice", "", "acme")))
          .containsExactly("acme-wide", "alice-own");
    }

    @Test
    @DisplayName("a group chat additionally reads that group's knowledge")
    void groupChat() {
      assertThat(readableIds(store, new KnowledgeScope("alice", "eng", "acme")))
          .containsExactly("acme-wide", "alice-own", "eng-shared");
    }

    @Test
    @DisplayName("never another user's, another group's, or another tenant's knowledge")
    void neverSomeoneElses() {
      assertThat(readableIds(store, new KnowledgeScope("alice", "eng", "acme")))
          .doesNotContain("bob-own", "sales-shared", "globex-wide");
    }
  }

  @Nested
  class ComposedExpressions {

    @Test
    @DisplayName("deleting is scoped, so a docId belonging to another user matches nothing")
    void deleteIsScoped() {
      final var mine =
          new Document(
              "a",
              "x",
              Map.of(
                  KnowledgeMetadata.OWNER,
                  "alice",
                  KnowledgeMetadata.GROUP,
                  "",
                  KnowledgeMetadata.TENANT,
                  "",
                  KnowledgeMetadata.DOC_ID,
                  "doc-1"));
      final var theirs =
          new Document(
              "b",
              "x",
              Map.of(
                  KnowledgeMetadata.OWNER,
                  "bob",
                  KnowledgeMetadata.GROUP,
                  "",
                  KnowledgeMetadata.TENANT,
                  "",
                  KnowledgeMetadata.DOC_ID,
                  "doc-1"));
      final var store = storeOf(mine, theirs);

      final var found =
          store.similaritySearch(
              SearchRequest.builder()
                  .query("anything")
                  .topK(100)
                  .similarityThresholdAll()
                  .filterExpression(
                      KnowledgeScopeFilter.document(new KnowledgeScope("alice", "", ""), "doc-1"))
                  .build());

      assertThat(found).extracting(Document::getId).containsExactly("a");
    }

    @Test
    @DisplayName("the disjunction is parenthesised before another condition is anded onto it")
    void disjunctionIsGrouped() {
      // Without the group, `owner == a OR tenant == t AND chunk == 0` binds the chunk test to the
      // tenant branch alone: every one of the user's own chunks would come back as a listing row,
      // so a ten-chunk document would be listed ten times.
      final var scope = new KnowledgeScope("alice", "", "acme");
      final var store =
          storeOf(
              withChunk(chunk("own-first", "alice", "", ""), 0),
              withChunk(chunk("own-second", "alice", "", ""), 1),
              withChunk(chunk("tenant-first", "", "", "acme"), 0),
              withChunk(chunk("tenant-second", "", "", "acme"), 1));

      final var listed =
          store
              .similaritySearch(
                  SearchRequest.builder()
                      .query("anything")
                      .topK(100)
                      .similarityThresholdAll()
                      .filterExpression(KnowledgeScopeFilter.firstChunks(scope))
                      .build())
              .stream()
              .map(Document::getId)
              .sorted()
              .toList();

      assertThat(listed).containsExactly("own-first", "tenant-first");
    }

    @Test
    @DisplayName(
        "replacing a document matches only the scope being written, not everything readable")
    void replacementIsScopedToTheOwningScope() {
      // Alice can read the tenant knowledge base, so a replacement scoped to what she can *read*
      // would let her destroy a company-wide document by re-indexing its id as her own. Scoping to
      // what she is *writing* means the id simply does not match and she gets a new document.
      final var tenantDoc = owned("tenant-copy", "", "", "acme", "doc-1");
      final var aliceDoc = owned("alice-copy", "alice", "", "", "doc-1");
      final var store = storeOf(tenantDoc, aliceDoc);

      final var replacingOwn =
          KnowledgeScopeFilter.documentOwnedBy(new KnowledgeScope("alice", "", ""), "doc-1");

      assertThat(matches(store, replacingOwn)).containsExactly("alice-copy");
    }

    @Test
    @DisplayName("an owning-scope match never treats a blank field as a wildcard")
    void owningScopeBlanksAreExact() {
      // The trap from the other direction: a group-owned document stores a blank owner, so
      // readableBy(owningScope) would start with `owner == ""` and sweep up every group- and
      // tenant-owned document in the deployment.
      final var engDoc = owned("eng", "", "eng", "", "doc-9");
      final var salesDoc = owned("sales", "", "sales", "", "doc-9");
      final var tenantDoc = owned("tenant", "", "", "acme", "doc-9");
      final var store = storeOf(engDoc, salesDoc, tenantDoc);

      final var replacingEng =
          KnowledgeScopeFilter.documentOwnedBy(new KnowledgeScope("", "eng", ""), "doc-9");

      assertThat(matches(store, replacingEng)).containsExactly("eng");
    }

    private static List<String> matches(
        final SimpleVectorStore store, final Filter.Expression expression) {
      return store
          .similaritySearch(
              SearchRequest.builder()
                  .query("anything")
                  .topK(100)
                  .similarityThresholdAll()
                  .filterExpression(expression)
                  .build())
          .stream()
          .map(Document::getId)
          .sorted()
          .toList();
    }

    private static Document owned(
        final String id,
        final String owner,
        final String group,
        final String tenant,
        final String docId) {
      return new Document(
          id,
          "content",
          Map.of(
              KnowledgeMetadata.OWNER, owner,
              KnowledgeMetadata.GROUP, group,
              KnowledgeMetadata.TENANT, tenant,
              KnowledgeMetadata.DOC_ID, docId));
    }

    private static Document withChunk(final Document document, final int chunk) {
      final var metadata = new java.util.HashMap<>(document.getMetadata());
      metadata.put(KnowledgeMetadata.CHUNK, chunk);
      return new Document(document.getId(), document.getText(), metadata);
    }
  }

  /**
   * Narrowing a read with a caller's own expression — what event triage does to read only the
   * playbook documents its source names.
   */
  @Nested
  class Narrowing {

    private static final FilterExpressionTextParser PARSER = new FilterExpressionTextParser();

    private static String printNarrowed(final KnowledgeScope scope, final String expression) {
      return print(KnowledgeScopeFilter.readableBy(scope, PARSER.parse(expression)));
    }

    @Test
    @DisplayName("the extra clause is ANDed onto the scope, both sides parenthesised")
    void bothSidesAreGrouped() {
      // Both groups are the assertion. A converter renders AND as `left && right` and parenthesises
      // nothing but a Filter.Group, so an ungrouped disjunction on either side would lose its last
      // branch to the AND — see readableBy's javadoc for what each of those two failures costs.
      assertThat(printNarrowed(new KnowledgeScope("u1", "g1", "t1"), "docId in ['a','b']"))
          .isEqualTo(
              "(owner EQ \"u1\" OR group EQ \"g1\" OR tenant EQ \"t1\") AND (docId IN"
                  + " [\"a\",\"b\"])");
    }

    @Test
    @DisplayName("a scope with nothing to disjoin still parenthesises the extra clause")
    void ownerOnlyIsStillGrouped() {
      assertThat(printNarrowed(new KnowledgeScope("u1", "", ""), "docId == 'runbook'"))
          .isEqualTo("owner EQ \"u1\" AND (docId EQ \"runbook\")");
    }

    @Test
    @DisplayName("no extra clause leaves the filter exactly as it was")
    void nullExtraChangesNothing() {
      final var scope = new KnowledgeScope("u1", "g1", "t1");
      assertThat(print(KnowledgeScopeFilter.readableBy(scope, null)))
          .isEqualTo(printReadable(scope));
    }

    @Test
    @DisplayName("it narrows what the owner may read")
    void narrows() {
      final var store =
          storeOf(
              docChunk("playbook", "agent", "runbook-github"),
              docChunk("notes", "agent", "something-else"));

      assertThat(
              matching(
                  store,
                  KnowledgeScopeFilter.readableBy(
                      new KnowledgeScope("agent", "", ""),
                      PARSER.parse("docId == 'runbook-github'"))))
          .containsExactly("playbook");
    }

    @Test
    @DisplayName("and can never widen it, however inviting the expression")
    void cannotWiden() {
      // The property the whole design rests on: an expression is composed under the scope filter,
      // not beside it, so a document the scope cannot reach stays unreachable whatever the extra
      // clause says about it.
      final var store =
          storeOf(
              docChunk("mine", "agent", "runbook-github"),
              docChunk("somebody-elses", "another-agent", "runbook-github"));

      assertThat(
              matching(
                  store,
                  KnowledgeScopeFilter.readableBy(
                      new KnowledgeScope("agent", "", ""),
                      PARSER.parse("docId == 'runbook-github'"))))
          .containsExactly("mine");
    }

    private static Document docChunk(final String id, final String owner, final String docId) {
      return new Document(
          id,
          "content of " + id,
          Map.of(
              KnowledgeMetadata.OWNER,
              owner,
              KnowledgeMetadata.GROUP,
              "",
              KnowledgeMetadata.TENANT,
              "",
              KnowledgeMetadata.DOC_ID,
              docId));
    }

    private static List<String> matching(
        final SimpleVectorStore store, final Filter.Expression expression) {
      return store
          .similaritySearch(
              SearchRequest.builder()
                  .query("anything")
                  .topK(100)
                  .similarityThresholdAll()
                  .filterExpression(expression)
                  .build())
          .stream()
          .map(Document::getId)
          .sorted()
          .toList();
    }
  }
}
