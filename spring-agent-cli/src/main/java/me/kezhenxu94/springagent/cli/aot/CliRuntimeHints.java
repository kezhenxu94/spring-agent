package me.kezhenxu94.springagent.cli.aot;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Resources the command line's logging configuration reads at startup.
 *
 * <p>Boot registers the {@code logback.xml} it detects and not the files it includes, and a missing
 * include is a warning rather than an error — after which Logback reports its own status on
 * standard output, which here is the user's conversation with the agent.
 */
public class CliRuntimeHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
    hints
        .resources()
        // Boot's defaults and console-appender definitions.
        .registerPattern("org/springframework/boot/logging/logback/*.xml")
        // Ours — both, since LOG_APPENDER picks between them when the binary runs.
        .registerPattern("logback-appender-*.xml");
  }
}
