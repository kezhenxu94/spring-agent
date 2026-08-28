package me.kezhenxu94.springagent.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.storage.FileSystemStorageProperties;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.context.support.ResourceBundleMessageSource;

/** What the knowledge tools record about a document, beyond the content itself. */
class KnowledgeBaseToolsTest {

  @TempDir Path location;

  private final AtomicReference<KnowledgeSource> indexed = new AtomicReference<>();
  private final AtomicReference<KnowledgeEntry> moved = new AtomicReference<>();
  private KnowledgeBaseTools tools;

  /** Records what it was asked to store, which is the whole of what these tests are about. */
  private final KnowledgeBase knowledgeBase =
      new KnowledgeBase() {
        @Override
        public String index(final KnowledgeSource source) {
          indexed.set(source);
          return "doc-1";
        }

        @Override
        public KnowledgePage list(final KnowledgeScope scope, final int offset, final int limit) {
          return KnowledgePage.EMPTY;
        }

        @Override
        public void delete(final KnowledgeScope scope, final String docId) {}

        @Override
        public Optional<KnowledgeEntry> move(
            final KnowledgeScope scope, final String docId, final KnowledgeScope.Target target) {
          moved.set(new KnowledgeEntry(docId, docId, "", 1, null, target));
          return Optional.of(moved.get());
        }

        @Override
        public DocumentRetriever retrieverFor(final KnowledgeScope scope) {
          return query -> List.of();
        }

        @Override
        public List<Document> search(
            final KnowledgeScope scope, final String query, final int topK) {
          return List.of();
        }
      };

  private static ToolContext context(final String replyMessageId) {
    return new ToolContext(
        replyMessageId == null
            ? Map.of(ToolContexts.KEY_USER_ID, "ou_1")
            : Map.of(
                ToolContexts.KEY_USER_ID,
                "ou_1",
                ToolContexts.KEY_REPLY_MESSAGE_ID,
                replyMessageId));
  }

  @BeforeEach
  void setUp() {
    final var source = new ResourceBundleMessageSource();
    source.setBasename(CoreMessages.BASENAME);
    source.setDefaultEncoding("UTF-8");
    final var properties =
        new SpringAgentProperties(
            null,
            new SpringAgentProperties.Ai(
                null, Set.of(), Map.of(), null, null, null, null, null, null),
            Locale.ENGLISH);
    tools =
        new KnowledgeBaseTools(
            knowledgeBase,
            new UserWorkspaceFactory(
                FileSystemStorageProperties.builder().location(location.toString()).build()),
            properties,
            new CoreMessages(source, properties));
  }

  @Nested
  class WhatIsRecordedAsTheOrigin {

    @Test
    @DisplayName("text pasted with no source is attributed to the message being answered")
    void pastedTextFallsBackToTheMessage() {
      tools.indexKnowledge(
          "Staging URL", "own", null, "staging is at 10.0.0.7", "om_42", context("om_42"));

      assertThat(indexed.get().attribution()).isEqualTo("om_42");
    }

    @Test
    @DisplayName("a source the caller gave is kept, and not replaced by the message")
    void explicitSourceWins() {
      tools.indexKnowledge(
          "Idol v1.5.5",
          "own",
          "https://wiki.example.com/idol",
          "the requirements",
          "https://wiki.example.com/idol",
          context("om_42"));

      assertThat(indexed.get().attribution()).isEqualTo("https://wiki.example.com/idol");
    }

    @Test
    @DisplayName("a surface with no message id records no origin rather than inventing one")
    void noMessageIdLeavesItBlank() {
      tools.indexKnowledge(
          "A note", "own", null, "something worth keeping", "a-note", context(null));

      assertThat(indexed.get().attribution()).isEmpty();
    }

    @Test
    @DisplayName("content read from a file is attributed to the file")
    void fileKeepsItsPath() throws Exception {
      final var file = location.resolve("ou_1/workspace/runbook.md");
      java.nio.file.Files.createDirectories(file.getParent());
      java.nio.file.Files.writeString(file, "how to release");

      tools.indexKnowledge(
          "Runbook", "own", file.toString(), null, file.toString(), context("om_42"));

      assertThat(indexed.get().attribution()).isEqualTo(file.toString());
      assertThat(indexed.get().mustBeRead()).isTrue();
    }
  }

  @Nested
  class WhatIsRefused {

    @Test
    @DisplayName("neither content nor a source to read is refused, and says so")
    void nothingToStore() {
      final var result =
          tools.indexKnowledge("A title", "own", null, null, "an-id", context("om_42"));

      assertThat(result).contains("Nothing to store");
      assertThat(indexed.get()).isNull();
    }

    @Test
    @DisplayName("a document with no id is refused, and is told what to identify it by")
    void noDocIdIsRefused() {
      // Filling one in would store a document nothing can ever index over, so the next call about
      // the same thing stores a second copy — the duplication the required id exists to prevent.
      final var result =
          tools.indexKnowledge(
              "The oncall rota", "own", null, "Kez is on call", null, context("om_42"));

      assertThat(result).contains("docId is required");
      assertThat(indexed.get()).isNull();
    }

    @Test
    @DisplayName("a file outside every reachable workspace is refused")
    void pathOutsideTheWorkspace() {
      final var result =
          tools.indexKnowledge(
              "Secrets", "own", "/etc/passwd", null, "/etc/passwd", context("om_42"));

      assertThat(result).contains("Access denied");
      assertThat(indexed.get()).isNull();
    }

    @Test
    @DisplayName("a scope that is not one of the three is refused rather than read as \"own\"")
    void unknownScopeIsRefused() {
      // Read as "own" it would take a document out of the company knowledge base because of a
      // typo, which is the one direction this must never go by default.
      final var result = tools.updateKnowledgeScope("a-doc", "everyone", context("om_42"));

      assertThat(result).contains("Not a knowledge base");
      assertThat(moved.get()).isNull();
    }

    @Test
    @DisplayName("moving a document into a group from a chat with no group is refused")
    void moveToGroupWithoutAGroup() {
      final var result = tools.updateKnowledgeScope("a-doc", "group", context("om_42"));

      assertThat(result).contains("no group");
      assertThat(moved.get()).isNull();
    }

    @Test
    @DisplayName("a group scope in a chat with no group is refused rather than stored unreachably")
    void groupScopeWithoutAGroup() {
      // Stamped with a blank group it would match no reader's filter: stored, reported as stored,
      // and never seen again.
      final var result =
          tools.indexKnowledge(
              "Team norms", "group", null, "we deploy on Fridays", "om_42", context("om_42"));

      assertThat(result).contains("no group");
      assertThat(indexed.get()).isNull();
    }
  }
}
