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

  // Not final, matching FeishuTools#feishuReplyCard: @Value on a field is an injection point in its
  // own right, and AOT generates a plain field assignment for it, which cannot target a final field
  // the way the JVM's reflective injection can.
  @Value("${share.base-url}")
  String shareBaseUrl;

  @Tool(
      name = "PublishFile",
      description =
          """
          发布一个本地文件或文件夹为可访问的分享链接。
          visibility=internal：仅登录过 Feishu 的公司成员可访问，ttl 选填，不填表示永久有效。
          visibility=public：任何人凭链接均可访问（无需登录），ttl 选填，不填默认 1d，最大 30d。
          只能发布当前用户 workspace 目录内的文件或文件夹（即本工具或其他工具此前为该用户生成的文件）。
          文件夹会整体发布，保留原有目录结构，浏览器访问时默认打开其中的 index.html（如果存在）。
          """)
  public String publishFile(
      @ToolParam(description = "要发布的本地文件或文件夹的绝对路径") final String path,
      @ToolParam(description = "internal 或 public") final String visibilityParam,
      @ToolParam(
              description = "有效期，时长格式如 30s、10m、2h、1d。public 选填，不填默认 1d，最大 30d；internal 选填，不填表示永久有效",
              required = false)
          final String ttl,
      final ToolContext context) {

    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);

    final Path sourcePath;
    try {
      sourcePath = resolveSourcePath(path, userId);
    } catch (IllegalArgumentException e) {
      return "错误：" + e.getMessage();
    }

    final var visibility = PublishedResource.Visibility.from(visibilityParam);
    if (visibility == null) {
      return "错误：visibility 必须为 internal 或 public。";
    }

    final Instant expiresAt;
    try {
      expiresAt = resolveExpiresAt(visibility, ttl);
    } catch (IllegalArgumentException e) {
      return "错误：" + e.getMessage();
    }

    final var token = UUID.randomUUID().toString().replace("-", "");
    final var visibilityDir = visibility.name().toLowerCase();
    final var basePrefix = visibilityDir + "/" + userId + "/" + token;

    final StoredContent stored;
    try {
      stored = storeContent(sourcePath, basePrefix, null);
    } catch (IOException | UncheckedIOException e) {
      log.error("Failed to publish {} for user {}", sourcePath, userId, e);
      return "错误：发布失败，请稍后重试。";
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
    final var expiryNote = expiresAt == null ? "永久有效（不过期）。" : "过期时间：" + expiresAt + "。";

    log.info(
        "Published resource token={} owner={} visibility={} directory={} expiresAt={}",
        token,
        userId,
        visibility,
        stored.directory(),
        expiresAt);

    return "已发布，链接：" + url + " " + expiryNote;
  }

  @Tool(
      name = "UpdatePublishedFile",
      description =
          """
          更新一个已发布的文件或文件夹的内容，分享链接（URL）保持不变。
          mode=update（默认）：将新内容叠加到已发布内容之上，同名文件被覆盖，未涉及的旧文件保留。
              仅当已发布内容和新内容都是文件夹时才能使用；用于只想新增/覆盖部分文件的场景。
          mode=replace：先删除已发布的全部旧内容，再整体替换为新内容，等价于重新发布一次但保留原链接。
          可选提供新的 ttl 重新计算有效期，不提供则保留原有效期不变。
          新内容同样只能来自当前用户 workspace 目录内的文件或文件夹。
          """)
  public String updatePublishedFile(
      @ToolParam(description = "发布时返回的 token（分享链接中 visibility 和用户 id 之后的那一段）") final String token,
      @ToolParam(description = "新内容的本地文件或文件夹绝对路径") final String path,
      @ToolParam(description = "update（叠加/覆盖，默认）或 replace（整体替换）", required = false)
          final String mode,
      @ToolParam(description = "新的有效期，从现在开始计算，时长格式如 30s、10m、2h、1d。不填则保留原有效期不变", required = false)
          final String ttl,
      final ToolContext context) {

    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    if (Strings.isNullOrEmpty(token)) {
      return "错误：请提供 token。";
    }

    final var resource = publishedResourceRepo.findById(token).orElse(null);
    if (resource == null) {
      return "错误：未找到该发布记录：" + token;
    }
    if (!resource.ownerId().equals(userId)) {
      return "错误：只能更新自己发布的内容。";
    }

    final var updateMode = UpdateMode.from(mode);
    if (updateMode == null) {
      return "错误：mode 必须为 update 或 replace。";
    }

    final Path sourcePath;
    try {
      sourcePath = resolveSourcePath(path, userId);
    } catch (IllegalArgumentException e) {
      return "错误：" + e.getMessage();
    }

    if (updateMode == UpdateMode.UPDATE && resource.directory() != Files.isDirectory(sourcePath)) {
      return "错误：mode=update 时，新内容的类型（文件/文件夹）必须与已发布内容一致；" + "如需更换类型，请使用 mode=replace。";
    }

    final Instant expiresAt;
    if (Strings.isNullOrEmpty(ttl)) {
      expiresAt = resource.expiresAt();
    } else {
      try {
        expiresAt = resolveExpiresAt(resource.visibility(), ttl);
      } catch (IllegalArgumentException e) {
        return "错误：" + e.getMessage();
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
      return "错误：更新失败，请稍后重试。";
    }

    publishedResourceRepo.save(
        resource.toBuilder()
            .directory(stored.directory())
            .entryFilename(stored.entryFilename())
            .expiresAt(expiresAt)
            .build());

    final var url = buildUrl(visibilityDir, userId, token, stored);
    final var expiryNote = expiresAt == null ? "永久有效（不过期）。" : "过期时间：" + expiresAt + "。";

    log.info(
        "Updated published resource token={} owner={} mode={} directory={} expiresAt={}",
        token,
        userId,
        updateMode,
        stored.directory(),
        expiresAt);

    return "已更新，链接保持不变：" + url + " " + expiryNote;
  }

  @Tool(name = "UnpublishFile", description = "取消发布一个已发布的文件或文件夹，链接将立即失效并删除已发布的内容。")
  public String unpublishFile(
      @ToolParam(description = "发布时返回的 token（分享链接中 visibility 和用户 id 之后的那一段）") final String token,
      final ToolContext context) {

    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    if (Strings.isNullOrEmpty(token)) {
      return "错误：请提供 token。";
    }

    final var resource = publishedResourceRepo.findById(token).orElse(null);
    if (resource == null) {
      return "错误：未找到该发布记录：" + token;
    }
    if (!resource.ownerId().equals(userId)) {
      return "错误：只能取消自己发布的内容。";
    }

    deletePublishedFiles(resource, userId);
    publishedResourceRepo.deleteById(token);

    log.info("Unpublished resource token={} owner={}", token, userId);
    return "已取消发布：" + token;
  }

  @Tool(name = "RenewPublishedFile", description = "续期一个已发布的文件或文件夹，从现在开始重新计算有效期，避免链接过期失效。")
  public String renewPublishedFile(
      @ToolParam(description = "发布时返回的 token（分享链接中 visibility 和用户 id 之后的那一段）") final String token,
      @ToolParam(
              description =
                  "新的有效期，从现在开始计算，时长格式如 30s、10m、2h、1d。"
                      + "public 选填，不填默认 1d，最大 30d；internal 选填，不填表示永久有效",
              required = false)
          final String ttl,
      final ToolContext context) {

    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    if (Strings.isNullOrEmpty(token)) {
      return "错误：请提供 token。";
    }

    final var resource = publishedResourceRepo.findById(token).orElse(null);
    if (resource == null) {
      return "错误：未找到该发布记录：" + token;
    }
    if (!resource.ownerId().equals(userId)) {
      return "错误：只能续期自己发布的内容。";
    }

    final Instant expiresAt;
    try {
      expiresAt = resolveExpiresAt(resource.visibility(), ttl);
    } catch (IllegalArgumentException e) {
      return "错误：" + e.getMessage();
    }

    publishedResourceRepo.save(resource.toBuilder().expiresAt(expiresAt).build());

    final var expiryNote = expiresAt == null ? "永久有效（不过期）。" : "过期时间：" + expiresAt + "。";
    log.info("Renewed resource token={} owner={} expiresAt={}", token, userId, expiresAt);
    return "已续期：" + token + "，" + expiryNote;
  }

  private Path resolveSourcePath(final String path, final String userId) {
    if (Strings.isNullOrEmpty(path)) {
      throw new IllegalArgumentException("请提供文件或文件夹路径。");
    }
    final Path sourcePath;
    try {
      sourcePath = Path.of(path).toAbsolutePath().normalize();
    } catch (Exception e) {
      throw new IllegalArgumentException("路径无效：" + path);
    }
    if (!Files.exists(sourcePath)) {
      throw new IllegalArgumentException("文件或文件夹不存在：" + path);
    }

    final var userHome = userWorkspaceFactory.forOwner(userId);
    final Path realSourcePath;
    final Path realWorkspaceRoot;
    try {
      realSourcePath = sourcePath.toRealPath();
      realWorkspaceRoot = userHome.workspace().toRealPath();
    } catch (IOException e) {
      throw new IllegalArgumentException("无法解析路径：" + path);
    }
    if (!realSourcePath.equals(realWorkspaceRoot)
        && !realSourcePath.startsWith(realWorkspaceRoot)) {
      throw new IllegalArgumentException("只能发布当前用户 workspace 目录内的文件或文件夹。");
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
        UriComponentsBuilder.fromUriString(shareBaseUrl)
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
        throw new IllegalArgumentException("public 的 ttl 必须大于 0 且不超过 30d。");
      }
      return Instant.now().plus(resolvedTtl);
    }
    if (Strings.isNullOrEmpty(ttl)) {
      return null;
    }
    final var parsed = parseTtl(ttl);
    if (parsed.isZero() || parsed.isNegative()) {
      throw new IllegalArgumentException("ttl 必须大于 0。");
    }
    return Instant.now().plus(parsed);
  }

  private Duration parseTtl(final String ttl) {
    try {
      return DurationStyle.detectAndParse(ttl);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("ttl 格式无效，请使用如 30s、10m、2h、1d 的格式。");
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
