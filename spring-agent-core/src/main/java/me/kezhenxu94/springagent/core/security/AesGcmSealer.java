package me.kezhenxu94.springagent.core.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Seals a secret so it can be written to a row without the row being worth anything on its own.
 *
 * <p>AES-GCM under a key that lives in configuration rather than in the database, so a dump of the
 * table yields nothing without it. A fresh 12-byte nonce is drawn for every write and prefixed to
 * the ciphertext: that is what stops two rows holding the same secret from looking the same, which
 * would otherwise leak that two users share a token without either being decrypted.
 *
 * <p>Shared by everything in this runtime that stores a secret — the shell credential store and the
 * per-user model registry — so that there is one implementation to get right rather than one per
 * caller. Each caller brings its own key, because the blast radius of a leaked key should be the
 * one feature it belongs to.
 *
 * <p>Thread-safe: {@link SecureRandom} and {@link SecretKeySpec} both are, and a {@link Cipher} is
 * created per call rather than held, since a Cipher is not.
 */
public class AesGcmSealer {

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  /**
   * @param base64Key a base64-encoded AES key of 128, 192 or 256 bits.
   * @param what what the key is for, named in the exception when it is unusable — there is more
   *     than one such key in this runtime now, and "the encryption key is not valid base64" does
   *     not say which one to go and fix.
   * @throws IllegalArgumentException when the key is absent or unusable. Rejected outright rather
   *     than fallen back on, because the only fallback is storing secrets in the clear.
   */
  public AesGcmSealer(final String base64Key, final String what) {
    if (base64Key == null || base64Key.isBlank()) {
      throw new IllegalArgumentException("A base64-encoded AES key is required to store " + what);
    }
    final byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(base64Key.trim());
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException("The " + what + " encryption key is not valid base64", e);
    }
    if (bytes.length != 16 && bytes.length != 24 && bytes.length != 32) {
      throw new IllegalArgumentException(
          "The "
              + what
              + " encryption key must decode to 16, 24 or 32 bytes, but was "
              + bytes.length);
    }
    this.key = new SecretKeySpec(bytes, "AES");
  }

  /** The sealed form of {@code plaintext}, base64-encoded and safe to store. */
  public String seal(final String plaintext) {
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
      throw new SealingException("Failed to encrypt the secret", e);
    }
  }

  /**
   * The plaintext behind {@code stored}.
   *
   * @param what names the secret in the failure message, so that a rotated key is a line naming
   *     what could not be read rather than a bare decryption error.
   * @throws SealingException when the stored value is truncated or the key no longer matches.
   *     Loudly rather than by returning null: a rotated or mistyped key would otherwise show up as
   *     a feature that quietly has no secrets in it.
   */
  public String open(final String what, final String stored) {
    try {
      final var raw = Base64.getDecoder().decode(stored);
      if (raw.length <= NONCE_BYTES) {
        throw new SealingException("Stored secret " + what + " is truncated");
      }
      final var nonce = Arrays.copyOfRange(raw, 0, NONCE_BYTES);
      final var sealed = Arrays.copyOfRange(raw, NONCE_BYTES, raw.length);
      final var cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
    } catch (final GeneralSecurityException | IllegalArgumentException e) {
      throw new SealingException(
          "Failed to decrypt " + what + "; the encryption key may have changed", e);
    }
  }

  /** A secret could not be sealed or opened. */
  public static class SealingException extends RuntimeException {
    public SealingException(final String message) {
      super(message);
    }

    public SealingException(final String message, final Throwable cause) {
      super(message, cause);
    }
  }
}
