package me.kezhenxu94.springagent.core.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * One scope's memories, as files rather than as prose for a model.
 *
 * <p>The half of {@link MemoryTools} that is not about talking to a model, pulled out because there
 * are now two callers and only one of them has a {@code ToolContext}: the tools, which take a scope
 * word the model wrote and answer in localized sentences, and the browser's {@code
 * MemoryController}, which takes a scope and a path and answers in JSON. Both have to be stopped by
 * the same guard — {@link MemoryFiles#resolveSafe} — and a guard that exists twice is a guard that
 * will eventually only be half fixed. Exactly the argument {@code core.skills.SkillFiles} makes
 * about the other thing a scope owns.
 *
 * <p>Nothing here is localized and nothing here reads a {@code ToolContext}: the caller passes the
 * one memories root it is acting in, and that choice <em>is</em> the scoping. A root, not a {@code
 * HomeDir}, because unlike a skill a memory is never looked up across scopes — {@code MemoryScopes}
 * has already decided which roots a request reaches and which of them it may write, and handing
 * this a composite would be a second place that decision could be made differently.
 *
 * <p>Whether a caller <em>may</em> write the root it passed is not asked here and must not be: this
 * refuses paths, not people. {@code MemoryTools} asks {@code MemoryScopes.writable}; the browser's
 * controller asks {@code TenantWrites}, which is the same question minus the group-chat witness a
 * browser session does not have.
 */
@Component
public class MemoryStore {

  /**
   * The index of every memory in a scope — one line per entry, maintained by whoever writes one.
   *
   * <p>Not itself a memory, and {@link MemoryEntry#index} exists to say so. It is what the memory
   * prompt tells the model to read first, so it is what a person should see first too: a list whose
   * top row is the table of contents reads as the thing it is, while the same file sorted
   * alphabetically among the memories reads as one of them.
   */
  public static final String INDEX = "MEMORY.md";

  /**
   * The largest file handed back as text, and the same number {@code SkillFiles} uses.
   *
   * <p>Deliberately the same: both are a file in somebody's home opened in the same editor on the
   * same page, and two ceilings would be two numbers to keep in step for no gain. A memory this
   * size is not a memory anyway — it is something that was written into the memories directory by
   * the shell — but the cap is about the browser, which draws a line number per line.
   */
  public static final long MAX_TEXT_BYTES = 512L * 1024;

  /**
   * The most files one scope's listing may hold.
   *
   * <p>Refused rather than truncated, for the reason {@code SkillFiles.MAX_TREE_ENTRIES} gives: a
   * truncated list has files missing from it with no way to say which, so a person would believe
   * they had seen everything the company remembers. A memories directory this size has been used as
   * a workspace, and saying so is more useful than drawing the first two thousand rows.
   */
  public static final int MAX_ENTRIES = 2000;

  /** How far into a file to look for a NUL before calling it binary. */
  private static final int SNIFF_BYTES = 8 * 1024;

  /** The fence that opens and closes YAML front matter. */
  private static final String FENCE = "---";

  // ─────────────────────────────────────── reading ───────────────────────────────────────

  /**
   * Every memory under {@code root}, the index first and the rest by path.
   *
   * <p>Walked rather than listed one level deep, because a memories directory is allowed to have
   * folders in it — {@code MemoryView} lists two levels and nothing stops the model creating a
   * third. A file the page cannot see is a file nobody can correct from here.
   *
   * <p>An absent root is an empty list and not an error: a scope gets its memories directory when
   * it gets its first memory, so "nobody has written one yet" is the ordinary state of a group's or
   * a company's, and it must not read as a failure. Nothing here creates the directory — that is
   * the write path's job, the rule {@link MemoryScopes} states.
   */
  public List<MemoryEntry> list(final Path root) {
    final var base = root.toAbsolutePath().normalize();
    if (!Files.isDirectory(base)) {
      return List.of();
    }
    final var entries = new ArrayList<MemoryEntry>();
    try (final var walk = Files.walk(base)) {
      for (final var path : walk.filter(Files::isRegularFile).toList()) {
        if (entries.size() >= MAX_ENTRIES) {
          throw new IllegalArgumentException(
              "more than " + MAX_ENTRIES + " files under the memories directory");
        }
        entries.add(entry(base, path));
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    // The index first whatever it is called next to, then by path. Sorted here rather than left to
    // the page because both callers want the same order, and the model's listing and the browser's
    // disagreeing about what comes first is a difference nobody could explain.
    entries.sort(
        Comparator.comparing(MemoryEntry::index).reversed().thenComparing(MemoryEntry::path));
    return List.copyOf(entries);
  }

  /**
   * One memory file, or empty where it is not there.
   *
   * <p>Absent rather than refused, the distinction {@code SkillFiles.read} draws: following a stale
   * link to a memory somebody has since corrected away is an ordinary thing to do, and answering it
   * as a traversal attempt tells the reader they did something wrong when they only pressed back.
   */
  public Optional<MemoryFile> read(final Path root, final String path) {
    final var base = root.toAbsolutePath().normalize();
    final var file = MemoryFiles.resolveSafe(base, path);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      final var size = Files.size(file);
      if (size > MAX_TEXT_BYTES) {
        return Optional.of(new MemoryFile(entry(base, file), null, false, true));
      }
      final var bytes = Files.readAllBytes(file);
      final var text = asText(bytes);
      final var entry = entry(base, file, text);
      return Optional.of(
          text == null
              ? new MemoryFile(entry, null, true, false)
              : new MemoryFile(entry, text, false, false));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ─────────────────────────────────────── writing ───────────────────────────────────────

  /**
   * Writes {@code text} to a memory, creating the root and any folder on the way if they are not
   * there.
   *
   * <p>Create and overwrite are one operation, unlike {@code MemoryCreate}, which refuses a path
   * that already exists. That refusal is right for a model, which cannot see the file and would
   * otherwise silently replace something it had not read; it is wrong for a person looking at the
   * file in an editor, for whom refusing to save what is on screen is the surprising answer.
   *
   * <p>Answers with the file so the caller can report what it wrote without resolving the path a
   * second time — the kind of second resolution that ends up without the guard on it.
   */
  public MemoryEntry write(final Path root, final String path, final String text) {
    final var base = root.toAbsolutePath().normalize();
    final var file = MemoryFiles.resolveSafe(base, path);
    if (file.equals(base)) {
      throw new IllegalArgumentException("the memories root is not a file");
    }
    final var body = text == null ? "" : text;
    try {
      Files.createDirectories(file.getParent() == null ? base : file.getParent());
      // NOFOLLOW_LINKS for the reason MemoryFiles.write gives: resolveSafe checked a moment ago,
      // and a link planted between that check and this write must fail the write rather than be
      // followed out of the memories directory.
      try (final var out =
          Files.newOutputStream(
              file,
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              StandardOpenOption.TRUNCATE_EXISTING,
              LinkOption.NOFOLLOW_LINKS)) {
        out.write(body.getBytes(StandardCharsets.UTF_8));
      }
      return entry(base, file, body);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Deletes one memory, answering whether there was one there.
   *
   * <p>Files only, in every scope — not the own/shared split {@code MemoryFiles.delete} makes. That
   * one is guarding against a model talked into a recursive delete; this is a person pressing a
   * button on a row, and a row is a file. A directory in a memories folder is therefore something
   * only the tools can remove, which is the right way round: nothing on this page can put one
   * there.
   */
  public boolean delete(final Path root, final String path) {
    final var base = root.toAbsolutePath().normalize();
    final var file = MemoryFiles.resolveSafe(base, path);
    if (file.equals(base) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    try {
      Files.delete(file);
      return true;
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ─────────────────────────────────────── the shared parts ───────────────────────────────

  /** A row with its front matter read off the file, for a listing. */
  private MemoryEntry entry(final Path base, final Path file) throws IOException {
    final var size = Files.size(file);
    // Only the front matter, and only where the file is small enough to be one. A memories
    // directory used as a workspace would otherwise have this read every byte of every file in it
    // to draw a list.
    final String text = size > MAX_TEXT_BYTES ? null : asText(Files.readAllBytes(file));
    return entry(base, file, text);
  }

  /** The same, for a caller that already has the contents. */
  private MemoryEntry entry(final Path base, final Path file, final String text)
      throws IOException {
    final var relative = relative(base, file);
    final var matter = frontMatter(text);
    return new MemoryEntry(
        relative,
        Files.size(file),
        Files.getLastModifiedTime(file).toInstant(),
        matter[0],
        matter[1],
        matter[2],
        INDEX.equalsIgnoreCase(relative));
  }

  /**
   * The {@code name}, {@code description} and {@code type} a memory claims, each blank where it
   * says nothing.
   *
   * <p>Deliberately forgiving, and not a YAML parser. What it looks for is a {@code key: value}
   * line between the opening and closing fences, at any indentation — which reads both the flat
   * shape {@code MemoryView}'s description asks for and the nested {@code metadata:} block some
   * memories are written with, without having to know which it is looking at. A memory whose front
   * matter is malformed, or which has none, still lists: these three fields are a claim the file
   * makes about itself, and a row is drawn from its path either way.
   *
   * <p>A real parser would be a dependency and a failure mode, in exchange for rejecting files this
   * has no business rejecting. The page shows the whole file to whoever opens it, so anything this
   * misreads is visible one click away.
   */
  private static String[] frontMatter(final String text) {
    final var out = new String[] {"", "", ""};
    if (text == null || !text.stripLeading().startsWith(FENCE)) {
      return out;
    }
    final var lines = text.lines().toList();
    var started = false;
    for (final var line : lines) {
      final var trimmed = line.trim();
      if (FENCE.equals(trimmed)) {
        if (started) {
          break;
        }
        started = true;
        continue;
      }
      if (!started) {
        continue;
      }
      final var colon = trimmed.indexOf(':');
      if (colon <= 0) {
        continue;
      }
      final var key = trimmed.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      final var value = unquoted(trimmed.substring(colon + 1).trim());
      if (value.isEmpty()) {
        continue;
      }
      // First occurrence wins, so a nested block cannot overwrite what the top level said.
      switch (key) {
        case "name" -> out[0] = out[0].isEmpty() ? value : out[0];
        case "description" -> out[1] = out[1].isEmpty() ? value : out[1];
        case "type" -> out[2] = out[2].isEmpty() ? value : out[2];
        default -> {}
      }
    }
    return out;
  }

  private static String unquoted(final String value) {
    if (value.length() >= 2
        && ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'")))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  /** The path as the wire and the page address it: relative to the root and {@code /}-separated. */
  private static String relative(final Path base, final Path file) {
    final var separator = base.getFileSystem().getSeparator();
    final var relative = base.relativize(file).toString();
    return "/".equals(separator) ? relative : relative.replace(separator, "/");
  }

  /** The bytes as text, or null where they are not text at all. */
  private static String asText(final byte[] bytes) {
    for (var i = 0; i < Math.min(bytes.length, SNIFF_BYTES); i++) {
      if (bytes[i] == 0) {
        return null;
      }
    }
    final var decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    } catch (final CharacterCodingException e) {
      return null;
    }
  }
}
