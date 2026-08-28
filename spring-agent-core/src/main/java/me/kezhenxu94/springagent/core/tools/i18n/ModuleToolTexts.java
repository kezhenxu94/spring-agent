package me.kezhenxu94.springagent.core.tools.i18n;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.LocalizedPrompt;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * A module's tool translations, read off its own classpath.
 *
 * <p>Two stores, split by kind rather than by length, so there is never a question about where a
 * given string belongs:
 *
 * <ul>
 *   <li>a tool's own description is a file, {@code <promptDirectory><ToolName>.md}. These are
 *       paragraphs — averaging four hundred characters and running to two and a half thousand — and
 *       a paragraph of instructions folded into a properties value is neither writable nor
 *       reviewable, which is the same reason core's prompts are files.
 *   <li>a parameter's description is a property, keyed {@code <ToolName>.<parameterName>}. These
 *       average under sixty characters and there are three hundred of them; a file each would be
 *       three hundred files.
 * </ul>
 *
 * <p>Both resolve the way a {@link java.util.ResourceBundle} does — exact locale, then language
 * alone, then the base — so adding a translation is adding a file. The properties are
 * <em>merged</em> along that chain rather than the most specific winning outright, which is what
 * lets the base file carry an English correction to a description this module cannot edit,
 * upstream's being in a jar, and have every locale inherit it.
 *
 * <p>Deliberately neither a {@code MessageSource} nor a {@link java.util.ResourceBundle}. A message
 * source cannot enumerate, and enumeration is what makes {@link #covers} cheap and the test that no
 * key names a tool that no longer exists possible at all; it would also have to be named in every
 * application's {@code spring.messages.basename}, and an application that forgot would get silent
 * English with nothing to say why. A resource bundle can enumerate but consults {@link
 * Locale#getDefault()} before the base bundle, so an English deployment on a Chinese host would
 * serve Chinese tool descriptions — the trap {@code FeishuMessages} closes with {@code
 * setFallbackToSystemLocale(false)}. Loading the properties directly has neither problem.
 */
@Slf4j
public class ModuleToolTexts implements ToolTexts {

  /** Where this module's per-tool description files live, e.g. {@code core/prompts/tools/}. */
  @Getter private final String promptDirectory;

  @Getter private final Locale locale;

  /** The parameter descriptions, base first so a more specific translation overwrites it. */
  private final Properties parameters;

  /** The tools this bundle has at least one parameter for, so {@link #covers} is a lookup. */
  private final Set<String> toolsWithParameters;

  /**
   * What {@link #description} found, remembered for good — the negative answer included.
   *
   * <p>Permanence is the point, not the speed. The tool search fingerprints the descriptions it is
   * given and re-indexes the whole set when that fingerprint changes, so an answer that could
   * differ between two requests of one process would clear and re-embed every tool on every
   * tool-calling round. Probing the classpath once and remembering it removes the only way that
   * could happen: a transient failure of {@code exists()} on a later probe.
   */
  private final ConcurrentHashMap<String, Optional<String>> descriptions =
      new ConcurrentHashMap<>();

  /**
   * @param bundleBase the parameter bundle without locale suffix or extension, e.g. {@code
   *     core/tools}
   * @param promptDirectory where the description files live, trailing slash included
   * @param locale which language to serve, the host's when {@code null}
   */
  public ModuleToolTexts(
      final String bundleBase, final String promptDirectory, final Locale locale) {
    this.promptDirectory = promptDirectory;
    this.locale = locale == null ? Locale.getDefault() : locale;
    this.parameters = load(bundleBase, this.locale);
    this.toolsWithParameters =
        this.parameters.stringPropertyNames().stream()
            .filter(key -> key.indexOf('.') > 0)
            .map(key -> key.substring(0, key.indexOf('.')))
            .collect(Collectors.toUnmodifiableSet());
    // Which language the tools will describe themselves in, and how much of it was found. Logged
    // because the failure is otherwise invisible: a locale that resolved to the wrong thing, or a
    // bundle left out of the jar, both read as "the tools are still in English".
    log.info(
        "Tool translations for {}: locale {}, {} parameter(s) over {} tool(s), descriptions from"
            + " {}",
        bundleBase,
        this.locale,
        this.parameters.size(),
        this.toolsWithParameters.size(),
        promptDirectory);
  }

  @Override
  public String description(final String toolName) {
    return descriptions
        .computeIfAbsent(toolName, name -> Optional.ofNullable(readDescription(name)))
        .orElse(null);
  }

  @Override
  public String parameter(final String toolName, final String parameterName) {
    return blankToNull(parameters.getProperty(toolName + "." + parameterName));
  }

  @Override
  public boolean covers(final String toolName) {
    return toolsWithParameters.contains(toolName) || description(toolName) != null;
  }

  /** Every parameter key this module holds, for the test that each still names a real parameter. */
  public Set<String> parameterKeys() {
    return parameters.stringPropertyNames();
  }

  private String readDescription(final String toolName) {
    return LocalizedPrompt.find(promptDirectory, toolName, locale)
        .map(ModuleToolTexts::read)
        .map(ModuleToolTexts::blankToNull)
        .orElse(null);
  }

  /**
   * The base properties with each more specific translation merged over it.
   *
   * <p>Least specific first: {@link Properties#putAll} overwrites, so the exact locale wins the
   * keys it states and inherits every key it does not.
   */
  private static Properties load(final String bundleBase, final Locale locale) {
    final var merged = new Properties();
    for (final var candidate :
        List.of(
            bundleBase,
            bundleBase + "_" + locale.getLanguage(),
            bundleBase + "_" + locale.getLanguage() + "_" + locale.getCountry())) {
      final var resource = new ClassPathResource(candidate + ".properties");
      if (!resource.exists()) {
        continue;
      }
      try (var stream = resource.getInputStream()) {
        final var properties = new Properties();
        properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        merged.putAll(properties);
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to read " + candidate + ".properties", e);
      }
    }
    return merged;
  }

  private static String read(final Resource resource) {
    try {
      return resource.getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + resource, e);
    }
  }

  /**
   * Blank counts as absent, and this is load-bearing rather than tidiness.
   *
   * <p>{@code DefaultToolDefinition.Builder.build()} answers a description with no text in it by
   * making one up from the tool's name — {@code FeishuCreateBitable} becomes "Feishu Create
   * Bitable". So an empty translation file would neither fail nor leave the English in place: it
   * would quietly replace eight hundred characters telling the model when to reach for the tool
   * with three words repeating what it is already called.
   */
  private static String blankToNull(final String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
