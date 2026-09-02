package me.kezhenxu94.springagent.integration.websocket.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeBase;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeDocument;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeEntry;
import me.kezhenxu94.springagent.core.knowledge.KnowledgePage;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeScope;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeSource;
import me.kezhenxu94.springagent.core.storage.StorageProperties;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import me.kezhenxu94.springagent.integration.websocket.config.WebMessages;
import me.kezhenxu94.springagent.integration.websocket.config.WebProperties;
import me.kezhenxu94.springagent.integration.websocket.security.WebUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who a knowledge request reads and writes as.
 *
 * <p>Every assertion here is about the same thing: a browser cannot name the identity a knowledge
 * operation runs under. That identity decides which documents come back, which are deleted, and who
 * a stored one becomes readable by — so a request able to state its own would be a request able to
 * read and empty somebody else's knowledge base, and none of it would look like a failure.
 *
 * <p>The scope refusals matter for a quieter reason. This surface puts no group on a run, so a
 * document filed into a group knowledge base from here would be stamped with a blank group, stored,
 * reported as stored, and then matched by no reader's filter ever again — a document that exists
 * and is unreachable, which is worse than a refusal.
 */
class KnowledgeControllerTest {

  private static final String ME = "ou_me";
  private static final String TENANT = "tenant_a";

  @Test
  @DisplayName("reads the caller's own knowledge base when no owner is named")
  void ownScopeByDefault() {
    final var controller = controller(new Recorder(), Set.of(), null);
    assertThat(controller.readableScope(user(ME, TENANT), null))
        .isEqualTo(new KnowledgeScope(ME, "", TENANT));
  }

  @Test
  @DisplayName("refuses somebody who is not an admin naming an owner")
  void ownerIsAdminOnly() {
    final var controller = controller(new Recorder(), Set.of(), null);
    assertThatThrownBy(() -> controller.readableScope(user(ME, TENANT), "ou_someone_else"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("403");
  }

  @Test
  @DisplayName("an admin reading somebody else gets that person's own scope and nothing wider")
  void adminReadsOneOwner() {
    final var controller = controller(new Recorder(), Set.of(ME), null);
    // Their own, not their own plus the admin's tenant: the admin tools read one owner, and a
    // scope carrying a tenant here would return documents the named person cannot see either.
    assertThat(controller.readableScope(user(ME, TENANT), "ou_someone_else"))
        .isEqualTo(new KnowledgeScope("ou_someone_else", "", ""));
  }

  @Test
  @DisplayName("refuses a group scope, which this surface has no identity for")
  void groupIsRefused() {
    final var controller = controller(new Recorder(), Set.of(), null);
    assertThatThrownBy(() -> controller.targetFor("group", user(ME, TENANT)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("group knowledge base");
  }

  @Test
  @DisplayName("refuses a company scope for a sign-in that carries no company")
  void tenantNeedsATenant() {
    final var controller = controller(new Recorder(), Set.of(), null);
    assertThat(controller.targetFor("tenant", user(ME, TENANT)))
        .isEqualTo(KnowledgeScope.Target.TENANT);
    assertThatThrownBy(() -> controller.targetFor("tenant", user(ME, "")))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  @DisplayName("refuses a scope it does not recognise rather than filing into the caller's own")
  void unknownScopeIsRefused() {
    final var controller = controller(new Recorder(), Set.of(), null);
    assertThatThrownBy(() -> controller.targetFor("everyone", user(ME, TENANT)))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  @DisplayName("deletes with the caller's scope, so another person's document id reaches nothing")
  void deleteIsScoped() {
    final var recorder = new Recorder();
    final var controller = controller(recorder, Set.of(ME), null);
    controller.delete(principal(ME, TENANT), "note:theirs");

    assertThat(recorder.deleted).containsExactly(Map.entry(scope(ME, TENANT), "note:theirs"));
  }

  @Test
  @DisplayName(
      "reads a document with the caller's scope, and reports one it cannot reach as absent")
  void readIsScoped() {
    final var recorder = new Recorder();
    final var controller = controller(recorder, Set.of(), null);

    final var document = controller.document(principal(ME, TENANT), "note:mine", null);
    assertThat(document).containsEntry("text", "what is stored").containsEntry("chunkCount", 2);

    // Somebody else's id is not found rather than refused, so this is not a way to ask whether
    // their document exists.
    assertThatThrownBy(() -> controller.document(principal(ME, TENANT), "note:theirs", null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
    assertThat(recorder.readDocuments)
        .containsExactly(
            Map.entry(scope(ME, TENANT), "note:mine"), Map.entry(scope(ME, TENANT), "note:theirs"));
  }

  @Test
  @DisplayName("refuses a non-admin naming an owner when reading one document")
  void readingAnothersDocumentIsAdminOnly() {
    final var controller = controller(new Recorder(), Set.of(), null);
    assertThatThrownBy(
            () -> controller.document(principal(ME, TENANT), "note:mine", "ou_someone_else"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("403");
  }

  @Test
  @DisplayName("moves with the caller's scope, and reports a document it could not reach as absent")
  void moveIsScoped() {
    final var recorder = new Recorder();
    final var controller = controller(recorder, Set.of(), null);

    assertThatThrownBy(
            () ->
                controller.move(
                    principal(ME, TENANT), new KnowledgeController.Move("note:theirs", "tenant")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
    assertThat(recorder.moved).containsExactly(Map.entry(scope(ME, TENANT), "note:theirs"));
  }

  @Test
  @DisplayName("a document id may be a file path, so it never travels in the URL path")
  void idsWithSlashesSurvive() {
    final var recorder = new Recorder();
    final var controller = controller(recorder, Set.of(), null);
    // What an uploaded file is indexed under. A route variable could not carry it: encoded, the
    // slashes are rejected before the method is reached; unencoded, they are more path segments.
    final var path = "/var/agent/ou_me/artifacts/report.pdf";

    controller.delete(principal(ME, TENANT), path);

    assertThat(recorder.deleted).containsExactly(Map.entry(scope(ME, TENANT), path));
  }

  @Test
  @DisplayName("a move with no scope stated is refused rather than read as the caller's own")
  void moveNeedsAScope() {
    final var controller = controller(new Recorder(), Set.of(), null);
    assertThatThrownBy(
            () ->
                controller.move(
                    principal(ME, TENANT), new KnowledgeController.Move("note:mine", "")))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  @DisplayName("an upload is indexed under the path it was stored at, inside the caller's own home")
  void uploadIsIndexedFromTheStoredFile(@TempDir final Path storage) {
    final var recorder = new Recorder();
    final var controller = controller(recorder, Set.of(), storage);
    final var file =
        new MockMultipartFile("files", "notes.md", "text/markdown", "hello".getBytes());

    final var result = controller.upload(principal(ME, TENANT), List.of(file), "own");

    assertThat(recorder.indexed).hasSize(1);
    final var source = recorder.indexed.get(0);
    final var stored = Path.of(source.docId());
    assertThat(stored).exists().hasContent("hello");
    // Under the caller's own home, which the principal chose and the request could not.
    assertThat(stored.startsWith(storage.resolve(ME))).isTrue();
    // The path is the id as well as the origin: it is what IndexKnowledge is told to use for a
    // file in a workspace, so a later re-index of the same file replaces this document rather
    // than storing a second copy of it.
    assertThat(source.source()).isEqualTo(source.docId());
    assertThat(source.title()).isEqualTo("notes.md");
    assertThat(source.scope()).isEqualTo(scope(ME, TENANT));
    assertThat(source.target()).isEqualTo(KnowledgeScope.Target.OWN);
    assertThat(result).containsKey("documents");
  }

  @Test
  @DisplayName("a file shared with the company is stored where the company can read it")
  void tenantUploadsGoToTheTenantHome(@TempDir final Path storage) {
    final var recorder = new Recorder();
    final var controller = controller(recorder, Set.of(), storage);
    final var file = new MockMultipartFile("files", "policy.md", "text/markdown", "x".getBytes());

    controller.upload(principal(ME, TENANT), List.of(file), "tenant");

    // Not the uploader's own home: the path is what the document cites and what another member's
    // agent would open, and one inside a private home is a citation nobody else can follow.
    final var stored = Path.of(recorder.indexed.get(0).docId());
    assertThat(stored.startsWith(storage.resolve("tenant").resolve(TENANT))).isTrue();
    assertThat(stored.startsWith(storage.resolve(ME))).isFalse();
  }

  @Test
  @DisplayName("a note is stored under an id of its own, so two notes never overwrite each other")
  void notesGetTheirOwnId() {
    final var recorder = new Recorder();
    final var controller = controller(recorder, Set.of(), null);

    controller.note(
        principal(ME, TENANT), new KnowledgeController.Note("A", "remember this", "tenant"));
    controller.note(principal(ME, TENANT), new KnowledgeController.Note("A", "and this", "tenant"));

    assertThat(recorder.indexed).hasSize(2);
    assertThat(recorder.indexed.get(0).docId()).isNotEqualTo(recorder.indexed.get(1).docId());
    assertThat(recorder.indexed.get(0).target()).isEqualTo(KnowledgeScope.Target.TENANT);
  }

  @Test
  @DisplayName("an empty note is refused, so the knowledge base does not fill with titles")
  void emptyNotesAreRefused() {
    final var controller = controller(new Recorder(), Set.of(), null);
    assertThatThrownBy(
            () ->
                controller.note(
                    principal(ME, TENANT), new KnowledgeController.Note("A", " ", "own")))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () ->
                controller.note(
                    principal(ME, TENANT), new KnowledgeController.Note(" ", "text", "own")))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  @DisplayName("every endpoint is absent where the deployment has no knowledge base")
  void withoutAKnowledgeBase() {
    final var controller =
        new KnowledgeController(provider(null), admins(Set.of()), null, messages(), properties());
    assertThatThrownBy(() -> controller.delete(principal(ME, TENANT), "anything"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
  }

  private static KnowledgeScope scope(final String owner, final String tenant) {
    return new KnowledgeScope(owner, "", tenant);
  }

  private KnowledgeController controller(
      final Recorder recorder, final Set<String> adminIds, final Path storage) {
    return new KnowledgeController(
        provider(recorder), admins(adminIds), workspaces(storage), messages(), properties());
  }

  private static UserWorkspaceFactory workspaces(final Path storage) {
    if (storage == null) {
      return null; // only the upload path resolves a home
    }
    return new UserWorkspaceFactory(
        new StorageProperties() {
          @Override
          public String getLocation() {
            return storage.toString();
          }

          @Override
          public String getWorkspaceLocation() {
            return null;
          }

          @Override
          public String getBaseUrl() {
            return "";
          }

          @Override
          public String getCdnUrl() {
            return "";
          }

          @Override
          public boolean isAutoUnzip() {
            return false;
          }
        });
  }

  private static WebMessages messages() {
    return new WebMessages(new WebProperties(null, null, null, Locale.ENGLISH, null, null));
  }

  private static SpringAgentProperties properties() {
    return new SpringAgentProperties(null, null, Locale.ENGLISH, null, null);
  }

  private static Admins admins(final Set<String> ids) {
    return new Admins(
        new SpringAgentProperties(
            null,
            new SpringAgentProperties.Ai(ids, Map.of(), null, null, null, null, null, null),
            Locale.ENGLISH,
            null,
            null));
  }

  private static OAuth2User principal(final String id, final String tenant) {
    return new DefaultOAuth2User(
        List.of(), Map.of("open_id", id, "name", "Me", "tenant_key", tenant), "open_id");
  }

  private static WebUser user(final String id, final String tenant) {
    return WebUser.of(principal(id, tenant));
  }

  private static ObjectProvider<KnowledgeBase> provider(final KnowledgeBase knowledgeBase) {
    return new ObjectProvider<>() {
      @Override
      public KnowledgeBase getObject() {
        return knowledgeBase;
      }

      @Override
      public KnowledgeBase getObject(final Object... args) {
        return knowledgeBase;
      }

      @Override
      public KnowledgeBase getIfAvailable() {
        return knowledgeBase;
      }

      @Override
      public KnowledgeBase getIfUnique() {
        return knowledgeBase;
      }
    };
  }

  /** A knowledge base that stores nothing and remembers who was asked what. */
  private static final class Recorder implements KnowledgeBase {

    private final List<KnowledgeSource> indexed = new ArrayList<>();
    private final List<Map.Entry<KnowledgeScope, String>> deleted = new ArrayList<>();
    private final List<Map.Entry<KnowledgeScope, String>> moved = new ArrayList<>();
    private final List<Map.Entry<KnowledgeScope, String>> readDocuments = new ArrayList<>();

    @Override
    public String index(final KnowledgeSource source) {
      indexed.add(source);
      return source.docId();
    }

    @Override
    public KnowledgePage list(final KnowledgeScope scope, final int offset, final int limit) {
      return KnowledgePage.EMPTY;
    }

    @Override
    public Optional<KnowledgeDocument> read(final KnowledgeScope scope, final String docId) {
      readDocuments.add(Map.entry(scope, docId));
      return docId.startsWith("note:mine")
          ? Optional.of(
              new KnowledgeDocument(
                  new KnowledgeEntry(docId, "Mine", "", 2, null, KnowledgeScope.Target.OWN),
                  "what is stored"))
          : Optional.empty();
    }

    @Override
    public void delete(final KnowledgeScope scope, final String docId) {
      deleted.add(Map.entry(scope, docId));
    }

    @Override
    public Optional<KnowledgeEntry> move(
        final KnowledgeScope scope, final String docId, final KnowledgeScope.Target target) {
      moved.add(Map.entry(scope, docId));
      return Optional.empty();
    }

    @Override
    public DocumentRetriever retrieverFor(
        final KnowledgeScope scope, final Filter.Expression extra) {
      return query -> List.of();
    }

    @Override
    public List<Document> search(final KnowledgeScope scope, final String query, final int topK) {
      return List.of();
    }
  }
}
