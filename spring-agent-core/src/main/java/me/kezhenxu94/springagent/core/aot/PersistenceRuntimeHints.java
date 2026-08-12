package me.kezhenxu94.springagent.core.aot;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Hints for the Hibernate dialect, which is named by {@code spring.jpa.database-platform} and
 * resolved with {@code Class.forName}, so nothing in the bytecode leads to it.
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

  @Override
  public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
    // Public constructors, which is how Hibernate builds it — DialectFactoryImpl selects the
    // (DialectResolutionInfo) one, falling back to the no-arg constructor.
    hints
        .reflection()
        .registerTypeIfPresent(
            classLoader, SQLITE_DIALECT, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
  }
}
