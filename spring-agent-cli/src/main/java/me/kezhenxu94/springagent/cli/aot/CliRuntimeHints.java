package me.kezhenxu94.springagent.cli.aot;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Resources the command line's logging configuration reads at startup.
 *
 * <p>Spring Boot registers a hint for the {@code logback.xml} it detects, and stops there. This one
 * is nothing but {@code <include resource=...>} lines, and an include whose target is not in the
 * image does not fail — Logback prints a warning and carries on without it. On this application
 * that is worse than an error: the missing file is Boot's {@code defaults.xml}, which defines the
 * conversion words the appender's pattern uses, so Logback falls back to reporting its own status
 * on standard output. Which is the user's conversation with the agent.
 */
public class CliRuntimeHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
    hints
        .resources()
        // Boot's own defaults and console-appender definitions, included by logback.xml and by
        // logback-appender-CONSOLE.xml.
        .registerPattern("org/springframework/boot/logging/logback/*.xml")
        // Ours. Both, not only the one this build defaults to: which is included is decided by
        // LOG_APPENDER when the binary runs, long after the image is built.
        .registerPattern("logback-appender-*.xml");
  }
}
