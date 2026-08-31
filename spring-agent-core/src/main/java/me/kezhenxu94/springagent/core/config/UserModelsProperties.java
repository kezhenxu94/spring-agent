package me.kezhenxu94.springagent.core.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A user's own choice of chat model, replacing the application's for their runs only.
 *
 * <p>Off by default, and off by having no key rather than by a flag of its own: the tokens users
 * register here are bearer credentials for somebody else's paid endpoint, and the only alternative
 * to sealing them is a column holding them in the clear. The same reasoning as {@code
 * app.ai.tools.shell.type} defaulting to {@code none} — a feature that cannot be run safely is not
 * run. {@link ConditionalOnUserModels} is what reads that.
 *
 * <p>The embedding model is deliberately not part of this. The knowledge base is shared between
 * users and its collections are built with one embedding model, so letting a user change theirs
 * would silently invalidate vectors that are not theirs.
 *
 * <p>Its own properties class rather than another component of {@code SpringAgentProperties.Ai},
 * following {@link ShellToolsProperties}: a self-contained feature binds its own settings.
 *
 * @param encryptionKey a base64-encoded AES key of 128, 192 or 256 bits, sealing the API tokens.
 *     Blank — the default — leaves the whole feature off, tools and all. Rotating it does not
 *     re-seal what is already stored: those rows stop being readable and say so.
 * @param maxPerUser how many endpoints one user may register, so that a table anyone can write to
 *     has a ceiling
 * @param cacheSize how many distinct endpoints are held as live clients at once. Each is an HTTP
 *     client with a connection pool, so this bounds sockets rather than memory; the least recently
 *     used is dropped when a new one does not fit
 * @param probeTimeout how long the connection test before saving waits. Short on purpose: it is a
 *     single tiny completion, and a user mistyping a URL should be told so rather than left
 *     watching the full {@code spring.ai.openai.chat.timeout}
 */
@ConfigurationProperties(prefix = "app.ai.user-models")
public record UserModelsProperties(
    String encryptionKey, int maxPerUser, int cacheSize, Duration probeTimeout) {

  public static final int DEFAULT_MAX_PER_USER = 10;
  public static final int DEFAULT_CACHE_SIZE = 50;
  public static final Duration DEFAULT_PROBE_TIMEOUT = Duration.ofSeconds(30);

  public UserModelsProperties {
    // Zero means "unset" rather than "none": a record's primitives default to zero when the
    // property is absent, and a ceiling of zero would refuse every registration while the feature
    // still claimed to be on.
    if (maxPerUser <= 0) {
      maxPerUser = DEFAULT_MAX_PER_USER;
    }
    if (cacheSize <= 0) {
      cacheSize = DEFAULT_CACHE_SIZE;
    }
    if (probeTimeout == null || probeTimeout.isNegative() || probeTimeout.isZero()) {
      probeTimeout = DEFAULT_PROBE_TIMEOUT;
    }
  }

  /** Whether a user may register a model at all, which is to say whether a key was given. */
  public boolean enabled() {
    return encryptionKey != null && !encryptionKey.isBlank();
  }
}
