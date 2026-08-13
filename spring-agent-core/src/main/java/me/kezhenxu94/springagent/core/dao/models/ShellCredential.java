package me.kezhenxu94.springagent.core.dao.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One credential a user has stored for their shell sandbox. Dual-mapped for both persistence
 * backends; see {@link ScheduledTask} for why.
 *
 * <p>{@link #value} is ciphertext, and this class neither encrypts nor decrypts it — that belongs
 * to the store that owns the key. Nothing that reads a row can recover the secret without it.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@CompoundIndex(name = "owner_credential_unique", def = "{'ownerId': 1, 'name': 1}", unique = true)
@Document(collection = ShellCredential.COLLECTION_NAME)
@Entity
@Table(
    name = ShellCredential.COLLECTION_NAME,
    // The JPA counterpart of the @CompoundIndex above.
    uniqueConstraints =
        @UniqueConstraint(
            name = "owner_credential_unique",
            columnNames = {"ownerId", "name"}))
public class ShellCredential {
  public static final String COLLECTION_NAME = "shell_credentials";

  /**
   * {@code ownerId + ':' + name}, so that storing the same name twice replaces the row rather than
   * racing the unique constraint. Built by {@link #idFor}; never assign it by hand.
   */
  @Id @jakarta.persistence.Id private String id;

  private String ownerId;
  private String name;

  /**
   * The encrypted value, base64-encoded. Long enough for the 64KiB ceiling the tools impose, plus
   * the expansion base64 and the nonce add.
   */
  @Column(length = 131072)
  private String value;

  private Instant updatedAt;

  public static String idFor(final String ownerId, final String name) {
    return ownerId + ':' + name;
  }
}
