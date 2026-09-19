package me.kezhenxu94.springagent.core.skills;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.HomeDir;
import org.springaicommunity.agent.tools.SkillsTool.Skill;
import org.springaicommunity.agent.utils.Skills;
import org.springframework.stereotype.Component;

/**
 * A scope's skills, as folders and files rather than as prose for a model.
 *
 * <p>This is the half of {@code SkillManagementTools} that is not about talking to a model, pulled
 * out because there are now two callers and only one of them has a {@code ToolContext}: the tools,
 * which take absolute paths the model wrote and answer in sentences, and the browser's {@code
 * SkillController}, which takes a scope and a name and answers in JSON. Both have to be stopped by
 * the same guard, and a guard that exists twice is a guard that will eventually only be half fixed.
 *
 * <p>Nothing here is localized and nothing here reads a {@code ToolContext}: a caller chooses the
 * {@link HomeDir} it is acting in, and that choice <em>is</em> the scoping. Handing this a
 * single-scope home confines it to that scope; handing it a composite lets a read span every scope
 * the request reaches, nearest first, which is the order {@code HomeDir.dirs} already promises and
 * the order {@code SkillsTool} resolves a duplicate name by.
 *
 * <p>Reads and writes deliberately ask different questions of the home. A read {@link #locate
 * locates} the skill among every scope's directory, because a person may open a skill the company
 * shares. A write {@link #target targets} {@code folderPath}, the one directory new content goes
 * into. That is not a special rule invented here — it is exactly the split {@link HomeDir} is built
 * around, and stating it twice would be the way the two drift.
 */
@Slf4j
@Component
public class SkillFiles {

  /** The file that makes a folder a skill. Anything else in the folder is that skill's own. */
  public static final String MANIFEST = "SKILL.md";

  /**
   * The largest file handed back as text.
   *
   * <p>A skill is instructions and the scripts they run, and half a megabyte is already far more
   * prose than a model will be given. The cap is not really about the server — it is about the
   * page, which draws a line number per line: a ten-megabyte file is a browser tab that stops
   * responding, and the person who opened it cannot tell that from a crash.
   */
  public static final long MAX_TEXT_BYTES = 512L * 1024;

  /**
   * The longest a skill name may be.
   *
   * <p>Not an arbitrary round number: {@code SkillsTool} turns a skill's name into a tool name by
   * putting {@code skill_} in front of it, and a tool name over 64 characters is one every model
   * provider rejects — so that skill is skipped, with a warning in a log nobody is reading, and it
   * is simply never offered to the model. A page that cheerfully creates one would be creating a
   * skill that does nothing, which is the worst thing a page like this can do.
   */
  public static final int MAX_NAME_LENGTH = 64 - "skill_".length();

  /**
   * The most files one skill's tree may hold.
   *
   * <p>Refused rather than truncated. A truncated tree is a tree with files missing from it and no
   * way to say which, so a person deleting the skill would believe they had seen what they were
   * deleting. Something this size is a directory that has been used as a workspace, and the answer
   * is to say so rather than to draw the first two thousand of it.
   */
  public static final int MAX_TREE_ENTRIES = 2000;

  /**
   * The most a zip may come to once it is unpacked.
   *
   * <p>Against the compressed size and not the compressed one, which is the whole point: a few
   * hundred kilobytes of zip can be gigabytes of zeroes, and a limit on what arrives over the wire
   * says nothing about what lands on the disk. Counted as it decompresses and abandoned the moment
   * it goes over, so the bomb is never fully expanded even once.
   *
   * <p>Smaller than an upload's ceiling on purpose. A single large asset dropped into a skill is a
   * reasonable thing; a whole archive of them is a repository, and a skill is not one.
   */
  public static final long MAX_ZIP_BYTES = 8L * 1024 * 1024;

  /**
   * How much of a file is looked at to decide whether it is text.
   *
   * <p>A NUL in the first block is the whole heuristic, plus a strict UTF-8 decode of the rest. It
   * is the same test every diff tool makes, and it is wrong only for a file whose first eight
   * kilobytes are clean text and whose tail is not — which the decode below then catches anyway.
   */
  private static final int SNIFF_BYTES = 8 * 1024;

  // ─────────────────────────────────────── the guard ───────────────────────────────────────

  /**
   * {@code candidate}, absolute and normalized, once it is certain it lies inside one of {@code
   * home}'s skills directories — or {@link SkillAccessDenied}.
   *
   * <p>Two checks and not one, and the second is the one that matters. Normalizing answers {@code
   * ../../etc/passwd}, which is what a hand-written path does. It does not answer a
   * <em>symlink</em> inside a skills folder pointing out of the home, because normalizing is pure
   * string arithmetic and knows nothing about the filesystem — and a symlink there is not
   * hypothetical: the sandbox shell runs as the same user in the same volume, so anything it can be
   * told to create is something this has to survive. So the deepest part of the path that exists is
   * resolved to its real location, the part that does not exist yet is put back on the end, and
   * containment is asked a second time about the answer.
   *
   * <p>A broken symlink is refused outright rather than reasoned about. It exists, so it is not a
   * path that has yet to be created; it has no real location, so containment cannot be decided; and
   * a write through one creates whatever it points at. There is no reading of that which is safe.
   */
  public Path guarded(final HomeDir home, final Path candidate) {
    final var resolved = candidate.toAbsolutePath().normalize();
    if (!home.containsIn(HomeDir.Folder.SKILLS, resolved)) {
      throw new SkillAccessDenied("That path is outside the skills directory.");
    }
    if (!reallyInside(home, resolved)) {
      throw new SkillAccessDenied("That path leads outside the skills directory.");
    }
    return resolved;
  }

  /**
   * Whether {@code candidate} lies inside one of {@code home}'s skills directories, links and all.
   *
   * <p>{@link HomeDir#containsIn} answers the same question by string arithmetic, and that is not
   * enough for a caller deciding <em>whose</em> directory a write lands in: a symlink under the
   * caller's own skills folder pointing at the company's is a path that reads as private and writes
   * as shared, and the sandbox shell can create one. So this is the question a scope check has to
   * ask, and it is the same one {@link #guarded} asks about the home it was given.
   */
  public boolean inSkillsOf(final HomeDir home, final Path candidate) {
    return reallyInside(home, candidate.toAbsolutePath().normalize());
  }

  /**
   * Whether {@code resolved} is still inside a skills directory once every link on the way to it
   * has been followed.
   *
   * <p>Both sides are resolved, and that is the part it is easy to get wrong: comparing a real path
   * against a <em>declared</em> directory refuses everything the moment any part of the storage
   * root is itself a symlink — which on macOS it always is, since {@code /var} is a link to {@code
   * /private/var}, and in a container it is however the volume was mounted. A guard that refuses
   * every write is not a strict guard, it is a broken feature, and the fix somebody reaches for
   * under that pressure is to delete the check.
   */
  private boolean reallyInside(final HomeDir home, final Path resolved) {
    final var real = real(resolved);
    for (final var root : home.roots()) {
      if (real.startsWith(real(root.resolve(HomeDir.Folder.SKILLS.dirName())))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Where {@code resolved} really is, with any part that does not exist yet put back on the end.
   */
  private Path real(final Path resolved) {
    var existing = resolved;
    final var missing = new ArrayDeque<String>();
    // NOFOLLOW_LINKS, so a broken symlink counts as something that is there. Left out, it would
    // read as a name yet to be created and the link itself would never be looked at.
    while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
      missing.push(existing.getFileName().toString());
      existing = existing.getParent();
    }
    if (existing == null) {
      return resolved;
    }
    Path real;
    try {
      real = existing.toRealPath();
    } catch (final IOException e) {
      throw new SkillAccessDenied("That path cannot be resolved to a real location.");
    }
    for (final var name : missing) {
      real = real.resolve(name);
    }
    return real.normalize();
  }

  /**
   * One folder name, checked before it is resolved against anything.
   *
   * <p>{@link #guarded} would catch most of what this refuses, and it is still worth refusing here:
   * a skill name is the identity a delete is issued against and the word a route spells, so one
   * carrying a separator is a skill that can be named two ways and deleted by neither.
   */
  private String segment(final String name, final String what) {
    if (name == null || name.isBlank()) {
      throw new SkillAccessDenied("A " + what + " is required.");
    }
    final var trimmed = name.trim();
    if (trimmed.contains("/")
        || trimmed.contains("\\")
        || ".".equals(trimmed)
        || "..".equals(trimmed)
        || trimmed.startsWith("~")) {
      throw new SkillAccessDenied("That is not a usable " + what + ".");
    }
    return trimmed;
  }

  /**
   * A relative path inside a skill, as a list of names.
   *
   * <p>Both separators are accepted on the way in and only {@code /} is ever written back out — see
   * {@link SkillEntry}. Every empty segment is dropped, so {@code a//b} and {@code ./a/b} are the
   * one path they obviously mean rather than a refusal somebody has to work out.
   */
  private List<String> segments(final String path) {
    if (path == null || path.isBlank()) {
      throw new SkillAccessDenied("A file path is required.");
    }
    final var names = new ArrayList<String>();
    for (final var raw : path.trim().split("[/\\\\]")) {
      if (raw.isBlank() || ".".equals(raw)) {
        continue;
      }
      names.add(segment(raw, "file path"));
    }
    if (names.isEmpty()) {
      throw new SkillAccessDenied("A file path is required.");
    }
    return names;
  }

  // ─────────────────────────────────────── where a skill is
  // ───────────────────────────────────────

  /**
   * The skill of this name in the nearest scope that has one, or empty.
   *
   * <p>"Has one" means the folder holds a {@link #MANIFEST}: a bare directory under {@code skills/}
   * is not a skill to {@code SkillsTool} either, and answering with it here would let the page open
   * something the agent will never be offered.
   */
  public Optional<Path> locate(final HomeDir home, final String skill) {
    final var name = segment(skill, "skill name");
    for (final var skillsDir : skillsDirs(home)) {
      final var candidate = guarded(home, skillsDir.resolve(name));
      if (Files.isRegularFile(candidate.resolve(MANIFEST))) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  /** Where a skill of this name would be written, whether or not it is there yet. */
  public Path target(final HomeDir home, final String skill) {
    return guarded(
        home, home.folderPath(HomeDir.Folder.SKILLS).resolve(segment(skill, "skill name")));
  }

  private List<Path> skillsDirs(final HomeDir home) {
    try {
      return home.dirs(HomeDir.Folder.SKILLS);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ─────────────────────────────────────── reading ───────────────────────────────────────

  /** Every skill this home reaches, nearest scope first, in the order a list should draw them. */
  public List<SkillSummary> list(final HomeDir home) {
    final var out = new ArrayList<SkillSummary>();
    final var seen = new ArrayList<String>();
    for (final var skillsDir : skillsDirs(home)) {
      final var declared = frontMatter(skillsDir);
      try (var children = Files.list(skillsDir)) {
        children
            .filter(Files::isDirectory)
            .filter(dir -> Files.isRegularFile(dir.resolve(MANIFEST)))
            .sorted(Comparator.comparing(dir -> dir.getFileName().toString()))
            .forEach(
                dir -> {
                  // Nearest scope wins, exactly as it does when the same folders become tools.
                  // Listing both would be two rows for one thing the model can only call once.
                  final var name = dir.getFileName().toString();
                  if (seen.contains(name)) {
                    return;
                  }
                  seen.add(name);
                  out.add(summary(dir, declared.get(dir.toAbsolutePath().normalize().toString())));
                });
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return out;
  }

  /** One skill and every file in it, or empty where no scope has a skill of that name. */
  public Optional<SkillDetail> detail(final HomeDir home, final String skill) {
    return locate(home, skill)
        .map(
            dir ->
                new SkillDetail(
                    summary(dir, frontMatter(dir.getParent()).get(dir.toString())), tree(dir)));
  }

  /**
   * One file inside a skill, or empty where the skill or the file is not there.
   *
   * <p>Absent rather than refused: a stale link to a file somebody has since deleted is an ordinary
   * thing to follow, and answering it as a traversal attempt tells the reader they did something
   * wrong when they only pressed back.
   */
  public Optional<SkillFile> read(final HomeDir home, final String skill, final String path) {
    final var dir = locate(home, skill);
    if (dir.isEmpty()) {
      return Optional.empty();
    }
    final var file = guarded(home, resolveIn(dir.get(), path));
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      final var size = Files.size(file);
      final var entry = new SkillEntry(relative(dir.get(), file), false, size);
      if (size > MAX_TEXT_BYTES) {
        return Optional.of(new SkillFile(entry, null, false, true));
      }
      final var bytes = Files.readAllBytes(file);
      final var text = asText(bytes);
      return Optional.of(
          text == null
              ? new SkillFile(entry, null, true, false)
              : new SkillFile(entry, text, false, false));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ─────────────────────────────────────── writing ───────────────────────────────────────

  /**
   * Writes {@code text} to a file inside a skill, creating the skill's folder and any folder on the
   * way if they are not there.
   *
   * <p>Answers with the file so that the caller can say what it wrote without resolving the path a
   * second time — which is the kind of second resolution that ends up without the guard on it.
   */
  public SkillEntry write(
      final HomeDir home, final Path skillDir, final String path, final String text) {
    return write(home, skillDir, path, (text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The same, for content that is not text.
   *
   * <p>A skill is allowed a font, an image or a compiled helper beside its instructions, and a file
   * uploaded into one must arrive byte for byte. Decoding it to a string on the way past and
   * encoding it again would rewrite every byte that is not valid UTF-8 as a replacement character —
   * an upload that reports success and stores a corrupted file, which is the worst of the three
   * possible outcomes.
   */
  public SkillEntry write(
      final HomeDir home, final Path skillDir, final String path, final byte[] content) {
    final var file = guarded(home, resolveIn(skillDir, path));
    try {
      Files.createDirectories(file.getParent());
      Files.write(
          file,
          content == null ? new byte[0] : content,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE);
      return new SkillEntry(relative(skillDir, file), false, Files.size(file));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The same, straight from a stream.
   *
   * <p>For an upload, where the alternative is holding the whole file in the heap first: the
   * ceiling on one upload is the same 32MB a conversation's is and ten may arrive together, so
   * reading them into byte arrays to hand them straight back out is a third of a gigabyte spent on
   * nothing. The zip path keeps the byte-array form because it has to hold the archive's entries
   * anyway — it validates all of them before writing any.
   */
  public SkillEntry write(
      final HomeDir home, final Path skillDir, final String path, final InputStream content) {
    final var file = guarded(home, resolveIn(skillDir, path));
    try {
      Files.createDirectories(file.getParent());
      Files.copy(content, file, StandardCopyOption.REPLACE_EXISTING);
      return new SkillEntry(relative(skillDir, file), false, Files.size(file));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Deletes one file inside a skill. Answers whether there was one. */
  public boolean deleteFile(final HomeDir home, final Path skillDir, final String path) {
    final var file = guarded(home, resolveIn(skillDir, path));
    // The manifest is the one file that may not go this way. Without it the folder stops being a
    // skill, which means `locate` no longer finds it — so it would vanish from the list and from
    // every endpoint that addresses a skill by name, leaving a directory nothing can reach and
    // nothing can delete. Deleting the skill is the operation that was wanted, and it exists.
    if (MANIFEST.equals(file.getFileName().toString()) && file.getParent().equals(skillDir)) {
      throw new SkillAccessDenied("Deleting " + MANIFEST + " would leave the skill unreachable.");
    }
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    if (Files.isDirectory(file)) {
      throw new SkillAccessDenied("That is a folder, not a file.");
    }
    try {
      return Files.deleteIfExists(file);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Deletes a whole skill folder.
   *
   * <p>Refuses a folder with no {@link #MANIFEST} in it, which is the same check {@code
   * DeleteSkill} has always made: a recursive delete is worth one proof that the thing being
   * deleted is the kind of thing this is allowed to delete.
   */
  public void deleteSkill(final HomeDir home, final Path skillDir) {
    final var dir = guarded(home, skillDir);
    if (!Files.isRegularFile(dir.resolve(MANIFEST))) {
      throw new SkillAccessDenied("That folder is not a skill.");
    }
    try (var walk = Files.walk(dir)) {
      // Deepest first, because a directory cannot be removed until it is empty.
      walk.sorted(Comparator.reverseOrder()).forEach(SkillFiles::delete);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void delete(final Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ─────────────────────────────────────── importing a zip ───────────────────────────────────────

  /**
   * A zip's files, keyed by the path each would take inside a skill. Nothing is written.
   *
   * <p>Reading and writing are two calls because a zip is the one input here that can fail half way
   * through. Unpacking straight to disk leaves a folder holding the entries that happened to come
   * before the bad one — which is a skill, as far as everything else is concerned, made of an
   * archive somebody's browser gave up on. Validating the whole thing first means a refusal leaves
   * nothing behind.
   *
   * <p>Every entry name goes through the same {@link #segments} the rest of this class uses, which
   * is what answers <b>zip slip</b>: an archive is free to contain {@code
   * ../../../.ssh/authorized_keys}, and that is not an exotic attack but the first thing anybody
   * tries. A name that climbs is refused rather than clamped, because an archive containing one is
   * not an archive somebody built by accident.
   */
  public Map<String, byte[]> unpack(final InputStream zip) {
    final var files = new LinkedHashMap<String, byte[]>();
    var total = 0L;
    try (var in = new ZipInputStream(zip, StandardCharsets.UTF_8)) {
      for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        if (entry.isDirectory()) {
          continue;
        }
        final var path = String.join("/", segments(entry.getName()));
        if (ignored(path)) {
          continue;
        }
        if (files.size() >= MAX_TREE_ENTRIES) {
          throw new IllegalArgumentException(
              "That archive holds more than " + MAX_TREE_ENTRIES + " files.");
        }
        final var bytes = read(in, MAX_ZIP_BYTES - total);
        total += bytes.length;
        files.put(path, bytes);
      }
    } catch (final IOException e) {
      throw new IllegalArgumentException("That file could not be read as a zip archive.", e);
    }
    if (files.isEmpty()) {
      throw new IllegalArgumentException("That archive holds no files.");
    }
    return stripped(files);
  }

  /**
   * Reads one entry, giving up the moment it is clear the whole will not fit.
   *
   * <p>{@code readAllBytes} on a decompressing stream is how a zip bomb takes the process down: the
   * entry declares nothing, the stream keeps answering, and the heap goes. So this reads to a
   * budget — what is left of {@link #MAX_ZIP_BYTES} — and throws rather than allocating past it.
   */
  private byte[] read(final ZipInputStream in, final long budget) throws IOException {
    final var out = new ByteArrayOutputStream();
    final var buffer = new byte[8192];
    var left = budget;
    for (var n = in.read(buffer); n >= 0; n = in.read(buffer)) {
      left -= n;
      if (left < 0) {
        throw new IllegalArgumentException(
            "That archive unpacks to more than " + (MAX_ZIP_BYTES / 1024 / 1024) + "MB.");
      }
      out.write(buffer, 0, n);
    }
    return out.toByteArray();
  }

  /**
   * An archive whose files all sit in one folder, with that folder taken off.
   *
   * <p>Which is nearly every archive a person will have: downloading a skill from a repository
   * gives {@code my-skill-main/SKILL.md}, and unpacked as-is that is a skill whose SKILL.md is one
   * level too deep and which therefore is not a skill at all. Only where there is exactly one such
   * folder — an archive with two top-level folders is two things, and guessing which is wanted is
   * worse than unpacking what was actually sent.
   */
  private Map<String, byte[]> stripped(final Map<String, byte[]> files) {
    final var roots = files.keySet().stream().map(path -> path.split("/")[0]).distinct().toList();
    if (roots.size() != 1 || files.keySet().stream().noneMatch(path -> path.contains("/"))) {
      return files;
    }
    final var prefix = roots.get(0) + "/";
    final var out = new LinkedHashMap<String, byte[]>();
    files.forEach((path, bytes) -> out.put(path.substring(prefix.length()), bytes));
    return out;
  }

  /**
   * What an archive carries that a skill should not.
   *
   * <p>Archiving a folder on a Mac puts a shadow copy of every file under {@code __MACOSX/} and a
   * {@code .DS_Store} beside it. Unpacked, those are files in the tree that nobody put there and
   * nobody can explain — and one of them, {@code __MACOSX/._SKILL.md}, looks enough like the
   * manifest to be confusing.
   */
  private boolean ignored(final String path) {
    return path.isEmpty()
        || path.startsWith("__MACOSX/")
        || path.equals(".DS_Store")
        || path.endsWith("/.DS_Store");
  }

  /**
   * Writes a skill out as a zip, entry by entry, straight into {@code out}.
   *
   * <p>Streamed rather than assembled: a skill may hold assets, and building the archive in memory
   * to hand it to a response that is about to stream it anyway is heap spent on nothing.
   *
   * <p><b>No size cap, where {@link #unpack} has one</b>, and the asymmetry is deliberate rather
   * than an oversight. The import cap is there because a small archive can expand into a large
   * amount of somebody else's disk; nothing about sending a person their own files back has that
   * shape. Capping it would also mean a skill that grew past the limit — which uploading a single
   * large asset can do — could never be got out again, which is the worst property a download can
   * have.
   *
   * <p>Every file is put through {@link #guarded} on the way out. {@code Files.walk} does not
   * follow links, but {@code Files.copy} does, so without this a symlink planted inside a skill
   * would be read and packed — the same hole the read path closes, and the reason to close it here
   * too is that a download is the easiest way to carry something off.
   */
  public void pack(final HomeDir home, final Path skillDir, final OutputStream out) {
    final var dir = guarded(home, skillDir);
    try (var zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
      // The tree's own order, so two exports of one skill differ only where the skill does.
      for (final var entry : tree(dir)) {
        if (entry.dir()) {
          continue;
        }
        final var file = guarded(home, dir.resolve(entry.path()));
        if (!Files.isRegularFile(file)) {
          continue;
        }
        zip.putNextEntry(new ZipEntry(entry.path()));
        Files.copy(file, zip);
        zip.closeEntry();
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Writes a whole unpacked archive into a skill, and answers how many files that was. */
  public int writeAll(final HomeDir home, final Path skillDir, final Map<String, byte[]> files) {
    files.forEach((path, bytes) -> write(home, skillDir, path, bytes));
    return files.size();
  }

  // ─────────────────────────────────────── the small parts ───────────────────────────────────────

  private Path resolveIn(final Path skillDir, final String path) {
    var file = skillDir;
    for (final var name : segments(path)) {
      file = file.resolve(name);
    }
    return file;
  }

  /** A path under {@code skillDir}, spelt the one way the wire and the page spell it. */
  private String relative(final Path skillDir, final Path file) {
    final var names = new ArrayList<String>();
    skillDir.relativize(file).forEach(part -> names.add(part.toString()));
    return String.join("/", names);
  }

  private SkillSummary summary(final Path dir, final Skill declared) {
    var files = 0;
    var newest = Instant.EPOCH;
    try (var walk = Files.walk(dir)) {
      final var all = walk.filter(Files::isRegularFile).toList();
      files = all.size();
      for (final var file : all) {
        try {
          final var at = Files.getLastModifiedTime(file).toInstant();
          if (at.isAfter(newest)) {
            newest = at;
          }
        } catch (final IOException e) {
          // One unreadable file is not a reason to have no answer for the skill it is in.
          log.debug("Could not read the modification time of {}", file, e);
        }
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    return new SkillSummary(
        dir,
        dir.getFileName().toString(),
        field(declared, "name"),
        field(declared, "description"),
        files,
        newest);
  }

  private List<SkillEntry> tree(final Path skillDir) {
    try (var walk = Files.walk(skillDir)) {
      final var entries =
          walk.filter(path -> !path.equals(skillDir))
              .limit(MAX_TREE_ENTRIES + 1L)
              .map(path -> entry(skillDir, path))
              .sorted(SkillFiles::byPath)
              .collect(Collectors.toCollection(ArrayList::new));
      if (entries.size() > MAX_TREE_ENTRIES) {
        throw new IllegalArgumentException(
            "That skill holds more than " + MAX_TREE_ENTRIES + " files.");
      }
      return entries;
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private SkillEntry entry(final Path skillDir, final Path path) {
    final var dir = Files.isDirectory(path);
    long size = 0;
    if (!dir) {
      try {
        size = Files.size(path);
      } catch (final IOException e) {
        log.debug("Could not read the size of {}", path, e);
      }
    }
    return new SkillEntry(relative(skillDir, path), dir, size);
  }

  /**
   * Folders before files at each level, then by name.
   *
   * <p>Case-insensitively, and not because a filesystem is: a tree is read down its left edge, and
   * {@code LICENSE.txt} landing above {@code examples/} on one machine and below it on another is
   * the same skill drawn two ways.
   */
  private static int byPath(final SkillEntry left, final SkillEntry right) {
    final var l = left.path().split("/");
    final var r = right.path().split("/");
    for (var i = 0; i < Math.min(l.length, r.length); i++) {
      if (!l[i].equals(r[i])) {
        final var lastOfLeft = i == l.length - 1 && !left.dir();
        final var lastOfRight = i == r.length - 1 && !right.dir();
        if (lastOfLeft != lastOfRight) {
          return lastOfLeft ? 1 : -1;
        }
        final var byName = l[i].compareToIgnoreCase(r[i]);
        return byName != 0 ? byName : l[i].compareTo(r[i]);
      }
    }
    return Integer.compare(l.length, r.length);
  }

  /**
   * Every SKILL.md under {@code skillsDir}, by the folder it is in.
   *
   * <p>One walk for the whole directory rather than one per skill, and the library's parser rather
   * than a second reading of the same front matter — {@code SkillsTool} builds the model's view of
   * these files with it, and a page that disagreed with the model about what a skill is called
   * would be worse than one that did not say.
   *
   * <p>Answers with what it could read. A skill whose SKILL.md cannot be parsed still exists, still
   * has files in it, and is still the one somebody opened the page to fix.
   */
  private Map<String, Skill> frontMatter(final Path skillsDir) {
    try {
      return Skills.loadDirectory(skillsDir.toString()).stream()
          .collect(
              Collectors.toMap(
                  skill -> Path.of(skill.basePath()).toAbsolutePath().normalize().toString(),
                  Function.identity(),
                  (first, second) -> first));
    } catch (final RuntimeException e) {
      log.warn("Could not read the front matter of the skills in {}", skillsDir, e);
      return Map.of();
    }
  }

  private String field(final Skill skill, final String key) {
    if (skill == null) {
      return "";
    }
    final var value = skill.frontMatter().get(key);
    return value == null ? "" : value.toString();
  }

  /** The bytes as text, or null where they are not text at all. */
  private String asText(final byte[] bytes) {
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
