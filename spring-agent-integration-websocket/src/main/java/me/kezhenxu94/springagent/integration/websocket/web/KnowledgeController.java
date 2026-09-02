package me.kezhenxu94.springagent.integration.websocket.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeBase;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeEntry;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeReference;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeScope;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeSource;
import me.kezhenxu94.springagent.core.tools.HomeDir;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import me.kezhenxu94.springagent.integration.websocket.config.WebMessages;
import me.kezhenxu94.springagent.integration.websocket.security.WebUser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * The knowledge base, without going through the model to reach it.
 *
 * <p>Everything here is something {@code KnowledgeBaseTools} already does in a conversation, and
 * the two are deliberately the same operations on the same documents — what is added from this page
 * is what a run retrieves, and what a run stored is what this page lists. The reason for a page at
 * all is that asking in prose is a poor way to check a list or correct one entry: the model has to
 * pick the tool, guess the id, and report back, and any of the three can go wrong quietly.
 *
 * <p><b>Whose knowledge base is decided by the authenticated principal, never by the request.</b>
 * The scope is built from the principal exactly as {@code ChatController} builds an {@code
 * AgentRequest} — so a page cannot read a knowledge base a run started from it could not. The one
 * exception is {@code owner}, which an admin may name on the read endpoints, mirroring {@code
 * KnowledgeAdminTools} and going no further than it does: no write ever accepts one.
 *
 * <p>A deployment with no {@link KnowledgeBase} bean is a supported deployment, not a broken one —
 * the knowledge base exists only where {@code spring-agent-rag-milvus} is on the classpath and
 * {@code app.ai.rag.enabled} is set. Every endpoint here answers 404 in that case, and {@code
 * /api/me} says so first so the page never offers the panel at all.
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

  /** The same ceiling {@code KnowledgeFormat} puts on a listing, restated for the query string. */
  private static final int MAX_PAGE_SIZE = 100;

  private final ObjectProvider<KnowledgeBase> knowledgeBases;
  private final Admins admins;
  private final UserWorkspaceFactory workspaces;
  private final WebMessages messages;
  private final SpringAgentProperties properties;

  @GetMapping
  public Map<String, Object> list(
      @AuthenticationPrincipal final OAuth2User principal,
      @RequestParam(required = false) final Integer offset,
      @RequestParam(required = false) final Integer limit,
      @RequestParam(required = false) final String owner) {

    final var user = ChatController.user(principal);
    final var from = offset == null || offset < 0 ? 0 : offset;
    final var size =
        Math.min(
            MAX_PAGE_SIZE,
            limit == null || limit <= 0 ? properties.ai().rag().listPageSize() : limit);

    final var page = knowledgeBase().list(readableScope(user, owner), from, size);
    final var out = new LinkedHashMap<String, Object>();
    out.put("entries", page.entries().stream().map(KnowledgeController::asJson).toList());
    out.put("hasMore", page.hasMore());
    out.put("offset", from);
    out.put("limit", size);
    return out;
  }

  @GetMapping("/search")
  public Map<String, Object> search(
      @AuthenticationPrincipal final OAuth2User principal,
      @RequestParam("q") final String query,
      @RequestParam(required = false) final Integer topK,
      @RequestParam(required = false) final String owner) {

    final var user = ChatController.user(principal);
    if (query == null || query.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, messages.get("knowledge-no-query"));
    }
    final var limit = topK == null || topK <= 0 ? properties.ai().rag().topK() : topK;
    final var found = knowledgeBase().search(readableScope(user, owner), query, limit);

    // Per document rather than per passage, and deduped by the same rule a card's citations use:
    // five chunks of one file are one thing somebody stored, and listing them five times would say
    // the knowledge base holds five documents about the subject when it holds one.
    final var hits = new ArrayList<Map<String, Object>>();
    for (final var reference : KnowledgeReference.of(found)) {
      final var hit = new LinkedHashMap<String, Object>();
      hit.put("docId", reference.docId());
      hit.put("title", reference.title());
      hit.put("source", reference.source());
      hit.put("scope", reference.scope().name().toLowerCase(Locale.ROOT));
      hit.put("score", reference.score());
      hits.add(hit);
    }
    return Map.of("hits", hits);
  }

  /**
   * One document with the text that was indexed, for reading what is actually stored.
   *
   * <p>The id is a parameter rather than a path variable, the same as {@link #delete} and {@link
   * #move}: a document indexed from a file is identified by its absolute path.
   *
   * <p>Readable by an admin naming an owner, because this is a read and it shows exactly what a
   * listing already showed them the title of — {@code KnowledgeAdminTools} reads another person's
   * documents on the same terms.
   */
  @GetMapping("/document")
  public Map<String, Object> document(
      @AuthenticationPrincipal final OAuth2User principal,
      @RequestParam("docId") final String docId,
      @RequestParam(required = false) final String owner) {

    final var user = ChatController.user(principal);
    final var document =
        knowledgeBase()
            .read(readableScope(user, owner), docId)
            // Not found rather than forbidden, as everywhere else here: whether somebody else's
            // document exists is not this caller's business.
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, messages.get("knowledge-not-found", docId)));

    final var out = new LinkedHashMap<>(asJson(document.entry()));
    out.put("text", document.text());
    return out;
  }

  /**
   * A file, stored and indexed in one request.
   *
   * <p>Stored first, in the caller's {@code artifacts} directory beside what they have sent the
   * agent in a chat, and indexed from there. Keeping the file is the point: the agent's file tools
   * can still open it, the document can be indexed again if the chunking changes, and its path is
   * an honest answer to "where did this come from" in a listing.
   *
   * <p>Indexed synchronously, so the answer to this request is whether it worked. Embedding a large
   * document is not fast and this holds a request thread for as long as it takes — the trade is
   * deliberate: a background job would leave the page reporting a success it cannot know about,
   * which is the failure this endpoint exists to remove. {@code spring.threads.virtual} is on in
   * the applications carrying this module, and {@code MAX_BYTES} caps the worst case.
   */
  @PostMapping("/files")
  public Map<String, Object> upload(
      @AuthenticationPrincipal final OAuth2User principal,
      @RequestParam("files") final List<MultipartFile> files,
      @RequestParam(required = false) final String scope) {

    final var user = ChatController.user(principal);
    final var knowledgeBase = knowledgeBase();
    final var target = targetFor(scope, user);

    if (files == null || files.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, messages.get("upload-empty"));
    }
    if (files.size() > FileController.MAX_FILES) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, messages.get("upload-too-many", FileController.MAX_FILES));
    }

    final var home = homeFor(target, user);
    final var indexed = new ArrayList<Map<String, Object>>();
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
      final Path stored;
      try {
        stored = FileController.free(FileController.artifactPath(file.getOriginalFilename(), home));
        try (var in = file.getInputStream()) {
          Files.copy(in, stored, StandardCopyOption.REPLACE_EXISTING);
        }
      } catch (final IllegalArgumentException e) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
      } catch (final IOException e) {
        log.warn("Could not store a knowledge upload from {}", user.id(), e);
        throw new ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR, messages.get("upload-failed"), e);
      }

      // The absolute path, which is what IndexKnowledge is told to use for a file in a workspace.
      // It is unique on its own — a home directory is one person's — so two people filing a
      // report.pdf into the company knowledge base store two documents rather than one of them
      // silently replacing the other, and a re-index of the same file from a run replaces this one
      // rather than duplicating it.
      final var docId = stored.toAbsolutePath().toString();
      indexed.add(
          index(
              KnowledgeSource.ofPath(
                  callerScope(user), target, stored.getFileName().toString(), docId, docId),
              user));
    }
    if (indexed.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, messages.get("upload-empty"));
    }
    return Map.of("documents", indexed);
  }

  /** Something typed rather than uploaded, for what there is no file for. */
  @PostMapping("/notes")
  public Map<String, Object> note(
      @AuthenticationPrincipal final OAuth2User principal, @RequestBody final Note note) {

    final var user = ChatController.user(principal);
    if (note == null || note.text() == null || note.text().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, messages.get("knowledge-no-text"));
    }
    if (note.title() == null || note.title().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, messages.get("knowledge-no-title"));
    }
    final var target = targetFor(note.scope(), user);
    // A note has no origin to be named after — nobody sent it, and no file holds it — so it gets an
    // id of its own rather than one derived from its title, which two notes could share.
    final var docId = "note:" + UUID.randomUUID();
    return index(
        KnowledgeSource.ofText(
            callerScope(user), target, note.title().trim(), note.text(), docId, docId),
        user);
  }

  /**
   * Which knowledge base a document sits in.
   *
   * <p>The id is in the body rather than in the path, and that is not a style choice: a document
   * indexed from a file is identified by its absolute path, so its id contains slashes. Encoded,
   * they are rejected by the servlet container before this method is reached; unencoded, they are
   * extra path segments. The same reason {@link #delete} takes a parameter.
   */
  @PatchMapping
  public Map<String, Object> move(
      @AuthenticationPrincipal final OAuth2User principal, @RequestBody final Move move) {

    final var user = ChatController.user(principal);
    if (move == null || move.docId() == null || move.docId().isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, messages.get("knowledge-not-found", ""));
    }
    // Named, never defaulted. targetFor reads a missing scope as "own" because that is what an
    // upload with no opinion means; a move with no scope is not an opinion at all, and taking it
    // as "own" would pull a document out of the company knowledge base by omission.
    if (move.scope() == null || move.scope().isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, messages.get("knowledge-scope-unknown", ""));
    }
    final var target = targetFor(move.scope(), user);
    return knowledgeBase()
        .move(callerScope(user), move.docId(), target)
        .map(KnowledgeController::asJson)
        // Not found rather than forbidden, the same as everywhere else here: whether somebody
        // else's document exists is not this caller's business.
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, messages.get("knowledge-not-found", move.docId())));
  }

  @DeleteMapping
  public ResponseEntity<Void> delete(
      @AuthenticationPrincipal final OAuth2User principal,
      @RequestParam("docId") final String docId) {

    final var user = ChatController.user(principal);
    // Scoped, so a docId belonging to somebody else matches nothing rather than being refused —
    // which is also what keeps this from being a way to ask whether a document exists.
    knowledgeBase().delete(callerScope(user), docId);
    log.info("Knowledge document {} deleted by {}", docId, user.id());
    return ResponseEntity.noContent().build();
  }

  /** What a note or an upload becomes, reported the way a listing reports it. */
  private Map<String, Object> index(final KnowledgeSource source, final WebUser user) {
    final String storedId;
    try {
      storedId = knowledgeBase().index(source);
    } catch (final RuntimeException e) {
      log.warn("Could not index {} for {}", source.docId(), user.id(), e);
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, messages.get("knowledge-index-failed", e.getMessage()));
    }
    log.info("Indexed {} into the {} knowledge base for {}", storedId, source.target(), user.id());
    final var out = new LinkedHashMap<String, Object>();
    out.put("docId", storedId);
    out.put("title", source.title());
    out.put("source", source.attribution());
    out.put("scope", source.target().name().toLowerCase(Locale.ROOT));
    return out;
  }

  /**
   * Where the file itself is put, chosen by who is going to be able to read the document.
   *
   * <p>Its path is what a reader is shown as the origin and what the agent's file tools are pointed
   * at, so a document shared with the company whose file sat in the uploader's own home would cite
   * a path nobody else can open — findable in search, and a dead end the moment anyone follows it.
   * The tenant home is the same shared storage a group's or a tenant's skills already live in, and
   * a run carrying that tenant already reaches it.
   */
  private HomeDir homeFor(final KnowledgeScope.Target target, final WebUser user) {
    return target == KnowledgeScope.Target.TENANT
        ? workspaces.forTenant(user.tenantId())
        : workspaces.forOwner(user.id());
  }

  private KnowledgeBase knowledgeBase() {
    final var knowledgeBase = knowledgeBases.getIfAvailable();
    if (knowledgeBase == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, messages.get("knowledge-unavailable"));
    }
    return knowledgeBase;
  }

  /**
   * What the caller may read, and — for an admin who named one — whose.
   *
   * <p>Naming an owner is the whole of what {@code app.ai.admins} adds here, and it adds only
   * reading: the scope returned for one is that person's own knowledge base, not a view across
   * everybody, and no write endpoint calls this. Anybody else naming one is refused rather than
   * quietly given their own, so a page that asks the wrong question gets an answer saying so.
   */
  KnowledgeScope readableScope(final WebUser user, final String owner) {
    if (owner == null || owner.isBlank()) {
      return callerScope(user);
    }
    if (!admins.isAdmin(user.id())) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, messages.get("knowledge-owner-forbidden"));
    }
    return new KnowledgeScope(owner.trim(), "", "");
  }

  /**
   * Which knowledge base a write goes into.
   *
   * <p>A target, not an identity — the identity is always the caller's. A group one is refused
   * outright on this surface: {@code ChatController} puts no group on a web request, so a document
   * stamped with a blank group would be stored, reported as stored, and matched by no reader's
   * filter ever again. The same reasoning as {@code KnowledgeBaseTools.refuseUnreachableTarget},
   * except that here it can be said before anything is written.
   */
  KnowledgeScope.Target targetFor(final String scope, final WebUser user) {
    if (scope == null || scope.isBlank()) {
      return KnowledgeScope.Target.OWN;
    }
    final var target =
        KnowledgeScope.Target.named(scope)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, messages.get("knowledge-scope-unknown", scope)));
    if (target == KnowledgeScope.Target.GROUP) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, messages.get("knowledge-no-group"));
    }
    if (target == KnowledgeScope.Target.TENANT && callerScope(user).tenant().isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, messages.get("knowledge-no-tenant"));
    }
    return target;
  }

  /**
   * The caller's own reach, built the same way {@code ChatController} builds a run's.
   *
   * <p>No group, because this surface has none — see {@link #targetFor}.
   */
  static KnowledgeScope callerScope(final WebUser user) {
    return new KnowledgeScope(user.id(), "", user.tenantId());
  }

  private static Map<String, Object> asJson(final KnowledgeEntry entry) {
    final var out = new LinkedHashMap<String, Object>();
    out.put("docId", entry.docId());
    out.put("title", entry.title());
    out.put("source", entry.source());
    out.put("chunkCount", entry.chunkCount());
    out.put("createdAt", entry.createdAt() == null ? null : entry.createdAt().toString());
    out.put("scope", entry.scope().name().toLowerCase(Locale.ROOT));
    return out;
  }

  public record Note(String title, String text, String scope) {}

  public record Move(String docId, String scope) {}
}
