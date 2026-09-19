package me.kezhenxu94.springagent.integration.websocket.web;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.TenantWrites;
import me.kezhenxu94.springagent.core.memory.MemoryStore;
import me.kezhenxu94.springagent.core.tools.HomeDir.Folder;
import me.kezhenxu94.springagent.core.tools.ScopeTarget;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import me.kezhenxu94.springagent.integration.websocket.config.WebMessages;
import me.kezhenxu94.springagent.integration.websocket.security.WebUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * What the agent has remembered, without going through the model to reach it.
 *
 * <p>The same argument {@code SkillController} and {@code KnowledgeController} make about the other
 * two things a scope owns. A memory is a file the agent wrote about the person it was talking to,
 * and everything here is something {@code MemoryTools} already does in a conversation — but asking
 * in prose is a poor way to correct a claim. The model has to list, guess a path, read the file
 * back and retell it, and any of those can go wrong quietly. Worse than with a skill, in fact: a
 * memory the agent got wrong is a memory it will keep acting on, and asking the thing that wrote it
 * to unwrite it is the one correction most likely to go round in a circle.
 *
 * <p><b>Whose memories is decided by the authenticated principal, never by the request.</b> {@code
 * scope} chooses between the caller's own home and their company's and nothing else — there is no
 * {@code owner} parameter here, and no administrator can read another person's memories through it.
 * That is the rule {@code SkillController} keeps rather than the one the knowledge base keeps: a
 * knowledge document is something a person filed on purpose, while a memory is what the agent
 * concluded about them, and the second is nobody else's to read.
 *
 * <p>There is no group scope, for the reason {@code SkillController.targetFor} gives: {@code
 * ChatController} puts no group on a web request, so a memory written into a blank group's
 * directory would be a file nobody's run ever reads.
 *
 * <p><b>Who may write company memories is {@code app.ai.non-admin-tenant-writes}</b>, asked through
 * {@link TenantWrites} exactly as the skills page and the knowledge base ask it. Deliberately
 * <em>not</em> through {@code MemoryScopes.writable}, which is the same question plus one more:
 * that a shared write happen in a group chat, in front of the people it affects. A browser session
 * has no group at all, so reusing that answer here would make the company scope permanently
 * read-only on this page whatever the deployment configured — a control no setting could ever open.
 * The witness rule is a rule about chats and stays in the chat tools; what this page grants is what
 * it already grants over company skills, which are instructions the agent executes and so strictly
 * the more dangerous of the two.
 *
 * <p><b>A path is a query parameter and never a path variable</b>, the rule the other two state: a
 * memory in a folder is addressed by a relative path with slashes in it, which encoded is rejected
 * by the container and unencoded reads as more of the route.
 */
@Slf4j
@RestController
@RequestMapping("/api/memories")
@RequiredArgsConstructor
public class MemoryController {

  private final MemoryStore memories;
  private final UserWorkspaceFactory workspaces;
  private final TenantWrites tenantWrites;
  private final WebMessages messages;

  // ─────────────────────────────────────── reading ───────────────────────────────────────

  @GetMapping
  public Map<String, Object> list(
      @AuthenticationPrincipal final OAuth2User principal,
      @RequestParam(required = false) final String scope) {

    final var user = ChatController.user(principal);
    final var target = targetFor(scope, user);

    // The scope on the envelope and not on every row: it is the same for all of them, and a field
    // repeated two hundred times is two hundred chances for one of them to say something else.
    return Map.of(
        "scope", target.word(), "memories", guarding(() -> memories.list(rootFor(target, user))));
  }

  @GetMapping("/file")
  public Map<String, Object> file(
      @AuthenticationPrincipal final OAuth2User principal,
      @RequestParam(required = false) final String scope,
      @RequestParam("path") final String path) {

    final var user = ChatController.user(principal);
    final var target = targetFor(scope, user);

    final var file =
        guarding(() -> memories.read(rootFor(target, user), path))
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, messages.get("memory-not-found", path)));

    return Map.of("scope", target.word(), "file", file);
  }

  // ─────────────────────────────────────── writing ───────────────────────────────────────

  /**
   * Writes a memory, whether or not there was one there.
   *
   * <p>One endpoint rather than a create and an update, because from the page they are one gesture:
   * somebody is looking at a text area and pressing save. {@code MemoryCreate} refuses a path that
   * exists, which is right for a model that cannot see the file and would otherwise replace
   * something it never read, and wrong for a person who is reading it.
   *
   * <p>Nothing here maintains {@code MEMORY.md}. The index is prose, one line per memory, and what
   * belongs on that line is a judgement — so it is a file on this page like any other, edited by
   * whoever is editing memories. A page that appended a line of its own devising would be a page
   * fighting the agent over the shape of a file they both write.
   */
  @PutMapping("/file")
  public Map<String, Object> save(
      @AuthenticationPrincipal final OAuth2User principal, @RequestBody final Save body) {

    final var user = ChatController.user(principal);
    final var target = targetFor(body == null ? null : body.scope(), user);
    requireTenantWritable(target, user);

    final var path = trimmed(body == null ? null : body.path());
    if (path.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, messages.get("memory-path-required"));
    }
    // The same ceiling the read has, and deliberately the same one — SkillController.save says
    // why: a larger write cap means a file that can be saved and then never shown again.
    final var text = body.text() == null ? "" : body.text();
    if (text.getBytes(StandardCharsets.UTF_8).length > MemoryStore.MAX_TEXT_BYTES) {
      throw new ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE,
          messages.get("memory-too-large", MemoryStore.MAX_TEXT_BYTES / 1024));
    }

    final var written = guarding(() -> memories.write(rootFor(target, user), path, text));
    log.info("{} saved the memory {} in {}", user.id(), written.path(), target.word());
    return Map.of("scope", target.word(), "memory", written);
  }

  @DeleteMapping("/file")
  public ResponseEntity<Void> delete(
      @AuthenticationPrincipal final OAuth2User principal,
      @RequestParam(required = false) final String scope,
      @RequestParam("path") final String path) {

    final var user = ChatController.user(principal);
    final var target = targetFor(scope, user);
    requireTenantWritable(target, user);

    if (!guarding(() -> memories.delete(rootFor(target, user), path))) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, messages.get("memory-not-found", path));
    }
    log.info("{} deleted the memory {} from {}", user.id(), path, target.word());
    return ResponseEntity.noContent().build();
  }

  // ─────────────────────────────────────── the shared parts ───────────────────────────────

  /** Which store this request is about — see {@code SkillController.targetFor}. */
  ScopeTarget targetFor(final String scope, final WebUser user) {
    if (scope == null || scope.isBlank()) {
      return ScopeTarget.OWN;
    }
    final var target =
        ScopeTarget.named(scope)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, messages.get("memory-scope-unknown", scope)));
    if (target == ScopeTarget.GROUP) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, messages.get("memory-no-group"));
    }
    if (target == ScopeTarget.TENANT && (user.tenantId() == null || user.tenantId().isBlank())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, messages.get("memory-no-tenant"));
    }
    return target;
  }

  /**
   * Refuses a write to the company's memories where this deployment keeps them to its admins.
   *
   * <p>403 and not 404, the distinction {@code SkillController} draws: these are memories the
   * caller reads and lists, and hiding them on a write would be a refusal nothing on the page could
   * explain.
   */
  private void requireTenantWritable(final ScopeTarget target, final WebUser user) {
    if (target == ScopeTarget.TENANT && !tenantWrites.allowed(user.id())) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, messages.get("memory-tenant-read-only"));
    }
  }

  /**
   * The one memories directory this request may reach.
   *
   * <p>{@code folderPath} and never {@code folder}: naming a company's memories in a listing must
   * not bring a directory into existence in shared storage on behalf of somebody who only read.
   * That is the rule {@code MemoryScopes} states and the reason it resolves its roots the same way;
   * creating is the write path's job, and {@code MemoryStore.write} does it in the write that needs
   * it.
   */
  Path rootFor(final ScopeTarget target, final WebUser user) {
    final var home =
        target == ScopeTarget.TENANT
            ? workspaces.forTenant(user.tenantId())
            : workspaces.forOwner(user.id());
    return home.folderPath(Folder.MEMORIES);
  }

  /**
   * Runs something that may refuse the path it was given, and turns that refusal into a status.
   *
   * <p>403 rather than 400: the request was well formed and the answer is that it may not be
   * answered. The message is this module's own and names no path — {@code MemoryFiles} puts the
   * path it was given into the {@code SecurityException} for a log, and repeating a constructed
   * absolute path back out of the process is how a probe learns the shape of the storage it did not
   * reach. Same reasoning as {@code SkillAccessDenied}.
   */
  private <T> T guarding(final java.util.function.Supplier<T> work) {
    try {
      return work.get();
    } catch (final SecurityException e) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, messages.get("memory-path-denied"), e);
    } catch (final IllegalArgumentException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          messages.get("memory-too-many-files", MemoryStore.MAX_ENTRIES),
          e);
    } catch (final UncheckedIOException e) {
      log.warn("A memory could not be read or written", e);
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, messages.get("memory-write-failed"), e);
    }
  }

  private static String trimmed(final String value) {
    return value == null ? "" : value.trim();
  }

  /**
   * @param scope which store, {@code own} (the default) or {@code tenant}
   * @param path where in that store, relative to its memories root
   */
  public record Save(String scope, String path, String text) {}
}
