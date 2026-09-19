package me.kezhenxu94.springagent.integration.websocket.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.config.TenantWrites;
import me.kezhenxu94.springagent.core.memory.MemoryEntry;
import me.kezhenxu94.springagent.core.memory.MemoryFile;
import me.kezhenxu94.springagent.core.memory.MemoryStore;
import me.kezhenxu94.springagent.core.storage.StorageProperties;
import me.kezhenxu94.springagent.core.tools.ScopeTarget;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import me.kezhenxu94.springagent.integration.websocket.config.WebMessages;
import me.kezhenxu94.springagent.integration.websocket.config.WebProperties;
import me.kezhenxu94.springagent.integration.websocket.security.WebUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.server.ResponseStatusException;

/**
 * Whose memories a browser request reads and writes.
 *
 * <p>The identity is the principal's and the request cannot state it, exactly as with skills: there
 * is no {@code owner} parameter, so the only thing a request chooses is which of the caller's own
 * two stores it means. What a memory says is a conclusion the agent drew about somebody, which is
 * why not even an administrator reaches another person's through here.
 */
class MemoryControllerTest {

  private static final String ME = "ou_me";
  private static final String TENANT = "tenant_a";

  @TempDir Path storage;

  MemoryController controller;

  @BeforeEach
  void setUp() throws Exception {
    // Open by default in this suite, so that every test below is about paths, scopes and files
    // rather than about who may write the company's — the closed default has a section of its own.
    controller = controller(tenantWrites(true));
    Files.createDirectories(storage.resolve(ME + "/memories"));
    Files.createDirectories(storage.resolve("tenant/" + TENANT + "/memories"));
  }

  // ─────────────────────────────────────── which store ───────────────────────────────────────

  @Test
  @DisplayName("a request that names no scope means the caller's own memories")
  void ownByDefault() {
    assertThat(controller.targetFor(null, user(ME, TENANT))).isEqualTo(ScopeTarget.OWN);
    assertThat(controller.targetFor("  ", user(ME, TENANT))).isEqualTo(ScopeTarget.OWN);
  }

  @Test
  @DisplayName("company is spelt either way, because the page says one and the tools say the other")
  void companyAndTenantAreOneScope() {
    assertThat(controller.targetFor("tenant", user(ME, TENANT))).isEqualTo(ScopeTarget.TENANT);
    assertThat(controller.targetFor("company", user(ME, TENANT))).isEqualTo(ScopeTarget.TENANT);
  }

  @Test
  @DisplayName("there is no group on this surface, so a group scope is refused rather than blank")
  void refusesGroup() {
    assertThatThrownBy(() -> controller.targetFor("group", user(ME, TENANT)))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  @DisplayName("a sign-in carrying no company cannot ask for company memories")
  void refusesTenantWithoutATenant() {
    assertThatThrownBy(() -> controller.targetFor("tenant", user(ME, "")))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  @DisplayName("naming a scope resolves to that store's memories folder and nothing else")
  void rootPerScope() {
    assertThat(controller.rootFor(ScopeTarget.OWN, user(ME, TENANT)))
        .isEqualTo(storage.resolve(ME + "/memories"));
    assertThat(controller.rootFor(ScopeTarget.TENANT, user(ME, TENANT)))
        .isEqualTo(storage.resolve("tenant/" + TENANT + "/memories"));
  }

  @Test
  @DisplayName("listing a company nobody has written to creates nothing in shared storage")
  void listingCreatesNothing() throws Exception {
    final var untouched = storage.resolve("tenant/other/memories");

    assertThat(memoriesIn(controller.list(principal(ME, "other"), "tenant"))).isEmpty();
    assertThat(untouched).doesNotExist();
  }

  // ─────────────────────────────────────── what a row says ────────────────────────────────────

  @Test
  @DisplayName("a memory's front matter is read onto its row, and the index is marked as one")
  void readsFrontMatter() throws Exception {
    memory(ME, "MEMORY.md", "- [Compactness](web-ui.md) — keep the composer small");
    memory(
        ME,
        "web-ui.md",
        """
        ---
        name: web-ui-compactness-wins
        description: do not fix a cosmetic problem by making the composer taller
        type: feedback
        ---
        The composer is already the right height.
        """);

    final var rows = memoriesIn(controller.list(principal(ME, TENANT), null));

    // The index first whatever it sorts next to: it is the list of memories rather than one of
    // them, and a reader should meet it before the files it indexes.
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).path()).isEqualTo("MEMORY.md");
    assertThat(rows.get(0).index()).isTrue();

    final var memory = rows.get(1);
    assertThat(memory.index()).isFalse();
    assertThat(memory.name()).isEqualTo("web-ui-compactness-wins");
    assertThat(memory.type()).isEqualTo("feedback");
    assertThat(memory.description()).startsWith("do not fix a cosmetic problem");
  }

  @Test
  @DisplayName("a memory with no front matter still lists, because its path is its identity")
  void listsWithoutFrontMatter() throws Exception {
    memory(ME, "scratch.md", "just a note somebody wrote");

    final var rows = memoriesIn(controller.list(principal(ME, TENANT), null));

    assertThat(rows).singleElement().extracting(MemoryEntry::path).isEqualTo("scratch.md");
    assertThat(rows.get(0).name()).isEmpty();
    assertThat(rows.get(0).type()).isEmpty();
  }

  @Test
  @DisplayName("a memory in a folder is listed and addressed by the path it is at")
  void listsNested() throws Exception {
    memory(ME, "projects/spring-agent.md", "---\nname: sa\n---\nnotes");

    final var rows = memoriesIn(controller.list(principal(ME, TENANT), null));

    assertThat(rows)
        .singleElement()
        .extracting(MemoryEntry::path)
        .isEqualTo("projects/spring-agent.md");
    assertThat(
            file(controller.file(principal(ME, TENANT), null, "projects/spring-agent.md")).text())
        .contains("notes");
  }

  // ─────────────────────────────────────── reading and writing ────────────────────────────────

  @Test
  @DisplayName("saving a memory that is not there writes it, and saving again replaces it")
  void savesAndReplaces() throws Exception {
    controller.save(principal(ME, TENANT), new MemoryController.Save(null, "note.md", "first"));
    assertThat(storage.resolve(ME + "/memories/note.md")).hasContent("first");

    controller.save(principal(ME, TENANT), new MemoryController.Save(null, "note.md", "second"));
    assertThat(storage.resolve(ME + "/memories/note.md")).hasContent("second");
  }

  @Test
  @DisplayName("a memory in a folder that does not exist yet brings the folder with it")
  void savesIntoANewFolder() throws Exception {
    controller.save(principal(ME, TENANT), new MemoryController.Save(null, "projects/new.md", "x"));

    assertThat(storage.resolve(ME + "/memories/projects/new.md")).hasContent("x");
  }

  @Test
  @DisplayName("a save with no path is refused rather than landing on the root")
  void refusesABlankPath() {
    assertThatThrownBy(
            () ->
                controller.save(principal(ME, TENANT), new MemoryController.Save(null, "  ", "x")))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  @DisplayName("reading a memory that is not there is a 404, not a refusal")
  void missingIsNotFound() {
    assertThatThrownBy(() -> controller.file(principal(ME, TENANT), null, "gone.md"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("deleting a memory removes the file and nothing else")
  void deletes() throws Exception {
    memory(ME, "note.md", "x");
    memory(ME, "MEMORY.md", "- [note](note.md)");

    controller.delete(principal(ME, TENANT), null, "note.md");

    assertThat(storage.resolve(ME + "/memories/note.md")).doesNotExist();
    assertThat(storage.resolve(ME + "/memories/MEMORY.md")).exists();
  }

  @Test
  @DisplayName("deleting one that is not there is a 404 rather than a silent success")
  void deletingMissingIsNotFound() {
    assertThatThrownBy(() -> controller.delete(principal(ME, TENANT), null, "gone.md"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  // ─────────────────────────────────────── staying inside ─────────────────────────────────────

  @Test
  @DisplayName("a path climbing out of the memories folder is refused, read or written")
  void refusesEscapes() {
    assertThatThrownBy(() -> controller.file(principal(ME, TENANT), null, "../skills/x"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThatThrownBy(
            () ->
                controller.save(
                    principal(ME, TENANT), new MemoryController.Save(null, "../owned.md", "x")))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  @DisplayName("an absolute path is refused, whatever it points at")
  void refusesAbsolutePaths() {
    assertThatThrownBy(
            () -> controller.file(principal(ME, TENANT), null, storage.resolve("x").toString()))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  @DisplayName("a link out of the memories folder is not followed")
  void refusesLinksOut() throws Exception {
    final var outside = storage.resolve("outside");
    Files.createDirectories(outside);
    Files.writeString(outside.resolve("secret.md"), "not yours");
    Files.createSymbolicLink(storage.resolve(ME + "/memories/away"), outside);

    assertThatThrownBy(() -> controller.file(principal(ME, TENANT), null, "away/secret.md"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  @DisplayName("one scope's request cannot reach another's store, even by climbing")
  void scopesAreSeparate() throws Exception {
    memory("tenant/" + TENANT, "company.md", "everyone reads this");

    assertThat(memoriesIn(controller.list(principal(ME, TENANT), null))).isEmpty();
    assertThat(memoriesIn(controller.list(principal(ME, TENANT), "tenant")))
        .singleElement()
        .extracting(MemoryEntry::path)
        .isEqualTo("company.md");
  }

  // ─────────────────────────────────── who may write the company's ────────────────────────────

  /**
   * {@code app.ai.non-admin-tenant-writes} on this surface.
   *
   * <p>The toggle is asked through {@code TenantWrites} and deliberately not through {@code
   * MemoryScopes.writable}, which additionally requires a group chat as a witness. A browser
   * session has no group, so that answer would make the company scope permanently read-only here
   * whatever a deployment configured — a control no setting could open. These are the tests that
   * would fail if somebody swapped the check for the other one.
   */
  @Nested
  @DisplayName("the company's memories")
  class CompanyMemories {

    @Test
    @DisplayName("are read by anybody whose sign-in carries one, open or not")
    void readableByEveryone() throws Exception {
      memory("tenant/" + TENANT, "shared.md", "what we all know");
      final var closed = controller(tenantWrites(false));

      assertThat(memoriesIn(closed.list(principal(ME, TENANT), "tenant")))
          .singleElement()
          .extracting(MemoryEntry::path)
          .isEqualTo("shared.md");
    }

    @Test
    @DisplayName("are read-only for a member where the deployment has not opened them")
    void closedByDefault() {
      final var closed = controller(tenantWrites(false));

      assertThatThrownBy(
              () ->
                  closed.save(
                      principal(ME, TENANT), new MemoryController.Save("tenant", "ours.md", "x")))
          .isInstanceOf(ResponseStatusException.class)
          .extracting(e -> ((ResponseStatusException) e).getStatusCode())
          // 403 and not 404: these are memories the caller reads and lists, and hiding them on a
          // write would be a refusal nothing on the page could explain.
          .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("are written by a member once the deployment opens them")
    void openByProperty() throws Exception {
      controller.save(
          principal(ME, TENANT), new MemoryController.Save("tenant", "ours.md", "agreed"));

      assertThat(storage.resolve("tenant/" + TENANT + "/memories/ours.md")).hasContent("agreed");
    }

    @Test
    @DisplayName("are written by an administrator whatever the deployment configured")
    void alwaysOpenToAdmins() throws Exception {
      final var closed = controller(tenantWrites(false, ME));

      closed.save(principal(ME, TENANT), new MemoryController.Save("tenant", "ours.md", "agreed"));

      assertThat(storage.resolve("tenant/" + TENANT + "/memories/ours.md")).hasContent("agreed");
    }

    @Test
    @DisplayName("cannot be deleted by a member where the deployment has not opened them")
    void deleteIsAWriteToo() throws Exception {
      memory("tenant/" + TENANT, "ours.md", "x");
      final var closed = controller(tenantWrites(false));

      assertThatThrownBy(() -> closed.delete(principal(ME, TENANT), "tenant", "ours.md"))
          .isInstanceOf(ResponseStatusException.class)
          .extracting(e -> ((ResponseStatusException) e).getStatusCode())
          .isEqualTo(HttpStatus.FORBIDDEN);
      assertThat(storage.resolve("tenant/" + TENANT + "/memories/ours.md")).exists();
    }
  }

  // ─────────────────────────────────────── the plumbing ───────────────────────────────────────

  private MemoryController controller(final TenantWrites tenantWrites) {
    return new MemoryController(new MemoryStore(), workspaces(storage), tenantWrites, messages());
  }

  private Path memory(final String home, final String path, final String text) throws Exception {
    final var file = storage.resolve(home + "/memories/" + path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
    return file;
  }

  @SuppressWarnings("unchecked")
  private static List<MemoryEntry> memoriesIn(final Map<String, Object> body) {
    return (List<MemoryEntry>) body.get("memories");
  }

  private static MemoryFile file(final Map<String, Object> body) {
    return (MemoryFile) body.get("file");
  }

  /** {@code app.ai.non-admin-tenant-writes}, with nobody listed as an admin unless named. */
  private static TenantWrites tenantWrites(final boolean open, final String... admins) {
    final var properties =
        new SpringAgentProperties(
            new SpringAgentProperties.Ai(
                Set.of(admins), open, Map.of(), null, null, null, null, null, null),
            Locale.ENGLISH,
            null,
            null);
    return new TenantWrites(properties, new Admins(properties));
  }

  private static UserWorkspaceFactory workspaces(final Path storage) {
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
    return new WebMessages(
        new WebProperties(null, null, null, Locale.ENGLISH, null, null, null, null, false, null));
  }

  private static OAuth2User principal(final String id, final String tenant) {
    return new DefaultOAuth2User(
        List.of(), Map.of("open_id", id, "name", "Me", "tenant_key", tenant), "open_id");
  }

  private static WebUser user(final String id, final String tenant) {
    return new WebUser(id, "Me", "", tenant);
  }
}
