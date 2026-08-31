package me.kezhenxu94.springagent.core.tools.credentials;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.kezhenxu94.springagent.core.dao.models.ShellCredential;
import me.kezhenxu94.springagent.core.dao.repo.ShellCredentialRepo;
import me.kezhenxu94.springagent.core.security.AesGcmSealer;
import me.kezhenxu94.springagent.core.security.AesGcmSealer.SealingException;

/**
 * Keeps credentials in the application's own database, encrypted.
 *
 * <p>For a sandbox with nothing like a Kubernetes Secret behind it. The rows are useless on their
 * own: values are sealed with AES-GCM under a key that lives in configuration, not in the database,
 * so a dump of the table yields nothing without it.
 *
 * <p>Encryption is not decoration here. The point of this store is that a credential never reaches
 * a disk in the clear, and a plaintext column would put it on the database's disk instead of the
 * sandbox host's.
 */
public class DatabaseCredentialStore implements ShellCredentialStore {

  /** Names this key in the message when it is missing or unusable. */
  private static final String WHAT = "shell credentials";

  private final ShellCredentialRepo repo;
  private final AesGcmSealer sealer;

  /**
   * @param base64Key a base64-encoded AES key of 128, 192 or 256 bits. Rejected outright when
   *     absent, because the alternative is storing secrets in the clear.
   */
  public DatabaseCredentialStore(final ShellCredentialRepo repo, final String base64Key) {
    this.repo = repo;
    this.sealer = new AesGcmSealer(base64Key, WHAT);
  }

  @Override
  public void put(final String userId, final String name, final String value) {
    repo.save(
        ShellCredential.builder()
            .id(ShellCredential.idFor(userId, name))
            .ownerId(userId)
            .name(name)
            .value(seal(value))
            .updatedAt(Instant.now())
            .build());
  }

  @Override
  public List<Entry> list(final String userId) {
    return repo.findByOwnerId(userId).stream()
        .map(credential -> new Entry(credential.name(), credential.updatedAt()))
        .toList();
  }

  @Override
  public boolean delete(final String userId, final String name) {
    if (repo.findByOwnerIdAndName(userId, name).isEmpty()) {
      return false;
    }
    repo.deleteByOwnerIdAndName(userId, name);
    return true;
  }

  @Override
  public Map<String, String> resolve(final String userId) {
    final var resolved = new LinkedHashMap<String, String>();
    for (final var credential : repo.findByOwnerId(userId)) {
      resolved.put(credential.name(), open(credential.name(), credential.value()));
    }
    return resolved;
  }

  // The two below translate the sealer's failures into this store's own exception type, which is
  // what CredentialTools catches and turns into something the model can read.

  private String seal(final String plaintext) {
    try {
      return sealer.seal(plaintext);
    } catch (final SealingException e) {
      throw new CredentialStoreException("Failed to encrypt the credential", e);
    }
  }

  private String open(final String name, final String stored) {
    try {
      return sealer.open("credential " + name, stored);
    } catch (final SealingException e) {
      throw new CredentialStoreException(e.getMessage(), e);
    }
  }
}
