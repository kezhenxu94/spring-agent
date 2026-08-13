package me.kezhenxu94.springagent.core.tools.credentials;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import me.kezhenxu94.springagent.core.dao.models.ShellCredential;
import me.kezhenxu94.springagent.core.dao.repo.ShellCredentialRepo;

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

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final ShellCredentialRepo repo;
  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  /**
   * @param base64Key a base64-encoded AES key of 128, 192 or 256 bits. Rejected outright when
   *     absent, because the alternative is storing secrets in the clear.
   */
  public DatabaseCredentialStore(final ShellCredentialRepo repo, final String base64Key) {
    if (base64Key == null || base64Key.isBlank()) {
      throw new IllegalArgumentException(
          "A base64-encoded AES encryption key is required to store shell credentials");
    }
    final byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(base64Key.trim());
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "The shell credential encryption key is not valid base64", e);
    }
    if (bytes.length != 16 && bytes.length != 24 && bytes.length != 32) {
      throw new IllegalArgumentException(
          "The shell credential encryption key must decode to 16, 24 or 32 bytes, but was "
              + bytes.length);
    }
    this.repo = repo;
    this.key = new SecretKeySpec(bytes, "AES");
  }

  @Override
  public void put(final String userId, final String name, final String value) {
    repo.save(
        ShellCredential.builder()
            .id(ShellCredential.idFor(userId, name))
            .ownerId(userId)
            .name(name)
            .value(encrypt(value))
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
      resolved.put(credential.name(), decrypt(credential.name(), credential.value()));
    }
    return resolved;
  }

  private String encrypt(final String plaintext) {
    try {
      final var nonce = new byte[NONCE_BYTES];
      random.nextBytes(nonce);
      final var cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      final var sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

      // Nonce first: it is not secret, and it has to come back with the ciphertext to decrypt it.
      final var out = new byte[nonce.length + sealed.length];
      System.arraycopy(nonce, 0, out, 0, nonce.length);
      System.arraycopy(sealed, 0, out, nonce.length, sealed.length);
      return Base64.getEncoder().encodeToString(out);
    } catch (final GeneralSecurityException e) {
      throw new CredentialStoreException("Failed to encrypt the credential", e);
    }
  }

  private String decrypt(final String name, final String stored) {
    try {
      final var raw = Base64.getDecoder().decode(stored);
      if (raw.length <= NONCE_BYTES) {
        throw new CredentialStoreException("Stored credential " + name + " is truncated");
      }
      final var nonce = Arrays.copyOfRange(raw, 0, NONCE_BYTES);
      final var sealed = Arrays.copyOfRange(raw, NONCE_BYTES, raw.length);
      final var cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
    } catch (final GeneralSecurityException | IllegalArgumentException e) {
      // Loudly rather than by dropping the entry: a rotated or mistyped key would otherwise show
      // up as a sandbox that quietly has no credentials in it.
      throw new CredentialStoreException(
          "Failed to decrypt credential " + name + "; the encryption key may have changed", e);
    }
  }
}
