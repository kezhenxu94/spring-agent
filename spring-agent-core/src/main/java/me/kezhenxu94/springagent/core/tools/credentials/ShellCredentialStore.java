package me.kezhenxu94.springagent.core.tools.credentials;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Where a user's shell credentials are kept.
 *
 * <p>Split out from {@link CredentialTools} so the tools the model sees are written once and each
 * shell backend supplies whatever storage it can reach: the Kubernetes module a per-user Secret,
 * the Docker module the application's own database.
 *
 * <p>Implementations are shared across users by design and must therefore be thread-safe. Every
 * method takes the user id rather than the implementation holding one.
 */
public interface ShellCredentialStore {

  /** Stores {@code value} under {@code name}, replacing any previous value. */
  void put(String userId, String name, String value);

  /** The user's credential names and when each was last written. Never their values. */
  List<Entry> list(String userId);

  /** Removes {@code name}. Returns whether there was anything to remove. */
  boolean delete(String userId, String name);

  /**
   * Every credential the user has, for injecting into a sandbox as it is created.
   *
   * <p>This is the one method that hands back plaintext, and the only caller is the code building a
   * sandbox. The Kubernetes backend never needs it — its Pod references the Secret directly, so
   * values never pass through this application at all — but it is answerable there too.
   */
  Map<String, String> resolve(String userId);

  /**
   * A stored credential, minus its value.
   *
   * @param updatedAt when it was last written, or {@code null} if the store does not know.
   */
  record Entry(String name, Instant updatedAt) {}

  /** A store refused the operation, with a message fit to hand back to the model. */
  class CredentialStoreException extends RuntimeException {
    public CredentialStoreException(final String message) {
      super(message);
    }

    public CredentialStoreException(final String message, final Throwable cause) {
      super(message, cause);
    }
  }
}
