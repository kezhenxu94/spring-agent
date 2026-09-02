package me.kezhenxu94.springagent.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Which knowledge base an administrator reaches, which is the whole of what these tools decide.
 *
 * <p>The scope they ask for is the assertion: an owner alone, no group and no tenant, because that
 * is what {@code SituationSweeper} reads a playbook with. A scope wider than that would show an
 * administrator documents an unattended run cannot see, and the check they came to make — whether
 * the playbook will be retrieved — would pass while the run still found nothing.
 */
class KnowledgeAdminToolsTest {

  private final AtomicReference<KnowledgeScope> listed = new AtomicReference<>();
  private final AtomicReference<KnowledgeScope> searched = new AtomicReference<>();
  private KnowledgeAdminTools tools;

  private final KnowledgeBase knowledgeBase =
      new KnowledgeBase() {
        @Override
        public String index(final KnowledgeSource source) {
          return "doc-1";
        }

        @Override
        public KnowledgePage list(final KnowledgeScope scope, final int offset, final int limit) {
          listed.set(scope);
          return new KnowledgePage(
              List.of(
                  new KnowledgeEntry(
                      "github-triage",
                      "How to triage GitHub issues",
                      "om_42",
                      3,
                      Instant.parse("2026-08-01T00:00:00Z"),
                      KnowledgeScope.Target.OWN)),
              false);
        }

        @Override
        public Optional<KnowledgeDocument> read(
            final KnowledgeScope scope, final KnowledgeScope.Target owning, final String docId) {
          return Optional.empty();
        }

        @Override
        public void delete(
            final KnowledgeScope scope, final KnowledgeScope.Target owning, final String docId) {}

        @Override
        public Optional<KnowledgeEntry> move(
            final KnowledgeScope scope,
            final KnowledgeScope.Target owning,
            final String docId,
            final KnowledgeScope.Target target) {
          return Optional.empty();
        }

        @Override
        public DocumentRetriever retrieverFor(
            final KnowledgeScope scope,
            final org.springframework.ai.vectorstore.filter.Filter.Expression extra) {
          return query -> List.of();
        }

        @Override
        public List<Document> search(
            final KnowledgeScope scope, final String query, final int topK) {
          searched.set(scope);
          return List.of(
              Document.builder()
                  .text("Page the owner before restarting anything.")
                  .metadata(
                      Map.of(
                          KnowledgeMetadata.TITLE,
                          "How to triage GitHub issues",
                          KnowledgeMetadata.DOC_ID,
                          "github-triage"))
                  .build());
        }
      };

  @BeforeEach
  void setUp() {
    final var source = new ResourceBundleMessageSource();
    source.setBasename(CoreMessages.BASENAME);
    source.setDefaultEncoding("UTF-8");
    final var properties =
        new SpringAgentProperties(
            null,
            new SpringAgentProperties.Ai(Set.of(), Map.of(), null, null, null, null, null, null),
            Locale.ENGLISH,
            null,
            null);
    tools =
        new KnowledgeAdminTools(knowledgeBase, properties, new CoreMessages(source, properties));
  }

  @Test
  @DisplayName("a listing reads the owner's own knowledge base and nothing shared with them")
  void listsTheOwnersOwnBase() {
    final var result = tools.listOwnerKnowledgeBase("ou_agent", null, null);

    assertThat(listed.get()).isEqualTo(new KnowledgeScope("ou_agent", "", ""));
    assertThat(result).contains("ou_agent").contains("github-triage");
  }

  /**
   * Every row is that owner's own by construction, and the label the other listing carries reads
   * "your own" — which is false about somebody else's documents.
   */
  @Test
  @DisplayName("a listing does not label each row with a scope of the caller's")
  void listingOmitsTheScopeLabel() {
    assertThat(tools.listOwnerKnowledgeBase("ou_agent", null, null)).doesNotContain("your own");
  }

  @Test
  @DisplayName("a search reads the owner's own knowledge base, the scope a triage run retrieves in")
  void searchesTheOwnersOwnBase() {
    final var result = tools.searchOwnerKnowledge("ou_agent", "how do I triage this", null);

    assertThat(searched.get()).isEqualTo(new KnowledgeScope("ou_agent", "", ""));
    assertThat(result).contains("Page the owner before restarting anything.");
  }

  @Test
  @DisplayName("no owner is refused rather than read as the caller's own knowledge base")
  void ownerIsRequired() {
    // Defaulting to the run's own scope would answer a question about somebody else's knowledge
    // base with the contents of one's own, which reads as the playbook being missing.
    assertThat(tools.listOwnerKnowledgeBase(" ", null, null)).contains("A user id is required");
    assertThat(tools.searchOwnerKnowledge(null, "anything", null))
        .contains("A user id is required");
    assertThat(listed.get()).isNull();
    assertThat(searched.get()).isNull();
  }
}
