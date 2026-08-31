package me.kezhenxu94.springagent.integration.slack.greeting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.integration.slack.config.SlackMessages;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * What the agent has to say about itself: the note it greets a new person with, and the numbered
 * notes saying what it has learned to do since.
 *
 * <p>Each is a markdown file with a YAML frontmatter block, read once at startup and held. A note
 * is prose written by whoever ships the deployment, not by a model and not by a user, and it is
 * shown to a person rather than given to one — so nothing here is a prompt and nothing here is
 * escaped as one.
 *
 * <p><b>A note's version is its filename, and the notes are found by counting up from 1 until one
 * is missing.</b> Both halves matter. Scanning the directory — {@code classpath*:/slack/updates/
 * *.md} — needs classpath directory listing, which a native image does not have: the binary would
 * find no notes and tell every user they were up to date, while the JVM build worked, which is the
 * worst shape a bug can have. Reading each file by the name it must have works the same in both.
 * And taking the version from the name means the number and the order can never disagree, which
 * they can when both a filename and a frontmatter field claim to say which note this is.
 *
 * <p>A gap therefore ends the list. That is the intended reading: notes are numbered from 1 with no
 * holes, and stopping at the hole is better than skipping a note nobody notices was skipped.
 */
@Slf4j
@Component
public class SlackUpdates {

  /**
   * Where counting stops regardless, so a location that answers every name — a misconfigured
   * resource loader, a servlet path that returns a page for anything — cannot spin here forever.
   */
  private static final int MOST_NOTES = 1000;

  /** One numbered note: what changed, in the words of whoever changed it. */
  public record Note(int version, String title, String body) {}

  /** What a person is told the first time, and the things they can tap to ask for. */
  public record Welcome(String title, String body, List<String> suggestions) {}

  /** The first-contact note. Never null: a location with no file there yields empty text. */
  @Getter private final Welcome welcome;

  /** The numbered notes, in ascending order, starting at 1. */
  private final List<Note> notes;

  /**
   * The prompts the welcome message is allowed to fire, as a set, so that a button press naming one
   * can be checked against what this deployment actually offers rather than taken as given.
   */
  private final Set<String> suggestions;

  public SlackUpdates(
      final ResourceLoader resourceLoader,
      final SlackMessages messages,
      @Value("${app.slack.welcome:classpath:/slack/welcome.md}") final String welcomeLocation,
      @Value("${app.slack.updates:classpath:/slack/updates/}") final String updatesLocation) {
    final var suffixes = localeSuffixes(messages.locale());
    this.welcome = readWelcome(resourceLoader, welcomeLocation, suffixes);
    this.notes = readNotes(resourceLoader, updatesLocation, suffixes);
    this.suggestions = Set.copyOf(new LinkedHashSet<>(welcome.suggestions()));
    log.info("Loaded {} update notes and {} suggestions", notes.size(), suggestions.size());
  }

  /** The highest note there is, or 0 where a deployment ships none. */
  public int current() {
    return notes.isEmpty() ? 0 : notes.getLast().version();
  }

  /** The notes above {@code version}, in the order they were written. */
  public List<Note> since(final int version) {
    return notes.stream().filter(note -> note.version() > version).toList();
  }

  /** Whether {@code prompt} is one of the things the welcome message offers to ask. */
  public boolean offers(final String prompt) {
    return prompt != null && suggestions.contains(prompt);
  }

  /**
   * The names to try for one note, most specific first: the language and country, then the language
   * alone, then the file with no suffix at all. The same order {@code messages.properties} and its
   * siblings are resolved in, so a deployment translating one translates the other the same way.
   */
  private static List<String> localeSuffixes(final Locale locale) {
    final var suffixes = new ArrayList<String>(3);
    if (locale != null && !locale.getLanguage().isEmpty()) {
      if (!locale.getCountry().isEmpty()) {
        suffixes.add("_" + locale.getLanguage() + "_" + locale.getCountry());
      }
      suffixes.add("_" + locale.getLanguage());
    }
    suffixes.add("");
    return suffixes;
  }

  private static Welcome readWelcome(
      final ResourceLoader loader, final String location, final List<String> suffixes) {
    final var base =
        location.endsWith(".md") ? location.substring(0, location.length() - 3) : location;
    final var text = read(loader, base, suffixes);
    if (text == null) {
      log.warn("No welcome note at {}; new users will be greeted with an empty message", location);
      return new Welcome("", "", List.of());
    }
    final var note = Frontmatter.parse(text);
    return new Welcome(note.string("title"), note.body(), note.strings("suggestions"));
  }

  private static List<Note> readNotes(
      final ResourceLoader loader, final String location, final List<String> suffixes) {
    final var directory = location.endsWith("/") ? location : location + "/";
    final var notes = new ArrayList<Note>();
    for (var version = 1; version <= MOST_NOTES; version++) {
      final var text = read(loader, directory + version, suffixes);
      if (text == null) {
        break;
      }
      final var parsed = Frontmatter.parse(text);
      notes.add(new Note(version, parsed.string("title"), parsed.body()));
    }
    return List.copyOf(notes);
  }

  /** The first of {@code base + suffix + ".md"} that exists, or null where none does. */
  private static String read(
      final ResourceLoader loader, final String base, final List<String> suffixes) {
    for (final var suffix : suffixes) {
      final var resource = loader.getResource(base + suffix + ".md");
      if (!resource.exists()) {
        continue;
      }
      try {
        return resource.getContentAsString(StandardCharsets.UTF_8);
      } catch (IOException e) {
        // A file that is there and unreadable is a broken deployment, not an absent note: saying so
        // at startup is better than a message that quietly says less than it was meant to.
        throw new IllegalStateException("Could not read " + resource, e);
      }
    }
    return null;
  }

  /**
   * A markdown file's leading YAML block and the prose under it.
   *
   * <p>Parsed with the safe constructor: the frontmatter is this repository's own text today, but a
   * deployment points {@code app.slack.updates} at files of its own, and a YAML parser that can be
   * asked to construct arbitrary classes is not something to hand a file path to.
   */
  private record Frontmatter(Map<String, Object> fields, String body) {

    private static final String FENCE = "---";

    static Frontmatter parse(final String text) {
      final var trimmed = text.stripLeading();
      if (!trimmed.startsWith(FENCE)) {
        return new Frontmatter(Map.of(), text.strip());
      }
      final var afterOpening = trimmed.indexOf('\n');
      if (afterOpening < 0) {
        return new Frontmatter(Map.of(), "");
      }
      final var closing = trimmed.indexOf("\n" + FENCE, afterOpening);
      if (closing < 0) {
        // An opened block that is never closed is a typo in the note, and reading the whole file as
        // YAML or as prose would both be a guess. Say which file, and stop.
        throw new IllegalStateException("A note opens a frontmatter block and never closes it");
      }
      final var yaml = trimmed.substring(afterOpening + 1, closing);
      final var restStarts = trimmed.indexOf('\n', closing + 1 + FENCE.length());
      final var body = restStarts < 0 ? "" : trimmed.substring(restStarts + 1).strip();
      final var parser = new Yaml(new SafeConstructor(new LoaderOptions()));
      final Object fields = parser.load(yaml);
      if (fields instanceof Map<?, ?> map) {
        final var typed = new java.util.LinkedHashMap<String, Object>();
        map.forEach((key, value) -> typed.put(String.valueOf(key), value));
        return new Frontmatter(typed, body);
      }
      return new Frontmatter(Map.of(), body);
    }

    String string(final String field) {
      final var value = fields.get(field);
      return value == null ? "" : String.valueOf(value);
    }

    List<String> strings(final String field) {
      final var value = fields.get(field);
      if (value instanceof List<?> list) {
        return list.stream().filter(Objects::nonNull).map(String::valueOf).toList();
      }
      return List.of();
    }
  }
}
