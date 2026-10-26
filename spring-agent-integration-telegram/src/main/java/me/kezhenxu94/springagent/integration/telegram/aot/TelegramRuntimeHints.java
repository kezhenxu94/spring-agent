package me.kezhenxu94.springagent.integration.telegram.aot;

import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * What a native image cannot find out from the bytecode: the Bot API types Jackson binds
 * reflectively, and every resource this module reads by name.
 *
 * <p>A missing one fails the quiet way — a type that binds as nothing, a note that is never found —
 * while the JVM build works, which is why each is listed here rather than left to be noticed.
 */
public class TelegramRuntimeHints implements RuntimeHintsRegistrar {

  private final BindingReflectionHintsRegistrar binding = new BindingReflectionHintsRegistrar();

  @Override
  public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
    TelegramTypes.ALL.forEach(type -> binding.registerReflectionHints(hints.reflection(), type));

    hints.resources().registerResourceBundle("telegram.messages");
    hints.resources().registerPattern("telegram/prompts/*.md");
    hints.resources().registerPattern("telegram/prompts/tools/*.md");
    hints.resources().registerPattern("telegram/tools.properties");
    hints.resources().registerPattern("telegram/tools_*.properties");
    // Read by the name each must have, never by listing — see TelegramUpdates.
    hints.resources().registerPattern("telegram/welcome*.md");
    hints.resources().registerPattern("telegram/updates/*.md");
  }
}
