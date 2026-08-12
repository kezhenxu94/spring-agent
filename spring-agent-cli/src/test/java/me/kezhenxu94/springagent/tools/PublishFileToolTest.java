package me.kezhenxu94.springagent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import me.kezhenxu94.springagent.bot.storage.FileSystemStorageProperties;
import me.kezhenxu94.springagent.bot.storage.FileSystemStorageService;
import me.kezhenxu94.springagent.dao.models.PublishedResource;
import me.kezhenxu94.springagent.dao.repo.PublishedResourceRepo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;

class PublishFileToolTest {

  private static final String USER_ID = "user-1";

  @TempDir Path storageRoot;

  FileSystemStorageService storageService;
  UserWorkspaceFactory userWorkspaceFactory;
  Map<String, PublishedResource> repoBackingStore;
  PublishedResourceRepo publishedResourceRepo;
  PublishFileTool tool;

  @BeforeEach
  void setUp() {
    final var props =
        FileSystemStorageProperties.builder()
            .location(storageRoot.toString())
            .baseUrl("http://localhost:8080")
            .cdnUrl("http://localhost:8080")
            .build();
    storageService = new FileSystemStorageService(props);
    storageService.init();
    userWorkspaceFactory = new UserWorkspaceFactory(props);

    repoBackingStore = new HashMap<>();
    publishedResourceRepo = mock(PublishedResourceRepo.class);
    when(publishedResourceRepo.save(any()))
        .thenAnswer(
            inv -> {
              final PublishedResource resource = inv.getArgument(0);
              repoBackingStore.put(resource.getId(), resource);
              return resource;
            });
    when(publishedResourceRepo.findById(any()))
        .thenAnswer(inv -> Optional.ofNullable(repoBackingStore.get((String) inv.getArgument(0))));
    doAnswer(inv -> repoBackingStore.remove((String) inv.getArgument(0)))
        .when(publishedResourceRepo)
        .deleteById(any());

    tool =
        new PublishFileTool(
            storageService, userWorkspaceFactory, publishedResourceRepo, "http://localhost:8080");
  }

  private ToolContext contextFor(final String userId) {
    return new ToolContext(userId == null ? Map.of() : Map.of("userId", userId));
  }

  private Path workspaceOf(final String userId) throws IOException {
    return userWorkspaceFactory.forOwner(userId).workspace();
  }

  private void assumeSymlinksSupported(final Path link, final Path target) {
    try {
      Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException | IOException e) {
      Assumptions.abort("symlinks not supported on this filesystem/runner");
    }
  }

  // -------------------- happy paths --------------------

  @Test
  @DisplayName("publishes a single file as public with the default 1-day ttl")
  void publishesSingleFilePublicWithDefaultTtl() throws IOException {
    final var file = workspaceOf(USER_ID).resolve("report.txt");
    Files.writeString(file, "hello world");

    final var result = tool.publishFile(file.toString(), "public", null, contextFor(USER_ID));

    assertThat(result).startsWith("已发布，链接：http://localhost:8080/share/public/" + USER_ID + "/");
    assertThat(repoBackingStore).hasSize(1);
    final var resource = repoBackingStore.values().iterator().next();
    assertThat(resource.getVisibility()).isEqualTo(PublishedResource.Visibility.PUBLIC);
    assertThat(resource.isDirectory()).isFalse();
    assertThat(resource.getEntryFilename()).isEqualTo("report.txt");
    assertThat(resource.getExpiresAt())
        .isAfter(Instant.now())
        .isBefore(Instant.now().plus(Duration.ofDays(1)).plusSeconds(10));

    final var stored =
        storageRoot
            .resolve("public")
            .resolve(USER_ID)
            .resolve(resource.getId())
            .resolve("report.txt");
    assertThat(Files.readString(stored)).isEqualTo("hello world");
  }

  @Test
  @DisplayName("publishes a directory, preserving structure, using index.html as the entry point")
  void publishesDirectoryWithIndexHtml() throws IOException {
    final var dir = Files.createDirectories(workspaceOf(USER_ID).resolve("site"));
    Files.writeString(dir.resolve("index.html"), "<html></html>");
    Files.writeString(dir.resolve("style.css"), "body{}");

    final var result = tool.publishFile(dir.toString(), "internal", null, contextFor(USER_ID));

    assertThat(result).contains("永久有效");
    final var resource = repoBackingStore.values().iterator().next();
    assertThat(resource.isDirectory()).isTrue();
    assertThat(resource.getEntryFilename()).isEqualTo("index.html");
    assertThat(resource.getExpiresAt()).isNull();

    final var storedDir =
        storageRoot.resolve("internal").resolve(USER_ID).resolve(resource.getId());
    assertThat(Files.readString(storedDir.resolve("index.html"))).isEqualTo("<html></html>");
    assertThat(Files.readString(storedDir.resolve("style.css"))).isEqualTo("body{}");
  }

  @Test
  @DisplayName("directory without index.html has no default entry point")
  void publishesDirectoryWithoutIndexHtml() throws IOException {
    final var dir = Files.createDirectories(workspaceOf(USER_ID).resolve("data"));
    Files.writeString(dir.resolve("data.json"), "{}");

    tool.publishFile(dir.toString(), "internal", null, contextFor(USER_ID));

    final var resource = repoBackingStore.values().iterator().next();
    assertThat(resource.getEntryFilename()).isNull();
  }

  // -------------------- workspace containment / traversal --------------------

  @Test
  @DisplayName(
      "rejects a file outside the workspace but still inside the user's home (e.g. memories/)")
  void rejectsPathOutsideWorkspaceButInsideUserHome() throws IOException {
    final var userHome = userWorkspaceFactory.forOwner(USER_ID);
    final var file = userHome.memories().resolve("secret.txt");
    Files.writeString(file, "secret");

    final var result = tool.publishFile(file.toString(), "public", null, contextFor(USER_ID));

    assertThat(result).contains("只能发布当前用户 workspace 目录内");
    assertThat(repoBackingStore).isEmpty();
  }

  @Test
  @DisplayName("rejects a file entirely outside any user's storage location")
  void rejectsPathOutsideUserHomeEntirely(@TempDir final Path outside) throws IOException {
    final var file = outside.resolve("other.txt");
    Files.writeString(file, "nope");

    final var result = tool.publishFile(file.toString(), "public", null, contextFor(USER_ID));

    assertThat(result).contains("只能发布当前用户 workspace 目录内");
  }

  @Test
  @DisplayName("rejects another user's workspace file")
  void rejectsAnotherUsersWorkspaceFile() throws IOException {
    final var file = workspaceOf("user-2").resolve("file.txt");
    Files.writeString(file, "x");

    final var result = tool.publishFile(file.toString(), "public", null, contextFor(USER_ID));

    assertThat(result).contains("只能发布当前用户 workspace 目录内");
  }

  @Test
  @DisplayName("rejects a path argument using ../ segments to climb out of the workspace")
  void rejectsDotDotTraversalOutOfWorkspace() throws IOException {
    final var userHome = userWorkspaceFactory.forOwner(USER_ID);
    final var workspace = workspaceOf(USER_ID);
    final var outsideWorkspaceFile = userHome.root().resolve("outside.txt");
    Files.writeString(outsideWorkspaceFile, "nope");
    final var traversalPath = workspace.resolve("../outside.txt").toString();

    final var result = tool.publishFile(traversalPath, "public", null, contextFor(USER_ID));

    assertThat(result).contains("只能发布当前用户 workspace 目录内");
  }

  @Test
  @DisplayName("rejects a symlink inside the workspace that points outside of it")
  void rejectsSymlinkEscapeAtTopLevel(@TempDir final Path outside) throws IOException {
    final var secret = outside.resolve("secret.txt");
    Files.writeString(secret, "top secret");
    final var link = workspaceOf(USER_ID).resolve("link.txt");
    assumeSymlinksSupported(link, secret);

    final var result = tool.publishFile(link.toString(), "public", null, contextFor(USER_ID));

    assertThat(result).contains("只能发布当前用户 workspace 目录内");
    assertThat(repoBackingStore).isEmpty();
  }

  @Test
  @DisplayName("directory publish skips symlinked entries that point outside the workspace")
  void skipsSymlinkedFilesInsideDirectory(@TempDir final Path outside) throws IOException {
    final var secret = outside.resolve("secret.txt");
    Files.writeString(secret, "top secret");
    final var dir = Files.createDirectories(workspaceOf(USER_ID).resolve("bundle"));
    Files.writeString(dir.resolve("index.html"), "<html></html>");
    final var link = dir.resolve("leak.txt");
    assumeSymlinksSupported(link, secret);

    final var result = tool.publishFile(dir.toString(), "public", null, contextFor(USER_ID));

    assertThat(result).startsWith("已发布");
    final var resource = repoBackingStore.values().iterator().next();
    final var storedDir = storageRoot.resolve("public").resolve(USER_ID).resolve(resource.getId());
    assertThat(Files.exists(storedDir.resolve("index.html"))).isTrue();
    assertThat(Files.exists(storedDir.resolve("leak.txt"))).isFalse();
  }

  @Test
  @DisplayName("rejects a broken/non-existent path")
  void rejectsNonExistentPath() {
    final var missing = storageRoot.resolve("does-not-exist.txt").toString();

    final var result = tool.publishFile(missing, "public", null, contextFor(USER_ID));

    assertThat(result).contains("不存在");
  }

  @Test
  @DisplayName("rejects when the caller's identity cannot be resolved")
  void rejectsMissingUserId() {
    assertThatThrownBy(() -> tool.publishFile("/tmp/x", "public", null, contextFor(null)))
        .isInstanceOf(IllegalStateException.class);
  }

  // -------------------- visibility / ttl validation --------------------

  @Test
  @DisplayName("rejects an unknown visibility value")
  void rejectsInvalidVisibility() throws IOException {
    final var file = workspaceOf(USER_ID).resolve("a.txt");
    Files.writeString(file, "a");

    final var result = tool.publishFile(file.toString(), "everyone", null, contextFor(USER_ID));

    assertThat(result).isEqualTo("错误：visibility 必须为 internal 或 public。");
  }

  @Test
  @DisplayName("rejects a public ttl exceeding the 30-day cap")
  void rejectsPublicTtlExceedingMax() throws IOException {
    final var file = workspaceOf(USER_ID).resolve("a.txt");
    Files.writeString(file, "a");

    final var result = tool.publishFile(file.toString(), "public", "31d", contextFor(USER_ID));

    assertThat(result).contains("public 的 ttl 必须大于 0 且不超过 30d");
    assertThat(repoBackingStore).isEmpty();
  }

  @Test
  @DisplayName("rejects an unparsable ttl string")
  void rejectsInvalidTtlFormat() throws IOException {
    final var file = workspaceOf(USER_ID).resolve("a.txt");
    Files.writeString(file, "a");

    final var result = tool.publishFile(file.toString(), "public", "banana", contextFor(USER_ID));

    assertThat(result).contains("ttl 格式无效");
  }

  @Test
  @DisplayName("accepts sub-day duration-style ttl values (30m, 2h) for public")
  void acceptsDurationStyleTtl() throws IOException {
    final var file = workspaceOf(USER_ID).resolve("a.txt");
    Files.writeString(file, "a");

    tool.publishFile(file.toString(), "public", "30m", contextFor(USER_ID));

    final var resource = repoBackingStore.values().iterator().next();
    assertThat(resource.getExpiresAt())
        .isAfter(Instant.now().plus(Duration.ofMinutes(29)))
        .isBefore(Instant.now().plus(Duration.ofMinutes(31)));
  }

  @Test
  @DisplayName("internal without ttl never expires")
  void internalWithoutTtlNeverExpires() throws IOException {
    final var file = workspaceOf(USER_ID).resolve("a.txt");
    Files.writeString(file, "a");

    tool.publishFile(file.toString(), "internal", null, contextFor(USER_ID));

    assertThat(repoBackingStore.values().iterator().next().getExpiresAt()).isNull();
  }

  @Test
  @DisplayName("internal rejects a zero-length ttl")
  void internalTtlZeroRejected() throws IOException {
    final var file = workspaceOf(USER_ID).resolve("a.txt");
    Files.writeString(file, "a");

    final var result = tool.publishFile(file.toString(), "internal", "0s", contextFor(USER_ID));

    assertThat(result).contains("ttl 必须大于 0");
  }

  // -------------------- unpublish --------------------

  @Test
  @DisplayName("unpublish deletes both the stored files and the record")
  void unpublishDeletesFilesAndRecord() throws IOException {
    final var file = workspaceOf(USER_ID).resolve("a.txt");
    Files.writeString(file, "a");
    tool.publishFile(file.toString(), "public", null, contextFor(USER_ID));
    final var token = repoBackingStore.keySet().iterator().next();

    final var result = tool.unpublishFile(token, contextFor(USER_ID));

    assertThat(result).isEqualTo("已取消发布：" + token);
    assertThat(repoBackingStore).isEmpty();
    assertThat(Files.exists(storageRoot.resolve("public").resolve(USER_ID).resolve(token)))
        .isFalse();
  }

  @Test
  @DisplayName("unpublish rejects a caller who does not own the resource")
  void unpublishRejectsNonOwner() throws IOException {
    final var file = workspaceOf(USER_ID).resolve("a.txt");
    Files.writeString(file, "a");
    tool.publishFile(file.toString(), "public", null, contextFor(USER_ID));
    final var token = repoBackingStore.keySet().iterator().next();

    final var result = tool.unpublishFile(token, contextFor("someone-else"));

    assertThat(result).contains("只能取消自己发布的内容");
    assertThat(repoBackingStore).containsKey(token);
  }

  @Test
  @DisplayName("unpublish rejects an unknown token")
  void unpublishRejectsUnknownToken() {
    final var result = tool.unpublishFile("does-not-exist", contextFor(USER_ID));

    assertThat(result).contains("未找到该发布记录");
  }

  // -------------------- renew --------------------

  @Test
  @DisplayName("renew extends expiresAt from now")
  void renewExtendsExpiry() throws IOException {
    final var file = workspaceOf(USER_ID).resolve("a.txt");
    Files.writeString(file, "a");
    tool.publishFile(file.toString(), "public", "1s", contextFor(USER_ID));
    final var token = repoBackingStore.keySet().iterator().next();

    final var result = tool.renewPublishedFile(token, "10d", contextFor(USER_ID));

    assertThat(result).contains("已续期");
    assertThat(repoBackingStore.get(token).getExpiresAt())
        .isAfter(Instant.now().plus(Duration.ofDays(9)));
  }

  @Test
  @DisplayName("renew rejects a caller who does not own the resource")
  void renewRejectsNonOwner() throws IOException {
    final var file = workspaceOf(USER_ID).resolve("a.txt");
    Files.writeString(file, "a");
    tool.publishFile(file.toString(), "internal", null, contextFor(USER_ID));
    final var token = repoBackingStore.keySet().iterator().next();

    final var result = tool.renewPublishedFile(token, "1d", contextFor("someone-else"));

    assertThat(result).contains("只能续期自己发布的内容");
  }

  @Test
  @DisplayName("renew rejects an unknown token")
  void renewRejectsUnknownToken() {
    final var result = tool.renewPublishedFile("missing", "1d", contextFor(USER_ID));

    assertThat(result).contains("未找到该发布记录");
  }

  @Test
  @DisplayName("renew applies the same ttl validation rules as publish")
  void renewValidatesTtlLikePublish() throws IOException {
    final var file = workspaceOf(USER_ID).resolve("a.txt");
    Files.writeString(file, "a");
    tool.publishFile(file.toString(), "public", null, contextFor(USER_ID));
    final var token = repoBackingStore.keySet().iterator().next();

    final var result = tool.renewPublishedFile(token, "60d", contextFor(USER_ID));

    assertThat(result).contains("public 的 ttl 必须大于 0 且不超过 30d");
  }

  // -------------------- update --------------------

  @Test
  @DisplayName(
      "update (default mode) replaces the content of a single-file resource while keeping the URL"
          + " and expiry")
  void updateReplacesSingleFileContentKeepingUrlAndExpiry() throws IOException {
    final var file = workspaceOf(USER_ID).resolve("a.txt");
    Files.writeString(file, "v1");
    final var publishResult =
        tool.publishFile(file.toString(), "public", "10d", contextFor(USER_ID));
    final var token = repoBackingStore.keySet().iterator().next();
    final var originalUrl = publishResult.split("链接：")[1].split(" ")[0];
    final var originalExpiresAt = repoBackingStore.get(token).getExpiresAt();

    // Even though the new source file has a different name, the stored filename (and therefore
    // the URL, which embeds it as the last path segment) must stay exactly as it was.
    final var updated = workspaceOf(USER_ID).resolve("b.txt");
    Files.writeString(updated, "v2");
    final var result =
        tool.updatePublishedFile(token, updated.toString(), null, null, contextFor(USER_ID));

    assertThat(result)
        .isEqualTo("已更新，链接保持不变：" + originalUrl + " " + "过期时间：" + originalExpiresAt + "。");
    final var resource = repoBackingStore.get(token);
    assertThat(resource.getEntryFilename()).isEqualTo("a.txt");
    assertThat(resource.getExpiresAt()).isEqualTo(originalExpiresAt);

    final var stored =
        storageRoot.resolve("public").resolve(USER_ID).resolve(token).resolve("a.txt");
    assertThat(Files.readString(stored)).isEqualTo("v2");
    assertThat(
            Files.exists(
                storageRoot.resolve("public").resolve(USER_ID).resolve(token).resolve("b.txt")))
        .isFalse();
  }

  @Test
  @DisplayName("mode=update overlays new files onto a directory without removing untouched ones")
  void updateModeMergesWithoutRemovingStaleFiles() throws IOException {
    final var dir = Files.createDirectories(workspaceOf(USER_ID).resolve("site-v1"));
    Files.writeString(dir.resolve("index.html"), "v1");
    Files.writeString(dir.resolve("keep.txt"), "still here");
    final var publishResult =
        tool.publishFile(dir.toString(), "internal", null, contextFor(USER_ID));
    final var token = repoBackingStore.keySet().iterator().next();
    final var originalUrl = publishResult.split("链接：")[1].split(" ")[0];

    final var dir2 = Files.createDirectories(workspaceOf(USER_ID).resolve("site-v2"));
    Files.writeString(dir2.resolve("index.html"), "v2");
    final var updateResult =
        tool.updatePublishedFile(token, dir2.toString(), "update", null, contextFor(USER_ID));

    assertThat(updateResult).contains(originalUrl);
    final var storedDir = storageRoot.resolve("internal").resolve(USER_ID).resolve(token);
    assertThat(Files.readString(storedDir.resolve("index.html"))).isEqualTo("v2");
    // untouched by the new content, so it must survive a merge update
    assertThat(Files.readString(storedDir.resolve("keep.txt"))).isEqualTo("still here");
  }

  @Test
  @DisplayName("mode=replace wipes stale files that aren't in the new directory content")
  void updateReplaceModeRemovesStaleFiles() throws IOException {
    final var dir = Files.createDirectories(workspaceOf(USER_ID).resolve("site-v1"));
    Files.writeString(dir.resolve("index.html"), "v1");
    Files.writeString(dir.resolve("extra.txt"), "stale");
    tool.publishFile(dir.toString(), "internal", null, contextFor(USER_ID));
    final var token = repoBackingStore.keySet().iterator().next();

    final var dir2 = Files.createDirectories(workspaceOf(USER_ID).resolve("site-v2"));
    Files.writeString(dir2.resolve("index.html"), "v2");
    tool.updatePublishedFile(token, dir2.toString(), "replace", null, contextFor(USER_ID));

    final var storedDir = storageRoot.resolve("internal").resolve(USER_ID).resolve(token);
    assertThat(Files.readString(storedDir.resolve("index.html"))).isEqualTo("v2");
    assertThat(Files.exists(storedDir.resolve("extra.txt"))).isFalse();
  }

  @Test
  @DisplayName("mode=update rejects switching between file and directory content")
  void updateModeRejectsTypeMismatch() throws IOException {
    final var dir = Files.createDirectories(workspaceOf(USER_ID).resolve("site"));
    Files.writeString(dir.resolve("index.html"), "v1");
    tool.publishFile(dir.toString(), "internal", null, contextFor(USER_ID));
    final var token = repoBackingStore.keySet().iterator().next();

    final var file = workspaceOf(USER_ID).resolve("single.txt");
    Files.writeString(file, "not a directory");
    final var result =
        tool.updatePublishedFile(token, file.toString(), "update", null, contextFor(USER_ID));

    assertThat(result).contains("mode=update 时，新内容的类型");
    // the original content must be untouched after a rejected update
    assertThat(repoBackingStore.get(token).isDirectory()).isTrue();
  }

  @Test
  @DisplayName("mode=replace allows switching content type from directory to a single file")
  void updateReplaceModeAllowsTypeChange() throws IOException {
    final var dir = Files.createDirectories(workspaceOf(USER_ID).resolve("site"));
    Files.writeString(dir.resolve("index.html"), "v1");
    tool.publishFile(dir.toString(), "internal", null, contextFor(USER_ID));
    final var token = repoBackingStore.keySet().iterator().next();

    final var file = workspaceOf(USER_ID).resolve("single.txt");
    Files.writeString(file, "now a file");
    final var result =
        tool.updatePublishedFile(token, file.toString(), "replace", null, contextFor(USER_ID));

    assertThat(result).startsWith("已更新");
    final var resource = repoBackingStore.get(token);
    assertThat(resource.isDirectory()).isFalse();
    assertThat(resource.getEntryFilename()).isEqualTo("single.txt");
  }

  @Test
  @DisplayName("update rejects an unrecognized mode value")
  void updateRejectsInvalidMode() throws IOException {
    final var file = workspaceOf(USER_ID).resolve("a.txt");
    Files.writeString(file, "v1");
    tool.publishFile(file.toString(), "public", null, contextFor(USER_ID));
    final var token = repoBackingStore.keySet().iterator().next();

    final var result =
        tool.updatePublishedFile(
            token, file.toString(), "overwrite-everything", null, contextFor(USER_ID));

    assertThat(result).isEqualTo("错误：mode 必须为 update 或 replace。");
  }

  @Test
  @DisplayName("update with a new ttl recalculates expiresAt from now")
  void updateWithNewTtlRecalculatesExpiry() throws IOException {
    final var file = workspaceOf(USER_ID).resolve("a.txt");
    Files.writeString(file, "v1");
    tool.publishFile(file.toString(), "public", "1s", contextFor(USER_ID));
    final var token = repoBackingStore.keySet().iterator().next();

    tool.updatePublishedFile(token, file.toString(), null, "10d", contextFor(USER_ID));

    assertThat(repoBackingStore.get(token).getExpiresAt())
        .isAfter(Instant.now().plus(Duration.ofDays(9)));
  }

  @Test
  @DisplayName("update rejects a caller who does not own the resource")
  void updateRejectsNonOwner() throws IOException {
    final var file = workspaceOf(USER_ID).resolve("a.txt");
    Files.writeString(file, "v1");
    tool.publishFile(file.toString(), "internal", null, contextFor(USER_ID));
    final var token = repoBackingStore.keySet().iterator().next();

    final var otherFile = workspaceOf("someone-else").resolve("b.txt");
    Files.writeString(otherFile, "v2");
    final var result =
        tool.updatePublishedFile(
            token, otherFile.toString(), null, null, contextFor("someone-else"));

    assertThat(result).contains("只能更新自己发布的内容");
  }

  @Test
  @DisplayName("update rejects an unknown token")
  void updateRejectsUnknownToken() throws IOException {
    final var file = workspaceOf(USER_ID).resolve("a.txt");
    Files.writeString(file, "v1");

    final var result =
        tool.updatePublishedFile("missing", file.toString(), null, null, contextFor(USER_ID));

    assertThat(result).contains("未找到该发布记录");
  }

  @Test
  @DisplayName("update rejects new content outside the caller's workspace")
  void updateRejectsPathOutsideWorkspace(@TempDir final Path outside) throws IOException {
    final var file = workspaceOf(USER_ID).resolve("a.txt");
    Files.writeString(file, "v1");
    tool.publishFile(file.toString(), "public", null, contextFor(USER_ID));
    final var token = repoBackingStore.keySet().iterator().next();

    final var outsideFile = outside.resolve("evil.txt");
    Files.writeString(outsideFile, "evil");
    final var result =
        tool.updatePublishedFile(token, outsideFile.toString(), null, null, contextFor(USER_ID));

    assertThat(result).contains("只能发布当前用户 workspace 目录内");
  }

  @Test
  @DisplayName("update rejects an invalid ttl and leaves the existing content untouched")
  void updateRejectsInvalidTtl() throws IOException {
    final var file = workspaceOf(USER_ID).resolve("a.txt");
    Files.writeString(file, "v1");
    tool.publishFile(file.toString(), "public", "10d", contextFor(USER_ID));
    final var token = repoBackingStore.keySet().iterator().next();
    final var originalExpiresAt = repoBackingStore.get(token).getExpiresAt();

    final var updated = workspaceOf(USER_ID).resolve("b.txt");
    Files.writeString(updated, "v2");
    final var result =
        tool.updatePublishedFile(token, updated.toString(), null, "60d", contextFor(USER_ID));

    assertThat(result).contains("public 的 ttl 必须大于 0 且不超过 30d");
    assertThat(repoBackingStore.get(token).getExpiresAt()).isEqualTo(originalExpiresAt);
    assertThat(repoBackingStore.get(token).getEntryFilename()).isEqualTo("a.txt");
  }
}
