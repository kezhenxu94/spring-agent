package me.kezhenxu94.springagent.core.support;

import java.util.Locale;
import java.util.Set;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.config.TenantWrites;

/**
 * {@link TenantWrites} for a test, stated as the two things it is: who the admins are, and whether
 * everybody else may write the company scope.
 *
 * <p>Built over real {@link SpringAgentProperties} rather than a mock, so that the default a
 * deployment gets by saying nothing is the default a test gets — a stubbed {@code allowed} would go
 * on answering true long after the property that decides it had changed.
 */
public final class TestTenantWrites {

  private TestTenantWrites() {}

  /** The shipped default: only the named admins may write the company scope. */
  public static TenantWrites adminsOnly(final String... admins) {
    return of(false, admins);
  }

  /** {@code app.ai.non-admin-tenant-writes: true} — anybody may. */
  public static TenantWrites openToEveryone(final String... admins) {
    return of(true, admins);
  }

  private static TenantWrites of(final boolean open, final String... admins) {
    final var properties =
        new SpringAgentProperties(
            new SpringAgentProperties.Ai(
                Set.of(admins), open, null, null, null, null, null, null, null, null),
            Locale.ENGLISH,
            null,
            null);
    return new TenantWrites(properties, new Admins(properties));
  }
}
