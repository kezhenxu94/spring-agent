package me.kezhenxu94.springagent.core.tools;

import com.google.common.base.Strings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.PublishedResource;
import me.kezhenxu94.springagent.core.dao.repo.PublishedResourceRepo;
import me.kezhenxu94.springagent.core.storage.StorageService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
public class PublishFileTool {
  private static final Duration PUBLIC_MAX_TTL = Duration.ofDays(30);
  private static final Duration PUBLIC_DEFAULT_TTL = Duration.ofDays(1);
  private static final String INDEX_FILENAME = "index.html";

  private final StorageService storageService;
  private final UserWorkspaceFactory userWorkspaceFactory;
  private final PublishedResourceRepo publishedResourceRepo;

  // Read straight from the environment rather than through SpringAgentProperties.Ai.Tools, where
  // the other per-tool settings live: it has no default worth writing down, and a placeholder with
  // none fails the context at startup, whereas a null bound into that record would surface as a
  // broken link on the first publish instead.
  //
  // Not final, matching FeishuTools#feishuReplyCard: @Value on a field is an injection point in its
  // own right, and AOT generates a plain field assignment for it, which cannot target a final field
  // the way the JVM's reflective injection can.
  @Value("${app.ai.tools.publish-file.base-url}")
  String baseUrl;

  @Tool(
      name = "PublishFile",
      description =
          """
          Publish a local file or directory as a shareable link.
          visibility=internal: reachable only by colleagues who have signed in with Feishu. ttl is
              optional and means the link never expires when omitted.
          visibility=public: reachable by anyone holding the link, no sign-in. ttl is optional and
              defaults to 1d, at most 30d.
          Only files and directories inside the current user's workspace can be published, which
          means whatever this or another tool produced for them earlier.
          A directory is published whole, keeping its structure, and a browser opens its index.html
          if it has one.
          """)
  public String publishFile(
      @ToolParam(description = "Absolute path of the local file or directory to publish")
          final String path,
      @ToolParam(description = "internal or public") final String visibilityParam,
      @ToolParam(
              description =
                  "How long the link lives, as a duration such as 30s, 10m, 2h or 1d. Optional for"
                      + " public (defaults to 1d, at most 30d) and for internal (never expires when"
                      + " omitted)",
              required = false)
          final String ttl,
      final ToolContext context) {

    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);

    final Path sourcePath;
    try {
      sourcePath = resolveSourcePath(path, userId);
    } catch (IllegalArgumentException e) {
      return "Error: " + e.getMessage();
    }

    final var visibility = PublishedResource.Visibility.from(visibilityParam);
    if (visibility == null) {
      return "Error: visibility must be internal or public.";
    }

    final Instant expiresAt;
    try {
      expiresAt = resolveExpiresAt(visibility, ttl);
    } catch (IllegalArgumentException e) {
      return "Error: " + e.getMessage();
    }

    final var token = UUID.randomUUID().toString().replace("-", "");
    final var visibilityDir = visibility.name().toLowerCase();
    final var basePrefix = visibilityDir + "/" + userId + "/" + token;

    final StoredContent stored;
    try {
      stored = storeContent(sourcePath, basePrefix, null);
    } catch (IOException | UncheckedIOException e) {
      log.error("Failed to publish {} for user {}", sourcePath, userId, e);
      return "Error: publishing failed, try again shortly.";
    }

    publishedResourceRepo.save(
        PublishedResource.builder()
            .id(token)
            .ownerId(userId)
            .visibility(visibility)
            .directory(stored.directory())
            .entryFilename(stored.entryFilename())
            .expiresAt(expiresAt)
            .build());

    final var url = buildUrl(visibilityDir, userId, token, stored);
    final var expiryNote =
        expiresAt == null ? "The link never expires." : "The link expires at " + expiresAt + ".";

    log.info(
        "Published resource token={} owner={} visibility={} directory={} expiresAt={}",
        token,
        userId,
        visibility,
        stored.directory(),
        expiresAt);

    return "Published at " + url + ". " + expiryNote;
  }

  @Tool(
      name = "UpdatePublishedFile",
      description =
          """
          Replace what an already published link serves, keeping the link itself unchanged.
          mode=update (the default): lay the new content over the published content, overwriting
              files of the same name and leaving the rest alone. Only possible when both sides are
              directories, and meant for adding or replacing part of what is published.
          mode=replace: delete everything published so far and put the new content in its place,
              which is a fresh publish that keeps the old link.
          Pass a new ttl to restart the expiry from now; omit it to leave the expiry as it is.
          The new content, too, has to come from inside the current user's workspace.
          """)
  public String updatePublishedFile(
      @ToolParam(
              description =
                  "The token publishing returned: the segment of the link after visibility and the"
                      + " user id")
          final String token,
      @ToolParam(description = "Absolute path of the new local file or directory")
          final String path,
      @ToolParam(
              description = "update (overlay, the default) or replace (swap wholesale)",
              required = false)
          final String mode,
      @ToolParam(
              description =
                  "New lifetime, counted from now, as a duration such as 30s, 10m, 2h or 1d; omit"
                      + " it to keep the current expiry",
              required = false)
          final String ttl,
      final ToolContext context) {

    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    if (Strings.isNullOrEmpty(token)) {
      return "Error: give a token.";
    }

    final var resource = publishedResourceRepo.findById(token).orElse(null);
    if (resource == null) {
      return "Error: nothing is published under " + token + ".";
    }
    if (!resource.ownerId().equals(userId)) {
      return "Error: you can only update content you published yourself.";
    }

    final var updateMode = UpdateMode.from(mode);
    if (updateMode == null) {
      return "Error: mode must be update or replace.";
    }

    final Path sourcePath;
    try {
      sourcePath = resolveSourcePath(path, userId);
    } catch (IllegalArgumentException e) {
      return "Error: " + e.getMessage();
    }

    if (updateMode == UpdateMode.UPDATE && resource.directory() != Files.isDirectory(sourcePath)) {
      return "Error: with mode=update the new content must be the same kind, file or directory, as"
          + " what is published. Use mode=replace to change kind.";
    }

    final Instant expiresAt;
    if (Strings.isNullOrEmpty(ttl)) {
      expiresAt = resource.expiresAt();
    } else {
      try {
        expiresAt = resolveExpiresAt(resource.visibility(), ttl);
      } catch (IllegalArgumentException e) {
        return "Error: " + e.getMessage();
      }
    }

    final var visibilityDir = resource.visibility().name().toLowerCase();
    final var basePrefix = visibilityDir + "/" + userId + "/" + token;

    // Keep the original filename for a file-to-file update so the share URL, which embeds the
    // entry filename as its last path segment, never changes.
    final var forcedSingleFileName = resource.directory() ? null : resource.entryFilename();

    final StoredContent stored;
    try {
      if (updateMode == UpdateMode.REPLACE) {
        deletePublishedFiles(resource, userId);
        stored = storeContent(sourcePath, basePrefix, forcedSingleFileName);
      } else {
        stored = mergeContent(sourcePath, basePrefix, resource.directory(), forcedSingleFileName);
      }
    } catch (IOException | UncheckedIOException e) {
      log.error("Failed to update published resource {} for user {}", token, userId, e);
      return "Error: the update failed, try again shortly.";
    }

    publishedResourceRepo.save(
        resource.toBuilder()
            .directory(stored.directory())
            .entryFilename(stored.entryFilename())
            .expiresAt(expiresAt)
            .build());

    final var url = buildUrl(visibilityDir, userId, token, stored);
    final var expiryNote =
        expiresAt == null ? "The link never expires." : "The link expires at " + expiresAt + ".";

    log.info(
        "Updated published resource token={} owner={} mode={} directory={} expiresAt={}",
        token,
        userId,
        updateMode,
        stored.directory(),
        expiresAt);

    return "Updated, still at " + url + ". " + expiryNote;
  }

  @Tool(
      name = "UnpublishFile",
      description =
          "Unpublish a file or directory: the link stops working at once and the published"
              + " content is deleted.")
  public String unpublishFile(
      @ToolParam(
              description =
                  "The token publishing returned: the segment of the link after visibility and the"
                      + " user id")
          final String token,
      final ToolContext context) {

    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    if (Strings.isNullOrEmpty(token)) {
      return "Error: give a token.";
    }

    final var resource = publishedResourceRepo.findById(token).orElse(null);
    if (resource == null) {
      return "Error: nothing is published under " + token + ".";
    }
    if (!resource.ownerId().equals(userId)) {
      return "Error: you can only unpublish content you published yourself.";
    }

    deletePublishedFiles(resource, userId);
    publishedResourceRepo.deleteById(token);

    log.info("Unpublished resource token={} owner={}", token, userId);
    return "Unpublished " + token + ".";
  }

  @Tool(
      name = "RenewPublishedFile",
      description =
          "Extend a published link: its lifetime restarts from now, so it does not lapse.")
  public String renewPublishedFile(
      @ToolParam(
              description =
                  "The token publishing returned: the segment of the link after visibility and the"
                      + " user id")
          final String token,
      @ToolParam(
              description =
                  "New lifetime, counted from now, as a duration such as 30s, 10m, 2h or 1d."
                      + " Optional for public (defaults to 1d, at most 30d) and for internal"
                      + " (never expires when omitted)",
              required = false)
          final String ttl,
      final ToolContext context) {

    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    if (Strings.isNullOrEmpty(token)) {
      return "Error: give a token.";
    }

    final var resource = publishedResourceRepo.findById(token).orElse(null);
    if (resource == null) {
      return "Error: nothing is published under " + token + ".";
    }
    if (!resource.ownerId().equals(userId)) {
      return "Error: you can only extend content you published yourself.";
    }

    final Instant expiresAt;
    try {
      expiresAt = resolveExpiresAt(resource.visibility(), ttl);
    } catch (IllegalArgumentException e) {
      return "Error: " + e.getMessage();
    }

    publishedResourceRepo.save(resource.toBuilder().expiresAt(expiresAt).build());

    final var expiryNote =
        expiresAt == null ? "The link never expires." : "The link expires at " + expiresAt + ".";
    log.info("Renewed resource token={} owner={} expiresAt={}", token, userId, expiresAt);
    return "Extended " + token + ". " + expiryNote;
  }

  private Path resolveSourcePath(final String path, final String userId) {
    if (Strings.isNullOrEmpty(path)) {
      throw new IllegalArgumentException("Give a file or directory path.");
    }
    final Path sourcePath;
    try {
      sourcePath = Path.of(path).toAbsolutePath().normalize();
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid path: " + path);
    }
    if (!Files.exists(sourcePath)) {
      throw new IllegalArgumentException("No such file or directory: " + path);
    }

    final var userHome = userWorkspaceFactory.forOwner(userId);
    final Path realSourcePath;
    final Path realWorkspaceRoot;
    try {
      realSourcePath = sourcePath.toRealPath();
      realWorkspaceRoot = userHome.workspace().toRealPath();
    } catch (IOException e) {
      throw new IllegalArgumentException("Could not resolve the path: " + path);
    }
    if (!realSourcePath.equals(realWorkspaceRoot)
        && !realSourcePath.startsWith(realWorkspaceRoot)) {
      throw new IllegalArgumentException(
          "Only files and directories inside the current user's workspace can be published.");
    }
    return sourcePath;
  }

  private StoredContent storeContent(
      final Path sourcePath, final String basePrefix, final String forcedSingleFileName)
      throws IOException {
    if (!Files.isDirectory(sourcePath)) {
      return storeSingleFile(sourcePath, basePrefix, forcedSingleFileName);
    }
    copyDirectoryTree(sourcePath, basePrefix);
    final var entryFilename =
        Files.exists(sourcePath.resolve(INDEX_FILENAME)) ? INDEX_FILENAME : null;
    return new StoredContent(true, entryFilename);
  }

  /**
   * Like {@link #storeContent}, but for a directory target does not wipe existing stored content
   * first: files in {@code sourcePath} overwrite same-path entries and are added alongside whatever
   * isn't touched, rather than fully replacing the published tree.
   */
  private StoredContent mergeContent(
      final Path sourcePath,
      final String basePrefix,
      final boolean targetIsDirectory,
      final String forcedSingleFileName)
      throws IOException {
    if (!targetIsDirectory) {
      return storeSingleFile(sourcePath, basePrefix, forcedSingleFileName);
    }
    copyDirectoryTree(sourcePath, basePrefix);
    final var mergedIndex = storageService.resolve(basePrefix + "/" + INDEX_FILENAME);
    final var entryFilename = Files.exists(mergedIndex) ? INDEX_FILENAME : null;
    return new StoredContent(true, entryFilename);
  }

  private StoredContent storeSingleFile(
      final Path sourcePath, final String basePrefix, final String forcedSingleFileName)
      throws IOException {
    final var entryFilename =
        forcedSingleFileName != null ? forcedSingleFileName : sourcePath.getFileName().toString();
    try (final var in = Files.newInputStream(sourcePath)) {
      storageService.store(in, basePrefix + "/" + entryFilename);
    }
    return new StoredContent(false, entryFilename);
  }

  private void copyDirectoryTree(final Path sourcePath, final String basePrefix)
      throws IOException {
    try (final var files = Files.walk(sourcePath)) {
      files
          .filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS))
          .forEach(
              file -> {
                final var relative = sourcePath.relativize(file).toString().replace('\\', '/');
                try (final var in = Files.newInputStream(file)) {
                  storageService.store(in, basePrefix + "/" + relative);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
    }
  }

  private String buildUrl(
      final String visibilityDir,
      final String userId,
      final String token,
      final StoredContent stored) {
    final var urlBuilder =
        UriComponentsBuilder.fromUriString(baseUrl)
            .pathSegment("share", visibilityDir, userId, token);
    if (!stored.directory()) {
      urlBuilder.pathSegment(stored.entryFilename());
    }
    return urlBuilder.build().toUriString();
  }

  private Instant resolveExpiresAt(
      final PublishedResource.Visibility visibility, final String ttl) {
    if (visibility == PublishedResource.Visibility.PUBLIC) {
      final var resolvedTtl = Strings.isNullOrEmpty(ttl) ? PUBLIC_DEFAULT_TTL : parseTtl(ttl);
      if (resolvedTtl.isZero()
          || resolvedTtl.isNegative()
          || resolvedTtl.compareTo(PUBLIC_MAX_TTL) > 0) {
        throw new IllegalArgumentException("A public ttl must be above 0 and at most 30d.");
      }
      return Instant.now().plus(resolvedTtl);
    }
    if (Strings.isNullOrEmpty(ttl)) {
      return null;
    }
    final var parsed = parseTtl(ttl);
    if (parsed.isZero() || parsed.isNegative()) {
      throw new IllegalArgumentException("ttl must be above 0.");
    }
    return Instant.now().plus(parsed);
  }

  private Duration parseTtl(final String ttl) {
    try {
      return DurationStyle.detectAndParse(ttl);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid ttl: use a duration such as 30s, 10m, 2h or 1d.");
    }
  }

  private void deletePublishedFiles(final PublishedResource resource, final String userId) {
    final var visibilityDir = resource.visibility().name().toLowerCase();
    final var dir = storageService.resolve(visibilityDir + "/" + userId + "/" + resource.id());
    try {
      if (!FileSystemUtils.deleteRecursively(dir)) {
        log.warn("Failed to delete published files at {}", dir);
      }
    } catch (IOException e) {
      log.error("Failed to delete published files at {}", dir, e);
    }
  }

  private record StoredContent(boolean directory, String entryFilename) {}

  private enum UpdateMode {
    UPDATE,
    REPLACE;

    static UpdateMode from(final String value) {
      if (Strings.isNullOrEmpty(value)) {
        return UPDATE;
      }
      for (final var mode : values()) {
        if (mode.name().equalsIgnoreCase(value)) {
          return mode;
        }
      }
      return null;
    }
  }
}
