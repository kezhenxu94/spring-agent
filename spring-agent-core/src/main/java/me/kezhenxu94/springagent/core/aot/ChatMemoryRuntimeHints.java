package me.kezhenxu94.springagent.core.aot;

import me.kezhenxu94.springagent.core.config.ChatMemoryConfiguration;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Hints for the two things {@link ChatMemoryConfiguration} resolves from runtime strings, which AOT
 * therefore cannot infer: the schema script it loads as a {@code ClassPathResource} and the JDBC
 * driver it names through {@code DatabaseDriver.fromJdbcUrl}.
 *
 * <p>Imported from that configuration rather than registered globally, so the hints are only
 * contributed when the {@code jdbc} chat memory branch is the one baked into the image.
 */
public class ChatMemoryRuntimeHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
    // The platform segment of the path comes from the configured JDBC URL, so match the whole set
    // rather than guessing which dialect this image will be pointed at.
    hints
        .resources()
        .registerPattern("org/springframework/ai/chat/memory/repository/jdbc/schema-*.sql");

    // Only SQLite: it is the default URL, and it is the only driver on the runtime classpath, so a
    // URL naming any other database fails on the driver regardless of what is registered here.
    hints
        .reflection()
        .registerTypeIfPresent(
            classLoader, "org.sqlite.JDBC", MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
  }
}
