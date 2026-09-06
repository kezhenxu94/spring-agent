package me.kezhenxu94.springagent.core.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * That every page of prose a module hands the model has a translation beside it.
 *
 * <p>These are the largest model-facing text in the system after the system prompt — a reference
 * guide returned as a tool result runs to several thousand characters — and they are also the
 * easiest to forget, because {@code LocalizedPrompt} falls back to the base file rather than
 * failing. A guide left untranslated is six thousand characters of English dropped into the middle
 * of a Chinese conversation, which is more than enough to decide what language the model thinks in
 * for the rest of the turn.
 *
 * <p>Files rather than resolved text, so the failure names the file to write.
 */
public abstract class AbstractPromptFilesTranslatedTest {

  /**
   * The classpath directories holding prose the model reads, e.g. {@code core/prompts/}. Trailing
   * slash included; not searched recursively, since a per-tool description directory has its own
   * check in the parity test.
   */
  protected abstract List<String> promptLocations();

  /** The locale suffixes this module ships. */
  protected List<String> translations() {
    return List.of("zh_CN");
  }

  /**
   * Base files with no translation of their own on purpose — a page whose whole content is a
   * grammar or a table of API constants, where there is no prose to render.
   */
  protected List<String> untranslatedAllowed() {
    return List.of();
  }

  @Test
  @DisplayName("every prompt file the module ships has a translation beside it")
  void everyPromptIsTranslated() throws Exception {
    final var resolver = new PathMatchingResourcePatternResolver();
    final var missing = new TreeSet<String>();
    var found = 0;

    for (final var location : promptLocations()) {
      final var names = new TreeSet<String>();
      for (final var resource : resolver.getResources("classpath*:" + location + "*.md")) {
        final var filename = resource.getFilename();
        if (filename != null) {
          names.add(filename);
        }
      }
      for (final var filename : names) {
        final var base = filename.substring(0, filename.length() - ".md".length());
        if (base.indexOf('_') >= 0 || untranslatedAllowed().contains(filename)) {
          continue;
        }
        found++;
        for (final var suffix : translations()) {
          if (!names.contains(base + "_" + suffix + ".md")) {
            missing.add(location + base + "_" + suffix + ".md");
          }
        }
      }
    }

    assertThat(found).as("no prompt files were found at all, so this proves nothing").isPositive();
    assertThat(missing)
        .as("these pages reach the model in English however the workspace is configured")
        .isEmpty();
  }
}
