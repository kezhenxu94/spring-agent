package me.kezhenxu94.springagent.integration.websocket.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.TenantWrites;
import me.kezhenxu94.springagent.core.skills.SkillAccessDenied;
import me.kezhenxu94.springagent.core.skills.SkillFiles;
import me.kezhenxu94.springagent.core.skills.SkillSummary;
import me.kezhenxu94.springagent.core.tools.HomeDir;
import me.kezhenxu94.springagent.core.tools.ScopeTarget;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import me.kezhenxu94.springagent.integration.websocket.config.WebMessages;
import me.kezhenxu94.springagent.integration.websocket.security.WebUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Somebody's skills, as folders and files, without going through the model to reach them.
 *
 * <p>The same argument {@code KnowledgeController} makes, about the other thing a user owns. A
 * skill is a folder of instructions the agent loads by name, and everything here is something
 * {@code SkillManagementTools} already does in a conversation — but asking in prose is a poor way
 * to read a folder. The model has to list, guess a path, read a file back and retell it, and any of
 * those can go wrong quietly. What is written here is what a run loads, and what a run wrote is
 * what this lists.
 *
 * <p><b>Whose skills is decided by the authenticated principal, never by the request.</b> {@code
 * scope} chooses between the caller's own home and their tenant's, and nothing else: there is no
 * {@code owner} parameter here, and no administrator can reach another person's skills through
 * this. A knowledge document is prose and an admin reading one is a moderation question; a skill is
 * instructions the agent will execute, and there is no view of somebody else's worth the door it
 * opens.
 *
 * <p><b>Who may write company skills is {@code app.ai.non-admin-tenant-writes}</b>, and by default
 * that is administrators only: a skill there is instructions every colleague's agent loads and acts
 * on. It is the same check {@code SkillManagementTools} makes, so asking the agent is not the way
 * round the page — which is the rule this side has to keep, because a stricter page over an open
 * tool would only mean the page is the slow way round.
 *
 * <p>Unlike the knowledge base there is nothing optional about any of this: skills are folders on
 * the filesystem core always has. Every endpoint therefore answers, and {@code /api/me} reports
 * only whether the caller's sign-in carries a company — which is what decides whether the page
 * offers the second scope at all.
 *
 * <p><b>Every id is a query parameter and never a path variable.</b> A file inside a skill is
 * addressed by a relative path with slashes in it, which encoded is rejected by the container and
 * unencoded reads as more of the route — the rule {@code KnowledgeController} states for document
 * ids, for the same reason.
 */
@Slf4j
@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillController {

  private final SkillFiles skills;
  private final UserWorkspaceFactory workspaces;

  /**
   * Whether this caller may change the company's skills, or only read them. Core's decision, asked
   * here so that the page and a run are refused in the same place — see {@code TenantWrites}.
   */
  private final TenantWrites tenantWrites;

  private final WebMessages messages;

  // ─────────────────────────────────────── reading ───────────────────────────────────────

  @GetMapping
  public Map<String, Object> list(
      @AuthenticationPrincipal final OAuth2User principal,
      @RequestParam(required = false) final String scope) {

    final var user = ChatController.user(principal);
    final var target = targetFor(scope, user);

    // The caller's own store is read whichever scope was asked for, because of `shadowed` below:
    // whether a company skill reaches the model is a fact about the caller's own directory, and a
    // list of the company's alone cannot see it. Read once and reused, rather than walked twice.
    final var mine = skills.list(homeFor(ScopeTarget.OWN, user));
    final var listed = target == ScopeTarget.OWN ? mine : company(user);
    final var own = names(mine);

    final var out = new ArrayList<Map<String, Object>>();
    for (final var skill : listed) {
      out.add(asJson(skill, target, target != ScopeTarget.OWN && own.contains(skill.name())));
    }
    return Map.of("skills", out);
  }

  @GetMapping("/tree")
  public Map<String, Object> tree(
      @AuthenticationPrincipal final OAuth2User principal,
      @RequestParam(required = false) final String scope,
      @RequestParam("skill") final String skill) {

    final var user = ChatController.user(principal);
    final var target = targetFor(scope, user);
    final var home = homeFor(target, user);

    final var detail =
        guarding(() -> skills.detail(home, skill))
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, messages.get("skill-not-found", skill)));

    final var out = new LinkedHashMap<String, Object>(asJson(detail.skill(), target, false));
    final var entries = new ArrayList<Map<String, Object>>();
    for (final var entry : detail.entries()) {
      entries.add(Map.of("path", entry.path(), "dir", entry.dir(), "size", entry.size()));
    }
    out.put("entries", entries);
    return out;
  }

  @GetMapping("/file")
  public Map<String, Object> file(
      @AuthenticationPrincipal final OAuth2User principal,
      @RequestParam(required = false) final String scope,
      @RequestParam("skill") final String skill,
      @RequestParam("path") final String path) {

    final var user = ChatController.user(principal);
    final var home = homeFor(targetFor(scope, user), user);

    final var file =
        guarding(() -> skills.read(home, skill, path))
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, messages.get("skill-file-not-found", path)));

    final var out = new LinkedHashMap<String, Object>();
    out.put("path", file.entry().path());
    out.put("size", file.entry().size());
    out.put("binary", file.binary());
    out.put("tooLarge", file.tooLarge());
    // Absent rather than null when there is none: the page tests for the text, and a key that is
    // there but empty is indistinguishable from an empty file, which is a thing a skill really has.
    if (file.text() != null) {
      out.put("text", file.text());
    }
    return out;
  }

  /**
   * The whole skill as a zip, to keep or to hand to somebody else.
   *
   * <p>The counterpart of {@link #importZip}, and deliberately its exact counterpart: what comes
   * down here goes back up there unchanged, because both speak the skill's own folder with no
   * wrapper directory around it. A skill is a folder of files, and "send me a copy of that" is a
   * thing people want to do with a folder.
   *
   * <p>Streamed rather than buffered, so a skill carrying assets does not become a heap allocation
   * the size of itself. The response is written as it is read, which is why this is the one
   * endpoint here returning a {@code StreamingResponseBody} — the same shape core's {@code
   * ShareController} uses.
   */
  @GetMapping("/export")
  public ResponseEntity<StreamingResponseBody> export(
      @AuthenticationPrincipal final OAuth2User principal,
      @RequestParam(required = false) final String scope,
      @RequestParam("skill") final String skill) {

    final var user = ChatController.user(principal);
    final var home = homeFor(targetFor(scope, user), user);
    final var dir = located(home, skill);

    log.info("{} downloaded the skill {}", user.id(), skill);
    final StreamingResponseBody body = out -> skills.pack(home, dir, out);

    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, attachment(skill + ".zip"))
        // The bytes are a zip whatever a browser might make of the name, and this is a file
        // written by whoever owns the skill. Saying so stops any of it being sniffed as something
        // the browser would rather run.
        .header("X-Content-Type-Options", "nosniff")
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(body);
  }

  /**
   * A {@code Content-Disposition} that survives a name with a character in it.
   *
   * <p>Two spellings, which is what RFC 6266 asks for: a plain {@code filename} that only ASCII
   * goes into, for anything old, and a {@code filename*} carrying the real name percent-encoded as
   * UTF-8. A skill name is validated as one path segment but nothing stops it being Chinese, and a
   * raw non-ASCII byte in a header is a name the browser renders as mojibake — or, with a quote in
   * it, a header the browser reads as ending early.
   */
  private static String attachment(final String name) {
    final var ascii = name.replaceAll("[^A-Za-z0-9._-]", "_");
    final var encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
  }

  // ─────────────────────────────────────── writing ───────────────────────────────────────

  /**
   * A new skill: the folder, and the SKILL.md that makes it one.
   *
   * <p>The manifest is written here rather than left for the first save, because a folder without
   * one is not a skill to anything else in this runtime — {@code SkillsTool} would not load it and
   * {@link #list} would not show it, so a person would create something and watch it not appear.
   */
  @PostMapping
  public ResponseEntity<Map<String, Object>> create(
      @AuthenticationPrincipal final OAuth2User principal, @RequestBody final NewSkill body) {

    final var user = ChatController.user(principal);
    final var target = targetFor(body == null ? null : body.scope(), user);
    requireTenantWritable(target, user);
    final var home = homeFor(target, user);

    final var name = trimmed(body == null ? null : body.name());
    if (name.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, messages.get("skill-name-required"));
    }
    if (name.length() > SkillFiles.MAX_NAME_LENGTH) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, messages.get("skill-name-too-long", SkillFiles.MAX_NAME_LENGTH));
    }
    // The name is typed by a person, so what is wrong with it is worth saying in its own words:
    // "outside the skill" is true of `../evil` and tells whoever typed it nothing about what a
    // skill name may be. Everything after this point has a name SkillFiles has already accepted.
    final Path dir;
    try {
      dir = skills.target(home, name);
    } catch (final SkillAccessDenied e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, messages.get("skill-name-invalid"), e);
    }
    if (skills.locate(home, name).isPresent()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, messages.get("skill-name-taken", name));
    }

    final var description = trimmed(body.description());
    guarding(
        () ->
            skills.write(
                home,
                dir,
                SkillFiles.MANIFEST,
                """
                ---
                name: %s
                description: %s
                ---

                """
                    .formatted(name, description)));

    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            asJson(
                skills
                    .detail(home, name)
                    .orElseThrow(() -> new IllegalStateException("just written"))
                    .skill(),
                target,
                false));
  }

  /**
   * A whole skill at once, out of a zip.
   *
   * <p>The other way of getting a skill onto this page is to make an empty one and type into it,
   * which is fine for a skill somebody is writing and hopeless for one they already have — a folder
   * of instructions, references and scripts arrives as a folder, not as eight files pasted in one
   * at a time.
   *
   * <p>The archive is read and checked in full before anything is written ({@code
   * SkillFiles.unpack} says why), and the name still comes from the request rather than from the
   * archive: an archive chooses its own folder name, and a page that let it choose where it landed
   * would be a page where uploading a file decides what it overwrites.
   */
  @PostMapping("/import")
  public ResponseEntity<Map<String, Object>> importZip(
      @AuthenticationPrincipal final OAuth2User principal,
      @RequestParam(required = false) final String scope,
      @RequestParam("name") final String wantedName,
      @RequestParam("file") final MultipartFile archive) {

    final var user = ChatController.user(principal);
    final var target = targetFor(scope, user);
    requireTenantWritable(target, user);
    final var home = homeFor(target, user);

    final var name = trimmed(wantedName);
    if (name.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, messages.get("skill-name-required"));
    }
    if (name.length() > SkillFiles.MAX_NAME_LENGTH) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, messages.get("skill-name-too-long", SkillFiles.MAX_NAME_LENGTH));
    }
    if (archive == null || archive.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, messages.get("upload-empty"));
    }
    if (archive.getSize() > FileController.MAX_BYTES) {
      throw new ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE,
          messages.get(
              "upload-too-large",
              archive.getOriginalFilename(),
              FileController.MAX_BYTES / 1024 / 1024));
    }

    final Path dir;
    try {
      dir = skills.target(home, name);
    } catch (final SkillAccessDenied e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, messages.get("skill-name-invalid"), e);
    }
    if (skills.locate(home, name).isPresent()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, messages.get("skill-name-taken", name));
    }

    final Map<String, byte[]> files;
    try (var in = archive.getInputStream()) {
      files = skills.unpack(in);
    } catch (final IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, messages.get("upload-failed"), e);
    } catch (final IllegalArgumentException e) {
      // Everything unpack refuses — not a zip, too big unpacked, too many entries, a name that
      // climbs out. Its own sentence rather than a generic failure: each of those is something the
      // person can do something about, and which one it was is the whole of the information.
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    } catch (final SkillAccessDenied e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, messages.get("skill-zip-escapes"), e);
    }
    if (!files.containsKey(SkillFiles.MANIFEST)) {
      // Refused before a single file is written. An archive without a manifest unpacks into a
      // folder nothing in this runtime treats as a skill — invisible to the list, unreachable by
      // name, and therefore impossible to delete from here.
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, messages.get("skill-zip-no-manifest", SkillFiles.MANIFEST));
    }

    final var written = guarding(() -> skills.writeAll(home, dir, files));
    log.info("{} imported the skill {} from an archive, {} files", user.id(), name, written);

    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            asJson(
                skills
                    .detail(home, name)
                    .orElseThrow(() -> new IllegalStateException("just written"))
                    .skill(),
                target,
                false));
  }

  @PutMapping("/file")
  public Map<String, Object> save(
      @AuthenticationPrincipal final OAuth2User principal, @RequestBody final Save body) {

    final var user = ChatController.user(principal);
    final var target = targetFor(body == null ? null : body.scope(), user);
    requireTenantWritable(target, user);
    final var home = homeFor(target, user);
    final var dir = located(home, body == null ? null : body.skill());

    // The same ceiling the read has, and deliberately the same one. A larger write cap means a
    // file that can be saved and then never shown again — the editor would refuse to open what it
    // had just written, and the only way back to it would be to ask the agent.
    final var text = body.text() == null ? "" : body.text();
    if (text.getBytes(StandardCharsets.UTF_8).length > SkillFiles.MAX_TEXT_BYTES) {
      throw new ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE,
          messages.get("skill-file-too-large", SkillFiles.MAX_TEXT_BYTES / 1024));
    }

    final var written = guarding(() -> skills.write(home, dir, body.path(), text));
    log.info("{} saved {} in the skill {}", user.id(), written.path(), body.skill());
    return Map.of("path", written.path(), "dir", false, "size", written.size());
  }

  /**
   * Files added to a skill as they are, rather than typed into the page.
   *
   * <p>The same ceilings uploading into a conversation has, and deliberately the same ones: both
   * write into somebody's home off a browser request, and a second pair of numbers would be a
   * second thing to keep in step.
   */
  @PostMapping("/files")
  public Map<String, Object> upload(
      @AuthenticationPrincipal final OAuth2User principal,
      @RequestParam(required = false) final String scope,
      @RequestParam("skill") final String skill,
      @RequestParam(required = false) final String dir,
      @RequestParam("files") final List<MultipartFile> files) {

    final var user = ChatController.user(principal);
    final var target = targetFor(scope, user);
    requireTenantWritable(target, user);
    final var home = homeFor(target, user);
    final var skillDir = located(home, skill);

    if (files == null || files.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, messages.get("upload-empty"));
    }
    if (files.size() > FileController.MAX_FILES) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, messages.get("upload-too-many", FileController.MAX_FILES));
    }

    final var saved = new ArrayList<Map<String, Object>>();
    for (final var file : files) {
      if (file.isEmpty()) {
        continue;
      }
      if (file.getSize() > FileController.MAX_BYTES) {
        throw new ResponseStatusException(
            HttpStatus.PAYLOAD_TOO_LARGE,
            messages.get(
                "upload-too-large",
                file.getOriginalFilename(),
                FileController.MAX_BYTES / 1024 / 1024));
      }
      saved.add(store(file, home, skillDir, dir));
    }
    if (saved.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, messages.get("upload-empty"));
    }
    return Map.of("files", saved);
  }

  @DeleteMapping("/file")
  public ResponseEntity<Void> deleteFile(
      @AuthenticationPrincipal final OAuth2User principal,
      @RequestParam(required = false) final String scope,
      @RequestParam("skill") final String skill,
      @RequestParam("path") final String path) {

    final var user = ChatController.user(principal);
    final var target = targetFor(scope, user);
    requireTenantWritable(target, user);
    final var home = homeFor(target, user);
    final var dir = located(home, skill);

    guarding(() -> skills.deleteFile(home, dir, path));
    log.info("{} deleted {} from the skill {}", user.id(), path, skill);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping
  public ResponseEntity<Void> delete(
      @AuthenticationPrincipal final OAuth2User principal,
      @RequestParam(required = false) final String scope,
      @RequestParam("skill") final String skill) {

    final var user = ChatController.user(principal);
    final var target = targetFor(scope, user);
    requireTenantWritable(target, user);
    final var home = homeFor(target, user);
    final var dir = located(home, skill);

    guarding(() -> skills.deleteSkill(home, dir));
    log.info("{} deleted the skill {}", user.id(), skill);
    return ResponseEntity.noContent().build();
  }

  // ─────────────────────────────────────── the shared parts
  // ───────────────────────────────────────

  /**
   * Which store this request is about.
   *
   * <p>A target, not an identity — the identity is always the caller's. A group one is refused
   * outright because this surface has no group: {@code ChatController} puts none on a web request,
   * so a skill written into a blank group's directory would be a folder nobody's run ever reads.
   * The same reasoning {@code KnowledgeController.targetFor} gives.
   */
  ScopeTarget targetFor(final String scope, final WebUser user) {
    if (scope == null || scope.isBlank()) {
      return ScopeTarget.OWN;
    }
    final var target =
        ScopeTarget.named(scope)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, messages.get("skill-scope-unknown", scope)));
    if (target == ScopeTarget.GROUP) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, messages.get("skill-no-group"));
    }
    if (target == ScopeTarget.TENANT && (user.tenantId() == null || user.tenantId().isBlank())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, messages.get("skill-no-tenant"));
    }
    return target;
  }

  /**
   * Refuses a write to the company's skills where this deployment keeps them to its admins.
   *
   * <p>403 and not 404: these are skills the caller reads, lists and exports, and hiding them on a
   * write would be a refusal nothing on the page could explain.
   */
  private void requireTenantWritable(final ScopeTarget target, final WebUser user) {
    if (target == ScopeTarget.TENANT && !tenantWrites.allowed(user.id())) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, messages.get("skill-tenant-read-only"));
    }
  }

  /**
   * The one home this request may reach.
   *
   * <p>Single-scope, never the composite, and that is load-bearing rather than tidy: {@code
   * SkillFiles.guarded} asks the home it is given whether a path is inside it, so a composite would
   * accept a {@code scope=own} request that resolved its way into the tenant's store. The scope in
   * the query decides which directory, and the home is how that decision is enforced.
   */
  HomeDir homeFor(final ScopeTarget target, final WebUser user) {
    return target == ScopeTarget.TENANT
        ? workspaces.forTenant(user.tenantId())
        : workspaces.forOwner(user.id());
  }

  private List<SkillSummary> company(final WebUser user) {
    return skills.list(workspaces.forTenant(user.tenantId()));
  }

  private static List<String> names(final List<SkillSummary> summaries) {
    return summaries.stream().map(SkillSummary::name).toList();
  }

  /** The skill's folder, or 404 — every write and every delete starts here. */
  private Path located(final HomeDir home, final String skill) {
    return guarding(() -> skills.locate(home, skill))
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, messages.get("skill-not-found", skill)));
  }

  /**
   * Runs something that may refuse the path it was given, and turns that refusal into a status.
   *
   * <p>403 rather than 400: the request was well formed and the answer is that it may not be
   * answered. The message is {@code SkillFiles}' own, which deliberately names no path — see {@link
   * SkillAccessDenied}.
   */
  private <T> T guarding(final java.util.function.Supplier<T> work) {
    try {
      return work.get();
    } catch (final SkillAccessDenied e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, messages.get("skill-path-denied"), e);
    } catch (final IllegalArgumentException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          messages.get("skill-too-many-files", SkillFiles.MAX_TREE_ENTRIES),
          e);
    } catch (final UncheckedIOException e) {
      log.warn("A skill could not be read or written", e);
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, messages.get("skill-write-failed"), e);
    }
  }

  private void guarding(final Runnable work) {
    guarding(
        () -> {
          work.run();
          return null;
        });
  }

  private Map<String, Object> store(
      final MultipartFile file, final HomeDir home, final Path skillDir, final String dir) {
    // Only the last segment of whatever the browser sent, and taken before anything is resolved.
    // The name is chosen by whoever uploads, and a path in it is the oldest trick there is;
    // SkillFiles would refuse the climb anyway, but a file landing in a folder nobody named is
    // still the wrong file in the wrong place.
    final var sent = trimmed(file.getOriginalFilename());
    final var name = sent.isEmpty() ? null : Path.of(sent).getFileName();
    if (name == null || name.toString().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, messages.get("upload-failed"));
    }
    final var relative = (dir == null || dir.isBlank() ? "" : dir.trim() + "/") + name;
    try (var in = file.getInputStream()) {
      // Streamed, and as bytes rather than text: a skill may hold a font or an image, and decoding
      // one through a String on the way in stores a corrupted file and reports success.
      final var written = guarding(() -> skills.write(home, skillDir, relative, in));
      return Map.of("path", written.path(), "size", written.size());
    } catch (final IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, messages.get("upload-failed"), e);
    }
  }

  private static String trimmed(final String value) {
    return value == null ? "" : value.trim();
  }

  private static Map<String, Object> asJson(
      final SkillSummary skill, final ScopeTarget scope, final boolean shadowed) {
    final var out = new LinkedHashMap<String, Object>();
    out.put("name", skill.name());
    out.put("declaredName", skill.declaredName());
    out.put("description", skill.description());
    out.put("fileCount", skill.fileCount());
    out.put("updatedAt", skill.updatedAt() == null ? null : skill.updatedAt().toString());
    out.put("scope", scope.name().toLowerCase(Locale.ROOT));
    // Whether the model will ever see this one. A company skill whose name the caller also has
    // privately is never offered: SkillsTool keeps the nearest of a duplicate. Said here because
    // nothing else tells anybody, and a skill that quietly does nothing is worse than none.
    out.put("shadowed", shadowed);
    // Never the directory. An absolute path under app.storage.location describes the shape of the
    // storage behind this page to anybody who can open it.
    return out;
  }

  /**
   * @param scope which store, {@code own} (the default) or {@code tenant}
   */
  public record NewSkill(String name, String description, String scope) {}

  /**
   * @param path where in the skill, relative to its own folder
   */
  public record Save(String skill, String path, String text, String scope) {}
}
