package me.kezhenxu94.springagent.appweb.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.appweb.config.WebMessages;
import me.kezhenxu94.springagent.appweb.security.WebUser;
import me.kezhenxu94.springagent.core.tools.HomeDir;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Files a person hands the agent.
 *
 * <p>They land in the caller's {@code artifacts} directory — the same place a file sent to the bot
 * on Feishu ends up, deliberately, so somebody's files are in one place whichever way they arrived
 * and the agent's file tools find them without being told where to look. From there the run reads
 * them with the tools it already has; nothing here parses or inspects the content.
 *
 * <p>Whose artifacts is decided by the authenticated principal and nothing else. The conversation
 * in the path is checked to be theirs as well, but it only says which run will be told about the
 * file — it never selects the directory, because a request that could name someone else's
 * conversation would otherwise be a request that could write into their home.
 */
@Slf4j
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class FileController {

  /**
   * Enough for a document or a log; a bigger payload belongs somewhere the agent can be pointed at.
   */
  private static final long MAX_BYTES = 32L * 1024 * 1024;

  /** A run is given a handful of files to look at, not a directory tree. */
  private static final int MAX_FILES = 10;

  private final ChatSessions sessions;
  private final UserWorkspaceFactory workspaces;
  private final WebMessages messages;

  @PostMapping("/{id}/files")
  public Map<String, Object> upload(
      @AuthenticationPrincipal final OAuth2User principal,
      @PathVariable final String id,
      @RequestParam("files") final List<MultipartFile> files) {

    final var user = ChatController.user(principal);
    // Ownership of the conversation, even though it does not choose the directory: uploading into a
    // thread that is not yours would put the file in front of a run you cannot see.
    sessions.ownedBy(id, user).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

    if (files == null || files.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, messages.get("upload-empty"));
    }
    if (files.size() > MAX_FILES) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, messages.get("upload-too-many", MAX_FILES));
    }

    final var home = workspaces.forRequest(user.id(), null, user.tenantId());
    final var saved = new ArrayList<Map<String, Object>>();
    for (final var file : files) {
      if (file.isEmpty()) {
        continue;
      }
      if (file.getSize() > MAX_BYTES) {
        throw new ResponseStatusException(
            HttpStatus.PAYLOAD_TOO_LARGE,
            messages.get("upload-too-large", file.getOriginalFilename(), MAX_BYTES / 1024 / 1024));
      }
      saved.add(store(file, home, user));
    }
    if (saved.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, messages.get("upload-empty"));
    }
    return Map.of("files", saved);
  }

  private Map<String, Object> store(
      final MultipartFile file, final HomeDir home, final WebUser user) {
    try {
      final var destination = free(artifactPath(file.getOriginalFilename(), home));
      try (var in = file.getInputStream()) {
        Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
      }
      log.info(
          "Stored {} ({} bytes) in the artifacts of {}",
          destination.getFileName(),
          file.getSize(),
          user.id());
      final var out = new LinkedHashMap<String, Object>();
      out.put("name", destination.getFileName().toString());
      out.put("size", file.getSize());
      return out;
    } catch (final IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    } catch (final IOException e) {
      log.warn("Could not store an upload from {}", user.id(), e);
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, messages.get("upload-failed"), e);
    }
  }

  /**
   * Where {@code fileName} may be written, and nowhere else.
   *
   * <p>The name comes from a browser, so it is reduced to a bare basename first — that alone
   * defeats {@code ../} and an absolute path, since neither survives {@code getFileName}. The
   * containment check afterwards is what actually holds, and stands as an assertion that the
   * reduction did what it claims. The same shape as {@code FeishuFiles.artifactPath}, restated
   * because that class lives in a module this one deliberately does not depend on.
   */
  static Path artifactPath(final String fileName, final HomeDir home) throws IOException {
    if (fileName == null || fileName.isBlank()) {
      throw new IllegalArgumentException("A file must have a name");
    }
    final String basename;
    try {
      final var nameOnly = Path.of(fileName).getFileName();
      basename = nameOnly == null ? null : nameOnly.toString();
    } catch (final InvalidPathException e) {
      throw new IllegalArgumentException("That file name cannot be used: " + fileName, e);
    }
    if (basename == null || basename.isBlank() || ".".equals(basename) || "..".equals(basename)) {
      throw new IllegalArgumentException("That file name cannot be used: " + fileName);
    }
    final var artifacts = home.artifacts().normalize();
    final var destination = artifacts.resolve(basename).normalize();
    if (!destination.startsWith(artifacts)) {
      throw new IllegalArgumentException("That file name cannot be used: " + fileName);
    }
    return destination;
  }

  /**
   * The first name near {@code destination} that is not taken.
   *
   * <p>Uploading {@code report.pdf} twice keeps both. Overwriting would be the quiet destruction of
   * something the agent may have been told about an hour ago, and this is a person's own storage.
   */
  private static Path free(final Path destination) {
    if (!Files.exists(destination)) {
      return destination;
    }
    final var name = destination.getFileName().toString();
    final var dot = name.lastIndexOf('.');
    final var stem = dot <= 0 ? name : name.substring(0, dot);
    final var extension = dot <= 0 ? "" : name.substring(dot);
    for (var n = 2; n < 1000; n++) {
      final var candidate = destination.resolveSibling(stem + "-" + n + extension);
      if (!Files.exists(candidate)) {
        return candidate;
      }
    }
    throw new IllegalArgumentException("Too many files are already called " + name);
  }
}
