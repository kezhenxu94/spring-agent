package me.kezhenxu94.springagent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.util.ClassUtils;

/**
 * The filter names Spring Boot's auto-configurations as strings, because it has to run before any
 * of them are loaded. Nothing else would notice a name going stale: an entry that no longer matches
 * anything degrades into silence, not into an error, and the first symptom would be a MongoDB
 * deployment failing on a missing {@code spring.datasource.url} — in production, since that is the
 * only place the MongoDB backend runs.
 *
 * <p>Every backend is on this module's test classpath (and only its test classpath) so that these
 * names are resolvable here at all. A backend whose artifacts are missing from it does not fail
 * this test — it removes cases from it, which is the same silence this test exists to catch.
 */
class PersistenceAutoConfigurationFilterTest {

  static Stream<String> filteredAutoConfigurations() {
    return PersistenceAutoConfigurationFilter.filteredAutoConfigurations().stream()
        .flatMap(Set::stream);
  }

  /**
   * Looked up as a resource rather than with {@code ClassUtils.isPresent}, which reports false for
   * a class that exists but cannot be initialised — several of these extend actuator types that are
   * deliberately absent here. The filter never loads these classes either; it compares their names
   * against the auto-configuration candidates, so the name existing is exactly the property under
   * test.
   */
  @ParameterizedTest
  @MethodSource("filteredAutoConfigurations")
  void eachFilteredAutoConfigurationStillExists(final String className) {
    final var resource = ClassUtils.convertClassNameToResourcePath(className) + ".class";
    assertThat(getClass().getClassLoader().getResource(resource))
        .as(
            "%s is no longer on the classpath, so PersistenceAutoConfigurationFilter has quietly "
                + "stopped filtering it. Find what it was renamed to and update the set.",
            className)
        .isNotNull();
  }
}
