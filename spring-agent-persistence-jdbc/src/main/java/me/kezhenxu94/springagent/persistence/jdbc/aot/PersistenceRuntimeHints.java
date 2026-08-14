package me.kezhenxu94.springagent.persistence.jdbc.aot;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Hints for the two things this backend resolves from runtime strings, which AOT therefore cannot
 * infer: the Hibernate dialect and the JDBC driver.
 *
 * <p>The dialect is named by {@code spring.jpa.database-platform} and resolved with {@code
 * Class.forName}, so nothing in the bytecode leads to it.
 *
 * <p>{@code hibernate-community-dialects} ships no reachability metadata of its own and exposes its
 * dialects only through {@code META-INF/services}, so without this the native image fails at {@code
 * entityManagerFactory} with {@code Unable to load class
 * [org.hibernate.community.dialect.SQLiteDialect]}.
 *
 * <p>Registered by name, and only the dialect the application defaults to: pointing {@code
 * spring.jpa.database-platform} at another one in a native image needs that class registered here
 * as well, since AOT cannot know at build time what the property will say at runtime.
 */
public class PersistenceRuntimeHints implements RuntimeHintsRegistrar {

  private static final String SQLITE_DIALECT = "org.hibernate.community.dialect.SQLiteDialect";
  private static final String SQLITE_DRIVER = "org.sqlite.JDBC";

  @Override
  public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
    // Public constructors, which is how Hibernate builds it — DialectFactoryImpl selects the
    // (DialectResolutionInfo) one, falling back to the no-arg constructor.
    hints
        .reflection()
        .registerTypeIfPresent(
            classLoader, SQLITE_DIALECT, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);

    // Reached by name from the JDBC URL, through DatabaseDriver.fromJdbcUrl. Only SQLite: it is
    // what spring.datasource.url defaults to, and the only driver on the runtime classpath, so a
    // URL naming any other database fails on the driver whatever is registered here.
    //
    // The schema scripts the chat memory repository loads are deliberately not registered here:
    // Spring AI's own JdbcChatMemoryRepositoryRuntimeHints covers them, contributed through the
    // aot.factories in spring-ai-model-chat-memory-repository-jdbc.
    hints
        .reflection()
        .registerTypeIfPresent(
            classLoader, SQLITE_DRIVER, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
  }
}
