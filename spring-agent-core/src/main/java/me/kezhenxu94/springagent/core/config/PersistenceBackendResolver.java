package me.kezhenxu94.springagent.core.config;

import me.kezhenxu94.springagent.core.config.PersistenceProperties.Type;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * Decides which persistence backend is in play, for the two places that need to agree on it: {@link
 * ConditionalOnPersistenceBackend}, which registers one backend's beans, and {@link
 * PersistenceAutoConfigurationFilter}, which keeps the other backend's auto-configuration out. They
 * are evaluated by different Spring mechanisms and cannot share an annotation, so they share this.
 *
 * <p>The backends live in their own modules, and each module is identified by its
 * auto-configuration class rather than by {@code JpaRepository} or {@code MongoRepository}: a
 * consumer may well have Spring Data JPA on the classpath for reasons of their own, and that is not
 * a request for this SDK to persist through it. Resolved by name, so this module keeps no compile
 * dependency on either.
 */
final class PersistenceBackendResolver {

  static final String TYPE_PROPERTY = "app.persistence.type";

  private static final String JDBC_MODULE =
      "me.kezhenxu94.springagent.persistence.jdbc.JdbcPersistenceAutoConfiguration";
  private static final String MONGODB_MODULE =
      "me.kezhenxu94.springagent.persistence.mongodb.MongoPersistenceAutoConfiguration";

  private PersistenceBackendResolver() {}

  static boolean present(final Type type, final ClassLoader classLoader) {
    return ClassUtils.isPresent(type == Type.JDBC ? JDBC_MODULE : MONGODB_MODULE, classLoader);
  }

  /** The artifact a deployment has to add when it names a backend it did not depend on. */
  static String moduleOf(final Type type) {
    return type == Type.JDBC ? "spring-agent-persistence-jdbc" : "spring-agent-persistence-mongodb";
  }

  static Type configured(final Environment environment) {
    final var value = environment.getProperty(TYPE_PROPERTY);
    if (value == null || value.isBlank()) {
      return null;
    }
    return Type.valueOf(value.trim().toUpperCase());
  }

  /**
   * The selected backend.
   *
   * <p>{@code app.persistence.type} wins whenever it is set, so an application carrying both
   * modules — {@code spring-agent-app} does, so that one image can switch — behaves exactly as it
   * did before the backends were split into modules. Only when the property says nothing does the
   * classpath decide, which is what lets an SDK consumer depend on a single backend module and
   * configure nothing at all.
   *
   * <p>With both modules present and no property, the answer is {@link Type#JDBC}: the default this
   * project has always had, and the one that needs no server.
   */
  static Type resolve(final Environment environment, final ClassLoader classLoader) {
    final var configured = configured(environment);
    if (configured != null) {
      return configured;
    }
    if (present(Type.MONGODB, classLoader) && !present(Type.JDBC, classLoader)) {
      return Type.MONGODB;
    }
    return Type.JDBC;
  }
}
