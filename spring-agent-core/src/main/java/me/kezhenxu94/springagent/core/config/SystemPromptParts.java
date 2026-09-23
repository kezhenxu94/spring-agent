package me.kezhenxu94.springagent.core.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.ResourceUtils;

/**
 * Lets {@code app.ai.system-prompt} be written as several pieces instead of one string, each either
 * literal text or a resource location whose content is read in — and keeps them apart rather than
 * joining them, so a run built from them carries one {@code SystemMessage} per piece.
 *
 * <p>An operator's own house rules, and one of core's translations of the base prompt, are two
 * different things kept in two different places — a paragraph in {@code application.yaml} and a
 * markdown file this project ships — and until this existed the only way to combine them was to
 * paste one into the other by hand, which is a copy that drifts the moment core's own file changes.
 * A YAML list lets a deployment name the pieces instead of copying either:
 *
 * <pre>{@code
 * app:
 *   ai:
 *     system-prompt:
 *       - "You work for Acme. Always answer in Acme's house style."
 *       - classpath:core/prompts/system-prompt_zh_CN.md
 * }</pre>
 *
 * <p>Each element is read as literal text unless it looks like a resource location — {@code
 * classpath:}, {@code file:}, an {@code http(s)://} URL, anything {@link ResourceUtils#isUrl}
 * recognises — in which case its content is substituted in whole. {@code ${app.locale}} inside a
 * resource location is expanded first, to the workspace's configured locale or the host's own where
 * nothing is configured, which is what lets one list reach for whichever translation of a shared
 * file a deployment's own locale calls for without naming it literally:
 *
 * <pre>{@code
 * system-prompt:
 *   - classpath:core/prompts/system-prompt_${app.locale}.md
 * }</pre>
 *
 * <p><b>The resolved pieces are never joined.</b> They are written to a property of their own —
 * {@code app.ai.system-prompt-parts[0]}, {@code [1]} and so on, bound as {@link
 * SpringAgentProperties.Ai#systemPromptParts()} — rather than back to {@code app.ai.system-prompt},
 * because joining them into one string would be exactly the copy this class exists to avoid making
 * a deployment write by hand: {@code SpringAgent} renders each piece and hands the model one {@code
 * SystemMessage} per piece, which is what lets a provider with per-request prompt caching —
 * Anthropic's {@code cache_control} chief among them — put a cache boundary between a piece that
 * never changes and one that carries this request's own identity, something one joined string gives
 * it no seam to do.
 *
 * <p><b>A single plain string is left untouched, and nothing is written for it.</b> This is what
 * keeps every deployment that configures nothing, or configures one string the way this property
 * always worked, on exactly the behaviour it had before this class existed — including a string
 * that happens to contain a comma, which is why parts are found by looking for {@code
 * app.ai.system-prompt[0]}, {@code [1]} and so on directly rather than by binding the property to a
 * {@code List<String>}: Spring Boot's own binder converts a lone scalar to a single-element
 * collection by splitting it on commas when no indexed keys are present, and a system prompt is
 * prose, not a comma-separated list. A lone element that is itself a resource location is the one
 * exception — there is exactly one piece, but it still has to be read rather than sent as its own
 * location string, so it is still written to {@code system-prompt-parts[0]}.
 */
public class SystemPromptParts implements EnvironmentPostProcessor, Ordered {

  private static final String KEY = "app.ai.system-prompt";
  private static final String PARTS_KEY = "app.ai.system-prompt-parts";
  private static final String LOCALE_PLACEHOLDER = "${app.locale}";
  private static final String SOURCE_NAME = "springAgentSystemPromptParts";

  @Override
  public void postProcessEnvironment(
      final ConfigurableEnvironment environment, final SpringApplication application) {
    var parts = indexedParts(environment);
    if (parts.isEmpty()) {
      final var single = environment.getProperty(KEY);
      if (single == null || !isResourceLocation(single)) {
        // Nothing configured, or one plain string: both are app.ai.system-prompt's job, unchanged.
        return;
      }
      parts = List.of(single);
    }
    final var loader = new DefaultResourceLoader();
    final var locale = locale(environment);
    final var defaults = new LinkedHashMap<String, Object>();
    for (int i = 0; i < parts.size(); i++) {
      defaults.put(PARTS_KEY + "[" + i + "]", resolvePart(parts.get(i), locale, loader));
    }
    environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, defaults));
  }

  /** Every {@code app.ai.system-prompt[N]} in order, empty where the property is not a list. */
  private static List<String> indexedParts(final ConfigurableEnvironment environment) {
    final var parts = new ArrayList<String>();
    for (int i = 0; environment.containsProperty(KEY + "[" + i + "]"); i++) {
      parts.add(environment.getProperty(KEY + "[" + i + "]"));
    }
    return parts;
  }

  /**
   * {@code app.locale} as configured, or the host's own where nothing is — see {@link
   * SpringAgentProperties.Ai#defaultPrompt}, which reasons the same way for the built-in file.
   */
  private static String locale(final ConfigurableEnvironment environment) {
    final var configured = environment.getProperty("app.locale");
    return configured != null && !configured.isBlank()
        ? configured
        : Locale.getDefault().toString();
  }

  /**
   * Resource locations only, decided before {@code ${app.locale}} is expanded — a literal part is
   * read exactly as written, and a handwritten paragraph mentioning a shell variable in {@code
   * ${...}} syntax must not be mistaken for one just because it resembles a placeholder.
   */
  private static boolean isResourceLocation(final String part) {
    return part != null && ResourceUtils.isUrl(part.strip());
  }

  private static String resolvePart(
      final String rawPart, final String locale, final ResourceLoader loader) {
    if (!isResourceLocation(rawPart)) {
      return rawPart;
    }
    final var location = rawPart.strip().replace(LOCALE_PLACEHOLDER, locale);
    try (var stream = loader.getResource(location).getInputStream()) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      throw new UncheckedIOException(
          "app.ai.system-prompt named " + location + ", which could not be read", e);
    }
  }

  /**
   * Last, so that {@code application.yaml} has already been loaded and {@code
   * app.ai.system-prompt[0]} and company are there to read.
   */
  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
