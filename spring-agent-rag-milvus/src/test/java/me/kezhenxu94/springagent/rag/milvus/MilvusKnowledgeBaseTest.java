package me.kezhenxu94.springagent.rag.milvus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeEntry;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeMetadata;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeScope;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.milvus.MilvusContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The knowledge base against a real Milvus, which is the only place several of these can be checked
 * at all: that the scope filter survives conversion to Milvus' own expression syntax, that its
 * {@code OR} across metadata keys does what it reads as, that a collection is loaded and so answers
 * queries after a restart, and that listing pages by document rather than by chunk.
 *
 * <p>Embeddings are constant, so every document is equally similar to every query. Retrieval
 * ranking is Spring AI's business; what is asserted here is who can reach what, which the filter
 * decides and which uniform similarity leaves as the only variable.
 */
@Testcontainers
class MilvusKnowledgeBaseTest {

  private static final int DIMENSION = 8;

  @Container
  static final MilvusContainer MILVUS =
      new MilvusContainer(DockerImageName.parse("milvusdb/milvus:v2.4.13"));

  private static MilvusKnowledgeBase knowledgeBase;

  /** Uniform similarity: the filter is the only thing that can exclude a document. */
  private static final EmbeddingModel CONSTANT_EMBEDDING =
      new EmbeddingModel() {
        @Override
        public float[] embed(final Document document) {
          return vector();
        }

        @Override
        public int dimensions() {
          return DIMENSION;
        }

        @Override
        public EmbeddingResponse call(final EmbeddingRequest request) {
          final var embeddings = new ArrayList<Embedding>();
          for (var i = 0; i < request.getInstructions().size(); i++) {
            embeddings.add(new Embedding(vector(), i));
          }
          return new EmbeddingResponse(embeddings);
        }

        private static float[] vector() {
          final var v = new float[DIMENSION];
          v[0] = 1f;
          return v;
        }
      };

  @BeforeAll
  static void startKnowledgeBase() throws Exception {
    final var properties =
        new MilvusKnowledgeProperties(
            MILVUS.getHost(), MILVUS.getMappedPort(19530), "knowledge_test", DIMENSION, true);
    knowledgeBase = new MilvusKnowledgeBase(properties, agentProperties(), CONSTANT_EMBEDDING);
    knowledgeBase.afterPropertiesSet();
  }

  @AfterAll
  static void stopKnowledgeBase() {
    if (knowledgeBase != null) {
      knowledgeBase.destroy();
    }
  }

  private static SpringAgentProperties agentProperties() {
    return new SpringAgentProperties(
        null,
        new SpringAgentProperties.Ai(
            Set.of(),
            Map.of(),
            null,
            // Defaults for everything: zero means unset, and the record fills them in.
            new SpringAgentProperties.Ai.Rag(true, 0, 0d, 0, 0),
            null,
            "you are an agent",
            null,
            null),
        Locale.ENGLISH,
        null,
        null);
  }

  private static KnowledgeScope scope(final String owner, final String group, final String tenant) {
    return new KnowledgeScope(owner, group, tenant);
  }

  private static String store(
      final KnowledgeScope scope,
      final KnowledgeScope.Target target,
      final String title,
      final String text) {
    // The title doubles as the document id here: an id is required of every caller, and every
    // title in these tests names one document.
    return knowledgeBase.index(KnowledgeSource.ofText(scope, target, title, text, null, title));
  }

  private static List<String> searchTitles(final KnowledgeScope reader, final String query) {
    return knowledgeBase.search(reader, query, 50).stream()
        .map(d -> String.valueOf(d.getMetadata().get("title")))
        .distinct()
        .sorted()
        .toList();
  }

  @Nested
  class ScopeIsolation {

    @Test
    @DisplayName(
        "each scope reaches its own knowledge, the group's when in one, and the tenant's always")
    void scopesAreHonoured() {
      final var alice = scope("iso-alice", "iso-eng", "iso-acme");
      final var bob = scope("iso-bob", "", "iso-acme");

      store(alice, KnowledgeScope.Target.OWN, "iso-alice-note", "alice private note");
      store(bob, KnowledgeScope.Target.OWN, "iso-bob-note", "bob private note");
      store(alice, KnowledgeScope.Target.GROUP, "iso-eng-note", "engineering runbook");
      store(alice, KnowledgeScope.Target.TENANT, "iso-acme-note", "company holiday policy");

      // A group chat: own, group and tenant.
      assertThat(searchTitles(alice, "note"))
          .contains("iso-alice-note", "iso-eng-note", "iso-acme-note")
          .doesNotContain("iso-bob-note");

      // A one-to-one chat in the same tenant: own and tenant, never the group's.
      assertThat(searchTitles(bob, "note"))
          .contains("iso-bob-note", "iso-acme-note")
          .doesNotContain("iso-alice-note", "iso-eng-note");
    }

    @Test
    @DisplayName("a user with no tenant does not see documents whose tenant field is blank")
    void blankIsNotAWildcard() {
      // The leak the scope filter exists to prevent, asserted where the expression is really
      // converted to Milvus syntax rather than only printed.
      final var loner = scope("iso-loner", "", "");
      store(loner, KnowledgeScope.Target.OWN, "iso-loner-note", "a note of my own");

      assertThat(searchTitles(loner, "note")).containsExactly("iso-loner-note");
    }
  }

  @Nested
  class Listing {

    @Test
    @DisplayName("lists one entry per document, not per chunk, with the chunk count on it")
    void listsDocumentsNotChunks() {
      final var owner = scope("list-owner", "", "");
      // Long enough to split, so a chunk-level listing would show this several times over.
      store(owner, KnowledgeScope.Target.OWN, "list-long", "paragraph of text. ".repeat(400));

      final var page = knowledgeBase.list(owner, 0, 10);

      assertThat(page.entries()).hasSize(1);
      assertThat(page.entries().getFirst().title()).isEqualTo("list-long");
      assertThat(page.entries().getFirst().chunkCount()).isGreaterThan(1);
      assertThat(page.entries().getFirst().createdAt()).isNotNull();
      assertThat(page.hasMore()).isFalse();
    }

    @Test
    @DisplayName(
        "offset and limit walk the documents without gaps, duplicates, or a lost last page")
    void paginates() {
      final var owner = scope("page-owner", "", "");
      for (var i = 0; i < 5; i++) {
        store(owner, KnowledgeScope.Target.OWN, "page-doc-" + i, "content number " + i);
      }

      final var seen = new ArrayList<String>();
      var offset = 0;
      boolean more;
      do {
        final var page = knowledgeBase.list(owner, offset, 2);
        assertThat(page.entries()).hasSizeLessThanOrEqualTo(2);
        page.entries().forEach(e -> seen.add(e.title()));
        more = page.hasMore();
        offset += 2;
      } while (more);

      assertThat(seen).hasSize(5).doesNotHaveDuplicates();
      assertThat(seen)
          .containsExactlyInAnyOrder(
              "page-doc-0", "page-doc-1", "page-doc-2", "page-doc-3", "page-doc-4");
    }

    @Test
    @DisplayName("a listing says which knowledge base each document is in")
    void reportsScope() {
      final var alice = scope("label-alice", "label-eng", "label-acme");
      store(alice, KnowledgeScope.Target.GROUP, "label-group-doc", "shared with the team");

      final var groupEntry =
          knowledgeBase.list(alice, 0, 50).entries().stream()
              .filter(e -> "label-group-doc".equals(e.title()))
              .findFirst()
              .orElseThrow();

      assertThat(groupEntry.scope()).isEqualTo(KnowledgeScope.Target.GROUP);
    }
  }

  @Nested
  class Updating {

    @Test
    @DisplayName("re-indexing under the same id replaces the document rather than adding a second")
    void replacesInPlace() {
      final var owner = scope("upd-owner", "", "");
      final var docId =
          store(owner, KnowledgeScope.Target.OWN, "upd-spec", "the first draft says blue");

      final var sameId =
          knowledgeBase.index(
              KnowledgeSource.ofText(
                  owner,
                  KnowledgeScope.Target.OWN,
                  "upd-spec",
                  "the second draft says green",
                  null,
                  docId));

      assertThat(sameId).isEqualTo(docId);
      final var entries =
          knowledgeBase.list(owner, 0, 50).entries().stream()
              .filter(e -> "upd-spec".equals(e.title()))
              .toList();
      assertThat(entries).hasSize(1);

      // The superseded text must be gone, not merely outranked: leaving it behind is what makes a
      // revised document contradict itself in later searches.
      final var found = knowledgeBase.search(owner, "draft", 50);
      assertThat(found).isNotEmpty();
      assertThat(found).allSatisfy(d -> assertThat(d.getText()).doesNotContain("blue"));
    }

    @Test
    @DisplayName(
        "re-indexing another scope's id writes a new document instead of destroying theirs")
    void cannotReplaceAcrossScopes() {
      final var alice = scope("cross-alice", "", "cross-acme");
      final var tenantDocId =
          store(alice, KnowledgeScope.Target.TENANT, "cross-policy", "the company policy");

      // Alice can read the tenant document, so a replacement scoped to what she can read rather
      // than what she is writing would let her delete it here.
      knowledgeBase.index(
          KnowledgeSource.ofText(
              alice,
              KnowledgeScope.Target.OWN,
              "cross-policy-mine",
              "my own take",
              null,
              tenantDocId));

      final var titles =
          knowledgeBase.list(alice, 0, 50).entries().stream().map(e -> e.title()).toList();
      assertThat(titles).contains("cross-policy", "cross-policy-mine");
    }
  }

  @Nested
  class Reading {

    @Test
    @DisplayName("a document reads back as the text it was indexed from, and as its own entry")
    void readsWhatWasStored() {
      final var owner = scope("read-owner", "", "read-acme");
      final var docId =
          store(owner, KnowledgeScope.Target.OWN, "read-note", "the badge code is 4321");

      final var document =
          knowledgeBase.read(owner, KnowledgeScope.Target.OWN, docId).orElseThrow();

      assertThat(document.text()).contains("the badge code is 4321");
      // The entry travels with it, so a caller holding only a search hit does not need a second
      // query for the chunk count and the date.
      assertThat(document.entry().docId()).isEqualTo(docId);
      assertThat(document.entry().title()).isEqualTo("read-note");
      assertThat(document.entry().scope()).isEqualTo(KnowledgeScope.Target.OWN);
      assertThat(document.entry().chunkCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a document split into several chunks reads back in its own order")
    void rejoinsEveryChunkInOrder() {
      final var owner = scope("read-long", "", "");
      // Numbered, so a query that hands its rows back in segment order rather than in the
      // document's shows up as a jumble here rather than as text that happens to look fine.
      final var text =
          java.util.stream.IntStream.range(0, 200)
              .mapToObj(i -> "paragraph " + i + " of the policy. ")
              .reduce("", String::concat);
      final var docId = store(owner, KnowledgeScope.Target.OWN, "read-policy", text);

      final var document =
          knowledgeBase.read(owner, KnowledgeScope.Target.OWN, docId).orElseThrow();

      assertThat(document.entry().chunkCount()).isGreaterThan(1);
      assertThat(document.text().indexOf("paragraph 0 "))
          .isLessThan(document.text().indexOf("paragraph 199 "));
      assertThat(document.text()).contains("paragraph 100 ");
    }

    @Test
    @DisplayName("one id in two knowledge bases reads back as two documents, not as one mixture")
    void readsOneKnowledgeBaseAtATime() {
      final var owner = scope("read-both", "", "read-both-acme");
      final var docId =
          store(owner, KnowledgeScope.Target.OWN, "read-shared-id", "the private one");
      store(owner, KnowledgeScope.Target.TENANT, "read-shared-id", "the company one");

      // Read across the caller's whole reach these would come back as one document with both
      // texts in it, ordered by chunk ordinals that mean nothing across two documents.
      assertThat(knowledgeBase.read(owner, KnowledgeScope.Target.OWN, docId).orElseThrow().text())
          .isEqualTo("the private one");
      assertThat(
              knowledgeBase.read(owner, KnowledgeScope.Target.TENANT, docId).orElseThrow().text())
          .isEqualTo("the company one");
    }

    @Test
    @DisplayName("somebody else's document is not found rather than refused")
    void isScopedLikeADelete() {
      final var alice = scope("read-alice", "", "");
      final var mallory = scope("read-mallory", "", "");
      final var docId = store(alice, KnowledgeScope.Target.OWN, "read-private", "alice only");

      assertThat(knowledgeBase.read(mallory, KnowledgeScope.Target.OWN, docId)).isEmpty();
      assertThat(knowledgeBase.read(alice, KnowledgeScope.Target.OWN, docId)).isPresent();
    }
  }

  @Nested
  class Moving {

    @Test
    @DisplayName("a moved document is readable in its new scope and gone from the old one")
    void movesBetweenScopes() {
      final var alice = scope("move-alice", "move-eng", "");
      final var bob = scope("move-bob", "move-eng", "");
      final var docId =
          store(alice, KnowledgeScope.Target.OWN, "move-runbook", "how the release goes");

      // Bob is in the same group but cannot see Alice's own document.
      assertThat(searchTitles(bob, "release")).doesNotContain("move-runbook");

      final var moved =
          knowledgeBase.move(alice, KnowledgeScope.Target.OWN, docId, KnowledgeScope.Target.GROUP);

      assertThat(moved).isPresent();
      assertThat(moved.get().scope()).isEqualTo(KnowledgeScope.Target.GROUP);
      assertThat(searchTitles(bob, "release")).contains("move-runbook");
      // One document, not the original plus a copy — a listing counts what is really stored.
      assertThat(
              knowledgeBase.list(alice, 0, 50).entries().stream()
                  .filter(e -> "move-runbook".equals(e.title()))
                  .toList())
          .hasSize(1);
    }

    @Test
    @DisplayName("a move keeps the content, so the document is still what it said")
    void keepsTheContent() {
      final var owner = scope("move-keep", "", "move-acme");
      final var docId =
          store(owner, KnowledgeScope.Target.OWN, "move-policy", "the badge code is 4321");

      knowledgeBase.move(owner, KnowledgeScope.Target.OWN, docId, KnowledgeScope.Target.TENANT);

      final var found = knowledgeBase.search(owner, "badge", 50);
      assertThat(found).isNotEmpty();
      assertThat(found.getFirst().getText()).contains("the badge code is 4321");
      // And the listing still shows it as one document, in the base it was moved into.
      final var entry =
          knowledgeBase.list(owner, 0, 50).entries().stream()
              .filter(e -> "move-policy".equals(e.title()))
              .findFirst()
              .orElseThrow();
      assertThat(entry.scope()).isEqualTo(KnowledgeScope.Target.TENANT);
    }

    @Test
    @DisplayName("a document of somebody else's is not found rather than dragged into your own")
    void cannotMoveWhatYouCannotRead() {
      final var alice = scope("move-owner-alice", "", "");
      final var mallory = scope("move-owner-mallory", "", "");
      final var docId = store(alice, KnowledgeScope.Target.OWN, "move-private", "alice only");

      assertThat(
              knowledgeBase.move(
                  mallory, KnowledgeScope.Target.OWN, docId, KnowledgeScope.Target.OWN))
          .isEmpty();
      assertThat(searchTitles(alice, "alice only")).contains("move-private");
      assertThat(searchTitles(mallory, "alice only")).isEmpty();
    }

    @Test
    @DisplayName("a document split into several chunks is moved whole, not only its first chunk")
    void movesEveryChunk() {
      final var alice = scope("move-chunky", "move-chunky-eng", "");
      final var bob = scope("move-chunky-bob", "move-chunky-eng", "");
      final var docId =
          store(alice, KnowledgeScope.Target.OWN, "move-long", "paragraph of text. ".repeat(400));

      final var moved =
          knowledgeBase.move(alice, KnowledgeScope.Target.OWN, docId, KnowledgeScope.Target.GROUP);

      assertThat(moved).isPresent();
      assertThat(moved.get().chunkCount()).isGreaterThan(1);
      // Every chunk is in the group now, and none is left behind in Alice's own base: the two
      // counts agree only if the whole document moved.
      assertThat(knowledgeBase.search(bob, "paragraph", 500)).hasSize(moved.get().chunkCount());
      // And the listing still finds it, which only holds if chunk ordinals survived as numbers.
      assertThat(
              knowledgeBase.list(bob, 0, 50).entries().stream().map(KnowledgeEntry::title).toList())
          .contains("move-long");
    }
  }

  @Nested
  class SearchingIgnoresTheRelevanceBar {

    /**
     * A second knowledge base whose embeddings actually differ, so the similarity threshold has
     * something to bite on. The constant embeddings the other tests use make every score 1.0, which
     * is what they want — the filter as the only variable — and useless here.
     *
     * <p>One axis per keyword: a text mentioning "alpha" embeds orthogonally to one mentioning
     * "beta", so their cosine similarity is 0 and the default threshold excludes it.
     */
    private MilvusKnowledgeBase keywordBase() throws Exception {
      final EmbeddingModel keyword =
          new EmbeddingModel() {
            @Override
            public float[] embed(final Document document) {
              return axis(document.getText());
            }

            @Override
            public int dimensions() {
              return DIMENSION;
            }

            @Override
            public EmbeddingResponse call(final EmbeddingRequest request) {
              final var embeddings = new ArrayList<Embedding>();
              final var texts = request.getInstructions();
              for (var i = 0; i < texts.size(); i++) {
                embeddings.add(new Embedding(axis(texts.get(i)), i));
              }
              return new EmbeddingResponse(embeddings);
            }

            private static float[] axis(final String text) {
              final var v = new float[DIMENSION];
              v[text != null && text.contains("alpha") ? 0 : 1] = 1f;
              return v;
            }
          };

      final var properties =
          new MilvusKnowledgeProperties(
              MILVUS.getHost(), MILVUS.getMappedPort(19530), "keyword_test", DIMENSION, true);
      final var base = new MilvusKnowledgeBase(properties, agentProperties(), keyword);
      base.afterPropertiesSet();
      return base;
    }

    @Test
    @DisplayName(
        "a passage below the automatic threshold is still found by an explicit search, with its"
            + " score")
    void searchSeesWhatRetrievalFiltersOut() throws Exception {
      final var base = keywordBase();
      final var owner = scope("bar-owner", "", "");
      base.index(
          KnowledgeSource.ofText(
              owner,
              KnowledgeScope.Target.OWN,
              "bar-alpha",
              "all about alpha things",
              null,
              "bar-alpha"));

      // Automatic retrieval applies the configured threshold, and an orthogonal query scores 0.
      final var retrieved =
          base.retrieverFor(owner)
              .retrieve(org.springframework.ai.rag.Query.builder().text("beta only").build());
      assertThat(retrieved).isEmpty();

      // The explicit search does not, which is what makes it usable for choosing the threshold:
      // it reports the passage and what it actually scored instead of silently returning nothing.
      final var searched = base.search(owner, "beta only", 10);
      assertThat(searched).isNotEmpty();
      assertThat(searched.getFirst().getScore()).isNotNull();
      assertThat(searched.getFirst().getScore())
          .isLessThan(agentProperties().ai().rag().similarityThreshold());

      base.destroy();
    }

    @Test
    @DisplayName("relaxing the relevance bar does not relax the scope")
    void searchIsStillScoped() throws Exception {
      final var base = keywordBase();
      final var alice = scope("bar-alice", "", "");
      final var bob = scope("bar-bob", "", "");
      base.index(
          KnowledgeSource.ofText(
              alice,
              KnowledgeScope.Target.OWN,
              "bar-alice-doc",
              "alpha secrets of alice",
              null,
              "bar-alice-doc"));

      assertThat(base.search(bob, "alpha", 10)).isEmpty();
      assertThat(base.search(alice, "alpha", 10)).isNotEmpty();

      base.destroy();
    }

    @Test
    @DisplayName("an extra filter narrows retrieval, and still cannot reach across scopes")
    void retrievalNarrowsButNeverWidens() throws Exception {
      final var base = keywordBase();
      final var agent = scope("bar-agent", "", "");
      final var other = scope("bar-other", "", "");
      base.index(
          KnowledgeSource.ofText(
              agent, KnowledgeScope.Target.OWN, "playbook", "alpha runbook", null, "runbook"));
      base.index(
          KnowledgeSource.ofText(
              agent, KnowledgeScope.Target.OWN, "notes", "alpha notes", null, "notes"));
      // Somebody else's document under the very id the filter names — the case where a filter
      // composed beside the scope instead of under it would hand it over.
      base.index(
          KnowledgeSource.ofText(
              other, KnowledgeScope.Target.OWN, "theirs", "alpha runbook", null, "runbook"));

      final var parser = new org.springframework.ai.vectorstore.filter.FilterExpressionTextParser();
      final var retrieved =
          base.retrieverFor(agent, parser.parse("docId == 'runbook'"))
              .retrieve(org.springframework.ai.rag.Query.builder().text("alpha runbook").build());

      assertThat(retrieved)
          .isNotEmpty()
          .allSatisfy(
              document -> {
                assertThat(document.getMetadata().get(KnowledgeMetadata.DOC_ID))
                    .isEqualTo("runbook");
                assertThat(document.getMetadata().get(KnowledgeMetadata.OWNER))
                    .isEqualTo("bar-agent");
              });

      base.destroy();
    }
  }

  @Nested
  class Deleting {

    @Test
    @DisplayName("deleting removes every chunk of the document")
    void deletesWholeDocument() {
      final var owner = scope("del-owner", "", "");
      final var docId =
          store(owner, KnowledgeScope.Target.OWN, "del-doc", "sentence to remove. ".repeat(400));

      knowledgeBase.delete(owner, KnowledgeScope.Target.OWN, docId);

      final var titles =
          knowledgeBase.list(owner, 0, 50).entries().stream().map(e -> e.title()).toList();
      assertThat(titles).doesNotContain("del-doc");
    }

    @Test
    @DisplayName("one id in two knowledge bases: the copy that was named goes, the other stays")
    void deletesOnlyTheNamedKnowledgeBase() {
      // What happens whenever somebody files what they had already filed: a file's absolute path
      // is its id, so the private copy and the company's wear the same one. Deleting across
      // everything the caller can read would take the company's document away with theirs, and
      // nobody would be able to say which of the two they had asked to remove.
      final var owner = scope("del-both", "", "del-both-acme");
      final var docId = store(owner, KnowledgeScope.Target.OWN, "del-shared-id", "the private one");
      store(owner, KnowledgeScope.Target.TENANT, "del-shared-id", "the company one");

      knowledgeBase.delete(owner, KnowledgeScope.Target.OWN, docId);

      final var left =
          knowledgeBase.list(owner, 0, 50).entries().stream()
              .filter(e -> docId.equals(e.docId()))
              .toList();
      assertThat(left)
          .singleElement()
          .extracting(KnowledgeEntry::scope)
          .isEqualTo(KnowledgeScope.Target.TENANT);
      assertThat(
              knowledgeBase.read(owner, KnowledgeScope.Target.TENANT, docId).orElseThrow().text())
          .isEqualTo("the company one");
    }

    @Test
    @DisplayName("deleting somebody else's document does nothing")
    void cannotDeleteAnothersDocument() {
      final var alice = scope("delx-alice", "", "");
      final var bob = scope("delx-bob", "", "");
      final var aliceDoc = store(alice, KnowledgeScope.Target.OWN, "delx-alice-doc", "mine");

      knowledgeBase.delete(bob, KnowledgeScope.Target.OWN, aliceDoc);

      final var titles =
          knowledgeBase.list(alice, 0, 50).entries().stream().map(e -> e.title()).toList();
      assertThat(titles).contains("delx-alice-doc");
    }
  }
}
