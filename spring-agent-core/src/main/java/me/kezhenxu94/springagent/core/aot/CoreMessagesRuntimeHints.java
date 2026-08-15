package me.kezhenxu94.springagent.core.aot;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * The bundle behind {@code CoreMessages}, whose locale is only known when the binary runs, so the
 * closed-world analysis cannot tell which of its siblings will be asked for.
 */
public class CoreMessagesRuntimeHints implements RuntimeHintsRegistrar {

  private static final String MESSAGES_BUNDLE = "core.messages";

  @Override
  public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
    hints.resources().registerResourceBundle(MESSAGES_BUNDLE);
  }
}
