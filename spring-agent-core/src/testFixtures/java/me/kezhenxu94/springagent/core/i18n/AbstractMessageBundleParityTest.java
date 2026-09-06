package me.kezhenxu94.springagent.core.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * That a module's bundle says the same things in every language it claims to speak.
 *
 * <p>What these bundles hold is not chrome. It is what a tool <em>answers</em> — the sentence the
 * model reasons from and then writes the user's answer out of — so a key added in English and left
 * out of the translation is not a missing label: it is an English sentence in the middle of a
 * Chinese turn, and the model follows it. That is the whole of why the model reasons in English in
 * a workspace that asked for something else, and it is invisible until somebody reads a transcript.
 *
 * <p>Nothing here reads the bundle through a {@code MessageSource}, deliberately. A message source
 * answers a missing key by falling back, which is the behaviour that makes the gap invisible in the
 * first place; the files are compared directly so that the gap is the failure.
 */
public abstract class AbstractMessageBundleParityTest {

  /** A MessageFormat argument, whose index has to survive translation. */
  private static final Pattern ARGUMENT = Pattern.compile("\\{(\\d+)[^}]*}");

  /**
   * The bundle as a classpath path without locale suffix or extension, e.g. {@code core/messages}.
   */
  protected abstract String bundlePath();

  /** The locale suffixes this module ships, e.g. {@code zh_CN}. */
  protected List<String> translations() {
    return List.of("zh_CN");
  }

  /**
   * Keys whose value is legitimately the same in every language — an identifier, a symbol, a name
   * the model has to type back. Anything else being identical means the line was copied and not
   * translated.
   */
  protected Set<String> sameInEveryLanguage() {
    return Set.of();
  }

  private Properties load(final String suffix) throws Exception {
    final var name = bundlePath() + (suffix.isEmpty() ? "" : "_" + suffix) + ".properties";
    final var resource = new ClassPathResource(name);
    assertThat(resource.exists()).as("%s is not on the test classpath", name).isTrue();
    final var properties = new Properties();
    try (var stream = resource.getInputStream()) {
      properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
    return properties;
  }

  @Test
  @DisplayName("the base bundle is there and is not empty, so the rest cannot pass vacuously")
  void baseBundleIsNotEmpty() throws Exception {
    assertThat(load("")).as("%s.properties holds nothing", bundlePath()).isNotEmpty();
  }

  @Test
  @DisplayName("every message the module can hand back has a translation in every language")
  void everyKeyIsTranslated() throws Exception {
    final var base = load("");
    for (final var suffix : translations()) {
      final var translated = load(suffix);
      final var missing = new TreeSet<>(base.stringPropertyNames());
      missing.removeAll(translated.stringPropertyNames());
      assertThat(missing)
          .as(
              "%s_%s.properties leaves these in English; each one is a sentence the model reads and"
                  + " answers from",
              bundlePath(), suffix)
          .isEmpty();
    }
  }

  @Test
  @DisplayName("no translation names a key the base bundle does not have")
  void noOrphanedTranslation() throws Exception {
    final var base = load("");
    for (final var suffix : translations()) {
      final var orphans = new TreeSet<>(load(suffix).stringPropertyNames());
      orphans.removeAll(base.stringPropertyNames());
      assertThat(orphans)
          .as(
              "%s_%s.properties translates keys nothing looks up, which is a rename nobody"
                  + " finished",
              bundlePath(), suffix)
          .isEmpty();
    }
  }

  @Test
  @DisplayName("a translated line is not the English line copied over")
  void everyTranslationSaysSomethingOfItsOwn() throws Exception {
    final var base = load("");
    final var allowed = sameInEveryLanguage();
    for (final var suffix : translations()) {
      final var translated = load(suffix);
      assertSoftly(
          softly ->
              base.stringPropertyNames().stream()
                  .filter(key -> !allowed.contains(key))
                  .filter(key -> translated.getProperty(key) != null)
                  .forEach(
                      key ->
                          softly
                              .assertThat(translated.getProperty(key))
                              .as(
                                  "%s is identical in %s and English, so it was copied rather than"
                                      + " translated",
                                  key, suffix)
                              .isNotEqualTo(base.getProperty(key))));
    }
  }

  /**
   * A translation that drops an argument is worse than one that is missing: the message resolves,
   * reads as a finished sentence, and quietly leaves out the id or the count the model needed.
   */
  @Test
  @DisplayName("a translation carries every argument the English one does")
  void everyTranslationKeepsItsArguments() throws Exception {
    final var base = load("");
    for (final var suffix : translations()) {
      final var translated = load(suffix);
      assertSoftly(
          softly ->
              base.stringPropertyNames().stream()
                  .filter(key -> translated.getProperty(key) != null)
                  .forEach(
                      key ->
                          softly
                              .assertThat(arguments(translated.getProperty(key)))
                              .as("%s loses an argument in %s", key, suffix)
                              .isEqualTo(arguments(base.getProperty(key)))));
    }
  }

  private static Set<String> arguments(final String message) {
    final var found = new TreeSet<String>();
    final var matcher = ARGUMENT.matcher(message);
    while (matcher.find()) {
      found.add(matcher.group(1));
    }
    return found;
  }
}
