package me.kezhenxu94.springagent.core.config;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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

  /**
   * The auto-configuration class each backend module is recognised by. A map rather than a
   * condition over {@link Type}, so that a backend added to the enum without a marker here fails
   * loudly on a missing entry instead of silently resolving to whichever branch was the fallback.
   */
  private static final Map<Type, String> MARKERS =
      new EnumMap<>(
          Map.of(
              Type.JDBC,
              "me.kezhenxu94.springagent.persistence.jdbc.JdbcPersistenceAutoConfiguration",
              Type.MONGODB,
              "me.kezhenxu94.springagent.persistence.mongodb.MongoPersistenceAutoConfiguration",
              Type.REDIS,
              "me.kezhenxu94.springagent.persistence.redis.RedisPersistenceAutoConfiguration"));

  private static final Map<Type, String> ARTIFACTS =
      new EnumMap<>(
          Map.of(
              Type.JDBC, "spring-agent-persistence-jdbc",
              Type.MONGODB, "spring-agent-persistence-mongodb",
              Type.REDIS, "spring-agent-persistence-redis"));

  private PersistenceBackendResolver() {}

  static boolean present(final Type type, final ClassLoader classLoader) {
    return ClassUtils.isPresent(MARKERS.get(type), classLoader);
  }

  /** The artifact a deployment has to add when it names a backend it did not depend on. */
  static String moduleOf(final Type type) {
    return ARTIFACTS.get(type);
  }

  static Type configured(final Environment environment) {
    final var value = environment.getProperty(TYPE_PROPERTY);
    if (value == null || value.isBlank()) {
      return null;
    }
    return Type.valueOf(value.trim().toUpperCase());
  }

  /** The backend modules this deployment carries. */
  static Set<Type> present(final ClassLoader classLoader) {
    return Arrays.stream(Type.values())
        .filter(type -> present(type, classLoader))
        .collect(Collectors.toCollection(() -> EnumSet.noneOf(Type.class)));
  }

  /**
   * The selected backend.
   *
   * <p>{@code app.persistence.type} wins whenever it is set, so an application carrying several
   * modules — {@code spring-agent-app} does, so that one image can switch — behaves exactly as it
   * did before the backends were split into modules. Only when the property says nothing does the
   * classpath decide, which is what lets an SDK consumer depend on a single backend module and
   * configure nothing at all.
   *
   * <p>Deliberately "exactly one present" rather than "the first one present in enum order": the
   * latter would make a deployment that already carried two modules and set no property change
   * backend the day a third was added to the enum. With more than one present and no property the
   * answer stays {@link Type#JDBC} — the default this project has always had, and the one that
   * needs no server.
   */
  static Type resolve(final Environment environment, final ClassLoader classLoader) {
    final var configured = configured(environment);
    if (configured != null) {
      return configured;
    }
    final var present = present(classLoader);
    return present.size() == 1 ? present.iterator().next() : Type.JDBC;
  }
}
