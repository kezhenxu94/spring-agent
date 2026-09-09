package me.kezhenxu94.springagent.core.memory;

import com.google.common.util.concurrent.Striped;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import org.springframework.util.StringUtils;

/**
 * The file operations behind the memory tools, one memory root at a time.
 *
 * <p>Forked from {@code org.springaicommunity.agent.tools.AutoMemoryTools}, which does the same
 * things against a single directory fixed at construction. Everything here takes the root as an
 * argument instead, because which root a call means is decided per call by {@link MemoryScopes} —
 * that is the whole point of the fork. The formatting of a listing, of a numbered file and of an
 * edit snippet is upstream's, deliberately unchanged: it is what the tool descriptions and the
 * memory prompt describe, and a model that has learnt one shape should not have to learn another.
 *
 * <p>What did change, and why, is in the comments on {@link #resolveSafe}, {@link #delete} and the
 * caps below. Each is a consequence of a root now being shared: upstream could assume that whoever
 * could reach a memory directory owned everything in it.
 */
@RequiredArgsConstructor
class MemoryFiles {

  /**
   * How much of one scope a single call may return, and how deep a listing goes.
   *
   * <p>Upstream needed no cap: a memory directory held what one person had accumulated about
   * themselves. A tenant's holds what a company has, written by everybody, and an uncapped read of
   * it is a way to spend every other user's context. The line figure is the one the prompt already
   * uses for {@code MEMORY.md}, so the model is being held to a limit it was already told about,
   * and the notice names {@code viewRange} so the rest is one call away rather than lost.
   */
  private static final int MAX_LINES = 200;

  private static final int MAX_CHARS = 8192;

  /**
   * One lock per memory file, held across a whole read-modify-write.
   *
   * <p>New with shared scopes. {@code MemoryInsert} and {@code MemoryStrReplace} read a file,
   * change it in memory and write it back, which upstream could leave unguarded because the only
   * writer to a personal memory directory was that person's own runs, serialised by their own
   * turn-taking. A group's {@code MEMORY.md} has as many concurrent writers as the group has
   * members, and two index lines appended at once would leave one of them gone with both calls
   * reporting success.
   *
   * <p>The honest bound: this is per JVM. Two instances of the application over one shared volume
   * still race, and nothing here can fix that — a file lock would, at the cost of failing on the
   * network filesystems such a deployment is most likely to be using. Striped rather than a map so
   * the number of locks is bounded; collisions cost a wait, never a wrong answer.
   */
  private static final Striped<Lock> LOCKS = Striped.lock(64);

  private final CoreMessages messages;

  /**
   * Resolves a path the model supplied, relative to one scope's memory root.
   *
   * <p>Upstream stops after {@code normalize()}, which settles {@code ..} but says nothing about
   * symlinks — and a link inside a memory directory is something the same user can plant with
   * {@code Write} or with the shell, both of which are allowed into the whole home. While every
   * root belonged to one person that was bounded: the link and the memories had the same owner. A
   * shared root is not: a link in a group's {@code memories/} pointing at one member's home turns
   * {@code MemoryView(scope="group")} into a read of that member's files for everyone in the chat,
   * and a create through it into a write.
   *
   * <p>So after normalizing, the deepest part of the path that actually exists is resolved to its
   * real location and re-tested against the root's. That part is the only part that can lie — a
   * path that does not exist yet cannot be a link — and it covers the target itself when the target
   * is the link. Writes then open with {@link LinkOption#NOFOLLOW_LINKS}, so a link planted between
   * this check and the write fails the write rather than being followed.
   *
   * <p>{@code FileSystemTools} has the same weakness and this does not close it; that one is
   * upstream's to fix, and the two should not be conflated in review.
   */
  static Path resolveSafe(final Path root, final String relativePath) {
    final var base = root.toAbsolutePath().normalize();
    if (!StringUtils.hasText(relativePath) || "/".equals(relativePath)) {
      return base;
    }
    final var userPath = Paths.get(relativePath);
    if (userPath.isAbsolute()) {
      throw new SecurityException("absolute path: " + relativePath);
    }
    final var resolved = base.resolve(userPath).normalize();
    if (!resolved.startsWith(base)) {
      throw new SecurityException("escapes the memory root: " + relativePath);
    }
    // Only worth asking where the root exists. Where it does not, nothing under it exists either,
    // so there is no link to follow — and real-pathing an absent root would walk out of it and
    // refuse every path.
    if (Files.exists(base)) {
      try {
        final var baseReal = base.toRealPath();
        var existing = resolved;
        while (!existing.equals(base) && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
          existing = existing.getParent();
        }
        if (!existing.toRealPath().startsWith(baseReal)) {
          throw new SecurityException("leaves the memory root through a link: " + relativePath);
        }
      } catch (final IOException e) {
        throw new SecurityException("cannot be checked against the memory root: " + relativePath);
      }
    }
    return resolved;
  }

  /** A file with line numbers, or a directory listed two levels deep, both capped. */
  String view(final Path root, final String path, final String viewRange) throws IOException {
    final var target = resolveSafe(root, path);
    if (!Files.exists(target)) {
      return messages.get("memory-not-found", display(path));
    }
    return Files.isDirectory(target)
        ? listDirectory(target, display(path))
        : readFile(target, viewRange);
  }

  /** Whether that path is there at all, used to work out which scope a call meant. */
  static boolean exists(final Path root, final String path) {
    try {
      return Files.exists(resolveSafe(root, path));
    } catch (final SecurityException e) {
      return false;
    }
  }

  String create(final Path root, final String path, final String fileText) throws IOException {
    final var target = resolveSafe(root, path);
    if (Files.exists(target)) {
      return messages.get("memory-exists", display(path));
    }
    final var lock = LOCKS.get(target.toString());
    lock.lock();
    try {
      // The one place a memory root is brought into existence, and only once a write has been
      // allowed this far: a group's memories/ appears when the group gets its first memory, put
      // there by the write that puts it, never by somebody who only looked.
      Files.createDirectories(target.getParent() == null ? root : target.getParent());
      final var text = fileText == null ? "" : fileText;
      write(target, text);
      return messages.get("memory-created", display(path), String.valueOf(text.length()));
    } finally {
      lock.unlock();
    }
  }

  String strReplace(final Path root, final String path, final String oldStr, final String newStr)
      throws IOException {
    final var target = resolveSafe(root, path);
    final var lock = LOCKS.get(target.toString());
    lock.lock();
    try {
      if (!Files.exists(target)) {
        return messages.get("memory-not-found", display(path));
      }
      if (Files.isDirectory(target)) {
        return messages.get("memory-not-a-file", display(path));
      }
      if (!StringUtils.hasText(oldStr)) {
        return messages.get("memory-old-str-missing", display(path));
      }
      final var content = Files.readString(target, StandardCharsets.UTF_8);
      final var occurrences = countOccurrences(content, oldStr);
      if (occurrences == 0) {
        return messages.get("memory-old-str-missing", display(path));
      }
      if (occurrences > 1) {
        return messages.get("memory-old-str-ambiguous", String.valueOf(occurrences), display(path));
      }
      final var replacement = newStr == null ? "" : newStr;
      final var updated = replaceFirst(content, oldStr, replacement);
      write(target, updated);
      return StringUtils.hasText(replacement)
          ? messages.get("memory-edited", display(path), generateEditSnippet(updated, replacement))
          : messages.get("memory-deleted-text", display(path));
    } finally {
      lock.unlock();
    }
  }

  String insert(
      final Path root, final String path, final Integer insertLine, final String insertText)
      throws IOException {
    final var target = resolveSafe(root, path);
    final var lock = LOCKS.get(target.toString());
    lock.lock();
    try {
      if (!Files.exists(target)) {
        return messages.get("memory-not-found", display(path));
      }
      if (Files.isDirectory(target)) {
        return messages.get("memory-not-a-file", display(path));
      }
      if (insertLine == null || insertLine < 0) {
        return messages.get("memory-insert-line-invalid");
      }
      final var lines = Files.readAllLines(target, StandardCharsets.UTF_8);
      if (insertLine > lines.size()) {
        return messages.get(
            "memory-insert-line-past-end",
            String.valueOf(insertLine),
            String.valueOf(lines.size()));
      }
      // Whether the file ended with a newline is restored below, so that appending an index line to
      // MEMORY.md does not silently join it to the line before on the next append.
      final var trailingNewline = Files.readString(target, StandardCharsets.UTF_8).endsWith("\n");
      lines.add(insertLine, insertText == null ? "" : insertText);
      write(target, String.join("\n", lines) + (trailingNewline ? "\n" : ""));
      return messages.get("memory-inserted", String.valueOf(insertLine), display(path));
    } finally {
      lock.unlock();
    }
  }

  /**
   * Deletes a memory file, or a directory where the scope is the requester's own.
   *
   * <p>Upstream deletes a directory and everything under it whatever the directory is. In somebody
   * own memories that is their own foot; in a group's or the tenant's it is one persuaded turn away
   * from erasing what a company had written down, and nothing here keeps a copy. So a shared scope
   * takes files only, and the refusal says to delete them one at a time — which is slow on purpose.
   *
   * <p>{@code Files.walk} does not follow symlinks and must not be made to: with a link inside a
   * memory directory, following one would turn this into a recursive delete of wherever it points.
   */
  String delete(final Path root, final String path, final boolean shared) throws IOException {
    final var target = resolveSafe(root, path);
    if (target.equals(root.toAbsolutePath().normalize())) {
      return messages.get("memory-root-undeletable");
    }
    final var lock = LOCKS.get(target.toString());
    lock.lock();
    try {
      if (!Files.exists(target)) {
        return messages.get("memory-not-found", display(path));
      }
      if (Files.isDirectory(target)) {
        if (shared) {
          return messages.get("memory-directory-shared", display(path));
        }
        try (final var walk = Files.walk(target)) {
          final var deepestFirst = walk.sorted(Comparator.reverseOrder()).toList();
          for (final var each : deepestFirst) {
            Files.delete(each);
          }
        }
        return messages.get("memory-deleted-directory", display(path));
      }
      Files.delete(target);
      return messages.get("memory-deleted", display(path));
    } finally {
      lock.unlock();
    }
  }

  String rename(final Path root, final String oldPath, final String newPath) throws IOException {
    final var source = resolveSafe(root, oldPath);
    final var destination = resolveSafe(root, newPath);
    final var lock = LOCKS.get(source.toString());
    lock.lock();
    try {
      if (!Files.exists(source)) {
        return messages.get("memory-not-found", display(oldPath));
      }
      if (Files.exists(destination)) {
        return messages.get("memory-exists", display(newPath));
      }
      final var parent = destination.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.move(source, destination);
      return messages.get("memory-renamed", display(oldPath), display(newPath));
    } finally {
      lock.unlock();
    }
  }

  /**
   * Writes without following a symlink at the target, so that a link planted after {@link
   * #resolveSafe} checked fails here instead of being written through.
   */
  private static void write(final Path target, final String text) throws IOException {
    try (final var out =
        Files.newOutputStream(
            target,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS)) {
      out.write(text.getBytes(StandardCharsets.UTF_8));
    }
  }

  private String listDirectory(final Path dir, final String displayPath) throws IOException {
    final var sb = new StringBuilder();
    // Through the bundle, like everything else the agent writes for itself: upstream's headers are
    // English literals, and a zh_CN workspace read them in the middle of its own language.
    sb.append(messages.get("memory-listing-header", displayPath.isEmpty() ? "/" : displayPath))
        .append("\n\n");
    try (final var level1 = Files.list(dir)) {
      for (final var entry : sorted(level1)) {
        final var name = entry.getFileName().toString();
        if (Files.isDirectory(entry)) {
          sb.append("  ").append(name).append("/\n");
          try (final var level2 = Files.list(entry)) {
            for (final var sub : sorted(level2)) {
              final var subName = sub.getFileName().toString();
              if (Files.isDirectory(sub)) {
                sb.append("    ").append(subName).append("/\n");
              } else {
                sb.append("    ")
                    .append(subName)
                    .append(" (")
                    .append(Files.size(sub))
                    .append(" bytes)\n");
              }
            }
          }
        } else {
          sb.append("  ").append(name).append(" (").append(Files.size(entry)).append(" bytes)\n");
        }
      }
    }
    return cap(sb.toString(), displayPath);
  }

  private static List<Path> sorted(final Stream<Path> entries) {
    return entries.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
  }

  private String readFile(final Path file, final String viewRange) throws IOException {
    final var allLines = Files.readAllLines(file, StandardCharsets.UTF_8);
    final var totalLines = allLines.size();

    var startLine = 1;
    var endLine = totalLines;
    if (StringUtils.hasText(viewRange)) {
      final var parts = viewRange.split(",");
      if (parts.length != 2) {
        return messages.get("memory-view-range-invalid");
      }
      try {
        startLine = Math.max(1, Integer.parseInt(parts[0].trim()));
        endLine = Math.min(totalLines, Integer.parseInt(parts[1].trim()));
      } catch (final NumberFormatException e) {
        return messages.get("memory-view-range-invalid");
      }
    }

    final var sb = new StringBuilder();
    // Line numbers as strings, not as ints: MessageFormat groups a number by locale, so a file of
    // two thousand lines would be reported as running to line 2,000.
    sb.append(
            messages.get(
                "memory-file-header",
                file.getFileName(),
                String.valueOf(startLine),
                String.valueOf(endLine),
                String.valueOf(totalLines)))
        .append("\n\n");
    for (var i = startLine - 1; i < endLine; i++) {
      sb.append(String.format("%6d\t%s%n", i + 1, allLines.get(i)));
    }
    return cap(sb.toString(), file.getFileName().toString());
  }

  /**
   * Trims one scope's section to {@link #MAX_LINES}/{@link #MAX_CHARS}, saying how to see the rest.
   */
  private String cap(final String text, final String what) {
    var capped = text;
    final var lines = capped.split("\n", -1);
    if (lines.length > MAX_LINES) {
      capped = String.join("\n", List.of(lines).subList(0, MAX_LINES));
    }
    if (capped.length() > MAX_CHARS) {
      capped = capped.substring(0, MAX_CHARS);
    }
    return capped.equals(text) ? text : capped + "\n" + messages.get("memory-truncated", what);
  }

  private static String display(final String path) {
    return StringUtils.hasText(path) ? path : "/";
  }

  private static int countOccurrences(final String text, final String substring) {
    var count = 0;
    var index = 0;
    while ((index = text.indexOf(substring, index)) != -1) {
      count++;
      index += substring.length();
    }
    return count;
  }

  private static String replaceFirst(final String text, final String oldStr, final String newStr) {
    final var index = text.indexOf(oldStr);
    return index == -1
        ? text
        : text.substring(0, index) + newStr + text.substring(index + oldStr.length());
  }

  private static String generateEditSnippet(final String fileContent, final String newStr) {
    final var lines = fileContent.split("\n", -1);
    final var newLines = newStr.split("\n", -1);

    var editStartLine = -1;
    var editEndLine = -1;
    for (var i = 0; i < lines.length; i++) {
      if (newLines.length > 0 && lines[i].contains(newLines[0])) {
        var matches = true;
        for (var j = 1; j < newLines.length && i + j < lines.length; j++) {
          if (!lines[i + j].contains(newLines[j])) {
            matches = false;
            break;
          }
        }
        if (matches) {
          editStartLine = i;
          editEndLine = i + newLines.length - 1;
          break;
        }
      }
    }
    if (editStartLine == -1) {
      editStartLine = 0;
      editEndLine = Math.min(10, lines.length - 1);
    }

    final var from = Math.max(0, editStartLine - 5);
    final var to = Math.min(lines.length - 1, editEndLine + 5);
    final var snippet = new StringBuilder();
    for (var i = from; i <= to; i++) {
      snippet.append(String.format("%6d→%s", i + 1, lines[i]));
      if (i < to) {
        snippet.append("\n");
      }
    }
    return snippet.toString();
  }
}
