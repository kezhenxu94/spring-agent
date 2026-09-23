package me.kezhenxu94.springagent.integration.websocket.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.config.TenantWrites;
import me.kezhenxu94.springagent.core.skills.SkillFiles;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.server.ResponseStatusException;

/**
 * Whose skills a browser request reads and writes.
 *
 * <p>The identity is the principal's and the request cannot state it — there is no {@code owner}
 * parameter here at all, so the only thing a request chooses is which of the caller's own two
 * stores it means. A skill is instructions the agent will load and act on, which is why this is
 * stricter than the knowledge base rather than the same: reading somebody else's notes is a
 * moderation question, and writing into their instructions is not a question anybody should be able
 * to ask.
 */
class SkillControllerTest {

  private static final String ME = "ou_me";
  private static final String TENANT = "tenant_a";

  @TempDir Path storage;

  SkillController controller;

  @BeforeEach
  void setUp() throws Exception {
    // Open by default in this suite, so that every test below is about paths, scopes and files
    // rather than about who may write the company's — the closed default has a section of its own.
    controller = controller(tenantWrites(true));
    Files.createDirectories(storage.resolve(ME + "/skills"));
    Files.createDirectories(storage.resolve("tenant/" + TENANT + "/skills"));
  }

  private SkillController controller(final TenantWrites tenantWrites) {
    return new SkillController(new SkillFiles(), workspaces(storage), tenantWrites, messages());
  }

  /** {@code app.ai.non-admin-tenant-writes}, with nobody listed as an admin. */
  private static TenantWrites tenantWrites(final boolean open) {
    final var properties =
        new SpringAgentProperties(
            new SpringAgentProperties.Ai(
                Set.of(), open, Map.of(), null, null, null, null, null, null, null),
            Locale.ENGLISH,
            null,
            null);
    return new TenantWrites(properties, new Admins(properties));
  }

  private Path skill(final String home, final String name, final String description)
      throws Exception {
    final var dir = storage.resolve(home + "/skills/" + name);
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve("SKILL.md"),
        "---\nname: %s\ndescription: %s\n---\nSteps.".formatted(name, description));
    return dir;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> skillsIn(final Map<String, Object> body) {
    return (List<Map<String, Object>>) body.get("skills");
  }

  // ─────────────────────────────────────── which store ───────────────────────────────────────

  @Test
  @DisplayName("a request that names no scope means the caller's own skills")
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
  @DisplayName("a sign-in carrying no company cannot ask for company skills")
  void refusesTenantWithoutATenant() {
    assertThatThrownBy(() -> controller.targetFor("tenant", user(ME, "")))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  @DisplayName("there is no group on this surface, so a group scope is refused rather than blank")
  void refusesGroup() {
    // Left to fall through, a skill would be written into groups//skills — a folder no run reaches,
    // by a request that reported success.
    assertThatThrownBy(() -> controller.targetFor("group", user(ME, TENANT)))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  @DisplayName("a scope that is not a scope is refused rather than read as the default")
  void refusesAnUnknownScope() {
    assertThatThrownBy(() -> controller.targetFor("everybody", user(ME, TENANT)))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  @DisplayName("each scope is one directory, so a path can never resolve into the other")
  void eachScopeIsASingleRoot() {
    // The guard asks the home it is given whether a path is inside it. A composite home spans both
    // stores, so it would accept a scope=own request that resolved its way into the company's —
    // which is why homeFor builds forOwner or forTenant and never forRequest.
    assertThat(controller.homeFor(ScopeTarget.OWN, user(ME, TENANT)).roots()).hasSize(1);
    assertThat(controller.homeFor(ScopeTarget.TENANT, user(ME, TENANT)).roots()).hasSize(1);
    assertThat(controller.homeFor(ScopeTarget.OWN, user(ME, TENANT)).roots().get(0))
        .isEqualTo(storage.resolve(ME));
    assertThat(controller.homeFor(ScopeTarget.TENANT, user(ME, TENANT)).roots().get(0))
        .isEqualTo(storage.resolve("tenant").resolve(TENANT));
  }

  // ─────────────────────────────────────── listing ───────────────────────────────────────

  @Test
  @DisplayName("each scope lists its own, and one person's skills are never another's")
  void listsOneStoreAtATime() throws Exception {
    skill(ME, "mine", "personal");
    skill("tenant/" + TENANT, "ours", "shared");
    skill("ou_somebody_else", "theirs", "not yours");

    assertThat(skillsIn(controller.list(principal(ME, TENANT), null)))
        .singleElement()
        .satisfies(
            row -> assertThat(row).containsEntry("name", "mine").containsEntry("scope", "own"));
    assertThat(skillsIn(controller.list(principal(ME, TENANT), "tenant")))
        .singleElement()
        .satisfies(
            row -> assertThat(row).containsEntry("name", "ours").containsEntry("scope", "tenant"));
  }

  @Test
  @DisplayName("a company skill the caller also has privately is marked as shadowed")
  void marksShadowedCompanySkills() throws Exception {
    // SkillsTool keeps the nearest of a duplicate name, so this company skill is never offered to
    // the model. Nothing else says so, and a skill that quietly does nothing is worse than none.
    skill(ME, "greeting", "mine");
    skill("tenant/" + TENANT, "greeting", "theirs");
    skill("tenant/" + TENANT, "only-theirs", "theirs");

    assertThat(skillsIn(controller.list(principal(ME, TENANT), "tenant")))
        .anySatisfy(
            row ->
                assertThat(row).containsEntry("name", "greeting").containsEntry("shadowed", true))
        .anySatisfy(
            row ->
                assertThat(row)
                    .containsEntry("name", "only-theirs")
                    .containsEntry("shadowed", false));
  }

  @Test
  @DisplayName("a skill in the caller's own store is never shadowed by anything")
  void ownIsNeverShadowed() throws Exception {
    skill(ME, "greeting", "mine");
    skill("tenant/" + TENANT, "greeting", "theirs");

    assertThat(skillsIn(controller.list(principal(ME, TENANT), null)))
        .singleElement()
        .satisfies(row -> assertThat(row).containsEntry("shadowed", false));
  }

  @Test
  @DisplayName("no absolute path reaches the browser")
  void neverLeaksAPath() throws Exception {
    skill(ME, "greeting", "mine");

    assertThat(skillsIn(controller.list(principal(ME, TENANT), null)))
        .singleElement()
        .satisfies(row -> assertThat(row).doesNotContainKey("directory"));
    assertThat(controller.tree(principal(ME, TENANT), null, "greeting"))
        .doesNotContainKey("directory");
  }

  // ─────────────────────────────────────── one skill ───────────────────────────────────────

  @Test
  @DisplayName("a skill that is not there is 404 and not an empty tree")
  void missingSkillIsNotFound() {
    assertThatThrownBy(() -> controller.tree(principal(ME, TENANT), null, "nothing"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("a file path climbing out of a skill is refused, not silently confined")
  void refusesTraversal() throws Exception {
    skill(ME, "greeting", "mine");
    final var memories = storage.resolve(ME + "/memories");
    Files.createDirectories(memories);
    Files.writeString(memories.resolve("note.md"), "private");

    for (final var attempt :
        List.of("../../memories/note.md", "../../../escape.md", "..\\..\\memories\\note.md")) {
      assertThatThrownBy(() -> controller.file(principal(ME, TENANT), null, "greeting", attempt))
          .as("%s", attempt)
          .isInstanceOf(ResponseStatusException.class)
          .extracting(e -> ((ResponseStatusException) e).getStatusCode())
          .isEqualTo(HttpStatus.FORBIDDEN);
    }
  }

  @Test
  @DisplayName("a file that is not text says so instead of arriving as mojibake")
  void marksBinaryFiles() throws Exception {
    final var dir = skill(ME, "greeting", "mine");
    Files.write(dir.resolve("logo.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1});

    final var body = controller.file(principal(ME, TENANT), null, "greeting", "logo.png");
    assertThat(body).containsEntry("binary", true).doesNotContainKey("text");
  }

  // ─────────────────────────────────────── writing ───────────────────────────────────────

  @Test
  @DisplayName("a new skill gets the SKILL.md that makes it one")
  void createsAManifest() {
    controller.create(
        principal(ME, TENANT), new SkillController.NewSkill("pdf", "Fills forms.", null));

    assertThat(storage.resolve(ME + "/skills/pdf/SKILL.md"))
        .content()
        .contains("name: pdf")
        .contains("description: Fills forms.");
    assertThat(skillsIn(controller.list(principal(ME, TENANT), null))).hasSize(1);
  }

  @Test
  @DisplayName("a second skill of the same name is refused rather than overwriting the first")
  void refusesADuplicateName() throws Exception {
    skill(ME, "pdf", "mine");

    assertThatThrownBy(
            () ->
                controller.create(
                    principal(ME, TENANT), new SkillController.NewSkill("pdf", "again", null)))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(storage.resolve(ME + "/skills/pdf/SKILL.md"))
        .content()
        .contains("description: mine");
  }

  @Test
  @DisplayName("a skill name carrying a path is refused before anything is created")
  void refusesANameWithAPath() {
    assertThatThrownBy(
            () ->
                controller.create(
                    principal(ME, TENANT), new SkillController.NewSkill("../../evil", "no", null)))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(storage.resolve("evil")).doesNotExist();
  }

  @Test
  @DisplayName("with non-admin tenant writes on, anybody in the company may write a company skill")
  void anyoneMayWriteCompanySkills() throws Exception {
    skill("tenant/" + TENANT, "ours", "shared");

    controller.save(
        principal(ME, TENANT),
        new SkillController.Save("ours", "references/notes.md", "added", "tenant"));

    assertThat(storage.resolve("tenant/" + TENANT + "/skills/ours/references/notes.md"))
        .hasContent("added");
  }

  @Nested
  @DisplayName("by default the company's skills are an administrator's to change")
  class CompanySkillsClosed {

    private SkillController closed;

    @BeforeEach
    void closed() {
      closed = controller(tenantWrites(false));
    }

    @Test
    @DisplayName("a member may still read, list and export them")
    void readsAreUntouched() throws Exception {
      skill("tenant/" + TENANT, "ours", "shared");

      assertThat(skillsIn(closed.list(principal(ME, TENANT), "tenant"))).hasSize(1);
      assertThat(closed.tree(principal(ME, TENANT), "tenant", "ours")).containsKey("entries");
    }

    @Test
    @DisplayName("every write into them is refused, and nothing is left behind")
    void writesAreRefused() throws Exception {
      skill("tenant/" + TENANT, "ours", "shared");

      assertThatThrownBy(
              () ->
                  closed.save(
                      principal(ME, TENANT),
                      new SkillController.Save("ours", "notes.md", "added", "tenant")))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("403");
      assertThatThrownBy(
              () ->
                  closed.create(
                      principal(ME, TENANT),
                      new SkillController.NewSkill("theirs", "no", "tenant")))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("403");
      assertThatThrownBy(() -> closed.delete(principal(ME, TENANT), "tenant", "ours"))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("403");
      assertThatThrownBy(
              () -> closed.deleteFile(principal(ME, TENANT), "tenant", "ours", "SKILL.md"))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("403");

      assertThat(storage.resolve("tenant/" + TENANT + "/skills/ours/SKILL.md")).exists();
      assertThat(storage.resolve("tenant/" + TENANT + "/skills/ours/notes.md")).doesNotExist();
      assertThat(storage.resolve("tenant/" + TENANT + "/skills/theirs")).doesNotExist();
    }

    @Test
    @DisplayName("their own skills are still theirs")
    void ownIsUntouched() throws Exception {
      skill(ME, "greeting", "mine");

      closed.save(
          principal(ME, TENANT), new SkillController.Save("greeting", "notes.md", "added", null));

      assertThat(storage.resolve(ME + "/skills/greeting/notes.md")).hasContent("added");
    }
  }

  @Test
  @DisplayName("an uploaded file keeps its bytes, whatever they are")
  void uploadsAreNotRoundTrippedThroughText() throws Exception {
    skill(ME, "greeting", "mine");
    final var bytes = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, (byte) 0xFF, (byte) 0xFE};

    controller.upload(
        principal(ME, TENANT),
        null,
        "greeting",
        "assets",
        List.of(new MockMultipartFile("files", "logo.png", "image/png", bytes)));

    assertThat(Files.readAllBytes(storage.resolve(ME + "/skills/greeting/assets/logo.png")))
        .isEqualTo(bytes);
  }

  @Test
  @DisplayName("an upload named with a path lands under its own last segment and nowhere else")
  void uploadNamesAreReducedToABasename() throws Exception {
    skill(ME, "greeting", "mine");

    controller.upload(
        principal(ME, TENANT),
        null,
        "greeting",
        null,
        List.of(
            new MockMultipartFile(
                "files", "../../evil.md", "text/markdown", "no".getBytes(StandardCharsets.UTF_8))));

    assertThat(storage.resolve(ME + "/skills/greeting/evil.md")).exists();
    assertThat(storage.resolve(ME + "/evil.md")).doesNotExist();
  }

  @Test
  @DisplayName("a file too large to be shown again is too large to save")
  void refusesToSaveWhatItCouldNotShow() throws Exception {
    skill(ME, "greeting", "mine");
    final var huge = "a".repeat((int) SkillFiles.MAX_TEXT_BYTES + 1);

    assertThatThrownBy(
            () ->
                controller.save(
                    principal(ME, TENANT),
                    new SkillController.Save("greeting", "notes.md", huge, null)))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
  }

  @Test
  @DisplayName("a name too long to become a tool name is refused at creation")
  void refusesANameNoToolCouldCarry() {
    // SkillsTool would skip it with a log line nobody reads, and the skill would simply never be
    // offered to the model — a page that created one would be creating something that does nothing.
    assertThatThrownBy(
            () ->
                controller.create(
                    principal(ME, TENANT),
                    new SkillController.NewSkill(
                        "x".repeat(SkillFiles.MAX_NAME_LENGTH + 1), "no", null)))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  // ─────────────────────────────────────── importing a zip ───────────────────────────────────────

  private static byte[] zipOf(final String... pathsAndText) throws Exception {
    final var out = new java.io.ByteArrayOutputStream();
    try (var zip = new java.util.zip.ZipOutputStream(out)) {
      for (var i = 0; i < pathsAndText.length; i += 2) {
        zip.putNextEntry(new java.util.zip.ZipEntry(pathsAndText[i]));
        zip.write(pathsAndText[i + 1].getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return out.toByteArray();
  }

  private static MockMultipartFile archive(final byte[] bytes) {
    return new MockMultipartFile("file", "skill.zip", "application/zip", bytes);
  }

  @Test
  @DisplayName("a zip becomes a skill, under the name the request gave rather than the archive's")
  void importsAZip() throws Exception {
    controller.importZip(
        principal(ME, TENANT),
        null,
        "triage",
        archive(
            zipOf(
                "bundle-main/SKILL.md", "---\nname: triage\ndescription: from a zip\n---\nSteps.",
                "bundle-main/references/a.md", "kept")));

    // The archive's own folder is stripped, so SKILL.md lands where it makes the folder a skill.
    assertThat(storage.resolve(ME + "/skills/triage/SKILL.md")).exists();
    assertThat(storage.resolve(ME + "/skills/triage/references/a.md")).hasContent("kept");
    assertThat(storage.resolve(ME + "/skills/bundle-main")).doesNotExist();
    assertThat(skillsIn(controller.list(principal(ME, TENANT), null)))
        .singleElement()
        .satisfies(row -> assertThat(row).containsEntry("name", "triage"));
  }

  @Test
  @DisplayName("an archive whose entries climb out is refused, and nothing at all is written")
  void refusesZipSlip() throws Exception {
    final var bytes =
        zipOf(
            "SKILL.md", "---\nname: x\n---\n",
            "../../../escaped.md", "no");

    assertThatThrownBy(
            () -> controller.importZip(principal(ME, TENANT), null, "triage", archive(bytes)))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(storage.resolve("escaped.md")).doesNotExist();
    assertThat(storage.resolve(ME + "/skills/triage")).doesNotExist();
  }

  @Test
  @DisplayName("an archive with no SKILL.md is refused before a single file is written")
  void refusesAnArchiveThatIsNotASkill() throws Exception {
    // Unpacked, it would be a folder nothing treats as a skill — absent from the list, unreachable
    // by name, and therefore impossible to delete from this page.
    final var bytes = zipOf("README.md", "hello", "src/main.py", "print()");

    assertThatThrownBy(
            () -> controller.importZip(principal(ME, TENANT), null, "triage", archive(bytes)))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(storage.resolve(ME + "/skills/triage")).doesNotExist();
  }

  @Test
  @DisplayName("importing over a skill that is already there is refused rather than merged")
  void refusesToImportOverAnExistingSkill() throws Exception {
    skill(ME, "triage", "mine");

    assertThatThrownBy(
            () ->
                controller.importZip(
                    principal(ME, TENANT),
                    null,
                    "triage",
                    archive(zipOf("SKILL.md", "---\nname: triage\n---\ntheirs"))))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(storage.resolve(ME + "/skills/triage/SKILL.md")).content().contains("mine");
  }

  @Test
  @DisplayName("a zip can be imported into the company's skills too")
  void importsIntoTheCompany() throws Exception {
    controller.importZip(
        principal(ME, TENANT),
        "tenant",
        "triage",
        archive(zipOf("SKILL.md", "---\nname: triage\n---\nSteps.")));

    assertThat(storage.resolve("tenant/" + TENANT + "/skills/triage/SKILL.md")).exists();
    assertThat(storage.resolve(ME + "/skills/triage")).doesNotExist();
  }

  @Test
  @DisplayName("something that is not a zip is refused")
  void refusesWhatIsNotAZip() {
    assertThatThrownBy(
            () ->
                controller.importZip(
                    principal(ME, TENANT),
                    null,
                    "triage",
                    archive("not a zip".getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  // ─────────────────────────────────────── downloading ───────────────────────────────────────

  private static byte[] drain(
      final org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody body)
      throws Exception {
    final var out = new java.io.ByteArrayOutputStream();
    body.writeTo(out);
    return out.toByteArray();
  }

  @Test
  @DisplayName("a skill downloads as a zip that imports back as the same skill")
  void exportsAZipThatImportsBack() throws Exception {
    final var dir = skill(ME, "triage", "from here");
    Files.writeString(dir.resolve("notes.md"), "kept");

    final var response = controller.export(principal(ME, TENANT), null, "triage");
    final var bytes = drain(response.getBody());

    // Straight back in under another name, which is the whole point of the pair existing.
    controller.importZip(principal(ME, TENANT), null, "triage-copy", archive(bytes));
    assertThat(storage.resolve(ME + "/skills/triage-copy/notes.md")).hasContent("kept");
    assertThat(storage.resolve(ME + "/skills/triage-copy/SKILL.md"))
        .content()
        .contains("from here");
  }

  @Test
  @DisplayName("the download is named after the skill, and says it is an attachment")
  void namesTheDownload() throws Exception {
    skill(ME, "triage", "mine");

    final var response = controller.export(principal(ME, TENANT), null, "triage");

    assertThat(response.getHeaders().getFirst("Content-Disposition"))
        .isEqualTo("attachment; filename=\"triage.zip\"; filename*=UTF-8''triage.zip");
    assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
  }

  @Test
  @DisplayName("a name with characters a header cannot carry is spelt both ways")
  void namesANonAsciiDownload() throws Exception {
    // A skill name is one path segment but nothing makes it ASCII. A raw byte here renders as
    // mojibake, and a quote would end the header early.
    skill(ME, "\u6280\u80fd", "mine");

    final var response = controller.export(principal(ME, TENANT), null, "\u6280\u80fd");

    assertThat(response.getHeaders().getFirst("Content-Disposition"))
        .isEqualTo("attachment; filename=\"__.zip\"; filename*=UTF-8''%E6%8A%80%E8%83%BD.zip");
  }

  @Test
  @DisplayName("a skill that is not there cannot be downloaded")
  void refusesToExportWhatIsNotThere() {
    assertThatThrownBy(() -> controller.export(principal(ME, TENANT), null, "nothing"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("a download reaches only the store the request named")
  void exportsFromOneStoreOnly() throws Exception {
    skill("tenant/" + TENANT, "shared", "theirs");

    // It is in the company's store, so asking for it as one of your own is a 404 and not a copy.
    assertThatThrownBy(() -> controller.export(principal(ME, TENANT), null, "shared"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(drain(controller.export(principal(ME, TENANT), "tenant", "shared").getBody()))
        .isNotEmpty();
  }

  // ─────────────────────────────────────── deleting ───────────────────────────────────────

  @Test
  @DisplayName("deleting a skill takes the folder, and leaves the other store alone")
  void deletesOneSkill() throws Exception {
    skill(ME, "greeting", "mine");
    skill("tenant/" + TENANT, "greeting", "theirs");

    controller.delete(principal(ME, TENANT), null, "greeting");

    assertThat(storage.resolve(ME + "/skills/greeting")).doesNotExist();
    assertThat(storage.resolve("tenant/" + TENANT + "/skills/greeting")).exists();
  }

  @Test
  @DisplayName("deleting one file leaves the skill")
  void deletesOneFile() throws Exception {
    final var dir = skill(ME, "greeting", "mine");
    Files.writeString(dir.resolve("notes.md"), "x");

    controller.deleteFile(principal(ME, TENANT), null, "greeting", "notes.md");

    assertThat(dir.resolve("notes.md")).doesNotExist();
    assertThat(dir.resolve("SKILL.md")).exists();
  }

  // ─────────────────────────────────────── the plumbing ───────────────────────────────────────

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
