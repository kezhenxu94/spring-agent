package me.kezhenxu94.springagent.core.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * A prompt core hands to a library, in the workspace's language.
 *
 * <p>Kept as markdown files rather than in {@link CoreMessages}, which the rest of core's own text
 * goes through: these are pages long, and a paragraph of instructions folded into a properties
 * value is neither writable nor reviewable. The bundle stays for the one-line notes the agent
 * writes into a conversation.
 *
 * <p>Named the way a {@link java.util.ResourceBundle} is, and resolved the same way — exact locale,
 * then language alone, then the base file — so that adding a translation is adding a file and
 * nothing else. The base file is English and is what a locale nobody has translated falls back to,
 * since a prompt that fails to load fails the run.
 *
 * <p>The location is a parameter on the fuller overloads because two other kinds of text resolve
 * the same way and are not core's own prompts: a tool's description, under {@link #TOOLS_LOCATION},
 * and the reference guides a module returns as tool output, which live beside that module's own
 * resources rather than in core's. Only the resolution rule is shared.
 */
public final class LocalizedPrompt {

  /** Where core's own prompts live, as a classpath location. */
  static final String LOCATION = "core/prompts/";

  /**
   * Where a tool description's translation lives, one file per tool named exactly as the tool is.
   *
   * <p>A file rather than a key in a bundle because most of these are paragraphs — the longest runs
   * to two and a half thousand characters — for the reason the class comment gives.
   */
  public static final String TOOLS_LOCATION = "core/prompts/tools/";

  private LocalizedPrompt() {}

  /**
   * The most specific translation of {@code baseName} that exists, never {@code null}: the base
   * file ships with core and is the last candidate.
   *
   * @param baseName the file's name without locale suffix or extension, e.g. {@code auto-memory}
   * @param locale which language to look for, the host's when {@code null}
   */
  public static Resource resource(final String baseName, final Locale locale) {
    return resource(LOCATION, baseName, locale);
  }

  /** The same, for a caller whose files are not core's own prompts. */
  public static Resource resource(
      final String location, final String baseName, final Locale locale) {
    return find(location, baseName, locale)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No "
                        + location
                        + baseName
                        + ".md on the classpath; the module ships one, so it was excluded"));
  }

  /**
   * The same lookup, for a caller that has something to fall back to and so must be able to tell
   * that nothing was translated.
   *
   * <p>Which is what a tool description needs: the English text is the annotation's own, already in
   * the definition, and a missing file means keep it rather than fail the run.
   */
  public static Optional<Resource> find(
      final String location, final String baseName, final Locale locale) {
    final var wanted = locale == null ? Locale.getDefault() : locale;
    final var candidates =
        List.of(
            baseName + "_" + wanted.getLanguage() + "_" + wanted.getCountry(),
            baseName + "_" + wanted.getLanguage(),
            baseName);
    for (final var candidate : candidates) {
      final var resource = new ClassPathResource(location + candidate + ".md");
      if (resource.exists()) {
        return Optional.of(resource);
      }
    }
    return Optional.empty();
  }

  /**
   * The same file, read.
   *
   * <p>For the callers that have to hand over text rather than a resource — a property contributed
   * to the environment, a description a builder takes as a string, a guide returned to the model as
   * a tool's result — and so cannot leave the reading to whoever consumes it.
   */
  public static String text(final String baseName, final Locale locale) {
    return text(LOCATION, baseName, locale);
  }

  /** The same, for a caller whose files are not core's own prompts. */
  public static String text(final String location, final String baseName, final Locale locale) {
    return read(resource(location, baseName, locale), location + baseName);
  }

  /**
   * The text of core's own prompt {@code baseName}, or empty where nothing is translated.
   *
   * <p>For a prompt a library will fall back to its own default for: there is no base file here to
   * fall back to, so "nothing translated" has to be an answer rather than a failure.
   */
  public static Optional<String> findText(final String baseName, final Locale locale) {
    return findText(LOCATION, baseName, locale);
  }

  /** The text of whatever {@link #find} turned up, or empty where nothing is translated. */
  public static Optional<String> findText(
      final String location, final String baseName, final Locale locale) {
    return find(location, baseName, locale).map(resource -> read(resource, location + baseName));
  }

  private static String read(final Resource resource, final String named) {
    try {
      return resource.getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + named, e);
    }
  }
}
