package me.kezhenxu94.springagent.core.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
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
 */
public final class LocalizedPrompt {

  /** Where the files live, as a classpath location. */
  static final String LOCATION = "core/prompts/";

  private LocalizedPrompt() {}

  /**
   * The most specific translation of {@code baseName} that exists, never {@code null}: the base
   * file ships with core and is the last candidate.
   *
   * @param baseName the file's name without locale suffix or extension, e.g. {@code auto-memory}
   * @param locale which language to look for, the host's when {@code null}
   */
  public static Resource resource(final String baseName, final Locale locale) {
    final var wanted = locale == null ? Locale.getDefault() : locale;
    final var candidates =
        List.of(
            baseName + "_" + wanted.getLanguage() + "_" + wanted.getCountry(),
            baseName + "_" + wanted.getLanguage(),
            baseName);
    for (final var candidate : candidates) {
      final var resource = new ClassPathResource(LOCATION + candidate + ".md");
      if (resource.exists()) {
        return resource;
      }
    }
    throw new IllegalStateException(
        "No " + LOCATION + baseName + ".md on the classpath; core ships one, so it was excluded");
  }

  /**
   * The same file, read.
   *
   * <p>For the one caller that has to hand over text rather than a resource — a property it
   * contributes to the environment — and so cannot leave the reading to whoever consumes it.
   */
  public static String text(final String baseName, final Locale locale) {
    try {
      return resource(baseName, locale).getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + LOCATION + baseName, e);
    }
  }
}
